# 📖 ICdetection Field Manual (v2.0)

This manual provides operational guidelines for using ICdetection. This tool is designed for network auditing and cellular anomaly analysis. Understanding the data is as important as the code itself.

---

## 🛡️ Operational Philosophy

ICdetection is a passive, local-first auditing tool. It does not perform active attacks. It listens to the radio broadcast signals of your environment and cross-references them with global databases.

Most alerts are false positives caused by legitimate network conditions. This manual is intended for situations where alerts are **persistent, confirmed across multiple cycles, and geographically consistent**.

ICdetection does not aim for instant verdicts. Its strength is **historical intelligence**: it learns your normal RF environment over time and flags what deviates from it. The longer you use it in your regular areas, the more accurate it becomes. It is a complement to prevention (end-to-end encryption, encryption-required settings), not a replacement for it.

---

## ⚠️ Important: False Positives

Cellular networks are complex. Terrain, building materials, and carrier maintenance can trigger alerts.

- **If you see a warning:** Do not panic. Observe the signal stability.
- **If you see persistent alerts:** Take note of the time, location, and the Suspicious Reason provided by the app.

The engine is deliberately tuned to favour fewer false positives over aggressive alerting. Several layers (percentiles, cell reputation, multi-cycle confirmation) exist specifically to keep it quiet unless something is genuinely off.

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

When the status turns **SISTEMA EN COMPROMISO**, check the Suspicious Reason field. The strongest, hardest-to-fool heuristics are the ones anchored to physics and your own history (H11, H13, H14, H15):

- **"Celda aislada (sin vecinos)":** A tower broadcasting strongly while showing no coherent neighbors is a known red flag in amateur rogue BTS scenarios, but it is not proof by itself. Rural coverage, indoor deployments and unusual radio conditions can also produce this pattern.

- **"Salto de potencia anómalo (>35dB)":** An attacker might be boosting power to force your phone to latch onto their signal over legitimate ones.

- **"TA vs Distancia GPS":** If the tower claims to be 5km away but the Timing Advance (TA) indicates it is 50 meters away, this is likely a spoofed tower. *Note: TA telemetry is highly device-dependent and may not be available on all hardware.*

- **"Consistencia Geográfica (H11)":** The same Cell ID has been detected from physically inconsistent locations over time. This is one of the strongest contextual indicators in the engine because it relies on physics and local GPS history rather than public tower databases. It can be consistent with a mobile rogue base station cloning a legitimate tower, but it still requires careful interpretation.

- **"Potencia vs Histórico (Baseline + huella RSRQ/SINR) (H13)":** The app learns each cell's typical signal level (RSRP) at a given location from your own history, and flags readings that are anomalously **strong** versus that baseline — a transmitter impersonating a normally-weaker cell will appear far closer than its history allows. With enough samples, an anomaly must also exceed the cell's own **99th percentile**, making the check robust against occasional legitimate spikes. It additionally learns each cell's **RSRQ/SINR signature** and flags a signal quality incoherent with that fingerprint. Fully offline; needs a warm-up period and stays silent until it has data.

- **"Band Downgrade (H14)":** Detects a sudden, forced shift from a high-frequency capacity band to a low-frequency sub-GHz band. Attackers push devices to lower frequencies to extend coverage and penetration. The check uses real 3GPP band physics (via EARFCN) and distinguishes a forced downgrade from natural signal degradation (e.g. entering a garage), reducing false positives.

- **"RF Identity / PCI (H15)":** A legitimate cell keeps its physical-layer identity (PCI) fixed for life. This flags a single Cell ID seen alternating between distinct PCI values that persist recently — a sign of a clone reusing a legitimate Cell ID with a different radio fingerprint. It can fire while you are stationary (unlike H11, which needs movement). It deliberately uses **PCI only, not ARFCN**, because field testing showed ARFCN fluctuates legitimately due to carrier aggregation.

- **"Cifrado de enlace (Hardware)":** N/A on standard v2.0 installs unless the operating system exposes a supported ciphering-state signal. ICdetection v2.0 does not claim direct null-cipher or IMSI-disclosure detection on normal no-root Android installs.

> **Note on the scoring engine:** these heuristics are not simply added up. A Bayesian engine combines them, grouping correlated signals so they cannot double-count and inflate the score, and softening the noisy/environment-sensitive ones on cells with a long, clean local history (cell reputation). The probability is capped at 95% — on Android userland, certainty is never claimed.

---

## 🏃 Emergency Countermeasures

If you detect a credible, persistent threat:

1. **Immediate Fallback — Airplane Mode:** Manually toggle Airplane Mode from the system status bar. This is the most reliable countermeasure. *Note: the app can only toggle Airplane Mode automatically if it has been granted the privileged `WRITE_SECURE_SETTINGS` permission via ADB (`adb shell pm grant com.alexisgordr.icdetector android.permission.WRITE_SECURE_SETTINGS`). On a normal install this permission is **not** granted, so the automatic fallback will not fire and you should toggle Airplane Mode yourself.*

2. **Physical Displacement:** Move away from the detected signal source. Depending on equipment power level, moving 200-500 meters may disconnect your device from the rogue base station. Professional-grade IMSI-catchers can cover up to 2km — Airplane Mode is more reliable than distance alone.

3. **Data Persistence:** If the app generates a Security Alert, wait for the app to log it to the SQLite database. Later, go to the History tab and use the **Export CSV** function to save the forensic evidence.

---

## ⚙️ Best Practices for Auditing (OPSEC)

- **Use a Proxy:** If you are auditing in an area where you suspect you are being monitored, ensure the Tor (Orbot) proxy is enabled in the settings. This prevents the API (OpenCellID/WiGLE) from correlating your specific public IP with the CellIDs you are auditing.

- **Monitor with Screen Off:** The app continues monitoring with the screen off (polling roughly every 10 seconds). You can keep the phone in your pocket and rely on the Audio Alert System. *Note on GPS in repose: with the screen off and the device stationary, Android Doze restricts the GPS, so fresh coordinates may not be recorded for every observation. This is an OS limitation, not a bug — and it matters little when stationary, since your position has not changed and H15 (PCI) needs no GPS.*

- **Learn the tones:** The app uses specific tones for different threat levels. A confirmed threat sounds differently from a high-signal warning. Learn to distinguish them.

- **Let the history learn your environment:** H11, H13, the cell reputation and the RSRQ/SINR fingerprint all become more accurate over time as they build local history. The more you use the app in your regular areas, the more precisely it detects anomalies — and the quieter it stays on cells it has learned to trust. Expect a warm-up period of days before the historical heuristics are fully effective; the RF fingerprint in particular stays dormant until weeks of data accumulate, by design.

---

## 📋 Forensic Workflow

1. **Preparation:** Open the app and (optionally) configure valid API tokens for WiGLE and OpenCellID in Settings. The app works offline without them; the tokens only enable external tower cross-referencing.

2. **During Audit:** Keep the app running in the background. The GPS indicator shows whether location data is being recorded.

3. **Post-Audit:** Export the history to CSV from the History tab.

4. **Reporting:** Use the CSV data to identify patterns — specific times or locations where NOT FOUND or suspicious cells repeatedly appear. The export includes `Timestamp, NetType, CID, MNC, TAC, MCC, DBM, Verified, SecurityScore, FailedHeuristics, Lat, Lon, PCI, ARFCN, RSRQ, SINR`. Pay special attention to `Lat`, `Lon`, `PCI`, `ARFCN`, `RSRQ` and `SINR` for RF fingerprinting analysis. Fields are CSV-escaped (RFC 4180), so the file imports cleanly into spreadsheets and analysis tools.

---

## 🔬 Technical Limitations

ICdetection operates entirely in Android userland without root access. This means:

- **Direct null-cipher / IMSI-disclosure detection** is not implemented on standard v2.0 installs and requires privileged OS APIs/permissions that normal no-root apps do not receive
- **Timing Advance** values may be unavailable on some hardware (returns 0)
- **Modem-level signaling** (RRC, NAS) is not accessible
- **GPS in deep repose** (screen off + stationary) is throttled by Android Doze
- **Legal interception** at the carrier level cannot be detected — it occurs inside the operator's infrastructure, not at the radio layer

ICdetection is most useful against amateur rogue base stations, poorly configured setups, some semi-professional scenarios, and abnormal network behaviour visible through Android APIs. It is **not** able to reliably detect a well-configured professional IMSI-catcher in real time — that requires baseband/modem access this tool does not have. These are fundamental Android limitations, not application bugs.

---

*Final Note: This tool is for educational and defensive purposes. Always respect local laws regarding radio frequency monitoring.*

**Stay vigilant. Stay encrypted.**

*Developed by Alexis Gómez Rodríguez*
