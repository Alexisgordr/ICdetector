A try imsi catcher detection with some features, 

1 - Real-Time Network Monitoring: Continuous 2-second polling of cellular parameters, including Cell ID (CID), MNC, TAC, and signal strength (dBm).
2 - Advanced 5G NR Detection: Accurately distinguishes between 5G NR NSA (Non-Standalone) and SA (Standalone) connections, even when the OS incorrectly reports LTE.
3 - Heuristic IMSI Catcher Analysis: Real-time threat detection using advanced forensics:
Isolated Cell Detection: Alerts when a strong tower has no visible neighbors.
Signal Gap Analysis: Flags anomalous power jumps (>35dB) between the active cell and neighbors.
MCC Inconsistency: Detects spoofed towers broadcasting incorrect Country Codes.
4 - Multi-MNC Spoofing Alert: Identifies suspicious environments where more than 3 distinct operator codes (MNC) are detected simultaneously.
5 - Global Database Verification: Seamless integration with the OpenCellID API to verify the legitimacy of towers against a global registry using your personal API token.
6 - Graphical Signal Dashboard: Real-time visual comparison chart of the active cell versus surrounding neighbors for physical anomaly verification.
7 - Automated Security Countermeasures: Optional automatic "Airplane Mode" fallback when insecure or legacy networks (2G/3G) are detected.
8 - Intelligent Background Service: Adaptive polling system (10-second intervals when screen is off) designed to maintain monitoring while minimizing battery consumption.
9 - Persistent Connection History: Local SQLite database that logs every tower transition with full metadata (Timestamp, Network Type, CID, MNC, TAC, and Verification Status).
10 - Forensic CSV Export: One-click tool to export all recorded history to a CSV file for external audit and detailed signal mapping.
11 - Audio Alert System: Instant acoustic notifications for critical threats and dangerously high power levels (potential proximity to an active Stingray).
12 - Professional Dark Interface: High-contrast, minimalist cybersecurity aesthetic designed for professional auditing and 24/7 technical monitoring.


This app TRY, you can get false positives.

In thi app you can use the api of opencellid for verificated the cells.
