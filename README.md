![License: GPL v3](https://img.shields.io/badge/License-GPLv3-blue.svg)
![Platform](https://img.shields.io/badge/Platform-Android%2012%2B-green.svg)
![Root Required](https://img.shields.io/badge/Root-Not%20Required-brightgreen.svg)
![Status](https://img.shields.io/badge/Status-Active-success.svg)

<table>
  <tr>
    <td width="60%" valign="top">
      <h3>📱 Forensic Cellular Monitoring Interface</h3>
      <p>
        ICdetection provides a minimalist forensic-oriented interface designed
        for continuous mobile network auditing, anomaly analysis,
        and real-time cellular telemetry visualization.
      </p>
      <ul>
        <li><strong>Forensic Terminal:</strong> Event-driven structured security logging.</li>
        <li><strong>Telemetry:</strong> Real-time RSRP and Timing Advance (TA) visualization.</li>
        <li><strong>Security Engine:</strong> Continuous multi-heuristic cellular analysis.</li>
      </ul>
    </td>
    <td width="40%" align="center">
      <img src="https://github.com/user-attachments/assets/1be2b0d4-70ef-45f5-a6bc-8fb2cb625a3e"
           alt="ICdetection Screenshot"
           width="260" />
    </td>
  </tr>
</table>

# 📡 ICdetection — Open-Source Cellular Security Auditor

ICdetection is an open-source Android application focused on:
- cellular network auditing
- heuristic anomaly detection
- radio telemetry analysis
- forensic mobile-network monitoring

The project is designed for:
- researchers
- privacy-conscious users
- mobile security enthusiasts
- forensic experimentation
- cellular infrastructure auditing

ICdetection attempts to identify suspicious cellular behavior potentially associated with:
- rogue base stations
- fake BTS deployments
- IMSI-catcher-like activity
- downgrade attempts
- abnormal reselection behavior
- suspicious topology inconsistencies

The application operates entirely from Android userland without requiring root or direct baseband access.

---

# 🛠️ Development Disclosure

This project was developed using an AI-assisted workflow.

The lead developer (**Alexis Gómez Rodríguez**) designed:
- heuristic logic
- detection architecture
- threat scoring behavior
- forensic telemetry workflow
- validation methodology
- anomaly correlation logic

AI assistance (Gemini, Claude, ChatGPT, DeepSeek) was used for:
- Kotlin implementation support
- Android API integration
- architectural iteration
- debugging assistance
- refactoring support

All core detection logic, heuristics, and operational decisions were manually reviewed and validated.

---

# 🔒 Project Philosophy

ICdetection does **not** claim to provide definitive IMSI-catcher detection.

Instead, the application follows a probabilistic forensic approach based on:
- heuristic correlation
- anomaly scoring
- infrastructure consistency validation
- timing analysis
- telemetry verification
- behavioral pattern analysis

The primary goal of the project is to provide:
- visibility
- anomaly awareness
- forensic logging
- infrastructure auditing
- radio telemetry analysis

within the technical limitations imposed by Android.

---

# ⚠️ Important Technical Reality

Modern Android devices do not expose the complete cellular protocol stack to third-party applications.

This means ICdetection cannot directly access:
- complete RRC signaling
- NAS messages
- full modem telemetry
- low-level baseband internals
- complete LTE/5G control-plane traffic

As a result:

Detection is heuristic in nature and should never be interpreted as definitive proof of surveillance or interception.

Sophisticated LTE/5G interception systems may emulate legitimate carrier infrastructure and remain difficult to distinguish from real towers using Android-only telemetry.

---

# 📱 Device & Hardware Compatibility

ICdetection relies heavily on Android radio callbacks and low-level telephony APIs. Hardware, firmware, and vendor implementations significantly affect telemetry quality.

## Recommended: Google Pixel / GrapheneOS

Google Pixel devices (especially Tensor-based models) running:
- stock Android
- GrapheneOS
- near-AOSP environments

currently provide the most reliable telemetry exposure available in Android userland.

These environments allow improved access to:
- ciphering-state callbacks
- Timing Advance telemetry
- radio event consistency
- identifier disclosure events

## OEM Firmware Limitations

Manufacturers using heavily customized Android stacks (OneUI, MIUI, EMUI, etc.) frequently modify or restrict the radio HAL layer.

This may:
- obfuscate radio metrics
- block security callbacks
- suppress Timing Advance visibility
- hide ciphering-state information

The application will still function normally on these devices, but some advanced heuristics may become partially degraded or unavailable.

## Android Support

- **Android 14+:** Best support currently available in Android userland.
- **Android 12 / 13:** Fully supported in heuristic-analysis mode.

---

# 🚀 Detection Engine

ICdetection continuously performs multi-layer heuristic analysis during:
- cell reselections
- handovers
- signal transitions
- topology changes
- mobility events

Current heuristics include:

## 1. Isolated Cell Detection
Detects serving cells operating without coherent neighboring infrastructure. Useful against amateur rogue BTS deployments or isolated SDR configurations.

## 2. Signal Dominance Analysis
Detects abnormal signal dominance deltas between serving and neighboring cells, potentially indicating forced camping behavior.

## 3. MCC Consistency Validation
Detects Mobile Country Code inconsistencies between nearby cells.

## 4. Multi-MNC Density Analysis
Flags environments containing unusually high operator-code diversity.

## 5. TAC Regional Consistency
Validates Tracking Area Code coherence against surrounding infrastructure.

## 6. Timing Advance Geometric Analysis
Attempts to correlate physical distance estimation using Timing Advance telemetry and tower-position validation. *(Highly device-dependent)*.

## 7. Ghost Neighbor Detection
Detects invalid or incoherent neighboring-cell behavior.

## 8. ARFCN / Frequency Sanity Validation
Performs frequency-consistency analysis against expected radio behavior and regional allocations.

## 9. Ciphering Integrity Monitoring
Attempts to detect insecure or non-ciphered cellular states (A5/0) when exposed by the Android framework or modem firmware.

## 10. Anti Ping-Pong Analysis
Detects aggressive reselection loops and repetitive handover behavior while applying mobility-aware filtering to reduce false positives during vehicular movement.

## 11. Geographic Consistency Analysis (H11)
Validates Cell IDs against a local GPS-based historical database built from previous observations. Detects Cell IDs appearing from physically inconsistent locations over time — a strong indicator of mobile rogue infrastructure cloning legitimate tower identifiers.

Because it is anchored to physical geography (the device's own GPS position), this heuristic is immune to RF parameter spoofing: no falsified radio parameter can alter the phone's real-world location. It is also fully offline and cannot be poisoned by registering fake towers in public databases, since it relies on no external database at all.

Its scope is limited to cells for which prior history (and a valid GPS fix) already exists. As such, it detects **identifier cloning at a distance** — but it does not catch a catcher that uses an unknown/fresh Cell ID, one that operates physically next to the legitimate tower, or a cell observed for the first time with no historical baseline yet.

## Temporal Confidence Decay
Anomalies must persist across 3 consecutive analysis cycles before triggering a confirmed threat alert. Transient heuristic failures are logged but do not raise alarms, significantly reducing false positive alert fatigue in dynamic RF environments.

---

# 📊 Telemetry & Visualization

## Forensic Terminal
Structured event-driven forensic logging designed to preserve meaningful security events while minimizing noisy output.

## Real-Time Signal Graphs
Continuous RSRP visualization with contextual heuristic overlays.

## Timing Advance Visualization
Geometric TA visualization for distance-behavior analysis.

## Threat Scoring Engine
Dynamic multi-factor anomaly scoring with contextual correlation.

## Persistent Telemetry History
Session-aware logging and infrastructure observation tracking.

---

# 🛡️ Infrastructure Verification

ICdetection optionally cross-references observed infrastructure using:
- OpenCellID
- WiGLE

to compare nearby towers against publicly known crowdsourced databases.

### Important

Public tower databases are:
- incomplete
- community-maintained
- occasionally outdated

A "not found" result does **not** imply malicious infrastructure.

---

# 🌐 Privacy & Networking

## Optional SOCKS5 Routing

ICdetection supports optional SOCKS5 proxy routing:
- Tor
- Orbot
- privacy-oriented VPN chains

for infrastructure-verification requests.

## Local-First Design

The application follows a strict local-first privacy philosophy.

By default:
- no cloud synchronization exists
- no analytics are collected
- no advertising SDKs are included
- no hidden telemetry exists
- no user tracking exists

---

# 💾 Forensic Logging

## SQLite Event Storage
All telemetry and forensic events remain stored locally on-device.

## CSV Export
Export support exists for:
- external analysis
- mapping
- reporting
- research
- archival workflows

---

# 📚 Related Research & Inspiration

This project was inspired by public research involving:
- LTE security
- IMSI-catcher detection
- SDR rogue BTS analysis
- Android telephony limitations
- cellular anomaly detection

Relevant public projects and research areas include:
- SnoopSnitch
- AIMSICD
- LTEInspector
- OWL
- academic LTE-security research
- SDR-based rogue BTS experimentation

---

# ⚠️ False Positives

False positives are possible and expected.

Legitimate situations that may trigger heuristic alerts include:
- carrier maintenance
- roaming environments
- dense urban deployments
- indoor DAS systems
- femtocells
- NSA/5G transitions
- temporary spectrum reconfiguration
- rural coverage gaps

ICdetection should be interpreted as:
- a forensic auditing tool
- a telemetry analyzer
- an anomaly detector

—not as definitive proof of surveillance activity.

---

# 🔐 Security & Threat Model

ICdetection is primarily effective against:
- amateur rogue BTS deployments
- poorly configured SDR towers
- simple fake base stations
- aggressive downgrade attempts
- abnormal cellular behavior
- topology inconsistencies

The application is significantly less reliable against:
- sophisticated LTE/5G interception platforms
- carrier-grade rogue infrastructure
- advanced state-level systems

These limitations are inherent to Android userland restrictions.

---

# ⚖️ License

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

# 🙏 Acknowledgements

Thank you all for your understanding throughout the many iterations this project has gone through.

The project is now considered stable and feature-complete within the boundaries of what Android userland allows without root access.

Future updates will focus on bug fixes and minor improvements as they are discovered in real-world usage.

This is the stable release. Thank you, and best regards to everyone who has followed the project.

-- Alexis
