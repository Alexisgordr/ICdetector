# ❄️ IMPORTANT — Field-collection campaign

> **Campaign status**
>
> | | |
> |---|---|
> | **Dataset baseline** | v2.10.5 (version code 33) — Day 1 of the dataset |
> | **Campaign releases** | v2.10.x, bug fixes only (current: v2.10.10) |
> | **Database schema** | 19 |
> | **Duration** | Approximately three months |
>
> **3.0 is not part of the campaign.** The 3.0 betas change the database (schema 20), the inputs of
> several rules and how alarms are confirmed, so their data is not comparable with the campaign.
> They are the same app as the stable release (`com.alexisgordr.icdetector`): installing one on the
> phone that collects campaign data would upgrade and migrate its database. **Keep the campaign phone
> on v2.10.x.**

ICdetection is in a **three-month field-collection and stabilization period**. The priority is no
longer adding features, heuristics, screens or new detection ideas. It is to **leave the methodology
stable, collect data, observe real behaviour, measure how often each rule warns, study possible false
positives and validate the current design over time.**

v2.10.4 was originally announced as the freeze release. A final review before the campaign found a
few data-integrity and stability bugs, so they were fixed first. **v2.10.5 is the real starting
point**: it changes no heuristic, weight, threshold or schema — it only makes the data it writes more
trustworthy.

---

## Why a freeze

A detector cannot be meaningfully evaluated if its methodology changes continuously while data is
being collected. During the campaign the following will **not** be added:

- new detection heuristics or H17+ rules
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

---

## Recommended database reset

For a clean research dataset, start from a **fresh local database on v2.10.5 or later**. The reset
is recommended for dataset consistency, not required for normal app operation.

> **How to reset:** export anything you want to keep, then open **History → Delete history** and
> type `DELETE` (English interface) or `BORRAR` (Spanish interface) to confirm.
>
> This removes antenna history, incidents, forensic cases, transitions, service-state events,
> Stable-Site learning **and** the route-familiarity trip tables (`mobility_trips`,
> `mobility_trip_cells`, `mobility_trip_edges`) in a single all-or-nothing transaction.
>
> Uninstalling and reinstalling (or *Settings → Apps → ICdetection → Storage → Clear data*) remains a
> valid alternative, but it also removes app settings such as your OpenCellID API key.

The reason is methodological, not database corruption. Earlier versions did not always distinguish
Android's `PRIMARY_SERVING` and `SECONDARY_SERVING` cells during carrier aggregation, so a secondary
carrier could sometimes be treated as the active serving cell. Those cells, PCI values, ARFCNs,
signal readings and GPS positions were real observations; the problem was the **role** assigned to
some of them. That could influence RF identity stability (H15), signal baselines, serving-cell
transitions and H16 context, topology and geometry, and local historical learning.

Existing databases keep working, and retention gradually reduces the influence of older data. For a
controlled three-month dataset, however, a clean start gives a clear methodological boundary:

> **Day 1 = v2.10.5 behaviour, schema 19, corrected serving-cell selection, honest neighbour identity
> reporting and the current detection baseline.**

Old exports remain useful for development history, regression analysis and understanding how the
detector evolved.

---

## Why v2.10.5 is the baseline

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

I want to apologize for the unusually high number of releases during development. Real-world testing
repeatedly exposed behaviour that was difficult or impossible to understand from Android
documentation alone: carrier aggregation, modem handovers, unavailable neighbour identities, Timing
Advance limitations, historical baselines and vendor-specific telephony reporting all required real
hardware. Some changes were small; others needed migrations, regression tests or a new
interpretation of historical evidence.

I preferred to document and correct problems rather than hide them or leave known issues in the
detector. v2.10.5 follows the same principle: it was better to fix the last known integrity bugs
before Day 1 than to carry them through three months of data.

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
