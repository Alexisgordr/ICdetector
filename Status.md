# ICdetection Status

## v2.10.2 — Stable-Site RF retention and export consistency

**Version code:** 30  
**Database schema:** 18 (unchanged)  
**Enforcement:** disabled / shadow only

RF-only neighbour evidence now expires under the same policy as full neighbour identities. Exported maturity uses the runtime policy directly, and PCI validation is radio-specific. Existing history is preserved; H1–H16, scoring, trust and alerts are unchanged.
## v2.10.1 — Stable-Site RF neighbour compatibility (local release candidate)

**Version code:** 29
**Database schema:** 18 (additive migration from 17)
**Enforcement:** disabled / shadow only

Stable-Site distinguishes full neighbour identities, local RF-only fingerprints and absent usable
neighbour data. RF-only evidence requires RAT, ARFCN and PCI and never becomes a Cell ID. Existing
history is retained; H1–H16, scoring, trust and alerts are unchanged.

## v2.10.0 — Mobility Familiarity and Geometry export (release candidate)

**Database schema:** 17 (additive migration from 16)  
**Security effect:** none  
**Feature flag:** `MOBILITY_FAMILIARITY_ENABLED = true`

The background service now owns a persistent mobility-trip engine. Route familiarity is descriptive
memory only and is physically excluded from ThreatAnalyzer, LocalCellTrust, Stable-Site, alerts and
forensics. Geometry remains read-only and has no trip lifecycle reference. Geometry displays route familiarity and exports coordinate-free CSV, GraphML and metadata in one ZIP.

## v2.9.1 — emergency motion-sampling correction (local release candidate)

**Version code:** 27  
**Database schema:** 16 (unchanged)  
**Enforcement:** disabled / shadow only

Motion classification now consumes each new GPS timestamp once and is independent from cellular
polling. One inaccurate fix abstains without erasing recent reliable evidence; after 60 seconds
without a reliable fix the window is invalidated. Continuous updates remain limited to a 15-second
interval and no longer require 20 m of movement. H1-H16, LocalCellTrust, scoring, neighbours,
site maturity, schema and migrations are unchanged.

## v2.9.0 — Stable-Site context and conservative novelty hold

**Release:** published
**Version code:** 26
**Database schema:** 16 (additive)
**Detection baseline:** H1-H16 and scoring unchanged

Schema 16 starts collecting privacy-reduced site, motion, serving and neighbour evidence. Clean
installs and upgrades both begin site learning from zero; upgrades retain every schema-15 record
for existing features. Each site matures independently. v2.9.0 measures `SITE_UNVERIFIED`
conditions in shadow mode; it reports `wouldTrigger` in the Terminal but does not alter trust or
forensics. Enforcement remains disabled until field data establishes its false-positive rate.
`SHADOW_READY` begins measurement at five serving days and two static days. `ACTIVE` remains the
stricter state requiring 7 serving days, 3 static days, 3 neighbour days and 30 observations.
The four overlapping grids are ranked only by maturity and evidence. Continuous novelty is stored
as one persistent episode, and the dedicated ZIP export exposes sites, neighbours, motion and
shadow outcomes for field validation. Enforcement remains disabled.

## v2.8.1 — complete temporal evidence for frequent cells

**Release:** local release candidate
**Version code:** 25
**Database schema:** 15 (unchanged)
**Detection baseline:** unchanged; historical admission is corrected

Local confidence and RF reconfiguration quarantine now calculate days, age and capped clean
observations across the complete 90-day window. The latest-500 limit remains only on detailed
PCI/ARFCN and location analysis. This removes the permanent ceiling affecting cells observed more
than roughly 36 times per day while retaining every existing reconfiguration safeguard, including
the veto while the previous RF pair remains visible. The location and RF eligibility minimums
continue to use that bounded set of 500 recent detailed observations.

Installation must preserve the existing database: there is no migration and no reset is needed.
H1-H16, scoring, confirmation, alarms, Mobility Consistency and forensic capture are unchanged.

## v2.8.0 — forensic integrity and physical radio decisions

**Release:** stable
**Version code:** 24
**Database schema:** 15 (unchanged)
**Detection baseline:** changed; record installation as a dataset cut

Forensic capture now survives an already-contradicted first observation without producing one
duplicate per restart. Deduplication requires a real stored sample, failed writes become visible,
retention removes whole closed cases, active captures remain protected, and SQLite foreign keys
are enforced. H8, H11 and H14 now derive physical decisions from `radioTech`; an LTE anchor whose
display label says 5G NSA is therefore treated consistently as LTE. H6 remains unchanged.

This is the final planned candidate before definitive field collection. Once validated, the
project enters a freeze of at least one month except for defects that threaten data integrity,
collection continuity, security or export.

## v2.7.2 — silent established-cell contradiction capture (development)

**Release:** local development / not published
**Version code:** 23
**Database schema:** 15 (unchanged)
**Detection baseline:** unchanged from v2.7.1

An actual `ESTABLISHED → CHANGED` transition with a non-empty trust-contradiction set now opens a
neutral forensic observation case. The trigger is scoped to the existing complete cell identity,
fires only on the transition, and reuses the production prebuffer and post-capture window. A later
normal anomaly promotes the active case rather than duplicating it. The observer does not feed into
scoring, temporal confirmation, episode tracking, learning, alerts or notifications.

The origin is encoded compatibly in the existing case code (`ICD-OBS-…`) and sample payload, so
schema 15 and all existing databases remain valid.

## v2.7.1 — interactive geometry and searchable antenna explorer

**Release:** stable
**Version code:** 22
**Database schema:** 15 (unchanged)
**Detection baseline:** unchanged from v2.7.0

While the activity is visible, explicit radio refresh now runs once per second so the main RSRP,
RSRQ and geometry traces respond continuously. Leaving the app restores the existing 3-second
screen-on cadence, and turning the screen off retains the 10-second low-power cadence. Event-driven
telephony callbacks continue to report handovers without waiting for any polling interval.

This development patch turns the local cell-geometry canvas into a bounded interactive explorer.
The complete learned route remains fitted on entry, while pinch zoom, one-finger panning,
double-tap navigation and explicit zoom/reset controls make dense or long-distance graphs
inspectable. Selection highlights a cell and its connected routes without changing any detection,
learning, persistence or export behavior. Existing history remains compatible and no reset is
needed.

The ANTENNAS view now supports immediate search across cellular identity and radio fields. Each cell exposes one detail section at a time: GPS observations with map access, directional handover summaries, or consolidated technical telemetry. This is a read-only presentation change and does not alter detection or stored data.

Incident and forensic history now supports deliberate per-item cleanup. Only closed incidents and
completed or interrupted forensic cases can be deleted, confirmation requires the exact word
`BORRAR`, and forensic samples are removed in the same database transaction as their case. Active
captures remain protected. Users should export evidence they want to preserve before deletion.

## v2.7.0 — detection hardening and revocable local confidence

**Release:** stable / field validation continues
**Version code:** 21
**Database schema:** 15 (unchanged)
**Detection baseline:** new revocable local-confidence layer

This recommended update strengthens multi-signal detection and learns a slow local confidence
profile for each complete cellular identity. It correlates independent evidence across short
episodes while preserving temporal confirmation, and shows confidence progress in the main status
card. Confidence is built only from local evidence, capped at
98%, requires evidence across at least fourteen distinct days, and is never described as proof of
authenticity. A lone parameter change is visible and excluded from learning but cannot alarm; only
an established RF change corroborated by independent geometry or handover evidence enters temporal
confirmation. Existing history is reused and no reset is needed.

RF identity is evaluated as an `ARFCN → PCI` relationship. New pairs remain in a separate
quarantine and cannot train the trusted profile. A coherent replacement needs fourteen days of
evidence and the previous PCI must have disappeared from that carrier for at least 48 hours.

## v2.6.0 — service refactor and bilingual interface

**Release:** stable
**Version code:** 19
**Database schema:** 15 (unchanged)
**Detection baseline:** unchanged

v2.6.0 decomposes the monitoring service into focused controllers and completes the Spanish and
English interface, including the forensic terminal and the H1–H16 diagnostic panel. External
verification now uses OpenCellID alone; WiGLE quota state can no longer alter a verdict.

The release changes no heuristic weight, temporal confirmation threshold, database schema or CSV
column. Existing v2.5.x data remains compatible and no database reset is required.

## v2.5.2 — GNSS continuity hardening

**Release:** stable
**Version code:** 18
**Database schema:** 15 (unchanged)
**Detection baseline:** unchanged; invalid GNSS candidates are stopped before geographic analysis

v2.5.2 fixes a field-confirmed path to a false H11 observation. A fresh GPS coordinate with stated
accuracy below 100 metres could still be many kilometres wrong; when enough time had elapsed since
the previous accepted fix, the old `400 km/h` cutoff could classify that excursion as plausible.
Repeated reads could then trigger the old three-rejection recovery path.

High-speed displacement is now provisional until a second, distinct GNSS timestamp confirms a
spatially coherent trajectory. Cached repetitions cannot advance the gate, and returning to the
last accepted area cancels the excursion. A real train remains supported after one confirmation
interval, while a single out-and-back GPS spike never reaches H11/H13 or valid-position storage.

The patch changes no heuristic weight, temporal threat threshold, database schema or CSV column.

## v2.5.1 — trusted-baseline hardening

**Release:** stable
**Version code:** 17
**Database schema:** 15 (unchanged)
**Detection baseline:** trusted learning and evidence-aware temporal confirmation introduced

v2.5.1 prevents suspicious or newly seen observations from immediately training the historical
reference used by H11, H13, H15 and H16. Every row remains stored and exportable, but a complete
cell identity is quarantined from learning until it has at least five clean observations
(`score >= 85`) spread across two different days. After promotion, only clean observations feed
the detector baselines.

Temporal confirmation still requires three distinct modem observations for isolated or weak
signals. It requires two only when at least two independent high-value checks among H11, H13, H15
and H16 fail together. Duplicate, stale and cached deliveries cannot advance either path.

No database migration, deletion or CSV change is required. Existing data remains useful, but this
is a detector-baseline change and its installation date must be recorded for campaign comparisons.

## v2.5.0 — offline cell geometry release

**Release:** stable
**Version code:** 16
**Database schema:** 15
**Detection baseline:** H16 shortcut handling updated in v2.5.0

v2.5.0 adds a fifth, read-only **GEOMETRÍA** tab. It renders observation centres, P90 radii,
handover routes and LTE eNodeB groupings from the device's own database without maps, tiles or
external location services. The view is diagnostic and never changes the score or learned data.

H16 now evaluates mature contradictory geometry before accepting the neighbour and previously
trusted-route shortcuts. Timing Advance hardware evidence is persisted across service restarts so
the same modem zero is not alternately exported as `LTE_INDEX` and `STUB_ZERO`. This release is a
dataset cut: record the installation date. Database schema and CSV columns remain unchanged.

An eNodeB grouping is not asserted to be a physical tower. Large separation is shown as a review
signal because legitimate distributed deployments or remote radio heads may share one logical
eNodeB.

### v2.4.0 — continuous GPS collection

v2.4.0 keeps the GPS-only stream and collection loop active throughout the lifetime of the
foreground service, including with the screen off. A partial wake lock reduces overnight CPU-sleep
gaps. Continuous GPS pauses only below 5% battery while unplugged and resumes automatically after
recovery or charging. The higher battery cost is accepted explicitly because contemporaneous device
coordinates feed H11, H13 and H16. Network-derived location remains forbidden, and all existing
accuracy, freshness and anti-jump validation remains in force.

This is a collection-continuity change, not a detector retune. Detection weights, thresholds,
database schema and CSV columns remain unchanged. Automatic reboot startup remains an explicit user
choice. Manual exemption from Android battery optimization is recommended and no privileged
exemption permission is requested.

v2.3.5 fixed long-running collection paths without changing the detector: bounded GPS refresh,
daily retention and forensic caps, an honest callback watchdog, locale-independent timestamps,
robust API request construction, an actionable 2G/3G warning, and bounded history rendering with
complete streaming export.

v2.3.4 fixed one campaign-data labeling defect without changing detection. A WiGLE source skipped
because its global quota cooldown is active no longer contributes a synthetic `ERROR`; the real
OpenCellID verdict is preserved. During that pause, `NOT_FOUND` means specifically that OpenCellID
returned its documented negative response, not that every public database was queried.

v2.3.3 introduced the protections for data a multi-month campaign produces against
four ways of losing it silently: a retention window shorter than the campaign itself, database
writes that fail without surfacing, forensic captures that could grow without bound, and an export
that could be truncated while reporting success. History retention is now 120 days, forensic
samples are capped, failed writes and collection gaps are reported in the notification, and the CSV
export streams from the cursor and verifies its own row count.

This release starts the **definitive field-collection campaign**. The project enters a release and
detection freeze for at least one month: no planned updates, heuristic changes, threshold tuning,
or schema changes will be made during that period. An emergency release remains possible only if
a defect threatens data integrity, collection continuity, security, or successful export.

## v2.3.2 — bounded temporal-recovery release

**Release:** stable
**Version code:** 11
**Database schema:** 15
**Detection baseline:** unchanged from v2.3.0

v2.3.2 prevents temporal confirmation from remaining frozen when a modem repeats or moves backwards
its `CellInfo` timestamp. The timestamp now belongs to the registered cell that actually enters the
analysis. A fresh callback may recover after 30 seconds, while cached `allCellInfo` data obtained
from an error path can never use that recovery mechanism.

The release also prevents empty callbacks from invalidating queued valid work, corrects permission
state after partial requests, preserves the bounded API-location cache when no cell is active, and
aligns the SQLite regression fixture with the production schema. Scoring, thresholds, schema, and
export columns remain unchanged.

## v2.3.1 — correctness and campaign-integrity release

**Release:** stable
**Version code:** 10
**Database schema:** 15
**Detection baseline:** unchanged from v2.3.0

v2.3.1 fixes faults that could reject valid public-database coordinates, fail to persist a later
successful verification, advance temporal confirmation through duplicate refreshes, mix cell-cache
state between overlapping callbacks, or leave a high-accuracy GPS listener registered. It also
hardens permission handling, CSV export, terminal logging, and status presentation.

The release does not add heuristics or change scoring weights, alert thresholds, database schema,
or export columns. The v2.3.0 H1–H16 field-validation baseline therefore remains comparable.

## v2.3.0 — mobility-transition coherence release

**Release:** stable  
**Version code:** 9  
**Database schema:** 15  
**Detection baseline:** H1–H16 frozen for field validation  
**Field-validation freeze:** active from 2026-09-18

v2.3.0 adds H16, a local-first check for physically incoherent serving-cell transitions. It does
not use a fixed tower-spacing rule: it compares the handover with actual device motion, GPS
quality, previous neighbours, locally learned cell zones, and trusted transition history.

### Field-validation freeze

Starting on **2026-09-18**, the v2.3.0 detection baseline enters a multi-week field-validation
freeze. H16 is part of this frozen baseline: the freeze begins after its introduction and does not
mean that v2.3.0 is identical to the earlier v2.2.x baseline.

During this period, no new heuristics, scoring weights, alert thresholds, database semantics, or
export fields are planned. Changes should be limited to critical correctness, security, privacy,
compatibility, or data-loss fixes that are clearly documented and assessed for their effect on the
collection campaign.

The purpose of the freeze is to collect and correlate representative field evidence before making
further detection changes, with particular attention to:

- H16 outcomes across stationary, walking, driving, and poor-GPS conditions.
- Transition-topology maturity and trusted-route formation.
- The distribution and causes of `PASS`, `FAIL`, and explained `N/A` results.
- Temporal progression through `1/3`, `2/3`, and `3/3`.
- False positives, short-lived anomalies, incident records, and forensic captures.
- Differences between devices, modem implementations, operators, radio technologies, and regions.

This is a stability and evidence-collection period, not a claim that detection is complete or that
an alert proves the presence of an IMSI catcher. Findings will be reviewed after several weeks of
data collection before another detection-policy change is considered.

### v2.3.0 detection changes

- H16 reports `COHERENT`, `INCOHERENT`, or an explained `N/A` in a dedicated mobility-sanity card.
- Poor GPS, stale observations, and immature baselines cause abstention rather than suspicion.
- A fail requires a short, near-stationary handover between mature, remote, non-overlapping local
  zones when the destination was not a neighbour and the route was not previously trusted.
- The low `-15` weight cannot trigger the `<70` alert threshold alone.
- H16 shares the Bayesian mobility group with H11, Timing Advance, ping-pong, and RF stability, so
  correlated evidence is not multiplied.
- Schema 15 persists transition counts. Only coherent transitions with mature geographic evidence
  increase the trusted count; failed and unavailable events cannot teach themselves as normal.
- The result remains active for 20 seconds, allowing the existing `1/3 → 3/3` confirmation system
  to observe it without leaving a permanent state on the cell.

### Retained v2.2.1 maintenance behavior

- WiGLE rate limiting is identified explicitly from HTTP `429` and quota-related API messages.
- A WiGLE quota failure now activates one global cooldown instead of one retry loop per cell.
- The cooldown survives service restarts and defaults to 24 hours when WiGLE provides no usable
  `Retry-After` value.
- OpenCellID and all local heuristic analysis continue normally while WiGLE is paused.
- The terminal explains the cooldown and approximate remaining time; a rate-limited source remains
  unavailable context and does not become evidence against a cell.
- While WiGLE is paused, `NOT_FOUND` means specifically that OpenCellID returned its documented
  negative response. WiGLE was not queried, so the label does not claim absence from every public
  database. A skipped WiGLE request contributes no synthetic `ERROR` to the combined verdict.
- The forensic prebuffer now uses monotonic elapsed time rather than wall-clock subtraction, so an
  NTP or manual clock adjustment cannot disturb its retention window.
- Safe access to the prebuffer head removes the nullable Kotlin release-build warning.
- Regression tests cover HTTP `429`, the observed `too many queries today` response, and ordinary
  non-quota service failures.

### Current capability status

| Capability | Status | Operational meaning |
|---|---|---|
| Heuristic engine H1–H16 | Frozen for field validation | H16 is active in v2.3.0; weights, thresholds, and detection policy are held stable during the collection period. |
| Temporal phases | Active | `1/3`, `2/3`, and `3/3` are visible and persisted with incident context. |
| Incident black box | Active | Opens at `1/3`; records the highest phase, score, confidence, reason, and diagnostic snapshot. |
| Rule diagnostics | Active | Rules report `PASS`, `FAIL`, or explained `N/A` according to available context. |
| Handover topology | Active | Read-only route explorer exposes cells, direction, frequency, trusted observations, last state, and recency. |
| Topology export | Active | ZIP includes cell/transition CSV, directed GraphML, metadata, and SHA-256 checksums after a privacy warning. |
| Baseline maturity | Active | Shows readiness of power, quality-fingerprint, PCI-identity, and reputation histories. |
| Forensic prebuffer | Active | Holds up to 60 seconds / 180 recent samples using monotonic timing. |
| External verification | Active | OpenCellID is the sole external source; WiGLE is no longer configured or queried. |
| Post-recovery capture | Active | Continues for 60 seconds; recurrence stays in the same case. |
| Forensic ZIP export | Active | Produces seven documented files plus SHA-256 integrity hashes. |
| Root/baseband visibility | Unavailable | Android userland still cannot expose all ciphering and baseband state on every device. |

### Incident lifecycle

The incident history is separate from antenna history and uses four states:

- `OBSERVING`: an anomaly reached `1/3` but is not confirmed.
- `CONFIRMED`: the episode reached `3/3`.
- `RECOVERED`: later samples returned to normal.
- `INTERRUPTED`: monitoring ended before the episode completed.

Sub-threshold observations are now reviewable without being mislabeled as confirmed alarms. A
dynamic transition between `N/A`, `PASS`, and `FAIL` is expected when Android supplies different
telemetry from one analysis cycle to the next.

### Forensic case lifecycle

The forensic recorder starts automatically at phase `1/3`. It combines up to 60 seconds of
pre-event context, the full temporal episode, and 60 seconds after recovery. Captures are bounded
to 30 minutes and use the states `CAPTURING`, `POST_CAPTURE`, `READY`, and `INTERRUPTED`.

The recorder can include serving and neighboring cells; MCC, MNC, TAC, Cell ID, PCI, ARFCN, band,
RSRP/dBm, RSRQ, SINR and Timing Advance when exposed; device GPS and accuracy; latency and external
verification state; threat phase, score and confidence; per-rule diagnostics; capability state;
device/Android information; and relevant terminal context.

It does **not** export API credentials, IMSI, IMEI, or the phone number.

### Export and integrity boundaries

Each exported case contains:

```text
case.json
timeline.csv
cells.csv
heuristics.csv
capabilities.json
terminal.log
SHA256SUMS.txt
```

The SHA-256 manifest detects later file modification. It is not a digital signature, does not
identify who collected the case, and does not by itself provide a legal chain of custody. Exact
device coordinates may be present, so exported cases must be treated as sensitive material.

### Storage and upgrade behavior

- Schema 13 adds incident records; schema 14 adds forensic cases and samples; schema 15 adds
  handover-transition history.
- Migrations are non-destructive for supported recent releases.
- Open forensic captures are marked `INTERRUPTED` after an unexpected restart.
- The existing 60-day cleanup policy also removes expired forensic cases and their samples.
- Official releases from v2.1.2 onward update in place when signed with the same project key.
- If an older installation uses a different signing key, Android requires uninstalling it first.
  Export any history you want to retain before uninstalling.

### Validation focus

The H16, topology, diagnostic, and forensic policies have regression coverage, but the evidence
still needs field validation across different modems, manufacturers, Android versions, operators,
mobility conditions, and radio environments. `N/A` is a valid outcome when the required telemetry
is not available. A case or alert is evidence of an observed anomaly, not definitive attribution
to a rogue base station.

---

## Historical v2.1 field-status record

## v2.1.1 — final release candidate. Second field-collection phase starts after build validation.

v2.1.1 is a **data-integrity release**. It adds no new heuristics and makes no new detection claims.
What it does is fix the reasons the *first* collection phase could not answer the questions it was
designed to answer, and add the tooling to check that the second one can.

### Final verification hardening

- The earlier campaign baseline started clean on 2026-09-15 with the frozen v2.1 build and
  continued through v2.2.x. That statement applies only to the historical campaign: v2.3.0 starts
  a new field-validation baseline on 2026-09-18 because it adds H16, schema 15, and
  transition-topology evidence. Results from the two baselines must be labelled separately when
  they are compared or correlated.
- **Quota-safe inconclusive retry.** `REJECTED` is retried after one hour, not every 15 minutes.
- **Ping-pong remains observable without creating a raw alarm.** A one-cycle detection writes an
  informational terminal line with no tone; only the confirmed alarm pipeline may sound.
- **Freeze audit closed three final consistency gaps.** A raw ping-pong observation cannot sound
  before `TemporalConfidence` confirms three cycles; every historical/cache lookup now includes
  radio; and history cards/statistics group by complete identity rather than CID alone.
- Database schema 12 adds a non-destructive radio-aware identity index. Existing rows are retained.

- **Discarded replies are not abandoned.** `REJECTED` and `NOT_FOUND` use independent one-hour
  retry timers. A retry occurs when the same cell is still serving or is
  observed again after the window. Restarting the service also clears the in-memory wait.
- **Latest field evidence is positive.** `alexis3.csv` contains 71 observations: 33 VERIFIED, 34
  REJECTED, 3 NOT_FOUND and 1 ERROR. Fourteen distinct cells have a verified API coordinate, so
  genuine verification and the API-coordinate-to-GPS distance path are both operating.
- **WiGLE response parsing fixed after field evidence.** WiGLE's cell endpoint returns the queried
  identity in `results[].id` as `MCC+MNC_AREA_CELLID` (for example,
  `21407_31601_79362070`). The previous defensive parser looked for separate CID fields, so it
  rejected even a correctly filtered result. v2.1 now validates the complete compound identity
  before accepting its coordinates; it does not restore the unsafe legacy `results[0]` shortcut.
- **A partial negative is not presented as a global negative.** If one source says `NOT_FOUND` but
  the other returns an error or unusable response, the combined result is `REJECTED` (inconclusive).
  “Not registered in public databases” requires agreement from every source actually queried.
- **Verification TTLs now measure API evidence, not observation activity.** Periodic samples copy
  the visible status and previously refreshed its database timestamp indefinitely. A negative is
  now retained for one hour only by the session cache and is then queried again; a stored VERIFIED
  is reusable only from a row that contains the API coordinates that created that verification.
- Every request writes `OpenCellID → STATUS` and `WiGLE → STATUS` to the terminal, making quota,
  permission, rejection and genuine absence distinguishable during field tests.
- **OpenCellID canonical fields are now read in the documented order.** Its LTE response can carry
  the requested area in `lac` while also including an auxiliary `tac: 0`; choosing `tac` first made
  a valid response look like an identity mismatch. The client now uses `lac`/`cellid` first and
  only falls back to `tac`/`cid` when the canonical field is absent. A documented `code: 1` carrying
  a temporary-unavailability notice is classified as `ERROR`, not as a missing cell.
- Radio technology is part of the in-memory, every identity-based database lookup/cache, and UI
  identity of a cell.
- Database updates are scoped by MCC, MNC, area, Cell ID **and radio**, preventing one technology
  from inheriting another technology's result.
- Legacy rows without a recorded radio remain available as historical evidence but are not reused
  as a current verification; the API is queried again.
- OpenCellID can return `VERIFIED` only after a successful HTTP response, no API error, matching
  identity and a coordinate that passes validation.
- GSM/WCDMA mapping, invalid identifiers and CSV replay of the `Radio` column are covered by the
  final code and regression checks.
- API verification callbacks update context only. They cannot emit tones, critical notifications
  or threat state, and they do not advance `TemporalConfidence`; a fresh real radio observation is
  requested and must pass through the normal three-cycle confirmation pipeline.
- The live identity row and expanded history now show MCC together with MNC (`MCC / MNC`) in a
  compact single-line layout that preserves the existing four-column monitor panel.

---

## ⚠️ Before installing: uninstall the previous version

**Uninstall ICdetection, then install v2.1.1 as a fresh install.** Two independent reasons, and both
point the same way:

1. **Signature.** If the v2.1.1 APK is not signed with the same keystore as the copy already on the
   device, Android refuses the update (`INSTALL_FAILED_UPDATE_INCOMPATIBLE`).
2. **A clean history, which you want anyway.** Rows written before v2.1 may hold an antenna
   coordinate from the verification APIs in the `lat`/`lon` fields, where your own GPS position
   belongs. The database migration deliberately does not rewrite them: after the fact there is no
   reliable way to tell which is which. Uninstalling clears the database, so the new baseline is
   built only from correct data.

If you want to keep your old records, **export the CSV before uninstalling** — bearing in mind that
in that old export `Lat`/`Lon` may be either your position or the antenna's, with no way to tell
them apart. That ambiguity is exactly what v2.1 ends.

---

## What the first collection phase found

It produced 1,065 records over 59 days (173 cells). Cross-referencing that export against the
source code showed four things, all of them uncomfortable and all of them useful:

- **The history was erasing the reason for almost every anomaly.** 33 of the 34 penalised rows
  recorded `OK` while carrying a real score of 85 or 75 — an internal contradiction. Exactly the
  material a false-positive study needs, thrown away at write time.
- **The recurring ~1,100 km coordinates never came from the GPS.** They came from the verification
  APIs and were being written over the device's own position. That is why none of the previous
  GPS hardening could stop them: the coordinate never passed through it.
- **Two heuristics were firing on carrier aggregation** — the phone's own modem attributing a
  secondary carrier's PCI and ARFCN to the serving cell. Between them they accounted for roughly
  27 of the 34 score deductions in two months, and not one was a real threat.
- **With a median of 2 samples per cell**, the RSRQ/SINR fingerprint had been dormant the whole
  period and would have stayed dormant for months.

All four are fixed.

## One more finding, and it is not a comfortable one

**The `VERIFIED` column of any history collected before v2.1 cannot be trusted.** The WiGLE query
was built with parameters WiGLE does not have (`mcc`, `mnc`, `lac`, `cellid`), and an unknown
parameter is ignored rather than rejected — so every lookup was an unfiltered search and the app
kept `results[0]`, a cell unrelated to the one asked about. Cells were being marked as verified on
the strength of somebody else's antenna.

Checked by hand against the OpenCellID API, cells the app had marked verified are simply not in
OpenCellID. The verifications were not lost in v2.1; they were never real. This is also where the
impossible coordinates came from: the distance barrier was doing its job on a malformed question.

The practical consequence for the second collection phase: expect a lot of "not in the public
databases". In areas where OpenCellID's coverage is thin that is the honest answer — which is
exactly why the verification no longer affects the score at all (see below).

## What changes for you in daily use

- **Sub-threshold observations are now recorded.** Heuristics that failed without reaching the
  alarm threshold appear marked `[sub-umbral]`, in grey. These are **not** alerts: nothing sounds,
  nothing is flagged as a threat, and they are excluded from the anomalous-cells counter. They
  exist so false positives can be studied.
- **The app samples the serving cell periodically** while you stay camped on it — roughly every
  5 minutes with the screen on, 15 with it off. It does not wake the GPS and does not force extra
  radio reads; it just persists the analysis the service was already doing. This is what finally
  lets the baselines and the RF fingerprint accumulate enough data to be worth anything.
- **The CSV export has six new columns:** `AnomalyConfidence`, `ApiLat`, `ApiLon`, `TA`, `TAUnit`
  and `TAMeters`. `Lat`/`Lon` are now your GPS position and nothing else.
- **Two distance readings in the GEOM panel.** One from the Timing Advance, one from the antenna
  coordinate in the public databases. They are shown separately and labelled, because their
  precision is not comparable.
- **The external verification no longer scores.** A verified cell used to gain +15 and an unknown
  one to lose 10. Both numbers are gone, for the same reason: they measured the database, not the
  antenna. The `-10` was charged to every cell in a poorly mapped area, legitimate ones included —
  a penalty everyone pays separates nobody. The `+15` did not raise a clean cell (already at 100);
  what it did was give it a 15-point cushion against real heuristic penalties, so being listed in a
  crowd-sourced database excused anomalous radio behaviour. And the methodological reason that
  outweighs both: this collection phase exists to find out **whether** the verification status
  carries signal, and that cannot be measured while it is baked into the score used as the
  reference. It stays as an independent label in the `Verified` column, next to a score that comes
  only from the local heuristic engine. `check_export.py` now prints both halves of that comparison.
- **"Not in the database" and "I could not ask" are no longer the same answer.** There is a fifth
  state, `REJECTED`, for a reply that arrives but does not survive our checks: an identity that does
  not match, a sentinel coordinate, an impossible distance, an incomplete body. `NOT_FOUND` is now
  reserved for the only two genuinely negative replies — OpenCellID's documented "cell not found",
  and WiGLE answering successfully with zero results. An exhausted quota, a rejected key or an HTTP
  404 is an `ERROR` and costs nothing. It matters for your data: the history of this phase must not
  record "unknown antenna" where what happened was "I did not trust that answer".
- **The whole identity of every reply is checked**, not just the cell id — operator, area, cell and
  radio technology, compared as numbers so that a "07" and a "7" are not mistaken for two different
  operators. The OpenCellID query also sends `radio` now, so the API cannot answer with the first
  record that happens to match the rest.
- **The CSV has a new `Radio` column** with the technology of each observation (LTE/NR/UMTS/GSM),
  taken from the class of the reading and not from the label on your status bar. `NetType` is still
  there, but it is the icon's string: the same cell flips between "4G" and "5G" without changing
  identity, so it was never something an analysis could rely on.
- **A verification expires after 30 days.** The historical fact stays in the history; what expires
  is the right to answer today with a confirmation from last spring. Public databases change, and
  cell identifiers get reconfigured and reused.
- **"Threat probability" is now "anomaly confidence (uncalibrated)".** Same number. The old label
  suggested a statistical validation that does not exist.
- **The header and the audit panel can no longer disagree.** They used different logic for the same
  score — a clean cell at 90% showed amber above and green below. Both now take the colour from the
  score, and the header shows what the public databases answered on its own line. A cell that is
  not in WiGLE/OpenCellID is **not** a threat: those databases are incomplete, and a new antenna
  takes months to appear in them — and as of this release it **costs nothing** either.

## About the Timing Advance

If your phone shows `Timing Advance: 0 (STUB_ZERO)`, your modem does not implement TA reporting: it
returns a constant 0 instead of declaring the value unavailable. The app detects this (three
distinct cells all reporting 0) and stops deriving any geometry from it.

This is not cosmetic. That permanent 0 fed H6's proximity branch, so any strong unverified cell
would have collected a −15 penalty indefinitely — a silent, perpetual false positive originating
in the phone's firmware, not in the network.

**There is no way to get the real TA on such a phone without root**, and that is not a limitation
of this app: `getTimingAdvance()` is the only public API there is. GrapheneOS has nothing to do
with it either — it does not touch the telephony HAL.

## What v2.1 does *not* do

It does not detect more than v2.0 did. It is quieter, more honest, and — for the first time — it
produces data that can be analysed. If you were hoping for a new signal against a sophisticated
IMSI-catcher, that is not what this is, and Android userland does not allow it: the app cannot
inspect RRC/NAS/baseband traffic the way dedicated hardware can.

---

## Historical v2.1 collection plan — superseded

At the time of v2.1, the project planned a three-month collection period with no new heuristics,
features, or tuning. This paragraph is retained as a historical record and is no longer the current
release policy. The active policy is the **v2.4.0 definitive field-collection freeze**, described at
the top of this document. The v2.3.0 detection baseline, including H16, remains frozen; v2.4.0 changes
only collection continuity and location availability.

The reason is specific. Everything the project needs next depends on data it does not have yet:

- which heuristics actually carry signal and which are noise,
- what the real false-positive rate looks like over months rather than over a bench,
- whether the Bayesian likelihood ratios — currently reasoned estimates, not measurements — hold
  up when contrasted with reality,
- whether the RF fingerprint, awake for the first time, is useful or just noisy.

None of that can be answered by reading code. It needs a few thousand honest observations.

**How to help.** Just use the app. When you export a CSV, you can check it yourself before sending
anything:

```
python3 tools/check_export.py historial.csv
```

It verifies the invariants the design guarantees, and prints how mature your history is — samples
per cell, how many cells have reached the thresholds H13 and the RF fingerprint need, and whether
your modem reports Timing Advance at all.

**If you share a history, blank the `Lat`, `Lon`, `ApiLat` and `ApiLon` columns first.** Those
coordinates are where you live, where you work and the route between them. Nothing that matters for
the analysis depends on them.

This remains a defensive anomaly detector, not a guaranteed IMSI-catcher detector. If you find a
bug or something that does not look right, please open an issue with as much detail as possible.

---

Spanish

En español:

## ⚠️ Antes de instalar: desinstala la versión anterior

**Desinstala ICdetection e instala la v2.1 como instalación limpia.** Dos motivos independientes, y
los dos apuntan a lo mismo:

1. **La firma.** Si el APK no está firmado con el mismo keystore que la copia que ya tienes,
   Android rechaza la actualización (`INSTALL_FAILED_UPDATE_INCOMPATIBLE`).
2. **Empezar con el historial limpio, que además es lo que interesa.** Los registros anteriores a
   v2.1 pueden llevar en `lat`/`lon` la coordenada de la antena que devolvieron las APIs de
   verificación, en lugar de tu posición GPS. La migración no intenta reescribirlos a propósito:
   después no hay forma fiable de distinguirlos.

Si quieres conservar tus registros antiguos, **exporta el CSV antes de desinstalar**.

## Qué encontró la primera fase de recolección

1.065 registros en 59 días, 173 celdas. Cruzarlos con el código enseñó cuatro cosas, todas
incómodas y todas útiles:

- **El historial borraba el motivo de casi todas las anomalías**: 33 de 34 filas penalizadas decían
  `OK` con un score real de 85 o 75. Justo el material que hace falta para estudiar falsos
  positivos, tirado a la basura al guardarlo.
- **Las coordenadas imposibles de los Países Bajos no venían del GPS.** Venían de las APIs de
  verificación y se escribían encima de tu propia posición. Por eso ninguno de los arreglos de GPS
  anteriores podía cazarlas: la coordenada nunca pasaba por ahí.
- **Dos heurísticas disparaban por agregación de portadoras** — el propio módem atribuye a la celda
  servidora el PCI y el ARFCN de una portadora secundaria. Entre las dos explicaban unas 27 de las
  34 penalizaciones de dos meses, y ninguna era una amenaza real.
- **Con una mediana de 2 muestras por celda**, la huella RSRQ/SINR llevaba dormida todo el periodo
  y así habría seguido durante meses.

Las cuatro están arregladas.

## Un hallazgo más, y no es cómodo

**La columna `VERIFIED` de cualquier historial anterior a v2.1 no es fiable.** La consulta a WiGLE
se construía con parámetros que WiGLE no tiene (`mcc`, `mnc`, `lac`, `cellid`), y un parámetro
desconocido no da error: se ignora. Así que cada consulta era una búsqueda sin filtrar y la app se
quedaba con `results[0]` — una celda sin relación con la preguntada. Se daban celdas por verificadas
con la antena de otro.

Comprobado a mano contra la API de OpenCellID: celdas que la app daba por verificadas sencillamente
no están en OpenCellID. Las verificaciones no se perdieron en v2.1; nunca fueron reales. De ahí
venían también las coordenadas imposibles: la barrera de distancia hacía bien su trabajo sobre una
pregunta mal formulada.

Lo que esto implica para la segunda fase de recolección: habrá muchas celdas "sin registro en las
bases públicas". Donde la cobertura de OpenCellID es escasa, esa es la respuesta honesta — y por eso
mismo la verificación ya no toca la puntuación (más abajo).

## Qué notarás en el uso diario

- **Se registran las observaciones sub-umbral**, marcadas `[sub-umbral]` y en gris. **No son
  alertas**: no suena nada, no se señalan como amenaza y no cuentan en el contador de celdas
  anómalas. Están para poder estudiar los falsos positivos.
- **Muestreo periódico de la celda servidora** mientras sigas en ella (5 min con pantalla
  encendida, 15 con ella apagada). No despierta el GPS ni fuerza lecturas extra de radio.
- **Seis columnas nuevas en el CSV:** `AnomalyConfidence`, `ApiLat`, `ApiLon`, `TA`, `TAUnit` y
  `TAMeters`. `Lat`/`Lon` son ya tu posición GPS y nada más.
- **Dos distancias en el panel GEOM**: una por Timing Advance y otra según la coordenada que las
  bases públicas atribuyen a la antena. Se muestran por separado porque su precisión no es
  comparable.
- **La verificación externa ya no puntúa.** Una celda verificada se llevaba +15 y una desconocida
  -10. Las dos cifras han caído por el mismo motivo: medían la base de datos, no la antena. El -10
  se lo cobraba cualquier celda en una zona poco mapeada, legítimas incluidas — y una penalización
  que paga todo el mundo no distingue a nadie. El +15 no subía una celda limpia (ya estaba en 100):
  lo que hacía era darle un colchón de 15 puntos contra penalizaciones reales de las heurísticas,
  con lo que estar en una base colaborativa excusaba un comportamiento de radio anómalo. Y la razón
  metodológica, que pesa más que las dos: esta fase existe para averiguar **si** el estado de
  verificación aporta señal, y eso no se puede medir mientras está metido dentro de la puntuación
  que sirve de referencia. Queda como etiqueta independiente en la columna `Verified`, junto a un
  score que sale solo del motor heurístico local. `check_export.py` imprime ya las dos mitades de esa
  comparación.
- **"No está en la base" y "no he podido preguntar" dejan de ser la misma respuesta.** Hay un quinto
  estado, `REJECTED`, para la respuesta que llega pero no supera nuestras comprobaciones: identidad
  que no cuadra, coordenada centinela, distancia imposible, cuerpo incompleto. `NOT_FOUND` queda
  reservado a las dos únicas respuestas realmente negativas — el "cell not found" documentado de
  OpenCellID y un WiGLE que contesta bien con cero resultados. Una cuota agotada, una key rechazada
  o un HTTP 404 son `ERROR` y no cuestan nada. Importa para tus datos: el historial de esta fase no
  puede registrar "antena desconocida" donde lo que pasó fue "no me fío de esa respuesta".
- **Se comprueba la identidad entera de cada respuesta**, no solo la Cell ID: operador, área, celda
  y tecnología, comparadas como números para que un "07" y un "7" no parezcan dos operadores
  distintos. La consulta a OpenCellID envía además `radio`, así que la API ya no puede contestar con
  el primer registro que cuadre con el resto.
- **Columna nueva `Radio` en el CSV** con la tecnología de cada observación (LTE/NR/UMTS/GSM),
  tomada de la clase de la lectura y no de la etiqueta de tu barra de estado. `NetType` sigue ahí,
  pero es la cadena del icono: la misma celda alterna entre "4G" y "5G" sin cambiar de identidad,
  así que nunca sirvió para analizar nada.
- **Una verificación caduca a los 30 días.** El hecho histórico se queda en el historial; lo que
  caduca es el derecho a contestar hoy con una confirmación de la primavera pasada. Las bases
  públicas cambian y los identificadores celulares se reconfiguran y se reutilizan.
- **"Amenaza estimada" pasa a "confianza de anomalía (no calibrada)"**. El número es el mismo; la
  etiqueta ha dejado de prometer una validación estadística que no existe.
- **La cabecera y el panel de auditoría ya no pueden contradecirse.** Usaban criterios distintos
  para el mismo score: una celda limpia al 90% salía ámbar arriba y verde abajo. Ahora el color
  sale del score en los dos sitios, y lo que contestaron las bases públicas se muestra aparte, en
  su propia línea. Que una celda no esté en WiGLE/OpenCellID **no** la hace sospechosa: esas bases
  están incompletas y una antena recién desplegada tarda meses en aparecer — y desde esta versión
  **tampoco resta nada**.

## Sobre el Timing Advance

Si tu móvil muestra `Timing Advance: 0 (STUB_ZERO)`, tu módem no implementa el reporte de TA:
devuelve un 0 constante en lugar de declarar que el dato no está disponible. La app lo detecta
(tres celdas distintas reportando solo 0) y deja de deducir geometría de ahí.

No es cosmético: ese 0 permanente alimentaba la rama de proximidad de H6, así que cualquier celda
con señal fuerte y sin verificar se habría llevado un −15 indefinido. Un falso positivo perpetuo
originado en el firmware del teléfono, no en la red.

**Sin root no hay forma de obtener el TA real en un teléfono así**, y no es una limitación de la
app: `getTimingAdvance()` es la única API pública que existe. GrapheneOS tampoco tiene nada que
ver — no toca el HAL de telefonía.

## Endurecimiento final de la verificación

- **Las respuestas descartadas no se abandonan.** `REJECTED` y `NOT_FOUND` tienen temporizadores
  independientes de una hora. Se reintenta cuando la misma celda continúa activa o
  vuelve a aparecer después de esa ventana. Reiniciar el servicio también elimina la espera en
  memoria.
- **La última evidencia de campo es positiva.** `alexis3.csv` contiene 71 observaciones: 33
  VERIFIED, 34 REJECTED, 3 NOT_FOUND y 1 ERROR. Catorce celdas distintas conservan coordenadas API
  verificadas, confirmando que funcionan tanto la verificación real como la distancia API/GPS.
- **Corregida la lectura de respuestas válidas de WiGLE.** La identidad llega en `results[].id`
  como `MCC+MNC_AREA_CELLID` (por ejemplo, `21407_31601_79362070`), no en campos CID separados.
  Ahora se interpreta y compara completa antes de aceptar la coordenada; no se recupera el antiguo
  atajo inseguro que aceptaba `results[0]` sin saber a qué celda correspondía.
- **Una negativa parcial ya no se presenta como negativa global.** Si una fuente responde
  `NOT_FOUND` pero la otra da error o una respuesta inutilizable, el resultado combinado será
  `REJECTED` (inconcluso). “Sin registro en bases públicas” exige que todas coincidan.
- **Los TTL ya miden evidencia de la API, no actividad de observación.** Las muestras periódicas
  copiaban el estado y renovaban su fecha indefinidamente. Una negativa queda una hora en la caché
  de sesión y después se consulta otra vez; VERIFIED solo se reutiliza desde una fila que conserve
  las coordenadas API que originaron la confirmación.
- Cada consulta muestra `OpenCellID → ESTADO` y `WiGLE → ESTADO` en el terminal.
- **OpenCellID ya lee primero sus campos canónicos documentados.** Una respuesta LTE puede traer el
  área pedida en `lac` y además un `tac: 0` auxiliar; escoger antes ese cero convertía una respuesta
  válida en identidad incompatible. Ahora se usan primero `lac`/`cellid`, con `tac`/`cid` solo como
  respaldo. Un `code: 1` con aviso de indisponibilidad temporal se marca `ERROR`, no celda ausente.
- La tecnología de radio forma parte de la identidad de la celda en memoria, base de datos y UI.
- Las actualizaciones de la base se limitan por MCC, MNC, área, Cell ID **y radio**, evitando que
  una tecnología herede el resultado de otra.
- Las filas antiguas sin radio se conservan como evidencia histórica, pero no se reutilizan como
  verificación actual: se vuelve a consultar la API.
- OpenCellID solo puede devolver `VERIFIED` tras HTTP correcto, ausencia de error de API, identidad
  coincidente y coordenada aceptada.
- El mapeo GSM/WCDMA, las identidades inválidas y el replay de la columna `Radio` quedan cubiertos
  por el código final y sus comprobaciones de regresión.
- Los callbacks de verificación API solo actualizan contexto. No pueden emitir tonos,
  notificaciones críticas ni estado de amenaza, y no avanzan `TemporalConfidence`; se solicita
  una lectura real que debe atravesar el flujo normal de confirmación de tres ciclos.
- La fila de identidad en vivo y el historial expandido muestran MCC junto a MNC (`MCC / MNC`) en
  una disposición compacta que conserva las cuatro columnas del monitor.

## Plan histórico de recolección de v2.1 — sustituido

En la etapa de v2.1 se planteó una recolección de tres meses sin heurísticas, funciones ni ajustes
nuevos. Este apartado se conserva como registro histórico y ya no describe la política vigente.
La política actual es la **congelación de validación de campo de v2.3.0 durante varias semanas,
iniciada el 18 de septiembre de 2026**, tal como se explica al principio del documento. H16 forma
parte de esta nueva línea base congelada. Solo se contemplan correcciones críticas de funcionamiento,
seguridad, privacidad, compatibilidad o pérdida de datos.

El motivo es concreto: todo lo que el proyecto necesita a continuación depende de datos que
todavía no existen — qué heurísticas aportan señal de verdad y cuáles son ruido, cuál es la tasa
real de falsos positivos a lo largo de meses, si los likelihood ratios del bayesiano (hoy
estimaciones razonadas, no medidas) aguantan el contraste con la realidad, y si la huella RF,
despierta por primera vez, sirve o solo hace ruido. Nada de eso se contesta leyendo código.

**Cómo ayudar:** usa la app. Antes de mandar nada, puedes revisar tu propio export:

```
python3 tools/check_export.py historial.csv
```

Comprueba las invariantes que el diseño garantiza y te dice cómo de maduro está tu historial —
muestras por celda, cuántas llegan a los umbrales que necesitan H13 y la huella RF, y si tu módem
reporta Timing Advance.

**Si compartes un historial, vacía antes las columnas `Lat`, `Lon`, `ApiLat` y `ApiLon`.** Esas
coordenadas son dónde vives, dónde trabajas y el camino entre las dos. Nada de lo que importa para
el análisis depende de ellas.

Esto sigue siendo un detector defensivo de anomalías, no un detector garantizado de IMSI-catchers.
Si encontráis un fallo o algo que no cuadre, abrid una issue con todo el detalle que podáis.

Gracias a todos los que estáis probando la app. Vuestros datos son literalmente lo que ha hecho
posible esta versión.

Alexis.

