# Changelog

## Unreleased

## 2.6.0

### Service refactor and complete localization

- Split `MiniICService` responsibilities into focused controllers for telephony, location,
  persistence, verification, alerts, transitions, power, health, maintenance and telemetry.
- Added complete Spanish and English interface localization, including monitoring labels,
  heuristic names and explanations, geometry, history, settings and live-terminal presentation.
- Added an in-app language selector and Android locale configuration.
- Removed WiGLE from runtime verification and Settings. OpenCellID is now the sole external source,
  preventing WiGLE quota failures or inconclusive replies from altering OpenCellID results.
- Preserved the database schema, CSV format, heuristic weights and confirmation thresholds.
- Updated release metadata to `versionName 2.6.0` and `versionCode 19`.

### OpenCellID-only external verification

- Removed WiGLE from the runtime verification pipeline and from Settings. OpenCellID is now the
  sole external source, so quota failures or inconclusive replies from WiGLE cannot alter its
  result.
- Stored WiGLE credentials and cooldown state are deleted during upgrade. Existing history and
  database schema remain compatible; local heuristics and scoring are unchanged.

## 2.5.2

### GNSS continuity validation

- Replaced the single `400 km/h` plausibility cutoff with a continuity gate. Ordinary movement is
  still accepted immediately, while a high-speed displacement remains provisional until a second,
  genuinely newer GNSS fix confirms a coherent trajectory.
- Cached repetitions of the same `Location` timestamp can no longer advance recovery or turn one
  bad coordinate into several confirmations.
- A return to the last accepted area cancels a provisional excursion immediately. Rejected or
  provisional coordinates never reach H11/H13 and are not persisted as valid device positions.
- Sustained high-speed rail travel remains supported: two distinct, spatially coherent fixes admit
  the new trajectory instead of relying on a lower hard speed limit.
- Added regression coverage for the field case that jumped 18.5 km and returned, duplicate cached
  fixes, normal road travel and a coherent high-speed train trajectory.
- No database migration, heuristic weight, confirmation threshold or CSV schema change.
- Updated release metadata to `versionName 2.5.2` and `versionCode 18`.

## 2.5.1

### Trusted baselines and evidence-aware confirmation

- Historical observations now enter detection baselines only after the complete cell identity has
  accumulated at least five clean observations (`score >= 85`) across two different days. Until
  then they remain preserved and exportable but quarantined from learning.
- H11 geographic history, H13 signal baseline, the RSRQ/SINR fingerprint, H15 RF stability and H16
  location profiles consume only trusted clean observations. Suspicious rows can no longer teach
  the reference that will later judge them.
- Temporal confirmation remains conservative for isolated or weak signals: three distinct modem
  observations are still required. When at least two independent high-value checks among H11,
  H13, H15 and H16 fail together, confirmation requires two distinct observations instead.
- Duplicate, stale and cached modem deliveries still cannot advance confirmation.
- No database migration or data deletion: existing history and CSV fields remain intact. This is a
  detector-baseline change; record the installation date when comparing campaign data.
- Updated release metadata to `versionName 2.5.1` and `versionCode 17`.

## 2.5.0

### Cell geometry tab

- New `GEOMETRÍA` tab in the history screen: draws the handover graph over the positions this
  device itself observed, with no map tiles, no map SDK and no external cell database. The whole
  view is rendered offline on a `Canvas`, so displaying it emits no network request that could
  reveal where the user lives.
- Node circles represent the P90 radius of the device's own observations, drawn to scale. Edges are
  handovers, weighted by observation count and coloured by learned trust ratio.
- LTE eNodeB grouping check (`eNodeB = CID / 256`): sectors grouped under one logical eNodeB are
  highlighted when their learned observation centres are unusually far apart. This is a review
  signal, not proof of a rogue cell: distributed deployments and remote radio heads can be
  legitimate. Deliberately restricted to LTE because NR uses an operator-chosen gNB/cell split.
- Route coherence check: unexplained gap between the learned centres of two cells that hand over
  to each other, after subtracting both P90 radii.
- The tab is read-only and diagnostic. It does not alter the score, open forensic cases or teach
  routes to the baseline. Centres are labelled "observation centre", never "antenna position".
- Centroid mathematics now lives in a single place (`CellGeometry`) and H16 consumes it from
  there, so the screen cannot drift from what the detector actually decides.
- New aggregate query `getAllCellLocationSamples()`: profiles every cell in one pass instead of one
  query per cell. Read-only, no schema change.

### H16: shortcuts no longer override geometry

- Until 2.4.0 the first check in `TransitionCoherence.evaluate()` returned PASSED whenever the
  destination cell had been visible as a neighbour before the handover, without ever comparing the
  learned zones. A local IMSI catcher appears in the neighbour list, so the attack H16 exists to
  detect could bypass H16 entirely by announcing itself. The same applied to the
  `priorTrustedTransitions >= 2` shortcut.
- Geometry is now computed first, and both shortcuts apply only when no geometric contradiction
  exists over mature baselines. Cells without sufficient history still pass as before: abstaining
  where there is nothing to contradict remains correct.

### Timing Advance diagnosis survives restarts

- Field measurement over 713 rows: 394 samples labelled `STUB_ZERO` and 319 labelled `LTE_INDEX`,
  all with TA = 0. Same modem, same zero, different labels depending on how recently the service
  had restarted, leaving that history column not self-consistent.
- The evidence (the real-value latch and the zero-only cell identities) is now persisted; the
  verdict is not. `isStub` is still derived from the evidence on every query, so a future change to
  `MIN_DISTINCT_CELLS` re-evaluates the past instead of inheriting a frozen conclusion.

### Fixes and cleanup

- Settings could trap the user: the Tor proxy row was hidden while latency detection was enabled,
  and the latency switch was disabled under Wi-Fi/VPN, so entering settings with latency saved as
  on and Wi-Fi active left neither control reachable. The Tor row is now always shown (greyed with
  the reason), and Wi-Fi/VPN block only turning latency on, never off.
- Audit log said "15 REGLAS" while the report counts 16.
- Retention comment corrected from 60 to 120 days.
- Removed unreachable code: `DataBox`, `TemporalConfidence.streakOf`, `VerificationStatus.isConclusive`,
  the unused `neighbors` parameter of `verifyCell`, and the `FileProvider` manifest entry plus
  `file_paths.xml` (exports use the Storage Access Framework; nothing referenced the provider).
- Test suite: 216 tests passing under the real Android/Compose Gradle build.
- Updated release metadata to `versionName 2.5.0` and `versionCode 16`.

## 2.4.0

### Continuous GPS collection

- Keeps the GPS-only location stream active while the foreground monitoring service is running,
  including with the screen off, so H11, H13 and H16 receive substantially more contemporaneous
  device positions during overnight and pocket collection.
- Holds a non-reference-counted partial wake lock to keep the collection loop running through CPU
  sleep. The persistent notification explicitly identifies continuous GPS operation.
- Pauses continuous GPS and releases the wake lock only below 5% battery while unplugged, then
  resumes both automatically after charging or battery recovery.
- Rechecks the GPS provider idempotently so turning location off and back on restores the stream.
- Continues to use GPS only. Network-derived location, stale coordinates and invented backfills are
  never accepted; existing accuracy, freshness and physical-plausibility filters remain unchanged.
- Documents the intentionally higher battery cost and the recommended manual battery-optimization
  exemption. No detection weights, thresholds, database schema or CSV columns changed.
- Updated release metadata to `versionName 2.4.0` and `versionCode 15`.

## 2.3.5

### Long-running collection reliability

- Periodic screen-off samples now request one bounded GPS fix when the cached position has expired,
  so the observation can feed geographic baselines without restoring continuous background GPS.
- History retention and the forensic-sample cap now run daily as well as at service startup.
- Polling responses no longer refresh the registered telephony-callback watchdog.
- Database timestamps now use `Locale.ROOT`, preserving lexicographic date comparisons on every
  Android locale.
- API credentials are trimmed and OpenCellID query parameters are encoded safely; request creation
  failures now produce a retryable API error instead of leaving a cell pending.
- A detected 2G/3G fallback now posts a high-priority action that reliably opens Airplane Mode
  settings when tapped; no restricted full-screen intent is used.
- The history screen loads at most 2,000 recent rows while verified streaming export continues to
  include the complete database.
- Automatic startup after a reboot remains intentionally opt-in through opening the app; no boot
  receiver was added. Detection weights, thresholds, database schema and CSV columns are unchanged.
- Updated release metadata to `versionName 2.3.5` and `versionCode 14`.

## 2.3.4

### WiGLE cooldown label integrity

- A paused WiGLE source no longer injects a synthetic `ERROR` into verification aggregation.
  OpenCellID's real `NOT_FOUND` response therefore remains `NOT_FOUND` while the global WiGLE
  cooldown is active instead of being mislabeled as `REJECTED`.
- Clarified that `NOT_FOUND` during a WiGLE cooldown means that OpenCellID did not contain the
  cell; it does not claim absence from WiGLE or every public database.
- Detection weights, thresholds, database schema and export columns remain unchanged.
- Updated release metadata to `versionName 2.3.4` and `versionCode 13`.

## 2.3.3

### Campaign-data protection

- Raised history retention from 60 to 120 days. The previous value was shorter than the planned
  90-day collection campaign, so the prune that runs on every service start deleted the first
  month of data — including the start triggered by opening the app to export it.
- Added a hard cap of 20,000 forensic samples, enforced at startup. Age-based pruning alone did
  not bound disk growth, and a full disk stops collection silently.
- A forensic case closed by the 30-minute timeout no longer reopens on the same cell until the
  anomaly actually clears or the device changes cell.
- Failed database writes are now detected and surfaced. `SQLiteDatabase.insert()` swallows
  disk-full and locked-database errors and returns `-1`; the app kept analysing and reporting
  "monitoring active" while nothing reached the history.
- A gap since the last successful write is reported at service start, so an interruption caused by
  a reboot, an OTA or a flat battery is visible in the notification and the terminal instead of
  appearing as an unexplained hole in the CSV months later.
- CSV export now streams rows straight from the database cursor and verifies the written row count
  against the count the database declares. A partial export fails loudly instead of being reported
  as a success, and the full history is no longer materialised in memory.
- Clearing the history now requires typing the confirmation word and states how many records will
  be destroyed. There is no backup: `allowBackup` is disabled by design.
- Added regression coverage for retention, write-failure escalation, interruption detection,
  forensic case reopening and export completeness.
- Detection weights, thresholds, schema and export columns remain unchanged.
- Updated release metadata to `versionName 2.3.3` and `versionCode 12`.
- This release begins the definitive collection campaign and a release freeze of at least one
  month. Only a defect that threatens data integrity, continuity, security, or export can justify
  an emergency update during that period.

## 2.3.2

### Temporal confirmation and callback correctness

- The modem-observation token now comes from the registered cell that actually survives parsing,
  instead of the maximum timestamp across raw registered LTE/NR entries.
- Added a bounded 30-second recovery path for fresh modem callbacks whose timestamp freezes or
  moves backwards. Cached `allCellInfo` data returned after `onError` cannot use that recovery path
  and therefore cannot manufacture a three-cycle confirmation.
- Migrated to `CellInfo.timestampMillis` on Android 11 and later while retaining the Android 10
  compatibility path.
- Empty or wholly invalid callback payloads no longer increment the processing sequence and cannot
  invalidate a valid cycle waiting for serialized analysis.
- Temporal confirmation remains deliberately conservative: a genuinely newer clean observation
  still resets a suspicious streak immediately.

### State and regression fixes

- Permission results are read back from Android, so omitting an already granted notification
  permission from a request cannot incorrectly mark it as denied.
- Capped the tower-location cache without clearing every entry when no serving cell is available.
- Removed an unreachable RF-status branch and kept the RSRQ warning explicitly independent from
  network-latency status.
- Updated the SQLite regression fixture to use the production `id` column name and run the source
  regression checks in CI.
- Added regression tests for frozen timestamps, cached fallback data, and token-watermark rollback.

### Release metadata

- Updated release metadata to `versionName 2.3.2` and `versionCode 11`.

## 2.3.1

### Verification and runtime correctness

- Fixed SQLite numeric affinity in the repeated-coordinate sentinel check. Bound query arguments
  are now explicitly cast to `REAL`, so unrelated API coordinates no longer compare as equal after
  three tracking areas have accumulated.
- A new verification result is now attached to the most recent observation of that full cell
  identity, even when it was inserted as `NOT_FOUND` or `REJECTED`. Earlier rows intentionally keep
  their historical state; therefore old `PENDING` rows are not rewritten retroactively in exports.
- Added an executable SQLite regression check covering both numeric affinity and the transition
  from `NOT_FOUND` to a persisted `VERIFIED` result.
- Serialized cellular processing, isolated API-coordinate caches by full identity, and prevented
  duplicate or rapid modem snapshots from advancing the three-phase confirmation.
- Made the one-shot high-accuracy GPS listener a synchronized single-flight operation. Concurrent
  requests can no longer register an orphan listener that remains active at maximum frequency.
- Fixed lifecycle cleanup for the advanced telephony callback and made terminal log updates atomic.

### Interface and exports

- Notification permission denial no longer blocks the whole application on Android 13 and later.
- CSV export now runs outside the main thread and reports destination failures.
- Fixed terminal auto-scroll after its 40-line buffer fills, RF quality presentation, suspicious
  cell counting, and GPS-status reads during Compose rendering.

### Release metadata

- Updated release metadata to `versionName 2.3.1` and `versionCode 10`.

## 2.3.0

### H16 — cellular transition coherence

- Added a conservative mobility heuristic that audits real serving-cell handovers against device
  displacement, GPS accuracy, previously visible neighbours, locally learned cell zones, and
  previously trusted transitions.
- H16 does not impose a universal maximum distance between towers. It abstains when the GPS is
  inaccurate, the interval is stale, or either geographic baseline is immature.
- A transition fails only when the phone barely moved while two mature local coverage zones are
  remote and non-overlapping, the destination was not a visible neighbour, and the route was not
  previously learned.
- Added a dedicated **Mobility sanity · H16** card with live `COHERENT`, `INCOHERENT`, or `N/A`
  state and a human-readable explanation.
- Handover results remain visible for 20 seconds so the existing three-cycle temporal confirmation
  can evaluate them; they do not become permanent failures.
- Added a persistent transition baseline (database schema 15). Only coherent transitions backed by
  mature local baselines can teach a trusted route, preventing an unevaluable or suspicious event
  from legitimising itself.
- H16 carries a deliberately low 15-point penalty and a conservative expert-estimated likelihood
  ratio of 1.8. It is grouped with the existing mobility family to prevent double-counting H11,
  Timing Advance, ping-pong, and RF-identity evidence.
- Added unit coverage for impossible jumps, immature history, inaccurate GPS, visible neighbours,
  previously learned routes, and overlapping legitimate coverage zones.

### Local handover topology explorer

- Added a read-only **TOPOLOGY** view alongside antennas, incidents, and forensic cases.
- Shows unique observed cells, directional routes, total handovers, and routes backed by trusted
  H16 observations.
- Each route exposes its complete origin/destination identity, observation count, trusted count,
  last result, trust ratio, and most recent observation time.
- Added `ALL`, `LEARNED`, and `REVIEW` filters. The explorer never changes the score or teaches the
  baseline; it is an analyst view over evidence already collected by H16.
- Added privacy-gated topology ZIP export with `cells.csv`, `transitions.csv`, directed GraphML,
  machine-readable metadata, and SHA-256 checksums.

### Release metadata

- Updated release metadata to `versionName 2.3.0` and `versionCode 9`.

## 2.2.1

### WiGLE quota handling

- Added structured detection of WiGLE rate limiting for both HTTP `429` responses and API bodies
  reporting `too many queries`, `rate limit`, or `quota` exhaustion.
- Added a global WiGLE cooldown instead of retrying the same exhausted account separately for each
  observed cell.
- Persisted the cooldown across service restarts so restarting ICdetection cannot immediately resume
  requests against an exhausted daily allowance.
- WiGLE now respects a numeric `Retry-After` response when supplied; otherwise the application uses
  a conservative 24-hour fallback with a one-hour minimum.
- OpenCellID and all local heuristic analysis remain active while WiGLE is paused.
- Added terminal context explaining that WiGLE is paused and reporting the approximate time
  remaining, without treating an unavailable database as evidence against the observed cell.
- Added regression tests for HTTP `429`, WiGLE's real `too many queries today` response, and normal
  non-quota service failures.

### Forensic recorder correctness

- Replaced wall-clock arithmetic in the forensic pre-event buffer with Android's monotonic elapsed
  time. Manual clock changes or NTP adjustments can no longer empty or freeze the retention window.
- Replaced the nullable `peekFirst()` access with a safe lookup, removing the Kotlin release-build
  warning without changing the 60-second / 180-sample capture policy.

### Release metadata

- Updated release metadata to `versionName 2.2.1` and `versionCode 8`.
- Detection weights, heuristic rules, alarm thresholds, database schema, and forensic export format
  are unchanged from v2.2.0.

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
