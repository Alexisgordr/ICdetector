# Changelog

## 2.1

### Verification integrity

- Rebuilt WiGLE/OpenCellID handling around `VERIFIED`, `NOT_FOUND`, `REJECTED`, `ERROR` and
  `PENDING`, with full identity and coordinate validation.
- Added radio-specific identity to requests, caches, UI and database operations.
- Prevented HTTP/API errors and legacy rows without radio from becoming current verification.
- Added verification TTL and separated public-database coordinates from device GPS coordinates.
- Prevented API callbacks from bypassing the three real-observation cycles required by
  `TemporalConfidence`. They update context only and cannot directly emit critical alerts.

### Timing Advance and data quality

- Added explicit TA units, defensible LTE/GSM conversion and abstention for NR/unknown values.
- Detects modem implementations that return a constant zero.
- Unified engine, UI, history and CSV conversion policy.
- Added `TA`, `TAUnit`, `TAMeters` and `Radio` export and replay support.
- Kept external verification neutral to score and anomaly confidence.
- Expanded regression coverage to 149 declared tests.

### Interface

- The live identity panel now displays `MCC / MNC` together in a compact column, preserving the
  four-column layout on narrow screens.
- Expanded history rows display `MCC/MNC` alongside TAC so the operator identity is visible without
  opening an export.

### Release metadata

- `versionName`: `2.1`
- `versionCode`: `3`
- Database schema: `11`
