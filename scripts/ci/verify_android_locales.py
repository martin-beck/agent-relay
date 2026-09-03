#!/usr/bin/env python3
"""Verify that every declared Android locale has a complete, compatible catalog."""

from __future__ import annotations

import argparse
import collections
import re
import sys
import xml.etree.ElementTree as ElementTree
from dataclasses import dataclass
from pathlib import Path

FORMAT_ARGUMENT = re.compile(
    r"(?<!%)%(?!%)(?:(?P<position>[1-9][0-9]*)\$)?[-#+ 0,(]*[0-9]*(?:\.[0-9]+)?(?P<kind>[a-zA-Z])"
)

PLURAL_QUANTITIES_BY_LANGUAGE = {
    "ar": frozenset({"zero", "one", "two", "few", "many", "other"}),
    "bn": frozenset({"one", "other"}),
    "de": frozenset({"one", "other"}),
    "en": frozenset({"one", "other"}),
    "es": frozenset({"one", "many", "other"}),
    "fr": frozenset({"one", "many", "other"}),
    "hi": frozenset({"one", "other"}),
    "id": frozenset({"other"}),
    "it": frozenset({"one", "many", "other"}),
    "ja": frozenset({"other"}),
    "pt": frozenset({"one", "many", "other"}),
    "ru": frozenset({"one", "few", "many", "other"}),
    "zh": frozenset({"other"}),
}


class LocaleError(RuntimeError):
    """The locale declaration or one of its Android resources is invalid."""


@dataclass(frozen=True)
class Resource:
    kind: str
    name: str
    variants: tuple[tuple[str, str], ...]

    @property
    def format_arguments(self) -> dict[str, collections.Counter[tuple[str, str]]]:
        arguments_by_variant: dict[str, collections.Counter[tuple[str, str]]] = {}
        for variant, value in self.variants:
            arguments: collections.Counter[tuple[str, str]] = collections.Counter()
            implicit_position = 1
            for match in FORMAT_ARGUMENT.finditer(value):
                position = match.group("position")
                if position is None:
                    position = str(implicit_position)
                    implicit_position += 1
                arguments[(position, match.group("kind").lower())] += 1
            arguments_by_variant[variant] = arguments
        return arguments_by_variant


def read_locale_map(path: Path) -> dict[str, str]:
    locales: dict[str, str] = {}
    directories: set[str] = set()
    for line_number, raw_line in enumerate(path.read_text(encoding="utf-8").splitlines(), 1):
        line = raw_line.strip()
        if not line or line.startswith("#"):
            continue
        try:
            tag, directory = (part.strip() for part in line.split("=", 1))
        except ValueError as failure:
            raise LocaleError(
                f"{path}:{line_number}: expected language-tag=values-directory"
            ) from failure
        if not tag or not directory.startswith("values"):
            raise LocaleError(f"{path}:{line_number}: invalid locale mapping")
        if tag in locales:
            raise LocaleError(f"{path}:{line_number}: duplicate locale tag {tag}")
        if directory in directories:
            raise LocaleError(f"{path}:{line_number}: duplicate resource directory {directory}")
        locales[tag] = directory
        directories.add(directory)
    if not locales:
        raise LocaleError(f"{path}: no locales declared")
    return locales


def element_text(element: ElementTree.Element) -> str:
    return "".join(element.itertext()).strip()


def read_resource_variants(
    path: Path,
    element: ElementTree.Element,
    name: str,
) -> tuple[tuple[str, str], ...]:
    if element.tag == "string":
        return (("", element_text(element)),)
    variants: list[tuple[str, str]] = []
    seen_quantities: set[str] = set()
    for item in element.findall("item"):
        quantity = item.attrib.get("quantity", "").strip()
        if quantity not in {"zero", "one", "two", "few", "many", "other"}:
            raise LocaleError(f"{path}: plurals/{name} has invalid quantity {quantity!r}")
        if quantity in seen_quantities:
            raise LocaleError(f"{path}: plurals/{name} repeats quantity {quantity}")
        seen_quantities.add(quantity)
        variants.append((quantity, element_text(item)))
    if "other" not in seen_quantities:
        raise LocaleError(f"{path}: plurals/{name} must declare quantity other")
    return tuple(variants)


def read_catalog(path: Path) -> dict[tuple[str, str], Resource]:
    try:
        root = ElementTree.parse(path).getroot()
    except (ElementTree.ParseError, OSError) as failure:
        raise LocaleError(f"{path}: could not parse Android resources") from failure
    if root.tag != "resources":
        raise LocaleError(f"{path}: root element must be resources")
    catalog: dict[tuple[str, str], Resource] = {}
    for element in root:
        if element.tag not in {"string", "plurals"}:
            continue
        name = element.attrib.get("name", "").strip()
        if not name:
            raise LocaleError(f"{path}: unnamed {element.tag} resource")
        if element.attrib.get("translatable") == "false":
            continue
        variants = read_resource_variants(path, element, name)
        if not variants or any(not value for _, value in variants):
            raise LocaleError(f"{path}: {element.tag}/{name} is empty")
        key = (element.tag, name)
        if key in catalog:
            raise LocaleError(f"{path}: duplicate {element.tag}/{name}")
        catalog[key] = Resource(element.tag, name, variants)
    return catalog


def verify_plural_quantities(
    path: Path,
    key: tuple[str, str],
    language_tag: str,
    resource: Resource,
) -> None:
    if resource.kind != "plurals":
        return
    language = language_tag.split("-", maxsplit=1)[0]
    expected = PLURAL_QUANTITIES_BY_LANGUAGE.get(language)
    if expected is None:
        raise LocaleError(f"{path}: no plural quantity rules declared for {language_tag}")
    actual = {variant for variant, _ in resource.variants}
    missing = sorted(expected - actual)
    unused = sorted(actual - expected)
    if not missing and not unused:
        return
    kind, name = key
    details: list[str] = []
    if missing:
        details.append("missing quantities " + ", ".join(missing))
    if unused:
        details.append("unused quantities " + ", ".join(unused))
    raise LocaleError(f"{path}: {kind}/{name} " + "; ".join(details))


def verify_resource_arguments(
    path: Path,
    key: tuple[str, str],
    language_tag: str,
    default_resource: Resource,
    localized_resource: Resource,
) -> None:
    localized_arguments = localized_resource.format_arguments
    default_arguments = default_resource.format_arguments
    verify_plural_quantities(path, key, language_tag, localized_resource)
    for variant, arguments in localized_arguments.items():
        expected_arguments = default_arguments.get(variant, default_arguments.get("other"))
        if arguments == expected_arguments:
            continue
        kind, name = key
        suffix = f"/{variant}" if variant else ""
        raise LocaleError(f"{path}: format arguments differ for {kind}/{name}{suffix}")


def verify_catalogs(
    resource_root: Path,
    locale_map: dict[str, str],
) -> dict[str, int]:
    default_directory = locale_map.get("en-US")
    if default_directory != "values":
        raise LocaleError("en-US must map to the unqualified values directory")
    default_path = resource_root / default_directory / "strings.xml"
    default_catalog = read_catalog(default_path)
    counts = {"en-US": len(default_catalog)}
    for key, resource in default_catalog.items():
        verify_plural_quantities(default_path, key, "en-US", resource)
    default_keys = set(default_catalog)
    for tag, directory in locale_map.items():
        if tag == "en-US":
            continue
        path = resource_root / directory / "strings.xml"
        catalog = read_catalog(path)
        keys = set(catalog)
        missing = sorted(default_keys - keys)
        extra = sorted(keys - default_keys)
        if missing or extra:
            details: list[str] = []
            if missing:
                details.append("missing " + ", ".join(f"{kind}/{name}" for kind, name in missing))
            if extra:
                details.append("extra " + ", ".join(f"{kind}/{name}" for kind, name in extra))
            raise LocaleError(f"{path}: " + "; ".join(details))
        for key, default_resource in default_catalog.items():
            verify_resource_arguments(path, key, tag, default_resource, catalog[key])
        counts[tag] = len(catalog)
    return counts


def verify_default_locale(resources_properties: Path) -> None:
    settings = {}
    for raw_line in resources_properties.read_text(encoding="utf-8").splitlines():
        line = raw_line.strip()
        if line and not line.startswith("#"):
            key, separator, value = line.partition("=")
            if not separator:
                raise LocaleError(f"{resources_properties}: invalid property line")
            settings[key.strip()] = value.strip()
    if settings.get("unqualifiedResLocale") != "en-US":
        raise LocaleError(f"{resources_properties}: unqualifiedResLocale must be en-US")


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--root", type=Path, default=Path.cwd())
    arguments = parser.parse_args()
    resource_root = arguments.root / "app/src/main/res"
    try:
        locale_map = read_locale_map(arguments.root / "config/android-locales.txt")
        verify_default_locale(resource_root / "resources.properties")
        counts = verify_catalogs(resource_root, locale_map)
    except (LocaleError, OSError) as failure:
        print(f"Android locale verification failed: {failure}", file=sys.stderr)
        return 1
    summary = ", ".join(f"{tag}={count}" for tag, count in counts.items())
    print(f"Android locale resources are complete: {summary}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
