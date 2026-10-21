# History CSV — column changes from 3.1.0

The history CSV (`ExportUtils.CSV_HEADER`) keeps every column it had in 3.0.0, in the same order.
New columns are only ever added at the end. This page lists what changed in the meaning of existing
columns and which columns were added; [MANUAL.md](../MANUAL.md#12-exporting-antenna-history) describes every column.

## Columns added in 3.1.0

| Column | Meaning |
|---|---|
| `ChurnCells15m` | Distinct full serving identities (MCC, MNC, area, Cell ID, technology) in the 15 minutes before the observation |
| `ChurnChanges15m` | Serving changes in those 15 minutes (same definition of a handover as H10) |
| `ChurnMotion` | `STILL` or `MOVING` from the speed of a GPS fix no older than 60 s; `UNKNOWN` otherwise |

They come from H17 (cell churn), which runs in shadow mode: stored, never evaluated, no points. The
count restarts after a service loss or a gap of more than 2 minutes. Database schema 26 adds the
three columns to `history` (`churn_cells_15m`, `churn_changes_15m`, `churn_motion`); rows recorded
before 3.1.0 have them empty.

## Rules that are always listed as not evaluated from 3.1.0

`NotEvaluatedHeuristics` lists H12 and `H17(sombra)` in every row from 3.1.0: the latency + RF
correlation was retired with the latency probe, and the cell churn runs in shadow mode. Rows from
earlier versions list H12 only when it was not evaluated then, and never H17.

## Columns that are always empty from 3.1.0

| Column | Up to 3.0.0 | From 3.1.0 |
|---|---|---|
| `Verified` | Result of the optional OpenCellID lookup: `VERIFIED`, `NOT_FOUND`, `REJECTED`, `ERROR` or `PENDING` | Always `PENDING` |
| `ApiLat` / `ApiLon` | Antenna position returned by OpenCellID, when the lookup found one | Always empty |

3.1.0 removed the external verification (and, with it, the app's only use of the Internet besides
the latency probe). The three columns stay in the database (`verified`, `api_lat`, `api_lon`) and in
the CSV so that scripts reading the file by position keep working. Rows recorded by earlier versions
keep the values they had; use `AppVersion` to tell them apart. None of these values ever changed
`SecurityScore`.
