# ICdetection — User Manual

ICdetection is an open-source Android application for passive cellular-network auditing and anomaly
analysis. It observes the information Android exposes, compares each observation with the device's
own history and, when configured, cross-checks cells against a public tower database.

This manual explains how to install and operate the application, read its results, investigate
incidents and export evidence. It describes the current version; what changed in each release is in
[CHANGELOG.md](CHANGELOG.md).

> **Important:** ICdetection is an anomaly detector, not a device that can prove the presence of an
> IMSI catcher. A warning means that the observations deserve examination. It does not identify an
> attacker or establish intent by itself.

## Contents

1. [What ICdetection does](#1-what-icdetection-does)
2. [Installation and setup](#2-installation-and-setup)
3. [Monitoring behaviour](#3-monitoring-behaviour)
4. [Reading cellular data](#4-reading-cellular-data)
5. [Heuristic diagnostics](#5-heuristic-diagnostics)
6. [Baseline maturity](#6-baseline-maturity)
7. [Temporal phases](#7-temporal-phases-13-23-and-33)
8. [Main heuristic families](#8-main-heuristic-families)
9. [History and retention](#9-history-and-retention)
10. [Forensic capture](#10-forensic-capture)
11. [Exporting a forensic case](#11-exporting-a-forensic-case)
12. [Exporting antenna history](#12-exporting-antenna-history)
13. [Responding to a credible alert](#13-responding-to-a-credible-alert)
14. [Privacy and evidence handling](#14-privacy-and-evidence-handling)
15. [Technical limitations](#15-technical-limitations)
16. [Troubleshooting](#16-troubleshooting)
17. [Responsible use](#17-responsible-use)

---

## 1. What ICdetection does

ICdetection works passively and locally. It does not transmit radio commands, attack cellular
networks or require root access.

While monitoring, it can:

- Observe the serving cell and the cellular information exposed by Android.
- Record MCC, MNC, Cell ID, TAC, PCI, channel, RSRP, RSRQ, SINR, radio technology and Timing Advance
  when available.
- Evaluate sixteen anomaly heuristics (H1–H16) in every analysis cycle.
- Learn the historical behaviour of repeatedly observed cells.
- Cross-reference cells with OpenCellID when configured.
- Require suspicious behaviour to persist through three analysis phases, or two when two independent
  high-value historical or physical checks fail together.
- Explain which checks passed, failed or could not run.
- Preserve incidents separately from routine antenna history.
- Capture and export a forensic package around qualifying incidents.

The available observations vary by phone, modem, Android version, operator, radio conditions and
power-saving restrictions.

Physical interpretation — channel sanity (H8), geographic distance (H11) and band downgrade (H14) —
follows the radio technology of the actual `CellInfo` observation. The 4G/5G label shown by Android
is presentation context; an NSA icon cannot make its LTE anchor behave like a physical NR cell.

---

## 2. Installation and setup

### Installing

Stable versions are published as an APK on the project's GitHub Releases page. Betas are published
as source only: build them from their branch with `./gradlew assembleDebug`. Every version is the
same app (`com.alexisgordr.icdetector`, *ICdetection*), so a newer release installs over an older one
and keeps its data; a build signed with your own key cannot update the published APK, so uninstall
first.

**Moving from v2.10.x to 3.0 requires a clean install.** Export anything you want to keep (history
CSV, forensic cases), **uninstall v2.10.x** and then install 3.0. History, settings and the
OpenCellID key start empty. Installing 3.0 over v2.10.x would still upgrade the old database safely
(old rows keep the new fields empty), but mixing data from both sides of the 3.0 methodology changes
is not supported. From 3.0 on, every version updates the previous one normally.

> If you take part in the field-collection campaign, keep that phone on v2.10.x; see
> [IMPORTANT.md](IMPORTANT.md).

### Permissions

Grant the permissions the app requests. Location permission is essential because Android protects
cellular identifiers as location-sensitive data; GPS also enables geographic consistency checks and
comparisons with tower-database coordinates.

If a permission is unavailable, ICdetection continues with the remaining information, and the
affected rules report `N/A`.

### Background monitoring and battery

For longer sessions:

1. Allow ICdetection to run in the background.
2. Exclude it from Android and manufacturer battery optimisation:
   **Settings → Apps → ICdetection → Battery → Battery optimization → Don't optimize**.
3. Confirm that its persistent monitoring notification remains visible.

While the monitoring service runs, ICdetection keeps a GPS-only location stream and the collection
loop active, including with the screen off, so H11, H13 and H16 have contemporaneous positions. This
intentionally uses more battery; the persistent notification says that continuous GPS is active. The
app does not request the privileged battery-optimization exemption itself.

- **Low battery:** below 5% while unplugged, continuous GPS and the collection wake lock pause, and
  resume automatically when charging starts or the battery recovers.
- **Missing coordinates:** if a scheduled sample is recorded without coordinates, ICdetection
  requests a bounded high-accuracy fix. A fix is applied only to the newest coordinate-less record
  of the same complete cell identity, and only if that record is from the last 2 minutes. Otherwise
  the coordinates stay empty; the app never invents, reuses or retroactively assigns a stale position.
- **Location mode:** **Settings → Location mode**. Names and descriptions follow the app language:
  *Continuo / Continuous*, *Inteligente / Smart*, *Adaptativo / Adaptive*.
  - **Continuous** (default): the existing GPS stream remains active with the screen on or off.
    Use it for field campaigns where location continuity matters. Failed normal precise-fix
    requests retain their existing backoff; forced requests retain their existing behavior.
  - **Smart**: attempts a GPS fix roughly 45 seconds after the previous successful fix or the
    end of an unsuccessful attempt. A probe stops on success or after 20 seconds; the first
    saving-mode probe after service startup gets 45 seconds for acquisition. Requests caused by
    cell changes, suspected anomalies or screen events share a minimum 30-second interval and
    cannot overlap. This mode aims for frequent location coverage without a permanent GPS stream.
  - **Adaptive**: retains continuous GPS with the screen on while reception is usable. With the
    screen off, it requests fixes on relevant events, at most once a minute.

  **Poor reception:** Smart and Adaptive pause repeated acquisition after three failed probes.
  Adaptive also stops a continuous stream after three minutes without a new usable GPS fix.
  Periodic retries wait 2, then 5, then at most 10 minutes after failure. A cell change, screen
  event or suspected anomaly can request an earlier probe, at most once every 2 minutes during
  poor reception; events do not reset the failure count. One newly delivered usable fix restores
  the normal policy. GPS reception loss suggests an obstruction, not necessarily a building:
  tunnels, garages and other conditions can behave similarly. Repeated cached positions cannot
  clear the poor-reception state. No Wi-Fi/cellular position provider is added.

  Cellular scanning continues while GPS acquisition pauses. Fresh coordinates for every cell
  cannot be guaranteed, especially in fast travel or indoors. Fix age is checked using elapsed
  realtime, so changing the clock does not make an old position fresh. The existing 2-minute age
  limit remains. Location-dependent rules can be `N/A`; battery savings require measurement on
  the device. Switching to a saving mode changes the availability of detector inputs.

  Each history row records the selected mode it was observed with (`CONTINUOUS`, `INTELLIGENT` or
  `ADAPTIVE` in the CSV), and the
  monitoring notification title shows the active mode.
- **After a phone restart,** collection resumes when you open the app again. The gap is reported as
  an interrupted-collection notice, never silently hidden.

### Notifications and sound

ICdetection uses two notification channels, which you can configure separately in
**Settings → Apps → ICdetection → Notifications**:

| Channel | What it shows | Sound |
|---|---|---|
| **Monitoring** | The persistent notification (cell, signal, verification), updated every few seconds | Always silent, whatever the channel setting |
| **Security alerts** | A confirmed network anomaly, once per episode | Keep it alerting so you hear an alarm |

Only a confirmed network anomaly makes a sound, so there is no need to silence the app. If
notifications are off, or *Security alerts* is silenced or has its sound set to *None*, the main
screen shows **ALARMS ARE MUTED** with a button that opens the alert settings. That warning is about
the notification: the short alert tone is separate, plays at notification volume and respects only
silent, vibrate and Do Not Disturb.

### External verification

OpenCellID is optional. Enter your own API token in Settings to enable cross-referencing. Without
it, local and historical analysis still works, while database-dependent rules may report `N/A` or
`PENDING`. A missing public-database record does not make a cell malicious: public datasets can be
incomplete or outdated. Verification is a label and never changes the score.

### Proxy

If your threat model requires it, configure the SOCKS5 proxy and a compatible service such as Orbot,
then check that verification requests still succeed.

### Latency detection (experimental)

Off by default. When you enable it in Settings, and only while traffic leaves over mobile data (not
over Wi-Fi, a VPN or the SOCKS5 proxy), the app sends an HTTPS `HEAD` request roughly every
30 seconds to three third-party endpoints: `www.google.com/generate_204` (Google),
`one.one.one.one` (Cloudflare) and `dns.quad9.net` (Quad9). The requests carry no cell or location
data, but they reveal your IP address to those services. Together with optional OpenCellID
verification, these are the only network connections the app makes.

The network indicator on the main screen shows one of four states:

| Indicator | Meaning |
|---|---|
| `NET N/A` | Not measured (disabled, not on mobile data, or no recent result) |
| `NET LEARNING` (amber) | Learning this cell's reference latency; H12 is `N/A` meanwhile |
| `NET OK` | Measured and normal |
| `NET ANOMALOUS` | Measured and anomalous for this cell |

A result is valid for 90 seconds and is reset when the cell changes or the signal is lost.

### Mobility Familiarity and Geometry (experimental)

Mobility Familiarity remembers serving-cell transitions seen in independent journeys. It describes
route history as `UNKNOWN_ON_ROUTE`, `OBSERVED_ON_ROUTE` or `KNOWN_ON_ROUTE`; it never calls a cell
trusted, verified or legitimate and cannot change any security decision.

A trip opens only after confirmed `MOVING`. Its cells and directed edges are persisted, including
across service or process restarts, and the current trip is evaluated only against aggregate counts
from earlier committed trips. Pending edges are committed once, after the trip has movement and at
least three distinct serving identities. A trip closes after three minutes of sustained static state,
ten minutes on the same serving cell without conclusive motion, or six hours at most. An open trip
can be resumed for fifteen minutes; older open trips close as `ABANDONED` and are committed only if
already valid. Closed trip detail is kept for 90 days; pruning it does not reduce the aggregate
`trip_count`. The experimental familiarity rule requires two incident edges that each appeared in at
least three earlier trips.

Geometry shows accumulated transitions and confirmed historical trips separately. During an open
valid trip, an optional `n → n+1` preview never changes the confirmed count until the service closes
and commits the trip. Opening or closing Geometry has no effect on a trip. **EXPORT GEOMETRY**
creates one ZIP with CSV, GraphML and metadata files: complete cellular identities and route
relationships, but no GPS coordinates. Review it before sharing.

### Stable-Site protection (shadow mode)

Stable-Site learns coarse local sites while the phone is physically static. A site is an approximately
500 m grid bucket stored as a derived hash; the site tables keep no exact coordinates. Four
overlapping grids reduce false site changes caused by GNSS jitter near a boundary.

**Motion.** Motion evidence advances only when Android delivers a genuinely new GPS fix. Two minutes
of precise, coherent fixes are required for `STATIC_CONFIRMED`; missing or inaccurate GPS yields
`UNKNOWN`. A poor fix temporarily reports `UNKNOWN` but preserves the good window for up to
60 seconds; longer gaps reset it.

**Neighbour capability.** A complete neighbour identity follows the full path. When Android withholds
the Cell ID but supplies RAT, ARFCN and PCI, the app records a local `RF_CONTEXT` fingerprint scoped
to the hashed site; it is not a Cell ID and is not globally unique. It accepts only RAT-specific
Android ranges and excludes unavailable sentinels; signal strength is never part of it. With no
usable neighbours the site remains `SHADOW_READY` and the export explains why. Timing Advance does
not take part in this decision.

**Maturity.** A site becomes active only after seven serving days, three static days, three
neighbour-baseline days and thirty serving observations. New sites, movement, unreliable GPS and
unavailable neighbours cannot activate a hold. A single serving sighting does not make an identity
known: corroboration needs three serving days or two neighbour days.

**Shadow mode.** At an active site, a serving identity new to that site may meet the
`SITE_UNVERIFIED` conditions. Stable-Site only reports this — the terminal says that it *would* apply
the hold — while trust learning, forensics and alarms remain unchanged. Novelty is stored as one
episode, so GPS loss, unknown motion and service restarts do not turn one serving period into
repeated triggers. This lets the false-positive rate, including legitimate sibling sectors, be
measured before any enforcement.

**Export.** Use **History → Topology → Export Stable-Site** after collection. The ZIP contains
`sites.csv`, `site_cells.csv`, `motion.csv` and `shadow_episodes.csv`: hashed site keys, serving and
neighbour days and observations, motion bands and episode timing, but no exact site coordinates. The
cell and episode files contain complete cellular identities; treat the ZIP as sensitive movement and
context data. A transmitter present throughout the first days can influence the first baseline;
local history cannot establish external truth.

---

## 3. Monitoring behaviour

Start the monitoring service from the main screen. While active, the app repeatedly reads what
Android exposes and re-evaluates the cellular environment.

**Cadence.** With ICdetection on screen, fresh radio telemetry is requested once per second so the
graphs move responsively. With another app in front it uses a three-second cadence, and with the
screen off a ten-second low-power cadence. Handovers are delivered by Android as events and do not
wait for those intervals. The modem may coalesce measurements, so repeated points can legitimately
have the same value.

**Dynamic states.** A rule may move from `N/A` to `PASS` once GPS, neighbour data, latency or an API
result becomes available, and later to `FAIL` if a new measurement becomes inconsistent.

**Continuity.** Confirmation counts consecutive observations of the same cell. It starts again — and
the terminal says why — after:

- a **signal loss** (empty cell list, abstention, airplane mode): *"Continuity interrupted by signal
  loss"*;
- a **gap of more than 2 minutes without readings**, even when no loss was reported (for example in
  deep sleep): *"Continuity interrupted by a gap without readings"*.

After either, the `1/3`, `2/3` phases, H1's isolation streak and H14's previous band start from
scratch. A normal handover or the refresh button does not interrupt anything.

**Results always belong to their reading.** The time, GPS position and service state of each history
row are those of the moment the reading arrived, not of the moment the analysis finished. If the
signal is lost or the serving cell changes while an analysis is still running, its result is
discarded instead of being shown, alerting or being stored.

**Interruptions.** If the service stops or the device restarts while an incident or forensic capture
is open, the record is closed as `INTERRUPTED`. The app never describes an unobserved period as
continuous monitoring.

### Local cell confidence

The main status card shows a confidence percentage and one of five states: `NEW`, `LEARNING`,
`ESTABLISHED`, `CHANGED` or `QUARANTINED`. The percentage grows from clean observations across days,
GPS evidence, stable RF identity and trusted handover routes. Reaching `ESTABLISHED` takes at least
fourteen distinct days within a 90-day window; rapid sampling is capped and cannot accelerate trust.
Detailed radio and coordinate analysis uses the latest 500 clean rows, within which at least eight
located and ten RF observations are needed, so a long indoor period without GPS can delay
eligibility. OpenCellID is shown separately and never increases local confidence.

The maximum is 98%. `ESTABLISHED` is not proof that a transmitter is genuine; it means the current
observation matches a repeatedly observed local pattern. A changed parameter is shown immediately and
excluded from further learning, but an alarm requires an RF contradiction plus independent geographic
or handover evidence, and still passes through temporal confirmation. Failed observations are
quarantined from learning.

PCI values are learned inside their ARFCN carrier, so legitimate carrier aggregation does not look
like an identity change. A permanent operator reconfiguration is kept apart from the trusted profile
for at least fourteen coherent days, and accepted only with enough located observations, trusted
transitions and no recent alternation with the previous PCI on the same carrier.

---

## 4. Reading cellular data

| Field | Meaning |
|---|---|
| MCC | Mobile Country Code |
| MNC | Mobile Network Code |
| Cell ID / CID | Identifier reported for the observed cell |
| TAC | Tracking Area Code |
| PCI | Physical Cell Identity at the radio layer |
| ARFCN / EARFCN / NR-ARFCN | Radio channel number, depending on technology |
| Radio | Technology derived from Android's cellular-information type |
| RSRP | Reference-signal received power |
| RSRQ | Reference-signal received quality |
| SINR | Signal-to-interference-plus-noise ratio, when available |
| TA | Timing Advance, when the modem provides a usable value |

Blank or unavailable data is preferable to an invented measurement. Some modems fill LTE neighbours
with placeholder values (TAC `65535`, Cell ID `268435455`); ICdetection shows those as `N/A`.

### Radio context

The **RADIO CONTEXT** card on the main screen and the **RADIO** tab (History → RADIO) show the
serving cell's connection state (`PRIMARY`, `SECONDARY`, `NONE`, `UNKNOWN`), bandwidth, declared
bands, additional PLMNs and closed subscriber group (CSG, a possible femtocell); the secondary
carriers; the service state (in service, emergency only, out of service, radio off) with data/voice
registration, roaming, network PLMN and SIM PLMN; every visible cell; and recent service-state
changes, exportable to CSV.

Radio context is collection only: it never changes the score or triggers an alert. The serving cell
is the one the modem declares as `PRIMARY_SERVING`; if a declared primary has unusable telemetry, the
cycle abstains instead of promoting a secondary. The terminal logs `[RADIO]` when the serving cell or
its carriers change and `[SERVICIO]` when the service state changes. "Not reported" means the modem
or Android did not provide the field.

### Verification states

- **VERIFIED:** a configured external source returned a compatible record. Context, not proof of
  legitimacy. The label is re-checked after 30 days.
- **PENDING:** verification has not finished or is waiting for required data.
- **NOT FOUND:** no matching public record. Common for new, indoor, rural or unmapped cells.
- **ERROR / REJECTED:** credentials, connectivity, proxy configuration or the external service
  prevented verification.

### Threat score

The score is a decision aid, not a measured probability that an IMSI catcher exists. The engine
combines available evidence, limits double-counting between related signals and considers the
cell's historical reputation. Missing evidence stays unavailable instead of becoming suspicious.

---

## 5. Heuristic diagnostics

The diagnostics view shows the state and explanation of each rule:

- **PASS:** the rule ran with sufficient data and did not detect its suspicious condition.
- **FAIL:** the rule ran and detected its condition. One failed rule does not confirm a threat.
- **N/A:** the rule could not make a defensible decision with the available data.

Common reasons for `N/A`: the modem does not expose the measurement; GPS is unavailable or
inaccurate; external tower coordinates are unavailable; the baseline is not mature; too few
observations exist; a network request is pending or failed; Android does not expose ciphering
information. `N/A` is an honest abstention, not a defect — the explanation names the missing
prerequisite.

**H1 (isolated cell)** with no neighbours and strong signal shows *"pending confirmation (x/3
deliveries)"* until three fresh deliveries confirm the isolation; only then can it fail.

### H16: mobility sanity

The **MOBILITY SANITY · H16** card audits a real serving-cell transition. It combines the distance
the phone actually travelled, GPS accuracy, cells visible as neighbours just before the handover, the
locally learned observation zones of both cells and trusted previous transitions. A neighbour
announcement or a trusted route does not override a mature, contradictory geographic baseline.
Without enough samples at both ends, H16 abstains; it never treats a cell as suspicious merely for
being new.

- `COHERENT` — the available movement evidence explains the handover.
- `INCOHERENT` — mature local evidence describes a physically implausible jump. H16 has low weight
  and cannot confirm an alert on its own.
- `N/A` — no handover yet, insufficient GPS, or a local zone still learning.

There is deliberately no universal one-kilometre rule: rural macrocells, urban small cells, terrain,
load balancing and operator policy make fixed tower-spacing thresholds unreliable.

### Offline geometry view

The **GEOMETRY** tab draws observation centres, P90 coverage of the phone's own samples and known
handover routes entirely on-device. It makes no map or external request and does not alter the
score. LTE sectors grouped under one eNodeB are highlighted when unusually far apart — a review
signal only, since distributed radio deployments can produce the same pattern.

### Topology explorer

**History → TOPOLOGY** shows the directional handover graph learned by the device: unique cells,
routes, total handovers and routes with trusted H16 observations. Each route keeps its full origin
and destination identities, observation count, trusted count, last H16 state, trust ratio and most
recent timestamp. Filters separate all routes, learned routes and routes worth reviewing. The screen
is read-only: it cannot change the score, H16 or the transition baseline.

**EXPORT TOPOLOGY** creates a ZIP with `cells.csv`, `transitions.csv`, `topology.graphml`,
`metadata.json` and `SHA256SUMS.txt`. GraphML opens in tools such as Gephi or Cytoscape. The export
contains no API credentials, IMSI, IMEI or phone number, but cell identities and routes can reveal
habitual movements; review it before sharing.

---

## 6. Baseline maturity

Historical rules need repeated observations before they become reliable. The maturity indicators
show whether enough local history exists.

The RSRP power baseline, the RSRQ/SINR fingerprint and PCI identity stability only use a cell once it
is **trusted**: at least 5 clean observations (score ≥ 85, no failed rule) on 2 different days within
the last 30 days. Power and fingerprint also need 5 samples within 500 m of your position.

- **NEEDS 2 DAYS** (*NECESITA 2 DÍAS*): the cell has not yet earned trust.
- **WAITING** (*EN ESPERA*): the cell is trusted, but the row has no data for another reason — no GPS
  fix, away from the stored samples, cell just recovered.

This is expected: observations are stored meanwhile, and once the cell becomes trusted all clean
samples from the window count at once. *Local reputation* has no such gate. Until a new cell is
trusted, only clean observations can train the geographic, signal, RSRQ/SINR and RF-identity
baselines, so a suspicious observation cannot redefine the reference that will judge it.

A new installation naturally shows immature baselines and several `N/A` results. Run the app
regularly in familiar areas, let it observe legitimate cells under normal conditions, avoid deleting
the database without a reason, and allow days or weeks for history-dependent fingerprints to mature.
Mature baselines improve context but do not guarantee correct attribution: operators legitimately
reconfigure their networks.

---

## 7. Temporal phases: `1/3`, `2/3` and `3/3`

ICdetection does not confirm a threat from one suspicious cycle:

1. **`1/3` — observation begins.** An incident opens and forensic capture starts, including the
   pre-event buffer.
2. **`2/3` — suspicion persists.** A second consecutive cycle remains suspicious.
3. **`3/3` — confirmed incident.** The behaviour persisted for the confirmation window. The app records
   the confirmation, posts a **Network anomaly confirmed** notification once per episode on the
   *Security alerts* channel and plays the alert tone at notification volume (respecting silent,
   vibrate and Do Not Disturb).

A single or weak anomaly needs three distinct modem observations. If two or more independent
high-value checks — geographic consistency (H11), signal baseline (H13), RF identity stability (H15)
and transition coherence (H16) — fail in the same observation, two are enough. Repeated delivery of
the same modem sample never counts as another phase, and a continuity break (section 3) starts
again from zero. An alarm episode closes after 60 s without alarm; a new alarm in the same cell
afterwards is notified again.

If the condition returns to normal before `3/3`, the episode stays in incident history but is not
presented as a confirmed threat.

### Incident states

- **OBSERVING:** suspicion started but has not reached confirmation.
- **CONFIRMED:** the episode reached `3/3`.
- **RECOVERED:** conditions returned to normal and the episode closed normally.
- **INTERRUPTED:** monitoring stopped before a reliable closure was observed.

---

## 8. Main heuristic families

No individual rule proves the presence of a rogue base station.

**Cell isolation and neighbours (H1, H3–H5, H7).** A strong serving cell without coherent neighbours
can be suspicious, but rural coverage, indoor deployments, modem limitations and temporary network
conditions look similar. H3 (MCC), H4 (MNC) and H5 (TAC) need neighbours that report their identity;
many modems report only frequency, PCI and signal, so these rules can stay `N/A` permanently.

**Sudden signal changes (H2, H13).** An unusually large power increase can indicate a transmitter much
closer or stronger than expected. Movement, handovers, line-of-sight changes and indoor propagation
also cause abrupt shifts. Weaker-than-usual readings are not treated as suspicious.

**Timing Advance and geometry (H6).** When Timing Advance has a known unit, its implied range is
compared with geographic context. A missing TA is only taken from a duplicate of the same cell (same
technology and physical cell), never from another transmitter. Many modems omit TA or always return
zero; that "always 0" behaviour is detected per technology and never read as a real distance of zero
metres. TA on 5G NR is not converted to distance, so H6 is `N/A` there.

**Channel sanity (H8).** Impossible or malformed channel values fail, using technology-specific
limits. When the modem does not report the frequency at all, H8 is `N/A`: a missing value is not an
impossible one.

**Ciphering visibility (H9).** Regular Android apps cannot inspect the modem's ciphering state, so H9
is always `N/A`. ICdetection does not claim null-cipher or IMSI-disclosure detection.

**Ping-Pong (H10).** Three changes of the full serving identity (MCC, MNC, TAC, Cell ID or
technology) within 10 s while the phone is not moving fast. Without a GPS speed it cannot tell
whether you are stationary, so it reports `N/A`.

**Geographic consistency (H11).** The phone's own GPS history can reveal a Cell ID appearing in
physically inconsistent places. Geographic evidence passes a GNSS continuity gate: a displacement
implying high speed stays provisional until a second, genuinely newer fix continues coherently, so a
single bad fix is not evidence. Operator reuse, database errors and parsing limits must also be
considered.

**Regional, latency and external correlation (H12 and others).** These depend on reliable location,
tower-database results, network reachability and enough samples, and may legitimately remain `N/A`.

**Band downgrade (H14).** Suspicious moves from high-frequency capacity bands to sub-GHz bands when
the previous signal was strong and there was no progressive degradation. Every 3GPP LTE band is
known; outside the table the band declared by the phone (Android 11+) is used. Band changes between
cells of the same LTE base station (eNodeB) are not penalised, and NR → NR downgrades on 5G SA are
evaluated from the exact NR-ARFCN frequency. Coverage optimisation, congestion, indoor movement and
operator policy can produce similar transitions.

**RF identity / PCI (H15).** Repeated alternation between PCI values on the same carrier. A PCI run
counts as one episode however many samples Android reports, and a stable one-way change is handled
as a reconfiguration, not instability. Operator reconfiguration and modem reporting remain possible
explanations.

**Mobility sanity (H16).** See section 5.

---

## 9. History and retention

The app keeps three kinds of information:

- **Antenna history:** long-term observations used to understand cells and build baselines.
- **Incident history:** security-relevant episodes and their progression.
- **Forensic cases:** detailed evidence captured around qualifying incidents.

Closed incidents and completed or interrupted forensic cases can be deleted individually: expand the
item, choose delete and type exactly the word the dialog asks for — `DELETE` in English, `BORRAR` in
Spanish. Active incidents and captures cannot be deleted. Deleting a forensic case also removes all
its samples, so export it first if it may be useful.

**Delete history** (same confirmation word) removes antenna history, incidents, forensic cases,
transitions, service-state events, Stable-Site learning and route-familiarity trips in one
all-or-nothing operation. It is safe while monitoring is running: the service closes the open
forensic case, discards pending writes and in-flight verifications, and restarts its baselines and
episode tracking from a clean state.

Retention and the forensic-sample cap run when monitoring starts and then about once every 24 hours.
The History screen loads the 2,000 most recent rows to keep memory bounded; **EXPORT CSV** still
streams the complete retained database. Export important information before clearing app data or
uninstalling.

---

## 10. Forensic capture

When an episode reaches `1/3`, ICdetection opens a forensic case that preserves context, not only the
final alert. A case can contain:

- up to about 60 seconds before the first suspicious cycle;
- the progression through `1/3`, `2/3` and `3/3`, when reached;
- about 60 seconds after recovery (30 minutes at most in total);
- serving and observed neighbour cells, with RSRP, RSRQ, SINR, PCI, channel, TAC, TA and technology;
- device position and GPS accuracy when available;
- external-verification state and latency context;
- threat score, temporal phase and rule diagnostics for each cycle;
- device capabilities and unavailable-data explanations;
- relevant terminal output;
- app version, Android version and device model.

It never stores API credentials, IMSI, IMEI or the phone number.

### Silent trust-contradiction cases

If a complete cell identity previously `ESTABLISHED` moves to `CHANGED` with concrete trust
contradictions, ICdetection opens a neutral observation case. Its identifier starts with `ICD-OBS-`
and the history labels it **Contradiction in established cell · observation case**. This is evidence
preservation, not an alarm. Staying in `CHANGED` does not create repeated cases; returning to
`ESTABLISHED` and changing again is a new transition. If temporal detection begins while the case is
active, the case is promoted and continued without a duplicate.

### Forensic states

- **CAPTURING:** the suspicious episode is still being recorded.
- **POST_CAPTURE:** the condition recovered and the post-event window is being collected.
- **READY:** capture completed normally and can be exported.
- **INTERRUPTED:** monitoring ended before normal completion; partial evidence is kept.

Do not describe an interrupted package as a complete continuous recording.

---

## 11. Exporting a forensic case

Select a completed case and use **EXPORT FORENSIC CASE**:

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
|---|---|
| `case.json` | Case metadata, state, timing, summary and device/app context |
| `timeline.csv` | Chronological measurements and analysis state |
| `cells.csv` | Serving and neighbour-cell observations |
| `heuristics.csv` | Rule state and explanation by cycle |
| `capabilities.json` | Data the device could and could not provide |
| `terminal.log` | Relevant diagnostic log entries |
| `SHA256SUMS.txt` | SHA-256 digests to detect later modification |

To verify an extracted package on Linux or WSL:

```bash
sha256sum -c SHA256SUMS.txt
```

Every file should report `OK`. Verify a copy and keep the original ZIP unchanged. The checksum only
shows that the files match their recorded digests; it does not establish who collected them, prove
the collection time, create a legal chain of custody or prove that an IMSI catcher existed.

---

## 12. Exporting antenna history

The search field beside **EXPORT CSV** filters cells by CID, MCC, MNC, TAC, PCI, ARFCN or radio
technology. Expand a cell to see its **GPS locations**, **handovers** (incoming and outgoing routes,
frequency, confidence, state) and **technical information** (identity, radio parameters, latest
metrics, Timing Advance, verification, scores, sample count and observation range).

**EXPORT CSV** exports long-term observations. Its columns are:

```text
Timestamp, NetType, CID, MNC, TAC, MCC, DBM, Verified, SecurityScore, FailedHeuristics, Lat, Lon,
PCI, ARFCN, RSRQ, SINR, AnomalyConfidence, ApiLat, ApiLon, TA, TAUnit, TAMeters, Radio,
ServingConnection, BandwidthKHz, Bands, AdditionalPlmns, CsgIndicator, CsgIdentity, CsgName,
SecondaryCarriers, ServiceState, NetworkOperator, SimOperator, NetworkRoaming,
ObservedAtUtc, NotEvaluatedHeuristics, GpsAccuracyM, AppVersion, ExportDevice, ExportAndroid,
LocationMode
```

- `Lat` / `Lon` are the phone's own GPS position at the time of the observation; `ApiLat` / `ApiLon`
  are coordinates returned by OpenCellID.
- `Timestamp` is local time; `ObservedAtUtc` is the observation instant in UTC (ISO-8601).
- `TA` is the raw Timing Advance, `TAUnit` how it was interpreted, and `TAMeters` is empty when a
  conversion is not defensible.
- `Radio` comes from Android's cellular information, not only the status-bar label.
- `FailedHeuristics` entries marked `[sub-umbral]` failed without reaching the alarm threshold; they
  are kept on purpose so false positives can be studied (shown as `[sub-threshold]` in English).
- `AnomalyConfidence` is uncalibrated analytical output, not a measured probability.
- `NotEvaluatedHeuristics` lists the rules that could not be evaluated (`H1;H6;H9`), or `NONE`. A
  rule listed here neither passed nor failed.
- `GpsAccuracyM` is the accuracy of the row's fix in metres; filter large values before analysing
  geometry.
- `AppVersion` is the version that recorded the row; use it to apply dataset cuts per row.
- `ExportDevice` and `ExportAndroid` describe the phone that made the export (manufacturer, model,
  Android version), with no personal or hardware identifier.
- `LocationMode` is `CONTINUOUS`, `INTELLIGENT` or `ADAPTIVE`: the location mode the row was observed with.
  Compare position-based rules per mode before joining data.

Columns that did not exist when a row was recorded are empty — unknown, never an assumed value.

Validate a CSV from the project directory with:

```bash
python3 tools/check_export.py path/to/export.csv
```

The validator checks the invariants the design guarantees, applies technology-specific physical-ID
ranges, and reports calendar coverage, the evaluation coverage of each rule, GPS accuracy, app
versions and the dataset cuts the file crosses. It warns when a file joins several phones.

Exports can contain precise locations, network identifiers, device information and security
observations. Redact sensitive information before sharing publicly.

---

## 13. Responding to a credible alert

If an event persists to `3/3` and remains concerning after reviewing its explanations:

1. **Do not panic.** It is an anomaly classification, not attribution.
2. **Preserve context.** Do not clear history, storage or the forensic case.
3. **Use Airplane Mode** if immediate disconnection is appropriate. Automatic activation needs the
   privileged `WRITE_SECURE_SETTINGS` permission granted through ADB; without it, the app posts a
   security notification that opens Android's Airplane Mode settings.
4. **Move to another safe location** if useful, and observe whether the anomaly follows the phone or
   stays local.
5. **Export the forensic case** once it is `READY`; keep an `INTERRUPTED` case as partial evidence.
6. **Preserve the original ZIP** and record the circumstances and actions taken.
7. **Seek corroboration** from another device, the operator, another data source or qualified radio
   analysis.

---

## 14. Privacy and evidence handling

ICdetection data may reveal locations, travel patterns, observed networks, device characteristics and
security events.

- Preserve original exports and analyse copies.
- Record the SHA-256 digest of the original ZIP.
- Do not edit files inside the original package.
- Use encrypted storage for sensitive cases.
- Redact GPS coordinates and credentials before public sharing.
- Never publish API tokens, signing keys or private keystores.

Formal evidential use normally requires documented acquisition, preservation of originals, an
auditable chain of custody, trustworthy time evidence, corroboration and expert interpretation.
Obtain appropriate legal and technical advice; the forensic export assists an investigation but does
not satisfy legal requirements by itself.

---

## 15. Technical limitations

Because ICdetection runs in Android user space without root:

- It cannot access raw baseband, RRC or NAS signalling.
- It cannot inspect active cellular ciphering or reliably observe IMSI disclosure.
- Timing Advance is unavailable, ambiguous or stubbed on many phones.
- Neighbour-cell reporting differs by device and operator.
- GPS can be inaccurate, unavailable indoors or throttled in the background.
- Public tower databases can be incomplete, delayed or wrong.
- Carrier aggregation and modem behaviour can make identities appear unstable.
- Operator-side lawful interception is not visible from these observations.
- A sophisticated IMSI catcher may expose no anomaly through public Android APIs.

ICdetection provides leads, historical context and structured evidence about behaviour visible to
Android; it cannot guarantee detection.

### What your own data can and cannot show

- **No ground truth:** your history lets you measure how often warnings appear and study possible
  false positives, not detection. A clean history does not prove that nothing happened, and an alert
  does not prove that something did.
- **No negative control:** with one phone you cannot tell a network anomaly from a modem fault.
  Comparing with a second phone at the same place is the strongest check you can make.
- **Your coverage only:** results describe your phone, your operator and your routes; thresholds may
  behave differently elsewhere.
- **Coverage matters:** `NotEvaluatedHeuristics` shows when each rule could not run. Read a rule's
  "passed" rate together with how often it was evaluated.

---

## 16. Troubleshooting

**Most rules show `N/A`.** Check permissions, GPS, API configuration, connectivity and the capability
explanations, and allow time for baselines to mature. Some measurements stay unavailable on a given
modem; H3, H4 and H5 in particular remain `N/A` on modems that do not report neighbour identities.

**Every neighbour shows the same number** (for example `268435455`). Your modem fills neighbours with
placeholder values, as some MediaTek modems do. ICdetection reads them as unavailable, but such
devices are less reliable; see the compatibility notes in the [README](README.md).

**A rule alternates between `N/A` and `PASS`.** Its input is intermittent. Check GPS, neighbour
reporting, latency or API availability. This alone is not a threat.

**A cell is `NOT FOUND`.** Public databases do not contain every cell. Check whether independent rules
fail, whether the condition persists and whether local history stays consistent.

**No forensic case appears.** A case starts at the first suspicious phase. Confirm that monitoring is
running and check incident history; normal observations do not create cases.

**A case is `INTERRUPTED`.** The service stopped or restarted before capture completed. The partial
case is kept honestly and is never relabelled as recovered.

**Checksum verification fails.** A file changed after export, or extraction was corrupted. Keep the
original ZIP, extract a fresh copy and verify again.

---

## 17. Responsible use

Use ICdetection only for lawful, defensive, educational and research purposes. Respect local laws,
privacy, operator terms and restrictions on network monitoring.

When reporting bugs, give enough technical detail to reproduce the issue, but remove personal
locations, credentials, tokens and unrelated third-party information.

---

**Stay observant. Verify context. Preserve evidence carefully.**

*Developed by Alexis Gómez Rodríguez.*
