# History CSV — column changes from 3.1.0

The history CSV (`ExportUtils.CSV_HEADER`) keeps every column it had in 3.0.0, in the same order.
New columns are only ever added at the end. This page lists what changed in the meaning of existing
columns and which columns were added; [MANUAL.md](../MANUAL.md#12-exporting-antenna-history) describes every column.

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
