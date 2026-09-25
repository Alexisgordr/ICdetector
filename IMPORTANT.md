# ❄️ IMPORTANT — Field Collection Freeze

> **Definitive release:** v2.10.5 (version code 33) · **Database schema:** 19 ·
> **Duration:** approximately three months

ICdetection is now entering a **three-month field-collection and stabilization period**.

After the work completed through **v2.10.5**, I consider the project sufficiently mature for a
sustained real-world data collection campaign. The priority is no longer adding features,
heuristics, screens or new detection ideas. The priority is now to **leave the methodology stable,
collect data, observe real behavior, measure false positives, and validate the current design over
time**.

v2.10.4 was originally announced as the freeze release. A final review before the campaign found a
few data-integrity and stability bugs, so they were fixed first. **v2.10.5 is the real starting
point**: it changes no heuristic, weight, threshold or schema — it only makes the data it writes more
trustworthy.

---

## Recommended database reset

For users who want to participate in long-term testing or maintain a clean research dataset, I
recommend starting with a **fresh local database after upgrading to v2.10.5**.

This reset is **recommended for dataset consistency, not required for normal app operation**.

> **How to reset:** export anything you want to keep, then open **History → Delete history** and
> type `BORRAR` to confirm.
>
> Since v2.10.5 this is a complete reset. It removes antenna history, incidents, forensic cases,
> transitions, service-state events, Stable-Site learning **and** the route-familiarity trip tables
> (`mobility_trips`, `mobility_trip_cells`, `mobility_trip_edges`) in a single all-or-nothing
> transaction. In v2.10.4 the trip tables survived and an open trip could carry pre-reset routes into
> the new dataset; that is fixed.
>
> Uninstalling and reinstalling (or *Settings → Apps → ICdetection → Storage → Clear data*) remains a
> valid alternative, but it also removes app settings such as your OpenCellID API key.

The reason is methodological rather than a database corruption issue.

Previous releases collected useful and valid information, but several important corrections were
introduced during development that changed how some observations should be interpreted. In
particular, earlier versions did not always distinguish Android's `PRIMARY_SERVING` and
`SECONDARY_SERVING` cells during carrier aggregation. On devices that expose multiple registered
cells at the same time, a secondary carrier could sometimes be treated as the active serving cell.

Those cells, PCI values, ARFCNs, signal readings and GPS positions were real observations reported by
Android. The problem was the **role assigned to some observations**, especially when reconstructing
serving-cell history and transitions.

This could influence historical data used by areas such as:

- RF identity stability and H15 history
- signal baselines
- serving-cell transitions and H16 context
- topology and geometry
- local historical learning

The issue did **not** mean that the entire historical database was invalid, and existing databases
can continue to operate normally. Retention policies and new observations will progressively reduce
the operational influence of older data.

However, for a controlled three-month research dataset, starting from a clean database provides a
much clearer methodological boundary:

> **Day 1 = v2.10.5 behavior, schema 19, corrected serving-cell selection, honest neighbour identity
> reporting and the current detection baseline.**

Before resetting, export anything you want to preserve. Old exports remain useful for development
history, regression analysis and understanding how the detector evolved.

---

## Why v2.10.5 is the baseline

### Corrections carried from v2.10.4

Serving-cell selection prioritizes Android's `PRIMARY_SERVING` state. Secondary carriers remain
available as radio context but are not allowed to replace the primary serving cell merely because
they have stronger signal. If Android declares a primary serving cell but its telemetry is
temporarily unusable, ICdetection **abstains for that cycle** instead of silently substituting a
secondary carrier.

H15 RF identity stability reasons about **independent PCI alternation episodes** instead of treating
consecutive samples from a single handover as independent evidence. Its own H15-only observations
can re-enter the H15 historical baseline, preventing the heuristic from freezing itself in a
false-positive loop.

Stable-Site supports devices that expose useful neighbour RF context without exposing a complete
neighbour Cell ID. RF-only neighbour information is stored separately and conservatively, with its
own retention rules, without pretending that a local RF fingerprint is a real cellular identity.

The RADIO context preserves diagnostic information such as serving/secondary connection state,
bands, bandwidth, additional PLMN information, CSG context and service-state changes. It is intended
for analysis and diagnostics and does **not** change the threat score.

### What v2.10.5 adds

- **Complete, atomic reset.** *Delete history* now clears every learned table, route trips included,
  in one transaction. Daily retention also runs in one transaction, so an interrupted prune can no
  longer leave forensic cases without their samples.
- **Database safety.** The app and the background service share a single SQLite connection. Deleting
  history while the service is writing can no longer fail with `database is locked`.
- **Honest neighbour identity.** Most modems only report frequency, PCI and power for neighbour
  cells. Their MCC/MNC used to be filled with your own operator's values, so H3 (MCC) and H4 (MNC)
  reported **PASS** without comparing anything. They now report **N/A** when neighbours carry no
  identity, exactly like H5 (TAC) already did.
- **Verification retry.** An unexpected error during OpenCellID verification no longer leaves a cell
  stuck in `PENDING` until the service restarts.
- **Stability.** Minor lifecycle fixes in the activity and history screen.

### Dataset note for v2.10.5

The honest neighbour identity fix is the only change that affects how stored data reads:

| Data | Before v2.10.5 | From v2.10.5 |
|---|---|---|
| H3 / H4 status when neighbours carry no MCC/MNC | `PASS` (compared your network with itself) | `N/A` (nothing to compare) |
| `site_rf_neighbours.mcc` / `mnc` for new rows | Your operator's values, copied | `NULL` |
| Security score and anomaly confidence | — | **Unchanged** (a passing rule never contributed) |
| Devices that report neighbour identities | — | **Unchanged** |

If you compare data across v2.10.4 and v2.10.5, treat H3/H4 `PASS` results and neighbour MCC/MNC
values from older rows as "not really evaluated". Starting from a clean database avoids the issue
entirely.

Schema 19 is unchanged; no migration runs when upgrading from v2.10.4.

---

## Development freeze

From this point, ICdetection is in a **feature freeze for approximately three months**.

During this period I do not plan to add:

- new detection heuristics
- new H17+ rules
- new anomaly weights
- new detection thresholds
- major scoring changes
- experimental detection features
- large architectural features that alter the dataset semantics

The purpose of the freeze is simple: a detector cannot be meaningfully evaluated if its methodology
changes continuously while data is being collected.

The next three months are intended to answer questions with evidence rather than with more code:

- How stable are the current heuristics in daily use?
- Which anomalies repeat?
- Which false positives remain?
- How does the detector behave across different locations and mobility patterns?
- How quickly do Local Cell Trust, Stable-Site and route familiarity mature?
- Does corrected `PRIMARY_SERVING` handling produce cleaner geometry and handover history?
- How useful is the additional radio context when investigating unusual events?
- On devices without neighbour identities, how much do the remaining layers carry on their own?
- Which rules remain useful after weeks or months rather than only during short tests?

---

## What may still change

The project is frozen for features, **not abandoned**.

If a genuine bug is discovered, I will still correct it when it affects:

- data integrity
- collection continuity
- database safety
- privacy or security
- compatibility
- exports
- crashes
- clearly incorrect behavior
- false positives caused by an implementation defect

Whenever possible, these fixes should preserve the current dataset semantics and detection baseline.

A bug fix should not become an excuse to introduce a new heuristic or redesign the detector during
the collection period.

If a future correction necessarily changes how the dataset must be interpreted, it will be
documented explicitly as a new dataset cut.

---

## About the previous versions

I also want to apologize for the unusually high number of releases during development.

Many versions were published close together because real-world testing repeatedly exposed behavior
that was difficult or impossible to understand from Android documentation alone. Carrier
aggregation, modem handovers, unavailable neighbour identities, Timing Advance limitations,
historical baseline behavior and vendor-specific telephony reporting all required testing on real
hardware.

Some changes were small. Others required new migrations, regression tests or changes to how
historical evidence was interpreted.

That development process has sometimes looked chaotic from the outside, and I understand that
frequent releases can be frustrating.

At the same time, I preferred to document and correct problems rather than hide them or leave known
issues in the detector. v2.10.5 follows that same principle: it was better to fix the last known
integrity bugs before Day 1 than to carry them through three months of data.

The result is a project that I now consider significantly more mature, conservative and
understandable than the early releases.

ICdetection still has limitations. It is an Android userland cellular anomaly auditor, not definitive
proof of an IMSI catcher or surveillance system. The operating system does not expose the complete
cellular protocol stack, and no amount of application-side code can remove those limitations.

The goal of the next stage is therefore not to claim perfection. It is to find out how well the
current design behaves when it is finally left alone long enough to accumulate meaningful evidence.

---

## The next three months

From now on, my focus will be:

**collect, observe, export, compare, and learn.**

No feature race.
No adding heuristics simply because they sound interesting.
No changing thresholds every few days.

The current release will be allowed to build history.

After approximately three months, I will review the accumulated data, false positives, topology,
Stable-Site maturity, Local Cell Trust, radio context and forensic events before deciding what — if
anything — should change next.

Thank you to everyone who has downloaded, tested, followed, reviewed or simply taken an interest in
the project.

And to anyone who has endured all the development releases:

**thank you for your patience, your understanding, and for giving the project the time it needed to
mature.**

See you in three months.

— Alexis
