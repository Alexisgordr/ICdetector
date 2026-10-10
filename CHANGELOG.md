# Changelog

Every notable change to ICdetection, newest first. Releases inside the field-collection freeze only
fix bugs; a fix that changes how a rule is evaluated is marked as a **dataset cut** for that rule.
Version numbers follow the app's `versionName`; the Android `versionCode` is given where it matters.

## 3.0.0-beta4 (development beta, source only)

Available as source on the `ICdetection-v3.0.0-beta` branch; no APK is published. A short-lived
GitHub pre-release of an earlier build was withdrawn.

### Phase 4 — Maintenance backlog and documentation

Fourth and last beta of the 3.0 roadmap (GitHub issue #27): the maintenance items from the
post-freeze review (#7) and the documentation of the campaign's validation limits (#31). Schema
21 (one additive column, see *Location mode*).

#### Location mode (found during testing: continuous GPS drains the battery)

- **New setting: Adaptive location (battery saver).** Settings → *Adaptive location*. **Off**
  (default) keeps the continuous GPS of every earlier version: the stream stays active with the
  screen off, which uses more battery and gives the most complete location data — the mode for
  field campaigns. **On** (adaptive) uses less battery: with the screen off there is no continuous
  GPS, and a fix is requested only when the phone is used again, the serving cell changes or a
  suspicion is detected, **at most once a minute** (`LocationPolicy.OnDemandGate`), so repeated
  cell changes or a persistent suspicion cannot keep the GPS on. A location older than 2 minutes
  is never used, so between fixes the location-based rules (H11, H13, H16) are `N/A` instead of
  using a stale position.
- **Schema 21: the mode of every row.** An additive migration adds `location_mode` to the history;
  each row stores `CONTINUOUS` or `ADAPTIVE`, captured when the reading arrived. Rows from before
  schema 21 keep it empty (unknown). The history CSV ends with a new `LocationMode` column, and
  `tools/check_export.py` counts rows per mode and warns when adaptive rows are mixed in.
- The persistent notification title and a terminal line say which mode is active.
- **Dataset:** with the default (continuous) nothing changes. Rows recorded in adaptive mode have
  fewer positions with the screen off; compare H11, H13 and H16 coverage per mode before joining
  data. No rule, weight or threshold changes.

#### Installation

- **Same app for betas and release, clean install for 3.0.** Betas build the same app as the
  release (`com.alexisgordr.icdetector`, *ICdetection*), so a beta updates to the final 3.0. An
  earlier beta setting (a separate `.beta` app called *ICdetection β*) was removed before any
  release. **3.0 requires a clean install:** export your data, uninstall v2.10.x, then install 3.0.
  Installing over v2.10.x still upgrades the database safely, but mixing data from both sides of
  the methodology cuts is not supported. From 3.0 on, every version updates the previous one.
- **Version code 39 for every 3.0 beta.** The betas used 39, 40, 41 and 42, but none is published
  on F-Droid, so the 3.0 release would have skipped from 38 to 43. All betas now
  use 39, the next after v2.10.10 (38), and the release keeps it. The four store notes are merged
  into `fastlane/.../changelogs/39.txt` (EN/ES); 40–42 are removed so a later 3.0.x cannot pick up a
  beta note. A test phone with a local beta built as 40–42 must uninstall it first.

#### Bugs found during testing

- **The monitoring notification could beep on every update, and muting it muted alarms (#35).**
  The persistent notification is redrawn about every 2 seconds and was built without
  `setOnlyAlertOnce` / `setSilent`, so a phone whose *Monitoring* channel had sound beeped on every
  update. Silencing the app to stop it also silenced *Security alerts*, and a confirmed alarm then
  arrived without sound with nothing to say so. The monitoring notification is now always silent,
  and the main screen shows a warning with a shortcut to the alert settings when ICdetection
  notifications are off or the *Security alerts* channel is silenced (`AlarmAudibility`). Only a
  confirmed network anomaly makes a sound. No detection or data change.
- **A history row could carry the context of a later moment (A03).** The time, GPS position and
  service state of a row were read when the row was saved, which happens after the analysis
  publishes its result. With a slow analysis, a cell reading was stored with the time, position
  and service state of a later moment. They are now captured when the reading arrives and travel
  with it to the history row; the analysis uses the same position. Rows written by earlier 3.0
  betas may carry a context up to one analysis cycle late (**dataset cut** for `ObservedAtMs`,
  position, `GpsAccuracyM` and service-state columns).
- **A long gap without readings did not break continuity (#20 follow-up).** Only a reported
  signal loss (empty list, abstention) reset H1's isolation streak and H14's previous band. If no
  readings arrived for several minutes (Doze, sleeping modem) and the same cell came back with no
  empty list in between, H1 kept its streak and H14 compared with a band from before the gap. A gap
  of more than 2 minutes between published observations now breaks continuity like a signal loss
  (`CoverageContinuity`), and the terminal says which one happened. The confirmation streak
  (`TemporalConfidence`) now measures its 2-minute gap with `SystemClock.elapsedRealtime()`: the
  previous clock stops in deep sleep, so a long Doze gap could look like zero seconds
  (**dataset cut** for H1, H14 and confirmation methodology).
- **Schema 20 migration failed on a database missing a table.** The migration altered `history`,
  `incidents` and `forensic_cases` without checking they existed. A partial or damaged database
  without one of them stopped with `no such table` and the app could not open; the instrumented
  migration tests, which build such partial databases, failed the same way. The migration now
  creates any of the three tables that is missing, empty, before adding columns; no row is
  invented. A real v2.10.x database (schema 19) and a fresh install were not affected. The
  instrumented tests now expect schema 20.

#### Maintenance (#7)

- **Stable-Site prefix not translated (B4).** The `[site-unverified]` prefix was stored in English and
  had no translation, unlike every other stored prefix. It is now stored in Spanish
  (`[sitio-sin-verificar]`, like `[sub-umbral]`) and shown as `[site-unverified]` in the English
  interface. Stable-Site enforcement is still off (shadow mode), so no stored row is affected.
- **A cell seen every day could be forgotten first (B5).** The trust-contradiction tracker keeps the
  last state of up to 500 identities and evicted them in insertion order, so the home cell — among
  the first to enter — could be evicted on a busy day even if it had just been seen, and a later
  change was then recorded as `ON_START` instead of `TRANSITION`. It now evicts the identity unseen
  for longest (LRU).
- **Each rule had three unconnected names (O2).** A rule's id (`H5`), its key in the Bayesian scorer
  (`tacDev`) and its multi-signal episode family (identity) lived in three places with nothing tying
  them together: a new rule without a weight would silently count as neutral. `HeuristicCatalog` now
  declares all three once per rule; the episode tracker uses it directly and a test checks that the
  report, the scorer weights and groups, and the keys emitted by the analyzer all match it. No
  behaviour change.
- **Precise GPS fixes were retried at the same pace when they kept failing (O5).** Each handover could
  start a new 20 s precise-fix attempt. Where GPS does not reach (indoors, tunnels, garages) all of
  them failed, keeping the GPS on for nothing. After each consecutive failure the minimum interval
  between normal attempts now doubles (30 s, 1, 2, 4, 8 min, capped at 10 min) and returns to 30 s
  with the first accepted fix. Forced requests (suspicious episode, leaving airplane mode) never wait.
- **H15's history scan reviewed (O1).** The query reads only the rows of the current cell for 30 days
  through the identity index (now checked with `EXPLAIN QUERY PLAN` in `tools/check_sql_affinity.py`);
  the only extra cost is sorting those rows. No limit was added on purpose: it would change which
  history H15 sees. The trust-contradiction eviction (B5) and this review close the open items of #7.

#### Documentation (#31)

- **The limits of the campaign are now written down.** README (new section *What the field campaign
  can and cannot show*), IMPORTANT and MANUAL state that there is no ground truth (the warning rate
  can be measured and possible false positives studied, but the absence of a known attack does not
  make every warning false, and detection cannot be measured), no negative control (one phone cannot
  tell a network anomaly from a detector fault) and limited coverage (one phone, one operator, mostly
  one area), and what the campaign is for: measuring how often each rule warns, studying possible
  false positives and, with the 3.0 coverage columns, how long each rule was evaluable. The likelihood
  ratios are not recalibrated from benign data alone.

## 3.0.0-beta3 (development beta, not published)

### Phase 3 — Confirmation and rule behaviour

Third beta of 3.0 (roadmap: GitHub issue #27). With correct inputs in place, this phase adjusts how
evidence is confirmed and how some rules behave. Schema 20.
The LTE band table was also checked against 3GPP TS 36.104 V19.2.0, which confirms every band and
makes bands 53–113 resolvable by EARFCN (follow-up to #32).

#### Bugs found during testing

- **A confirmation could span a coverage gap (#20).** When the serving cell was lost (empty list,
  abstention, airplane mode) the screen was cleared, but the confirmation streak survived. Two
  suspicious cycles, ten minutes without signal and one more suspicious cycle of the same cell could
  complete a confirmation as if they were consecutive; the multi-signal episode window, H1's
  "no neighbours" streak and H14's previous band also survived the gap. A signal loss now restarts
  the confirmation streak, the episode correlation and H1's isolation streak and clears the band
  context (H14 is `N/A` until a new previous band exists),
  and the terminal records "Continuity interrupted by signal loss" so the gap is visible in the
  forensic black box. Independently, two accepted observations more than 2 minutes apart are no
  longer consecutive. A normal handover or a manual refresh does not count as a gap. **Methodology
  cut:** affects when any alarm is confirmed.
- **H14 flagged band changes inside the same base station (#10).** In the first week of field data,
  all 9 sub-threshold "Band downgrade" warnings were carrier changes inside one site: the eNodeB
  (`Cell ID >> 8`) was the same before and after, e.g. `79360544 → 79360545` (eNB `310002`). That is
  the base station moving the phone to another of its carriers, not a forced downgrade. H14 no longer
  penalises a change between cells of the same eNodeB in the same network (known MCC and MNC);
  without a known network the exception does not apply. A loss of signal also clears the stored
  site. **Dataset cut for H14.**
- **Latency showed OK before measuring anything (#26).** After a cell change the network indicator
  was set to OK while the probe could run, before the new cell had any measurement; while the
  baseline was being learnt it stayed OK; and a measured OK had no expiry. H12 treated that OK as an
  available measurement. The latency state now has four explicit values: not measured (`N/A`),
  learning (`APRENDIENDO`, fewer than 5 samples for this cell), OK and anomalous. A measured result is
  valid for 90 s; with no new measurement it returns to not measured (an isolated failed probe does not
  make it flicker). The expiry runs on every periodic cycle, even when no check can run (airplane
  mode, no registered cell), and a signal loss resets the latency to not measured. H12 is evaluated only with OK or anomalous; while learning it is `N/A` with its own
  explanation. The network indicator on the main screen shows the learning state (amber) and is now
  translated (`NET` in English, `RED` in Spanish). The optional latency feature is off by default.
  **Dataset cut for H12.**
- **H1 said "passed" while isolation was still unconfirmed (#28).** With no neighbours, strong signal
  and Wi-Fi off, H1 only fails after 3 consecutive fresh deliveries confirm the isolation. During the
  first 1–2 deliveries it reported PASSED, claiming "not isolated" when the app did not know yet. It is
  now `N/A`, and the diagnostics panel explains "no neighbours, pending confirmation (1/3 deliveries)".
  With no neighbours and a weak signal (worse than −80 dBm) H1 stays evaluated and passed: the data is
  there and the condition the rule looks for — a strong lone cell — is absent. Score and alarms are
  unchanged (only failures add points); the H1 status in diagnostics, coverage counts and forensic
  snapshots changes. **Dataset cut for H1 status.**
- **5G SA had less coverage than LTE (#8).** On a 5G Standalone serving cell H14 (band downgrade) was
  always `N/A`, because the band table is LTE-only. In NR one NR-ARFCN can belong to several
  overlapping bands (632448 is in n77 and n78), so a band table would not help; what H14 needs is
  whether the carrier is high or low, and the NR-ARFCN gives the exact downlink frequency (TS 38.104
  global raster). H14 now evaluates NR → NR changes with the same conditions as LTE; a change between
  LTE and NR is not evaluated, and there is no same-site exception in NR (the gNB ID length is not
  fixed). **H6 on NR stays `N/A`:** Android's NR Timing Advance has no defensible conversion to metres
  (the documentation does not settle whether it is one-way or round-trip, and no field data has NR and
  LTE TA at the same site), so the raw value keeps being recorded as `NR_RAW` for later study.
  Regression tests cover an NR serving cell (H8 passes, H14 evaluated or `N/A`, H6 `N/A`). The H14
  diagnostic now mentions LTE or 5G. **Dataset cut for H14 on 5G SA.**

## 3.0.0-beta2 (development beta, not published)

### Phase 2 — Correct inputs to the rules

Second beta of 3.0 (roadmap: GitHub issue #27). It makes sure each rule receives true data before
any rule is tuned. No database schema
change (still 20).

#### Bugs found during testing

- **A handover was detected by Cell ID only (#19).** Two cells with the same number on another
  operator, another tracking area or another technology (LTE/NR) were treated as the same cell: the
  handover row was not written, the state of the previous cell was not reset and H10 (Ping-Pong) did
  not count the change. A handover is now a change of the full serving identity (MCC, MNC, TAC, Cell
  ID and technology), and that one definition is used for the handover row, the alarm episode and
  H10. A field the modem did not fill in (`N/A`) on one side does not count as a change, so a modem
  that briefly drops MCC/MNC does not create handovers that never happened. **Dataset cut for H10
  and handover rows:** from 3.0.0-beta2 they also include changes of operator, tracking area or
  technology with the same Cell ID.
- **Placeholder neighbour values counted as real (#11).** Some modems (seen on Xiaomi/Redmi/POCO
  phones with a MediaTek modem) fill every LTE neighbour with TAC 65535 and Cell ID 268435455 instead
  of leaving them empty. H5 compared the serving TAC with 65535, never matched and failed on every
  cycle; in one field case that false signal helped confirm a false alarm. Stable-Site also counted
  those neighbours as full identities. Both values are now read as unavailable (`N/A`) in LTE
  neighbours, as v2.10.5 already did for neighbour MCC/MNC: H5 is `N/A` when no neighbour has a real
  TAC, and those neighbours count as RF-only. The serving cell keeps what the modem reports, and NR is
  unchanged (65535 is a valid NR TAC). Rows already stored are not changed. **Dataset cut for H5**
  on affected devices. These devices remain outside the supported AOSP-like set.
- **EARFCN 0 was dropped from learning (#22).** H8 accepted EARFCN 0 as a valid LTE frequency
  (lowest Band 1 channel), but the history learning used `> 0`, so a real Band 1 carrier on channel
  0 could not learn its known PCIs for local trust, and H15 grouped it with rows that really have no
  frequency. Learning also accepted any PCI up to 1007 for every technology. There is now one
  validator per technology (`RadioChannels`): EARFCN 0..262143, NR-ARFCN 0..3279165, UARFCN
  0..16383, GSM 0..1023; PCI LTE 0..503, NR 0..1007, PSC UMTS 0..511; Android's "unavailable" value
  is never valid. Local trust, H15, Stable-Site and H8 use it. **Dataset cut for local trust and H15**
  on carriers using EARFCN 0, and for LTE rows with an impossible PCI (504..1007), which no longer
  count as learnt values. H8's results are unchanged for every value Android can report.
- **H14 could not see most LTE bands (#32).** The band table had 17 bands. A cell on any other band
  left H14 (band downgrade) as `N/A`, and the band the modem declared could not be used either,
  because the high/low classification came from the same table. The table also gave Band 71 the
  upper limit of Band 74, so EARFCNs of bands 72–74 were taken as Band 71 (600 MHz, low) although
  Band 74 is at 1475 MHz. The table now has every band of 3GPP TS 36.101 (1–71 resolvable by EARFCN,
  72–255 for classification only), checked against an independent implementation, with one
  definition for band and high/low class. The band is resolved from the EARFCN when it is in the table
  (each EARFCN belongs to one band, so the measurement wins over what the modem declares); otherwise
  from `CellIdentityLte.getBands()` (Android 11+): one declared band is used as is, several only when
  they are all of the same class. Same-eNodeB band changes stay a separate issue (#10).
  `tools/check_export.py` counts LTE rows whose declared band is outside the table. **Dataset cut for
  H14.**
- **Timing Advance could be borrowed from another transmitter (#21).** When the serving cell had no
  TA, the app copied it from another list entry with the same Cell ID and TAC, without checking the
  technology or the physical cell. LTE and NR can share numbers and measure TA in different units:
  the unit was copied, but the value belonged to another transmitter and fed H6, the highest penalty
  in the system. The TA is now copied only from a duplicate of the same cell: same technology, same
  Cell ID and TAC, matching or unreported MCC/MNC, and at least one physical field (PCI or frequency)
  valid in both and equal. PCI and frequency are checked with the shared validator first, so
  Android's "unavailable" value (or an out-of-range value) on both sides is never taken as a match.
  A field the modem omits is not required; with none valid to compare, the app abstains. **Dataset cut for H6.**
- **One technology's Timing Advance hid another's stub zero (#33).** The evidence that decides whether
  a TA of 0 is a measurement or an unfilled field was one latch for the whole phone: the first
  non-zero TA of any technology set it for good. A modem whose LTE path reports real values could
  never be detected returning a constant 0 on GSM or NR, and that 0 reached H6's proximity branch as a
  real distance. The evidence is now kept per declared unit (LTE, GSM, NR, unknown), each with its own
  latch and its own list of cells seen at 0, and is saved per unit. The evidence saved by earlier
  versions does not say which technology it came from, so it is not read: every unit starts empty
  and is re-derived within minutes of use. A single TA of 0 is still legitimate and a missing TA still
  gives no evidence. Found by reading the code, not yet seen in the field. **Dataset cut for H6 and
  `TAUnit`.**

## 3.0.0-beta1 (development beta, not published)

### Phase 1 — Data foundations

First beta of 3.0, built after the field-collection freeze. It follows the ordered roadmap in
GitHub issue #27. Database schema **20**: the migration only adds columns, and every row recorded
before 3.0 keeps the new columns empty, meaning **unknown** — nothing is filled in with an assumed
value.

#### Bugs found during testing

- **Times were ambiguous (#23).** History, incident and forensic times were stored only as local
  text without a time zone. Travelling across zones changed the age the app computed, and at the
  autumn clock change the repeated hour gave two different moments the same text, so H15's ordering
  inside that hour was undefined. Every new row now also stores the instant of the observation in
  milliseconds since epoch (UTC). Windows (30/90 days, 48 h, the 2-minute GPS backfill), the
  verification TTL, retention and H15's ordering use that instant. Rows recorded before 3.0 have no
  instant (their zone was never stored and is not reconstructed); they keep the previous
  approximate comparison on their local text. The local text is still written: it is what the
  screens show and it still defines the "day" of the evidence-by-days counts. The history CSV gains
  an `ObservedAtUtc` column (ISO-8601, UTC) at the end, empty for rows before 3.0, and
  `tools/check_export.py` uses it for the impossible-jump check and verifies it against the local
  time. **Methodology cut:** age and ordering are exact from 3.0 on; before 3.0 they remain
  approximate around clock changes and zone changes.

- **The history could not tell "passed" from "not evaluated" (#29).** Each row stored only the
  failed rules, so a rule that never had the data it needs looked the same as a rule that passed,
  and after the campaign it was impossible to say how long each rule was actually evaluable. Rows
  also kept the position without its GPS accuracy, so precise fixes could not be separated from
  vague ones in the analysis. Each new row now stores `not_evaluated_heuristics` (the rules that
  abstained, e.g. `H1;H6;H9`, or `NONE` when every rule was evaluated) and `gps_accuracy_m` (the
  accuracy of the fix used for that row, also when the position is filled in later). Both are
  exported at the end of the history CSV as `NotEvaluatedHeuristics` and `GpsAccuracyM`, empty for
  rows before 3.0 (unknown, never "evaluated"). `tools/check_export.py` now prints the evaluation
  coverage of each rule and checks that every 3.0 position has an accuracy below 100 m; positions
  worse than 50 m are reported so they can be filtered. Detection is unchanged: the app already
  discarded fixes worse than 100 m, H16 requires 75 m or better and Stable-Site 50 m or better.

- **Exports did not say which app version or phone produced them (#30).** Dataset cuts are per
  version (H10 from 2.10.9, H8 from 2.10.10, more in 3.0), but no export recorded the app version,
  so the cuts could only be applied by install date. Files from several people could not be told
  apart once joined. Each new history row now stores the app version that observed it, and the
  history CSV ends with `AppVersion` (per row, empty before 3.0), `ExportDevice` (manufacturer and
  model) and `ExportAndroid` (Android version and API level) of the phone that made the export. No
  personal or hardware identifier is exported (no IMEI, serial number or account).
  `tools/check_export.py` lists the rows per app version, shows which dataset cuts the file crosses
  and warns when a file joins exports from several phones.

- **"Most recent row" meant "last row written" (#25).** Since 2.10.10 each row keeps the
  context of the moment it was observed, but the app still picked "the latest row" by its row id
  (`MAX(id)`, `ORDER BY id DESC`). With several writers the last row written can be an earlier
  observation, so a verification result or a GPS backfill could land on the wrong row and the
  recent-sample windows of H11, H13, H15 and the local baselines could take an older sample as the
  newest. Every "most recent" choice now uses the observation instant (#23); rows before 3.0, which
  have no instant, come after all newer rows and keep their previous order by id. The history screen
  and the CSV are listed in the same order.

- **A slow analysis cycle could publish after the signal was lost (#24).** A cycle was only
  checked when it started; an empty cell list or an abstention did not even count as something
  newer. A cycle still analysing in the background could therefore show a cell, alert, write
  history, incidents and black-box samples, and feed the confirmation counters after the signal had
  been lost or the serving cell had changed. Each cycle now carries a ticket (`CollectionGeneration`)
  that is checked again just before it publishes; an empty list or abstention invalidates it. A
  newer delivery of the **same** cell does not invalidate it, so the app cannot stop publishing when
  deliveries arrive faster than they are analysed, but any interruption — an empty list, an
  abstention, airplane mode, a forced refresh or a change of serving cell — invalidates every earlier
  cycle for good, even if the same cell comes back (A → loss → A, A → B → A). Observations the cycle already recorded (Timing
  Advance evidence, the per-minute Stable-Site context) are kept: they describe a real moment.


## 2.10.10

### Bug-fix release inside the freeze — dataset cut for H8

Fixes from an external static audit of v2.10.9. Weights, thresholds, rules and the database schema
are **unchanged**. One fix changes how H8 (frequency sanity) is evaluated, so this release is a
**dataset cut for H8 only**. The data-integrity fixes make new rows more accurate without changing
what any column means. Audit items that would change detection more broadly are deferred to 3.0
and tracked as GitHub issues #19–#26.

#### Dataset cut

- **H8 treated a missing frequency as suspicious.** When the modem does not report the frequency,
  Android delivers `CellInfo.UNAVAILABLE` (`2147483647`). H8 counted it as an impossible LTE/NR
  frequency, subtracted 15 points and fed it to the scorer and the episode tracker. It is now `N/A`.
  A measured out-of-range value still fails, as before. **Dataset cut for H8 only:** treat
  `Frecuencia (EARFCN) 4G sospechosa` / `Frecuencia (ARFCN) 5G sospechosa` results before v2.10.10 with
  care, especially when the exported `ARFCN` is `2147483647`.

#### Data integrity

- **GPS coordinates written on an old row.** The backfill took the latest row of the cell without
  checking its age. Staying on one cell, moving without GPS and getting a fix minutes later wrote the
  new position into the old row, with its old time, contaminating H11/H13/H16 geometry. Only a row
  stored within the last 2 minutes of the fix can now be filled; an older one stays without
  coordinates.
- **Rows mixed the context of two moments.** Position, service state and timestamp were read when the
  background write ran, not when the cell was observed. Each row now takes a snapshot at observation
  time, and history writes go through one ordered queue. The timestamp format is unchanged.
- **Second alarm episode in the same cell.** The "already alerted" guard was cleared only on a cell
  change, so a new episode after a recovery played the tone but was neither notified nor recorded as
  a confirmed alarm. An episode now closes after 60 s without alarm (the same margin as the forensic
  post-capture), measured up to the moment the alarm returns, and is keyed by the full cell identity.

#### Reliability

- **Delete history with the service running.** The screen deleted the database directly while the
  service kept the open forensic case, its pre-buffer, cached baselines and episode state. The next
  samples failed on the deleted case (a false "forensic capture degraded" warning) and old
  observations could be flushed into a later case. The service now coordinates the deletion: it
  pauses analysis, drops pending writes from before the deletion, resets the forensic recorder,
  deletes, and clears every cache and episode tracker. An OpenCellID verification that started before
  the deletion no longer writes, caches or shows its result afterwards: every asynchronous result
  carries the history "epoch" it started in (`HistoryEpoch`) and is applied only if that epoch is
  still current, checked under the same lock that the deletion uses to invalidate it — including the
  screen update queued on the main thread. The Incidents and Forensic lists are cleared
  as well.
- **Start-up recovery race.** Interrupted cases from the previous run are now closed on the forensic
  queue before any new sample is accepted, so a case opened by the new service can no longer be
  marked as interrupted.
- **SQLite on the main thread.** Stable-Site writes (up to four transactions per cycle) ran inside the
  main-thread block and could freeze the interface while the database was busy. They now run on the
  analysis thread, in the same order.
- **Exports.** Stable-Site and Geometry exports read all their tables in one consistent snapshot. The
  RADIO export now fails when the read fails, instead of exporting a partial list as complete.

#### Smaller fixes

- Charts clear the RSRQ and Timing Advance series on a cell change, not only the power series.
- A VERIFIED label kept in memory is re-checked every 6 h against the database, which applies the
  30-day TTL, even without OpenCellID credentials (the re-check is local). It is a label and never
  changes the score.
- A stale "ANOMALOUS" latency state is cleared when the endpoints stop answering (optional feature,
  off by default).

#### Tests

- `HeuristicsTest`: H8 is `N/A` with an unavailable frequency, fails with a measured impossible one,
  accepts EARFCN 0, and an unavailable value costs no points.
- `AlarmEpisodeGateTest`: a new episode after a real recovery notifies again; a short flicker does not.
- `HistoryEpochTest`: a screen update queued before a deletion is not applied after it; invalidation
  waits for a result that is being applied, which can apply nothing afterwards.
- `ForensicRecorderResetTest`: after a reset, no sample goes to the deleted case and no pre-buffer from
  before the reset is flushed into the next case.
- `CoordinateBackfillWindowTest` (instrumented): an old row stays without coordinates; a recent one
  is filled.
- Source checks for the service wiring (coordinated deletion, start-up order, Stable-Site thread,
  ordered snapshots, export snapshots).

## 2.10.9

### Bug-fix release inside the freeze — dataset cut for H10

Bugs found during testing. Weights, thresholds, rules, stored data and the database schema are
**unchanged**. The major fix below changes how H10 (Ping-Pong) is evaluated, so this release is a
**dataset cut for H10 only**: treat `Efecto Ping-Pong` results recorded before v2.10.9 with care.
Everything else remains comparable with the v2.10.5 baseline.

#### Major fix

- **H10 (Ping-Pong) stayed failed long after a burst of cell changes.** The service removed old
  cell changes only when *another* change arrived, and the analyzer counted every stored entry.
  After three quick changes, a phone that then stayed on one cell kept failing H10 on every cycle
  until the next handover — possibly for hours. Each of those cycles cost 25 points, was stored as
  `[sub-umbral] Efecto Ping-Pong`, did not count as a clean observation (so the baselines could not
  pass their trust gate) and made false alarms easier. In addition, a missing GPS speed was treated
  as 0 km/h, so normal cell changes while travelling without a fix counted as "stationary".

  H10 now counts only cell changes within the last 10 s and reports `N/A` when the speed is
  unknown. The rule itself — three changes in 10 s while not moving fast — is unchanged.
  **Dataset cut for H10 only.**

  Fixing it during the campaign was worth the cut: left in place, it would have biased every H10
  result, kept baselines from learning and made false alarms easier — exactly what the campaign is
  meant to measure.

#### Other fixes

- **RSRQ/SINR fingerprint compared against the previous area.** The fingerprint is limited to the
  current position, but its cache was reused for 60 s based only on the cell. It is now
  recalculated after moving 150 m, or once GPS appears after it was computed without a fix — the
  same rule the power baseline already used.
- **Latency result from the previous cell** (optional feature, off by default). A measurement that
  was running, or queued, when the cell changed could publish "OK" or "ANOMALOUS" for the new cell.
  A result is now published only for the active cell and only if the probe was not reset
  meanwhile. A cell change for latency means any change of the full identity
  (MCC-MNC-TAC-CID-radio), and the latency baseline is keyed the same way. Handover detection is
  unchanged.
- **"NEEDS 2 DAYS" shown after the 2-day period** (#18). An empty power, fingerprint or PCI row now
  shows **"NEEDS 2 DAYS"** / **"NECESITA 2 DÍAS"** only while the cell has not passed the trust gate,
  and **"WAITING"** / **"EN ESPERA"** when it has but the row has no data for another reason (no GPS
  fix, away from the stored samples, cell just recovered). Display only.

#### Removed

- **Empty ciphering callback.** Android does not give a regular app access to the modem's ciphering
  state, and nothing in the app provided it. An empty telephony callback was still registered,
  suggesting otherwise. It is removed; H9 stays `N/A`, as before.

#### Tests

- `PingPongRuleTest`: the H10 decision without Android — window, old burst, fast movement, unknown
  speed, future timestamps. No test builds an `android.location.Location`, which plain JVM unit tests
  cannot use.
- `NetworkLatencyMonitorTest`: latency sequences with a controlled measurement — cell change during
  a measurement, probe reset, check queued for the previous cell, cells sharing a Cell ID. Each guard
  was verified to make its test fail when removed.
- `RfFingerprintRefreshTest`, `DiagnosticsTest` (trust gate passed to the display without changing
  any level) and `StringResourcesTest` (both labels in English and Spanish, one row).

## 2.10.8

### Maintenance release inside the freeze

Bugs found during testing. Detection, scoring, thresholds, what triggers an alarm, stored data
and the database schema are **unchanged**. Safe to ship during the field-collection freeze.

- **Fixed: a confirmed alarm showed no notification** (#17). It only played the tone and was
  recorded in History. With the phone on silent and the app in the background, nobody noticed it.
  A confirmed alarm now posts one notification per episode on the existing *Security alerts*
  channel: **"Network anomaly confirmed"** / **"Anomalía de red confirmada"**, with the cell ID,
  network and main reason. Tapping it opens the app. When an alarm is confirmed is unchanged: the
  notification follows the same rule that already saved the alarm once per episode.
- **Fixed: notifications in Spanish with the interface in English** (#15). The monitoring and
  2G/3G notification titles, the *Open settings* button, the latency warning, the collection
  interrupted / write failed notices, the notification channel name ("miniIC Channel") and its
  description were hard-coded in Spanish. They are now translated into English and Spanish. The
  background service also follows the language chosen in Settings (it used the system language)
  and switches immediately when you change it, without restarting monitoring.
  Channel IDs are unchanged, so your notification settings are kept.
- **Fixed: "[sub-umbral]" not translated in English** (#13). History and the terminal now show
  `[sub-threshold]` with the interface in English. The stored value and the exports keep
  `[sub-umbral]`, so the dataset is unchanged.
- **Fixed: "EMPTY · 0 samples" looked like a bug** (#14). The power, RSRQ/SINR fingerprint and PCI
  baselines only count once a cell is trusted (5 clean observations on 2 different days). Until
  then they now show **"NEEDS 2 DAYS"** / **"NECESITA 2 DÍAS"** on the same line, instead of
  "EMPTY". The rule itself is unchanged.
- **Fixed: "98%%" shown with a double percent sign** (#15) under *Local cell trust*.
- **Fixed: the app could close when opening a link** (#15). The map button (OpenStreetMap) and
  *Support project* crashed the app on a phone with no browser installed. Now nothing happens.
- **Fixed: "Iniciando..." shown in Spanish** for a moment on launch with the interface in English (#15).
- **Removed: unused WiGLE code** (#16). The app no longer queried WiGLE, but its client and tests
  were still in the source. They are removed so the code matches the documentation: the only
  network connections are OpenCellID and the optional latency check. Old WiGLE credentials are
  still deleted from the device on start-up.
- **Removed: three texts no screen used** (`geometry_legend`, `not_evaluated_explanation`,
  `terminal_technical_event`).
- **Tests:** notification texts exist in both languages and are not hard-coded, no `%%` in plain
  texts, the confirmed-alarm notification is posted once per episode, `[sub-umbral]` is translated
  for display only, and the WiGLE client is gone.

## 2.10.7

### Maintenance release inside the freeze

Bugs found during testing. Detection, scoring, thresholds, what triggers an alert, stored data
and the database schema are **unchanged**. Safe to ship during the field-collection freeze.

- **Fixed: the alert tone could not be silenced.** It used Android's alarm stream at full volume,
  which ignores silent mode and the volume buttons. A tester had no way to stop the beeping during
  an alarm. The tone now uses the notification stream, so it follows the notification volume and
  respects silent, vibrate and Do Not Disturb. When and how often it sounds is unchanged.
- **Fixed: the confirmed-alarm label overstated what the app knows.** "SYSTEM COMPROMISED" /
  "SISTEMA EN COMPROMISO" now reads **"NETWORK ANOMALY CONFIRMED"** / **"ANOMALÍA DE RED
  CONFIRMADA"**. ICdetection detects anomalous network behaviour; it cannot know that the device
  itself has been compromised.
- **Fixed: misleading terminal line.** Rapid cell changes were logged as "Ping-Pong detectado … Ignorando
  alerta" right before H10 confirmed the same ping-pong. The line now reads "Cambios rápidos de
  celda observados a … km/h" ("Rapid cell changes observed at … km/h" in English). H10 is unchanged.
- **Fixed: repeated terminal lines during an alarm.** "Episodio multiseñal" and "Efecto Ping-Pong
  confirmado" were written on every cycle, up to once per second, burying the rest of the log
  (seen in a real field capture). Each is now written once per episode. Detection and stored data
  are unchanged.
- **Tests:** the alarm label must not claim a compromised device, and the tone must not use the
  alarm stream.

## 2.10.6

### Maintenance release inside the freeze

Bug fixes that do **not** change detection, scoring, alerts, thresholds, stored data or the
database schema. Safe to ship during the field-collection freeze.

- **Fixed: deletion confirmation ignored the interface language.** The English UI asked for `DELETE` but
  the code only accepted the hard-coded word `BORRAR`, in both the history reset and the
  incident / forensic-case dialogs. The expected word is now the string resource
  `delete_confirm_word` (`DELETE` in English, `BORRAR` in Spanish) and every prompt shows it.
- **Fixed: Spanish text hard-coded in History and Geometry.** Screen titles, the history
  deletion warning, export privacy warnings (Topology and Geometry), export status messages,
  Topology metrics and route states, the forensic capture window note, the RF intelligence
  summary and the Mobility / node detail labels moved to string resources with English and
  Spanish versions. Warning meaning and detail are unchanged. Route-familiarity states are shown
  as localized labels instead of raw enum names (`UNKNOWN_ON_ROUTE`…), which exports keep as-is,
  and the Spanish strings no longer mix English terms.
- **Fixed: first launch ignored the system language.** The app always started in Spanish until a
  language was picked in Settings. It now follows the system: Spanish when the phone is in Spanish
  (any region), English for any other language. A language chosen in Settings still wins.
- **Fixed: permissions were requested without saying why.** On first launch Android asked for
  location, phone and notification access straight away. The app now first shows a screen that
  explains what each permission is used for (and that notifications are optional), then opens the
  system dialog. It also links to the app settings when a permission was denied permanently.
- **Fixed: Spanish words in the English live terminal.** With the interface in English, many
  terminal lines and heuristic explanations were still partly in Spanish. The translation table was
  applied in the order it was written, so a short entry broke a longer phrase before the whole
  phrase could match, and several service, audit, TA, GPS, Stable-Site, verification and H16
  messages had no entry. Phrases now apply longest first and the missing ones were added. Display
  only: stored and exported logs are unchanged.
- **Documented: every network connection.** The F-Droid description, README and manual now name
  every network connection: OpenCellID, and the optional, off-by-default latency detection that
  sends HTTPS `HEAD` requests to `www.google.com/generate_204` (Google), `one.one.one.one`
  (Cloudflare) and `dns.quad9.net` (Quad9). `NetworkLatencyMonitor` is unchanged.
- **Topology and Geometry no longer truncate routes** (#9). Both screens and both exports
  read every stored transition. Previously Topology stopped at 250 routes, the Geometry screen
  at 400 and the Geometry export at 1,000 (plus an internal 1,000 clamp), silently keeping
  only the most recently used routes. Per-cell aggregation in `TopologyExporter` and
  `MobilityGeometryProjection` now groups routes once instead of filtering the full list per
  cell, so thousands of routes stay fast. The screens remain lazily rendered.
- **`ForensicExporter`** builds its ZIP contents in a pure `buildFiles` function, like the
  other exporters, and now has unit tests. File names and contents are unchanged.
- **`check_export.py`**: the "not enough cells in both states" note is attached to the
  VERIFIED/NOT_FOUND check instead of the REJECTED one (#10), and a new invariant checks that
  `TA`, `TAUnit` and `TAMeters` agree with how the app writes them.
- **Text-only:** a suspicious cell without a reason can no longer produce the literal `null`
  in its confirmation text, and the H16 explanation after an expired verdict reads "no recent
  handover".
- **Tests:** every heuristic name `ThreatAnalyzer` can emit must carry a likelihood ratio;
  Topology per-cell counts with 3,000 routes; instrumented check that screens and exports
  return all 1,200 stored routes.

## 2.10.5

### Stabilization — definitive release for the field-collection freeze

No heuristic, weight, threshold, scoring rule or database schema changed. Schema remains 19.

- **Delete history is now complete and atomic.** It also clears `mobility_trips`,
  `mobility_trip_cells` and `mobility_trip_edges`, which previously survived the reset and could
  carry pre-reset routes into a new dataset. Every table is cleared in a single transaction.
- **Atomic daily retention.** `pruneOldRecords` runs in one transaction, so an interrupted prune can
  no longer leave forensic cases without their samples or Stable-Site tables half trimmed.
- **Single shared database connection.** The activity and the background service now use one
  `CellDbHelper` instance. Two independent connections could fail with `database is locked` when
  both wrote at once (for example, deleting history while the service stored a sample), closing the
  app.
- **Neighbour MCC/MNC are no longer invented.** Only the registered cell inherits the operator's
  MCC/MNC when the modem omits them; neighbours without identity stay `N/A`. Previously H3 (MCC) and
  H4 (MNC) compared the network with itself and always reported PASS on devices that do not expose
  neighbour identities. They now report N/A, like H5 (TAC). The same-cell Timing Advance copy treats
  an `N/A` MCC/MNC as unknown so it keeps working. **Dataset note:** H3/H4 `PASS` → `N/A` on such
  devices, and new `site_rf_neighbours` rows store `NULL` MCC/MNC; older rows keep the copied
  operator values. Score and anomaly confidence are unchanged, since a passing rule never
  contributed to either.
- **External verification no longer gets stuck.** An exception during an OpenCellID check left the
  cell as `PENDING` in memory, which is never retried, until the service restarted. It now falls
  back to `ERROR` and retries after 60 s.
- **Lifecycle fixes.** The activity unbinds from the service even if it is destroyed while still
  binding; the history list is reset on the UI thread after *Delete history*.
- **CI.** `actions/checkout`, `actions/setup-java` and `gradle/actions/setup-gradle` moved to v5
  (Node 24).
- **Tests.** New `CellParserIdentityTest`, an H3/H4 N/A case in `HeuristicsTest` and an
  instrumented check that *Delete history* clears route trips.

## 2.10.4

### Radio context collection

- The serving cell is now selected by `CellInfo.getCellConnectionStatus()`: a registered entry that
  declares `PRIMARY_SERVING` wins; if that primary sample has no usable signal telemetry, the cycle
  abstains instead of promoting a secondary. Only when no primary is declared is the first usable
  registered entry used as before. Selection is never based on signal strength.
- Fixed: with carrier aggregation or duplicated NSA entries, every registered entry was analysed with
  the first entry's history and the strongest one was kept. A secondary carrier could therefore be
  scored and stored as the serving cell. Now only the serving cell is analysed; the others are
  recorded as secondary carriers. This is a dataset cut for devices that publish several registered
  entries.
- New collection-only fields per observation: connection state, bandwidth, declared bands,
  additional PLMNs, closed subscriber group (CSG / possible femtocell), secondary carriers, service
  state, network PLMN, SIM PLMN and roaming. None of them feeds a heuristic or the score.
- `ServiceState` is collected by callback (Android 12+) and polling, deduplicated, logged to the
  terminal as `[SERVICIO]` and stored as change events (same retention as history, capped at 5000).
- Service-state changes are persisted successfully before RADIO is notified. Failed inserts remain
  invisible and retryable, removing the refresh race without duplicate events.
- Service readings are processed on a single ordered dispatcher and stale timestamps are rejected.
  Live fields such as channel and bandwidth still update on every reading, while the event list
  reloads only after a confirmed insert.
- An unusable declared primary is filtered from the visible list and produces one deduplicated
  `[RADIO]` abstention line for diagnosis.
- New RADIO tab (history screen) and radio context card (main screen), in English and Spanish.
  The RADIO tab exports service-state changes to CSV.
- History CSV gains 12 columns at the end; forensic `cells.csv` gains `connection_state` and
  `bandwidth_khz`. `check_export.py` summarises the new context and validates its values.
- Schema 19 is an additive migration: existing history keeps NULL in the new columns.
- H1-H16, weights, thresholds, Temporal Confidence, Stable-Site and LocalCellTrust are unchanged.

## 2.10.3

- H15 no longer freezes its own RF baseline: observations whose sole failure is H15 remain eligible for H15 history, while rows that also failed another heuristic remain excluded.
- PCI persistence now requires independent alternation episodes within the same ARFCN. Consecutive samples from one handover count once; a PCI must disappear and later return after another PCI to form a new episode.
- A stable one-way PCI replacement is treated as operator reconfiguration rather than RF instability. H15 weight, global thresholds, H1-H14/H16, TemporalConfidence and ThreatEpisodeTracker are unchanged.
- `check_export.py` reports inclusive calendar span, averages rows over days that contain data and identifies aggregate legacy `LTE_INDEX`/TA=0 stub patterns without declaring an individual TA=0 invalid.
- This H15 semantic correction is a dataset cut: comparisons across v2.10.2 and v2.10.3 must account for the changed RF-stability baseline.
- Database schema remains 18; existing history is preserved.
## 2.10.2

- RF-only Stable-Site neighbour context now follows the same retention policy as full neighbour identities: old day evidence and stale fingerprints are pruned deterministically.
- Stable-Site exports derive feature state, neighbour capability and maturity reason from the same policy used at runtime, avoiding duplicated decision logic.
- The export validator now checks PCI ranges per radio technology: LTE 0..503, NR 0..1007 and UMTS 0..511.
- Database schema remains 18. Existing history is preserved; H1-H16, scoring, trust and alert behaviour are unchanged.
## 2.10.1

- Stable-Site now preserves useful neighbour RF context when Android exposes RAT, ARFCN and PCI
  but withholds the complete Cell ID. RF fingerprints remain local site context and are never
  represented as authentic cell identities.
- Site maturity reports whether it is backed by full neighbour identities, RF-only context or no
  usable neighbour data. Three independent neighbour days remain mandatory in either evidence
  path; thousands of observations from one day cannot replace elapsed days.
- Stable-Site exports now include capability, maturity reason, separate full/RF counts and a
  dedicated RF fingerprint CSV. Schema 18 is an additive, history-preserving migration.
- RF context now validates RAT-specific channel and physical-ID ranges: LTE PCI 0..503, NR PCI
  0..1007 and UMTS PSC 0..511. Channel zero is retained where Android defines it as valid, while
  `UNAVAILABLE`, negative and out-of-range values are rejected. CI now runs the Stable-Site
  persistence and migration instrumentation suite on an Android emulator.

## 2.10.0

### Mobility Familiarity and Geometry export

- Added schema 17 and the experimental `UNKNOWN_ON_ROUTE`, `OBSERVED_ON_ROUTE` and
  `KNOWN_ON_ROUTE` contextual memory. These states never change detection, scoring, LocalCellTrust,
  Stable-Site, alerts or forensic capture.
- Trips open only from `MOVING`. Pending serving-cell edges remain isolated from aggregate
  `trip_count` until a trip with movement and at least three distinct cells closes successfully.
- Evaluation always reads committed prior-trip counters before the current trip is committed, so a
  route cannot validate itself. Repeated edges count once per independent valid trip.
- Open trip identity, cells and edges survive service/process restart. Geometry only reads route
  data and cannot open, close or mutate a trip.
- Detail for closed trips is retained for 90 days; aggregate `trip_count` is not reduced by pruning.
- Geometry presents route familiarity separately from LocalCellTrust and keeps accumulated transitions, confirmed historical trips and the pending trip contribution distinct.
- EXPORT GEOMETRY creates a ZIP with cell/edge CSV, retained trip summaries, GraphML and metadata. It contains cellular identities and route relationships but no GPS coordinates.
- Schema-16 databases migrate additively to schema 17 without deleting existing history.

## 2.9.1

### Emergency motion-sampling fix

- Motion classification is now driven by distinct GPS callbacks instead of cellular polling and
  repeated `lastKnownLocation` snapshots.
- A single inaccurate fix now yields `UNKNOWN` without immediately deleting the preceding good
  window. Four expected 15-second intervals (60 seconds) form the grace period; a longer gap
  invalidates the window and requires fresh evidence.
- Continuous GPS updates keep the 15-second time interval but use zero minimum distance so a truly
  stationary device can still collect the temporal evidence required for `STATIC_CONFIRMED`.
- Duplicate and out-of-order timestamps cannot add samples or duration. The 50 m positive-evidence
  accuracy limit, movement precedence, schema 16 and shadow-only enforcement remain unchanged.
- Stable-Site Terminal diagnostics now include the reason for an `UNKNOWN` motion result.

## 2.9.0

### Stable-Site Novelty Hold

- Added an additive schema-16 site-context layer. Existing history, RF baselines, transitions,
  local trust and forensic cases are retained unchanged; legacy rows are never assigned invented
  accuracy, motion or neighbour evidence.
- Precise successive GNSS fixes now derive `UNKNOWN`, `MOVING` or `STATIC_CONFIRMED`. Static state
  requires two minutes of coherent fixes; missing or worse-than-50 m accuracy always abstains.
- Sites are persisted only as a SHA-256-derived identifier of an approximately 500 m Web-Mercator
  grid cell. Four overlapping grids prevent ordinary GNSS jitter at a bucket edge from splitting
  every view of one physical site. Exact site coordinates are not added to the new tables.
- Serving and neighbour identities are aggregated per site with idempotent event keys and
  primary-keyed day rows. Volume from one day cannot manufacture multi-day maturity.
- Maturity is site-specific. Active shadow evaluation requires at least seven serving days, three static days,
  three neighbour-baseline days and thirty serving observations collected by schema 16.
- Earlier measurement starts in `SHADOW_READY` after at least five serving days and two static
  days; it remains non-enforcing. `ACTIVE` is the stricter 7/3/3/30 state described above.
- Overlapping grid candidates are evaluated read-only; only the selected candidate may persist one
  shadow trigger or change a hold, preventing fourfold telemetry inflation.
- Canonical-grid selection uses maturity first (`ACTIVE` > `SHADOW_READY` > `LEARNING` >
  `BOOTSTRAP`), followed by temporal/static/neighbour evidence and observations. `wouldTrigger`
  never influences which grid wins.
- At an active mature site, the engine records when a serving identity would enter
  `SITE_UNVERIFIED`, including after a restart by recovering the last established serving identity
  from site evidence. v2.9.0 keeps this decision in shadow mode: it does not freeze learning, open a
  case or change H1-H16, scores, temporal confirmation or normal alarm behaviour.
- Novelty is tracked as a persistent serving episode. A temporarily bad GPS fix, unknown motion or
  process restart cannot erase the established previous identity; a long dwell updates one episode
  instead of creating one row per polling cycle.
- One serving observation is not enough to become known: direct serving needs three distinct days,
  while two neighbour days provide an independent corroboration route. Shadow triggers are stored
  once per logical episode so their field false-positive rate can be measured after export.
- Added a dedicated Stable-Site ZIP export with aggregate sites, per-site serving/neighbour
  evidence, privacy-banded motion and shadow episodes. Exact coordinates are not exported.
- Schema tables are created only by `onCreate`/`onUpgrade`; `onOpen` no longer silently repairs a
  broken migration.
- The RF contradiction tracker remains active even when contextual novelty is present, so a real
  PCI/ARFCN contradiction always has priority over a future contextual hold.
- Site events and weak stale aggregates are pruned deterministically. A debug-only database method
  can reset site learning without touching any pre-existing evidence.
- Bootstrap poisoning remains unresolved by design: a transmitter present from first installation
  can influence the initial baseline. Multi-day, motion and neighbour gates reduce this risk but
  cannot establish an external ground truth.

## 2.8.1

### Frequent cells can complete their local trust history

- Removed a permanent 500-row ceiling from the temporal evidence used by local cell confidence.
  Distinct days, age, total clean observations and the three-per-day capped count now cover the
  complete 90-day learning window. A frequently observed cell can therefore reach the existing
  fourteen-day floor instead of remaining in `LEARNING` forever.
- Applied the same correction to quarantined PCI/ARFCN reconfiguration evidence. Existing safety
  gates remain mandatory, and a new pair cannot be accepted while the former pair remains visible.
- The detailed PCI, ARFCN and location scan remains bounded to the latest 500 clean rows. This
  preserves predictable runtime without imposing a time ceiling on trust. The existing eligibility
  gates for located and RF observations therefore still describe those 500 recent detailed rows.
- No database migration or reset is required. Schema 15, H1-H16, scoring, alert thresholds,
  Mobility Consistency and forensic rules are unchanged; existing history must be retained.
- Release metadata updated to `versionName 2.8.1` and `versionCode 25`.

## 2.8.0

### Evidence capture when the service starts on an already contradicted cell

- The trust-contradiction observer now reports a typed signal instead of a boolean:
  `TRANSITION` for an `ESTABLISHED → CHANGED` edge seen live, and `ON_START` for the first
  observation of an identity that is already `CHANGED` with real contradictions.
- `ON_START` closes a blind spot: the observer's state map lives in memory, so a service restart
  (device reboot, OTA, process death) emptied it and no case could ever be opened for a cell that
  was already contradicted when monitoring resumed — the situation where evidence matters most.
- `ON_START` cases are deduplicated against the database, not in memory: at most one case per
  complete cell identity per 24 hours, counting cases of any origin. A suppressed trigger still
  stays in the 60-second prebuffer, so a later real anomaly carries it into its own case.
- A live `TRANSITION` never consults the dedup window, and never opened duplicate cases to
  begin with. An `ON_START` that coincides with a real anomaly keeps the `ALARM` origin.

### Forensic writes fail loudly

- `insertForensicSample` now returns the inserted rowId, or `-1`. `SQLiteDatabase.insert()`
  swallows a full disk, a locked database and a foreign-key violation and returns `-1` without
  throwing, so discarding that value meant a capture could store nothing at all in silence.
- Three consecutive failed writes mark forensic storage as degraded and write one line to the
  terminal; a successful write clears the streak and reports the recovery. Same `CollectionHealth`
  logic already used for history writes, so an isolated lock does not raise a false alarm.
- The forensic recorder now runs on the same single-threaded dispatcher as incident writing.
  On `Dispatchers.IO` two cycles could enter concurrently: the recorder's mutex ordered them but
  did not guarantee *which* order, and a prebuffer flushed after its own trigger is not a timeline.

### The sample cap deletes whole cases instead of mutilating them

- `enforceForensicSampleCap` now deletes complete closed cases, oldest first, until the total fits
  under `MAX_FORENSIC_SAMPLES`. The previous version trimmed individual samples by rowid, which
  kept the cap and destroyed the property that makes a forensic package usable: a case missing its
  prebuffer and first minutes still listed its `ICD-…` code and sample count, and exported as if
  it were intact.
- Cases in `CAPTURING` or `POST_CAPTURE` are never candidates. If closed cases alone cannot bring
  the total under the cap, the maintenance log says so instead of cutting into a live capture.
- The decision lives in `core/ForensicRetentionPolicy`, without SQLite or Android, so it is tested
  directly; the SQL query is what guarantees only closed cases are ever offered to it.

### Foreign keys are actually enforced

- `forensic_samples` has declared `FOREIGN KEY(case_id) REFERENCES forensic_cases(id) ON DELETE
  CASCADE` since schema 14, but SQLite ignores that clause unless enabled per connection:
  `PRAGMA foreign_keys` defaults to OFF. The guarantee was written in the table and never applied.
- `onConfigure` now enables it, and sweeps orphan samples left by the previous behaviour first
  (guarded: on a first install the tables do not exist yet, and that is not an error).
- No schema migration: the database stays at version 15 and no row is rewritten.

### Physical heuristics use the observed radio technology

- H8 channel-range validation, H11's sparse-area distance threshold and H14 LTE band downgrade
  now use `radioTech` from the concrete `CellInfo` class instead of Android's display label.
- The same LTE anchor therefore produces the same decision whether the status bar says `4G LTE`
  or `5G NR (NSA)`. H6 and all heuristic weights remain unchanged.
- Added label-independence regressions for H8/H11/H14 and a production-chain regression covering
  analysis, local trust, episode correlation and temporal confirmation.

### Compatibility

- Heuristic weights, Bayesian scoring, temporal confirmation, episode tracking, baselines and
  learning are unchanged. H8/H11/H14 can change a verdict where the display label disagreed with
  the physical radio technology; record v2.8.0 installation as a dataset cut.
- Release metadata updated to `versionName 2.8.0` and `versionCode 24`; database schema remains 15.

## 2.7.2

### Silent evidence capture for established-cell contradictions

- Added a passive `ESTABLISHED → CHANGED` transition observer keyed by the existing complete cell
  identity. A transition with real trust contradictions opens one neutral observation case; a
  persistent `CHANGED` state cannot open another case every polling cycle.
- Reused the existing 60-second prebuffer and post-capture window. The triggering observation now
  records the trust state and contradiction set alongside the unchanged score and heuristics.
- Observation cases use the compatible `ICD-OBS-…` case-code prefix and a neutral bilingual label.
  No database migration is required.
- If normal temporal detection starts while the observation case is active, that same case is
  promoted and continued instead of creating duplicate evidence.
- Detection rules H1–H16, weights, thresholds, Bayesian scoring, temporal confirmation, episode
  tracking, baselines, learning, alarms and ordinary forensic cases remain unchanged.
- Updated release metadata to `versionName 2.7.2` and `versionCode 23`; database schema remains 15.

## 2.7.1

### Live foreground telemetry

- The visible monitoring screen now requests fresh radio telemetry every second, making the RSRP,
  RSRQ and geometry traces visibly continuous while the app is being watched.
- The existing 3-second screen-on and 10-second screen-off cadences remain unchanged outside the
  foreground app. Modem callbacks continue to deliver handovers immediately.

### Protected evidence deletion

- Added per-item deletion for closed incidents and completed or interrupted forensic cases.
- Permanent deletion requires typing exactly `BORRAR`; active incidents and captures cannot be
  deleted, and deleting a forensic case also removes its captured samples atomically.
- The confirmation reminds users to export forensic evidence before removing it.

### Searchable antenna explorer

- Added immediate search beside CSV export across CID, MCC, MNC, TAC, PCI, ARFCN and radio technology, with matching-cell suggestions.
- Replaced the long expanded observation list with three exclusive accordions: GPS locations, incoming/outgoing handovers and consolidated technical information.
- GPS entries retain timestamp, verification, precise coordinates and map access; handovers expose direction, frequency, confidence, status and recency.
- Added complete Spanish and English labels. Detection, database schema and CSV export remain unchanged.
### Interactive geometry explorer

- Reworked the local handover graph into a bounded interactive viewer without changing detection,
  learning, database schema or exported topology data.
- Added focal pinch zoom up to 20×, one-finger panning, double-tap zoom/reset and dedicated zoom,
  zoom-out and fit-all controls.
- The initial view still fits the complete learned route, while bounded navigation prevents the
  graph from being lost outside the canvas after zooming.
- Improved dense-graph inspection with a larger canvas, stable touch targets, a subtle reference
  grid, visible zoom level and clear highlighting of a selected cell and its connected routes.
- Added complete English and Spanish accessibility labels and interaction guidance.
- Updated development metadata to `versionName 2.7.1` and `versionCode 22`. Database schema 15 and
  all existing history remain compatible; no reset is required.

## 2.7.0

### Revocable local cell confidence

- Added conservative multi-signal episodes: independent evidence families can correlate across a
  90-second window, bridge short normal gaps for 30 seconds and still require temporal confirmation.
- Added three-fresh-observation hysteresis to H1 so transient empty neighbour snapshots cannot
  become retained episode evidence; corrected H12 so unavailable latency never penalizes silently.
- Suspicious observations are preserved for audit but excluded from geographic, signal, RF,
  reputation and transition learning to resist baseline poisoning.
- Added a bilingual local-confidence profile with `NEW`, `LEARNING`, `ESTABLISHED`, `CHANGED`
  and `QUARANTINED` states (localized in Spanish).
- Confidence grows slowly from clean observations capped per day, distinct days, elapsed time,
  GPS-backed history, stable PCI/ARFCN evidence and trusted handovers. OpenCellID remains separate
  context and never raises local confidence.
- Confidence is capped at 98%: it is explicitly historical local evidence, never proof that a
  transmitter is authentic or safe.
- Promotion requires at least fourteen distinct days, twenty capped clean observations, geographic
  evidence, RF history and trusted transitions. Suspicious observations cannot train the profile.
- A single RF change is displayed as `CHANGED`, is excluded from learning and freezes the trusted
  profile without sounding an alarm. Its 15% RF acceptance boundary matches H15.
- PCI is evaluated inside its ARFCN carrier, preventing legitimate carrier aggregation from
  appearing as an identity change. A genuinely new pair remains quarantined and can replace the
  old pair only after fourteen coherent days, sufficient located samples and trusted transitions,
  with no old PCI still active on that carrier during the previous 48 hours.
  Only an established RF contradiction combined with independent geographic or handover evidence
  enters the existing temporal-confirmation alarm path.
- Uses the existing history and transition tables; database schema 15 and existing data remain
  compatible. Updated metadata to `versionName 2.7.0` and `versionCode 21`.

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
