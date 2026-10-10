<div align="center">

# 📡 ICdetection

### Open-source, local-first cellular anomaly auditor for Android — no root required.

*Observe the cellular environment. Keep the evidence. Never overclaim.*

[![License: GPL v3](https://img.shields.io/badge/License-GPLv3-blue.svg)](LICENSE)
![Platform](https://img.shields.io/badge/Platform-Android%2010%2B-green.svg)
![Root Required](https://img.shields.io/badge/Root-Not%20Required-brightgreen.svg)
[![Release](https://img.shields.io/github/v/release/Alexisgordr/ICdetector?label=release&color=brightgreen)](https://github.com/Alexisgordr/ICdetector/releases/latest)
[![Featured in Awesome Telco](https://img.shields.io/badge/Featured%20in-Awesome%20Telco-6f42c1.svg)](https://github.com/ravens/awesome-telco#imsi-catcher-detection)

[**Install**](#-getting-started) ·
[**How it works**](#-detection-engine) ·
[**Exports**](#-forensic-logging--exports) ·
[**Manual**](MANUAL.md) ·
[**Changelog**](CHANGELOG.md) ·
[**Status**](Status.md)

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
> **Project status**
>
> - **Stable — v2.10.10.** Used by the ongoing field-collection campaign (dataset baseline v2.10.5).
>   Bug fixes only; see [IMPORTANT.md](IMPORTANT.md).
> - **In development — 3.0.0-beta4.** The 3.0 roadmap: schema 21, more accurate rule inputs and
>   stricter confirmation. No APK is published; it is available as source on the
>   [`ICdetection-v3.0.0-beta`](https://github.com/Alexisgordr/ICdetector/tree/ICdetection-v3.0.0-beta)
>   branch ([build it yourself](#-getting-started)). **3.0 needs a clean install** from v2.10.x.
>   Do not install it on a phone that collects campaign data.
>
> Details: [Status.md](Status.md) · [CHANGELOG.md](CHANGELOG.md)

---

## 📑 Table of Contents

- [What ICdetection is — and is not](#-what-icdetection-is--and-is-not)
- [Highlights](#-highlights)
- [Getting started](#-getting-started)
- [Detection engine](#-detection-engine)
- [Statistical and historical hardening](#-statistical-and-historical-hardening)
- [Temporal confidence](#-temporal-confidence)
- [Incident black box](#-incident-black-box)
- [Capability diagnostics & baseline maturity](#-capability-diagnostics--baseline-maturity)
- [Radio context](#-radio-context)
- [Stable-Site learning (shadow mode)](#-stable-site-learning-shadow-mode)
- [Telemetry & visualization](#-telemetry--visualization)
- [Infrastructure verification](#-infrastructure-verification)
- [Privacy & networking](#-privacy--networking)
- [Forensic logging & exports](#-forensic-logging--exports)
- [Device & hardware compatibility](#-device--hardware-compatibility)
- [Technical limitations](#-technical-limitations)
- [What the field campaign can and cannot show](#-what-the-field-campaign-can-and-cannot-show)
- [False positives](#-false-positives)
- [Security & threat model](#-security--threat-model)
- [Documentation](#-documentation)
- [Development disclosure](#-development-disclosure)
- [Community recognition](#-community-recognition)
- [Related research & inspiration](#-related-research--inspiration)
- [License](#-license)
- [Acknowledgements](#-acknowledgements)

---

## 🔍 What ICdetection is — and is not

ICdetection is an open-source Android application for cellular-network auditing, heuristic anomaly
detection, radio telemetry analysis, forensic logging and local historical baseline learning. It
runs entirely in Android userland, **without root or direct baseband access**.

It is designed for privacy-conscious users, mobile-security enthusiasts, researchers, forensic
experimentation, cellular infrastructure auditing, and GrapheneOS / Pixel users interested in
radio-layer visibility. It looks for cellular behaviour potentially associated with:

- rogue base stations and fake BTS deployments
- IMSI-catcher-like activity
- downgrade attempts
- abnormal reselection behaviour
- suspicious topology inconsistencies
- cellular infrastructure impersonation patterns

> [!WARNING]
> **ICdetection is not an IMSI-catcher proof tool.** It is a local-first cellular anomaly auditor.
> Alerts are signals that the cellular environment deserves closer attention — never definitive
> proof of surveillance or interception.

### Project philosophy

Instead of claiming definitive detection, ICdetection follows a probabilistic forensic approach built
on heuristic correlation, anomaly scoring, infrastructure consistency validation, timing analysis,
local telemetry verification, behavioural pattern analysis and historical baseline learning. The goal
is visibility, anomaly awareness and local evidence for later review — within the technical limits
imposed by Android.

---

## 🌟 Highlights

- **No root, no baseband access** — everything comes from public Android telephony APIs.
- **Conservative by design** — anomalies must persist across consecutive observations before they
  are confirmed, and a coverage gap starts confirmation again.
- **Honest diagnostics** — unavailable data is reported as `N/A`, never assumed to be safe.
- **Self-describing data** — every history row records the exact UTC instant, which rules could not
  be evaluated, its GPS accuracy and the app version that observed it.
- **Local-first privacy** — no cloud, no analytics, no ads, no hidden telemetry.
- **Forensic exports** — CSV, ZIP cases and GraphML with SHA-256 checksums and privacy notes.
- **Self-validating data** — `tools/check_export.py` checks the invariants the design guarantees.
- **Tested** — unit tests, lint, release build and on-emulator storage/migration tests run in CI.

---

## 🚀 Getting started

1. **Download** the stable APK from [GitHub Releases](https://github.com/Alexisgordr/ICdetector/releases).
   Every release is the same app (`com.alexisgordr.icdetector`), so a newer version installs over an
   older one and keeps its data.
2. **Trying the 3.0 beta?** It is published as source only. Build it from the beta branch:

   ```bash
   git clone -b ICdetection-v3.0.0-beta https://github.com/Alexisgordr/ICdetector.git
   cd ICdetector
   ./gradlew assembleDebug    # APK in app/build/outputs/apk/debug/
   ```

   3.0 changes the database and the methodology, so it needs a clean install: export what you want
   to keep and uninstall v2.10.x first (a build signed with your own key cannot update the published
   APK anyway). Do not use the phone that collects campaign data.
3. **Grant location permission.** Android only shares cell identities with apps that have location
   access, and GPS enables the geographic checks.
4. **Exclude the app from battery optimisation** for long sessions:
   **Settings → Apps → ICdetection → Battery → Battery optimization → Don't optimize**.
5. **Optional:** add your own OpenCellID token for infrastructure verification, and enable the
   experimental latency check.

The [Field Manual](MANUAL.md) covers setup, every screen, the rules and the exports in detail.

---

## 🧠 Detection engine

ICdetection continuously performs multi-layer heuristic analysis during cell reselections, handovers,
signal transitions, topology changes, mobility events and changes in local radio behaviour. Sixteen
rules (H1–H16) are evaluated on every cycle; each one passes, fails or reports `N/A` with a reason.

<details open>
<summary><strong>Analysis layers</strong></summary>

#### Isolated Cell Detection (H1)
Detects serving cells operating without coherent neighbouring infrastructure. Useful against amateur
rogue BTS deployments or isolated SDR setups, but it can also occur in rural areas. The isolation must
be confirmed over three fresh deliveries; until then H1 shows *pending confirmation*.

#### Signal Dominance Analysis (H2)
Detects abnormal power jumps and dominance deltas between the serving cell and its neighbours,
potentially indicating forced camping. Softened in sparse or rural contexts.

#### MCC Consistency Validation (H3)
Detects Mobile Country Code inconsistencies between nearby cells. Requires neighbours that report
their own MCC; otherwise it is `N/A`.

#### Multi-MNC Density Analysis (H4)
Flags unusually high operator-code diversity. Weak evidence: MVNOs, roaming, transport hubs and
border regions produce legitimate diversity. Requires neighbours that report their own MNC.

#### TAC Regional Consistency (H5)
Validates Tracking Area Code coherence against surrounding infrastructure. `N/A` when no neighbour
reports a real TAC; placeholder values some modems report (TAC `65535`) are read as unavailable.

#### Timing Advance Geometric Analysis (H6)
Correlates Timing Advance distance estimates with tower-position validation when enough data exists.
A missing TA is only taken from a duplicate of the same cell, never from another transmitter. Modems
that always report zero are detected per technology (`STUB_ZERO`) and excluded from geometry. TA on
5G NR is not converted to distance.

#### Ghost Neighbour Detection (H7)
Detects a very strong serving cell while every visible neighbour is extremely weak — possibly an
artificially controlled RF environment, or simply difficult radio conditions.

#### ARFCN / Frequency Sanity Validation (H8)
Guards against impossible or malformed channel values using technology-specific limits. It is not a
complete regional spectrum validator. A frequency the modem does not report is `N/A`, not failed.

#### Ciphering Integrity Monitoring (H9)
Android does not give a regular app access to the modem's ciphering state, so H9 is always `N/A` —
never assumed to be `PASSED`. ICdetection does not claim null-cipher or IMSI-disclosure detection.

#### Anti Ping-Pong Analysis (H10)
Detects aggressive reselection loops: three changes of the full serving identity (MCC, MNC, TAC,
Cell ID or technology) within 10 s while the phone is not moving fast. Without a GPS speed it reports
`N/A` instead of assuming you are stationary.

#### Geographic Consistency Analysis (H11)
Validates Cell IDs against a local GPS history built from the device's own observations, revealing
the same Cell ID appearing from physically inconsistent locations. It does not rely on public tower
databases.

#### Latency Correlation (H12, optional)
Compares network latency with this cell's own learned reference. Evaluated only on a real
measurement; `N/A` while learning or when latency detection is off.

#### Signal Baseline Anomaly (H13)
Learns each cell's typical signal level at a given location and flags readings anomalously
**stronger** than that baseline. Weaker readings are not suspicious: obstruction and distance cause
them.

#### Band Downgrade Analysis (H14)
Detects suspicious shifts from high-frequency capacity bands to sub-GHz bands when the previous
signal was strong and there was no progressive degradation. Every 3GPP LTE band is known; band
changes within the same LTE base station (eNodeB) are not penalised; NR → NR downgrades on 5G SA are
evaluated from the exact NR-ARFCN frequency.

#### RF Identity Stability (H15)
Looks for **repeated alternation** between PCI values within the same carrier. A PCI run counts as
one episode however many samples Android reports: `48,48,200,200,48` is one alternate episode,
`48,200,48,200` is two. A stable one-way change such as `48 → 200` is handled as a reconfiguration,
not instability.

#### Mobility Sanity (H16)
Compares a real handover with device movement, GPS quality, visible neighbours, locally learned
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

These layers refine evidence using the device's own history. They are fully offline and conservative.

- **Percentile-based baseline** — with enough samples, an anomaly must exceed the cell's own high
  historical range, robust against non-normal distributions.
- **Trusted learning only** — a new cell is quarantined from learning until it has 5 clean
  observations on 2 different days; after that, only clean observations train its baselines, so a
  suspicious observation cannot redefine the reference that judges it.
- **Cell reputation** — trust from observation volume, distinct days and clean past scores. It only
  dampens noisy instantaneous heuristics on well-established cells; it never increases suspicion and
  never suppresses physics-anchored evidence.
- **Local Cell Trust** — a revocable confidence profile learned from clean observations across
  independent days, capped at 98%. `ESTABLISHED` means *consistent with this device's local history*,
  not *operator-authenticated*.
- **Context-aware scoring** — environment-sensitive signals are softened by neighbour density and
  local reputation.
- **Bayesian-inspired anomaly confidence** — combines failed heuristics into an **uncalibrated**
  signal (0–95). Likelihood ratios are expert-derived, not empirically calibrated, so the value is not
  a literal probability.

---

## ⏱️ Temporal confidence

Anomalies must persist across consecutive observations before a confirmed alert. Transient failures
are logged but do not raise alarms, which reduces false-positive fatigue.

| Phase | Meaning |
|---|---|
| `1/3` | Initial observation — an incident record and forensic capture may begin. |
| `2/3` | The condition persists, but it is not yet a confirmed threat. |
| `3/3` | Confirmed: a *Network anomaly confirmed* notification is posted once per episode. |

Two independent high-value failures in the same observation (H11, H13, H15, H16) confirm in two
phases instead of three. A repeated delivery of the same modem sample never counts as a new phase.
Confirmation **starts again** after a signal loss or a gap of more than 2 minutes without readings,
and the terminal records which one happened.

---

## 🗃️ Incident black box

The black box preserves the lifecycle of a suspicious episode independently from ordinary antenna
history: first observation, highest phase, score, anomaly confidence, reason and a snapshot of the
diagnostics.

| State | Meaning |
|---|---|
| `OBSERVING` | Started but not confirmed. |
| `CONFIRMED` | Reached `3/3`. |
| `RECOVERED` | Subsequent observations returned to normal. |
| `INTERRUPTED` | Monitoring stopped before the lifecycle completed. |

It improves later review; it does not turn a heuristic alert into proof.

---

## 🩺 Capability diagnostics & baseline maturity

The diagnostics view reports whether each rule passed, failed or could not be evaluated, and every
`N/A` comes with a reason: unavailable modem telemetry, missing neighbour identities, missing
location, insufficient latency context or an immature baseline. It also shows the maturity of the
RSRP baseline, the RSRQ/SINR fingerprint, PCI identity stability, local reputation and Stable-Site —
so an inactive-looking rule can be told apart from one waiting for trustworthy data.

The RSRP baseline, the fingerprint and PCI stability learn from a cell only once it is trusted: at
least **5 clean observations on 2 different days** within the last 30 days (power and fingerprint
also need 5 samples within 500 m). Until then these rows show **NEEDS 2 DAYS**; once trusted, a row
without data for another reason shows **WAITING**.

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

The **RADIO** tab (History → RADIO) shows the live serving cell, its carriers, every visible cell and
the recent service-state changes, exportable to CSV. The serving cell is chosen by the modem's
`PRIMARY_SERVING` status, never by signal strength; if a declared primary has unusable telemetry, the
cycle abstains instead of promoting a secondary.

---

## 🏠 Stable-Site learning (shadow mode)

Stable-Site is a privacy-reduced local context model that learns whether the device repeatedly
observes a coherent cellular environment while physically static. Sites are hashed, overlapping
~500 m grid buckets — no exact site coordinates are stored. Only fresh, accurate location evidence
contributes static context.

Stable-Site is **shadow-only**: it does not change H1–H16, the security score, anomaly confidence,
Local Cell Trust, temporal confidence, alerts or forensic decisions.

- **Neighbour capability:** `FULL_NEIGHBOUR_IDENTITY`, `RF_NEIGHBOUR_ONLY` (validated local RF
  context `RFCTX:v1:RAT:ARFCN:PCI`, never presented as a Cell ID) or `NO_NEIGHBOUR_DATA`.
- **Maturity:** `ACTIVE` requires seven distinct serving days, three confirmed static days, thirty
  serving observations and three independent neighbour-evidence days. Sample volume from one day
  cannot replace elapsed days.

---

## 📈 Telemetry & visualization

- **Forensic Terminal** — event-driven logging of meaningful security and radio events.
- **Real-time signal graphs** — continuous visualization with heuristic overlays.
- **Timing Advance visualization** — when the device exposes TA.
- **Threat scoring engine** — dynamic multi-factor anomaly scoring.
- **Topology explorer** — a local, read-only view of cells and directional handover routes.
- **Geometry view** — handover graph over positions observed by this phone, with route familiarity.
- **Identity row** — `CELL ID`, `TAC/LAC`, `MCC / MNC` and `ARFCN` in a compact layout.

For long collection sessions the app keeps a GPS-only stream and a partial wake lock active while
monitoring, which increases battery use. At 5% battery or less while unplugged, both pause and resume
after charging; where GPS keeps failing, precise fixes are retried less often.

**Location mode.** Settings offers *Continuous* (default), *Smart* and *Adaptive*, with names and
descriptions also available in Spanish. Continuous retains the permanent GPS stream for campaigns.
Smart uses short acquisition windows roughly every 45 seconds. Smart and Adaptive pause after
repeated acquisition failures and retry after 2, 5 and 10 minutes; cell changes and suspected
anomalies may request an earlier bounded attempt. Cellular scanning continues independently.
GPS positions for every cell and a particular battery saving cannot be guaranteed. Each row records
its selected mode; see the [manual](MANUAL.md) for timing and coverage.

---

## 🛰️ Infrastructure verification

ICdetection can optionally cross-reference observed cells with **OpenCellID**. Public databases are
incomplete, community-maintained, occasionally outdated and uneven across regions, so a `NOT_FOUND`
result does **not** imply malicious infrastructure. Requests are made only after you configure and
enable verification.

Verification is a **label, not a verdict**: its result never changes the security score.

---

## 🔒 Privacy & networking

**Local-first design.** No cloud synchronization, no analytics, no advertising SDKs, no hidden
telemetry and no user tracking. All history stays on the device unless you export it, and
`allowBackup` is disabled.

**Complete local reset.** *Delete history* removes antenna history, incidents, forensic cases,
transitions, service-state events, Stable-Site learning and route-familiarity trips in a single
transaction, safely even while monitoring is running.

**Optional SOCKS5 routing.** Verification requests can be routed through a SOCKS5 proxy, including
Tor / Orbot-style local setups.

**Every network connection is optional and off by default.**

| Feature | When it connects | Endpoint(s) | What is sent |
|---|---|---|---|
| OpenCellID verification | Only after you enter your own API token | `opencellid.org` | Serving-cell identity (MCC, MNC, TAC/LAC, Cell ID, radio type) and your token; can go through SOCKS5 |
| Latency detection (experimental) | Only if you enable it, and only over mobile data (not Wi-Fi, a VPN or the SOCKS5 proxy); one round roughly every 30 s | `www.google.com/generate_204` (Google), `one.one.one.one` (Cloudflare), `dns.quad9.net` (Quad9) | An HTTPS `HEAD` request with no cell or location data; like any connection, it reveals your IP address to those services |

---

## 🧾 Forensic logging & exports

All telemetry and forensic events are stored locally in SQLite.

**Forensic cases.** A case starts automatically at phase `1/3`, includes up to 60 s of pre-event
context, follows the whole episode and stays open 60 s after recovery (30 min maximum). It never
stores API credentials, IMSI, IMEI or the phone number.

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
Stable-Site (hashed sites, neighbours, motion bands, shadow episodes) and RADIO service-state changes
(CSV).

**History CSV.** One row per observation, with the cell identity, signal, Timing Advance, radio
context, score and failed rules — and, for each row, the exact UTC instant (`ObservedAtUtc`), the
rules that could not be evaluated (`NotEvaluatedHeuristics`), the GPS accuracy (`GpsAccuracyM`) and
the app version that observed it (`AppVersion`) and the location mode (`LocationMode`). Columns that did not exist when a row was recorded
are empty: unknown, never assumed. The full column list is in the
[manual](MANUAL.md#12-exporting-antenna-history).

Validate any export with:

```bash
python3 tools/check_export.py <file.csv>
```

It checks the invariants the design guarantees, applies technology-specific physical-ID ranges (LTE
PCI `0..503`, NR PCI `0..1007`, UMTS PSC `0..511`), reports calendar and rule-evaluation coverage,
GPS accuracy, app versions and the dataset cuts a file crosses, and flags legacy `TA=0` stub patterns.

Key points for analysis:

- **`Lat` / `Lon` are always the device's own GPS position**, taken when the reading arrived. The
  OpenCellID antenna position is in `ApiLat` / `ApiLon`.
- **`[sub-umbral]`** entries in `FailedHeuristics` failed without reaching the alarm threshold; they
  are kept on purpose so false positives can be studied (shown as `[sub-threshold]` in English).
- **`Verified` is a label, not a verdict:** `VERIFIED`, `NOT_FOUND`, `REJECTED`, `ERROR` or `PENDING`.
  None of them changes `SecurityScore`.

> [!CAUTION]
> Exports can contain precise location and cellular metadata. Treat them as private forensic material
> and review them before sharing.

---

## 📱 Device & hardware compatibility

ICdetection relies on Android radio callbacks and telephony APIs. Hardware, firmware, modem
implementation, Android version and vendor HAL behaviour all affect telemetry quality.

**Recommended environment.** Google Pixel devices running stock Android, **GrapheneOS** or near-AOSP
builds generally provide cleaner and more consistent telephony behaviour than heavily customized OEM
builds. Advanced radio-security signals depend on the Android version, modem firmware, vendor HAL and
exposed APIs; when a device does not expose them, ICdetection marks them as unavailable rather than
assuming they are safe.

**OEM firmware.** Heavily customized stacks (OneUI, MIUI, EMUI and similar) may obfuscate radio
metrics, suppress Timing Advance, alter signal-quality values, limit callback consistency or hide
ciphering information. The app still works, but some heuristics can be degraded or unavailable.

**Chipset matters.** From the devices tested so far, the **modem chipset** seems to matter more than
the brand:

| Device | Chipset | Result |
|---|---|---|
| Google Pixel | Google Tensor (Samsung modem) | ✅ Works well. Main development device |
| POCO F6 Pro | Qualcomm Snapdragon | ✅ Reported to work well, no false alarms |
| Honor (model not recorded) | — | ✅ Reported to work well. Neighbour cells reported honestly as `N/A` |
| Xiaomi Redmi Note 10 5G | MediaTek Dimensity | ⚠️ Not reliable. The modem fills every neighbour cell with placeholder values (TAC `65535`, Cell ID `268435455`) ([#11](https://github.com/Alexisgordr/ICdetector/issues/11)) |

Phones with Qualcomm or Google Tensor modems have behaved well, and at least one MediaTek phone has
not. This is based on a handful of devices, so treat it as a trend, not a rule.

**Quick check:** if neighbour cells show as `NEIGHBOR (N/A)`, your modem reports honestly. If every
neighbour shows the same number (e.g. `268435455`), your device fills placeholders; ICdetection reads
them as unavailable, but these devices remain outside the supported set. Reports from more devices
are welcome in the issues.

| Android | Support |
|---|---|
| **14+** | Best practical support currently available in Android userland. |
| **12 / 13** | Supported in heuristic-analysis mode. |
| **10 / 11** | May work depending on device telemetry; advanced behaviour can be limited. |

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
interception. Sophisticated LTE/5G interception systems may emulate legitimate carrier infrastructure
and remain difficult — or impossible — to distinguish from real towers using Android-only telemetry.

---

## 🔬 What the field campaign can and cannot show

The field campaign measures how the detector behaves on real, ordinary networks. Its limits are
design limits, not bugs, and they apply to any conclusion drawn from the data:

| Limit | What it means |
|---|---|
| **No ground truth** | No attack is known to have been recorded, which does not prove that none happened. The data shows how often each rule warns and lets possible false positives be studied; it cannot show whether the app catches a real IMSI catcher, nor prove that every warning was false. `ScenarioTest` is synthetic and says so. |
| **No negative control** | One phone observes at a time. When a rule fires, nothing in the data can tell a network anomaly from a detector or modem fault; a second phone at the same place would be needed. |
| **Limited coverage** | Mostly one phone, one operator and one area. Thresholds are not validated for rural areas, roaming, borders or other modem vendors. |
| **Self-collected** | The person running the app chose where and when to collect. The data describes those routes, not a representative sample. |

**What it is for:** measuring how often each rule warns on ordinary networks, studying possible false
positives case by case and, with the per-row coverage column, measuring how long each rule was
actually evaluable. That evidence can inform later reviews of thresholds and weights, but it is not
enough on its own to recalibrate the likelihood ratios, which would also need data from real attacks.
A rule that "passes" most of the time because it is `N/A` most of the time is not shown to work.

The campaign itself — its baseline, dataset cuts and recommended reset — is described in
[IMPORTANT.md](IMPORTANT.md).

---

## ⚠️ False positives

False positives are possible and expected. Legitimate causes include carrier maintenance, roaming,
dense urban deployments, indoor DAS systems, femtocells, NSA / 5G transitions, temporary spectrum
reconfiguration, rural coverage gaps, public transport routes, tunnels, basements, elevators and
parking garages, and vendor-specific Android radio behaviour. Measuring them in real conditions is
the main goal of the field campaign.

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

## 📚 Documentation

| Document | What it covers |
|---|---|
| [MANUAL.md](MANUAL.md) | How to install and use the app: screens, rules, phases, exports and troubleshooting |
| [CHANGELOG.md](CHANGELOG.md) | Every release in detail, including each dataset cut |
| [Status.md](Status.md) | Current versions, the 3.0 roadmap, dataset cuts in 3.0 and open items |
| [IMPORTANT.md](IMPORTANT.md) | The field-collection campaign: baseline, dataset cuts and recommended reset |
| [docs/history/](docs/history/) | Archived per-release status notes, including the v2.1 field notes |

---

## 🤖 Development disclosure

This project was developed with an AI-assisted workflow.

The lead developer (**Alexis Gomez Rodriguez**) designed and directed the heuristic logic, detection
architecture, threat-scoring behaviour, forensic telemetry workflow, validation methodology, anomaly
correlation, false-positive reduction strategy and the overall project direction.

AI assistance — including Gemini, Claude, ChatGPT and DeepSeek — was used for Kotlin implementation,
Android API integration, architectural iteration, debugging, refactoring, code review and
documentation.

I do not claim to be a Kotlin, Android or telecommunications expert. This project is the result of
focused self-study, field testing, careful iteration and AI-assisted development under my direction.
Every heuristic and detection decision is something I aim to understand, explain and defend honestly.

---

## 🏅 Community recognition

ICdetection is listed in [**awesome-telco**](https://github.com/ravens/awesome-telco), a curated
community collection of telecommunications software, research resources, protocols, datasets and
security tooling. Inclusion in a community-maintained list is recognition and visibility — not an
independent security audit, certification or endorsement of ICdetection's detection results.

---

## 📖 Related research & inspiration

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
field; the field campaign now gives the detector the time it needs to be judged on evidence.

This is my first Android application. I do not have formal training in telecommunications or Android
development: I designed the detection approach, selected and rejected heuristics, reviewed the logic,
tested it in real conditions and made the project decisions, while AI tools helped with the
Kotlin/Android implementation.

Questions, corrections and bug reports are welcome — please
**[open an issue](https://github.com/Alexisgordr/ICdetector/issues)**. Every report is reviewed and
addressed where possible.

> **Nota para los usuarios:** gracias por vuestra paciencia con la frecuencia de actualizaciones
> durante el desarrollo; fueron necesarias para corregir problemas encontrados en pruebas reales. La
> campaña de recogida de datos usa la versión estable y solo recibe correcciones de errores; cada
> corrección que cambia cómo se evalúa una regla se documenta como corte del dataset. La 3.0 es la
> siguiente etapa y requiere una instalación limpia. Todos los detalles están en
> [IMPORTANT.md](IMPORTANT.md) y [CHANGELOG.md](CHANGELOG.md).

<div align="center">

**Alexis Gomez Rodriguez**

</div>
