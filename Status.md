# ICdetection — Project status

The current state of the project in one page. What changed in each release is in
[CHANGELOG.md](CHANGELOG.md); the per-release status log up to v2.10.10 is archived in
[docs/history/status-history.md](docs/history/status-history.md).

## At a glance

| | Version | Version code | DB schema | Where |
|---|---|---|---|---|
| **Stable release** | v2.10.10 | 38 | 19 | GitHub Releases · used by the field campaign |
| **Campaign baseline** | v2.10.5 | 33 | 19 | Day 1 of the field dataset ([IMPORTANT.md](IMPORTANT.md)) |
| **Next version** | 3.0.0-beta4 | 39 | 20 | Branch [`ICdetection-v3.0.0-beta`](https://github.com/Alexisgordr/ICdetector/tree/ICdetection-v3.0.0-beta) · draft PR #34 · source only |

- **Detection baseline:** H1–H16, weights, thresholds, temporal confidence, Local Cell Trust and
  Stable-Site are frozen for the campaign. Releases inside the freeze only fix bugs.
- **App identity:** `com.alexisgordr.icdetector`, *ICdetection*.

## Dataset cuts during the campaign

| Release | Rule | What changed |
|---|---|---|
| v2.10.6 – v2.10.8 | — | Interface, localization, notifications and stability only |
| v2.10.9 | H10 Ping-Pong | Only cell changes within the last 10 s count; `N/A` without a GPS speed |
| v2.10.10 | H8 Frequency | An unavailable frequency is `N/A` instead of failing |

How to treat earlier data is explained in [IMPORTANT.md](IMPORTANT.md#dataset-cuts-during-the-campaign).

## Next: 3.0

The 3.0 roadmap (GitHub issue #27) is implemented on the beta branch and published as source only. It
changes the database to schema 20 (UTC instant, evaluation coverage, GPS accuracy and app version per
row), corrects the inputs of several rules and makes confirmation restart after coverage gaps.
Weights and thresholds are unchanged. 3.0 needs a clean install from v2.10.x and is **not part of
the campaign**.

## Known limitations

- **H9 (ciphering)** is always `N/A`: Android does not expose the modem's ciphering state to
  regular apps.
- **MediaTek modems** that fill neighbour cells with placeholder values (TAC `65535`, Cell ID
  `268435455`) produce permanent false TAC warnings on v2.10.x (#11, fixed in 3.0).
- **Stable-Site** runs in shadow mode only: it never changes a score or an alert.
- **No ground truth, no negative control, limited coverage** — see
  [README → What the field campaign can and cannot show](README.md#-what-the-field-campaign-can-and-cannot-show).

## Quality checks

Run in CI on every push to `main` and every tag:

- JVM unit tests (`testDebugUnitTest`), Android lint and the release build.
- Instrumented storage and migration tests on an emulator.
- `tools/check_export.py` regression tests, SQL affinity checks and `when` exhaustiveness.
