# Changelog

## 2.2.0

### Incident black box and temporal transparency

- Added a persistent incident black box that opens as soon as an anomaly reaches temporal phase
  `1/3`, rather than waiting for a confirmed `3/3` alert.
- Added explicit incident states: `OBSERVING`, `CONFIRMED`, `RECOVERED`, and `INTERRUPTED`.
- Added visible and persistent `1/3`, `2/3`, and `3/3` temporal phases so users can distinguish an
  initial observation from a sustained, confirmed event.
- Incident records preserve the highest phase reached, threat score, anomaly confidence, reason,
  and the heuristic snapshot associated with the event.
- Added separate **ANTENNAS** and **INCIDENTES** views to keep infrastructure history distinct from
  security-event history.

### Explainable diagnostics and baseline maturity

- Added structured per-rule diagnostics with contextual explanations for `PASS`, `FAIL`, and
  `N/A` results.
- `N/A` now indicates that a rule could not be evaluated with the telemetry or context currently
  available; it is not treated as a pass or a failure.
- Added maturity indicators for the RSRP power baseline, RSRQ/SINR fingerprint, PCI identity
  history, and local cell reputation.
- Live diagnostic states update as new modem, location, latency, and historical data becomes
  available.
- Detection weights, penalties, confirmation thresholds, and the existing threat engine remain
  unchanged in this release.

### Forensic case capture

- Added an automatic forensic case recorder that starts at temporal phase `1/3`.
- Added a bounded in-memory pre-event buffer covering up to 60 seconds and 180 samples.
- A case preserves the complete `1/3 -> 2/3 -> 3/3` episode and continues for 60 seconds after
  recovery. A recurrence during that post-event window remains part of the same case.
- Added a 30-minute safety limit for an individual capture.
- Captures serving and neighboring cells, radio identity and quality, Timing Advance when exposed,
  device GPS and accuracy, latency state, verification state, temporal phase, score, confidence,
  heuristic diagnostics, device capabilities, and relevant terminal context.
- Added forensic case states: `CAPTURING`, `POST_CAPTURE`, `READY`, and `INTERRUPTED`.
- Open captures are marked `INTERRUPTED` after an unexpected service or process restart instead of
  being silently presented as complete.

### Portable forensic export

- Added case export as a ZIP archive containing:
  `case.json`, `timeline.csv`, `cells.csv`, `heuristics.csv`, `capabilities.json`, `terminal.log`,
  and `SHA256SUMS.txt`.
- `SHA256SUMS.txt` allows later modification of exported files to be detected. It is an integrity
  aid, not a cryptographic signature or a legal chain-of-custody guarantee.
- Exports deliberately exclude API credentials, IMSI, IMEI, and the phone number.
- Added an explicit privacy warning because forensic exports can contain exact device coordinates
  and sensitive cellular metadata.

### Storage, compatibility, and validation

- Database schema advanced from 12 to 14 with non-destructive migrations for incident and forensic
  case storage.
- The existing 60-day retention policy also applies to forensic cases and their samples.
- Added regression coverage for diagnostic state/maturity behavior and forensic capture policy.
- Release metadata updated to `versionName 2.2.0` and `versionCode 7`.

## 2.1.1

- Eliminado el resolvedor Foojay para permitir compilaciones reproducibles en F-Droid.
- La aplicación utiliza el JDK 17 proporcionado por el entorno de compilación.


## 2.1

### Final freeze fixes

- Increased the `REJECTED` retry window from 15 minutes to one hour to protect API quota when one
  source repeatedly returns an unusable response.
- Raw ping-pong detections now leave an informational terminal trace without tone; only a
  three-cycle confirmed threat can produce the alarm.
- Removed the immediate raw ping-pong tone. Ping-pong can now contribute to an alert only after
  the normal three consecutive observations required by `TemporalConfidence`.
- Extended complete cell identity (`MCC + MNC + area + CID + radio`) to API-coordinate lookup,
  history, signal baseline, reputation, RF fingerprint, RF stability and both service caches.
- Added the non-destructive database migration 11→12 and a radio-aware identity index.
- History cards and Intel statistics now group by complete identity instead of CID alone; LTE and
  NR records sharing a numeric CID are no longer merged.

### Verification integrity

- Split retry timing by meaning: `REJECTED` and a genuine `NOT_FOUND` are retried after one hour.
  Separate timestamp maps prevent either state from renewing
  or blocking the other's retry.
- Field check with `alexis3.csv`: 33/71 observations were VERIFIED (14 distinct verified cells with
  API coordinates), confirming that strict identity verification and API/GPS distance are working.
- Fixed WiGLE's successful-result parser: cellular identity is returned in `results[].id` as
  `MCC+MNC_AREA_CELLID`, not as separate `cellid`/`cid` fields. Valid WiGLE matches no longer end
  up incorrectly as `REJECTED`, while unrelated results remain impossible to verify.
- Aligned the request with WiGLE's official Android client (`cell_op`, `cell_net`, `cell_id`) and
  added regression tests for the compound identifier.
- Made multi-source aggregation conservative: `NOT_FOUND` is shown only when every source queried
  agrees negatively; an error or rejected reply can no longer be hidden by one empty database.
- Fixed verification TTL renewal: periodic history samples no longer keep an old `NOT_FOUND` or
  `VERIFIED` fresh forever. Negative database rows are re-queried after the in-memory one-hour
  window, and only VERIFIED rows carrying actual API coordinates may be reused for 30 days.
- Added explicit terminal diagnostics (`OpenCellID → STATUS`, `WiGLE → STATUS`) for every lookup.
- Removed undocumented camelCase aliases from WiGLE requests; only the official Android client's
  `cell_op`, `cell_net` and `cell_id` filters are sent.
- Fixed OpenCellID identity parsing to prefer its canonical `lac` and `cellid` response fields over
  auxiliary `tac`/`cid` values that may be present as zero. This prevented valid LTE matches from
  being rejected as area mismatches.
- Treats OpenCellID `code: 1` accompanied by a temporary-unavailability notice as `ERROR`, following
  the API documentation, rather than recording it as a missing cell.
- Redacts the OpenCellID key if an API error echoes it in the response body before terminal logging.
- Rebuilt WiGLE/OpenCellID handling around `VERIFIED`, `NOT_FOUND`, `REJECTED`, `ERROR` and
  `PENDING`, with full identity and coordinate validation.
- Added radio-specific identity to requests, caches, UI and all identity-based database reads and
  writes.
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
- Expanded regression coverage to 155 declared tests.

### Interface

- The live identity panel now displays `MCC / MNC` together in a compact column, preserving the
  four-column layout on narrow screens.
- Expanded history rows display `MCC/MNC` alongside TAC so the operator identity is visible without
  opening an export.

### Release metadata

- `versionName`: `2.1`
- `versionCode`: `3`
- Database schema: `12`
