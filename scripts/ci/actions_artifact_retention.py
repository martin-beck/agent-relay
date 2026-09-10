#!/usr/bin/env python3
# Copyright (C) Huawei Technologies Co., Ltd. 2026. All rights reserved.
# SPDX-License-Identifier: MIT

"""Plan and explicitly apply bounded GitHub Actions artifact retention."""

from __future__ import annotations

import argparse
import dataclasses
import datetime as dt
import json
import os
import re
import sys
import urllib.error
import urllib.parse
import urllib.request
from pathlib import Path
from typing import Any

API_ROOT = "https://api.github.com"
DELETE_CONFIRMATION = "DELETE_ACTIONS_ARTIFACTS"
MAX_ALLOWED_DELETIONS = 100
PROTECTED_ROLE_TOKENS = frozenset(
    {"attestation", "pages", "provenance", "publication", "release", "sbom"}
)
DIAGNOSTIC_ROLE_TOKENS = frozenset(
    {"actual", "corpus", "diagnostic", "diff", "evidence", "report", "sarif", "test", "ui"}
)


class RetentionError(RuntimeError):
    """The requested artifact operation is invalid or unsafe."""


def parse_time(value: str) -> dt.datetime:
    """Parse a GitHub timestamp as an aware UTC datetime."""
    parsed = dt.datetime.fromisoformat(value.replace("Z", "+00:00"))
    if parsed.tzinfo is None:
        raise RetentionError(f"timestamp lacks a timezone: {value}")
    return parsed.astimezone(dt.UTC)


@dataclasses.dataclass(frozen=True)
class Artifact:
    """Bounded artifact metadata used by the pure retention policy."""

    artifact_id: int
    name: str
    size_bytes: int
    created_at: dt.datetime
    expires_at: dt.datetime
    expired: bool
    run_id: int | None
    head_sha: str | None

    @classmethod
    def from_api(cls, value: dict[str, Any]) -> Artifact:
        """Validate and convert one GitHub artifact response."""
        workflow_run = value.get("workflow_run")
        if workflow_run is not None and not isinstance(workflow_run, dict):
            raise RetentionError("artifact workflow_run must be an object or null")
        run_id = workflow_run.get("id") if workflow_run else None
        head_sha = workflow_run.get("head_sha") if workflow_run else None
        if run_id is not None and not isinstance(run_id, int):
            raise RetentionError("artifact run id must be an integer")
        if head_sha is not None and not isinstance(head_sha, str):
            raise RetentionError("artifact head SHA must be a string")
        artifact_id = value.get("id")
        name = value.get("name")
        size_bytes = value.get("size_in_bytes")
        expired = value.get("expired")
        if not isinstance(artifact_id, int) or artifact_id <= 0:
            raise RetentionError("artifact id must be a positive integer")
        if not isinstance(name, str) or not name.strip():
            raise RetentionError("artifact name must be nonempty")
        if not isinstance(size_bytes, int) or size_bytes < 0:
            raise RetentionError("artifact size must be a nonnegative integer")
        if not isinstance(expired, bool):
            raise RetentionError("artifact expired flag must be boolean")
        return cls(
            artifact_id=artifact_id,
            name=name,
            size_bytes=size_bytes,
            created_at=parse_time(str(value.get("created_at"))),
            expires_at=parse_time(str(value.get("expires_at"))),
            expired=expired,
            run_id=run_id,
            head_sha=head_sha,
        )


@dataclasses.dataclass(frozen=True)
class RetentionContext:
    """Live references that artifacts must not outlive accidentally."""

    current_main_sha: str
    open_pr_heads: frozenset[str]
    active_run_ids: frozenset[int]


@dataclasses.dataclass(frozen=True)
class RetentionPolicy:
    """Reviewable quota and evidence-retention policy."""

    quota_bytes: int
    high_watermark_percent: int = 90
    low_watermark_percent: int = 75
    minimum_age_days: int = 14
    diagnostic_protection_days: int = 30
    max_deletions: int = 25

    def validate(self) -> None:
        """Reject unsafe or contradictory policy bounds."""
        if self.quota_bytes <= 0:
            raise RetentionError("quota bytes must be positive")
        if not 0 < self.low_watermark_percent < self.high_watermark_percent <= 100:
            raise RetentionError("watermarks must satisfy 0 < low < high <= 100")
        if self.minimum_age_days < 1 or self.diagnostic_protection_days < 1:
            raise RetentionError("retention ages must be positive")
        if not 1 <= self.max_deletions <= MAX_ALLOWED_DELETIONS:
            raise RetentionError(f"max deletions must be between 1 and {MAX_ALLOWED_DELETIONS}")


@dataclasses.dataclass(frozen=True)
class Classification:
    """One deterministic retention decision before quota selection."""

    artifact: Artifact
    role: str
    protected_reasons: tuple[str, ...]
    candidate_reasons: tuple[str, ...]


def artifact_role(name: str) -> str:
    """Classify an artifact role conservatively from its public name."""
    tokens = set(re.findall(r"[a-z0-9]+", name.lower()))
    if tokens & PROTECTED_ROLE_TOKENS:
        return "publication"
    if tokens & DIAGNOSTIC_ROLE_TOKENS:
        return "diagnostic"
    if "doc" in tokens or "documentation" in tokens or "site" in tokens:
        return "documentation"
    return "build"


def duplicate_artifact_ids(artifacts: tuple[Artifact, ...]) -> frozenset[int]:
    """Identify older artifacts with the same name and source head."""
    newest: dict[tuple[str, str], Artifact] = {}
    duplicate_ids: set[int] = set()
    ordered = sorted(artifacts, key=lambda item: (item.created_at, item.artifact_id), reverse=True)
    for artifact in ordered:
        if artifact.head_sha is None:
            continue
        key = (artifact.name, artifact.head_sha)
        if key in newest:
            duplicate_ids.add(artifact.artifact_id)
        else:
            newest[key] = artifact
    return frozenset(duplicate_ids)


def protection_reasons(
    artifact: Artifact,
    role: str,
    context: RetentionContext,
    policy: RetentionPolicy,
    now: dt.datetime,
) -> tuple[str, ...]:
    """Return all reasons an artifact must be retained."""
    reasons: list[str] = []
    age = now - artifact.created_at
    if artifact.run_id is None or artifact.head_sha is None:
        reasons.append("unknown-provenance")
    if artifact.run_id in context.active_run_ids:
        reasons.append("active-run")
    if artifact.head_sha == context.current_main_sha:
        reasons.append("current-main")
    if artifact.head_sha in context.open_pr_heads:
        reasons.append("open-pr-head")
    if role == "publication":
        reasons.append("release-or-publication")
    if not artifact.expired and age < dt.timedelta(days=policy.minimum_age_days):
        reasons.append("recent")
    if role == "diagnostic" and age < dt.timedelta(days=policy.diagnostic_protection_days):
        reasons.append("recent-diagnostic")
    return tuple(sorted(set(reasons)))


def candidate_reasons(
    artifact: Artifact,
    duplicate_ids: frozenset[int],
    policy: RetentionPolicy,
    now: dt.datetime,
) -> tuple[str, ...]:
    """Return deterministic reasons an unprotected artifact may be removed."""
    reasons: list[str] = []
    age = now - artifact.created_at
    if artifact.expired or artifact.expires_at <= now:
        reasons.append("expired")
    if artifact.artifact_id in duplicate_ids:
        reasons.append("duplicate-head-artifact")
    if age >= dt.timedelta(days=policy.minimum_age_days):
        reasons.append("older-than-minimum-age")
    return tuple(sorted(set(reasons)))


def classify_artifacts(
    artifacts: tuple[Artifact, ...],
    context: RetentionContext,
    policy: RetentionPolicy,
    now: dt.datetime,
) -> tuple[Classification, ...]:
    """Classify artifacts without performing external effects."""
    policy.validate()
    duplicates = duplicate_artifact_ids(artifacts)
    results = []
    for artifact in artifacts:
        role = artifact_role(artifact.name)
        results.append(
            Classification(
                artifact=artifact,
                role=role,
                protected_reasons=protection_reasons(artifact, role, context, policy, now),
                candidate_reasons=candidate_reasons(artifact, duplicates, policy, now),
            )
        )
    return tuple(results)


def selection_key(item: Classification) -> tuple[int, int, int, int, int]:
    """Rank expired and duplicate artifacts before older and larger ones."""
    reasons = set(item.candidate_reasons)
    role_rank = {"build": 0, "documentation": 1, "diagnostic": 2}.get(item.role, 3)
    return (
        0 if "expired" in reasons else 1,
        0 if "duplicate-head-artifact" in reasons else 1,
        role_rank,
        -int(item.artifact.created_at.timestamp()),
        -item.artifact.size_bytes,
    )


def plan_retention(
    artifacts: tuple[Artifact, ...],
    context: RetentionContext,
    policy: RetentionPolicy,
    now: dt.datetime,
) -> dict[str, Any]:
    """Build a deterministic dry-run plan with quota hysteresis."""
    classified = classify_artifacts(artifacts, context, policy, now)
    total_bytes = sum(item.artifact.size_bytes for item in classified)
    high_bytes = policy.quota_bytes * policy.high_watermark_percent // 100
    low_bytes = policy.quota_bytes * policy.low_watermark_percent // 100
    quota_pressure = total_bytes >= high_bytes
    reclaim_target = max(0, total_bytes - low_bytes) if quota_pressure else 0
    eligible = [
        item for item in classified if not item.protected_reasons and item.candidate_reasons
    ]
    ordered = sorted(eligible, key=selection_key)
    selected: set[int] = set()
    reclaimed = 0
    for item in ordered:
        expired = "expired" in item.candidate_reasons
        if len(selected) >= policy.max_deletions:
            break
        if not expired and (not quota_pressure or reclaimed >= reclaim_target):
            continue
        selected.add(item.artifact.artifact_id)
        reclaimed += item.artifact.size_bytes
    entries = [
        classification_record(item, item.artifact.artifact_id in selected) for item in classified
    ]
    entries.sort(key=lambda value: int(value["artifact_id"]))
    return {
        "schema_version": 1,
        "generated_at": now.astimezone(dt.UTC).isoformat().replace("+00:00", "Z"),
        "mode": "dry-run",
        "quota": {
            "bytes": policy.quota_bytes,
            "high_watermark_bytes": high_bytes,
            "low_watermark_bytes": low_bytes,
            "pressure": quota_pressure,
        },
        "inventory": {"artifact_count": len(artifacts), "bytes": total_bytes},
        "selection": {
            "count": len(selected),
            "reclaim_bytes": reclaimed,
            "target_bytes": reclaim_target,
            "max_deletions": policy.max_deletions,
        },
        "artifacts": entries,
    }


def classification_record(item: Classification, selected: bool) -> dict[str, Any]:
    """Convert one classification to a stable public audit record."""
    return {
        "artifact_id": item.artifact.artifact_id,
        "name": item.artifact.name,
        "role": item.role,
        "size_bytes": item.artifact.size_bytes,
        "created_at": item.artifact.created_at.isoformat().replace("+00:00", "Z"),
        "expires_at": item.artifact.expires_at.isoformat().replace("+00:00", "Z"),
        "run_id": item.artifact.run_id,
        "head_sha": item.artifact.head_sha,
        "protected_reasons": list(item.protected_reasons),
        "candidate_reasons": list(item.candidate_reasons),
        "selected": selected,
    }


class GitHubClient:
    """Small authenticated GitHub API adapter with bounded pagination."""

    def __init__(self, repository: str, token: str) -> None:
        if re.fullmatch(r"[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+", repository) is None:
            raise RetentionError("repository must use owner/name syntax")
        if not token:
            raise RetentionError("GITHUB_TOKEN is required")
        self.repository = repository
        self.token = token

    def request_json(self, path: str) -> Any:
        """Request JSON from the fixed GitHub API origin."""
        request = urllib.request.Request(  # noqa: S310 - origin is fixed below
            f"{API_ROOT}{path}",
            headers={
                "Accept": "application/vnd.github+json",
                "Authorization": f"Bearer {self.token}",
                "X-GitHub-Api-Version": "2022-11-28",
            },
        )
        try:
            with urllib.request.urlopen(request, timeout=30) as response:  # noqa: S310
                return json.load(response)
        except (urllib.error.HTTPError, urllib.error.URLError, TimeoutError) as failure:
            raise RetentionError(f"GitHub API request failed for {path}") from failure

    def paginate(self, path: str, item_key: str | None = None) -> list[dict[str, Any]]:
        """Read at most 100 pages and reject malformed responses."""
        items: list[dict[str, Any]] = []
        separator = "&" if "?" in path else "?"
        for page in range(1, 101):
            payload = self.request_json(f"{path}{separator}per_page=100&page={page}")
            page_items = payload.get(item_key) if item_key is not None else payload
            if not isinstance(page_items, list) or not all(
                isinstance(item, dict) for item in page_items
            ):
                raise RetentionError(f"GitHub API returned invalid pagination data for {path}")
            items.extend(page_items)
            if len(page_items) < 100:
                return items
        raise RetentionError(f"GitHub API pagination exceeded 100 pages for {path}")

    def delete_artifact(self, artifact_id: int) -> None:
        """Delete exactly one selected artifact."""
        path = f"/repos/{self.repository}/actions/artifacts/{artifact_id}"
        request = urllib.request.Request(  # noqa: S310 - origin is fixed below
            f"{API_ROOT}{path}",
            method="DELETE",
            headers={
                "Accept": "application/vnd.github+json",
                "Authorization": f"Bearer {self.token}",
                "X-GitHub-Api-Version": "2022-11-28",
            },
        )
        try:
            with urllib.request.urlopen(request, timeout=30) as response:  # noqa: S310
                if response.status != 204:
                    raise RetentionError(f"unexpected delete status for artifact {artifact_id}")
        except (urllib.error.HTTPError, urllib.error.URLError, TimeoutError) as failure:
            raise RetentionError(
                f"GitHub API deletion failed for artifact {artifact_id}"
            ) from failure


def fetch_inventory(client: GitHubClient) -> tuple[tuple[Artifact, ...], RetentionContext]:
    """Fetch artifacts and the references required for fail-safe classification."""
    repository = client.request_json(f"/repos/{client.repository}")
    if not isinstance(repository, dict) or not isinstance(repository.get("default_branch"), str):
        raise RetentionError("GitHub repository response lacks default_branch")
    branch = urllib.parse.quote(repository["default_branch"], safe="")
    branch_data = client.request_json(f"/repos/{client.repository}/branches/{branch}")
    current_main_sha = nested_string(branch_data, "commit", "sha")
    pulls = client.paginate(f"/repos/{client.repository}/pulls?state=open")
    open_pr_heads = frozenset(nested_string(item, "head", "sha") for item in pulls)
    active_run_ids = fetch_active_run_ids(client)
    raw_artifacts = client.paginate(
        f"/repos/{client.repository}/actions/artifacts", item_key="artifacts"
    )
    artifacts = tuple(Artifact.from_api(item) for item in raw_artifacts)
    return artifacts, RetentionContext(current_main_sha, open_pr_heads, active_run_ids)


def nested_string(value: Any, *keys: str) -> str:
    """Read a required nested string from API data."""
    current = value
    for key in keys:
        if not isinstance(current, dict):
            raise RetentionError(f"GitHub API response lacks {'.'.join(keys)}")
        current = current.get(key)
    if not isinstance(current, str) or not current:
        raise RetentionError(f"GitHub API response lacks {'.'.join(keys)}")
    return current


def fetch_active_run_ids(client: GitHubClient) -> frozenset[int]:
    """Fetch every currently nonterminal Actions run id."""
    run_ids: set[int] = set()
    for status in ("in_progress", "queued", "requested", "waiting", "pending"):
        runs = client.paginate(
            f"/repos/{client.repository}/actions/runs?status={status}", item_key="workflow_runs"
        )
        for run in runs:
            run_id = run.get("id")
            if not isinstance(run_id, int):
                raise RetentionError("GitHub workflow run id must be an integer")
            run_ids.add(run_id)
    return frozenset(run_ids)


def apply_plan(
    client: GitHubClient,
    plan: dict[str, Any],
    confirmation: str,
) -> tuple[int, ...]:
    """Apply only the selected bounded plan after an exact confirmation."""
    if confirmation != DELETE_CONFIRMATION:
        raise RetentionError(f"apply requires exact confirmation {DELETE_CONFIRMATION}")
    selected = [item for item in plan["artifacts"] if item["selected"]]
    maximum = int(plan["selection"]["max_deletions"])
    if len(selected) > maximum or maximum > MAX_ALLOWED_DELETIONS:
        raise RetentionError("plan exceeds the deletion bound")
    deleted: list[int] = []
    for item in selected:
        artifact_id = int(item["artifact_id"])
        client.delete_artifact(artifact_id)
        deleted.append(artifact_id)
    return tuple(deleted)


def render_summary(plan: dict[str, Any], applied: bool, deleted: tuple[int, ...]) -> str:
    """Render a concise GitHub job summary without artifact names or SHAs."""
    selection = plan["selection"]
    inventory = plan["inventory"]
    mode = "APPLY" if applied else "DRY RUN"
    return (
        "## Actions artifact retention\n\n"
        f"- Mode: **{mode}**\n"
        f"- Inventory: {inventory['artifact_count']} artifacts, {inventory['bytes']} bytes\n"
        f"- Selected: {selection['count']} artifacts, {selection['reclaim_bytes']} bytes\n"
        f"- Deleted: {len(deleted)} artifacts\n"
        f"- Quota pressure: {str(plan['quota']['pressure']).lower()}\n"
    )


def parse_args(argv: list[str]) -> argparse.Namespace:
    """Parse the bounded command line."""
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--repository", default=os.environ.get("GITHUB_REPOSITORY"))
    parser.add_argument("--quota-mib", type=int, required=True)
    parser.add_argument("--high-watermark-percent", type=int, default=90)
    parser.add_argument("--low-watermark-percent", type=int, default=75)
    parser.add_argument("--minimum-age-days", type=int, default=14)
    parser.add_argument("--diagnostic-protection-days", type=int, default=30)
    parser.add_argument("--max-deletions", type=int, default=25)
    parser.add_argument("--apply", action="store_true")
    parser.add_argument("--confirm", default="")
    parser.add_argument("--output", type=Path)
    parser.add_argument("--summary-output", type=Path)
    return parser.parse_args(argv)


def main(argv: list[str] | None = None) -> int:
    """Fetch, classify, report, and optionally apply the bounded plan."""
    args = parse_args(sys.argv[1:] if argv is None else argv)
    if not isinstance(args.repository, str) or not args.repository:
        raise RetentionError("--repository or GITHUB_REPOSITORY is required")
    policy = RetentionPolicy(
        quota_bytes=args.quota_mib * 1024 * 1024,
        high_watermark_percent=args.high_watermark_percent,
        low_watermark_percent=args.low_watermark_percent,
        minimum_age_days=args.minimum_age_days,
        diagnostic_protection_days=args.diagnostic_protection_days,
        max_deletions=args.max_deletions,
    )
    policy.validate()
    client = GitHubClient(args.repository, os.environ.get("GITHUB_TOKEN", ""))
    artifacts, context = fetch_inventory(client)
    now = dt.datetime.now(dt.UTC).replace(microsecond=0)
    plan = plan_retention(artifacts, context, policy, now)
    deleted = apply_plan(client, plan, args.confirm) if args.apply else ()
    plan["mode"] = "apply" if args.apply else "dry-run"
    plan["deleted_artifact_ids"] = list(deleted)
    encoded = json.dumps(plan, indent=2, sort_keys=True) + "\n"
    if args.output is None:
        sys.stdout.write(encoded)
    else:
        args.output.write_text(encoded, encoding="utf-8")
    if args.summary_output is not None:
        args.summary_output.write_text(render_summary(plan, args.apply, deleted), encoding="utf-8")
    return 0


if __name__ == "__main__":
    try:
        raise SystemExit(main())
    except RetentionError as error:
        print(f"artifact retention error: {error}", file=sys.stderr)
        raise SystemExit(2) from error
