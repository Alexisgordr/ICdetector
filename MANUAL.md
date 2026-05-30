# 📖 ICdetection Field Manual

This manual provides operational guidelines for using ICdetection. This tool is designed for network auditing and cellular threat detection. Understanding the data is as important as the code itself.

---

## 🛡️ Operational Philosophy

ICdetection is a passive, local-first auditing tool. It does not perform active attacks. It listens to the radio broadcast signals of your environment and cross-references them with global databases.

Most alerts are false positives caused by legitimate network conditions. This manual is intended for situations where alerts are **persistent, confirmed across multiple cycles, and geographically consistent**.

---

## ⚠️ Important: False Positives

Cellular networks are complex. Terrain, building materials, and carrier maintenance can trigger alerts.

- **If you see a warning:** Do not panic. Observe the signal stability.
- **If you see persistent alerts:** Take note of the time, location, and the Suspicious Reason provided by the app.

---

## 🔍 How to Interpret the Data

### 1. Verification Status

- ✅ **VERIFIED:** The base station (CellID) is registered in the OpenCellID/WiGLE database and matches the expected network parameters. High confidence.
- ⏳ **PENDING:** The app is querying the databases or waiting for a GPS fix to validate coordinates. This resolves automatically.
- ❌ **NOT FOUND:** The tower is not in the database. This is common in very remote areas or brand-new 5G installations. Exercise caution but do not assume malicious intent.
- ⚠️ **ERROR API:** Connection issue. Check your API tokens and Proxy settings in the menu.

---

### 2. Threat Confirmation — Temporal Confidence Decay

Alerts require **3 consecutive analysis cycles** before triggering a confirmed threat. You may see `[1/3 cycles confirming]` or `[2/3 cycles confirming]` in the terminal — this means the app is building confidence before alarming.

Transient anomalies that resolve within one cycle are logged but **do not trigger alerts**. This significantly reduces false positive fatigue in dynamic RF environments.

---

### 3. Threat Analysis — The Red Flags

When the status turns **SISTEMA EN COMPROMISO**, check the Suspicious Reason field:

- **"Celda aislada (sin vecinos)":** A tower broadcasting strongly but failing to report neighbors is a classic IMSI-Catcher trait. Common in amateur rogue BTS deployments.

- **"Salto de potencia anómalo (>35dB)":** An attacker might be boosting power to force your phone to latch onto their signal over legitimate ones.

- **"TA vs Distancia GPS":** If the tower claims to be 5km away but the Timing Advance (TA) indicates it is 50 meters away, this is likely a spoofed tower. *Note: TA telemetry is highly device-dependent and may not be available on all hardware.*

- **"Consistencia Geográfica (H11)":** The same Cell ID has been detected from physically inconsistent locations over time. This is a strong indicator of a **mobile rogue base station** cloning a legitimate tower. Unlike other heuristics, this cannot be defeated by RF parameter spoofing — it relies purely on physics and your local GPS history. This is the most reliable heuristic in the engine.

- **"Conexión celular NO CIFRADA":** CRITICAL. The network has forced a downgrade to plain-text communication. *Note: This check requires privileged system access or root. On standard devices without root, this heuristic is unavailable and shown as N/A in the interface.*

---

## 🏃 Emergency Countermeasures

If you detect a credible, persistent threat:

1. **Immediate Fallback:** The app will attempt to trigger Airplane Mode automatically. If it fails, manually toggle Airplane Mode from the system status bar. This is the most reliable countermeasure.

2. **Physical Displacement:** Move away from the detected signal source. Depending on equipment power level, moving 200-500 meters may disconnect your device from the rogue base station. Professional-grade IMSI-catchers can cover up to 2km — Airplane Mode is more reliable than distance alone.

3. **Data Persistence:** If the app generates a Security Alert, wait for the app to log it to the SQLite database. Later, go to the History tab and use the **Export CSV** function to save the forensic evidence.

---

## ⚙️ Best Practices for Auditing (OPSEC)

- **Use a Proxy:** If you are auditing in an area where you suspect you are being monitored, ensure the Tor (Orbot) proxy is enabled in the settings. This prevents the API (OpenCellID/WiGLE) from correlating your specific public IP with the CellIDs you are auditing.

- **Monitor with Screen Off:** The app is designed to continue monitoring when the screen is off (polling every 10 seconds). You can keep the phone in your pocket and rely on the Audio Alert System.

- **Learn the tones:** The app uses specific tones for different threat levels. A confirmed threat sounds differently from a high-signal warning. Learn to distinguish them.

- **Let H11 learn your environment:** The Geographic Consistency heuristic becomes more accurate over time as it builds a local GPS history of known cell towers. The more you use the app in your regular areas, the more precisely it can detect anomalies.

---

## 📋 Forensic Workflow

1. **Preparation:** Open the app and ensure you have valid API tokens for WiGLE and OpenCellID configured in Settings.

2. **During Audit:** Keep the app running in the background. Ensure Airplane Mode fallback is enabled in Settings. The GPS indicator shows whether location data is being recorded.

3. **Post-Audit:** If any alerts were logged, export the history to CSV from the History tab.

4. **Reporting:** Use the CSV data to identify patterns — specific times or locations where NOT FOUND or suspicious cells repeatedly appear. Pay special attention to the `Lat`, `Lon`, `PCI` and `ARFCN` columns for RF fingerprinting analysis.

---

## 🔬 Technical Limitations

ICdetection operates entirely in Android userland without root access. This means:

- **Cipher state** cannot be read without privileged system permissions
- **Timing Advance** values may be unavailable on some hardware (returns 0)
- **Modem-level signaling** (RRC, NAS) is not accessible
- **Legal interception** at the carrier level cannot be detected — it occurs inside the operator's infrastructure, not at the radio layer

These are fundamental Android limitations, not application bugs.

---

*Final Note: This tool is for educational and defensive purposes. Always respect local laws regarding radio frequency monitoring.*

**Stay vigilant. Stay encrypted.**

*Developed by Alexis Gómez Rodríguez*
