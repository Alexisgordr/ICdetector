# ICdetection v2.1 — Changelog

**Type:** data-integrity release. **No new detection claims. No new heuristics.**

v2.0 was frozen for a field-data collection phase. That phase produced 1,065 records over 59 days (173 cells, MCC 214 / MNC 07). Cross-referencing that export against the source code showed that the collection **could not answer the questions it was designed to answer**, for reasons that were all fixable. This release fixes them.

Every change below is justified by a measurement from that dataset, not by speculation.

---

## 1. The forensic history was erasing the reason for every sub-threshold anomaly

**Evidence:** 34 of 1,065 rows had `SecurityScore < 100`. **33 of them recorded `FailedHeuristics = OK`.** A row saying `85 / OK` is an internal contradiction: the score proves heuristics failed.

**Cause:** `MiniICService.applyTemporalConfidence()` set `suspiciousReason = null` whenever the cell was not suspicious (score ≥ 70), and `checkAlerts()` then persisted `suspiciousReason ?: "OK"` together with the real score.

**Why it mattered:** those sub-threshold rows are 97 % of the useful signal — they are the incipient false positives the freeze existed to study. Roadmap items #5 (H13 robust baseline), #6 (H15 maturity) and #7 (Bayesian recalibration) were all unexecutable without them.

**Fix:** the reason is now preserved and tagged with the `[sub-umbral]` prefix (`models/HistoryRecord.kt`). Alarm behaviour is unchanged: `isSuspicious` is still false, nothing sounds, nothing is displayed as a threat, and the 3-cycle confirmation gate for forensic alarms is untouched. The evidence simply stops being destroyed.

The audit log was made consistent too: a cell with failed heuristics below the threshold no longer reports "✅ SEGURO".

---

## 2. API tower coordinates were being written over the device's GPS positions

**Evidence:** 47 records carried the coordinate `52.078831, 4.330071` — identical to six decimal places — across **46 distinct cells**, from 17 Jul to 12 Sep, all marked `VERIFIED`, some at strong signal (−73 dBm). **42 of the 47 were the first-ever sighting of that cell**, the other 5 the second. On 12 Sep it appears interleaved between two valid local fixes 48 seconds apart.

**Cause:** it never came from the GPS, so none of v2.0's GPS hardening (`isPlausibleFix()`, GPS-only provider, persisted plausibility reference) could ever catch it. `CellDbHelper.updateVerificationStatus()` wrote the lat/lon returned by WiGLE/OpenCellID into `COLUMN_LAT`/`COLUMN_LON` of every row of that cell still marked `PENDING` — which are exactly the rows that had no GPS coordinate. Hence the first-sighting pattern.

Compounding it, `isValidCoordinate()` only applied its 50 km sanity check when a live GPS fix existed, and accepted anything otherwise — and "otherwise" is the common case for a GPS-only app used indoors.

**Consequences:** (a) the `Lat`/`Lon` column mixed two different quantities, silently contaminating the geographic baseline H11/H13 reads from history; (b) ~28 % of first verifications marked a cell `VERIFIED` on a garbage answer, granting +15 score and a 0.4 Bayesian likelihood ratio — a **false-negative** pathway, not a cosmetic bug.

**Fixes:**

- New DB columns `api_lat` / `api_lon` (schema v9, additive migration). Tower coordinates go there. **`lat`/`lon` is now written by the GPS and by nothing else.**
- `getKnownStatus()` reads `api_lat`/`api_lon` for its 5 km proximity check, which is the comparison it always meant to make.
- `isValidCoordinate()` rewritten with three barriers: a **sentinel detector** (a coordinate already on record for ≥ 3 distinct Cell IDs is an API default, not an antenna — no GPS needed, `countDistinctCellsWithApiCoordinate()`); the 50 km check against a live fix; and, with no live fix, a 500 km check against the last accepted position (persisted, ≤ 6 h old) — generous enough never to reject a legitimate answer after travel, tight enough that 1,100 km still fails.
- A rejected coordinate no longer yields `VERIFIED`. A verification that does not hold up is not a verification: it is treated as `NOT_FOUND` and retried in 1 h.

> **Note on existing history:** rows written before v2.1 may hold an antenna coordinate in `lat`/`lon`. The migration does not rewrite them — guessing which is which afterwards is not possible. Starting from a cleared history is recommended.

---

## 3. H15 was firing on carrier aggregation, via PCI

v2.0 removed ARFCN from this heuristic because field data showed it flaps benignly under carrier aggregation, and kept PCI on the premise that "a legitimate cell never flaps its PCI".

**Evidence:** 26 of 173 cells showed more than one PCI. The PCI × ARFCN cross-tabulation showed **perfect correlation** — e.g. cell 79482913: PCI 200 only ever on ARFCN 6400 (6/6), PCI 473 only ever on ARFCN 3600 (7/7), zero crossover. The modem attributes the secondary carrier's PCI to the serving cell exactly as it does its ARFCN. Simulating the existing `solidSet` logic over the 59 days: **one cell fires (79482913, ≈ 15 of the 34 score deductions); with per-carrier comparison, none does.**

**Fix:** `CellRfStability` now carries `pciByArfcn` / `recentPciByArfcn`, and H15 requires two solid, recent PCI values **within the same carrier**. A clone reconfiguring its PCI does so on its own carrier, so detection capability is unchanged. Rows with no ARFCN (pre-v6 history) are grouped under `UNKNOWN_ARFCN` and keep comparing against each other. If no per-carrier data is available at all, the v2.0 global rule still applies.

---

## 4. H14 was flagging ordinary handovers to the coverage layer

**Evidence:** 12 of the 34 deductions were high-band → sub-GHz transitions where the *new* cell was at −101, −103, −108, −111, even −116 dBm. Those are not forced downgrades; they are what leaving an urban microcell's coverage looks like.

**Cause:** `prevStrong` tested the *previous* cell's signal. The new cell's own signal was never considered — yet the attack model is a nearby tactical transmitter, which by definition offers a **strong** signal.

**Fix:** two necessary conditions added, so no real attack signature is lost:

- `newStrong`: the new low-band cell must read ≥ −95 dBm.
- `notCoverageFallback`: it must not be weaker than the cell you had. Losing power on the way down is the signature of losing the previous cell, not of being captured.

The progressive-degradation exception (the garage/basement case) is unchanged, and the cross-handover trend buffer that powers it is deliberately **not** reset — it has to span the handover to work.

---

## 5. The sampling rate, not the heuristics, was the real bottleneck

**Evidence:** median **2 samples per cell**; 38 % of cells seen exactly once; only 12 of 173 cells reached the 20 samples H13 needs for its P99 hardening, and only 6 reached the 30 the RSRQ/SINR fingerprint needs. **The RF fingerprint had been dormant for two months and would still have been dormant in March.** Eight hours camped on one cell at home produced zero samples, because history was only written on handover.

**Fix:** `maybeLogPeriodicSample()` records the serving cell roughly every 5 minutes with the screen on and every 15 with it off. It does **not** wake the GPS (it uses whatever last known fix exists) and does **not** force extra radio reads — it persists the analysis the service loop already performs. Cost: ~150 rows/day, ~9,000 over the 60-day retention window.

Three secondary adjustments were needed so that *more data* does not *degrade* the heuristics:

- `getPreviousCellHistory()` now reads up to 300 rows and trims to 20 **after** the 50 m distance filter. With the old `LIMIT 20`, same-place periodic samples would have consumed the entire quota and left H11 with nothing usable.
- `getCellRfFingerprint()` accepts an optional location and confines samples to a ~1 km zone (rows without coordinates are kept, so indoor volume is not lost). Without this, the place where you spend most time would dominate the mean and the same cell seen elsewhere could look "incoherent".
- H15's per-carrier shares become more robust as observation counts rise, which further suppresses stray readings.

---

## 6. Smaller items

- **`ThreatProb` exported.** The Bayesian posterior was computed every cycle and discarded. Roadmap #7 asks for recalibrating those likelihood ratios against field data; without the model's output in the export there was nothing to calibrate. Now persisted (`threat_prob`) and exported.
- **`ApiLat` / `ApiLon` exported**, so the provenance of every coordinate is visible.
- **Temporal-confirmation streak keyed on full identity** (`MCC-MNC-TAC-CID`) — roadmap Tier 1 #1, the last place still keying on a bare `cellId`.
- **WiGLE `User-Agent`** updated from `ICdetection/1.0` to `ICdetection/2.1`.
- **History screen**: sub-threshold observations render in grey as `· reason`, visually distinct from a red `⚠️ Fallo:` alarm, and are excluded from the "anomalous cells" counter — they are analysis material, not incidents.
- **Version**: `versionCode = 3`, `versionName = "2.1"`.

---

## Installation and database migration

**v2.1 must be installed as a fresh install: uninstall the previous version first.** Android refuses an in-place update when the APK is not signed with the exact keystore that signed the installed copy (`INSTALL_FAILED_UPDATE_INCOMPATIBLE`), and a clean database is wanted regardless — see the note under §2. Export the CSV before uninstalling if the old history is worth keeping. Once v2.1 is installed, later versions signed with the same keystore update in place normally.

The migration itself (schema v8 → v9) is three additive `ALTER TABLE` statements (`threat_prob`, `api_lat`, `api_lon`), each individually guarded. No rows are modified, moved or deleted. Where an in-place upgrade *is* possible (same keystore), it works: pre-v2.1 rows simply carry `threat_prob = 0` and null API coordinates — but those rows keep the ambiguous `lat`/`lon`, which is why starting clean is recommended.

---

## Tests

`BandDowngradeTest` was updated to the new H14 contract and three cases added, including the real −85 → −108 dBm handover from the field data. `HeuristicsTest` keeps its existing H15 cases (which now exercise the compatibility path) and adds four for the per-carrier rule, including the real 79482913 case in both directions: unchanged data must not fire, the same PCI split forced onto a single carrier must.

---

## Resumen en español

v2.1 no añade detección nueva: arregla la integridad de los datos.

1. **El historial ya no borra el motivo** de las anomalías que no llegan al umbral de alarma (33 de 34 filas penalizadas decían "OK"). Se guardan marcadas como `[sub-umbral]`. El comportamiento de alarma no cambia en nada.
2. **La coordenada de la antena deja de escribirse encima de tu posición GPS.** Vive en columnas propias (`api_lat`/`api_lon`), y ahora se valida: se rechaza si ya consta para varias celdas (valor por defecto de la API) o si está a una distancia imposible, incluso sin fix GPS vivo. Una coordenada rechazada ya no da la celda por verificada.
3. **H15 compara PCI solo dentro de la misma portadora.** Con tus datos, el único caso que disparaba en 59 días era agregación de portadoras; con la regla nueva no dispara ninguno, y un clon real en la misma portadora sigue detectándose.
4. **H14 exige que la celda nueva se vea fuerte y no más débil que la anterior**, que es lo que de verdad distingue un "tirón" de un transmisor cercano de un handover normal a la capa de cobertura.
5. **Muestreo periódico de la celda servidora** (5 min con pantalla encendida, 15 con ella apagada), sin despertar el GPS. Es lo que por fin despierta la huella RF y da sentido a los baselines.
6. **`ThreatProb`, `ApiLat` y `ApiLon` en el CSV**, racha de confirmación por identidad completa, User-Agent actualizado y versión 2.1.

**Recomendación:** empezar la próxima fase de recolección con el historial borrado, para que la base geográfica se construya solo con datos limpios.
