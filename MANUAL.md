# ICdetection Field Manual —

ICdetection is an open-source Android application for passive cellular-network auditing and anomaly analysis. It observes information exposed by Android, compares each observation with the device's local history and, when configured, cross-checks cells against external tower databases.

This manual explains how to operate the application, interpret its results, investigate incidents, and export a forensic case.

> **Important:** ICdetection is an anomaly detector, not a device that can prove the presence of an IMSI catcher. A warning means that the observations deserve examination. It does not identify an attacker or establish intent by itself.

---

## 1. What ICdetection does

ICdetection works passively and locally. It does not transmit radio commands, attack cellular networks, or require root access.

While monitoring, it can:

- Observe the serving cell and cellular information exposed by Android.
- Record MCC, MNC, Cell ID, TAC, PCI, channel, RSRP, RSRQ, SINR, radio technology, and Timing Advance when available.
- Evaluate multiple anomaly heuristics during every analysis cycle.
- Learn historical behaviour for repeatedly observed cells.
- Cross-reference cells with OpenCellID when configured.
- Require suspicious behaviour to persist through three analysis phases, or two when two
  independent high-value historical/physical checks fail together.
- Explain which checks passed, failed, or could not run.
- Preserve incidents separately from routine antenna history.
- Capture and export a forensic package around qualifying incidents.

Available observations vary by phone, modem, Android version, operator, radio conditions, and power-saving restrictions.

From v2.8.0, the physical interpretation used by channel sanity (H8), geographic distance (H11)
and LTE band downgrade (H14) follows the radio technology of the actual `CellInfo` observation.
The 4G/5G label shown by Android remains useful presentation context, but an NSA icon cannot make
its LTE anchor behave like a physical NR cell in those checks.

From v2.8.1, the fourteen-day local-confidence requirement uses the complete 90-day clean-history
window even for cells sampled very frequently. The app still bounds detailed radio and coordinate
analysis to the latest 500 clean rows for predictable performance. The minimums of eight located
and ten RF observations are evaluated within that recent detailed window, so a long indoor period
without usable GPS can delay eligibility despite older located history. Keep the existing database
when updating: accumulated days are reused and no migration or reset is required.

---

## 2. Initial setup

### Stable-site protection

Version 2.9.1 learns coarse local sites independently. Motion evidence advances only when Android
delivers a genuinely new GPS fix. A poor fix temporarily reports `UNKNOWN` but preserves the good
window for up to 60 seconds; longer gaps reset it. The 15-second GPS subscription has no movement
distance gate so a stationary phone can demonstrate two minutes of coherent fixes. A site identifier represents an
approximately 500 m grid bucket and is stored as a derived hash; the new site tables do not retain
exact coordinates. Reliable fixes first classify motion. Two minutes of precise, coherent fixes
are required for `STATIC_CONFIRMED`; missing or inaccurate GPS yields `UNKNOWN`.

A clean install and an upgrade from v2.8.1 both begin site-context learning from zero. Existing
history remains fully available to all older detector features, but it cannot supply accuracy,
motion or neighbour facts that were never recorded. A site becomes active only after seven serving
days, three static days, three neighbour-baseline days and thirty serving observations. New sites,
movement, unreliable GPS and unavailable neighbours cannot activate a hold.

At an active site, a serving identity new to that site may satisfy the proposed
`SITE_UNVERIFIED` conditions. Version 2.9.0 runs this in shadow mode: the Terminal says that it
would apply the hold, but trust learning, forensics and normal alarms remain unchanged. This allows
the false-positive rate, including legitimate sibling sectors, to be measured before enforcement.
Shadow triggers are retained as privacy-reduced technical telemetry. A single serving sighting does
not make an identity known: corroboration requires three serving days or two neighbour days. Four
overlapping 500 m grids reduce false site changes caused by GNSS jitter near a grid boundary.
The canonical grid is chosen by maturity and evidence before its verdict is considered. Novelty is
stored as one episode, so temporary GPS loss, unknown motion and service restarts do not turn one
continuous serving period into repeated triggers.

Use **History → Topology → Export Stable-Site** after field collection. The ZIP contains
`sites.csv`, `site_cells.csv`, `motion.csv` and `shadow_episodes.csv`. It includes hashed site keys,
serving and neighbour days/observations, motion bands and logical episode timing; it contains no
exact site coordinates.
The cell and episode CSV files do contain complete cellular identities, just like the topology
export. Treat the ZIP as potentially sensitive movement/context data and review it before sharing.
Restart recovery uses established site evidence rather than relying only on process memory. A
transmitter present throughout bootstrap can influence the first baseline; ICdetection cannot
establish external truth from local history.

### Permissions

Grant the permissions requested by the app. Location permission is important because Android protects cellular identifiers as location-sensitive data. GPS also enables geographic consistency checks and comparisons with tower-database coordinates.

If a permission is unavailable, ICdetection should continue with the remaining information, but affected rules may report `N/A`.

### Background monitoring

For longer sessions:

1. Allow ICdetection to operate in the background.
2. Exclude it manually from Android and manufacturer battery optimisation.
3. Confirm that its persistent monitoring notification remains visible.

Version 2.4.0 keeps the GPS-only location stream and collection loop active continuously while the
monitoring service runs, including with the screen off. This intentionally consumes more battery so
H11, H13 and H16 have contemporaneous positions. The persistent notification states that continuous
GPS is active. The app does not request the privileged battery-optimization exemption itself; the
user remains in control of that system setting.

Below 5% battery while unplugged, continuous GPS and the collection wake lock pause to prevent the
phone from powering off and losing all collection. They resume automatically when charging starts
or the battery recovers.

If a scheduled sample is still recorded without coordinates, ICdetection requests a bounded
high-accuracy fix. A successful fix is applied only to the newest coordinate-less record for the
same complete cell identity. If GPS cannot produce a valid fix, the coordinates remain empty; the
app never invents, reuses or retroactively assigns a stale position.

### External verification

OpenCellID is optional. Configure its token in Settings to enable external cross-referencing. Without it, local and historical analysis still works, while database-dependent rules may report `N/A` or `PENDING`.

A missing public-database record does not make a cell malicious. Public datasets can be incomplete or outdated.

### Proxy

If required by your threat model, configure the supported proxy and a compatible service such as Orbot. Verify that API requests still succeed after enabling it.

---

## 3. Monitoring behaviour

Start the monitoring service from the main screen. While active, the app repeatedly reads the data Android exposes and reevaluates the current cellular environment.

When ICdetection is visible, it requests fresh radio telemetry once per second so the RSRP, RSRQ
and geometry graphs move responsively. With another app in front it returns to the normal
three-second screen-on cadence; with the screen off it uses the ten-second low-power cadence.
Handovers are event-driven by Android and do not wait for those intervals. The phone or modem may
still coalesce measurements, so repeated points can legitimately have the same value.

Displayed states are dynamic. A rule may move from `N/A` to `PASS` after GPS, neighbour-cell data, latency, or an API result becomes available. It can later change to `FAIL` if a new measurement becomes inconsistent.

If the service stops or the device restarts while an incident or forensic capture is open, the record is closed as `INTERRUPTED`. The app must not describe an unobserved period as continuous monitoring.

### Local cell confidence (private v2.7.0 field build)

The main status card shows a confidence percentage and one of five states: `NEW`, `LEARNING`,
`ESTABLISHED`, `CHANGED` or `QUARANTINED` (translated when Spanish is selected). The percentage
grows from clean observations across days, GPS evidence, stable RF identity and trusted handover
routes. It requires at least fourteen distinct days to reach `ESTABLISHED`. Rapid sampling is
capped and cannot accelerate trust indefinitely. OpenCellID is displayed separately and never
increases this local confidence.

The maximum is 98%. `ESTABLISHED` is not proof that a transmitter is genuine; it means the current
observation matches a repeatedly observed local pattern. A changed parameter is shown immediately
and is excluded from subsequent learning,
but an alarm requires an RF contradiction plus independent geographic or handover evidence and
still passes through temporal confirmation. Failed observations are quarantined from learning.

PCI values are learned inside their ARFCN carrier so legitimate carrier aggregation does not look
like an identity change. A permanent operator reconfiguration is kept separate from the trusted
profile for at least fourteen coherent days. It is accepted only with enough located observations,
trusted transitions and no recent alternation with the previous PCI on the same carrier.

---

## 4. Reading cellular data

| Field | Meaning |
| --- | --- |
| MCC | Mobile Country Code. |
| MNC | Mobile Network Code. |
| Cell ID / CID | Identifier reported for the observed cell. |
| TAC | Tracking Area Code. |
| PCI | Physical Cell Identity at the radio layer. |
| ARFCN / EARFCN / NR-ARFCN | Radio channel number, depending on technology. |
| Radio | Technology derived from Android's cellular-information type. |
| RSRP | Reference-signal received power. |
| RSRQ | Reference-signal received quality. |
| SINR | Signal-to-interference-plus-noise ratio, when available. |
| TA | Timing Advance, when the modem provides a usable value. |

Blank or unavailable data is preferable to an invented measurement.

### Verification states

- **VERIFIED:** A configured external source returned a compatible record. This adds context but is not absolute proof of legitimacy.
- **PENDING:** Verification has not finished or is waiting for required data.
- **NOT FOUND:** No matching public record was returned. This is common for new, indoor, rural, or incompletely mapped cells.
- **ERROR / API ERROR:** Credentials, connectivity, proxy configuration, or the external service prevented verification.

### Threat score

The score is a decision aid, not a measured probability that an IMSI catcher exists. The engine combines available evidence, limits double-counting between related signals, and considers historical cell reputation. Missing evidence should remain unavailable instead of becoming automatically suspicious.

---

## 5. Heuristic diagnostics

Version 2.5.0 exposes the state and explanation of each evaluated rule:

- **PASS:** The rule ran with sufficient data and did not detect its suspicious condition.
- **FAIL:** The rule ran and detected its suspicious condition. One failed rule does not automatically confirm a threat.
- **N/A:** The rule could not make a defensible decision with the available data.

Common reasons for `N/A` include:

- The modem does not expose the required measurement.
- GPS is unavailable or inaccurate.
- External tower coordinates are unavailable.
- The historical baseline is not mature.
- Too few observations exist for comparison.
- A network request is pending or failed.
- Android does not expose the required privileged ciphering information.

`N/A` is an honest abstention, not automatically a defect. Read the diagnostic explanation to learn which prerequisite is missing.

### H16: mobility sanity

The **CORDURA DE MOVILIDAD · H16** card audits a real serving-cell transition. It combines the
distance actually travelled by the phone, GPS accuracy, cells visible as neighbours immediately
before the handover, the locally learned observation zones of both cells, and trusted previous
transitions.

From v2.5.0, a neighbour announcement or a previously trusted route no longer overrides a mature,
contradictory geographic baseline. Without enough samples at both endpoints, H16 still abstains or
accepts the shortcut as before; it does not treat a new cell as suspicious merely for being new.

### Offline geometry view

The **GEOMETRÍA** tab draws observation centres, P90 coverage of the phone's own samples and known
handover routes entirely on-device. It makes no map or external database request and does not alter
the score. LTE sectors grouped under one logical eNodeB are highlighted when unusually separated,
but this is only a review signal: distributed radio deployments can also produce that pattern.

- `COHERENT` means that the available movement evidence explains the handover.
- `INCOHERENT` means that mature local evidence describes a physically implausible jump. H16 has
  low weight and does not confirm an IMSI catcher on its own.
- `N/A` usually means no handover occurred yet, GPS was insufficient, or either local zone is still
  learning.

There is deliberately no universal one-kilometre rule. Legitimate rural macrocells, urban small
cells, terrain, load balancing, and operator policy make fixed tower-spacing thresholds unreliable.

### Topology explorer

Open **History → TOPOLOGY** to inspect the directional handover graph learned locally by the
device. The summary reports unique cells, routes, total handovers, and routes with trusted H16
observations. Each route preserves its full origin and destination identities, observation count,
trusted count, last H16 state, trust ratio, and most recent timestamp.

The filters separate all routes, learned routes, and routes that deserve review. This screen is
strictly read-only: opening it, filtering it, or inspecting a route cannot change `SecurityScore`,
H16, or the transition baseline.

Use **EXPORT TOPOLOGY** to create a ZIP containing `cells.csv`, `transitions.csv`,
`topology.graphml`, `metadata.json`, and `SHA256SUMS.txt`. GraphML can be opened by tools such as
Gephi or Cytoscape. The export contains no API credentials, IMSI, IMEI, or telephone number, but
cell identities and routes can reveal habitual movement patterns; review it before sharing.

---

## 6. Baseline maturity

Historical rules need repeated observations before they become reliable. The maturity indicators show whether enough local history exists for those checks.

A new installation will naturally contain immature baselines and several `N/A` results. To improve them:

- Run the app regularly in familiar areas.
- Let it observe legitimate cells under different normal conditions.
- Avoid deleting the database without a reason.
- Allow days or weeks for history-dependent fingerprints to mature.

Mature baselines improve context but do not guarantee correct attribution. Operators can legitimately reconfigure their networks.

---

## 7. Temporal phases: `1/3`, `2/3`, and `3/3`

ICdetection does not confirm a threat from one suspicious cycle:

1. **`1/3` — observation begins:** The cycle is suspicious. An incident opens and forensic capture starts, including the pre-event buffer.
2. **`2/3` — suspicion persists:** A second consecutive cycle remains suspicious, but confirmation is not complete.
3. **`3/3` — confirmed incident:** Suspicious behaviour persisted for the required confirmation window. The app records confirmation and may issue the configured alert.

If the condition returns to normal before `3/3`, it remains an incident observation but is not presented as a confirmed threat. This distinction reduces alarms caused by transient radio behaviour.

### Incident states

- **OBSERVING:** Suspicion started but has not reached confirmation.
- **CONFIRMED:** The episode reached `3/3`.
- **RECOVERED:** Conditions returned to normal and the episode closed normally.
- **INTERRUPTED:** Monitoring stopped before a reliable closure was observed.

Even a brief `1/3` episode can remain in incident history for later examination.

---

## 8. Main heuristic families

No individual rule proves the presence of a rogue base station.

### Cell isolation and neighbours

A strong serving cell without coherent neighbours can be suspicious, but rural coverage, indoor deployments, modem limitations, and temporary network conditions may look similar.

### Sudden signal changes

An unusually large power increase can indicate a transmitter much closer or stronger than expected. Movement, handovers, line-of-sight changes, and indoor propagation can also cause abrupt shifts.

### Timing Advance and geometry

When Timing Advance has a known unit, the app can compare its implied range with geographic context. Many modems omit TA or repeatedly return zero as a stub. Unusable zero values must not be interpreted as a real distance of zero metres.

### Geographic consistency

Historical GPS observations can reveal a Cell ID appearing in physically inconsistent locations. Bad GPS fixes, operator reuse, database errors, or parsing limitations must also be considered.

### Historical RF baseline

The app learns normal signal strength and, when available, RSRQ/SINR characteristics for cells repeatedly observed by this device. Once sufficiently mature, the baseline can detect an unusually strong or inconsistent observation.

From version 2.5.1, a new complete cell identity is quarantined from detector learning until it has
at least five clean observations (`score >= 85`) on two different days. All observations are still
stored and exported. After promotion, only clean observations train the geographic, signal,
RSRQ/SINR and RF-identity baselines. A suspicious observation therefore cannot redefine the normal
reference that will later judge it. This may make new cells show an immature baseline for longer;
that abstention is intentional.

From version 2.5.2, geographic evidence also passes a GNSS continuity gate. Ordinary movement is
accepted immediately. A displacement implying high speed remains provisional until a second,
genuinely newer fix continues coherently in the new area. Repeated reads of one cached fix do not
count, and an immediate return to the accepted area discards the excursion. During validation the
location is unavailable to H11/H13 rather than being treated as evidence. This also supports fast
trains without lowering a single hard speed limit for every journey.

Temporal confirmation is also evidence-aware. A single or weak anomaly still needs three distinct
modem observations. If two or more independent high-value checks among geographic consistency
(H11), signal baseline (H13), RF identity stability (H15) and transition coherence (H16) fail in
the same observation, two distinct observations are required. Repeated delivery of the same modem
sample never counts as another phase.

### Band downgrade

The engine examines suspicious transitions from higher-frequency capacity bands to lower-frequency bands while considering radio conditions. Legitimate coverage optimisation, congestion, indoor movement, and operator policy can produce similar transitions.

### RF identity / PCI

A Cell ID associated with inconsistent physical-layer identities can deserve attention. The app limits comparisons to reduce known carrier-aggregation false positives, but operator reconfiguration and modem reporting remain possible explanations.

### Ciphering visibility

Normal no-root Android apps generally cannot directly inspect modem ciphering state. This rule is therefore normally `N/A` unless the operating system exposes a supported signal. ICdetection does not claim direct null-cipher or IMSI-disclosure detection when the data is unavailable.

### Regional, latency, and external correlation

These checks depend on inputs such as reliable location, tower-database results, network reachability, and sufficient samples. They may legitimately remain `N/A`; their explanation should identify the missing prerequisite.

---

## 9. History and retention

The app separates three types of information:

- **Antenna history:** Long-term observations used to understand cells and build historical baselines.
- **Incident history:** Security-relevant episodes and their progression.
- **Forensic cases:** Detailed evidence captured around qualifying incidents.

Closed incidents and completed or interrupted forensic cases can be deleted individually. Expand
the relevant item, select its delete action, and type exactly `BORRAR` in the confirmation dialog.
Active incidents and captures cannot be deleted. Deleting a forensic case also permanently removes
all samples belonging to it, so export it first if it may be useful later.

Routine history may be pruned according to the configured retention policy to prevent unlimited database growth. Export important information before clearing application data or uninstalling the app.

Retention and the forensic-sample cap are enforced when monitoring starts and then approximately
once every 24 hours while the service remains active. The History screen loads the 2,000 most recent
rows to keep memory use bounded, but **EXPORT CSV** still streams the complete retained database.

---

## 10. Forensic capture

### Silent trust-contradiction observation cases

If the same complete cell identity was previously observed as `ESTABLISHED` and then transitions
to `CHANGED` with one or more concrete trust contradictions, ICdetection opens a neutral observation
case. Its identifier begins with `ICD-OBS-`, and the history screen labels it **Contradiction in
established cell · observation case**. This is evidence preservation, not an alarm or a claim that
an IMSI catcher was detected.

The case contains the normal pre-event buffer, the exact transition observation and the existing
post-capture window. Remaining in `CHANGED` does not create repeated cases. Returning to
`ESTABLISHED` and later changing again is a new transition. If normal temporal detection begins
while the observation case is still active, that case is promoted and continued so the earlier
evidence is retained without a duplicate.

When an episode reaches `1/3`, ICdetection opens a forensic case. It preserves context rather than only the final alert.

A case can contain:

- Up to approximately 60 seconds before the first suspicious cycle.
- The progression through `1/3`, `2/3`, and `3/3`, when reached.
- Approximately 60 seconds after recovery.
- Serving and observed neighbour cells.
- Available RSRP, RSRQ, SINR, PCI, channel, TAC, TA, and radio technology.
- Device position and GPS accuracy when available.
- External-verification state and latency context.
- Threat score, temporal phase, and rule diagnostics for each cycle.
- Device capabilities and unavailable-data explanations.
- Relevant terminal output.
- App version, Android version, and device model.

Capture duration is bounded so an unresolved case cannot grow indefinitely.

### Forensic states

- **CAPTURING:** The suspicious episode is still being recorded.
- **POST_CAPTURE:** The condition recovered and the post-event window is being collected.
- **READY:** Capture completed normally and can be exported.
- **INTERRUPTED:** Monitoring ended before normal completion; partial evidence is retained.

Do not describe an interrupted package as a complete continuous recording.

---

## 11. Exporting a forensic case

Select a completed case and use **EXPORT FORENSIC CASE**. The ZIP can contain:

```text
ICD-YYYY-MM-DD-NNNN.zip
├── case.json
├── timeline.csv
├── cells.csv
├── heuristics.csv
├── capabilities.json
├── terminal.log
└── SHA256SUMS.txt
```

| File | Purpose |
| --- | --- |
| `case.json` | Case metadata, state, timing, summary, and device/app context. |
| `timeline.csv` | Chronological measurements and analysis state. |
| `cells.csv` | Serving and neighbour-cell observations. |
| `heuristics.csv` | Rule state and explanation by cycle. |
| `capabilities.json` | Data the device could and could not provide. |
| `terminal.log` | Relevant diagnostic log entries. |
| `SHA256SUMS.txt` | SHA-256 digests for detecting later file modification. |

To verify an extracted package on Linux or WSL:

```bash
sha256sum -c SHA256SUMS.txt
```

Every listed file should report `OK`. Verify a copy and preserve the original ZIP unchanged.

The checksum proves only that the checked files match their recorded digests. It does not establish who collected them, independently prove the collection time, create a legal chain of custody, or prove that an IMSI catcher existed.

---

## 12. Exporting antenna history

Use the search field beside **EXPORT CSV** to filter cells immediately by CID, MCC, MNC, TAC, PCI, ARFCN or radio technology. Suggestions open matching cell identities quickly.

Expand a cell and open one detail section at a time:

- **GPS locations:** observation date, verification state, coordinates and map access.
- **Handovers:** incoming and outgoing routes, observation frequency, confidence, state and last observation.
- **Technical information:** complete identity, radio parameters, latest signal metrics, Timing Advance, verification, scores, sample count and observation range.

Use **EXPORT CSV** for long-term observations rather than one incident:

- `Lat` and `Lon` are the phone's position at collection time.
- `ApiLat` and `ApiLon` are coordinates returned by an external database.
- `TA` is the raw Timing Advance.
- `TAUnit` explains how TA was interpreted.
- `TAMeters` should be empty when conversion is not defensible.
- `Radio` comes from Android cellular information, not only the status-bar label.
- `AnomalyConfidence` is analytical output, not an independently measured probability.
- Sub-threshold failures are observations, not confirmed alerts.

Validate a CSV from the project directory with:

```bash
python3 tools/check_export.py path/to/export.csv
```

Exports can contain precise locations, network identifiers, device information, and security observations. Redact sensitive information before sharing publicly.

---

## 13. Responding to a credible alert

If an event persists to `3/3` and remains concerning after reviewing its explanations:

1. **Do not panic.** It is still an anomaly classification, not attribution.
2. **Preserve context.** Do not clear history, storage, or the forensic case.
3. **Use Airplane Mode** if immediate disconnection is appropriate. Manual activation is the most dependable option for a normal installation.
4. **Move to another safe location** if useful, then observe whether the anomaly follows the phone or remains localised.
5. **Export the forensic case** after it becomes `READY`; retain an `INTERRUPTED` case as partial evidence if monitoring stopped.
6. **Preserve the original ZIP** and record the circumstances and actions taken.
7. **Seek corroboration** from another device, an operator, another data source, or qualified radio/network analysis.

Automatic Airplane Mode requires privileged `WRITE_SECURE_SETTINGS` access granted through ADB. Normal installations do not possess this permission.
If automatic activation is unavailable, version 2.3.5 posts an actionable security notification
that opens Android's Airplane Mode settings so the user can decide whether to disconnect.

---

## 14. Privacy and evidence handling

ICdetection data may reveal device locations, travel patterns, observed networks, device characteristics, and security events.

Recommended handling:

- Preserve original exports and analyse copies.
- Record the SHA-256 digest of the original ZIP.
- Do not edit files inside the original package.
- Use encrypted storage for sensitive cases.
- Redact GPS coordinates and credentials before public sharing.
- Never publish API tokens, signing keys, or private keystores.

Formal evidential use normally requires documented acquisition, preservation of originals, an auditable chain of custody, trustworthy time evidence, corroboration, and expert interpretation. Obtain appropriate legal and technical advice. The forensic export assists investigation but does not automatically satisfy legal requirements.

---

## 15. Technical limitations

Because ICdetection runs in Android user space without root:

- It cannot access raw baseband, RRC, or NAS signalling.
- It normally cannot inspect active cellular ciphering.
- It cannot reliably observe IMSI disclosure through privileged modem data.
- Timing Advance is unavailable, ambiguous, or stubbed on many phones.
- Neighbour-cell reporting differs by device and operator.
- GPS can be inaccurate, unavailable indoors, or throttled in the background.
- Public tower databases can be incomplete, delayed, or wrong.
- Carrier aggregation and modem behaviour can make identities appear unstable.
- Operator-side lawful interception is not visible from these observations.
- A sophisticated IMSI catcher may expose no anomaly available through public Android APIs.

ICdetection is a transparent multi-signal anomaly auditor. It provides leads, historical context, and structured evidence about behaviour visible to Android; it cannot guarantee detection.

---

## 16. Troubleshooting

### Most rules show `N/A`

Check permissions, GPS, API configuration, connectivity, and the capability explanations. Allow time for baselines to mature. Some measurements may remain unavailable permanently on a particular modem.

### A rule alternates between `N/A` and `PASS`

Its required input is intermittent. Review its explanation and check GPS, neighbour reporting, latency, or API availability. This behaviour alone is not a threat.

### A cell is `NOT FOUND`

Public databases do not contain every cell. Check whether independent heuristics fail, whether the condition persists, and whether local historical behaviour remains consistent.

### No forensic case appears

A case starts at the first suspicious temporal phase. Confirm that monitoring is running and inspect incident history. Normal observations do not create cases.

### A case is `INTERRUPTED`

The service stopped or restarted before capture completed. The app retains the partial case honestly; it must not be relabelled as recovered.

### Checksum verification fails

A file changed after export or extraction was corrupted. Preserve the original ZIP, extract a fresh copy, and verify again.

---

## 17. Responsible use

Use ICdetection only for lawful, defensive, educational, and research purposes. Respect local laws, privacy, operator terms, and restrictions concerning network monitoring.

When reporting bugs, provide enough technical detail to reproduce the issue, but remove personal locations, credentials, tokens, and unrelated third-party information.

---

**Stay observant. Verify context. Preserve evidence carefully.**

*Developed by Alexis Gómez Rodríguez.*
