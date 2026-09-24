![License: GPL v3](https://img.shields.io/badge/License-GPLv3-blue.svg)
![Platform](https://img.shields.io/badge/Platform-Android%2010%2B-green.svg)
![Root Required](https://img.shields.io/badge/Root-Not%20Required-brightgreen.svg)
![Status](https://img.shields.io/badge/Status-v2.10.3%20candidate-orange.svg)
[![Featured in Awesome Telco](https://img.shields.io/badge/Featured%20in-Awesome%20Telco-6f42c1.svg)](https://github.com/ravens/awesome-telco#imsi-catcher-detection)


<table>

  <tr>
    <td width="60%" valign="top">
      <h3>Forensic Cellular Monitoring Interface</h3>
      <p>
        ICdetection is a local-first Android tool for cellular-network auditing,
        anomaly analysis, and real-time radio telemetry visualization.
      </p>
      <ul>
        <li><strong>Forensic Terminal:</strong> structured security and radio-event logging.</li>
        <li><strong>Telemetry:</strong> real-time RSRP, RSRQ, SINR and Timing Advance visualization when available.</li>
        <li><strong>Security Engine:</strong> conservative multi-heuristic cellular anomaly analysis.</li>
      </ul>
    </td>
    <td width="40%" align="center">
      <img src="https://github.com/user-attachments/assets/725ccac0-0759-4b99-a635-d9b60a7da4e1"
           alt="ICdetection Screenshot"
           width="260" />
    </td>
  </tr>
</table>

# ICdetection — Open-Source Cellular Security Auditor

Version 2.10.3 corrects H15 RF-identity stability. Its own isolated failures no longer freeze the baseline, and persistence now means repeated alternation episodes inside one ARFCN rather than consecutive samples from one handover. A one-way stable PCI replacement is left to historical reconfiguration quarantine. This release is a dataset cut for H15; schema 18 and existing history are preserved.

ICdetection is an open-source Android application focused on cellular-network auditing, heuristic anomaly detection, radio telemetry analysis, forensic logging, and local historical baseline learning.

It is designed for privacy-conscious users, mobile security enthusiasts, researchers, forensic experimentation, cellular infrastructure auditing, and GrapheneOS / Pixel users interested in radio-layer visibility.

ICdetection attempts to identify suspicious cellular behavior potentially associated with:

- rogue base stations
- fake BTS deployments
- IMSI-catcher-like activity
- downgrade attempts
- abnormal reselection behavior
- suspicious topology inconsistencies
- cellular infrastructure impersonation patterns

The application operates from Android userland without requiring root or direct baseband access.

**Important:** ICdetection is not an IMSI-catcher proof tool. It is a local-first cellular anomaly auditor. Alerts should be interpreted as signals that the cellular environment deserves closer attention, not as definitive proof of surveillance or interception.

Version 2.10.0 adds descriptive route memory backed by schema 17.
`KNOWN_ON_ROUTE` only means that serving-cell transitions occurred in earlier independent trips; it
does not alter trust, anomaly scores, heuristics, alerts, Stable-Site or forensic decisions. Trips
belong to the background service and continue independently of the Geometry screen.

Version 2.9.1 fixes Stable-Site motion sampling so only new GPS callbacks contribute evidence.
Brief inaccurate fixes abstain without destroying the complete good window, while a 60-second gap
invalidates it. Periodic stationary fixes are requested every 15 seconds with no distance gate;
duplicate or out-of-order timestamps cannot manufacture static duration. The conservative 50 m
accuracy limit and shadow-only rollout remain unchanged. Version 2.9.0 added privacy-reduced stable-site learning. It derives conservative motion state from
successive accurate fixes, aggregates serving and neighbour identities by a hashed approximately
500 m site bucket, and requires multi-day site-specific maturity. A new serving identity while
confirmed static at an active site is evaluated as a potential `SITE_UNVERIFIED` event. Version
2.9.0 reports this in shadow mode without freezing trust, opening forensics or changing H1-H16,
anomaly scores or normal alarms. Schema 16 is additive; existing databases are preserved and site learning starts
from zero because legacy rows lack accuracy, motion and neighbour context. A dedicated Stable-Site
ZIP exports hashed sites, serving/neighbour evidence, motion bands and logical shadow episodes for
field calibration without exact coordinates. `site_cells.csv` and `shadow_episodes.csv` contain
complete cellular identities, like the topology ZIP, so review them before sharing. Version 2.8.1 lets high-frequency cells accumulate temporal trust evidence across the complete
90-day window. The previous 500-row scan could permanently hide the fourteen-day history of cells
seen more than roughly 36 times per day. Detailed radio and location analysis remains bounded, and
its location/RF eligibility minimums use those 500 recent detailed rows. All existing safety gates
for accepting a PCI/ARFCN reconfiguration remain active. Existing
schema-15 databases are reused and must not be cleared. Version 2.8.0 hardens forensic evidence continuity, detects failed
sample writes, preserves whole cases during retention, and enables the declared SQLite foreign
keys. Physical decisions in H8, H11 and H14 now use the modem observation's `radioTech` rather
than the user-facing 4G/5G icon label. This deliberately changes the detection baseline and must
be recorded as a dataset cut. Schema 16 is additive and existing history remains compatible.
The v2.7.0 release also displays a revocable local confidence profile for the serving
cell. It learns only from clean observations spread across independent days and never exceeds 98%.
`ESTABLISHED` means “consistent with this device's historical local pattern”, not “operator-
authenticated” or “guaranteed safe”.

---

# Project Status

> **⚠️ Upgrading from a version older than v2.1.1:** uninstall the previous version first. Android refuses an in-place update when the APK is not signed with the same keystore, and a clean database is required because records written before v2.1.1 may hold an antenna coordinate where the device GPS position belongs. Export your CSV first if you want to keep the old history. v2.1.2 and later releases signed with the same keystore update in place normally. See `Status.md`.

ICdetection v2.10.0 is the current release candidate. The v2.7 line materially hardened detection by
correlating independent anomaly families across short episodes, protecting learned baselines from
suspicious observations, adding hysteresis to noisy neighbour readings, and introducing revocable
local cell confidence. RF identity is learned per carrier, while possible legitimate operator
reconfigurations remain quarantined until they accumulate fourteen coherent days of evidence.
These changes improve sensitivity without removing temporal confirmation or presenting historical
consistency as proof that a transmitter is authentic.

> **Definitive field-collection freeze:** v2.3.3 began the definitive data-collection campaign;
> v2.3.4, v2.3.5 and v2.4.0 are targeted data-integrity and collection-continuity corrections.
> v2.5.0 changed H16 shortcut handling, v2.5.1 changed baseline admission and temporal
> confirmation, v2.5.2 changes GNSS admission for geographic evidence, v2.6.0 restructures the
> service and localizes the interface, v2.7.0 hardens multi-signal detection and trusted
> learning, and v2.7.1 improves geometry and antenna-history inspection without changing detection; record these
> installation dates as dataset cuts.
> v2.8.0 is the final planned correction before definitive collection and creates a new dataset cut.
> After it is validated, no further planned releases or detector changes will be made for at least one month, unless a defect
> threatens data integrity, collection continuity, security, or the ability to export the results.

> **A note to users:** We apologize for the unusually frequent updates during this development
> phase. They were necessary to correct issues discovered through real-world testing. The project
> is now entering a stabilization period, with no further changes planned unless a significant bug
> is found.
>
> **Nota para los usuarios:** Pedimos disculpas por la frecuencia inusual de las actualizaciones
> durante esta fase de desarrollo. Fueron necesarias para corregir problemas encontrados durante
> las pruebas reales. El proyecto entra ahora en una fase de estabilización y no se prevén más
> cambios salvo que aparezca un error importante.

> **What v2.3.0 changes:** H16 compares a real handover with device movement, GPS quality, visible
> neighbours, locally learned coverage zones, and trusted previous transitions. It has no fixed
> “maximum tower distance” and returns `N/A` whenever the available evidence is not defensible.

> **Topology explorer:** the history area now includes a local, read-only view of cells and
> directional handover routes. Analysts can inspect route frequency, trusted observations, last
> state, and recency without changing the detector or its learned baseline. A privacy-gated ZIP
> export provides CSV, directed GraphML, metadata, and SHA-256 checksums for external analysis.

> **What the v2.2 series means:** an observation at `1/3` can leave an incident record and start a forensic capture, while only a sustained event reaching `3/3` is presented as confirmed. This improves traceability without weakening the conservative confirmation model.

v2.1.1 was the project's **data-integrity release**. It added no new heuristics and no new detection claims. It exists because the first two months of field collection surfaced problems that made that very collection unable to answer the questions it was designed to answer: sub-threshold heuristic failures were recorded with their reason erased, API-supplied tower coordinates were overwriting the device's own GPS positions in the history, two heuristics were firing on carrier-aggregation artifacts, and the history was so sparse that the RF fingerprint never woke up. `Status.md` and `v2.1Roadmap.md` carry the full evidence and the fixes.

**H16 begins as a conservative field-validation rule.** Its low weight cannot trigger an alert by
itself, related mobility evidence is not double-counted, and its local transition baseline learns
only from coherent events with mature geographic history. Field data is still required to validate
its false-positive rate and the expert-estimated Bayesian likelihood ratios.

Future work continues to focus on:

- bug fixes
- field validation
- false-positive analysis
- exported CSV review
- small targeted corrections discovered in real-world usage

No new detection claims should be assumed until they are validated against real-world data.

---

# Community Recognition

[![Featured in awesome-telco](https://img.shields.io/badge/Featured%20in-awesome--telco-4c8bf5.svg)](https://github.com/ravens/awesome-telco)

ICdetection is listed in [**awesome-telco**](https://github.com/ravens/awesome-telco), a curated
community collection of telecommunications software, research resources, protocols, datasets, and
security tooling. Its inclusion helps researchers and telecommunications practitioners discover the
project alongside other open-source tools in the field.

Inclusion in a community-maintained list is recognition and visibility, not an independent security
audit, certification, or endorsement of ICdetection's detection results.

---

## Table of Contents

- [Community Recognition](#community-recognition)
- [Development Disclosure](#development-disclosure)
- [Project Philosophy](#project-philosophy)
- [Technical Limitations](#technical-limitations)
- [Device & Hardware Compatibility](#device--hardware-compatibility)
- [Detection Engine](#detection-engine)
- [Statistical and Historical Hardening](#statistical-and-historical-hardening)
- [Temporal Confidence](#temporal-confidence)
- [Incident Black Box](#incident-black-box)
- [Capability Diagnostics & Baseline Maturity](#capability-diagnostics--baseline-maturity)
- [Battery Optimization](#battery-optimization)
- [Telemetry & Visualization](#telemetry--visualization)
- [Infrastructure Verification](#infrastructure-verification)
- [Privacy & Networking](#privacy--networking)
- [Forensic Logging](#forensic-logging)
- [Forensic Case Export](#forensic-case-export)
- [False Positives](#false-positives)
- [Security & Threat Model](#security--threat-model)
- [Related Research & Inspiration](#related-research--inspiration)
- [License](#license)
- [Acknowledgements](#acknowledgements)

---

# Development Disclosure

This project was developed using an AI-assisted workflow.

The lead developer (**Alexis Gomez Rodriguez**) designed and directed:

- heuristic logic
- detection architecture
- threat scoring behavior
- forensic telemetry workflow
- validation methodology
- anomaly correlation logic
- false-positive reduction strategy
- project direction and operational philosophy

AI assistance, including Gemini, Claude, ChatGPT and DeepSeek, was used for:

- Kotlin implementation support
- Android API integration
- architectural iteration
- debugging assistance
- refactoring support
- documentation support

The code was written with AI assistance, but the system design, detection approach, heuristic selection, false-positive tradeoffs, validation decisions, and project direction were manually reviewed and directed by the developer.

I do not claim to be a Kotlin, Android, or telecommunications expert. This project is the result of focused self-study, field testing, careful iteration, and AI-assisted development under my direction.

Every heuristic and detection decision in this project is something I aim to understand, explain, and defend honestly.

---

# Project Philosophy

ICdetection does **not** claim to provide definitive IMSI-catcher detection.

Instead, the application follows a probabilistic forensic approach based on:

- heuristic correlation
- anomaly scoring
- infrastructure consistency validation
- timing analysis
- local telemetry verification
- behavioral pattern analysis
- historical baseline learning

The primary goal is to provide visibility, anomaly awareness, forensic logging, infrastructure auditing, radio telemetry analysis, and local evidence for later review within the technical limits imposed by Android.

---

# Technical Limitations

Modern Android devices do not expose the complete cellular protocol stack to third-party applications.

This means ICdetection cannot directly access:

- complete RRC signaling
- NAS messages
- full modem telemetry
- low-level baseband internals
- complete LTE/5G control-plane traffic
- cryptographic session details
- all identity-request events
- all ciphering state transitions

As a result, detection is heuristic in nature and should never be interpreted as definitive proof of surveillance or interception.

Sophisticated LTE/5G interception systems may emulate legitimate carrier infrastructure and remain difficult, or impossible, to distinguish from real towers using Android-only telemetry.

---

# Device & Hardware Compatibility

ICdetection relies heavily on Android radio callbacks and telephony APIs. Hardware, firmware, modem implementation, Android version, and vendor HAL behavior significantly affect telemetry quality.

## Recommended Environment

Google Pixel devices running:

- stock Android
- GrapheneOS
- near-AOSP Android environments

generally provide cleaner and more consistent Android telephony behavior than heavily customized OEM builds.

Advanced radio-security signals such as ciphering state, identifier disclosure events, or modem-level security callbacks remain highly dependent on:

- Android version
- modem firmware
- vendor HAL support
- exposed Android APIs
- required system permissions
- device-specific implementation details

If these signals are not exposed by the device, ICdetection marks or treats them as unavailable rather than assuming they are safe.

## OEM Firmware Limitations

Manufacturers using heavily customized Android stacks, such as OneUI, MIUI, EMUI and similar systems, may modify or restrict radio HAL behavior.

This may:

- obfuscate radio metrics
- suppress Timing Advance visibility
- hide or alter signal-quality values
- limit telephony callback consistency
- prevent access to ciphering or identifier-disclosure information

The application may still function normally on these devices, but some advanced heuristics can become partially degraded or unavailable.

## Android Support

- **Android 14+:** best practical support currently available in Android userland.
- **Android 12 / 13:** supported in heuristic-analysis mode.
- **Android 10 / 11:** may work depending on device telemetry support, but advanced behavior can be limited.

---

# Detection Engine

ICdetection continuously performs multi-layer heuristic analysis during cell reselections, handovers, signal transitions, topology changes, mobility events, and observed changes in local radio behavior.

Current analysis layers include:

## Isolated Cell Detection

Detects serving cells operating without coherent neighboring infrastructure. This can be useful against amateur rogue BTS deployments or isolated SDR configurations, but it can also occur in rural areas or unusual coverage environments.

## Signal Dominance Analysis

Detects abnormal signal dominance deltas between the serving cell and neighboring cells, potentially indicating forced camping behavior. This is environment-sensitive and is softened in sparse or rural contexts to reduce false positives.

## MCC Consistency Validation

Detects Mobile Country Code inconsistencies between nearby cells. This is a strong anomaly when observed in normal non-border environments, but still requires contextual interpretation.

## Multi-MNC Density Analysis

Flags environments containing unusually high operator-code diversity. This is treated as weak evidence because MVNOs, roaming, transport hubs, and border regions can produce legitimate diversity.

## TAC Regional Consistency

Validates Tracking Area Code coherence against surrounding infrastructure. TAC deviations can be meaningful, but operator maintenance and regional network changes can also produce unusual values.

## Timing Advance Geometric Analysis

Attempts to correlate physical distance estimation using Timing Advance telemetry and tower-position validation when enough data is available. This is highly device-dependent and may be unavailable or unreliable on some hardware.

## Ghost Neighbor Detection

Detects cases where the serving cell is very strong while all visible neighboring cells are extremely weak. This may indicate an artificially controlled RF environment, but can also occur in difficult radio conditions.

## ARFCN / Frequency Sanity Validation

Performs frequency sanity checks against expected radio ranges and technology-specific limits. This is mostly a guard against impossible or malformed radio values, not a complete regional spectrum validator.

## Ciphering Integrity Monitoring

ICdetection v2.0 does not claim direct null-cipher or IMSI-disclosure detection on standard Android installs.

Ciphering state is reported as unavailable unless the operating system exposes a supported and accessible signal. If the device does not expose this information, ICdetection treats it as N/A rather than PASSED or FAILED. It does not assume the network is safe simply because ciphering information cannot be read.

## Anti Ping-Pong Analysis

Detects aggressive reselection loops and repetitive handover behavior while applying mobility-aware filtering to reduce false positives during vehicular movement.

## Geographic Consistency Analysis

Validates Cell IDs against a local GPS-based historical database built from previous observations.

This can detect cases where the same Cell ID appears from physically inconsistent locations over time, which may be consistent with mobile rogue infrastructure cloning legitimate tower identifiers.

Because it is anchored to the device's own location history, this heuristic does not rely on public tower databases. Its scope is limited to cells for which prior history and a valid location fix already exist.

## Signal Baseline Anomaly

Learns each cell's typical signal level at a given location from the device's own historical observations, then flags readings that are anomalously strong compared to that learned baseline.

A nearby transmitter impersonating a cell that is normally weaker at that location may appear far stronger than its own history.

Only the "stronger than usual" direction is treated as suspicious, since weaker-than-usual readings are commonly caused by obstruction, distance, congestion, or environmental changes.

## Intra-LTE Band Downgrade Analysis

Detects suspicious shifts from high-frequency capacity bands to lower-frequency sub-GHz bands when the previous signal was strong and there is no evidence of progressive signal degradation.

This can be consistent with forced camping or rogue-cell behavior, but it is not treated as proof by itself.

## RF Identity Stability Analysis

Inspects the device's own historical record for a given cell identity and flags cases where that identity has recently alternated between multiple persistent PCI values.

This can be consistent with a clone reusing a legitimate Cell ID with a different physical-layer identity. It complements geographic consistency because it can fire while the user is stationary.

The heuristic is intentionally conservative: a PCI is only treated as a genuine alternate identity when it appears repeatedly, represents a meaningful share of observations, and is still present within a recent time window.

Field testing showed that ARFCN is not reliable enough for this identity-stability decision because carrier aggregation can cause serving-cell ARFCN values to appear inconsistent. Therefore this heuristic deliberately focuses on PCI.

**v2.1 correction.** Longer field data showed that carrier aggregation affects the reported PCI in exactly the same way: when the modem attributes a secondary carrier's ARFCN to the serving cell, it attributes that carrier's PCI too. Across 59 days the correlation was perfect — a given PCI appeared only ever on one ARFCN, with no crossover. The heuristic therefore now compares PCI values **only within the same carrier (ARFCN)**. A clone that reconfigures its PCI does so on its own carrier, so real detection is unaffected; what disappears is a false positive caused by the device's own reporting.

## RF Quality Fingerprint

Complementing the signal-power baseline, the engine can learn each cell's signal-quality signature using RSRQ and SINR.

Because RSRQ and SINR are noisy, this check is deliberately strict. It requires many samples and a large deviation in both metrics simultaneously. It remains dormant until enough history accumulates.

---

# Statistical and Historical Hardening

Beyond individual heuristics, the engine refines the quality of its evidence using the device's own accumulated history. These layers are fully offline and conservative by design.

## Percentile-Based Baseline

In addition to mean and standard deviation, the per-cell power baseline stores high-percentile historical values. With sufficient samples, an anomaly must exceed the cell's own high historical range, making the check more robust against non-normal distributions and occasional legitimate strong readings.

## Cell Reputation

Each cell earns a trust score derived from local history: observation volume, observations across distinct days, and the proportion of clean past scores.

This trust is used only to dampen the weight of noisy instantaneous heuristics on well-established cells. It never increases suspicion and does not suppress physics-anchored evidence such as geographic inconsistency, MCC mismatch, ciphering failure, or RF identity instability.

## Context-Aware Scoring

Some heuristics are environment-sensitive. A strong isolated cell is much more suspicious in dense urban conditions than in a rural coverage area. ICdetection softens these weak signals based on neighbor density and local cell reputation.

## Bayesian Threat Probability

The app includes a Bayesian-inspired scorer that estimates threat probability from failed heuristics. Correlated evidence groups are handled conservatively so that multiple symptoms of the same physical phenomenon do not inflate the result unfairly.

The values are expert-derived estimates, not scientifically measured likelihood ratios. They are used as a structured way to reason about uncertainty, not as a claim of mathematical certainty.

---

# Temporal Confidence

Anomalies must persist across multiple analysis cycles before triggering a confirmed threat alert.

Transient heuristic failures are logged but do not immediately raise confirmed alarms. This reduces false-positive fatigue in dynamic RF environments and makes the app more useful during daily use.

v2.2.0 exposes the full temporal progression:

- `1/3` — initial observation; an incident record and forensic capture may begin.
- `2/3` — the condition persists, but it is not yet a confirmed threat.
- `3/3` — the configured temporal confirmation requirement has been reached.

The phase can move as fresh radio observations arrive. A rule may also move between `N/A`, `PASS`,
and `FAIL` when the modem or Android begins or stops exposing the data needed to evaluate it.

---

# Incident Black Box

The incident black box preserves the lifecycle of a suspicious episode independently from the
ordinary antenna history. It records the first observation at `1/3`, the highest temporal phase
reached, score, anomaly confidence, reason, and a snapshot of the heuristic diagnostics.

Incident states are:

- `OBSERVING` — the condition has started but is not confirmed.
- `CONFIRMED` — the event reached `3/3`.
- `RECOVERED` — subsequent observations returned to normal.
- `INTERRUPTED` — the application or monitoring service stopped before the lifecycle completed.

This history improves later review, but it does not turn a heuristic alert into definitive proof
of a rogue base station or surveillance activity.

---

# Capability Diagnostics & Baseline Maturity

The live diagnostics view reports whether each rule passed, failed, or could not be evaluated.
An `N/A` result is accompanied by a contextual explanation, such as unavailable modem telemetry,
missing location, insufficient latency context, or an immature historical baseline.

The application also reports the maturity of the historical data used by:

- the RSRP power baseline
- the RSRQ/SINR quality fingerprint
- PCI identity stability
- local cell reputation

These indicators make it possible to distinguish an inactive-looking rule from one that is waiting
for enough trustworthy data. They are diagnostic information and do not independently increase the
threat score.

---

# Battery Optimization

ICdetection requires unoptimized battery settings to ensure reliable long-running monitoring.

Version 2.4.0 deliberately keeps the GPS-only stream active 24/7 while monitoring and holds a partial
wake lock so the collection loop continues with the screen off. This materially increases battery
use. Below 5% battery while unplugged, GPS and the wake lock pause to avoid losing the entire
collection session to a powered-off phone; they resume automatically after recovery or charging.
Android battery optimization and manufacturer policies may still interfere, so exclude the app
manually when continuity matters.

Recommended setting:

**Settings -> Apps -> ICdetection -> Battery -> Battery optimization -> Don't optimize**

The app may guide the user toward the relevant settings screen, but the final exception grant is controlled by Android and the user.

---

# Telemetry & Visualization

The live identity row shows `CELL ID`, `TAC/LAC`, the operator as `MCC / MNC`, and `ARFCN` in a
compact four-column layout. Expanded history rows also retain the visible `MCC/MNC` identity.

## Forensic Terminal

Structured event-driven logging designed to preserve meaningful security and radio events while minimizing noisy output.

## Real-Time Signal Graphs

Continuous signal visualization with contextual heuristic overlays.

## Timing Advance Visualization

Timing Advance visualization for distance-behavior analysis when TA is exposed by the device.

## Threat Scoring Engine

Dynamic multi-factor anomaly scoring with contextual correlation.

## Persistent Telemetry History

Session-aware logging and local infrastructure observation tracking.

---

# Infrastructure Verification

ICdetection can optionally cross-reference observed infrastructure using OpenCellID.

These services are used to compare observed cells against publicly known crowdsourced databases.

Public tower databases are:

- incomplete
- community-maintained
- occasionally outdated
- uneven across regions and operators

A "not found" result does **not** imply malicious infrastructure.

External requests are only made when the user configures and enables infrastructure verification APIs.

WiGLE is no longer queried. Its limited quota and inconclusive replies could obscure a valid
OpenCellID result without adding dependable coverage. Existing local history remains compatible.

---

# Privacy & Networking

## Local-First Design

The application follows a local-first privacy philosophy.

By default:

- no cloud synchronization exists
- no analytics are collected
- no advertising SDKs are included
- no hidden telemetry exists
- no user tracking exists

All historical telemetry is stored locally on the device unless the user explicitly exports it.

## Optional SOCKS5 Routing

ICdetection supports optional SOCKS5 proxy routing for infrastructure-verification requests, including Tor / Orbot-style local proxy setups.

---

# Forensic Logging

## SQLite Event Storage

Telemetry and forensic events are stored locally on-device in SQLite.

v2.2.0 adds bounded forensic cases. A case begins automatically when an observation reaches phase
`1/3`, includes up to 60 seconds of pre-event context, follows the complete episode, and remains
open for 60 seconds after recovery. A recurrence during that window continues the same case. An
individual capture is limited to 30 minutes.

The recorder can preserve serving and neighboring cell observations, radio identity and quality,
Timing Advance when available, GPS position and accuracy, latency and verification state, temporal
phase, score, confidence, heuristic explanations, device capabilities, and relevant terminal logs.
It deliberately excludes API credentials, IMSI, IMEI, and the phone number.

Cases move through `CAPTURING`, `POST_CAPTURE`, `READY`, and `INTERRUPTED` states. The forensic view
refreshes while monitoring so the current case state remains visible.

## Forensic Case Export

A ready case can be exported as a ZIP archive containing:

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

`SHA256SUMS.txt` can reveal whether an exported member was modified later. It is not a digital
signature and does not, by itself, establish legal chain of custody. Because an export can contain
precise location and cellular metadata, review and protect it before sharing.

## CSV Export

CSV export is available for:

- external analysis
- mapping
- reporting
- research
- archival workflows

Columns: `Timestamp, NetType, CID, MNC, TAC, MCC, DBM, Verified, SecurityScore, FailedHeuristics, Lat, Lon, PCI, ARFCN, RSRQ, SINR, AnomalyConfidence, ApiLat, ApiLon, TA, TAUnit, TAMeters, Radio`.

Validate any export with `python3 tools/check_export.py <file.csv>` — it checks the invariants the
design guarantees and prints how mature the history is.

Two points matter for analysis:

- **`Lat` / `Lon` are the device's own GPS position** at the moment of the observation, and nothing else. The antenna position reported by OpenCellID lives in its own `ApiLat` / `ApiLon` columns. Before v2.1 both were written to the same pair of columns, which silently mixed two different quantities.
- **`FailedHeuristics` entries prefixed with `[sub-umbral]`** are heuristics that failed without reaching the alarm threshold. They are observations, not alerts, and they are recorded precisely so that false positives can be studied. Before v2.1 they were stored as `OK`.
- **`Verified` is a label, not a verdict, and it has five values.** `VERIFIED` (OpenCellID returned *this* cell — identity checked field by field — with a credible coordinate), `NOT_FOUND` (OpenCellID's documented "cell not found" response), `REJECTED` (a reply arrived but did not survive the checks — wrong identity, sentinel or impossible coordinate, incomplete body), `ERROR` (no interpretable reply: quota, rejected key, timeout, 404) and `PENDING`. None of them changes `SecurityScore`: the score comes only from the local heuristic engine. A `VERIFIED` older than 30 days is re-checked; historical rows are kept either way.

Exports may contain sensitive location and cellular metadata. Users should treat exported files as private forensic material.

---

# False Positives

False positives are possible and expected.

Legitimate situations that may trigger heuristic alerts include:

- carrier maintenance
- roaming environments
- dense urban deployments
- indoor DAS systems
- femtocells
- NSA / 5G transitions
- temporary spectrum reconfiguration
- rural coverage gaps
- public transport routes
- tunnels, basements, elevators, and parking garages
- vendor-specific Android radio behavior

ICdetection should be interpreted as a forensic auditing tool, telemetry analyzer, anomaly detector, and local evidence collector, not as definitive proof of surveillance activity.

---

# Security & Threat Model

ICdetection is primarily useful against:

- amateur rogue BTS deployments
- poorly configured SDR towers
- simple fake base stations
- aggressive downgrade attempts
- abnormal cellular behavior
- topology inconsistencies
- unstable Cell ID / PCI behavior

The application is less reliable against:

- sophisticated LTE/5G interception platforms
- carrier-grade rogue infrastructure
- systems that accurately emulate legitimate network parameters
- attacks that do not expose anomalies through Android APIs
- lawful intercept or surveillance occurring inside operator infrastructure

These limitations are inherent to Android userland restrictions.

---

# Related Research & Inspiration

This project was inspired by public research involving:

- LTE security
- IMSI-catcher detection
- SDR rogue BTS analysis
- Android telephony limitations
- cellular anomaly detection
- radio-layer privacy research

Relevant public projects and research areas include:

- SnoopSnitch
- AIMSICD
- LTEInspector
- OWL
- academic LTE-security research
- SDR-based rogue BTS experimentation

---

# License

Licensed under the **GNU General Public License v3.0 (GPL-3.0)**.

You are free to:

- use
- study
- modify
- redistribute
- audit

the software under GPL terms.

Derivative works must remain open-source under GPL-compatible licensing.

---

# Acknowledgements

Thank you to everyone who has followed the project through its many iterations.

ICdetection v2.9.1 is the current development release within the boundaries of what Android userland
allows without root or direct baseband access.

Future updates will focus on bug fixes, field validation, false-positive analysis, and minor improvements discovered through real-world usage.

This is the first Android application I have ever built, and I put a lot of care into it.

I do not have formal telecommunications or Android-development training. This project is the result of focused self-study, field testing, and AI-assisted development under my direction. I designed the detection approach, selected and rejected heuristics, reviewed the logic, tested behavior in real conditions, and made the project decisions while AI tools helped with Kotlin/Android implementation.

If you have questions, find mistakes, or run into issues, please open an issue. I will review it honestly and fix what I can.

The detection baseline is stable/frozen while real-world data is collected. Future detection changes will be based on observed behavior, false positives, and field evidence rather than adding heuristics for their own sake.

Best regards,
Alexis

Carpe diem.
