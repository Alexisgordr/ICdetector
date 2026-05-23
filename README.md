# 📡 miniIC — Anti-IMSI Catcher & Cellular Auditor

A powerful, open-source Android tool designed for real-time mobile network forensics, infrastructure auditing, and heuristical IMSI-catcher (Stingray) detection.

---

## 🔒 Project Overview

This application continuously monitors your device's cellular baseband connections, analyzing signal signaling parameters and cross-referencing active base stations with global registries to flag anomalies and protect your digital sovereignty.

> ⚠️ **Disclaimer / Warning:** This application uses heuristics to evaluate cellular environments. It is a proof-of-concept tool and **you can get false positives** due to legitimate carrier configurations or network anomalies. Use with an auditing mindset.

---

## 🚀 Core Features

### 1. 📊 Real-Time Monitoring & Telemetry
* **Continuous Network Polling:** Active 2-second polling of cellular parameters, including Cell ID (CID), MNC, TAC, and signal strength (dBm).
* **Advanced 5G NR Detection:** Accurately distinguishes between 5G NR NSA (Non-Standalone) and SA (Standalone) connections, even when the OS incorrectly reports LTE.
* **Graphical Signal Dashboard:** Real-time visual comparison chart of the active cell versus surrounding neighbors for physical anomaly verification.

### 2. 🛡️ Heuristic IMSI Catcher Analysis
* **Isolated Cell Detection:** Alerts when a strong tower has no visible neighbors (classic rogue base station behavior).
* **Signal Gap Analysis:** Flags anomalous power jumps (`>35dB`) between the active cell and neighbors.
* **MCC Inconsistency:** Detects spoofed towers broadcasting incorrect Mobile Country Codes.
* **Multi-MNC Spoofing Alert:** Identifies suspicious environments where more than 3 distinct operator codes (MNC) are detected simultaneously.

### 3. 🌐 Verification & Countermeasures
* **Global Database Verification:** Seamless integration with the **OpenCellID API** to verify the legitimacy of towers against a global registry using your personal API token.
* **Automated Security Countermeasures:** Optional automatic "Airplane Mode" fallback when insecure or legacy networks (2G/3G) are detected.
* **Audio Alert System:** Instant acoustic notifications for critical threats and dangerously high power levels (potential proximity to an active active cell interceptor).

### 4. 💾 Logging, Forensics & Performance
* **Persistent Connection History:** Local SQLite database that logs every tower transition with full metadata (Timestamp, Network Type, CID, MNC, TAC, and Verification Status).
* **Forensic CSV Export:** One-click tool to export all recorded history to a CSV file for external audit and detailed signal mapping.
* **Intelligent Background Service:** Adaptive polling system (10-second intervals when the screen is off) designed to maintain monitoring while minimizing battery consumption.
* **Professional Dark Interface:** High-contrast, minimalist cybersecurity aesthetic designed for professional auditing and 24/7 technical monitoring.

---

## 🛠️ Getting Started & Configuration

### OpenCellID Integration
To unlock full tower verification capabilities, this app allows you to query the OpenCellID database in real-time:
1. Get your free or commercial personal API token at [OpenCellID.org](https://opencellid.org/).
2. Paste your token into the app settings menu.
3. The app will automatically cross-check the active cell's geographic parameters to ensure it matches the official registry.
