#!/usr/bin/env python3
"""Validate the repository's machine-readable documentation authority."""

from __future__ import annotations

import json
from pathlib import Path
from typing import Any

ROOT = Path(__file__).resolve().parents[2]
AUTHORITY = ROOT / "docs" / "contracts" / "documentation-authority-v1.json"
REQUIRED_SOURCE_KEYS = {"id", "path", "kind", "status"}
REQUIRED_CLAIM_KEYS = {"id", "authority", "evidence"}


class AuthorityError(ValueError):
    """Raised when the documentation authority is inconsistent."""


def _unique_ids(entries: list[dict[str, Any]], kind: str) -> None:
    ids = [entry.get("id") for entry in entries]
    if any(not isinstance(identifier, str) or not identifier for identifier in ids):
        raise AuthorityError(f"{kind} entries need non-empty string ids")
    if len(ids) != len(set(ids)):
        raise AuthorityError(f"{kind} ids must be unique")


def _repository_path(raw: Any, field: str) -> Path:
    if not isinstance(raw, str) or not raw or Path(raw).is_absolute():
        raise AuthorityError(f"{field} must be a repository-relative path")
    path = (ROOT / raw).resolve()
    if ROOT not in path.parents and path != ROOT:
        raise AuthorityError(f"{field} escapes the repository: {raw}")
    return path


def _read_authority(path: Path) -> dict[str, Any]:
    try:
        document = json.loads(path.read_text(encoding="utf-8"))
    except (OSError, json.JSONDecodeError) as error:
        raise AuthorityError(f"cannot read authority: {error}") from error
    if not isinstance(document, dict) or document.get("schema_version") != 1:
        raise AuthorityError("authority schema_version must be 1")
    return document


def _validate_sources(sources: list[dict[str, Any]]) -> set[str]:
    _unique_ids(sources, "source")
    source_ids = {entry["id"] for entry in sources}
    for source in sources:
        if not source.keys() >= REQUIRED_SOURCE_KEYS:
            raise AuthorityError(f"source {source.get('id', '<unknown>')} is incomplete")
        if source["status"] not in {"authoritative", "generated"}:
            raise AuthorityError(f"source {source['id']} has invalid status")
        if not _repository_path(source["path"], f"source {source['id']} path").exists():
            raise AuthorityError(f"source {source['id']} path does not exist")
    return source_ids


def _validate_claims(claims: list[dict[str, Any]], source_ids: set[str]) -> None:
    _unique_ids(claims, "claim")
    for claim in claims:
        if not claim.keys() >= REQUIRED_CLAIM_KEYS:
            raise AuthorityError(f"claim {claim.get('id', '<unknown>')} is incomplete")
        if claim["authority"] not in source_ids:
            raise AuthorityError(f"claim {claim['id']} references unknown authority")
        evidence = claim["evidence"]
        if not isinstance(evidence, list) or not evidence:
            raise AuthorityError(f"claim {claim['id']} needs evidence")
        for path in evidence:
            if not _repository_path(path, f"claim {claim['id']} evidence").exists():
                raise AuthorityError(f"claim {claim['id']} evidence does not exist: {path}")


def validate_authority(document: dict[str, Any]) -> dict[str, Any]:
    sources = document.get("sources")
    claims = document.get("claims")
    if not isinstance(sources, list) or not isinstance(claims, list):
        raise AuthorityError("authority needs sources and claims lists")
    if any(not isinstance(entry, dict) for entry in sources + claims):
        raise AuthorityError("authority entries must be objects")
    _validate_claims(claims, _validate_sources(sources))
    return document


def load_authority(path: Path = AUTHORITY) -> dict[str, Any]:
    return validate_authority(_read_authority(path))


def main() -> int:
    document = load_authority()
    print(
        f"documentation authority: {len(document['sources'])} sources, {len(document['claims'])} claims"
    )
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
