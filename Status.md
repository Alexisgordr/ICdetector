# ICdetection — Project status

The current state of the project in one page. What changed in each release is in
[CHANGELOG.md](CHANGELOG.md); the per-release status log up to 3.0.0-beta4 is archived in
[docs/history/status-history.md](docs/history/status-history.md).

## At a glance

| | Version | Version code | DB schema | Where |
|---|---|---|---|---|
| **In preparation** | 3.1.0 | 40 | 26 | Local branch `ICdetection-v3.1.0`, not published ([docs/3.1.0-progress.md](docs/3.1.0-progress.md)) |
| **Stable release** | 3.0.0 | 39 | 25 | GitHub Releases |
| **Field campaign** | 3.0.0 | 39 | 25 | Day 1 = a fresh database on 3.0.0 ([IMPORTANT.md](IMPORTANT.md)) |
| **Previous stable release** | v2.10.10 | 38 | 19 | GitHub Releases |
| **Previous campaign stage** | v2.10.5 | 33 | 19 | Baseline of the v2.10.x stage, closed |

- **App identity:** `com.alexisgordr.icdetector`, *ICdetection*, for every release, so a newer
  version installs over an older one.
- **3.0 needs a clean install** from v2.10.x (export, uninstall, install). From 3.0 on, every version
  updates the previous one.
- **Version code 39** is 3.0.0. The 3.0 betas used it too, with `versionName` 3.0.0-beta1 to
  3.0.0-beta4.

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
(`AppVersion`, for example `3.0.0+6bb5ed3`), so cuts can be applied per row. Every row recorded by
3.0.0 includes all of the changes below.

| Area | What changed |
|---|---|
| Ages and ordering | Exact UTC instant (#23); approximate before |
| Context columns | Time, position and service state of the moment the reading arrived (A03) |
| Location mode | Smart and adaptive rows can have fewer fresh positions (`LocationMode`); fix ages use the monotonic clock in every mode |
| Neighbours | A secondary carrier published as unregistered is no longer a neighbour (H1–H5, H7, H11, H16, density factor, Stable-Site) |
| Confirmation (all alarms) | Restarts after a signal loss or a gap of more than 2 min (#20); a held multi-signal episode no longer advances it; while H10 fails it counts on the set of ping-pong identities; without a modem timestamp only fresh deliveries count |
| H1 | Isolation streak restarts after a gap; `N/A` while pending (#20 #28); unknown freshness token is not "repeated"; `N/A` on a CSG cell with local history (campaign cut) |
| H2, H7 | Power compared only with neighbours of the same technology; `N/A` on a CSG cell with local history (campaign cut) |
| H3 | Serving cell compared with the SIM, not with the neighbours; roaming and borders are `N/A` with a stored reason (campaign cut) |
| H4 | Never subtracts points while not evaluated |
| H5 | Placeholders read as unavailable (#11); TAC compared only with neighbours of the same technology and operator; `N/A` on a CSG cell with local history (campaign cut) |
| H6, `TAUnit` | Timing Advance only from the same transmitter, range-checked; stub-zero evidence per technology and per site (#21 #33); geometric branch removed; `N/A` on a CSG cell with local history (campaign cut) |
| H8 | Data-quality flag: no points, no weight, not shown as a suspicion; its reason is still stored in the row (campaign cut) |
| H10, handover rows | Handover = change of the full serving identity (#19); a transient unreadable Cell ID is not a handover; H10 uses only a recent GPS speed; a serving change counts in the cycle that observes it (campaign cut) |
| H11 | Parser sentinels are not an RF difference; history chosen across days; `N/A` with a GPS fix older than 60 s or of unknown age, stored as `H11(fix_viejo)` (campaign cut) |
| H12 | Evaluated only on a real measurement; learning state (#26). Retired in 3.1.0: always `N/A` (see below) |
| H14 | Complete band table, same-eNodeB changes ignored, 5G SA, band context reset after a gap (#32 #10 #8 #20) |
| H15 | EARFCN 0 learnt, impossible PCIs ignored (#22); rows carrying the H15 reason stay in its history whatever else failed |
| H16 | Own recent fix at each end; no route across a service gap; incoherent only with the phone in the previous cell's zone; zones sampled across days |
| Anomaly confidence | Latency factor only when H12 fails; H6 in the RF-dominance group, damped by reputation; prior 0.5 % instead of 2 %, and no weight for H8 or H9 (campaign cut) |
| Local learning | Local confidence validates PCI/ARFCN; Stable-Site learns only from clean cycles; local profile: one evaluation per observation (schema 24) |

**Campaign cut.** Made on 3.0.0-beta4 without a new version name, in two parts. The first part
(H1–H3, H5–H8, anomaly confidence) applies to rows from builds after commit `3e67f8e`. The second
part (H10, H11 and the CSG condition of H1, H2, H5, H6 and H7) applies to rows from builds that
include commit `8f94d0e`; it was completed before Day 1, so a campaign started on such a build has
no rows before it. Rows from builds up to `0323822` have the first part only; every 3.0.0 row has
both. The scenario bench results each part changes are listed in [CHANGELOG.md](CHANGELOG.md).

The collection and storage fixes made with the second part (telephony watchdog, alarm-episode
clock, local-profile retention, export identifiers, retention clock guard, write-failure health)
change no rule and are not cuts.

## Dataset cuts in 3.1.0

3.1.0 (version code 40, in preparation) changes the rules below. Rows record `AppVersion`, so the
cut can be applied per row; the commit is the one that introduces each change.

| Area | What changed | Commit |
|---|---|---|
| H12, score, anomaly confidence | H12 retired: always `N/A`, no points, no likelihood ratio. Changes only rows where latency detection had been enabled | b1de2a9 |
| H14, score, anomaly confidence | A serving reading without a channel no longer erases the known band of the same cell (#37): H14 can now be evaluated (and fail) on the next handover where it used to be `N/A` | f27d44e |
| Local cell confidence | Without a valid ARFCN, a valid PCI is compared with every PCI learned for the cell (#38): a reading without a channel can now mark a mature cell `CHANGED` (and, with a failed H11 or H16, an alarm candidate) | c866e46 |
| H11, score, anomaly confidence | H11 uses the fix only with a known accuracy of 100 m or better (#46); otherwise `N/A`, stored as `H11(fix_impreciso)` | cb1951d |
| Anomaly confidence only | H1's likelihood ratio is no longer damped by neighbour density (#45), only by reputation: with H1 failing it was always halved. Score and alarms unchanged | 9945ef8 |

## Known limitations and open items

- **H9 (ciphering)** is reserved and always `N/A`: Android does not expose the modem's ciphering
  state to regular apps. **H12 (latency)** is retired from 3.1.0 and always `N/A`.
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
- **Femtocells (CSG)** are exempt from H1, H2, H5, H6 and H7 only after 10 rows on 2 days of local
  history, so a legitimate femtocell can alarm on its first day. After that its rows are clean, and
  nothing stops it from reaching `ESTABLISHED` or teaching Stable-Site as a serving cell. Both will
  be reviewed with field data.
- **Android 12 or later** is required (minSdk 31): there is no fallback for the telephony callbacks
  on Android 10 and 11.
- **One active SIM**; dual SIM is not supported. **Revoking the phone permission** while monitoring
  stops readings without a warning. See [MANUAL → Technical limitations](MANUAL.md#15-technical-limitations).
- **Forensic storage** has only a sample cap (20,000); an age or size cap is left for after the
  campaign.
- **Retention after a long pause.** When monitoring restarts after more than about 30 days, the
  age-based prune would delete more than 25 % of the history and is skipped until new rows bring
  the share down; the history can temporarily hold up to about four times the 120-day retention.
- **No ground truth, no negative control, limited coverage** — see
  [README → What the field campaign can and cannot show](README.md#-what-the-field-campaign-can-and-cannot-show).

## Quality checks

Run by CI on pull requests to `main`, on `main` and on release tags:

- JVM unit tests (`testDebugUnitTest`), Android lint and the release build.
- Instrumented storage, migration and backup tests on an emulator (schemas 15–19 → 20 and every
  later step up to 25; backups from schemas 23 and 24 restored on 25).
- `tools/check_export.py` regression tests, SQL affinity and query-plan checks, `when` exhaustiveness.

## Publishing 3.0.0

1. Merge PR #34 into `main`, tag `v3.0.0` and attach the signed APK to the GitHub release.
2. F-Droid is reviewing version code 39 (3.0.0); its release notes are
   `fastlane/metadata/android/*/changelogs/39.txt`.
3. Collect field data on 3.0.0 and test it on more devices.
