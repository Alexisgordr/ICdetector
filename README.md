![License: GPL v3](https://img.shields.io/badge/License-GPLv3-blue.svg)
![Platform](https://img.shields.io/badge/Platform-Android%2010%2B-green.svg)
![Root Required](https://img.shields.io/badge/Root-Not%20Required-brightgreen.svg)
![Status](https://img.shields.io/badge/Status-Stable%20v2.0-success.svg)

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
      <img src="https://github.com/user-attachments/assets/81bf1ed6-94c4-462e-8d13-828f7754f532"
           alt="ICdetection Screenshot"
           width="260" />
    </td>
  </tr>
</table>

# ICdetection — Open-Source Cellular Security Auditor

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

---

# Project Status

ICdetection v2.0 is currently considered stable and feature-complete within the limits of Android userland telemetry.

The project is intentionally entering a data-collection and observation phase. During this period, future work will focus on:

- bug fixes
- field validation
- false-positive analysis
- exported CSV review
- small targeted corrections discovered in real-world usage

No new detection claims should be assumed until they are validated against real-world data.

---

## Table of Contents

- [Development Disclosure](#development-disclosure)
- [Project Philosophy](#project-philosophy)
- [Technical Limitations](#technical-limitations)
- [Device & Hardware Compatibility](#device--hardware-compatibility)
- [Detection Engine](#detection-engine)
- [Statistical and Historical Hardening](#statistical-and-historical-hardening)
- [Temporal Confidence](#temporal-confidence)
- [Battery Optimization](#battery-optimization)
- [Telemetry & Visualization](#telemetry--visualization)
- [Infrastructure Verification](#infrastructure-verification)
- [Privacy & Networking](#privacy--networking)
- [Forensic Logging](#forensic-logging)
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

Attempts to detect insecure, null, or disabled cellular ciphering states when such information is exposed by Android, the modem firmware, or device-specific callbacks.

If the device does not expose this information, ICdetection treats it as unavailable. It does not assume the network is safe simply because ciphering information cannot be read.

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

---

# Battery Optimization

ICdetection requires unoptimized battery settings to ensure reliable long-running monitoring.

The application runs a continuous foreground service that scans cells, registers telephony callbacks, and manages location fixes. Android battery optimization mechanisms such as Doze and App Standby may throttle this behavior when the phone is idle with the screen off.

Recommended setting:

**Settings -> Apps -> ICdetection -> Battery -> Battery optimization -> Don't optimize**

The app may guide the user toward the relevant settings screen, but the final exception grant is controlled by Android and the user.

---

# Telemetry & Visualization

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

ICdetection can optionally cross-reference observed infrastructure using:

- OpenCellID
- WiGLE

These services are used to compare observed cells against publicly known crowdsourced databases.

Public tower databases are:

- incomplete
- community-maintained
- occasionally outdated
- uneven across regions and operators

A "not found" result does **not** imply malicious infrastructure.

External requests are only made when the user configures and enables infrastructure verification APIs.

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

## CSV Export

CSV export is available for:

- external analysis
- mapping
- reporting
- research
- archival workflows

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

ICdetection is now considered stable and feature-complete within the boundaries of what Android userland allows without root or direct baseband access.

Future updates will focus on bug fixes, field validation, false-positive analysis, and minor improvements discovered through real-world usage.

This is the first Android application I have ever built, and I put a lot of care into it.

I do not have formal telecommunications or Android-development training. This project is the result of focused self-study, field testing, and AI-assisted development under my direction. I designed the detection approach, selected and rejected heuristics, reviewed the logic, tested behavior in real conditions, and made the project decisions while AI tools helped with Kotlin/Android implementation.

If you have questions, find mistakes, or run into issues, please open an issue. I will review it honestly and fix what I can.

The current version is stable/frozen while I take a break and collect real-world data over the next few months. Future improvements will be based on observed behavior, false positives, and field data rather than adding features for their own sake.

Best regards,  
Alexis

Carpe diem.
