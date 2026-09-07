from __future__ import annotations

import copy
import unittest

from verify_language_assurance import _load, verify_contract


class LanguageAssuranceContractTest(unittest.TestCase):
    def test_contract_is_valid(self) -> None:
        verify_contract()

    def test_contract_rejects_missing_language(self) -> None:
        contract = copy.deepcopy(_load())
        contract["gates"] = [gate for gate in contract["gates"] if gate["language"] != "java"]
        with self.assertRaisesRegex(AssertionError, "missing assurance languages"):
            verify_contract(contract)

    def test_contract_rejects_path_escape(self) -> None:
        contract = copy.deepcopy(_load())
        contract["gates"][0]["evidence"] = "../private.json"
        with self.assertRaisesRegex(AssertionError, "escapes"):
            verify_contract(contract)

    def test_contract_rejects_unknown_invariant(self) -> None:
        contract = copy.deepcopy(_load())
        contract["gates"][0]["invariants"] = ["unknown"]
        with self.assertRaisesRegex(AssertionError, "unknown invariant"):
            verify_contract(contract)
