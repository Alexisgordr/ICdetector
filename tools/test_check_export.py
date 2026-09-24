import unittest
from datetime import datetime
from pathlib import Path
import sys

sys.path.insert(0, str(Path(__file__).resolve().parent))
from check_export import calendar_period_stats, radio_context_summary, ta_unit_diagnostic


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


class RadioContextSummaryTest(unittest.TestCase):
    def test_legacy_rows_without_context_do_not_count(self):
        summary = radio_context_summary([{"CID": "1"}, {"CID": "2", "ServingConnection": ""}])
        self.assertEqual(0, summary["rows_with_context"])
        self.assertEqual(0, summary["invalid_connection"])

    def test_counts_secondary_csg_and_operator_mismatch(self):
        rows = [
            {"ServingConnection": "PRIMARY_SERVING", "SecondaryCarriers": "LTE:6400:200",
             "ServiceState": "IN_SERVICE", "NetworkOperator": "21407", "SimOperator": "21407",
             "NetworkRoaming": "0"},
            {"ServingConnection": "UNKNOWN", "CsgIndicator": "1", "ServiceState": "EMERGENCY_ONLY",
             "NetworkOperator": "21401", "SimOperator": "21407", "NetworkRoaming": "0"},
            {"ServingConnection": "PRIMARY_SERVING", "NetworkOperator": "20801",
             "SimOperator": "21407", "NetworkRoaming": "1"},
        ]
        summary = radio_context_summary(rows)
        self.assertEqual(3, summary["rows_with_context"])
        self.assertEqual(1, summary["with_secondary"])
        self.assertEqual(1, summary["csg"])
        self.assertEqual(1, summary["operator_mismatch"])  # la de roaming no cuenta
        self.assertEqual(2, summary["connection"]["PRIMARY_SERVING"])

    def test_unknown_values_are_flagged(self):
        summary = radio_context_summary([{"ServingConnection": "PRIMARY", "ServiceState": "ONLINE"}])
        self.assertEqual(1, summary["invalid_connection"])
        self.assertEqual(1, summary["invalid_service"])


if __name__ == "__main__":
    unittest.main()
