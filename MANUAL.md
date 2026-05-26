📖 ICdetection Field Manual
This manual provides operational guidelines for using ICdetection. This tool is designed for network auditing and cellular threat detection. Understanding the data is as important as the code itself.

🛡️ Operational Philosophy
ICdetection is a passive, local-first auditing tool. It does not perform active attacks. It listens to the radio broadcast signals of your environment and cross-references them with global databases.

⚠️ Important: False Positives
Cellular networks are complex. Terrain, building materials, and carrier maintenance can trigger alerts.

If you see a warning: Do not panic. Observe the signal stability.

If you see persistent alerts: Take note of the time, location, and the Suspicious Reason provided by the app.

🔍 How to Interpret the Data
1. Verification Status
✅ VERIFIED: The base station (CellID) is registered in the OpenCellID/WiGLE database and matches the expected network parameters. High confidence.

⏳ PENDING: The app is currently querying the databases. Please wait 5-10 seconds.

❌ NOT FOUND: The tower is not in the database. This is common in very remote areas or brand-new 5G installations. Exercise caution.

⚠️ ERROR API: Connection issue. Check your API tokens and Proxy settings in the menu.

2. Threat Analysis (The Red Flags)
When the status turns "SISTEMA EN COMPROMISO" (System Compromised), check the Suspicious Reason field:

"Celda aislada (sin vecinos)": A tower broadcasting strongly but failing to report neighbors is a classic IMSI-Catcher trait.

"Salto de potencia anómalo (>35dB)": An attacker might be boosting power to force your phone to latch onto their signal over legitimate ones.

"TA vs Distancia GPS": If the tower claims to be 5km away but the Timing Advance (TA) indicates it is 50 meters away, this is likely a spoofed tower.

"Conexión celular NO CIFRADA": CRITICAL. The network has forced a downgrade to plain-text. Move away from this location immediately.

🏃 Emergency Countermeasures
If you detect a credible threat:

Immediate Fallback: The app will attempt to trigger Airplane Mode automatically. If it fails, manually toggle Airplane Mode from the system status bar.

Physical Displacement: Move away from the detected signal source. IMSI-Catchers are range-limited; moving 50-100 meters can often disconnect your device from the rogue base station.

Data Persistence: If the app generates a Security Alert, wait for the app to log it to the SQLite database. Later, go to the History tab and use the Export CSV function to save the forensic evidence.

⚙️ Best Practices for Auditing (OPSEC)
Use a Proxy: If you are auditing in an area where you suspect you are being monitored, ensure the Tor (Orbot) proxy is enabled in the settings. This prevents the API (OpenCellID/WiGLE) from correlating your specific public IP with the CellIDs you are auditing.

Monitor with Screen Off: The app is designed to continue monitoring when the screen is off (polling every 10 seconds). You can keep the phone in your pocket and rely on the Audio Alert System.

Don't ignore the tone: The app uses specific tones for different threats. Learn to distinguish the "High Power Alert" from the "Downgrade Attack" error tone.

📋 Forensic Workflow
Preparation: Open the app and ensure you have valid API tokens for WiGLE and OpenCellID.

During Audit: Keep the app in the background. Ensure "Airplane Mode fallback" is enabled.

Post-Audit: If any alerts were logged, export the history to CSV.

Reporting: Use the CSV data to identify patterns (e.g., specific times or locations where "Not Found" or "Suspicious" cells repeatedly appear).

Final Note: This tool is for educational and defensive purposes. Always respect local laws regarding radio frequency monitoring.

Stay vigilant. Stay encrypted.

Developed by Alexis Gómez Rodríguez
