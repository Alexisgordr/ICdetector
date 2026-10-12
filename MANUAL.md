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

**First start (3.0).** A new install first asks *"Do you want to restore a backup?"* with two
options, **Restore a backup** and **Start from scratch**. Nothing is recorded until you choose: the
app does not start monitoring, and the monitoring service refuses to start by any other route.
- *Restore a backup* checks the file (see [Backup and restore](#backup-and-restore-30)), restarts
  the app and restores it. If you cancel the file picker, or the restore fails, you are back at the
  question and nothing has been decided.
- *Start from scratch* begins with empty data. The answer is stored only after checking that
  every database, file and setting was really deleted; if not, you stay on the question and can
  try again.
- If the phone already has ICdetection data that did not come from this install (for example,
  copied by a transfer tool), the question says so: starting from scratch deletes that data,
  restoring replaces it, and a failed restore keeps it intact.
The answer is stored only after the restore has finished and been verified, or after the deletion
has finished; an interruption brings the question back. Installs that already existed before this
version are not asked.

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
- **GPS indicator:** the main screen shows **● GPS OK** when the analysis has a usable position
  (GPS on, fix younger than 2 minutes, accurate and consistent) and **● NO GPS** (*SIN GPS*)
  otherwise. It is checked every 5 seconds while the screen is visible and never turns the GPS on.
  In Smart and Adaptive it stays OK between attempts while the last fix is still usable. With
  monitoring stopped it shows NO GPS.
- **Location mode:** **Settings → Location mode**. Names and descriptions follow the app language:
  *Continuo / Continuous*, *Inteligente / Smart*, *Adaptativo / Adaptive*.
  - **Continuous** (default): the existing GPS stream remains active with the screen on or off.
    Use it for field campaigns where location continuity matters. Failed normal precise-fix
    requests retain their existing backoff; forced requests retain their existing behavior.
  - **Smart**: attempts a GPS fix roughly 45 seconds after the previous successful fix or the
    end of an unsuccessful attempt. A probe stops on success or after 20 seconds; the first
    saving-mode probe after service startup gets 45 seconds for acquisition. Requests caused by
    suspected anomalies or screen events share a minimum 30-second interval and cannot overlap.
    **Handovers:** a change to a cell not seen in the last 10 minutes always wakes the GPS: it opens
    or extends one continuous window until 60 seconds after that change, also with the screen off,
    so the GPS stays active during a journey; a recent fix never skips that window. With poor
    reception, a new cell gets a short 20-second attempt instead, without waiting for the 2-minute
    limit, and only if the last attempt of any kind started at least a minute earlier: at most 20
    seconds of searching per minute, so a run of new cells (a train in a tunnel) cannot keep the
    GPS searching. Every attempt that actually starts counts towards one shared limit. A change back to a cell seen in the last 10 minutes (the phone hopping between
    two cells while you are still) does not open or extend the window; it reuses a fix younger than
    45 seconds and otherwise asks for one. This keeps the battery saving at rest. Cells are compared
    by full identity and all times use the monotonic clock.
  - **Adaptive**: retains continuous GPS with the screen on while reception is usable. With the
    screen off, it requests fixes on relevant events, at most once a minute.

  **Poor reception:** Smart and Adaptive pause repeated acquisition after three failed probes
  (or Smart windows without any new live fix). Either saving mode also stops a continuous stream
  after three minutes without a new usable GPS fix; further handovers cannot extend that search.
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

ICdetection uses three notification channels, which you can configure separately in
**Settings → Apps → ICdetection → Notifications**:

| Channel | What it shows | Sound |
|---|---|---|
| **Monitoring** | The persistent notification (cell, signal, verification), updated every few seconds | Always silent, whatever the channel setting |
| **Security alerts** | A confirmed network anomaly, once per episode | Keep it alerting so you hear an alarm |
| **2G/3G network** | Use of or change to 2G/3G, once per episode (see below) | Silent unless you choose *Notification and sound*; then as this channel and Android allow |

A confirmed alarm sounds only through its *Security alerts* notification, once per episode; there is
no extra tone repeating while the alarm lasts. If notifications are off, or *Security alerts* is
silenced or has its sound set to *None*, the main screen shows **ALARMS ARE MUTED** with a button that
opens that channel's settings. The *2G/3G network* channel is independent: silencing one does not
silence the other, and the warning only looks at *Security alerts*.

Two other cues are short tones at notification volume (they respect silent, vibrate and Do Not
Disturb): the very-strong-signal warning (at most once a minute) and, if latency detection is
enabled, a persistent latency anomaly.

### 2G/3G network alerts

**Settings → 2G/3G network alerts** chooses what happens when the phone starts using a 2G or 3G
network: **Off**, **Notification only** (default, silent) or **Notification and sound** (only if you
choose it). Using 2G/3G is not a confirmed attack: it also happens with weak 4G/5G coverage or during
calls without VoLTE, and it does not change the score, rules or alarms. There is no automatic
exception during calls.

The alert is given **once per episode**. An episode starts with the first 2G/3G reading (marked as a
*change from 4G/5G* when the previous reading was 4G/5G) and stays open while the phone keeps using
2G/3G, including short returns to 4G/5G and moments without service. It closes after **1 minute in
a row** on 4G/5G with service: a 2G/3G reading or a loss of service (no active cell, Airplane mode)
restarts that minute without closing the episode. After it closes, the next 2G/3G reading starts a
new episode. Each episode leaves one line in
the terminal, also with alerts off. The terminal line and the notification also show the **last earlier signal observed** before
the episode, as context only; it is not scored, and it may not be the reading immediately before (its
age is not kept). A missing or invalid value reads *earlier signal unknown*. The notification has an
**Airplane mode settings** button; the
app never switches Airplane mode on by itself. Some devices also offer an **Allow 2G** setting (often under
*Network & internet → SIMs*); turning it off avoids 2G but not 3G. Its presence and location depend
on the manufacturer, Android version and operator, so the app does not link to it. The setting travels in backups; restoring a backup made before
it existed sets *Notification only*.

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

**Viewing it in the app.** **History → Topology → VIEW STABLE-SITE** shows what Stable-Site has
learned, read only, in two levels:

- **Plain view.** Each place shows *Learning this place* (`BOOTSTRAP`/`LEARNING`), *Reference being
  prepared* (`SHADOW_READY`), *Reference prepared* (`ACTIVE`) or *Not enough data* (`UNAVAILABLE`),
  with the requirement still missing and the line *Analysis only: it does not change alarms*. A
  prepared reference is not a protection: Stable-Site stays in shadow mode. The detail lists the
  requirements (days connected, days still, days with nearby cells, observations of the connected
  cell) as met or pending against the same constants as the maturity policy; it is not a countdown.
- **View technical details** (collapsible): stored key (no names, no coordinates), internal state
  and reason codes, neighbour capability, progress bars, dates, full-identity cells and RF
  fingerprints (local context, not cell identities). Nothing is removed; only the layout changes.

Cell changes in a place are its episodes. *Observed change* means the cell changed;
*Novelty recorded in shadow* means it was marked as a novelty at some point of the episode (no
alarm). That mark is kept for the whole episode and is shown apart from the **last saved
evaluation**, whose date, place state at that moment, reason and movement are translated as stored;
missing values read *Not enough information*. None of this names a cause or is an alarm.

**Routes.** Tapping a route in Topology opens its own detail view (back with the arrow or the
phone's back) with **Stable-Site episodes of this route**, paged per site with *Load more*:
episodes with the same stored previous and new cell (A→B) that start no later than the last time the
route was seen (a 2-minute query margin, not proof of association). They relate to the route as a
whole: a route is an aggregate without the time of each handover, so an episode cannot be attributed
to one specific transition. Different sites are listed apart; episodes without a stored site appear as
*No associated site*; with no match the detail says *No associated Stable-Site context*.

### Local profile (shadow mode, experimental)

The local profile learns how the serving signal of each cell usually looks **on this phone** and
records, for every saved history row, how far the observation is from it. It complements H1–H16
without changing them. It is not an IMSI-catcher detector and does not resist evasion: a transmitter
present while the profile learns, or one that imitates the usual signal, looks normal to it.

**What it learns.** Per cell (MCC, MNC, TAC, Cell ID and radio technology): the mean and spread of the
serving level (`DBM`) and, on LTE/NR, the quality (`RSRQ`). Everything else is fixed in the code and
identical on every phone. The detector's own score, alerts and rule results are never used for
learning.

**How learning is protected.**

- Missing, impossible, older than 30 days or future-dated readings are ignored, as are repeats.
- At most 48 samples a day, so one long session weighs no more than a day.
- A reading more than 4 spreads away from a formed reference is quarantined (counted, not learned).
- The mean and the spread can shift by at most one noise floor (3 dB for `DBM`, 1.5 dB for `RSRQ`)
  per day.

A burst of unusual readings therefore cannot redefine normal in a day. A slow change sustained over
many days is slowed, not prevented.

**Maturity.** A reference is used only after 30 samples on 3 different days. After 30 days without
new evidence it becomes *stale* and starts learning again. **Maturity is not validation**: it means
the phone has seen enough of that cell, not that the method has been shown to detect anything.

**Shadow mode (always on).** Each saved row is first evaluated against the existing reference and
then learned. About 1 observation in 10 is kept for evaluation only, so held-out results stay honest.
On first use, the profile also learns from the last 30 days of history (never from a restored
backup), holding out the most recent 20%. Nothing reaches the score, confirmation, alerts, trust or
forensic cases; if the profile fails, the row is saved unchanged and, with the option on, Settings
shows the error.

**Levels.** Deviation = `|value − mean| / spread`; the largest one sets the level: below 2 `NONE`,
below 3 `LOW`, below 4 `MODERATE`, otherwise `HIGH`. These cut-offs are conventions, **not
calibrated**: a deviation is not a probability and `HIGH` is not an alert. When it cannot judge
(incomplete identity, no or immature or stale reference, another phone model, technology or
algorithm version, no usable metric) it reports `ABSTAIN` with the reason.

**Experimental option.** *Settings → Local profile (experimental)* is off by default and can be
turned off at any time. When on, it only shows the latest evaluation, its time and cell, and how many
references are usable now, marked *not an engine decision*. It cannot add, silence or lower an alert,
or skip `1/3 → 3/3` confirmation. Every evaluation stores its mode, and every change of the option is
logged with time and app version.

**Viewing evaluations.** With the option on, *Settings → Local profile → VIEW EVALUATIONS* (or the
**Local profile** tab in History) lists the stored evaluations, newest first, 50 at a time: normal
observations, signal deviations and abstentions, filterable by level. The detail shows only what was
stored at the time — the reference used comes from the saved evidence, and when it was not stored the
view says so; the current profile is never used for past evaluations. It also shows the linked
history observation and what the saved row tells about H13 (failed, not evaluated, no failure
recorded, or unknown before 3.0); H13 and the profile look at the same signal, so agreement is not
independent confirmation. Nothing in this view raises alerts or changes any result.

**Reset.** **RESET LOCAL PROFILE** deletes only what was learned; history, alerts and past evaluations
are kept, and learning starts again. Use it after restoring a backup from another phone model, or
after a major change of surroundings.

**Backups.** The option is not saved in backups, and restoring one always turns it off (logged as
`OFF`). References learned on a different phone model stay inactive until you reset the profile;
on the same model they keep working but are as old as the backup.

**Comparing results.** Schemas 22 and 23 only add tables and columns, so existing data is kept.
Older rows have empty profile columns. Each reference carries a *generation* (new after a reset,
staleness or algorithm change), so evaluations of different references are never confused. Compare
evaluations only within the same `ProfileAlgorithm`; references from algorithm 1 are relearned
automatically.

**Before widening its role.** Extra alerts would need evidence the app cannot produce on its own:
held-out deviations across several phones, models and places; a false-positive rate per level in
normal use; the same analysis with a known disturbance; and a calibration. Until then the algorithm
is marked as not validated and the option stays display-only.

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

- **VERIFIED** (shown as *REGISTERED*): a configured external source returned a compatible record.
  Context, not proof of legitimacy: a cloned identity can also be registered. The label is re-checked after 30 days.
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
routes, total handovers and **learned** routes. A handover is *learned* when it passed H16 with two
contemporaneous, precise GPS fixes and was added to the local route reference; this is a mobility
criterion, not proof that either cell is legitimate. Each route keeps its full origin and
destination identities, observation count, learned count, last H16 state, learned ratio and most
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
   the confirmation and posts a **Network anomaly confirmed** notification once per episode on the
   *Security alerts* channel, which sounds as that channel allows (there is no separate tone).

A single or weak anomaly needs three distinct modem observations. If two or more high-value checks —
geographic consistency (H11), signal baseline (H13), RF identity stability (H15) and transition
coherence (H16) — fail in the same observation, two are enough. These checks are not fully
independent (H11 and H16 share the GPS fix; see *Shared evidence* in section 8). Repeated delivery of
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

**Timing Advance proximity (H6).** When Timing Advance has a known unit, H6 checks proximity only:
it fails when TA implies about 100 m or less while the signal is −60 dBm or stronger. It does not
compare TA with a tower position: no tower coordinates reach the score (public-database coordinates
are only displayed as a distance), and the phone's own position is never used as the tower's. A missing TA is only taken from a duplicate of the same cell (same
technology and physical cell), never from another transmitter. Many modems omit TA or always return
zero; that "always 0" behaviour is detected per technology and never read as a real distance of zero
metres. TA on 5G NR is not converted to distance, so H6 is `N/A` there. Only values in range are
used: LTE index 0–1282, GSM index 0–219 (also when converting older rows). LTE TA comes only from
Android's getter; the app no longer reads it from the manufacturer's text, so some phones show less
TA than before. NR TA comes from Android's public getter (Android 14+) and is kept as raw context;
a value read from text, only for NR, is marked `UNKNOWN`.

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
considered. When the cell is far from where it was seen before, H11 adds severity for a different
PCI/PSC or channel only if both values are valid for the same technology; an unknown, invalid or
"not available" value is not a difference. Carrier aggregation is not handled specially here: the
modem can attribute a secondary carrier's channel or PCI to the serving cell, which can still look
like a valid difference. That limit is documented for a separate review.

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

### Shared evidence: when two results are not independent

Some modules read the same inputs or learn from the same history rows. When they agree, that is one
fact seen twice, not two confirmations. This beta only documents the overlaps; it changes no weight,
score or rule.

| Modules | What they share | Effect today |
|---|---|---|
| H13 and the local profile | The serving level (and RSRQ) of the same cell, compared with references learned from the same clean history rows. | None on the engine: the profile is shadow or display only. In *History → Local profile* the H13 line says agreement is not a confirmation. |
| H13 power and H13 RSRQ/SINR fingerprint | One cell's signal history. | Already guarded: they share one flag, and the fingerprint only counts when the power check did not fail. |
| H11 and H16 | The current GPS fix and the positions stored for the cell(s) in history. A wrong fix, or a learned zone that is off, can fail both. | **Possible double counting.** Both are in the list of strong failures that shortens temporal confirmation from three phases to two, while the episode tracker already puts both in the same *mobility* family. |
| H11, H15 and local cell confidence | PCI/ARFCN values of the same cell: H11 adds severity when PCI/ARFCN differ far away, H15 looks for PCI alternation, local confidence lists PCI/ARFCN as contradictions. | **Possible double counting** of one PCI change across three outputs. Local confidence still requires a separate geographic or handover contradiction (H11 or H16) to raise its case. |
| H13 and H15 | Same history pool of the cell (they learn only once the cell is established). | Different measurements (power versus PCI); a shared blind spot if the pool was learned in a contaminated period. |
| Stable-Site and local cell confidence | Stable-Site site state. | None while Stable-Site stays in shadow mode (the `SITE_UNVERIFIED` path is disabled). |

The two "possible double counting" rows are reported for a later, data-backed decision; no behaviour
was changed in this phase.

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
LocationMode, ProfileMode, ProfileLevel, ProfileScore, ProfileCoverage, ProfileAbstainReason,
ProfileAlgorithm, ProfileRevision, ProfileEvidence, ProfileLearned, ProfileHoldout
```

- `Lat` / `Lon` are the phone's own GPS position at the time of the observation; `ApiLat` / `ApiLon`
  are coordinates returned by OpenCellID.
- `Timestamp` is local time; `ObservedAtUtc` is the observation instant in UTC (ISO-8601).
- `TA` is the raw Timing Advance, `TAUnit` how it was interpreted, and `TAMeters` is empty when a
  conversion is not defensible.
- `Radio` comes from Android's cellular information, not only the status-bar label.
- `PCI` and `ARFCN` are empty when Android reported "not available". Rows recorded before this
  change (see `AppVersion`) may contain `2147483647` instead; read it as empty. Other out-of-range
  values are real readings and are kept.
- `NetType` for an NR cell is `5G NR`; the presence of NR alone does not prove standalone (SA), so
  earlier rows labelled `5G NR (SA)` mean the same thing.
- GSM has a physical identifier (BSIC) in Android, but the app does not record it; `PCI` stays empty
  for GSM.
- `FailedHeuristics` entries marked `[sub-umbral]` failed without reaching the alarm threshold; they
  are kept on purpose so false positives can be studied (shown as `[sub-threshold]` in English).
- `AnomalyConfidence` is uncalibrated analytical output, not a measured probability.
- `NotEvaluatedHeuristics` lists the rules that could not be evaluated (`H1;H6;H9`), or `NONE`. A
  rule listed here neither passed nor failed.
- `GpsAccuracyM` is the accuracy of the row's fix in metres; filter large values before analysing
  geometry.
- `AppVersion` is the version that recorded the row; use it to apply dataset cuts per row. From
  3.0 it also names the build: `versionName+commit`, for example `3.0.0-beta4+6bb5ed3`, so rows
  from different builds of the same beta can be separated. `-dirty` means the build had
  uncommitted changes; `nogit` means the commit could not be known (built without git and not from
  a `git archive` ZIP). The part after `+` does not change the version.
- `ExportDevice` and `ExportAndroid` describe the phone that made the export (manufacturer, model,
  Android version), with no personal or hardware identifier.
- `LocationMode` is `CONTINUOUS`, `INTELLIGENT` or `ADAPTIVE`: the location mode the row was observed with.
  Compare position-based rules per mode before joining data.
- `Profile*` columns hold the [local profile](#local-profile-shadow-mode-experimental) evaluation,
  independent of `SecurityScore`. `ProfileMode`: `SHADOW` or `EXPERIMENTAL_DISPLAY`. `ProfileLevel`:
  `NONE`, `LOW`, `MODERATE`, `HIGH` or `ABSTAIN` (reason in `ProfileAbstainReason`). `ProfileScore`:
  largest deviation, uncalibrated. `ProfileCoverage`: metrics evaluated / available.
  `ProfileRevision`: reference used (`SIGNAL_DBM@g<generation>:<revision>`). `ProfileEvidence`: value,
  mean, spread and deviation per metric. `ProfileLearned` / `ProfileHoldout`: learned afterwards, or
  kept for evaluation only — analyse holdout rows separately, and never mix `ProfileAlgorithm` values.

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
3. **Use Airplane Mode** if immediate disconnection is appropriate, from Quick Settings or Android's
   settings. ICdetection never turns it on by itself and requests no privileged permission; the 2G/3G
   notification has a button that opens Android's Airplane mode settings.
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
- Android's automatic cloud backup and phone-to-phone transfer exclude every ICdetection file, so
  setting up a new phone does not bring the history with it. A vendor transfer tool that ignores
  Android's rules cannot be ruled out.

Formal evidential use normally requires documented acquisition, preservation of originals, an
auditable chain of custody, trustworthy time evidence, corroboration and expert interpretation.
Obtain appropriate legal and technical advice; the forensic export assists an investigation but does
not satisfy legal requirements by itself.

### Backup and restore (3.0)

**Settings → Backup** saves all ICdetection data in one `.icdbackup` file: history, antennas and
their learned trust, incidents, forensic cases, Stable-Site, trips, and the language, location mode,
proxy and latency settings. The OpenCelliD key is never included. The file is **not encrypted** and
contains your location history: keep it private. The backup is consistent even while monitoring
is running.

**When to use it.** A backup is meant to **move the data right away**: to a new phone, or to put
it back on the same phone after a reset or reinstall. Make it just before, restore it just after.
It is not a long-term archive. Restoring a backup kept for weeks or months can cause problems:

- It **replaces everything** recorded since the backup was made; those days or months are lost.
- What the app had learned (trusted antennas, reference values, Stable-Site, trips) is as old as
  the backup. Operators change antennas and frequencies, so old trust and references can produce
  false alerts or miss real changes until they are relearned, and parts older than the rules'
  windows (for example the 30 days of H11) are ignored anyway.
- The history has a gap for the whole time the backup was stored, which matters if you analyse
  the data.
- A future version may no longer accept a backup that old (for example after a format change).
- The longer the file is kept, the longer your location history sits outside the phone.

Restoring turns the local profile's experimental option off, and references learned on a different
phone model stay inactive until reset (see [Local profile](#local-profile-shadow-mode-experimental)).

To keep data for a long time, use the **exports** (history CSV, forensic cases, topology,
geometry, Stable-Site): they are read-only and meant for analysis and archiving.

**Restore** replaces all current data. Step by step:

1. Choose a backup. It is checked without touching anything: it must be an ICdetection backup in a
   known format, made by 3.0 or later (every 3.0 beta counts), with an intact database and a
   database version not newer than the app. Backups from v2.10.x are refused even though they could
   be migrated: 3.0 data is not mixed with the field-campaign data.
2. Confirm. Monitoring stops in two phases: first no new readings are analysed and the ones
   already accepted finish, together with everything they write (history, incidents, forensic
   samples); then every remaining write finishes. If that cannot finish, the restore does not
   start and nothing changes.
3. Choose where to save a copy of your current data (for example Downloads or a cloud folder, so it
   survives uninstalling the app). If you cancel or the copy fails, nothing is replaced.
4. The app restarts and installs the backup before anything opens the database. If anything goes
   wrong before the new database is verified, including a power cut, your previous data is put
   back intact. Once it is verified it is never undone: a failed cleanup is retried at the next
   start. If the app cannot tell which state the files are in (for example, an unreadable restore
   journal), it touches nothing, keeps monitoring blocked and says the restore is pending. A
   message tells you how it ended and the name of the copy of your previous data.

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

### Wi-Fi and rule coverage

While Wi-Fi is connected, five rules abstain (`N/A`, never "passed"): **H1** (isolated cell),
**H2** (power jump), **H7** (ghost neighbours), **H12** (latency + RF) and **H13** (signal baseline and
RSRQ/SINR fingerprint). The isolated-cell confirmation count also restarts. All other rules keep
running, and nothing about Wi-Fi itself is scored. Each row records the abstentions in
`NotEvaluatedHeuristics`; the Wi-Fi state itself is not stored per row.

Why each exclusion exists, and how strong the reason is:

| Rule | Kind | Reason | Assessment |
|---|---|---|---|
| H12 | Latency | The latency probe would measure the Wi-Fi path, not the cellular link (the same applies to a VPN or the proxy). | **Required.** Without cellular data traffic there is nothing to correlate. |
| H1, H2, H7 | RF, neighbours | With data on Wi-Fi the modem usually stays idle on the cellular network; idle-mode neighbour reports are sparser and less frequent, which can look like an isolated cell, a power gap or weak "ghost" neighbours. | **Precaution, not validated.** The radio measurements still exist; the concern is reporting quality. |
| H13 | RF, own history | Most of the baseline is learned with the phone in connected mode; idle readings may follow a different distribution. | **Precaution, not validated.** |

Consequences: at home or at work on Wi-Fi, often for many hours, these five rules give no evidence,
so the strongest remaining signals there are H6, H8, H11, H14, H15 and H16 (when their own data is
available). H3–H5 also depend on neighbour reports but do not abstain on Wi-Fi; that inconsistency is
documented, not changed.

Conclusion for this phase: H12 must keep abstaining. H1, H2, H7 and H13 should only be enabled on
Wi-Fi after a specific validation — for example, logging what each would have decided on Wi-Fi in
shadow mode and comparing it with the same places on mobile data — so no rule is changed now.

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
- **What a row does not keep:** the reason each rule abstained (only which ones did), the age of the
  GPS fix (stale fixes are discarded before saving, but the age is not stored) and the time zone of
  rows before 3.0. The app shows these as "not stored" and never fills them in.
- **Stable-Site history:** an episode's *recorded in shadow* mark is cumulative (it stays set if any
  evaluation during the episode met the shadow conditions), while its reason, movement and site state
  belong only to its **last saved evaluation**. Topology routes are aggregates (counts, last state,
  last time), not a timeline of each handover, so an episode is linked to a route as a whole.

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
