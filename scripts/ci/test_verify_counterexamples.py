import unittest
from pathlib import Path
from tempfile import TemporaryDirectory

from verify_counterexamples import verify_counterexamples


class CounterexampleTest(unittest.TestCase):
    def test_retained_counterexamples_replay_deterministically(self) -> None:
        count = verify_counterexamples(Path(__file__).resolve().parents[2])
        if count != 4:
            raise AssertionError(f"expected four retained traces, got {count}")

    def test_input_hash_detects_fixture_drift(self) -> None:
        with TemporaryDirectory() as directory:
            tmp_path = Path(directory)
            fixture_dir = tmp_path / "tests" / "formal" / "counterexamples"
            fixture_dir.mkdir(parents=True)
            source = (
                Path(__file__).resolve().parents[2]
                / "tests"
                / "formal"
                / "counterexamples"
                / "replay-success.json"
            )
            fixture_dir.joinpath(source.name).write_text(
                source.read_text(encoding="utf-8").replace('"complete:0"]', '"crash:0"]'),
                encoding="utf-8",
            )
            with self.assertRaisesRegex(AssertionError, "input hash mismatch"):
                verify_counterexamples(tmp_path)
