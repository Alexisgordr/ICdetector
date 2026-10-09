<div align="center">

# 📡 ICdetection

### Open-source, local-first cellular anomaly auditor for Android — no root required.

*Observe the cellular environment. Keep the evidence. Never overclaim.*

[![License: GPL v3](https://img.shields.io/badge/License-GPLv3-blue.svg)](LICENSE)
![Platform](https://img.shields.io/badge/Platform-Android%2010%2B-green.svg)
![Root Required](https://img.shields.io/badge/Root-Not%20Required-brightgreen.svg)
![Release](https://img.shields.io/badge/Release-v2.10.10-brightgreen.svg)
![Baseline](https://img.shields.io/badge/Dataset%20baseline-v2.10.5-blue.svg)
![Phase](https://img.shields.io/badge/Phase-Field%20collection%20freeze-blue.svg)
![Schema](https://img.shields.io/badge/DB%20schema-19%20(beta%3A%2020)-informational.svg)
![Beta](https://img.shields.io/badge/Beta-3.0.0--beta1%20(local)-orange.svg)
[![Featured in Awesome Telco](https://img.shields.io/badge/Featured%20in-Awesome%20Telco-6f42c1.svg)](https://github.com/ravens/awesome-telco#imsi-catcher-detection)

[**What's new**](#-whats-new-in-v21010) ·
[**Freeze**](#-the-field-collection-freeze) ·
[**Detection engine**](#-detection-engine) ·
[**Exports**](#-forensic-logging--exports) ·
[**Manual**](MANUAL.md) ·
[**Changelog**](CHANGELOG.md)

</div>

<table>
  <tr>
    <td width="60%" valign="top">
      <h3>Forensic Cellular Monitoring Interface</h3>
      <p>
        ICdetection is a local-first Android tool for cellular-network auditing,
        anomaly analysis and real-time radio telemetry visualization.
      </p>
      <ul>
        <li><strong>Forensic Terminal:</strong> structured security and radio-event logging.</li>
        <li><strong>Telemetry:</strong> real-time RSRP, RSRQ, SINR and Timing Advance when available.</li>
        <li><strong>Security Engine:</strong> conservative multi-heuristic cellular anomaly analysis.</li>
        <li><strong>Radio Context:</strong> serving/secondary carriers, bands, CSG and service state.</li>
        <li><strong>Forensics:</strong> incident black box, case capture and verifiable exports.</li>
      </ul>
    </td>
    <td width="40%" align="center">
      <img src="https://github.com/user-attachments/assets/725ccac0-0759-4b99-a635-d9b60a7da4e1"
           alt="ICdetection Screenshot"
           width="260" />
    </td>
  </tr>
</table>

---

> [!IMPORTANT]
> ### ❄️ Field-collection freeze — v2.10.5 is the dataset baseline
>
> The detector is frozen for a field-collection campaign of approximately three months.
> v2.10.5 fixed the last data-integrity and stability issues found before the campaign and defines
> the dataset baseline. No new heuristics, weights, thresholds or detection features will be added
> during the freeze.
>
> Later releases are **bug-fix releases** and add no detection features. Most of them leave
> detection, scoring, stored data and the database schema exactly as in v2.10.5. When a defect in a
> rule must be corrected, the change is documented as a **dataset cut for that rule only**, so data
> from before and after remains comparable for everything else. No reset is needed.
>
> | Release | Dataset impact |
> |---|---|
> | v2.10.6 – v2.10.8 | None — interface, localization, notifications and stability |
> | v2.10.9 | **Dataset cut for H10 (Ping-Pong) only** |
> | v2.10.10 | **Dataset cut for H8 (frequency) only**, plus data-integrity fixes (GPS backfill, timestamps, repeated alarms) |
>
> 📄 Read the full notice in **[IMPORTANT.md](IMPORTANT.md)**.

> [!NOTE]
> **Current release: v2.10.10** (bug fixes from an external code audit). Fixes data-integrity
> issues — GPS coordinates written on the wrong row, deleting history during a forensic capture,
> repeated alarms not notified — and H8 treating a missing frequency as suspicious (**dataset cut
> for H8 only**). See [What's new in v2.10.10](#-whats-new-in-v21010).

> [!WARNING]
> **3.0.0-beta1 (phase 1 of the 3.0 roadmap) is a local development beta, not a release.** It
> lives on a separate branch, is not published and is not part of the field campaign. It changes
> the database schema to **20** and adds columns to the history CSV; see
> [What's new in 3.0.0-beta1](#-whats-new-in-300-beta1-local-beta). Keep using v2.10.10 for the
> campaign.

> [!TIP]
> **Starting a clean research dataset?** Export anything you want to keep, then use
> **History → Delete history** and type the confirmation word (`DELETE` in English, `BORRAR`
> in Spanish). Since v2.10.5 it performs a complete, all-or-nothing reset, including the
> route-familiarity trip tables. Uninstalling and reinstalling still works too, but it also
> removes your OpenCellID key.

---

## 📑 Table of Contents

- [What ICdetection is — and is not](#-what-icdetection-is--and-is-not)
- [What's new in 3.0.0-beta1 (local beta)](#-whats-new-in-300-beta1-local-beta)
- [What's new in v2.10.10](#-whats-new-in-v21010)
- [The field-collection freeze](#-the-field-collection-freeze)
- [Highlights](#-highlights)
- [Technical limitations](#-technical-limitations)
- [Device & hardware compatibility](#-device--hardware-compatibility)
- [Detection engine](#-detection-engine)
- [Statistical and historical hardening](#-statistical-and-historical-hardening)
- [Radio context](#-radio-context)
- [Stable-Site learning (shadow mode)](#-stable-site-learning-shadow-mode)
- [Temporal confidence](#-temporal-confidence)
- [Incident black box](#-incident-black-box)
- [Capability diagnostics & baseline maturity](#-capability-diagnostics--baseline-maturity)
- [Battery optimization](#-battery-optimization)
- [Telemetry & visualization](#-telemetry--visualization)
- [Infrastructure verification](#-infrastructure-verification)
- [Privacy & networking](#-privacy--networking)
- [Forensic logging & exports](#-forensic-logging--exports)
- [False positives](#-false-positives)
- [Security & threat model](#-security--threat-model)
- [Community recognition](#-community-recognition)
- [Development disclosure](#-development-disclosure)
- [Release history](#-release-history)
- [Related research & inspiration](#-related-research--inspiration)
- [License](#-license)
- [Acknowledgements](#-acknowledgements)

---

## 🔍 What ICdetection is — and is not

ICdetection is an open-source Android application focused on cellular-network auditing, heuristic
anomaly detection, radio telemetry analysis, forensic logging and local historical baseline
learning. It runs entirely in Android userland, **without root or direct baseband access**.

It is designed for privacy-conscious users, mobile-security enthusiasts, researchers, forensic
experimentation, cellular infrastructure auditing, and GrapheneOS / Pixel users interested in
radio-layer visibility.

It looks for cellular behavior potentially associated with:

- rogue base stations and fake BTS deployments
- IMSI-catcher-like activity
- downgrade attempts
- abnormal reselection behavior
- suspicious topology inconsistencies
- cellular infrastructure impersonation patterns

> [!WARNING]
> **ICdetection is not an IMSI-catcher proof tool.** It is a local-first cellular anomaly auditor.
> Alerts are signals that the cellular environment deserves closer attention — never definitive
> proof of surveillance or interception.

### Project philosophy

Instead of claiming definitive detection, ICdetection follows a probabilistic forensic approach
built on heuristic correlation, anomaly scoring, infrastructure consistency validation, timing
analysis, local telemetry verification, behavioral pattern analysis and historical baseline
learning. The goal is visibility, anomaly awareness and local evidence for later review — within the
technical limits imposed by Android.

---

## 🧪 What's new in 3.0.0-beta1 (local beta)

First beta of **3.0**, phase 1 of the ordered roadmap (GitHub issue #27): **data foundations**. It
is a local development build on a separate branch, not a release. Database schema **20**: the
migration only adds columns, and every row recorded before 3.0 keeps them empty — **unknown**, never
filled with an assumed value. Detection rules, weights and thresholds are unchanged.

| Area | Change |
|---|---|
| **Unambiguous time** (#23) | Each row also stores the instant of the observation (UTC). Windows, the verification TTL, retention and H15's ordering use it, so a zone change or the repeated hour of the autumn clock change no longer shifts ages or ordering. Rows before 3.0 keep the previous approximate comparison on their local time. CSV: `ObservedAtUtc`. |
| **Evaluation coverage** (#29) | Each row records which rules could not be evaluated (`H1;H6;H9`, or `NONE`) and the accuracy of its GPS fix. After the campaign you can measure how long each rule was actually evaluable, and filter vague positions. CSV: `NotEvaluatedHeuristics`, `GpsAccuracyM`. |
| **App version and device** (#30) | Each row stores the app version that observed it, so dataset cuts apply per row. The CSV also says which phone and Android version made the export (no personal or hardware identifier). CSV: `AppVersion`, `ExportDevice`, `ExportAndroid`. |
| **Most recent row** (#25) | "The latest row" is the last one observed, not the last one written. Verification results and GPS backfill land on the right row. |
| **Stale cycles** (#24) | A slow analysis cycle no longer shows a cell, alerts or writes after the signal was lost (including airplane mode), the screen was refreshed or the serving cell changed — even if the same cell comes back afterwards. |
| **Validator** | `tools/check_export.py` checks the UTC instant against the local time, prints the evaluation coverage of each rule, checks GPS accuracy, lists app versions and the dataset cuts a file crosses, and warns when a file joins several phones. |

**Methodology cut (3.0):** ages and ordering are exact from 3.0 on; before 3.0 they remain
approximate around clock and zone changes. Coverage, GPS accuracy and app version exist only for
rows recorded by 3.0.

---

## ✨ What's new in v2.10.10

A **bug-fix release inside the freeze**, from an external static audit of v2.10.9. Weights,
thresholds, rules and the database schema are unchanged. One fix changes how H8 is evaluated, so
v2.10.10 is a **dataset cut for H8 only**; the data-integrity fixes make new rows more accurate
without changing what any column means.

| Area | Change |
|---|---|
| **H8 frequency** (dataset cut) | When the modem does not report the frequency, Android delivers an "unavailable" value. H8 treated it as a suspicious frequency and subtracted 15 points; it is now `N/A`. A measured out-of-range value still fails, as before. |
| **GPS coordinates** (data integrity) | A late GPS fix could be written into an old observation of the same cell, with its old time, contaminating H11/H13/H16 geometry. Only a row stored within the last 2 minutes can now receive the fix; an older one stays without coordinates. |
| **Observation context** (data integrity) | Each history row now stores the position, service state and time of the moment it was observed, not of the moment it was written. Writes go through one ordered queue. |
| **Repeated alarms** | A second alarm episode in the same cell was not notified or recorded as "confirmed alarm" until the cell changed. An episode now closes after 60 s without alarm, and the next one is notified again. |
| **Delete history** | With the service running, deleting history left the open forensic case, its pre-buffer and the cached baselines alive, causing a false "forensic capture degraded" warning. The service now coordinates the deletion and starts from a clean state. The Incidents and Forensic lists are emptied too. |
| **Stability** | Stable-Site writes moved off the main thread (risk of freezes). At start-up, interrupted cases are closed before any new sample is accepted. |
| **Exports** | Stable-Site and Geometry exports read one consistent snapshot; the RADIO export fails instead of exporting a partial list when the read fails. |
| **Smaller fixes** | Charts clear RSRQ and TA on a cell change; a VERIFIED label in memory is re-checked against the 30-day TTL; a stale "ANOMALOUS" latency state is cleared when the endpoints fail. |

### v2.10.9

A **bug-fix release inside the freeze**. Weights, thresholds, rules, stored data and the database
schema are unchanged. The H10 fix changes how that rule is evaluated, so v2.10.9 is a **dataset cut
for H10 only**; everything else remains comparable with the v2.10.5 baseline.

| Area | Change |
|---|---|
| **H10 Ping-Pong** — major fix (dataset cut) | After a burst of three quick cell changes, H10 kept failing on every cycle until the next handover, even with the phone still on one cell, costing 25 points and keeping those observations from counting as clean. It now counts only changes within the last 10 s. Without a GPS speed it no longer assumes 0 km/h: H10 is `N/A`. The rule is unchanged. |
| **RF fingerprint** | The RSRQ/SINR fingerprint is recalculated after moving 150 m, instead of reusing the previous area's fingerprint for up to 60 s. |
| **Latency check** (optional) | A result is published only for the active cell and only if the probe was not reset meanwhile, so a measurement started or queued for the previous cell is discarded. Both the reset and the baseline use the full cell identity (MCC, MNC, TAC, Cell ID and radio), not the Cell ID alone. |
| **Ciphering** | An empty telephony callback that suggested ciphering was monitored was removed. H9 stays `N/A`, as before. |
| **Baseline maturity** | An empty power, fingerprint or PCI row now shows the real reason: **NEEDS 2 DAYS** while the cell has not yet earned trust (5 clean observations on 2 different days), or **WAITING** once it has but the row has no data for another reason (no GPS fix, away from the stored samples, cell just recovered). v2.10.8 showed "NEEDS 2 DAYS" in both cases. |

### v2.10.8

| Area | Change |
|---|---|
| **Alarm notification** | A confirmed alarm now posts a notification once per episode on the *Security alerts* channel ("Network anomaly confirmed", with cell ID, network and main reason). Previously it only played a tone and was recorded, so it went unnoticed with the phone on silent. |
| **Localization** | Notification titles, actions, channel names and collection notices follow the app language (English or Spanish). The background service now uses the language chosen in Settings and switches immediately when it changes. |
| **Sub-threshold label** | `[sub-umbral]` is shown as `[sub-threshold]` in the English interface. The stored value and exports are unchanged. |
| **Baseline maturity** | Power, RSRQ/SINR fingerprint and PCI baselines show a short status instead of `EMPTY · 0 samples` while a cell has not yet earned trust. |
| **Display fixes** | `98%%` under *Local cell trust* now reads `98%`; the launch placeholder is translated. |
| **Stability** | Opening the map or the *Support project* link no longer closes the app on a phone without a browser. |
| **Codebase** | Unused WiGLE code and three unused texts were removed. The only network connections remain OpenCellID and the optional latency check. |

Earlier maintenance releases (v2.10.6, v2.10.7) are summarised in the
[release history](#-release-history); full details are in [`CHANGELOG.md`](CHANGELOG.md).

---

## ❄️ The field-collection freeze

**ICdetection is in a field-collection freeze.** v2.10.5 defines the methodology baseline: from Day 1
no detection features are added, so the data can speak for itself. Later releases only fix bugs; a
fix that changes how a rule is evaluated is recorded as a dataset cut for that rule.

| | |
|---|---|
| **Methodology baseline** | v2.10.5 (version code 33) |
| **Current release** | v2.10.10 (version code 38) — bug fixes; dataset cuts for H10 (v2.10.9) and H8 (v2.10.10) |
| **Database schema** | 19 (unchanged from v2.10.4) |
| **Detection baseline** | H1–H16, weights, thresholds, Temporal Confidence, Local Cell Trust and Stable-Site frozen |
| **Duration** | Approximately three months |
| **Day 1 of the dataset** | First observation recorded by v2.10.5 on a clean database |

**What will not change:** new heuristics, H17+ rules, weights, thresholds, scoring, experimental
detection features or architectural work that alters the dataset semantics.

**What may still change:** genuine bugs affecting data integrity, collection continuity, database
safety, privacy, compatibility, exports or crashes. Any fix that must change how data is interpreted
will be documented explicitly as a new dataset cut.

**Dataset cuts during the freeze**

| Release | Rule | What changed | How to treat earlier data |
|---|---|---|---|
| v2.10.10 | H8 Frequency | An unavailable frequency (Android's "unavailable" value) gives `N/A` instead of failing and subtracting 15 points. A measured out-of-range value still fails. | Treat `Frecuencia (EARFCN) 4G sospechosa` / `Frecuencia (ARFCN) 5G sospechosa` results before v2.10.10 with care, especially with `ARFCN` = `2147483647` in the export. All other rules are unaffected. |
| v2.10.9 | H10 Ping-Pong | Only cell changes within the last 10 s count (an old burst no longer keeps H10 failed while the phone stays on one cell), and H10 is `N/A` when the GPS speed is unknown instead of assuming 0 km/h. The rule itself is unchanged. Worth the cut: left in place, the defect would have biased every H10 result, kept baselines from learning and made false alarms easier. | Treat `Efecto Ping-Pong` results recorded before v2.10.9 with care; they may include stale bursts. All other rules are unaffected. |

**Data-integrity notes (v2.10.10)** — no rule changes, but useful when comparing data:

- **Coordinates:** before v2.10.10, a late GPS fix could be written into an older row of the same
  cell. Coordinates on rows recorded while the GPS was still acquiring may therefore belong to a
  later position. From v2.10.10 only rows from the last 2 minutes are filled.
- **Timestamps:** rows now carry the time of the observation instead of the time of the write. The
  format is the same; the difference was normally under a second.
- **Confirmed alarms:** a second alarm episode in the same cell now gets its own "confirmed alarm"
  row, which earlier versions skipped.

The full reasoning, the recommended reset and the questions this campaign aims to answer are in
**[IMPORTANT.md](IMPORTANT.md)**.

> [!NOTE]
> **Dataset note (H3/H4).** Most modems — Pixel devices included — only measure frequency, PCI and
> power for neighbour cells; their MCC, MNC and TAC travel in each cell's SIB1, which the phone reads
> only from the cell it is camped on. Before v2.10.5, missing neighbour MCC/MNC was filled with your
> operator's own values, so H3 compared your network with itself and always passed. From v2.10.5,
> H3/H4 show `N/A` in that situation and new `site_rf_neighbours` rows store `NULL` MCC/MNC. The
> security score and anomaly confidence are unaffected: a passing rule never contributed to either.

---

## 🌟 Highlights

- **No root, no baseband access** — everything comes from public Android telephony APIs.
- **Conservative by design** — anomalies must persist across cycles before they are confirmed.
- **Honest diagnostics** — unavailable data is reported as `N/A`, never assumed to be safe.
- **Local-first privacy** — no cloud, no analytics, no ads, no hidden telemetry.
- **Forensic exports** — CSV, ZIP cases and GraphML with SHA-256 checksums and privacy notes.
- **Self-validating data** — `tools/check_export.py` checks the invariants the design guarantees.
- **Tested** — unit tests, lint, release build and on-emulator storage/migration tests run in CI.

---

## 🚧 Technical limitations

Modern Android devices do not expose the complete cellular protocol stack to third-party
applications. ICdetection cannot directly access:

- complete RRC signaling or NAS messages
- full modem telemetry or low-level baseband internals
- complete LTE/5G control-plane traffic
- cryptographic session details
- all identity-request events or ciphering state transitions
- the identity (MCC, MNC, TAC, Cell ID) of most neighbour cells — many modems only report their
  frequency, PCI and signal level

Detection is therefore heuristic and must never be read as definitive proof of surveillance or
interception. Sophisticated LTE/5G interception systems may emulate legitimate carrier
infrastructure and remain difficult — or impossible — to distinguish from real towers using
Android-only telemetry.

---

## 📱 Device & hardware compatibility

ICdetection relies on Android radio callbacks and telephony APIs. Hardware, firmware, modem
implementation, Android version and vendor HAL behavior all affect telemetry quality.

### Recommended environment

Google Pixel devices running stock Android, **GrapheneOS** or near-AOSP builds generally provide
cleaner and more consistent telephony behavior than heavily customized OEM builds.

Advanced radio-security signals — ciphering state, identifier-disclosure events or modem-level
security callbacks — depend on the Android version, modem firmware, vendor HAL support, exposed
APIs, required system permissions and device-specific details. When a device does not expose them,
ICdetection marks them as unavailable rather than assuming they are safe.

### OEM firmware limitations

Heavily customized stacks (OneUI, MIUI, EMUI and similar) may obfuscate radio metrics, suppress
Timing Advance, alter signal-quality values, limit callback consistency or hide ciphering
information. The app still works, but some advanced heuristics can be degraded or unavailable.

### Chipset matters (field observations so far)

From the devices tested so far, the **modem chipset** seems to matter more than the brand:

| Device | Chipset | Result |
|---|---|---|
| Google Pixel | Google Tensor (Samsung modem) | ✅ Works well. Main development device |
| POCO F6 Pro | Qualcomm Snapdragon | ✅ Reported to work well, no false alarms |
| Honor (model not recorded) | — | ✅ Reported to work well. Neighbour cells reported honestly as `N/A` |
| Xiaomi Redmi Note 10 5G | MediaTek Dimensity | ⚠️ Not reliable. The modem fills every neighbour cell with placeholder values (TAC `65535`, Cell ID `268435455`), causing permanent false TAC-deviation warnings and occasional false alarms ([#11](https://github.com/alexisgordr/ICdetector/issues/11)) |

In short: **phones with Qualcomm or Google Tensor modems have behaved well, and at least one MediaTek
phone has not.**
This is based on a handful of devices, so treat it as a trend, not a rule.

**Quick check:** on the main screen, if neighbour cells show as `NEIGHBOR (N/A)`, your modem reports
honestly. If every neighbour shows the same number (e.g. `268435455`), your device is affected.
Reports from more devices are welcome in the issues.

### Android support

| Android | Support |
|---|---|
| **14+** | Best practical support currently available in Android userland. |
| **12 / 13** | Supported in heuristic-analysis mode. |
| **10 / 11** | May work depending on device telemetry; advanced behavior can be limited. |

---

## 🧠 Detection engine

ICdetection continuously performs multi-layer heuristic analysis during cell reselections,
handovers, signal transitions, topology changes, mobility events and changes in local radio
behavior.

<details open>
<summary><strong>Analysis layers</strong></summary>

#### Isolated Cell Detection
Detects serving cells operating without coherent neighboring infrastructure. Useful against amateur
rogue BTS deployments or isolated SDR setups, but it can also occur in rural areas.

#### Signal Dominance Analysis
Detects abnormal signal-dominance deltas between the serving cell and its neighbors, potentially
indicating forced camping. Softened in sparse or rural contexts to reduce false positives.

#### MCC Consistency Validation (H3)
Detects Mobile Country Code inconsistencies between nearby cells. A strong anomaly in normal
non-border environments, but still interpreted in context. Requires neighbours that report their
own MCC; otherwise it is `N/A`.

#### Multi-MNC Density Analysis (H4)
Flags unusually high operator-code diversity. Treated as weak evidence: MVNOs, roaming, transport
hubs and border regions produce legitimate diversity. Requires neighbours that report their own
MNC; otherwise it is `N/A`.

#### TAC Regional Consistency (H5)
Validates Tracking Area Code coherence against surrounding infrastructure. Operator maintenance and
regional changes can also produce unusual values. `N/A` when no neighbour reports a TAC.

#### Timing Advance Geometric Analysis
Correlates Timing Advance distance estimates with tower-position validation when enough data exists.
Highly device-dependent; modems that report a constant zero are detected as `STUB_ZERO` and excluded
from geometry.

#### Ghost Neighbor Detection
Detects a very strong serving cell while every visible neighbor is extremely weak — possibly an
artificially controlled RF environment, or simply difficult radio conditions.

#### ARFCN / Frequency Sanity Validation
Guards against impossible or malformed channel values using technology-specific limits. It is not a
complete regional spectrum validator. When the modem does not report the frequency at all, the rule
is `N/A` rather than failed (since v2.10.10): a missing value is not an impossible one.

#### Ciphering Integrity Monitoring
ICdetection does not claim direct null-cipher or IMSI-disclosure detection on standard Android
installs. Android does not give a regular app access to the modem's ciphering state, so in the
current version H9 is always `N/A` — never assumed to be `PASSED`.

#### Anti Ping-Pong Analysis
Detects aggressive reselection loops and repetitive handovers (three cell changes within 10 s) while
the phone is not moving fast. Without a GPS speed it cannot tell whether you are stationary, so it
reports `N/A` instead of assuming you are.

#### Geographic Consistency Analysis
Validates Cell IDs against a local GPS-based history built from the device's own observations. It can
reveal the same Cell ID appearing from physically inconsistent locations over time. It does not rely
on public tower databases.

#### Signal Baseline Anomaly
Learns each cell's typical signal level at a given location and flags readings that are anomalously
**stronger** than that baseline. Weaker-than-usual readings are not treated as suspicious, since
obstruction and distance commonly cause them.

#### Intra-LTE Band Downgrade Analysis
Detects suspicious shifts from high-frequency capacity bands to sub-GHz bands when the previous signal
was strong and there was no progressive degradation.

#### RF Identity Stability (H15)
Looks for **repeated alternation** between PCI values within the same carrier (ARFCN). A PCI run
counts as one episode however many samples Android reports: `48,48,200,200,48` is one alternate
episode, `48,200,48,200` is two. Observations whose only failure is H15 remain in H15's own history,
so later normal readings repair the baseline instead of freezing it. A stable one-way change such as
`48 → 200` is handled by the separate reconfiguration quarantine, not as instability.

#### RF Quality Fingerprint
Learns each cell's RSRQ/SINR signature. Deliberately strict: it needs many samples and a large
deviation in both metrics at once, and stays dormant until enough history accumulates.

#### Mobility Sanity (H16)
Compares a real handover with device movement, GPS quality, visible neighbors, locally learned
coverage zones and trusted previous transitions. It has no fixed maximum tower distance, returns
`N/A` when evidence is not defensible, and its low weight cannot trigger an alert by itself.

</details>

> [!NOTE]
> **Which layers carry the weight depends on the device.** On phones that do not report neighbour
> identities (common on Pixel devices), H3, H4 and H5 usually stay `N/A`. Detection then rests mainly
> on signal behaviour (H2, H7, H13), RF identity stability (H15), geographic and mobility coherence
> (H11, H16), Local Cell Trust and 2G/3G downgrade protection.

---

## 📊 Statistical and historical hardening

These layers refine evidence using the device's own accumulated history. They are fully offline and
conservative by design.

- **Percentile-based baseline** — with enough samples, an anomaly must exceed the cell's own high
  historical range, which is robust against non-normal distributions.
- **Cell reputation** — trust derived from observation volume, distinct days and clean past scores.
  It only dampens noisy instantaneous heuristics on well-established cells; it never increases
  suspicion and never suppresses physics-anchored evidence.
- **Local Cell Trust** — a revocable confidence profile learned from clean observations across
  independent days, capped at 98%. `ESTABLISHED` means *consistent with this device's local
  history*, not *operator-authenticated*.
- **Context-aware scoring** — environment-sensitive signals are softened by neighbor density and
  local reputation.
- **Bayesian-inspired anomaly confidence** — combines failed heuristics into an **uncalibrated**
  signal (0–95). Likelihood ratios are expert-derived, not empirically calibrated, so the value is
  not a literal probability.

---

## 📻 Radio context

*Collection and diagnostics only — it never changes the score.*

| Field | What it tells you |
|---|---|
| **Connection state** | Whether each cell is `PRIMARY`, `SECONDARY`, `NONE` or `UNKNOWN`, as declared by the modem. |
| **Secondary carriers** | Carrier-aggregation secondaries and the NR leg of 5G NSA, listed instead of analysed. |
| **Bandwidth & bands** | Carrier bandwidth and the bands each cell declares. |
| **Additional PLMNs** | Extra networks announced by the cell (e.g. network sharing). |
| **CSG** | Closed subscriber group — a possible femtocell. Context, not an anomaly by itself. |
| **Service state** | In service, emergency only, out of service or radio off; data/voice registration, roaming, network PLMN and SIM PLMN. |

The **RADIO** tab (History → RADIO) shows the live serving cell, its carriers, every visible cell with
its connection state and the recent service-state changes, exportable to CSV. The terminal logs
`[RADIO]` when the serving cell or its carriers change and `[SERVICIO]` when the service state
changes. "Not reported" means the modem or Android did not provide the field.

The serving cell is chosen by the modem's `PRIMARY_SERVING` status, never by signal strength. If a
declared primary has unusable telemetry, the cycle abstains instead of promoting a secondary.

---

## 🏠 Stable-Site learning (shadow mode)

Stable-Site is a privacy-reduced local context model that learns whether the device repeatedly
observes a coherent cellular environment while physically static. Sites are hashed, overlapping
~500 m grid buckets — no exact site coordinates are stored. Only fresh and accurate location
evidence contributes positive static context.

Stable-Site is **shadow-only**: it does not change H1–H16, the security score, anomaly confidence,
Local Cell Trust, Temporal Confidence, alerts or ordinary forensic decisions.

**Neighbour capability**

- `FULL_NEIGHBOUR_IDENTITY` — a complete neighbour cellular identity is available.
- `RF_NEIGHBOUR_ONLY` — no complete Cell ID, but validated local RF context (`RFCTX:v1:RAT:ARFCN:PCI`).
  It is **not** a Cell ID and is never presented as an authenticated identity.
- `NO_NEIGHBOUR_DATA` — not enough usable neighbour information; nothing is invented.

**Maturity** — `ACTIVE` requires at least seven distinct serving days, three confirmed static days,
thirty serving observations and three independent neighbour-evidence days. Large sample volume from
a single day cannot replace elapsed days. RF-only context follows the same bounded retention as full
identities, and exports derive maturity from the same policy used at runtime.

---

## ⏱️ Temporal confidence

Anomalies must persist across multiple analysis cycles before a confirmed alert. Transient failures
are logged but do not immediately raise alarms, which reduces false-positive fatigue.

| Phase | Meaning |
|---|---|
| `1/3` | Initial observation — an incident record and forensic capture may begin. |
| `2/3` | The condition persists, but it is not yet a confirmed threat. |
| `3/3` | The temporal confirmation requirement has been reached. |

A rule may move between `N/A`, `PASS` and `FAIL` as the modem or Android starts or stops exposing the
data needed to evaluate it.

---

## 🗃️ Incident black box

The black box preserves the lifecycle of a suspicious episode independently from ordinary antenna
history: first observation, highest phase reached, score, anomaly confidence, reason and a snapshot
of the diagnostics.

| State | Meaning |
|---|---|
| `OBSERVING` | Started but not confirmed. |
| `CONFIRMED` | Reached `3/3`. |
| `RECOVERED` | Subsequent observations returned to normal. |
| `INTERRUPTED` | Monitoring stopped before the lifecycle completed. |

It improves later review; it does not turn a heuristic alert into proof.

---

## 🩺 Capability diagnostics & baseline maturity

The live diagnostics view reports whether each rule passed, failed or could not be evaluated. Every
`N/A` comes with a reason: unavailable modem telemetry, missing neighbour identities, missing
location, insufficient latency context or an immature baseline. It also shows the maturity of the
RSRP baseline, the RSRQ/SINR fingerprint, PCI identity stability, local reputation and Stable-Site
state — so an inactive-looking rule can be told apart from one that is still waiting for trustworthy
data.

The RSRP baseline, the RSRQ/SINR fingerprint and PCI identity stability are only learned from a cell
once it is trusted: at least **5 clean observations on 2 different days** within the last 30 days
(power and fingerprint also need 5 samples within 500 m of the current position). Until the cell is
trusted these rows show **NEEDS 2 DAYS**; once it is trusted but a row still has no data (no GPS fix,
away from the stored samples), it shows **WAITING**. Observations are stored throughout, and once the cell becomes trusted all clean
samples from the window count at once. Local reputation has no such gate.

---

## 🔋 Battery optimization

For reliable long-running collection, ICdetection keeps a GPS-only stream active while monitoring and
holds a partial wake lock so collection continues with the screen off. This increases battery use.
At 5% battery or less while unplugged, GPS and the wake lock pause and resume automatically after charging.

**Settings → Apps → ICdetection → Battery → Battery optimization → Don't optimize**

> [!NOTE]
> After a phone restart, collection resumes when you open the app again. The gap is reported in the
> notification as an interrupted-collection notice so it is never silently hidden.

---

## 📈 Telemetry & visualization

- **Forensic Terminal** — event-driven logging of meaningful security and radio events.
- **Real-time signal graphs** — continuous visualization with heuristic overlays.
- **Timing Advance visualization** — when the device exposes TA.
- **Threat scoring engine** — dynamic multi-factor anomaly scoring.
- **Topology explorer** — a local, read-only view of cells and directional handover routes.
- **Geometry view** — handover graph over positions observed by this phone, with route familiarity.
- **Identity row** — `CELL ID`, `TAC/LAC`, `MCC / MNC` and `ARFCN` in a compact layout.

---

## 🛰️ Infrastructure verification

ICdetection can optionally cross-reference observed cells with **OpenCellID**. Public databases are
incomplete, community-maintained, occasionally outdated and uneven across regions, so a
`NOT_FOUND` result does **not** imply malicious infrastructure. Requests are made only after you
configure and enable verification. WiGLE support has been removed.

Verification is a **label, not a verdict**: its result never changes the security score.

---

## 🔒 Privacy & networking

**Local-first design.** No cloud synchronization, no analytics, no advertising SDKs, no hidden
telemetry and no user tracking. All history stays on the device unless you export it, and
`allowBackup` is disabled.

**Complete local reset.** *Delete history* removes antenna history, incidents, forensic cases,
transitions, service-state events, Stable-Site learning and route-familiarity trips in a single
transaction. It is safe while monitoring is running: the service resets its forensic capture,
caches and episode state so nothing from before the deletion is written afterwards.

**Optional SOCKS5 routing.** Verification requests can be routed through a SOCKS5 proxy, including
Tor / Orbot-style local setups.

**Every network connection is optional and off by default.**

| Feature | When it connects | Endpoint(s) | What is sent |
|---|---|---|---|
| OpenCellID verification | Only after you enter your own API token | `opencellid.org` | Serving-cell identity (MCC, MNC, TAC/LAC, Cell ID, radio type) and your token; can go through SOCKS5 |
| Latency detection (experimental) | Only if you enable it in Settings, and only over mobile data (not over Wi-Fi, a VPN or the SOCKS5 proxy); one round roughly every 30 s | `www.google.com/generate_204` (Google), `one.one.one.one` (Cloudflare), `dns.quad9.net` (Quad9) | An HTTPS `HEAD` request with no cell or location data; like any connection, it reveals your IP address to those services |

---

## 🧾 Forensic logging & exports

All telemetry and forensic events are stored locally in SQLite.

**Forensic cases.** A case starts automatically at phase `1/3`, includes up to 60 s of pre-event
context, follows the whole episode and stays open 60 s after recovery (30 min maximum). It keeps
serving and neighbor observations, radio identity and quality, connection state, Timing Advance when
available, GPS position and accuracy, latency, verification state, phase, score, confidence,
heuristic explanations, capabilities and relevant terminal logs. It never stores API credentials,
IMSI, IMEI or the phone number.

```text
ICD-YYYY-MM-DD-NNNN.zip
├── case.json
├── timeline.csv
├── cells.csv
├── heuristics.csv
├── capabilities.json
├── terminal.log
└── SHA256SUMS.txt
```

`SHA256SUMS.txt` reveals later modification of an exported member. It is not a digital signature and
does not by itself establish legal chain of custody.

**Other exports.** Topology (CSV + GraphML + checksums), Geometry (coordinate-free CSV + GraphML),
Stable-Site (hashed sites, neighbours, motion bands, shadow episodes) and RADIO service-state
changes (CSV).

**History CSV columns**

```text
Timestamp, NetType, CID, MNC, TAC, MCC, DBM, Verified, SecurityScore, FailedHeuristics, Lat, Lon,
PCI, ARFCN, RSRQ, SINR, AnomalyConfidence, ApiLat, ApiLon, TA, TAUnit, TAMeters, Radio,
ServingConnection, BandwidthKHz, Bands, AdditionalPlmns, CsgIndicator, CsgIdentity, CsgName,
SecondaryCarriers, ServiceState, NetworkOperator, SimOperator, NetworkRoaming
```

From 3.0.0-beta1 six columns follow, in this order. They are empty in rows recorded before 3.0
(unknown):

```text
ObservedAtUtc, NotEvaluatedHeuristics, GpsAccuracyM, AppVersion, ExportDevice, ExportAndroid
```

`ObservedAtUtc` is the observation instant (ISO-8601, UTC). `NotEvaluatedHeuristics` lists the
rules that abstained (`NONE` when all were evaluated). `GpsAccuracyM` is the accuracy of the row's
fix in metres. `AppVersion` is the version that observed the row. `ExportDevice` and `ExportAndroid`
describe the phone that made the export and are the same on every row of a file.

Validate any export with:

```bash
python3 tools/check_export.py <file.csv>
```

It checks the invariants the design guarantees, applies RAT-specific physical-ID ranges (LTE PCI
`0..503`, NR PCI `0..1007`, UMTS PSC `0..511`), reports calendar coverage and maturity, summarises
the radio context and flags legacy `LTE_INDEX` / `TA=0` stub patterns.

Key points for analysis:

- **`Lat` / `Lon` are always the device's own GPS position.** The OpenCellID antenna position lives
  in `ApiLat` / `ApiLon`. A row saved without GPS is filled later only if the fix arrives within
  2 minutes (since v2.10.10); otherwise it stays empty rather than receiving a later position.
- **`[sub-umbral]` entries** in `FailedHeuristics` failed without reaching the alarm threshold. They
  are observations kept on purpose so false positives can be studied. The English interface displays
  them as `[sub-threshold]`; the stored value is always `[sub-umbral]`.
- **`Verified` is a label, not a verdict:** `VERIFIED`, `NOT_FOUND`, `REJECTED`, `ERROR` or `PENDING`.
  None of them changes `SecurityScore`.
- **Neighbour MCC/MNC in Stable-Site exports** is empty when the modem did not report it. Rows
  written before v2.10.5 may contain your own operator's values instead; treat those columns as
  unreliable for pre-v2.10.5 data.

> [!CAUTION]
> Exports can contain precise location and cellular metadata. Treat them as private forensic material
> and review them before sharing.

---

## ⚠️ False positives

False positives are possible and expected. Legitimate causes include carrier maintenance, roaming,
dense urban deployments, indoor DAS systems, femtocells, NSA / 5G transitions, temporary spectrum
reconfiguration, rural coverage gaps, public transport routes, tunnels, basements, elevators and
parking garages, and vendor-specific Android radio behavior.

Measuring them in real conditions is the main goal of the field-collection freeze.

---

## 🛡️ Security & threat model

| More useful against | Less reliable against |
|---|---|
| Amateur rogue BTS deployments | Sophisticated LTE/5G interception platforms |
| Poorly configured SDR towers | Carrier-grade rogue infrastructure |
| Simple fake base stations | Systems that accurately emulate legitimate parameters |
| Aggressive downgrade attempts | Attacks that expose no anomaly through Android APIs |
| Topology inconsistencies and unstable Cell ID / PCI | Interception inside operator infrastructure |

These limitations are inherent to Android userland restrictions.

---

## 🏅 Community recognition

ICdetection is listed in [**awesome-telco**](https://github.com/ravens/awesome-telco), a curated
community collection of telecommunications software, research resources, protocols, datasets and
security tooling.

Inclusion in a community-maintained list is recognition and visibility — not an independent security
audit, certification or endorsement of ICdetection's detection results.

---

## 🤖 Development disclosure

This project was developed with an AI-assisted workflow.

The lead developer (**Alexis Gomez Rodriguez**) designed and directed the heuristic logic, detection
architecture, threat-scoring behavior, forensic telemetry workflow, validation methodology, anomaly
correlation, false-positive reduction strategy and the overall project direction.

AI assistance — including Gemini, Claude, ChatGPT and DeepSeek — was used for Kotlin implementation,
Android API integration, architectural iteration, debugging, refactoring, code review and
documentation.

I do not claim to be a Kotlin, Android or telecommunications expert. This project is the result of
focused self-study, field testing, careful iteration and AI-assisted development under my direction.
Every heuristic and detection decision is something I aim to understand, explain and defend honestly.

---

## 🗂️ Release history

Every release that changes detection behavior is recorded as a **dataset cut**. The full detail lives
in [`CHANGELOG.md`](CHANGELOG.md) and [`Status.md`](Status.md).

<details open>
<summary><strong>3.0 — post-freeze roadmap (local beta)</strong></summary>

- **3.0.0-beta1** (local, not released) — Phase 1, data foundations: schema 20 with the observation
  instant in UTC (#23), evaluation coverage and GPS accuracy per row (#29), app version per row and
  exporting device in the CSV (#30), most-recent row by observation time (#25), stale analysis cycles
  no longer publish (#24). Methodology cut for ages and ordering.

</details>

<details open>
<summary><strong>v2.10.x — Stable-Site, route memory, H15, radio context and the field-collection freeze</strong></summary>

- **v2.10.10** — Bug fixes from an external audit. H8 is `N/A` when the frequency is unavailable
  (**dataset cut for H8**); GPS backfill limited to recent rows; rows store the context of the
  moment they were observed; repeated alarms notified again; coordinated history deletion; Stable-Site
  writes off the main thread; consistent exports.
- **v2.10.9** — Bug fixes inside the freeze. **Major fix:** H10 no longer stays failed after a burst
  of cell changes and is `N/A` without GPS speed (**dataset cut for H10**); RF fingerprint refreshed after moving 150 m; stale latency
  results discarded; empty ciphering callback removed; baseline maturity shows NEEDS 2 DAYS or
  WAITING by the real reason.
- **v2.10.8** — Maintenance inside the freeze: confirmed-alarm notification (once per episode),
  notifications and service follow the app language, `[sub-threshold]` in English, NEEDS 2 DAYS state for
  trust-gated baselines, `98%` fix, no crash without a browser, unused WiGLE code removed. No
  detection, scoring or data change.
- **v2.10.7** — Maintenance inside the freeze: the alert tone respects silent mode (notification
  stream instead of alarm stream), the confirmed-alarm label reads "Network anomaly confirmed",
  neutral wording for the rapid-cell-change log line, and
  multi-signal / Ping-Pong confirmation lines logged once per episode. No detection, scoring or
  data change.
- **v2.10.6** — Maintenance inside the freeze: uncapped Topology and Geometry (screens and
  exports), History and Geometry fully localized (English/Spanish) including privacy warnings and
  the delete confirmation word, network endpoints documented, validator fixes, forensic exporter
  tests. No detection, scoring or data change.
- **v2.10.5** — Stabilization release. Complete and atomic *Delete history* (including route trips),
  single shared database connection, atomic retention, honest `N/A` for H3/H4 without neighbour
  identities, verification retry fix. Schema 19 unchanged. **Dataset baseline for the
  field-collection freeze.**
- **v2.10.4** — Serving cell chosen by `PRIMARY_SERVING`; safe abstention; radio context, RADIO tab
  and service-state events. Schema 19.
- **v2.10.3** — H15 reasons about independent PCI alternation episodes and no longer freezes its own
  baseline. Dataset cut for H15.
- **v2.10.2** — Bounded retention for RF-only neighbour evidence; exports use the runtime maturity
  policy; RAT-specific PCI/PSC validation.
- **v2.10.1** — Conservative RF-only neighbour context for devices without complete neighbour Cell
  IDs. Schema 18.
- **v2.10.0** — Descriptive route memory (Mobility Familiarity) and Geometry export. `KNOWN_ON_ROUTE`
  means earlier independent trips saw those transitions — not trust. Schema 17.

</details>

<details>
<summary><strong>v2.9.x — Stable-Site learning</strong></summary>

- **v2.9.1** — Motion classification driven by real GPS callbacks; brief inaccurate fixes abstain;
  a 60 s gap invalidates the window.
- **v2.9.0** — Privacy-reduced Stable-Site learning in shadow mode with hashed ~500 m buckets and
  multi-day maturity. Schema 16.

</details>

<details>
<summary><strong>v2.8.x and earlier</strong></summary>

- **v2.8.1** — Frequent cells accumulate temporal trust across the full 90-day window.
- **v2.8.0** — Forensic continuity hardening, failed-write detection, whole-case retention, SQLite
  foreign keys; H8/H11/H14 use the observation's real radio technology. Dataset cut.
- **v2.7.x** — Multi-signal detection hardening and revocable Local Cell Trust; geometry and
  antenna-history inspection.
- **v2.6.0** — Service restructuring and interface localization.
- **v2.5.x** — H16 shortcut handling, baseline admission, temporal confirmation and GNSS admission
  changes. Each recorded as a dataset cut.
- **v2.3.x / v2.4.0** — H16 mobility sanity, topology explorer, data-integrity and collection-
  continuity corrections; v2.3.3 began the first definitive campaign.
- **v2.2.x** — Temporal phases `1/3`–`3/3`, incident black box and bounded forensic cases.
- **v2.1.x** — Data-integrity release: sub-threshold reasons preserved, GPS and antenna coordinates
  separated, carrier-aggregation false positives removed.

> Upgrading from a version older than **v2.1.1** requires uninstalling first: those APKs were signed
> with a different key and their history may mix antenna and device coordinates.

</details>

---

## 📚 Related research & inspiration

Inspired by public research on LTE security, IMSI-catcher detection, SDR rogue-BTS analysis, Android
telephony limitations, cellular anomaly detection and radio-layer privacy, including **SnoopSnitch**,
**AIMSICD**, **LTEInspector**, **OWL**, academic LTE-security research and SDR-based rogue-BTS
experimentation.

---

## ⚖️ License

Licensed under the **GNU General Public License v3.0 (GPL-3.0)**. You are free to use, study, modify,
redistribute and audit the software under GPL terms. Derivative works must remain open source under
GPL-compatible licensing.

---

## 🙏 Acknowledgements

Thank you to everyone who has followed the project, tested it in real conditions and reported
issues. The frequent releases during development were necessary to correct problems found in the
field.

With the field-collection freeze, v2.10.5 defines the dataset baseline for the next three months.
No features will be added during that period. Only genuine bugs are fixed, and any fix that changes
how a rule is evaluated is documented as a dataset cut for that rule. The time will be used to collect data, observe real behaviour, measure false
positives and learn from the evidence. After that, the data will decide what — if anything —
changes next.

This is my first Android application. I do not have formal training in telecommunications or
Android development: I designed the detection approach, selected and rejected heuristics, reviewed
the logic, tested it in real conditions and made the project decisions, while AI tools helped with
the Kotlin/Android implementation.

Questions, corrections and bug reports are welcome — please **[open an issue](https://github.com/alexisgordr/ICdetector/issues)**.
Every report is reviewed and addressed where possible.

> **Nota para los usuarios:** gracias por vuestra paciencia con la frecuencia de actualizaciones
> durante el desarrollo; fueron necesarias para corregir problemas encontrados en pruebas reales.
> La **v2.10.5 es la base del conjunto de datos** durante los próximos tres meses: en ese periodo no
> se añaden funciones nuevas y solo se corrigen errores reales. Si una corrección cambia cómo se
> evalúa una regla, se documenta como corte del dataset para esa regla. La v2.10.9 lo hace solo para
> H10: era un fallo importante y merecía corregirse durante la campaña, porque dejarlo habría
> distorsionado los datos que se están recogiendo. La v2.10.10 hace lo mismo para H8, que trataba una
> frecuencia no disponible como sospechosa. La versión actual es la v2.10.10.

<div align="center">

**Alexis Gomez Rodriguez**

</div>
