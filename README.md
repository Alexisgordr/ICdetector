<table>
  <tr>
    <td width="60%" valign="top">
      <h3>📱 Forensic User Interface</h3>
      <p>The application features a high-contrast, minimalist dark aesthetic designed for 24/7 technical monitoring and physical anomaly verification.</p>
      <ul>
        <li><strong>Pro-Terminal:</strong> Real-time event-driven forensic logging.</li>
        <li><strong>Telemetry:</strong> Dynamic RSRP signal graphs & Geometric (TA) analysis.</li>
        <li><strong>Security Hub:</strong> Automated 9-heuristic audit cycles.</li>
      </ul>
    </td>
    <td width="40%" align="center">
      <img src="https://github.com/user-attachments/assets/d07675cf-04e2-4d7a-b836-03d1988d8ddb" alt="miniIC Screenshot" width="260" />
    </td>
  </tr>
</table>

# 📡 ICdetection — Anti-IMSI Catcher & Cellular Auditor

A professional-grade, open-source Android tool for real-time mobile network forensics, infrastructure auditing, and heuristical IMSI-catcher (Stingray) detection.

---

## 🛠️ Development Disclosure
This project was developed using an **AI-assisted workflow**. While the lead developer (**Alexis Gómez Rodríguez**) served as the primary architect—defining the security logic, designing the heuristic rules, and performing field testing—AI assistance (Gemini) was utilized for code implementation, API integration, and architectural optimization. This approach ensures high-quality, maintainable, and robust code.

---

## 🔒 Project Overview

**Hardware Compatibility Note:**
This application leverages native `TelephonyCallback` listeners and low-level baseband hooks. 
* **Android 14+:** Provides full-stack security telemetry (Ciphering Status & Identifier Disclosure).
* **Android 12/13:** Operates in Heuristic Audit Mode, which remains highly effective but provides a different signal analysis depth.
* **Pixel/Tensor Devices:** Optimized for Tensor-based hardware, including specific fallback logic for Timing Advance (TA) extraction in 5G NSA modes.

> ⚠️ **Disclaimer:** This tool evaluates cellular environments using advanced heuristics. False positives may occur due to legitimate network topology, topography, or carrier maintenance. Treat results with an auditing mindset.

---

## 🚀 Core Features

### 1. 🔬 Deep Forensics & Heuristic Analysis
The system runs a **9-Rule Security Audit** on every tower handover:
1.  **Isolated Cell Detection:** Identifies towers without neighbor lists (High risk).
2.  **Signal Gap Analysis:** Flags power jumps (>35dB) indicating artificial signal forcing.
3.  **MCC Inconsistency:** Detects spoofed towers broadcasting mismatched Country Codes.
4.  **Multi-MNC Spoofing:** Flags areas with >3 distinct operator codes simultaneously.
5.  **TAC Regional Deviation:** Cross-checks Tracking Area Codes for structural grid mismatches.
6.  **Geometric TA Audit:** Validates physical proximity via Timing Advance (TA) against database records.
7.  **Ghost Neighbors:** Detects "Ghost Cells" (main tower claims neighbors exist, but signal monitoring confirms they are non-existent).
8.  **Ciphering Integrity (L1):** Real-time monitoring of encryption status (detects A5/0 non-ciphered states).
9.  **Anti-Ping-Pong:** Detects repetitive cell reselection loops, a hallmark of tactical interference.

### 2. 📊 Advanced Visual Telemetry
* **Forensic Terminal:** Event-driven log output. Instead of constant noise, the engine provides structured, timestamped reports every 30 seconds or upon tower handovers.
* **Telemetry Graphs:** Real-time RSRP charting with visual threat thresholds.
* **Geometric Map:** Real-time Timing Advance (TA) graphing to visualize physical distance variations from the base station.

### 3. 🛡️ Verification & Privacy
* **Global Database Cross-Reference:** Integrates with OpenCellID and WiGLE to verify if the tower you are connected to matches official carrier registries.
* **Anonymous Routing:** Optional SOCKS5 proxy support (Tor/Orbot) for all API validation requests to prevent location correlation.
* **Automated Countermeasures:** Optional auto-Airplane Mode trigger when insecure legacy networks (2G/3G) are forced on your device.

---

## 💾 Forensic Logging
* **Persistent History:** All events are stored in a local SQLite database, ensuring your forensic history remains 100% private.
* **CSV Export:** One-click functionality to export your forensic logs for external analysis, signal mapping, or security reporting.
* **Zero Telemetry:** The app contains zero analytics, zero trackers, and zero background data collection.

---

## ⚖️ License
This project is licensed under the **GNU General Public License v3.0 (GPL-3.0)**. 
* You are free to modify and redistribute this software.
* Derivative works must also be open-source under the same license terms.

This project is open-source software licensed under the **GNU General Public License v3.0** — see the [LICENSE](LICENSE) file for details.
