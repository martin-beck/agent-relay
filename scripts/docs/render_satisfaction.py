#!/usr/bin/env python3
"""Render the user-satisfaction status page from its canonical contract."""

from __future__ import annotations

import argparse
from pathlib import Path
from typing import Any, cast

import yaml
from verify_satisfaction_assurance import ROOT, read_contract, validate_contract

OUTPUT = ROOT / "docs" / "USER_SATISFACTION.md"


def escaped(value: Any) -> str:
    return str(value).replace("|", "\\|").replace("\n", " ")


def scenario_test(root: Path, raw: str) -> str:
    scenario = yaml.safe_load((root / raw).read_text(encoding="utf-8"))
    return cast(str, scenario["verified_test"])


def render(document: dict[str, Any], root: Path = ROOT) -> str:
    journeys = cast(list[dict[str, Any]], document["verifiedJourneys"])
    children = cast(list[dict[str, Any]], document["childContracts"])
    lines = [
        "# User satisfaction assurance",
        "",
        "This page is generated from",
        "[`user-satisfaction-v1.json`](contracts/user-satisfaction-v1.json).",
        "It separates Android journey evidence from contract-only assurance so a planned",
        "surface is never presented as verified user behavior.",
        "",
        "## Verified Android journeys",
        "",
        "| Journey | Satisfaction phase | User value | Semantic evidence |",
        "| --- | --- | --- | --- |",
    ]
    for journey in journeys:
        test = scenario_test(root, cast(str, journey["scenario"]))
        scenario = f"workflows/{journey['id']}.md"
        lines.append(
            f"| [{escaped(journey['title'])}]({escaped(scenario)}) | "
            f"{escaped(journey['phase'])} | {escaped(journey['userValue'])} | "
            f"`{escaped(test)}` |"
        )
    lines.extend(
        [
            "",
            "## Integrated child contracts",
            "",
            "Every row below is executed by the normal JVM gate. Contract verification proves",
            "the named invariant; it does not substitute for a semantic Android journey, human",
            "usability review, authenticated OEM pairing, or live provider evidence.",
            "",
            "| Contract | Satisfaction phase | Assurance | Remaining evidence |",
            "| --- | --- | --- | --- |",
        ]
    )
    for child in children:
        lines.append(
            f"| {escaped(child['ar'])}: {escaped(child['title'])} | {escaped(child['phase'])} | "
            f"Contract verified | {escaped(child['limitation'])} |"
        )
    lines.extend(
        [
            "",
            "## Publication invariant",
            "",
            "The generator fails closed unless AR-2160 through AR-2169 appear exactly once,",
            "each child names existing production and test evidence, every satisfaction phase is",
            "covered, and every journey in the first table points to a verified scenario with a",
            "semantic Android test, reviewed screenshots, and non-empty alternative text.",
            "The documentation privacy gate separately rejects credential-like or identifying",
            "fixture content. Human-review results remain aggregate-only under",
            "[the usability protocol](USABILITY.md).",
            "",
        ]
    )
    return "\n".join(lines)


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--check", action="store_true", help="fail when generated output is stale")
    args = parser.parse_args()
    content = render(validate_contract(read_contract()))
    current = OUTPUT.read_text(encoding="utf-8") if OUTPUT.exists() else ""
    if args.check:
        if current != content:
            print("generated user-satisfaction status is stale: docs/USER_SATISFACTION.md")
            return 1
    else:
        OUTPUT.write_text(content, encoding="utf-8")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
