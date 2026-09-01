#!/usr/bin/env python3
"""Fail CI unless connected Android tests produced complete, clean JUnit evidence."""

from __future__ import annotations

import argparse
from dataclasses import dataclass
from pathlib import Path
import sys
import xml.etree.ElementTree as ElementTree

RESULT_GLOB = "**/build/outputs/androidTest-results/connected/**/TEST-*.xml"


class EvidenceError(RuntimeError):
    """Connected-test evidence is absent, malformed, incomplete, or failing."""


@dataclass
class TestCounts:
    tests: int = 0
    failures: int = 0
    errors: int = 0
    skipped: int = 0
    reports: int = 0

    @property
    def executed(self) -> int:
        return self.tests - self.skipped

    def add(self, other: "TestCounts") -> None:
        self.tests += other.tests
        self.failures += other.failures
        self.errors += other.errors
        self.skipped += other.skipped
        self.reports += other.reports


def _integer(element: ElementTree.Element, name: str, path: Path) -> int:
    try:
        value = int(element.attrib.get(name, "0"))
    except ValueError as failure:
        raise EvidenceError(f"{path}: invalid {name} count") from failure
    if value < 0:
        raise EvidenceError(f"{path}: negative {name} count")
    return value


def _module_name(root: Path, report: Path) -> str:
    relative = report.resolve().relative_to(root.resolve())
    try:
        build_index = relative.parts.index("build")
    except ValueError as failure:
        raise EvidenceError(f"{relative}: report is outside a module build directory") from failure
    module = "/".join(relative.parts[:build_index])
    if not module:
        raise EvidenceError(f"{relative}: report has no owning module")
    return module


def collect_evidence(root: Path) -> dict[str, TestCounts]:
    reports = sorted(root.glob(RESULT_GLOB))
    if not reports:
        raise EvidenceError(f"no connected-test XML matched {RESULT_GLOB}")
    modules: dict[str, TestCounts] = {}
    for report in reports:
        try:
            document = ElementTree.parse(report).getroot()
        except (ElementTree.ParseError, OSError) as failure:
            raise EvidenceError(f"{report}: JUnit XML could not be parsed") from failure
        suites = [document] if document.tag == "testsuite" else list(document.findall("./testsuite"))
        if document.tag not in {"testsuite", "testsuites"} or not suites:
            raise EvidenceError(f"{report}: JUnit document contains no test suite")
        module_counts = modules.setdefault(_module_name(root, report), TestCounts())
        for suite in suites:
            counts = TestCounts(
                tests=_integer(suite, "tests", report),
                failures=_integer(suite, "failures", report),
                errors=_integer(suite, "errors", report),
                skipped=_integer(suite, "skipped", report),
                reports=1,
            )
            if counts.skipped > counts.tests:
                raise EvidenceError(f"{report}: skipped count exceeds test count")
            module_counts.add(counts)
    return modules


def verify_evidence(
    root: Path,
    required_modules: set[str],
    minimum_tests: int,
    minimum_executed: int,
) -> dict[str, TestCounts]:
    modules = collect_evidence(root)
    missing = sorted(required_modules - modules.keys())
    if missing:
        raise EvidenceError("missing connected-test evidence for: " + ", ".join(missing))
    total = TestCounts()
    for counts in modules.values():
        total.add(counts)
    if total.tests < minimum_tests:
        raise EvidenceError(f"only {total.tests} tests were reported; expected at least {minimum_tests}")
    if total.executed < minimum_executed:
        raise EvidenceError(f"only {total.executed} tests executed; expected at least {minimum_executed}")
    if total.failures or total.errors:
        raise EvidenceError(f"JUnit evidence contains {total.failures} failures and {total.errors} errors")
    return modules


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--root", type=Path, default=Path.cwd())
    parser.add_argument("--require-module", action="append", default=[])
    parser.add_argument("--minimum-tests", type=int, default=1)
    parser.add_argument("--minimum-executed", type=int, default=1)
    arguments = parser.parse_args()
    if arguments.minimum_tests < 1 or arguments.minimum_executed < 1:
        parser.error("minimum counts must be positive")
    try:
        modules = verify_evidence(
            arguments.root,
            set(arguments.require_module),
            arguments.minimum_tests,
            arguments.minimum_executed,
        )
    except EvidenceError as failure:
        print(f"Connected-test evidence is invalid: {failure}", file=sys.stderr)
        return 1
    total = TestCounts()
    for module, counts in sorted(modules.items()):
        total.add(counts)
        print(
            f"{module}: {counts.tests} tests, {counts.executed} executed, "
            f"{counts.skipped} skipped, {counts.reports} reports",
        )
    print(
        f"Connected-test evidence is clean: {total.tests} tests, "
        f"{total.executed} executed, {total.skipped} skipped",
    )
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
