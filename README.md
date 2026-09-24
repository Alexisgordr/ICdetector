<div align="center">

# ICdetection

**Open-source, local-first cellular anomaly auditor for Android — no root required.**

[![License: GPL v3](https://img.shields.io/badge/License-GPLv3-blue.svg)](LICENSE)
![Platform](https://img.shields.io/badge/Platform-Android%2010%2B-green.svg)
![Root Required](https://img.shields.io/badge/Root-Not%20Required-brightgreen.svg)
![Status](https://img.shields.io/badge/Status-v2.10.4%20stable-brightgreen.svg)
![Phase](https://img.shields.io/badge/Phase-Field%20collection%20freeze-orange.svg)
[![Featured in Awesome Telco](https://img.shields.io/badge/Featured%20in-Awesome%20Telco-6f42c1.svg)](https://github.com/ravens/awesome-telco#imsi-catcher-detection)

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
> **v2.10.4 is the definitive release for the next three months.**
>
> ICdetection has entered a **field-collection freeze**. No new heuristics, weights, thresholds or
> detection features will be added during this period. The methodology stays fixed so real-world
> data can be collected, compared and validated over time.
>
> Only genuine bugs that affect data integrity, collection continuity, database safety, privacy,
> exports, crashes or clearly incorrect behavior will be fixed — and, whenever possible, without
> changing the dataset semantics.
>
> Read the full notice in **[IMPORTANT.md](IMPORTANT.md)**.

> [!TIP]
> **Starting a clean research dataset?** Export anything you want to keep, then **uninstall and
> reinstall v2.10.4** (or use *Settings → Apps → ICdetection → Storage → Clear data*). In v2.10.4
> the in-app *Delete history* button does not yet clear the route-familiarity trip tables; this will
> be corrected in a later release. Reinstalling also removes your OpenCellID key, so enter it again.

---

## Table of Contents

- [What ICdetection is — and is not](#what-icdetection-is--and-is-not)
- [What's new in v2.10.4](#whats-new-in-v2104)
- [Highlights](#highlights)
- [Technical Limitations](#technical-limitations)
- [Device & Hardware Compatibility](#device--hardware-compatibility)
- [Detection Engine](#detection-engine)
- [Statistical and Historical Hardening](#statistical-and-historical-hardening)
- [Radio Context](#radio-context)
- [Stable-Site Learning (Shadow Mode)](#stable-site-learning-shadow-mode)
- [Temporal Confidence](#temporal-confidence)
- [Incident Black Box](#incident-black-box)
- [Capability Diagnostics & Baseline Maturity](#capability-diagnostics--baseline-maturity)
- [Battery Optimization](#battery-optimization)
- [Telemetry & Visualization](#telemetry--visualization)
- [Infrastructure Verification](#infrastructure-verification)
- [Privacy & Networking](#privacy--networking)
- [Forensic Logging & Exports](#forensic-logging--exports)
- [False Positives](#false-positives)
- [Security & Threat Model](#security--threat-model)
- [Community Recognition](#community-recognition)
- [Development Disclosure](#development-disclosure)
- [Release History](#release-history)
- [Related Research & Inspiration](#related-research--inspiration)
- [License](#license)
- [Acknowledgements](#acknowledgements)

---

## What ICdetection is — and is not

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

## What's new in v2.10.4

| Area | Change |
|---|---|
| **Serving-cell selection** | The serving cell is chosen by the modem's `PRIMARY_SERVING` status, never by signal strength. Only that cell is analysed; with carrier aggregation a secondary carrier could previously be scored and stored as the serving cell. |
| **Safe abstention** | If Android declares a primary whose signal sample is unusable, the cycle abstains instead of promoting a secondary. No registered cell is exposed as active during abstention. Fallback to the first registered entry happens only when no primary is declared. |
| **Radio context** | Connection state, secondary carriers, bandwidth, declared bands, additional PLMNs, closed subscriber group (possible femtocell) and `ServiceState` are collected for forensics. |
| **RADIO tab & card** | A new RADIO tab (History → RADIO) and a main-screen card, in English and Spanish, with CSV export of service-state changes. |
| **Service-state events** | Processed in order, persisted before the event list refreshes, and rejected if stale. Live channel and bandwidth telemetry still update on every reading. |
| **Storage** | Schema 19 is additive; existing history is preserved. The history CSV gains 12 columns at the end. |

None of the new radio-context fields feeds a heuristic or the score. H1–H16, weights, thresholds,
Temporal Confidence, Stable-Site and Local Cell Trust are unchanged.

---

## Highlights

- **No root, no baseband access** — everything comes from public Android telephony APIs.
- **Conservative by design** — anomalies must persist across cycles before they are confirmed.
- **Honest diagnostics** — unavailable data is reported as `N/A`, never assumed to be safe.
- **Local-first privacy** — no cloud, no analytics, no ads, no hidden telemetry.
- **Forensic exports** — CSV, ZIP cases and GraphML with SHA-256 checksums and privacy notes.
- **Self-validating data** — `tools/check_export.py` checks the invariants the design guarantees.
- **Tested** — unit tests, lint, release build and on-emulator storage/migration tests run in CI.

---

## Technical Limitations

Modern Android devices do not expose the complete cellular protocol stack to third-party
applications. ICdetection cannot directly access:

- complete RRC signaling or NAS messages
- full modem telemetry or low-level baseband internals
- complete LTE/5G control-plane traffic
- cryptographic session details
- all identity-request events or ciphering state transitions

Detection is therefore heuristic and must never be read as definitive proof of surveillance or
interception. Sophisticated LTE/5G interception systems may emulate legitimate carrier
infrastructure and remain difficult — or impossible — to distinguish from real towers using
Android-only telemetry.

---

## Device & Hardware Compatibility

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

### Android support

| Android | Support |
|---|---|
| **14+** | Best practical support currently available in Android userland. |
| **12 / 13** | Supported in heuristic-analysis mode. |
| **10 / 11** | May work depending on device telemetry; advanced behavior can be limited. |

---

## Detection Engine

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

#### MCC Consistency Validation
Detects Mobile Country Code inconsistencies between nearby cells. A strong anomaly in normal
non-border environments, but still interpreted in context.

#### Multi-MNC Density Analysis
Flags unusually high operator-code diversity. Treated as weak evidence: MVNOs, roaming, transport
hubs and border regions produce legitimate diversity.

#### TAC Regional Consistency
Validates Tracking Area Code coherence against surrounding infrastructure. Operator maintenance and
regional changes can also produce unusual values.

#### Timing Advance Geometric Analysis
Correlates Timing Advance distance estimates with tower-position validation when enough data exists.
Highly device-dependent; modems that report a constant zero are detected as `STUB_ZERO` and excluded
from geometry.

#### Ghost Neighbor Detection
Detects a very strong serving cell while every visible neighbor is extremely weak — possibly an
artificially controlled RF environment, or simply difficult radio conditions.

#### ARFCN / Frequency Sanity Validation
Guards against impossible or malformed channel values using technology-specific limits. It is not a
complete regional spectrum validator.

#### Ciphering Integrity Monitoring
ICdetection does not claim direct null-cipher or IMSI-disclosure detection on standard Android
installs. Ciphering state is `N/A` unless the operating system exposes a supported and accessible
signal — never assumed to be `PASSED`.

#### Anti Ping-Pong Analysis
Detects aggressive reselection loops and repetitive handovers, with mobility-aware filtering during
vehicular movement.

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

---

## Statistical and Historical Hardening

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

## Radio Context

*New in v2.10.4 — collection and diagnostics only; it never changes the score.*

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

---

## Stable-Site Learning (Shadow Mode)

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

## Temporal Confidence

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

## Incident Black Box

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

## Capability Diagnostics & Baseline Maturity

The live diagnostics view reports whether each rule passed, failed or could not be evaluated. Every
`N/A` comes with a reason: unavailable modem telemetry, missing location, insufficient latency
context or an immature baseline. It also shows the maturity of the RSRP baseline, the RSRQ/SINR
fingerprint, PCI identity stability, local reputation and Stable-Site state — so an inactive-looking
rule can be told apart from one that is still waiting for trustworthy data.

---

## Battery Optimization

For reliable long-running collection, ICdetection keeps a GPS-only stream active while monitoring and
holds a partial wake lock so collection continues with the screen off. This increases battery use.
Below 5% battery while unplugged, GPS and the wake lock pause and resume automatically after charging.

**Settings → Apps → ICdetection → Battery → Battery optimization → Don't optimize**

---

## Telemetry & Visualization

- **Forensic Terminal** — event-driven logging of meaningful security and radio events.
- **Real-time signal graphs** — continuous visualization with heuristic overlays.
- **Timing Advance visualization** — when the device exposes TA.
- **Threat scoring engine** — dynamic multi-factor anomaly scoring.
- **Topology explorer** — a local, read-only view of cells and directional handover routes.
- **Geometry view** — handover graph over positions observed by this phone, with route familiarity.
- **Identity row** — `CELL ID`, `TAC/LAC`, `MCC / MNC` and `ARFCN` in a compact layout.

---

## Infrastructure Verification

ICdetection can optionally cross-reference observed cells with **OpenCellID**. Public databases are
incomplete, community-maintained, occasionally outdated and uneven across regions, so a
`NOT_FOUND` result does **not** imply malicious infrastructure. Requests are made only after you
configure and enable verification. WiGLE is no longer queried.

---

## Privacy & Networking

**Local-first design.** No cloud synchronization, no analytics, no advertising SDKs, no hidden
telemetry and no user tracking. All history stays on the device unless you export it, and
`allowBackup` is disabled.

**Optional SOCKS5 routing.** Verification requests can be routed through a SOCKS5 proxy, including
Tor / Orbot-style local setups.

---

## Forensic Logging & Exports

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

Validate any export with:

```bash
python3 tools/check_export.py <file.csv>
```

It checks the invariants the design guarantees, applies RAT-specific physical-ID ranges (LTE PCI
`0..503`, NR PCI `0..1007`, UMTS PSC `0..511`), reports calendar coverage and maturity, summarises
the v2.10.4 radio context and flags legacy `LTE_INDEX` / `TA=0` stub patterns.

Key points for analysis:

- **`Lat` / `Lon` are always the device's own GPS position.** The OpenCellID antenna position lives
  in `ApiLat` / `ApiLon`.
- **`[sub-umbral]` entries** in `FailedHeuristics` failed without reaching the alarm threshold. They
  are observations kept on purpose so false positives can be studied.
- **`Verified` is a label, not a verdict:** `VERIFIED`, `NOT_FOUND`, `REJECTED`, `ERROR` or `PENDING`.
  None of them changes `SecurityScore`.

> [!CAUTION]
> Exports can contain precise location and cellular metadata. Treat them as private forensic material
> and review them before sharing.

---

## False Positives

False positives are possible and expected. Legitimate causes include carrier maintenance, roaming,
dense urban deployments, indoor DAS systems, femtocells, NSA / 5G transitions, temporary spectrum
reconfiguration, rural coverage gaps, public transport routes, tunnels, basements, elevators and
parking garages, and vendor-specific Android radio behavior.

---

## Security & Threat Model

| More useful against | Less reliable against |
|---|---|
| Amateur rogue BTS deployments | Sophisticated LTE/5G interception platforms |
| Poorly configured SDR towers | Carrier-grade rogue infrastructure |
| Simple fake base stations | Systems that accurately emulate legitimate parameters |
| Aggressive downgrade attempts | Attacks that expose no anomaly through Android APIs |
| Topology inconsistencies and unstable Cell ID / PCI | Interception inside operator infrastructure |

These limitations are inherent to Android userland restrictions.

---

## Community Recognition

ICdetection is listed in [**awesome-telco**](https://github.com/ravens/awesome-telco), a curated
community collection of telecommunications software, research resources, protocols, datasets and
security tooling.

Inclusion in a community-maintained list is recognition and visibility — not an independent security
audit, certification or endorsement of ICdetection's detection results.

---

## Development Disclosure

This project was developed with an AI-assisted workflow.

The lead developer (**Alexis Gomez Rodriguez**) designed and directed the heuristic logic, detection
architecture, threat-scoring behavior, forensic telemetry workflow, validation methodology, anomaly
correlation, false-positive reduction strategy and the overall project direction.

AI assistance — including Gemini, Claude, ChatGPT and DeepSeek — was used for Kotlin implementation,
Android API integration, architectural iteration, debugging, refactoring and documentation.

I do not claim to be a Kotlin, Android or telecommunications expert. This project is the result of
focused self-study, field testing, careful iteration and AI-assisted development under my direction.
Every heuristic and detection decision is something I aim to understand, explain and defend honestly.

---

## Release History

Every release that changes detection behavior is recorded as a **dataset cut**. The full detail lives
in [`CHANGELOG.md`](CHANGELOG.md) and [`Status.md`](Status.md).

<details>
<summary><strong>v2.10.x — Stable-Site, route memory, H15 and radio context</strong></summary>

- **v2.10.4** — Serving cell chosen by `PRIMARY_SERVING`; safe abstention; radio context, RADIO tab
  and service-state events. Schema 19. **Definitive release for the field-collection freeze.**
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

## Related Research & Inspiration

Inspired by public research on LTE security, IMSI-catcher detection, SDR rogue-BTS analysis, Android
telephony limitations, cellular anomaly detection and radio-layer privacy, including **SnoopSnitch**,
**AIMSICD**, **LTEInspector**, **OWL**, academic LTE-security research and SDR-based rogue-BTS
experimentation.

---

## License

Licensed under the **GNU General Public License v3.0 (GPL-3.0)**. You are free to use, study, modify,
redistribute and audit the software under GPL terms. Derivative works must remain open source under
GPL-compatible licensing.

---

## Acknowledgements

Thank you to everyone who has followed the project through its many iterations — and for your
patience with the frequent releases during development. They were necessary to correct issues found
through real-world testing.

**ICdetection v2.10.4 is the definitive release for the next three months.** During this period I
will not change the detector unless a significant bug appears. The time will be used to collect data,
observe real behavior, measure false positives and learn from the evidence. After that, the data will
decide what — if anything — changes next.

This is the first Android application I have ever built, and I put a lot of care into it. I do not
have formal telecommunications or Android-development training; I designed the detection approach,
selected and rejected heuristics, reviewed the logic, tested it in real conditions and made the
project decisions, while AI tools helped with the Kotlin/Android implementation.

If you have questions, find mistakes or run into issues, please **open an issue**. I will review it
honestly and fix what I can.

> **Nota para los usuarios:** pedimos disculpas por la frecuencia inusual de actualizaciones durante
> el desarrollo; fueron necesarias para corregir problemas encontrados en pruebas reales. La v2.10.4
> es la versión definitiva durante los próximos tres meses, y no se harán cambios salvo que aparezca
> un error importante.

<div align="center">

Best regards,<br>
**Alexis**

*Carpe diem.*

</div>
