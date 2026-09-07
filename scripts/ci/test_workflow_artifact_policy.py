"""Regression contracts for non-authoritative CI artifact transport."""

import unittest
from pathlib import Path

import yaml

ROOT = Path(__file__).resolve().parents[2]
WORKFLOWS = (ROOT / ".github/workflows/verify.yml", ROOT / ".github/workflows/ui.yml")
UPLOAD_ACTION = "actions/upload-artifact@"
PAGES_UPLOAD_ACTION = "actions/upload-pages-artifact@"


def workflow_steps(path: Path) -> list[dict[str, object]]:
    """Return every concrete workflow step."""
    workflow = yaml.safe_load(path.read_text(encoding="utf-8"))
    return [step for job in workflow["jobs"].values() for step in job.get("steps", ())]


class WorkflowArtifactPolicyTest(unittest.TestCase):
    """Protect authoritative CI results from optional artifact transport."""

    def test_optional_artifact_uploads_are_quota_tolerant_and_reported(self) -> None:
        uploads: list[dict[str, object]] = []
        reporters: list[dict[str, object]] = []

        for path in WORKFLOWS:
            steps = workflow_steps(path)
            uploads.extend(
                step for step in steps if str(step.get("uses", "")).startswith(UPLOAD_ACTION)
            )
            reporters.extend(
                step
                for step in steps
                if "GITHUB_STEP_SUMMARY" in str(step.get("run", ""))
                and ".outcome" in str(step.get("if", ""))
            )

        self.assertEqual(len(uploads), 8)
        self.assertTrue(all(step.get("continue-on-error") is True for step in uploads))
        self.assertTrue(all(step.get("id") for step in uploads))
        referenced_outcomes = "\n".join(str(step.get("if", "")) for step in reporters)
        self.assertTrue(
            all(f"steps.{step['id']}.outcome" in referenced_outcomes for step in uploads)
        )

    def test_authoritative_commands_and_pages_publication_remain_strict(self) -> None:
        steps = [step for path in WORKFLOWS for step in workflow_steps(path)]
        command_steps = [step for step in steps if "run" in step]
        pages_uploads = [
            step for step in steps if str(step.get("uses", "")).startswith(PAGES_UPLOAD_ACTION)
        ]

        self.assertTrue(all("continue-on-error" not in step for step in command_steps))
        self.assertEqual(len(pages_uploads), 1)
        self.assertNotIn("continue-on-error", pages_uploads[0])
