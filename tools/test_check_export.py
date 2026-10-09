import unittest
from datetime import datetime
from pathlib import Path
import sys

sys.path.insert(0, str(Path(__file__).resolve().parent))
from check_export import (
    calendar_period_stats, instant_issues, radio_context_summary, row_instant,
    ta_consistency_issues, ta_unit_diagnostic
)


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


class TaConsistencyTest(unittest.TestCase):
    def test_rows_written_by_the_app_are_consistent(self):
        rows = [
            {"TA": "", "TAUnit": "", "TAMeters": ""},
            {"TA": "3", "TAUnit": "LTE_INDEX", "TAMeters": "234"},
            {"TA": "2", "TAUnit": "GSM_INDEX", "TAMeters": "1108"},
            {"TA": "0", "TAUnit": "LTE_INDEX", "TAMeters": "0"},
            {"TA": "512", "TAUnit": "NR_RAW", "TAMeters": ""},
            {"TA": "0", "TAUnit": "STUB_ZERO", "TAMeters": ""},
            {"TA": "7", "TAUnit": "UNKNOWN", "TAMeters": ""},
            {"TA": "-1", "TAUnit": "LTE_INDEX", "TAMeters": ""},
        ]
        self.assertEqual([], ta_consistency_issues(rows))

    def test_metres_without_a_defensible_unit_are_flagged(self):
        rows = [
            {"TA": "512", "TAUnit": "NR_RAW", "TAMeters": "39936"},
            {"TA": "0", "TAUnit": "STUB_ZERO", "TAMeters": "0"},
            {"TA": "3", "TAUnit": "LTE_INDEX", "TAMeters": "300"},
            {"TA": "", "TAUnit": "LTE_INDEX", "TAMeters": ""},
            {"TA": "3", "TAUnit": "", "TAMeters": ""},
            {"TA": "3", "TAUnit": "METRES", "TAMeters": ""},
        ]
        self.assertEqual(6, len(ta_consistency_issues(rows)))


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




class ObservedInstantTest(unittest.TestCase):
    """3.0 (#23) — El instante UTC desambigua la hora repetida del cambio de hora de otoño."""

    def test_repeated_autumn_hour_has_two_distinct_instants(self):
        # Madrid, 25-10-2026: las 02:30 locales ocurren dos veces (CEST +2 y CET +1).
        first = {"Timestamp": "2026-10-25 02:30:00", "ObservedAtUtc": "2026-10-25T00:30:00.000Z"}
        second = {"Timestamp": "2026-10-25 02:30:00", "ObservedAtUtc": "2026-10-25T01:30:00.000Z"}
        self.assertLess(row_instant(first), row_instant(second))
        self.assertEqual([], instant_issues([first, second]))

    def test_rows_before_3_0_have_no_instant_and_are_not_flagged(self):
        legacy = {"Timestamp": "2026-09-01 10:00:00", "ObservedAtUtc": ""}
        self.assertIsNone(row_instant(legacy))
        self.assertEqual([], instant_issues([legacy]))

    def test_impossible_offsets_and_unreadable_instants_are_flagged(self):
        rows = [
            {"Timestamp": "2026-10-09 12:00:00", "ObservedAtUtc": "2026-10-08T12:00:00.000Z"},
            {"Timestamp": "2026-10-09 12:07:00", "ObservedAtUtc": "2026-10-09T10:00:00.000Z"},
            {"Timestamp": "2026-10-09 12:00:00", "ObservedAtUtc": "yesterday"},
        ]
        self.assertEqual(3, len(instant_issues(rows)))

    def test_half_hour_zones_are_valid(self):
        row = {"Timestamp": "2026-10-09 17:30:00", "ObservedAtUtc": "2026-10-09T12:00:00.250Z"}
        self.assertEqual([], instant_issues([row]))

if __name__ == "__main__":
    unittest.main()
