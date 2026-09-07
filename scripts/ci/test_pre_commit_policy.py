from __future__ import annotations

import unittest
from pathlib import Path
from typing import Any

import yaml

ROOT = Path(__file__).parents[2]
CONFIG = ROOT / ".pre-commit-config.yaml"
GITLEAKS_REPOSITORY = "https://github.com/gitleaks/gitleaks"


class PreCommitPolicyTest(unittest.TestCase):
    def test_gitleaks_uses_pinned_staged_only_hook_contract(self) -> None:
        config: dict[str, Any] = yaml.safe_load(CONFIG.read_text(encoding="utf-8"))
        repository = next(
            entry for entry in config["repos"] if entry["repo"] == GITLEAKS_REPOSITORY
        )
        hook = next(entry for entry in repository["hooks"] if entry["id"] == "gitleaks")

        self.assertEqual("83d9cd684c87d95d656c1458ef04895a7f1cbd8e", repository["rev"])
        self.assertNotIn("entry", hook)
        self.assertNotIn("args", hook)


if __name__ == "__main__":
    unittest.main()
