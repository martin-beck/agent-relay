#!/usr/bin/env python3
# Copyright (C) Huawei Technologies Co., Ltd. 2026. All rights reserved.
# SPDX-License-Identifier: MIT

"""Delete only independently reviewed, exact GitHub Actions evidence IDs."""

from __future__ import annotations

import argparse
import json
import os
import re
import sys
import urllib.error
import urllib.request
from pathlib import Path
from typing import Any, Protocol

API_ROOT = "https://api.github.com"
CONFIRMATION = "DELETE_EXACT_ACTIONS_EVIDENCE"


class CleanupError(RuntimeError):
    """The manifest or a live evidence check is unsafe."""


def positive_id(value: Any, label: str) -> int:
    """Return one positive integer ID and reject ambiguous input."""
    if isinstance(value, bool) or not isinstance(value, int) or value <= 0:
        raise CleanupError(f"{label} must be a positive integer")
    return value


def read_manifest(path: Path) -> dict[str, Any]:
    """Read the explicit cleanup manifest as an object."""
    try:
        manifest = json.loads(path.read_text(encoding="utf-8"))
    except (OSError, json.JSONDecodeError) as error:
        raise CleanupError("cleanup manifest is not readable JSON") from error
    if not isinstance(manifest, dict):
        raise CleanupError("cleanup manifest must be an object")
    return manifest


def validate_replacements(replacements: Any) -> set[int]:
    """Validate independently verified replacement runs."""
    if not isinstance(replacements, list):
        raise CleanupError("replacement_evidence must be a list")
    replacement_ids: set[int] = set()
    for index, replacement in enumerate(replacements):
        if not isinstance(replacement, dict):
            raise CleanupError(f"replacement {index} must be an object")
        run_id = positive_id(replacement.get("run_id"), f"replacement {index} run_id")
        if replacement.get("verified") is not True:
            raise CleanupError(f"replacement {run_id} must be independently verified")
        head_sha = replacement.get("head_sha")
        if not isinstance(head_sha, str) or re.fullmatch(r"[0-9a-f]{40}", head_sha) is None:
            raise CleanupError(f"replacement {run_id} head_sha must be a full SHA")
        if run_id in replacement_ids:
            raise CleanupError(f"duplicate replacement run_id {run_id}")
        replacement_ids.add(run_id)
    return replacement_ids


def validate_targets(targets: Any, replacement_ids: set[int]) -> None:
    """Validate exact deletion targets and their replacement linkage."""
    if not isinstance(targets, list):
        raise CleanupError("targets must be a list")
    target_ids: set[tuple[str, int]] = set()
    for index, target in enumerate(targets):
        if not isinstance(target, dict):
            raise CleanupError(f"target {index} must be an object")
        kind = target.get("kind")
        if kind not in {"run", "artifact"}:
            raise CleanupError(f"target {index} kind must be run or artifact")
        target_id = positive_id(target.get("id"), f"target {index} id")
        key = (kind, target_id)
        if key in target_ids:
            raise CleanupError(f"duplicate target {kind} {target_id}")
        target_ids.add(key)
        for field in ("evidence_role", "privacy_classification"):
            value = target.get(field)
            if not isinstance(value, str) or not value.strip():
                raise CleanupError(f"target {kind} {target_id} requires {field}")
        if target.get("protection_decision") != "delete-after-replacement":
            raise CleanupError(f"target {kind} {target_id} is not approved for deletion")
        replacement_id = positive_id(
            target.get("replacement_run_id"), f"target {kind} {target_id} replacement_run_id"
        )
        if replacement_id not in replacement_ids:
            raise CleanupError(f"target {kind} {target_id} references unknown replacement")


def load_manifest(path: Path) -> dict[str, Any]:
    """Load and validate the explicit cleanup manifest."""
    manifest = read_manifest(path)
    if manifest.get("schema_version") != 1:
        raise CleanupError("cleanup manifest schema_version must be 1")
    repository = manifest.get("repository")
    if (
        not isinstance(repository, str)
        or re.fullmatch(r"[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+", repository) is None
    ):
        raise CleanupError("cleanup manifest repository must use owner/name syntax")
    replacement_ids = validate_replacements(manifest.get("replacement_evidence"))
    validate_targets(manifest.get("targets"), replacement_ids)
    return manifest


class EvidenceDeleter(Protocol):
    """Protocol for exact deletion clients and deterministic tests."""

    def delete_exact(self, kind: str, target_id: int) -> None:
        """Delete one exact target."""

    def replacement_run(self, run_id: int) -> dict[str, Any] | None:
        """Read one replacement run."""


class GitHubClient:
    """Minimal fixed-origin client for exact evidence operations."""

    def __init__(self, repository: str, token: str) -> None:
        if not token:
            raise CleanupError("GITHUB_TOKEN is required")
        self.repository = repository
        self.token = token

    def request(self, path: str, method: str = "GET") -> Any:
        request = urllib.request.Request(  # noqa: S310 - URL is fixed to GitHub below
            f"{API_ROOT}{path}",
            method=method,
            headers={
                "Accept": "application/vnd.github+json",
                "Authorization": f"Bearer {self.token}",
                "X-GitHub-Api-Version": "2022-11-28",
            },
        )
        try:
            with urllib.request.urlopen(request, timeout=30) as response:  # noqa: S310
                if response.status == 204:
                    return None
                return json.load(response)
        except urllib.error.HTTPError as error:
            if error.code == 404:
                return None
            raise CleanupError("GitHub evidence request failed") from error
        except (urllib.error.URLError, TimeoutError) as error:
            raise CleanupError("GitHub evidence request failed") from error

    def target_path(self, kind: str, target_id: int) -> str:
        """Build a path only from validated kind and integer ID."""
        if kind == "run":
            return f"/repos/{self.repository}/actions/runs/{target_id}"
        if kind == "artifact":
            return f"/repos/{self.repository}/actions/artifacts/{target_id}"
        raise CleanupError("unsupported evidence kind")

    def replacement_run(self, run_id: int) -> dict[str, Any] | None:
        """Read one exact replacement run."""
        value = self.request(self.target_path("run", run_id))
        return value if isinstance(value, dict) else None

    def delete_exact(self, kind: str, target_id: int) -> None:
        """Delete one exact target after the caller has validated its presence."""
        path = self.target_path(kind, target_id)
        if self.request(path) is None:
            raise CleanupError(f"target {kind} {target_id} is already absent")
        self.request(path, method="DELETE")
        if self.request(path) is not None:
            raise CleanupError(f"target {kind} {target_id} remains present after deletion")


def apply_manifest(
    manifest: dict[str, Any], client: EvidenceDeleter, confirmation: str
) -> list[dict[str, int | str]]:
    """Verify and delete exactly the manifest targets in listed order."""
    if confirmation != CONFIRMATION:
        raise CleanupError(f"apply requires exact confirmation {CONFIRMATION}")
    replacement_ids = {
        positive_id(item["run_id"], "replacement run_id")
        for item in manifest["replacement_evidence"]
    }
    for replacement in manifest["replacement_evidence"]:
        run_id = positive_id(replacement["run_id"], "replacement run_id")
        live = client.replacement_run(run_id)
        if (
            live is None
            or live.get("head_sha") != replacement["head_sha"]
            or live.get("status") != "completed"
            or live.get("conclusion") != "success"
        ):
            raise CleanupError(f"replacement run {run_id} is not exact terminal success")
    deleted: list[dict[str, int | str]] = []
    for target in manifest["targets"]:
        kind = str(target["kind"])
        target_id = positive_id(target["id"], f"target {kind} id")
        if kind == "run" and target_id in replacement_ids:
            raise CleanupError(f"replacement run {target_id} cannot be deleted")
        client.delete_exact(kind, target_id)
        deleted.append({"kind": kind, "id": target_id})
    return deleted


def main(argv: list[str] | None = None) -> int:
    """Validate a manifest and optionally apply its exact deletions."""
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--manifest", type=Path, required=True)
    parser.add_argument("--apply", action="store_true")
    parser.add_argument("--confirm", default="")
    args = parser.parse_args(argv)
    manifest = load_manifest(args.manifest)
    client = GitHubClient(manifest["repository"], os.environ.get("GITHUB_TOKEN", ""))
    deleted = apply_manifest(manifest, client, args.confirm) if args.apply else []
    print(
        json.dumps(
            {"mode": "apply" if args.apply else "verify", "deleted": deleted}, sort_keys=True
        )
    )
    return 0


if __name__ == "__main__":
    try:
        raise SystemExit(main())
    except CleanupError as error:
        print(f"actions evidence cleanup error: {error}", file=sys.stderr)
        raise SystemExit(2) from error
