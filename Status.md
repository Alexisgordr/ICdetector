# ICdetection — Project status

The current state of the project in one page. What changed in each release is in
[CHANGELOG.md](CHANGELOG.md); the per-release status log up to 3.0.0-beta4 is archived in
[docs/history/status-history.md](docs/history/status-history.md).

## At a glance

| | Version | Version code | DB schema | Where |
|---|---|---|---|---|
| **Stable release** | v2.10.10 | 38 | 19 | GitHub Releases · used by the field campaign |
| **Development** | 3.0.0-beta4 | 39 | 21 | Branch `ICdetection-v3.0.0-beta` · draft PR #34 · source only, no APK published |
| **Campaign baseline** | v2.10.5 | 33 | 19 | Day 1 of the field dataset ([IMPORTANT.md](IMPORTANT.md)) |

- **App identity:** `com.alexisgordr.icdetector`, *ICdetection*, for stable releases and betas
  alike, so a beta updates to the release.
- **3.0 needs a clean install** from v2.10.x (export, uninstall, install). From 3.0 on, every version
  updates the previous one.
- **Betas share version code 39**, the next after v2.10.10. The 3.0 release keeps it; only the
  `versionName` changes.

## 3.0 roadmap — GitHub issue #27

Every item is implemented. Weights and thresholds are unchanged; what changed is the data the rules
receive and how evidence is confirmed.

| Phase | Scope | Issues | State |
|---|---|---|---|
| 1 — Data foundations | Schema 20: UTC instant, evaluation coverage, GPS accuracy and app version per row; ordering by observation time; stale cycles never publish | #23 #29 #30 #25 #24 | Done |
| 2 — Correct inputs | Full-identity handovers, neighbour placeholders, one frequency/PCI validator, complete LTE band table, Timing Advance per transmitter and per unit | #19 #11 #22 #32 #21 #33 | Done |
| 3 — Confirmation and rules | Coverage gaps restart confirmation, same-eNodeB band changes, latency states, H1 pending, H14 on 5G SA | #20 #10 #26 #28 #8 | Done (H6 on NR stays `N/A`) |
| 4 — Maintenance and docs | Maintenance backlog, validation limits of the campaign | #7 #31 | Done |

**Latest fixes on the branch** (found during testing after the roadmap was complete): schema 20 migration
creates missing tables; time, GPS and service state captured when the reading arrives (A03); a gap
of more than 2 minutes without readings breaks continuity like a signal loss (#20); one constant for
that gap, kept distinct from the 30 s stalled-modem gate and guarded by a test; the monitoring
notification is always silent and the app warns when alarms are muted (#35); a location mode setting
(continuous by default; smart and adaptive with bounded GPS attempts and retries of 2, 5 and 10
minutes when reception is poor; in smart, a handover to a new cell opens a 60 s GPS window) with the
mode stored per row (schema 21); the main-screen GPS
indicator uses the same validated position as the analysis.

## Dataset cuts in 3.0

3.0 data is not comparable with the v2.10.x campaign for the rules below. Within 3.0, each row
records the app version that observed it (`AppVersion`), so cuts can be applied per row.

| Area | Issues | What changed |
|---|---|---|
| Ages and ordering | #23 | Exact UTC instant from 3.0; approximate before |
| Context columns | A03 | Time, position and service state of the moment the reading arrived |
| Location mode | — | Smart and adaptive rows can have fewer fresh positions (`LocationMode`); in every mode, fix age is measured with the monotonic clock and stream fixes pass the same accuracy and continuity checks |
| Confirmation (all alarms) | #20 | Restarts after a signal loss or a gap of more than 2 min |
| H1 | #20 #28 | Isolation streak restarts after a gap; `N/A` while pending |
| H5 | #11 | LTE neighbour placeholders read as unavailable |
| H6, `TAUnit` | #21 #33 | Timing Advance only from the same transmitter; stub-zero evidence per technology |
| H10, handover rows | #19 | Handover = change of the full serving identity |
| H12 | #26 | Evaluated only on a real measurement; learning state |
| H14 | #32 #10 #8 #20 | Complete band table, same-eNodeB changes ignored, 5G SA, band context reset after a gap |
| H15, local trust | #22 | EARFCN 0 learnt; impossible PCIs ignored |

## Known limitations and open items

- **H9 (ciphering)** is always `N/A`: Android does not expose the modem's ciphering state to
  regular apps.
- **H6 on 5G NR** stays `N/A`: Android's NR Timing Advance has no defensible distance conversion yet.
- **MediaTek modems** that fill neighbours with placeholder values are outside the supported set;
  3.0 reads those values as unavailable, but the devices remain less reliable.
- **Stable-Site** runs in shadow mode only: it never changes a score or an alert.
- **No ground truth, no negative control, limited coverage** — see
  [README → What the field campaign can and cannot show](README.md#-what-the-field-campaign-can-and-cannot-show).

## Quality checks

Run on every push to the beta branch through draft PR #34:

- JVM unit tests (`testDebugUnitTest`), Android lint and the release build.
- Instrumented storage and migration tests on an emulator (schemas 15–19 → 20, and 20 → 21).
- `tools/check_export.py` regression tests, SQL affinity and query-plan checks, `when` exhaustiveness.

## Before the 3.0 release

1. Test the beta on real devices; keep the campaign phone on v2.10.x.
2. Change `versionName` from `3.0.0-beta4` to `3.0.0` (the version code stays 39).
3. Add a `## 3.0.0` section on top of [CHANGELOG.md](CHANGELOG.md).
4. Update the version rows of this page and the badges and status box at the top of
   [README.md](README.md).
5. Mark PR #34 ready, merge it into `main`, tag `v3.0.0` and attach the signed APK to the release.
