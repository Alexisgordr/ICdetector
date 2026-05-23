
<table>
  <tr>
    <td width="60%" valign="top">
      <h3>📱 Forensic User Interface</h3>
      <p>The application features a high-contrast, minimalist dark aesthetic designed for 24/7 technical monitoring and physical anomaly verification.</p>
      <ul>
        <li>Real-time visual graphs of neighboring cells.</li>
        <li>Instant security threat level status color-coding.</li>
        <li>Quick access to automated countermeasures and SQLite connection logs.</li>
      </ul>
    </td>
    <td width="40%" align="center">
      <img src="https://github.com/user-attachments/assets/d05aa704-e1bf-49f2-9c10-7257f04151d6" alt="miniIC Screenshot" width="260" />
    </td>
  </tr>
</table>



# 📡 ICdetection — Anti-IMSI Catcher & Cellular Auditor

A powerful, open-source Android tool designed for real-time mobile network forensics, infrastructure auditing, and heuristical IMSI-catcher (Stingray) detection.

---

## 🔒 Project Overview

This application continuously monitors your device's cellular baseband connections, analyzing radio signaling parameters and cross-referencing active base stations with global registries to flag anomalies and protect your digital sovereignty.

> ⚠️ **Disclaimer / Warning:** This application uses advanced heuristics to evaluate cellular environments. It is a proof-of-concept tool and **you can get false positives** due to legitimate carrier configurations, terrain topography, or network maintenance. Use with an auditing mindset.

---

## 🚀 Core Features

### 1. 📊 Real-Time Monitoring & Telemetry
1. **Continuous Network Polling:** Active 2-second polling of cellular parameters, including Cell ID (CID), MNC, TAC, and signal strength (dBm).
2. **Advanced 5G NR Detection:** Accurately distinguishes between 5G NR NSA (Non-Standalone) and SA (Standalone) connections, even when the OS incorrectly reports LTE.
3. **Graphical Signal Dashboard:** Real-time visual comparison chart of the active cell versus surrounding neighbors for physical anomaly verification.

### 2. 🛡️ Heuristic IMSI Catcher Analysis
4. **Isolated Cell Detection:** Alerts when a strong tower has no visible neighbors (classic rogue base station behavior designed to trap devices).
5. **Signal Gap Analysis:** Flags anomalous power jumps (`>35dB`) between the active cell and its reported neighbors.
6. **MCC Inconsistency:** Detects spoofed towers broadcasting incorrect Mobile Country Codes (MCC).
7. **Multi-MNC Spoofing Alert:** Identifies suspicious environments where more than 3 distinct operator codes (MNC) are detected simultaneously.
8. **TAC Regional Deviation Audit:** Cross-checks the Tracking Area Code (TAC) of the active cell against its adjacent neighbors, raising flags if the main tower broadcasts a generic or structural mismatch compared to the localized network grid.

### 3. 🔬 Advanced Baseband Heuristics & Deep Scanning
9. **Downgrade Attack Prevention (Anti-Jamming):** Tracks network state transitions and signal history. If the device drops abruptly from 4G/5G to legacy 2G/3G networks while having an excellent prior signal (`>-85 dBm`), the system flags it as an active selective jamming or forced downgrade attack.
10. **Timing Advance (TA) Physical Distance Audit:** Extracts the Timing Advance parameter directly from the cellular signal layer (available in LTE and GSM bands). If the TA reports an extremely low value (0 or 1, indicating physical proximity under 150 meters) but the base station fails the OpenCellID global database verification, it elevates the threat level to a *Physical Proximity Rogue Cell Alert*.
11. **Neighboring Null Check (Ghost Cell Detection):** Performs deep parsing of the neighbor cell lists broadcasted by the active tower. If the main base station claims to have active neighboring towers but persistent spectrum monitoring returns zero signal or null structures for those specific channels while the device has strong coverage, the app flags a "Ghost Neighbors Signature"—a classic behavior of tactical IMSI-catchers forcing cell entrapment.
12. **Cell Reselection Loop Detection (Anti-Ping-Pong):** Tracks cellular transition speed and frequency. If the baseband chip is caught in a rapid, repetitive swap loop between identical cell IDs within a tight temporal window (e.g., 3 swaps in 10 seconds), it signals tactical interference or an ongoing cell-entrapment race condition.
13. **Real-Time Ciphering Status Monitoring (Android 14+):** Leverages the native `CipheringStatusListener` to audit network encryption parameters in real time. The system triggers an instant emergency alert if the cellular link drops encryption entirely (falling back to a vulnerable A5/0 plain-text transmitting state), which is a clear signature of tactical over-the-air interception.
14. **Cellular Identifier Disclosure Protection (Android 14+):** Integrates the native `CellularIdentifierDisclosureListener` to track raw hardware signaling requests. It immediately flags and logs any unencrypted or suspicious transmission demands where a rogue base station forces the device to disclose sensitive hardware identity flags (such as the IMSI or IMEI numbers) outside standard carrier protocols.

### 4. 🌐 Verification & Countermeasures
15. **Global Database Verification:** Seamless integration with the **OpenCellID API** to verify the legitimacy of towers against a global registry using your personal API token.
16. **Automated Security Countermeasures:** Optional automatic "Airplane Mode" fallback triggered instantly when insecure, legacy networks (2G/3G) or active downgrade attacks are detected.
17. **Audio Alert System:** Instant acoustic notifications for critical threats and dangerously high power levels (potential physical proximity to an active cell interceptor / Stingray).
18. **Anonymous Tor Routing (SOCKS5 Proxy Support):** Features optional integration with local SOCKS5 proxy architectures (e.g., Orbot running on port 9050). This completely detaches your active public IP address from outbound OpenCellID API validation requests, preventing geolocation correlation by third-party network infrastructure.

### 5. 💾 Logging, Forensics & Performance
19. **Persistent Connection History:** Local SQLite database that logs every tower transition with full metadata (Timestamp, Network Type, CID, MNC, TAC, and Verification Status) keeping your forensic data 100% private.
20. **Thread-Safe Memory Caching:** Utilizes a highly concurrent memory caching layer (`ConcurrentHashMap`) to entirely decouple volatile real-time radio streams from persistent database pipelines, avoiding database locks or concurrent transaction race conditions.
21. **Asynchronous Non-Blocking I/O:** Powered by native Kotlin Coroutines (`Dispatchers.IO`) to process all background SQLite operations completely off the main rendering thread, ensuring a silky-smooth UI with zero frame drops.
22. **Forensic CSV Export:** One-click tool to export all recorded history to a CSV file for external audit, log analysis, and detailed signal mapping.
23. **Intelligent Background Service:** Adaptive polling system designed to maintain monitoring while minimizing battery consumption, safely dropping from a 2-second rate to a conservative 10-second sweep when the device screen is off.
24. **Professional Dark Interface:** High-contrast, minimalist cybersecurity aesthetic designed for professional auditing and 24/7 technical monitoring.

### 6. 🛠️ Event-Driven Low-Level Architecture & Reactivity
25. **Native TelephonyCallback Integration:** Migrated from basic loop polling to the modern Android `TelephonyCallback` event listener ecosystem (Android 12+). The application no longer actively wakes the CPU every 2 seconds; instead, the OS native radio layer architecture signals the application backend within milliseconds of any raw signaling or radio state mutation.
26. **Reactive State-Bound UI Architecture:** Built natively using a strict state-bound lifecycle (`by mutableStateOf`) in MainActivity. This ensures that the Jetpack Compose frontend instantly and seamlessly recomposes the millisecond the background radio service binds, resolving classic first-launch permission synchronization freezes entirely.
27. **Native Multi-SDK TelephonyCallback Architecture:** Migrated from primitive execution polling loops to a highly reactive `TelephonyCallback` ecosystem. The application layer scales dynamically based on the host environment: operating as a solid background monitor on Android 12 and 13, and upgrading automatically to an advanced multi-interface listener on Android 14+. By implementing low-level framework hooks natively, the engine processes critical baseband signaling changes within milliseconds without polling overhead, maintaining a near-zero battery consumption footprint.

---

## 🛠️ Getting Started & Configuration

### OpenCellID Integration
To unlock full tower verification capabilities, this app allows you to query the OpenCellID database in real-time:
1. Get your free or commercial personal API token at [OpenCellID.org](https://opencellid.org/).
2. Paste your token into the app settings menu.
3. The app will automatically cross-check the active cell's geographic parameters to ensure it matches the official carrier registry.

> 💡 **GrapheneOS / Hardened OS Note:** This application strictly requires the **Network** permission to cross-reference cellular infrastructure with the OpenCellID API. Revoking network permissions via the OS sandbox will intentionally cause the application to terminate (Fail-Fast architecture), as real-time global registry validation is a core dependency of its security model.

>  🔒 **Privacy & Network Transparency:** Although this application requires the **Network** permission, it is strictly and exclusively used to perform outbound HTTP queries to the official OpenCellID API for cell verification. This application contains **zero telemetry, zero analytics trackers, and zero background data collection**. All your cellular history, logs, and forensic data are stored 100% locally in your device's SQLite database and never leave your phone. You are fully encouraged to audit the source code to verify this network behavior.

* **No GPS/IP Tracking:** To ensure absolute location privacy, this application strictly avoids using the device's GPS hardware or external IP geolocation APIs. Rogue cell detection relies entirely on low-level radio signaling heuristics and mathematical parameters (like Timing Advance), ensuring the user's physical movements are never recorded or exposed.

---

## ⚖️ License

This project is open-source software licensed under the **GNU General Public License v3.0** — see the [LICENSE](LICENSE) file for details.
