from __future__ import annotations

import copy
import unittest
from typing import Any

from verify_usability_review import ReviewContractError, validate_review_contract


def contract() -> tuple[dict[str, Any], dict[str, Any]]:
    journeys = {"contracts": [{"id": "sample"}]}
    review = {
        "schemaVersion": 1,
        "cadence": {
            "reviewIntervalDays": 90,
            "minimumParticipants": 5,
            "maximumJourneysPerParticipant": 5,
            "retentionDays": 365,
            "storage": "aggregate-only",
        },
        "journeys": [
            {
                "id": "sample",
                "successRateTarget": 0.8,
                "medianTimeSecondsMax": 120,
                "errorRateMax": 0.2,
                "workloadScoreMax": 4,
                "reviewPrompts": ["What was clear?", "What was difficult?"],
            }
        ],
    }
    return review, journeys


class UsabilityReviewContractTest(unittest.TestCase):
    def test_accepts_aggregate_review_contract(self) -> None:
        validate_review_contract(*contract())

    def test_rejects_unknown_journey(self) -> None:
        review, journeys = contract()
        review["journeys"] = [dict(review["journeys"][0], id="missing")]
        with self.assertRaisesRegex(ReviewContractError, "unknown journey"):
            validate_review_contract(review, journeys)

    def test_rejects_identifying_storage(self) -> None:
        review, journeys = contract()
        invalid = copy.deepcopy(review)
        invalid["cadence"]["storage"] = "raw-transcripts"
        with self.assertRaisesRegex(ReviewContractError, "aggregate-only"):
            validate_review_contract(invalid, journeys)


if __name__ == "__main__":
    unittest.main()
