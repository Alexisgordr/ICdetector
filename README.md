<table>
  <tr>
    <td width="60%" valign="top">
      <h3>📱 Forensic User Interface</h3>
      <p>
        The application features a high-contrast, minimalist dark interface
        designed for continuous technical monitoring, field auditing,
        and cellular anomaly verification.
      </p>
      <ul>
        <li><strong>Forensic Terminal:</strong> Real-time event-driven logging engine.</li>
        <li><strong>Telemetry:</strong> Dynamic RSRP signal graphs and Timing Advance (TA) analysis.</li>
        <li><strong>Security Hub:</strong> Automated multi-heuristic audit engine.</li>
      </ul>
    </td>
    <td width="40%" align="center">
      <img src="https://github.com/user-attachments/assets/d07675cf-04e2-4d7a-b836-03d1988d8ddb" alt="ICdetection Screenshot" width="260" />
    </td>
  </tr>
</table>

# 📡 ICdetection — Cellular Security Auditor & IMSI-Catcher Heuristic Detector

An advanced open-source Android tool for real-time mobile network analysis, cellular infrastructure auditing, and heuristic-based IMSI-catcher (Stingray) anomaly detection.

---

# 🛠️ Development Disclosure

This project was developed using an AI-assisted workflow.

While the lead developer (**Alexis Gómez Rodríguez**) served as the primary architect — defining the security logic, designing heuristic rules, validating network behavior, and performing field testing — AI assistance (Gemini) was utilized for code implementation support, API integration, debugging assistance, and architectural optimization.

The project remains fully open-source, manually reviewed, and field-tested.

---

# 🔒 Project Overview

## Hardware & Android Compatibility

This application leverages Android's native `TelephonyCallback` framework and advanced telephony telemetry exposed by the operating system.

### Platform Support

* **Android 14+**
  * Enhanced security telemetry support.
  * Ciphering and identifier-disclosure monitoring when exposed by the device firmware and modem stack.

* **Android 12 / 13**
  * Full heuristic analysis mode.
  * Reduced low-level security visibility due to Android framework limitations.

* **Pixel / Tensor Devices**
  * Optimized fallback logic for Timing Advance (TA) extraction.
  * Improved NSA/5G telemetry handling.

---

# ⚠️ Technical Disclaimer

This application performs heuristic and probabilistic analysis of cellular environments.

Results are generated through behavioral anomaly detection and infrastructure correlation techniques. Legitimate carrier operations, roaming conditions, topology, maintenance activity, dense urban deployments, or OEM firmware differences may produce false positives.

This tool should be treated as a forensic auditing instrument — not as a definitive proof of interception.

---

# ⚠️ Technical Limitations

Due to Android security restrictions and vendor modem implementations, third-party applications cannot access the full cellular protocol stack.

Limitations include:

* No direct access to full RRC/NAS signaling.
* Limited visibility into baseband-level operations.
* Ciphering telemetry availability depends heavily on OEM firmware.
* Timing Advance (TA) reporting is inconsistent across vendors.
* Modern LTE/5G IMSI-catchers may emulate legitimate carrier parameters.

Detection reliability varies depending on:
* Android version
* Device manufacturer
* Modem firmware
* Carrier infrastructure
* Regional deployment topology

---

# 🚀 Core Features

# 1. 🔬 Multi-Heuristic Security Analysis

The engine performs a continuous **9-Rule Security Audit** during cellular handovers and network transitions.

### Included Heuristics

1. **Isolated Cell Detection**
   * Detects towers operating without valid neighboring cells.

2. **Signal Gap Analysis**
   * Flags abnormal signal dominance (>35dB delta) commonly associated with forced camping behavior.

3. **MCC Consistency Verification**
   * Detects mismatched Mobile Country Codes across neighboring infrastructure.

4. **Multi-MNC Environment Analysis**
   * Flags unusually dense operator-code environments.

5. **TAC Regional Deviation**
   * Validates Tracking Area consistency against neighboring infrastructure.

6. **Geometric Timing Advance Audit**
   * Correlates physical distance estimation using Timing Advance (TA) telemetry.

7. **Ghost Neighbor Detection**
   * Detects invalid or non-coherent neighbor cell behavior.

8. **Ciphering Integrity Monitoring**
   * Attempts to detect non-ciphered cellular states when exposed by the device firmware.

9. **Anti Ping-Pong Analysis**
   * Detects aggressive cell reselection loops commonly associated with anomalous network behavior.

---

# 2. 📊 Advanced Visual Telemetry

### Forensic Terminal
Structured real-time event logging with timestamped forensic reports.

### Signal Telemetry
Real-time RSRP visualization with threat-level monitoring.

### Geometric Analysis
Timing Advance (TA) graphing for physical-distance behavior analysis.

### Threat Visualization
Security scoring and heuristic audit reporting in real time.

---

# 3. 🛡️ Verification & Privacy

### Infrastructure Verification
Cross-references towers against:
* OpenCellID
* WiGLE

to validate known public infrastructure records.

### Anonymous API Routing
Optional SOCKS5 proxy support (Tor / Orbot compatible) for anonymous validation requests.

### Automated Countermeasures
Optional mitigation behavior when insecure legacy networks (2G/3G) are force-triggered.

---

# 💾 Forensic Logging

### Persistent SQLite History
All events are stored locally on-device.

No cloud synchronization exists.

### CSV Export
Export forensic telemetry and historical logs for:
* external analysis
* mapping
* reporting
* research

### Zero Telemetry
The application contains:
* zero analytics
* zero trackers
* zero advertising SDKs
* zero background telemetry collection

---

# 🔐 Privacy Philosophy

ICdetection was designed with an offline-first security philosophy.

The application:
* does not require account registration
* does not upload forensic logs
* does not collect personal identifiers
* does not perform hidden telemetry collection

All analysis remains local unless the user explicitly enables external verification APIs.

---

# ⚖️ License

This project is licensed under the **GNU General Public License v3.0 (GPL-3.0)**.

You are free to:
* use
* modify
* redistribute
* audit

this software under GPL-3.0 terms.

Derivative works must also remain open-source under the same license.
```
er the **GNU General Public License v3.0** — see the [LICENSE](LICENSE) file for details.
