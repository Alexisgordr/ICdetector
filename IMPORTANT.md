# ❄️ IMPORTANT — Field-collection campaign

> **Campaign status**
>
> | | |
> |---|---|
> | **Campaign build** | 3.1.x from GitHub Releases or F-Droid: 3.1.0 (version code 40) up to 3.1.4 (44); each installs over the previous one and changes no rule |
> | **Day 1** | A full reinstall of 3.1.x, with a fresh database |
> | **Database schema** | 26 |
> | **Duration** | Approximately three months |
> | **Previous stages** | 3.0.0, schema 25 (closed by 3.1.0; see [below](#previous-stage-300)); v2.10.x, dataset baseline v2.10.5, schema 19 (closed; see [below](#previous-stage-the-v210x-campaign)) |
>
> **The campaign restarts on 3.1.0.** 3.1.0 is a design update together with a security update that
> fixes bugs found after 3.0.0. Several of those fixes change how rules are evaluated, so data from
> 3.0.0 is not comparable with data from 3.1.0. I am sorry to restart the dataset once more: 3.1.0
> needs a full reinstall of the app.

ICdetection is in a **three-month field-collection and stabilization period**. The priority is no
longer adding features, heuristics, screens or new detection ideas. It is to **leave the methodology
stable, collect data, observe real behaviour, measure how often each rule warns, study possible false
positives and validate the current design over time.**

---

## Why a freeze

A detector cannot be meaningfully evaluated if its methodology changes continuously while data is
being collected. During the campaign the following will **not** be added:

- new scored detection heuristics (H17, added in 3.1.0 before this Day 1, only records data in
  shadow mode: it is never evaluated and never scores)
- new anomaly weights or detection thresholds
- major scoring changes or experimental detection features
- large architectural features that alter the dataset semantics

The campaign is meant to answer questions with evidence rather than with more code:

- How stable are the current heuristics in daily use?
- Which anomalies repeat, and which false positives remain?
- How does the detector behave across different locations and mobility patterns?
- How quickly do Local Cell Trust, Stable-Site and route familiarity mature?
- Does `PRIMARY_SERVING` handling produce cleaner geometry and handover history?
- How useful is the additional radio context when investigating unusual events?
- On devices without neighbour identities, how much do the remaining layers carry on their own?
- Which rules remain useful after weeks or months rather than only during short tests?

## What may still change

The project is frozen for features, **not abandoned**. Genuine bugs are still fixed when they affect
data integrity, collection continuity, database safety, privacy or security, compatibility, exports,
crashes, clearly incorrect behaviour, or false positives caused by an implementation defect.

Fixes preserve the dataset semantics whenever possible, and a bug fix is never an excuse to introduce
a new heuristic or redesign the detector. When a fix must change how a rule is evaluated, it is
documented as a **dataset cut for that rule only**, so data from before and after remains comparable
for everything else. No reset is needed.

### Dataset cuts during the campaign

**The campaign cut.** Made on the last 3.0 beta (3.0.0-beta4) and included in 3.0.0: at the start of
collection, H3, H8, H9, femtocell (CSG) cells and the anomaly-confidence prior were changed together;
before Day 1 the cut was completed with H10, H11 and the condition for the femtocell exemption. Unlike the bug fixes described above, this
changes how evidence is weighed; it was made once, early, so that the rest of the campaign runs on a
single method. [Status.md](Status.md#dataset-cuts-in-30) lists what changed and how to tell rows from
before and after it.

**The 3.1.0 restart.** 3.1.0 changed several rules at once (H6, H11, H12, H14, the Timing Advance
stub detection and the anomaly confidence; [Status.md](Status.md#dataset-cuts-in-310) lists them).
With that many cuts, a cut per rule would leave little comparable data, so the campaign restarts on
3.1.0 with a full reinstall instead.

Every later fix that changes how a rule is evaluated will be listed in [CHANGELOG.md](CHANGELOG.md)
and [Status.md](Status.md) as a cut for that rule. Each row records the app version and build commit
that observed it (`AppVersion`), so cuts can be applied per row.

---

## Required reinstall

3.1.0 needs a **full reinstall**. Updating over 3.0.0 is not the supported path for the campaign: it
would mix data recorded with both methods in one database.

> **How to reinstall:**
>
> 1. Export anything you want to keep: the history CSV and forensic cases (*History*), and, if you
>    like, a backup (*Settings → Backup*) as an archive of the 3.0.0 data.
> 2. Uninstall ICdetection.
> 3. Install the latest 3.1.x (3.1.4) from GitHub Releases or F-Droid.
> 4. On the first start, choose **Start from scratch**. Restoring a 3.0.0 backup would bring the old
>    dataset back.

Reinstalling also clears the settings and the internal state left by 3.0.0 (for example the stored
Timing Advance evidence and the removed OpenCellID and proxy settings), so every phone starts the
campaign from the same point. Set the location mode again after installing.

> **Day 1 = 3.1.0, schema 26, a full reinstall and a fresh database.**

**No Internet.** From 3.1.0 the app works without Internet and does not declare Android's Internet
permission. Orbot (the Tor/SOCKS proxy), latency detection (H12) and the OpenCellID lookup are gone:
nothing the campaign records leaves the phone unless you export it.

---

## Previous stage: 3.0.0

Collection restarted on 3.0.0 (schema 25) after the third full audit of the app, with the campaign
cut described above. 3.1.0 closes that stage. Its data stays valid for what it measured and is not
comparable with 3.1.0 data; each row's `AppVersion` tells them apart.

---

## Previous stage: the v2.10.x campaign

The first collection stage ran on v2.10.x with v2.10.5 as Day 1 (schema 19). v2.10.4 had been
announced as the freeze release, but a final review found a few data-integrity and stability bugs,
so v2.10.5, which changes no heuristic, weight, threshold or schema, became the real starting
point. Its data stays valid for what it measured and is not comparable with 3.0 data. The notes
below are kept for anyone analysing it.

### Dataset cuts during the v2.10.x stage

| Release | Rule | Change | Earlier data |
|---|---|---|---|
| v2.10.6 – v2.10.8 | — | Interface, localization, notifications and stability only | Fully comparable |
| v2.10.9 | H10 Ping-Pong | Only cell changes within the last 10 s count, and H10 is `N/A` without a GPS speed. The rule itself is unchanged. | Treat `Efecto Ping-Pong` results before v2.10.9 with care |
| v2.10.10 | H8 Frequency | An unavailable frequency gives `N/A` instead of failing (−15). A measured out-of-range value still fails. | Treat H8 failures before v2.10.10 with care, especially with `ARFCN` = `2147483647` |

The H10 fix was worth a cut during the campaign: left in place, the defect would have biased every
H10 result, kept baselines from learning and made false alarms easier — exactly what the campaign is
meant to measure.

**Data-integrity notes (v2.10.10).** No rule changes, but useful when comparing data:

- **Coordinates:** before v2.10.10, a late GPS fix could be written into an older row of the same
  cell, so rows recorded while the GPS was still acquiring may carry a later position. From
  v2.10.10 only rows from the last 2 minutes are filled.
- **Timestamps:** rows carry the time of the observation instead of the time of the write. The format
  is the same; the difference was normally under a second.
- **Confirmed alarms:** a second alarm episode in the same cell gets its own "confirmed alarm" row,
  which earlier versions skipped.

Full details of every release are in [CHANGELOG.md](CHANGELOG.md).

### Why v2.10.5 was the baseline

**Carried from v2.10.4.** Serving-cell selection prioritizes Android's `PRIMARY_SERVING` state;
secondary carriers stay available as radio context but cannot replace the primary because their
signal is stronger. If a declared primary has unusable telemetry, the cycle **abstains** instead of
substituting a secondary. H15 reasons about independent PCI alternation episodes and cannot freeze
itself in a false-positive loop. Stable-Site supports devices that report neighbour RF context
without a complete neighbour Cell ID, without pretending that a local RF fingerprint is a cellular
identity. The RADIO context records serving/secondary state, bands, bandwidth, additional PLMNs, CSG
and service-state changes, and never changes the threat score.

**Added in v2.10.5.**

- **Complete, atomic reset.** *Delete history* clears every learned table, route trips included, in
  one transaction; daily retention also runs in one transaction.
- **Database safety.** The app and the background service share one SQLite connection, so deleting
  history while the service writes can no longer fail with `database is locked`.
- **Honest neighbour identity.** Most modems report only frequency, PCI and power for neighbour
  cells. Their MCC/MNC used to be filled with your own operator's values, so H3 and H4 passed
  without comparing anything. They now report `N/A` when neighbours carry no identity, like H5.
- **Verification retry.** An unexpected OpenCellID error no longer leaves a cell `PENDING` until the
  service restarts.

**Dataset note.** The honest neighbour identity fix is the only change that affects how stored data
reads:

| Data | Before v2.10.5 | From v2.10.5 |
|---|---|---|
| H3 / H4 when neighbours carry no MCC/MNC | `PASS` (compared your network with itself) | `N/A` (nothing to compare) |
| `site_rf_neighbours.mcc` / `mnc` for new rows | Your operator's values, copied | `NULL` |
| Security score and anomaly confidence | — | Unchanged (a passing rule never contributed) |
| Devices that report neighbour identities | — | Unchanged |

---

## What the campaign can and cannot prove

These are design limits, not bugs, and they apply to every conclusion drawn from the data:

- **No ground truth.** No attack is known to have been recorded, which does not prove that none
  happened. The campaign measures how often each rule warns and lets possible false positives be
  studied; it cannot show whether the app catches a real IMSI catcher, nor prove that every warning
  was false. The synthetic `ScenarioTest` is not field evidence.
- **No negative control.** One phone observes at a time, so the data alone cannot tell a network
  anomaly from a detector or modem fault.
- **Limited coverage.** Mostly one phone, one operator and one area. Thresholds are not validated for
  rural areas, roaming, borders or other modem vendors.

The evidence can inform later reviews of thresholds and weights. It is not enough on its own to
recalibrate the likelihood ratios, which would also need data from real attacks.

---

## A note on the release history

There were many releases during development because real hardware kept exposing behaviour the
Android documentation does not describe: carrier aggregation, modem handovers, missing neighbour
identities, Timing Advance limits and vendor-specific reporting. I preferred to document and correct
those problems rather than carry them into the data. For the same reason the whole app was audited
before the campaign moved to 3.0: better to fix what the audit found before Day 1 than to carry it
through three months of data. The design and security fixes of 3.1.0 restart it once more, and I am
sorry for the extra reinstall. Thank you for your patience with the update rate.

ICdetection is an Android userland cellular anomaly auditor, not definitive proof of an IMSI catcher
or surveillance system. The goal of this stage is not to claim perfection, but to find out how well
the current design behaves when it is left alone long enough to accumulate meaningful evidence.

## The next three months

**Collect, observe, export, compare and learn.** No feature race, no heuristics added because they
sound interesting, no thresholds changed every few days. After the campaign I will review the
accumulated data, false positives, topology, Stable-Site maturity, Local Cell Trust, radio context
and forensic events before deciding what — if anything — should change next.

Thank you to everyone who has downloaded, tested, followed, reviewed or simply taken an interest in
the project — and for your patience while it matured.

— Alexis
