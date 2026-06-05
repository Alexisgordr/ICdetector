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
           <img src="https://github.com/user-attachments/assets/81bf1ed6-94c4-462e-8d13-828f7754f532"
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

## On Transparency and AI-Assisted Development

This project is built on honesty — about its detection limits, and about how it was made.

The system design is mine: the decision to use a probabilistic engine instead of rigid rules,
which heuristics to include or reject, how they are weighted and correlated, what counts as a
false positive in real-world data, and the overall direction of the project. Many of those
decisions were made by testing the app in the field, finding real false positives, and reasoning
about why they happened.

The code itself was written by AI tools — I do not program in Kotlin. AI was also used to
explore and refine several heuristic ideas. What I bring is the design and the judgment: I
direct the project, decide what it should do and why, choose which ideas survive and which
get discarded, and validate the results against real-world data. Every heuristic and every
detection decision in this project I can explain and defend, because I understood the *why*
behind each one as it was built — even where I could not have written the implementation myself.

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

## 12. Signal Baseline Anomaly (H12)
Learns each cell's typical signal level (dBm) at a given location from the device's own historical observations, then flags readings that are anomalously **strong** compared to that learned baseline. A nearby rogue transmitter impersonating a cell that is normally weaker at that spot will appear far stronger than its own history — a physical inconsistency that falsified radio parameters cannot hide, since the attacker cannot change how close their transmitter physically is to the device.

Like H11, it is fully offline, relies on no external database, and needs no labelled data: it only learns from what the device has already observed. It is grouped with the RF-dominance heuristics in the Bayesian engine, so it cannot double-count with Signal Dominance Analysis and inflate the score.

Its scope is limited to cells with enough nearby historical samples, so a warm-up period is required: with no prior baseline for a location it stays silent. Only the "stronger than usual" direction is treated as suspicious, since weaker-than-usual readings are commonly caused by obstruction or distance rather than an attack.

## 13. Intra-LTE Band Downgrade Analysis (H13)
Detects aggressive and suspicious shifts from high-frequency capacity bands (e.g., Band 7) to low-frequency sub-GHz bands (e.g., Band 20). Attackers frequently attempt to push target devices into lower frequencies to maximize signal penetration and extend their sweeping area.

This heuristic mathematically differentiates between a sudden, forced band downgrade (highly indicative of a Rogue BTS or IMSI-catcher attack) and a natural, progressive signal degradation (such as entering a basement or parking garage). By evaluating physical band properties through EARFCN mapping rather than abstract channel numbers, it accurately identifies malicious physical-layer manipulations while heavily reducing false positives during normal mobility.

## 14. RF Identity Stability Analysis (H14)
A legitimate cell keeps its physical-layer identity — its PCI — fixed for its entire lifetime. This heuristic inspects the device's own historical record for a given Cell ID (CID + MNC + TAC + MCC) and flags it when that single identity has been observed alternating between multiple distinct PCI values that persist recently. Such behavior is a strong indicator of a clone reusing a legitimate Cell ID with a different physical-layer identity.

Crucially, it complements H11: while Geographic Consistency Analysis requires the user to move (it compares the same cell seen from incompatible positions), RF Identity Stability can fire while the user is completely stationary — covering the scenario where a device is disconnected and reconnected to the same Cell ID with a different radio fingerprint. Like H11 and the baseline heuristics, it is fully offline and relies solely on the device's own observation history, with no external database.

It is engineered to be conservative against false positives. A PCI is only treated as a genuine alternate identity when it (a) appears at least twice, (b) represents a meaningful share of observations, and (c) is still present within a recent time window — distinguishing a benign permanent reconfiguration (old value only in older records) from an active, currently-flapping clone. Field testing showed that ARFCN, unlike PCI, is unreliable for this purpose: carrier aggregation causes the serving cell to occasionally report a neighbor carrier's ARFCN, so this heuristic deliberately uses PCI only — the true, stable physical-layer cell identity. In the Bayesian engine it is grouped with H11 as correlated RF-identity evidence, so the two cannot double-count and inflate the score.


## Statistical and Historical Hardening

Beyond individual heuristics, the engine refines the *quality* of its existing evidence using the device's own accumulated history. These layers are fully offline and conservative by design.

**Percentile-based baseline (H13).** In addition to mean and standard deviation, the per-cell power baseline now stores the 95th and 99th percentiles of historical RSRP. With sufficient samples, an anomaly must exceed the cell's own 99th percentile, making the heuristic robust against non-normal distributions and occasional legitimate strong readings — it strictly reduces false positives without ever loosening detection.

**Cell reputation (trust score).** Each cell earns a trust score (0–100) derived purely from its own history (volume of observations, spread across distinct days, and the proportion of clean past scores). A cell observed cleanly many times over many days is almost certainly legitimate. This trust is used *only* to dampen the weight of the noisy, instantaneous heuristics (isolated cell, power jump, ghost neighbors) on well-established cells — it never increases suspicion and never affects the physics-anchored heuristics (MCC, ciphering, geographic consistency, RF identity). It is the same likelihood-ratio softening mechanism as the context-adaptive engine, applied to reputation instead of environment density.

**RF fingerprint (RSRQ/SINR).** Complementing the RSRP baseline, the engine learns each cell's signal-quality signature (mean and deviation of RSRQ and SINR). A transmitter impersonating a known cell may present a signal quality incoherent with that cell's historical fingerprint. Because RSRQ and SINR are intrinsically noisier than RSRP, this check is deliberately strict: it requires many samples and a large deviation in *both* metrics simultaneously, and reports through the baseline heuristic so it cannot double-count. As these measurements are only collected from this version onward, the fingerprint remains dormant until weeks of history accumulate — by design, it cannot produce early false positives.


## Temporal Confidence Decay
Anomalies must persist across 3 consecutive analysis cycles before triggering a confirmed threat alert. Transient heuristic failures are logged but do not raise alarms, significantly reducing false positive alert fatigue in dynamic RF environments.

---

# 🔋 Battery Optimization & Exceptions

ICdetection requires unoptimized battery settings (**"Battery optimization: Don't optimize"**) to ensure reliable 24/7 detection.

The application runs a **continuous foreground service** that scans cells, registers telephony callbacks, and manages location fixes. Android's battery optimization (Doze and App Standby) is designed to throttle or kill this type of behavior when the phone is idle with the screen off—exactly the scenario where an IMSI-catcher might operate.

To prevent detection gaps, the user must **manually** grant an optimization exception:

**Settings → Apps → ICdetection → Battery → Battery optimization → Don't optimize**

The app attempts to detect if it is optimized and guides the user to the appropriate settings screen. However, the **final exception grant is the user's responsibility**—it cannot be forced programmatically.

---

# 📈 Statistical Baseline Learning

ICdetection employs a **statistical baseline learning** system to:
- profile normal signal behavior
- detect anomalous deviations
- identify statistically significant outliers

The system learns each cell's typical signal range (dBm) at a given physical location from the device's own historical observations. This allows it to:
- flag anomalously strong readings (potential impersonation attacks)
- reduce false positives from natural signal variation
- adapt to the user's specific RF environment over time

The learning is fully **unsupervised**: no labelled data or external databases are used. The system only learns from the device's own real-world telemetry.

A **warm-up period** (typically a few days of varied usage) is required to build a stable baseline. During this period, some heuristics may remain silent to avoid premature alerting.

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
