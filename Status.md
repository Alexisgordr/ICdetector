# ICdetection — Project status

The current state of the project in one page. What changed in each release is in
[CHANGELOG.md](CHANGELOG.md); the per-release status log up to 3.0.0-beta4 is archived in
[docs/history/status-history.md](docs/history/status-history.md).

## At a glance

| | Version | Version code | DB schema | Where |
|---|---|---|---|---|
| **Stable release** | v2.10.10 | 38 | 19 | GitHub Releases |
| **Development** | 3.0.0-beta4 | 39 | 25 | Branch `ICdetection-v3.0.0-beta` · draft PR #34 · source only, no APK published |
| **Field campaign** | 3.0.0-beta4 | 39 | 25 | Day 1 = a fresh database on a build with the campaign cut ([IMPORTANT.md](IMPORTANT.md)) |
| **Previous campaign stage** | v2.10.5 | 33 | 19 | Baseline of the v2.10.x stage, closed |

- **App identity:** `com.alexisgordr.icdetector`, *ICdetection*, for stable releases and betas
  alike, so a beta updates to the release.
- **3.0 needs a clean install** from v2.10.x (export, uninstall, install). From 3.0 on, every version
  updates the previous one.
- **Betas share version code 39**, the next after v2.10.10. The 3.0 release keeps it; only the
  `versionName` changes.

## 3.0 roadmap — GitHub issue #27

Every item is implemented. The roadmap kept score weights and the alarm threshold; what it changed
is the data the rules receive and how evidence is confirmed. The later campaign cut (below) changes
how some evidence is weighed; the alarm threshold is still unchanged.

| Phase | Scope | Issues | State |
|---|---|---|---|
| 1 — Data foundations | UTC instant, evaluation coverage, GPS accuracy and app version per row; ordering by observation time; stale cycles never publish | #23 #29 #30 #25 #24 | Done |
| 2 — Correct inputs | Full-identity handovers, neighbour placeholders, one frequency/PCI validator, complete LTE band table, Timing Advance per transmitter and per unit | #19 #11 #22 #32 #21 #33 | Done |
| 3 — Confirmation and rules | Coverage gaps restart confirmation, same-eNodeB band changes, latency states, H1 pending, H14 on 5G SA | #20 #10 #26 #28 #8 | Done (H6 on NR stays `N/A`) |
| 4 — Maintenance and docs | Maintenance backlog, validation limits of the campaign | #7 #31 | Done |

Beyond the roadmap, 3.0 adds a location-mode setting, backup and restore, and a local statistical
profile in shadow mode, and it went through a review of the whole engine (rule inputs, scoring,
confirmation, local learning, geometry, the analysis cycle and the queries the engine learns from).
[CHANGELOG.md](CHANGELOG.md) describes every change.

## Dataset cuts in 3.0

3.0 data is not comparable with v2.10.x data for the rules below, and some of them also
changed between 3.0 betas. Each row records the app version and build commit that observed it
(`AppVersion`, for example `3.0.0-beta4+6bb5ed3`), so cuts can be applied per row.

| Area | What changed |
|---|---|
| Ages and ordering | Exact UTC instant (#23); approximate before |
| Context columns | Time, position and service state of the moment the reading arrived (A03) |
| Location mode | Smart and adaptive rows can have fewer fresh positions (`LocationMode`); fix ages use the monotonic clock in every mode |
| Neighbours | A secondary carrier published as unregistered is no longer a neighbour (H1–H5, H7, H11, H16, density factor, Stable-Site) |
| Confirmation (all alarms) | Restarts after a signal loss or a gap of more than 2 min (#20); a held multi-signal episode no longer advances it; while H10 fails it counts on the set of ping-pong identities; without a modem timestamp only fresh deliveries count |
| H1 | Isolation streak restarts after a gap; `N/A` while pending (#20 #28); unknown freshness token is not "repeated"; `N/A` on a CSG cell (campaign cut) |
| H2, H7 | Power compared only with neighbours of the same technology; `N/A` on a CSG cell (campaign cut) |
| H3 | Serving cell compared with the SIM, not with the neighbours; roaming and borders are `N/A` with a stored reason (campaign cut) |
| H4 | Never subtracts points while not evaluated |
| H5 | Placeholders read as unavailable (#11); TAC compared only with neighbours of the same technology and operator; `N/A` on a CSG cell (campaign cut) |
| H6, `TAUnit` | Timing Advance only from the same transmitter, range-checked; stub-zero evidence per technology and per site (#21 #33); geometric branch removed; `N/A` on a CSG cell (campaign cut) |
| H8 | Data-quality flag: no points, no weight, not shown as a suspicion; its reason is still stored in the row (campaign cut) |
| H10, handover rows | Handover = change of the full serving identity (#19); a transient unreadable Cell ID is not a handover; H10 uses only a recent GPS speed |
| H11 | Parser sentinels are not an RF difference; history chosen across days |
| H12 | Evaluated only on a real measurement; learning state (#26) |
| H14 | Complete band table, same-eNodeB changes ignored, 5G SA, band context reset after a gap (#32 #10 #8 #20) |
| H15 | EARFCN 0 learnt, impossible PCIs ignored (#22); rows carrying the H15 reason stay in its history whatever else failed |
| H16 | Own recent fix at each end; no route across a service gap; incoherent only with the phone in the previous cell's zone; zones sampled across days |
| Anomaly confidence | Latency factor only when H12 fails; H6 in the RF-dominance group, damped by reputation; prior 0.5 % instead of 2 %, and no weight for H8 or H9 (campaign cut) |
| Local learning | Local confidence validates PCI/ARFCN; Stable-Site learns only from clean cycles; local profile: one evaluation per observation (schema 24) |

**Campaign cut.** Made on 3.0.0-beta4 after collection had started, without a new version name.
Rows recorded by builds up to commit `3e67f8e` (the `AppVersion` commit is `3e67f8e` or one of its
ancestors) are before it; rows from later builds are after it. The scenario bench results it changes
are listed in [CHANGELOG.md](CHANGELOG.md).

## Known limitations and open items

- **H9 (ciphering)** is reserved and always `N/A`: Android does not expose the modem's ciphering
  state to regular apps.
- **H3 near borders** can fail when your operator's foreign partner is configured as non-roaming and
  no neighbour reports its MCC; see the [MANUAL](MANUAL.md#8-main-heuristic-families).
- **H6 on 5G NR** stays `N/A`: Android's NR Timing Advance has no defensible distance conversion yet.
- **H16 in Smart and Adaptive location modes** is mostly `N/A`: it needs a recent fix at both ends of
  a handover.
- **MediaTek modems** that fill neighbours with placeholder values are outside the supported set;
  3.0 reads those values as unavailable, but the devices remain less reliable.
- **Stable-Site** runs in shadow mode only: it never changes a score or an alert.
- **Local profile** runs in shadow mode; its option only displays. It is not validated, its levels
  are uncalibrated, a transmitter present while it learns becomes normal and slow multi-day drift is
  only slowed. Widening its role needs the evidence listed in the
  [MANUAL](MANUAL.md#local-profile-shadow-mode-experimental).
- **H3 or H15 alone** leave the score at 70, on the alarm boundary; whether that is right will be
  reviewed with field data.
- **Femtocells (CSG)** with H1, H2, H5, H6 and H7 abstaining have clean rows, so nothing stops one
  from reaching `ESTABLISHED` or teaching Stable-Site as a serving cell. This is how it works today;
  it will be reviewed with field data.
- **Android 12 or later** is required (minSdk 31): there is no fallback for the telephony callbacks
  on Android 10 and 11.
- **One active SIM**; dual SIM is not supported. **Revoking the phone permission** while monitoring
  stops readings without a warning. See [MANUAL → Technical limitations](MANUAL.md#15-technical-limitations).
- **Forensic storage** has only a sample cap (20,000); an age or size cap is left for after the
  campaign.
- **No ground truth, no negative control, limited coverage** — see
  [README → What the field campaign can and cannot show](README.md#-what-the-field-campaign-can-and-cannot-show).

## Quality checks

Run on every push to the beta branch through draft PR #34:

- JVM unit tests (`testDebugUnitTest`), Android lint and the release build.
- Instrumented storage, migration and backup tests on an emulator (schemas 15–19 → 20 and every
  later step up to 25; backups from schemas 23 and 24 restored on 25).
- `tools/check_export.py` regression tests, SQL affinity and query-plan checks, `when` exhaustiveness.

## Before the 3.0 release

1. Collect field data with the beta (it is the campaign build) and test it on more devices.
2. Change `versionName` from `3.0.0-beta4` to `3.0.0` (the version code stays 39).
3. Add a `## 3.0.0` section on top of [CHANGELOG.md](CHANGELOG.md).
4. Update the version rows of this page and the badges and status box at the top of
   [README.md](README.md).
5. Mark PR #34 ready, merge it into `main`, tag `v3.0.0` and attach the signed APK to the release.
