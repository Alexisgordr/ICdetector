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
      <img src="https://github.com/user-attachments/assets/d07675cf-04e2-4d7a-b836-03d1988d8ddb"
           alt="ICdetection Screenshot"
           width="260" />
    </td>
  </tr>
</table>

# 📡 ICdetection — Open-Source Cellular Security Auditor

ICdetection is an open-source Android application focused on cellular network auditing, heuristic anomaly detection, and mobile network telemetry analysis.

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
- abnormal reselection behavior
- suspicious network topology inconsistencies

The project operates entirely from Android userland without root or direct baseband access.

---

# 🛠️ Development Disclosure

This project was developed using an AI-assisted workflow.

The lead developer (**Alexis Gómez Rodríguez**) designed:
- the heuristic logic
- the detection architecture
- threat scoring behavior
- forensic telemetry workflow
- field validation methodology

AI assistance (Gemini) was used for:
- Kotlin implementation support
- Android API integration
- refactoring assistance
- architectural iteration
- debugging assistance

All core detection behavior, heuristics, and operational decisions were manually reviewed and validated.

---

# 🔒 Project Philosophy

ICdetection does **not** claim to provide definitive IMSI-catcher detection.

Instead, the application follows a probabilistic forensic approach based on:
- heuristic correlation
- anomaly scoring
- infrastructure consistency checks
- timing analysis
- telemetry validation

The goal of the project is to provide:
- visibility
- auditing
- anomaly awareness
- forensic logging

within the technical limitations imposed by Android.

---

# ⚠️ Important Technical Reality

Modern Android devices do not expose the full cellular protocol stack to third-party applications.

This means ICdetection cannot directly access:
- complete RRC signaling
- NAS messages
- full baseband telemetry
- low-level modem internals
- complete LTE/5G control-plane traffic

As a result:

Detection is heuristic in nature and should never be interpreted as definitive proof of surveillance or interception.

Modern professional-grade IMSI-catchers may emulate legitimate carrier infrastructure and remain difficult to distinguish from real towers using Android-only telemetry.

---

# 📱 Device & Hardware Compatibility

ICdetection relies on low-level system calls (L3) and radio frequency callbacks to audit connection security. Because of this, hardware and firmware play a massive role in the app's detection capabilities.

## Recommended: Google Pixel / GrapheneOS
Pixel devices (specifically those with Tensor chips) running stock firmware, **GrapheneOS**, or near-AOSP environments provide the most transparent and reliable telemetry. 

These environments respect low-level Android APIs, allowing ICdetection to accurately extract:
- Hardware-level ciphering states (detecting unencrypted A5/0 connections).
- Raw Timing Advance (TA) metrics for distance calculation.
- Identifier disclosure events.

## OEM Firmware Limitations (Samsung, Xiaomi, Huawei, etc.)
Manufacturers with heavily customized firmware (e.g., OneUI, MIUI, EMUI) often modify the Hardware Abstraction Layer (HAL) of the radio. 

This can result in the modem firmware blocking or obfuscating critical security sensors. On these devices, the app will still function and evaluate standard metrics, but advanced heuristics (like raw TA extraction or real-time cipher status) may be limited or entirely blocked by the OEM's hardware restrictions.

## Android Support
- **Android 14+:** Provides the best telemetry support currently available in standard Android userland (including ciphering state callbacks).
- **Android 12 / 13:** Fully supported in heuristic analysis mode, though some low-level telemetry features are unavailable due to framework restrictions.

---

# 🚀 Detection Engine

The application continuously performs a multi-layer heuristic audit during:
- handovers
- cell reselections
- signal transitions
- topology changes

Current heuristics include:

## 1. Isolated Cell Detection
Detects serving cells operating without coherent neighboring infrastructure. Useful against misconfigured SDR deployments or amateur rogue BTS setups.

## 2. Signal Dominance Analysis
Detects abnormal signal dominance deltas between the serving cell and neighboring towers, potentially indicating forced camping behavior.

## 3. MCC Consistency Validation
Detects inconsistencies between neighboring Mobile Country Codes.

## 4. Multi-MNC Density Analysis
Flags environments containing unusually high operator-code diversity.

## 5. TAC Regional Consistency
Validates Tracking Area Code coherence against nearby infrastructure.

## 6. Timing Advance Geometric Analysis
Attempts to correlate physical distance estimation using Timing Advance telemetry. *(Highly device-dependent)*.

## 7. Ghost Neighbor Detection
Detects invalid or incoherent neighboring-cell behavior.

## 8. ARFCN / Frequency Sanity Validation
Performs basic consistency analysis against reported radio-frequency behavior.

## 9. Ciphering Integrity Monitoring
Attempts to detect insecure or non-ciphered states (A5/0) when exposed by the Android framework and device firmware.

## 10. Anti Ping-Pong Analysis
Detects aggressive or repetitive reselection loops potentially associated with anomalous cellular behavior, with built-in speed filters to prevent false positives while driving.

---

# 📊 Telemetry & Visualization

## Forensic Terminal
Structured event-driven forensic logging engine. Designed to avoid noisy spam output while preserving important events.

## Real-Time Signal Graphs
Continuous RSRP visualization with heuristic threat overlays.

## Timing Advance Visualization
Geometric TA graphing for distance-behavior observation.

## Threat Scoring
Dynamic heuristic scoring engine with contextual event correlation.

---

# 🛡️ Infrastructure Verification

The application optionally cross-references cellular towers using:

- **OpenCellID**
- **WiGLE**

to compare observed infrastructure against publicly known crowdsourced tower databases.

*Important:* Public tower databases are incomplete and may contain outdated or missing records. A "not found" result does not imply malicious infrastructure.

---

# 🌐 Privacy & Networking

## Optional SOCKS5 Routing
ICdetection supports optional SOCKS5 proxy routing (Tor / Orbot compatible) for infrastructure verification requests.

## Local-First Design
The application was designed with a strict local-first privacy philosophy. By default:
- no cloud synchronization exists
- no analytics are collected
- no advertising SDKs are included
- no hidden telemetry exists

---

# 💾 Forensic Logging

## SQLite Event Storage
All logs are stored locally on-device.

## CSV Export
Forensic data can be exported for:
- external analysis
- research
- mapping
- reporting
- archival purposes

---

# ⚠️ False Positives

False positives are possible and expected. Legitimate situations that may trigger heuristic alerts include:
- carrier maintenance
- dense urban deployments
- roaming environments
- NSA/5G transitions
- indoor DAS systems
- femtocells
- rural coverage gaps

ICdetection should be interpreted as a forensic auditing tool, a telemetry analyzer, and an anomaly detector—not as a definitive surveillance detector.

---

# 🔐 Security & Threat Model

ICdetection is primarily effective against:
- amateur rogue BTS deployments
- poorly configured SDR towers
- simple fake base stations
- aggressive downgrade attempts
- abnormal cellular behavior

The application is significantly less reliable against:
- sophisticated LTE/5G interception platforms
- carrier-grade rogue infrastructure
- highly advanced state-level systems

This limitation is inherent to Android userland restrictions.

---

# ⚖️ License

Licensed under the **GNU General Public License v3.0 (GPL-3.0)** — see the [LICENSE](LICENSE) file for details.

You are free to use, study, modify, redistribute, and audit the software under GPL terms. Derivative works must remain open-source under GPL-compatible licensing.
