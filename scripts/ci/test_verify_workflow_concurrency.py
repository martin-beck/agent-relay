from unittest import TestCase

from verify_workflow_concurrency import replay, verify_model


class WorkflowConcurrencyTest(TestCase):
    def test_bounded_interleavings_and_fault_traces_are_deterministic(self) -> None:
        self.assertEqual(verify_model(), verify_model())

    def test_duplicate_claim_is_fenced_and_recovery_is_replayable(self) -> None:
        state = replay(("start", "acquire:0:0", "crash:0", "recover:1:0"))
        self.assertEqual(state.leases, (1, -1))
        self.assertEqual(state.attempts, (2, 0))
        self.assertEqual(state.generations, (2, 0))

    def test_uncertain_effect_is_terminal_and_cannot_be_completed(self) -> None:
        state = replay(("start", "acquire:0:0", "uncertain:0"))
        self.assertEqual(state.run, "UNCERTAIN")
        self.assertEqual(state.effects, ("UNCERTAIN", "NONE"))
        self.assertEqual(state.leases, (-1, -1))
        with self.assertRaisesRegex(ValueError, "completion"):
            replay(("start", "acquire:0:0", "uncertain:0", "complete:0"))
