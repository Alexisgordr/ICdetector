import unittest
from datetime import datetime
from pathlib import Path
import sys

sys.path.insert(0, str(Path(__file__).resolve().parent))
from check_export import calendar_period_stats, ta_unit_diagnostic


class ExportSummaryTest(unittest.TestCase):
    def test_calendar_period_is_inclusive_and_density_uses_observed_days(self):
        times = [
            datetime(2026, 9, 1, 23, 59),
            datetime(2026, 9, 3, 0, 1),
        ]
        self.assertEqual((3, 2, 5.0), calendar_period_stats(times, 10))

    def test_many_lte_zeroes_are_reported_as_aggregate_legacy_stub(self):
        rows = [{"TA": "0", "TAMeters": "0"} for _ in range(10)]
        usable, reliable, possible_stub, zeros = ta_unit_diagnostic(rows)
        self.assertEqual(10, usable)
        self.assertEqual(0, reliable)
        self.assertTrue(possible_stub)
        self.assertEqual(10, zeros)

    def test_single_lte_zero_remains_legitimate(self):
        usable, reliable, possible_stub, zeros = ta_unit_diagnostic(
            [{"TA": "0", "TAMeters": "0"}]
        )
        self.assertEqual((1, 1, False, 1), (usable, reliable, possible_stub, zeros))


if __name__ == "__main__":
    unittest.main()
