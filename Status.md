# ICdetection Status

## v2.2.0 — incident traceability and bounded forensic capture

**Release:** stable  
**Version code:** 7  
**Database schema:** 14  
**Detection baseline:** unchanged

v2.2.0 is a major observability and evidence-handling update. It does not add a new heuristic,
retune the threat score, lower the three-cycle confirmation threshold, or claim that Android
userland can prove the presence of an IMSI catcher. It makes the existing analysis easier to
inspect, preserve, and export.

### Current capability status

| Capability | Status | Operational meaning |
|---|---|---|
| Existing heuristic engine | Stable / unchanged | Existing weights, penalties, and confirmation behavior are preserved. |
| Temporal phases | Active | `1/3`, `2/3`, and `3/3` are visible and persisted with incident context. |
| Incident black box | Active | Opens at `1/3`; records the highest phase, score, confidence, reason, and diagnostic snapshot. |
| Rule diagnostics | Active | Rules report `PASS`, `FAIL`, or explained `N/A` according to available context. |
| Baseline maturity | Active | Shows readiness of power, quality-fingerprint, PCI-identity, and reputation histories. |
| Forensic prebuffer | Active | Holds up to 60 seconds / 180 recent samples in memory. |
| Post-recovery capture | Active | Continues for 60 seconds; recurrence stays in the same case. |
| Forensic ZIP export | Active | Produces seven documented files plus SHA-256 integrity hashes. |
| Root/baseband visibility | Unavailable | Android userland still cannot expose all ciphering and baseband state on every device. |

### Incident lifecycle

The incident history is separate from antenna history and uses four states:

- `OBSERVING`: an anomaly reached `1/3` but is not confirmed.
- `CONFIRMED`: the episode reached `3/3`.
- `RECOVERED`: later samples returned to normal.
- `INTERRUPTED`: monitoring ended before the episode completed.

Sub-threshold observations are now reviewable without being mislabeled as confirmed alarms. A
dynamic transition between `N/A`, `PASS`, and `FAIL` is expected when Android supplies different
telemetry from one analysis cycle to the next.

### Forensic case lifecycle

The forensic recorder starts automatically at phase `1/3`. It combines up to 60 seconds of
pre-event context, the full temporal episode, and 60 seconds after recovery. Captures are bounded
to 30 minutes and use the states `CAPTURING`, `POST_CAPTURE`, `READY`, and `INTERRUPTED`.

The recorder can include serving and neighboring cells; MCC, MNC, TAC, Cell ID, PCI, ARFCN, band,
RSRP/dBm, RSRQ, SINR and Timing Advance when exposed; device GPS and accuracy; latency and external
verification state; threat phase, score and confidence; per-rule diagnostics; capability state;
device/Android information; and relevant terminal context.

It does **not** export API credentials, IMSI, IMEI, or the phone number.

### Export and integrity boundaries

Each exported case contains:

```text
case.json
timeline.csv
cells.csv
heuristics.csv
capabilities.json
terminal.log
SHA256SUMS.txt
```

The SHA-256 manifest detects later file modification. It is not a digital signature, does not
identify who collected the case, and does not by itself provide a legal chain of custody. Exact
device coordinates may be present, so exported cases must be treated as sensitive material.

### Storage and upgrade behavior

- Schema 13 adds incident records; schema 14 adds forensic cases and samples.
- Migrations are non-destructive for supported recent releases.
- Open forensic captures are marked `INTERRUPTED` after an unexpected restart.
- The existing 60-day cleanup policy also removes expired forensic cases and their samples.
- Official releases from v2.1.2 onward update in place when signed with the same project key.
- If an older installation uses a different signing key, Android requires uninstalling it first.
  Export any history you want to retain before uninstalling.

### Validation focus

The new diagnostic and forensic policies have regression coverage, but the evidence still needs
field validation across different modems, manufacturers, Android versions, operators, and radio
conditions. `N/A` is a valid outcome when the required telemetry is not available. A case or alert
is evidence of an observed anomaly, not definitive attribution to a rogue base station.

---

## Historical v2.1 field-status record

## v2.1.1 — final release candidate. Second field-collection phase starts after build validation.

v2.1.1 is a **data-integrity release**. It adds no new heuristics and makes no new detection claims.
What it does is fix the reasons the *first* collection phase could not answer the questions it was
designed to answer, and add the tooling to check that the second one can.

### Final verification hardening

- Campaign baseline starts clean on 2026-09-15 (frozen build v2.1). The campaign continues on v2.2.0 from 2026-09-17: the     engine, weights and CSV format are identical, so the series is continuous.
- **Quota-safe inconclusive retry.** `REJECTED` is retried after one hour, not every 15 minutes.
- **Ping-pong remains observable without creating a raw alarm.** A one-cycle detection writes an
  informational terminal line with no tone; only the confirmed alarm pipeline may sound.
- **Freeze audit closed three final consistency gaps.** A raw ping-pong observation cannot sound
  before `TemporalConfidence` confirms three cycles; every historical/cache lookup now includes
  radio; and history cards/statistics group by complete identity rather than CID alone.
- Database schema 12 adds a non-destructive radio-aware identity index. Existing rows are retained.

- **Discarded replies are not abandoned.** `REJECTED` and `NOT_FOUND` use independent one-hour
  retry timers. A retry occurs when the same cell is still serving or is
  observed again after the window. Restarting the service also clears the in-memory wait.
- **Latest field evidence is positive.** `alexis3.csv` contains 71 observations: 33 VERIFIED, 34
  REJECTED, 3 NOT_FOUND and 1 ERROR. Fourteen distinct cells have a verified API coordinate, so
  genuine verification and the API-coordinate-to-GPS distance path are both operating.
- **WiGLE response parsing fixed after field evidence.** WiGLE's cell endpoint returns the queried
  identity in `results[].id` as `MCC+MNC_AREA_CELLID` (for example,
  `21407_31601_79362070`). The previous defensive parser looked for separate CID fields, so it
  rejected even a correctly filtered result. v2.1 now validates the complete compound identity
  before accepting its coordinates; it does not restore the unsafe legacy `results[0]` shortcut.
- **A partial negative is not presented as a global negative.** If one source says `NOT_FOUND` but
  the other returns an error or unusable response, the combined result is `REJECTED` (inconclusive).
  “Not registered in public databases” requires agreement from every source actually queried.
- **Verification TTLs now measure API evidence, not observation activity.** Periodic samples copy
  the visible status and previously refreshed its database timestamp indefinitely. A negative is
  now retained for one hour only by the session cache and is then queried again; a stored VERIFIED
  is reusable only from a row that contains the API coordinates that created that verification.
- Every request writes `OpenCellID → STATUS` and `WiGLE → STATUS` to the terminal, making quota,
  permission, rejection and genuine absence distinguishable during field tests.
- **OpenCellID canonical fields are now read in the documented order.** Its LTE response can carry
  the requested area in `lac` while also including an auxiliary `tac: 0`; choosing `tac` first made
  a valid response look like an identity mismatch. The client now uses `lac`/`cellid` first and
  only falls back to `tac`/`cid` when the canonical field is absent. A documented `code: 1` carrying
  a temporary-unavailability notice is classified as `ERROR`, not as a missing cell.
- Radio technology is part of the in-memory, every identity-based database lookup/cache, and UI
  identity of a cell.
- Database updates are scoped by MCC, MNC, area, Cell ID **and radio**, preventing one technology
  from inheriting another technology's result.
- Legacy rows without a recorded radio remain available as historical evidence but are not reused
  as a current verification; the API is queried again.
- OpenCellID can return `VERIFIED` only after a successful HTTP response, no API error, matching
  identity and a coordinate that passes validation.
- GSM/WCDMA mapping, invalid identifiers and CSV replay of the `Radio` column are covered by the
  final code and regression checks.
- API verification callbacks update context only. They cannot emit tones, critical notifications
  or threat state, and they do not advance `TemporalConfidence`; a fresh real radio observation is
  requested and must pass through the normal three-cycle confirmation pipeline.
- The live identity row and expanded history now show MCC together with MNC (`MCC / MNC`) in a
  compact single-line layout that preserves the existing four-column monitor panel.

---

## ⚠️ Before installing: uninstall the previous version

**Uninstall ICdetection, then install v2.1.1 as a fresh install.** Two independent reasons, and both
point the same way:

1. **Signature.** If the v2.1.1 APK is not signed with the same keystore as the copy already on the
   device, Android refuses the update (`INSTALL_FAILED_UPDATE_INCOMPATIBLE`).
2. **A clean history, which you want anyway.** Rows written before v2.1 may hold an antenna
   coordinate from the verification APIs in the `lat`/`lon` fields, where your own GPS position
   belongs. The database migration deliberately does not rewrite them: after the fact there is no
   reliable way to tell which is which. Uninstalling clears the database, so the new baseline is
   built only from correct data.

If you want to keep your old records, **export the CSV before uninstalling** — bearing in mind that
in that old export `Lat`/`Lon` may be either your position or the antenna's, with no way to tell
them apart. That ambiguity is exactly what v2.1 ends.

---

## What the first collection phase found

It produced 1,065 records over 59 days (173 cells). Cross-referencing that export against the
source code showed four things, all of them uncomfortable and all of them useful:

- **The history was erasing the reason for almost every anomaly.** 33 of the 34 penalised rows
  recorded `OK` while carrying a real score of 85 or 75 — an internal contradiction. Exactly the
  material a false-positive study needs, thrown away at write time.
- **The recurring ~1,100 km coordinates never came from the GPS.** They came from the verification
  APIs and were being written over the device's own position. That is why none of the previous
  GPS hardening could stop them: the coordinate never passed through it.
- **Two heuristics were firing on carrier aggregation** — the phone's own modem attributing a
  secondary carrier's PCI and ARFCN to the serving cell. Between them they accounted for roughly
  27 of the 34 score deductions in two months, and not one was a real threat.
- **With a median of 2 samples per cell**, the RSRQ/SINR fingerprint had been dormant the whole
  period and would have stayed dormant for months.

All four are fixed.

## One more finding, and it is not a comfortable one

**The `VERIFIED` column of any history collected before v2.1 cannot be trusted.** The WiGLE query
was built with parameters WiGLE does not have (`mcc`, `mnc`, `lac`, `cellid`), and an unknown
parameter is ignored rather than rejected — so every lookup was an unfiltered search and the app
kept `results[0]`, a cell unrelated to the one asked about. Cells were being marked as verified on
the strength of somebody else's antenna.

Checked by hand against the OpenCellID API, cells the app had marked verified are simply not in
OpenCellID. The verifications were not lost in v2.1; they were never real. This is also where the
impossible coordinates came from: the distance barrier was doing its job on a malformed question.

The practical consequence for the second collection phase: expect a lot of "not in the public
databases". In areas where OpenCellID's coverage is thin that is the honest answer — which is
exactly why the verification no longer affects the score at all (see below).

## What changes for you in daily use

- **Sub-threshold observations are now recorded.** Heuristics that failed without reaching the
  alarm threshold appear marked `[sub-umbral]`, in grey. These are **not** alerts: nothing sounds,
  nothing is flagged as a threat, and they are excluded from the anomalous-cells counter. They
  exist so false positives can be studied.
- **The app samples the serving cell periodically** while you stay camped on it — roughly every
  5 minutes with the screen on, 15 with it off. It does not wake the GPS and does not force extra
  radio reads; it just persists the analysis the service was already doing. This is what finally
  lets the baselines and the RF fingerprint accumulate enough data to be worth anything.
- **The CSV export has six new columns:** `AnomalyConfidence`, `ApiLat`, `ApiLon`, `TA`, `TAUnit`
  and `TAMeters`. `Lat`/`Lon` are now your GPS position and nothing else.
- **Two distance readings in the GEOM panel.** One from the Timing Advance, one from the antenna
  coordinate in the public databases. They are shown separately and labelled, because their
  precision is not comparable.
- **The external verification no longer scores.** A verified cell used to gain +15 and an unknown
  one to lose 10. Both numbers are gone, for the same reason: they measured the database, not the
  antenna. The `-10` was charged to every cell in a poorly mapped area, legitimate ones included —
  a penalty everyone pays separates nobody. The `+15` did not raise a clean cell (already at 100);
  what it did was give it a 15-point cushion against real heuristic penalties, so being listed in a
  crowd-sourced database excused anomalous radio behaviour. And the methodological reason that
  outweighs both: this collection phase exists to find out **whether** the verification status
  carries signal, and that cannot be measured while it is baked into the score used as the
  reference. It stays as an independent label in the `Verified` column, next to a score that comes
  only from the 14 heuristics. `check_export.py` now prints both halves of that comparison.
- **"Not in the database" and "I could not ask" are no longer the same answer.** There is a fifth
  state, `REJECTED`, for a reply that arrives but does not survive our checks: an identity that does
  not match, a sentinel coordinate, an impossible distance, an incomplete body. `NOT_FOUND` is now
  reserved for the only two genuinely negative replies — OpenCellID's documented "cell not found",
  and WiGLE answering successfully with zero results. An exhausted quota, a rejected key or an HTTP
  404 is an `ERROR` and costs nothing. It matters for your data: the history of this phase must not
  record "unknown antenna" where what happened was "I did not trust that answer".
- **The whole identity of every reply is checked**, not just the cell id — operator, area, cell and
  radio technology, compared as numbers so that a "07" and a "7" are not mistaken for two different
  operators. The OpenCellID query also sends `radio` now, so the API cannot answer with the first
  record that happens to match the rest.
- **The CSV has a new `Radio` column** with the technology of each observation (LTE/NR/UMTS/GSM),
  taken from the class of the reading and not from the label on your status bar. `NetType` is still
  there, but it is the icon's string: the same cell flips between "4G" and "5G" without changing
  identity, so it was never something an analysis could rely on.
- **A verification expires after 30 days.** The historical fact stays in the history; what expires
  is the right to answer today with a confirmation from last spring. Public databases change, and
  cell identifiers get reconfigured and reused.
- **"Threat probability" is now "anomaly confidence (uncalibrated)".** Same number. The old label
  suggested a statistical validation that does not exist.
- **The header and the audit panel can no longer disagree.** They used different logic for the same
  score — a clean cell at 90% showed amber above and green below. Both now take the colour from the
  score, and the header shows what the public databases answered on its own line. A cell that is
  not in WiGLE/OpenCellID is **not** a threat: those databases are incomplete, and a new antenna
  takes months to appear in them — and as of this release it **costs nothing** either.

## About the Timing Advance

If your phone shows `Timing Advance: 0 (STUB_ZERO)`, your modem does not implement TA reporting: it
returns a constant 0 instead of declaring the value unavailable. The app detects this (three
distinct cells all reporting 0) and stops deriving any geometry from it.

This is not cosmetic. That permanent 0 fed H6's proximity branch, so any strong unverified cell
would have collected a −15 penalty indefinitely — a silent, perpetual false positive originating
in the phone's firmware, not in the network.

**There is no way to get the real TA on such a phone without root**, and that is not a limitation
of this app: `getTimingAdvance()` is the only public API there is. GrapheneOS has nothing to do
with it either — it does not touch the telephony HAL.

## What v2.1 does *not* do

It does not detect more than v2.0 did. It is quieter, more honest, and — for the first time — it
produces data that can be analysed. If you were hoping for a new signal against a sophisticated
IMSI-catcher, that is not what this is, and Android userland does not allow it: the app cannot
inspect RRC/NAS/baseband traffic the way dedicated hardware can.

---

## Now: three months of collection, and no code changes

The code is **frozen again**, this time for three months. No new heuristics, no new features, no
tuning. Only genuine bug fixes.

The reason is specific. Everything the project needs next depends on data it does not have yet:

- which heuristics actually carry signal and which are noise,
- what the real false-positive rate looks like over months rather than over a bench,
- whether the Bayesian likelihood ratios — currently reasoned estimates, not measurements — hold
  up when contrasted with reality,
- whether the RF fingerprint, awake for the first time, is useful or just noisy.

None of that can be answered by reading code. It needs a few thousand honest observations.

**How to help.** Just use the app. When you export a CSV, you can check it yourself before sending
anything:

```
python3 tools/check_export.py historial.csv
```

It verifies the invariants the design guarantees, and prints how mature your history is — samples
per cell, how many cells have reached the thresholds H13 and the RF fingerprint need, and whether
your modem reports Timing Advance at all.

**If you share a history, blank the `Lat`, `Lon`, `ApiLat` and `ApiLon` columns first.** Those
coordinates are where you live, where you work and the route between them. Nothing that matters for
the analysis depends on them.

This remains a defensive anomaly detector, not a guaranteed IMSI-catcher detector. If you find a
bug or something that does not look right, please open an issue with as much detail as possible.

---

Spanish

En español:

## ⚠️ Antes de instalar: desinstala la versión anterior

**Desinstala ICdetection e instala la v2.1 como instalación limpia.** Dos motivos independientes, y
los dos apuntan a lo mismo:

1. **La firma.** Si el APK no está firmado con el mismo keystore que la copia que ya tienes,
   Android rechaza la actualización (`INSTALL_FAILED_UPDATE_INCOMPATIBLE`).
2. **Empezar con el historial limpio, que además es lo que interesa.** Los registros anteriores a
   v2.1 pueden llevar en `lat`/`lon` la coordenada de la antena que devolvieron las APIs de
   verificación, en lugar de tu posición GPS. La migración no intenta reescribirlos a propósito:
   después no hay forma fiable de distinguirlos.

Si quieres conservar tus registros antiguos, **exporta el CSV antes de desinstalar**.

## Qué encontró la primera fase de recolección

1.065 registros en 59 días, 173 celdas. Cruzarlos con el código enseñó cuatro cosas, todas
incómodas y todas útiles:

- **El historial borraba el motivo de casi todas las anomalías**: 33 de 34 filas penalizadas decían
  `OK` con un score real de 85 o 75. Justo el material que hace falta para estudiar falsos
  positivos, tirado a la basura al guardarlo.
- **Las coordenadas imposibles de los Países Bajos no venían del GPS.** Venían de las APIs de
  verificación y se escribían encima de tu propia posición. Por eso ninguno de los arreglos de GPS
  anteriores podía cazarlas: la coordenada nunca pasaba por ahí.
- **Dos heurísticas disparaban por agregación de portadoras** — el propio módem atribuye a la celda
  servidora el PCI y el ARFCN de una portadora secundaria. Entre las dos explicaban unas 27 de las
  34 penalizaciones de dos meses, y ninguna era una amenaza real.
- **Con una mediana de 2 muestras por celda**, la huella RSRQ/SINR llevaba dormida todo el periodo
  y así habría seguido durante meses.

Las cuatro están arregladas.

## Un hallazgo más, y no es cómodo

**La columna `VERIFIED` de cualquier historial anterior a v2.1 no es fiable.** La consulta a WiGLE
se construía con parámetros que WiGLE no tiene (`mcc`, `mnc`, `lac`, `cellid`), y un parámetro
desconocido no da error: se ignora. Así que cada consulta era una búsqueda sin filtrar y la app se
quedaba con `results[0]` — una celda sin relación con la preguntada. Se daban celdas por verificadas
con la antena de otro.

Comprobado a mano contra la API de OpenCellID: celdas que la app daba por verificadas sencillamente
no están en OpenCellID. Las verificaciones no se perdieron en v2.1; nunca fueron reales. De ahí
venían también las coordenadas imposibles: la barrera de distancia hacía bien su trabajo sobre una
pregunta mal formulada.

Lo que esto implica para la segunda fase de recolección: habrá muchas celdas "sin registro en las
bases públicas". Donde la cobertura de OpenCellID es escasa, esa es la respuesta honesta — y por eso
mismo la verificación ya no toca la puntuación (más abajo).

## Qué notarás en el uso diario

- **Se registran las observaciones sub-umbral**, marcadas `[sub-umbral]` y en gris. **No son
  alertas**: no suena nada, no se señalan como amenaza y no cuentan en el contador de celdas
  anómalas. Están para poder estudiar los falsos positivos.
- **Muestreo periódico de la celda servidora** mientras sigas en ella (5 min con pantalla
  encendida, 15 con ella apagada). No despierta el GPS ni fuerza lecturas extra de radio.
- **Seis columnas nuevas en el CSV:** `AnomalyConfidence`, `ApiLat`, `ApiLon`, `TA`, `TAUnit` y
  `TAMeters`. `Lat`/`Lon` son ya tu posición GPS y nada más.
- **Dos distancias en el panel GEOM**: una por Timing Advance y otra según la coordenada que las
  bases públicas atribuyen a la antena. Se muestran por separado porque su precisión no es
  comparable.
- **La verificación externa ya no puntúa.** Una celda verificada se llevaba +15 y una desconocida
  -10. Las dos cifras han caído por el mismo motivo: medían la base de datos, no la antena. El -10
  se lo cobraba cualquier celda en una zona poco mapeada, legítimas incluidas — y una penalización
  que paga todo el mundo no distingue a nadie. El +15 no subía una celda limpia (ya estaba en 100):
  lo que hacía era darle un colchón de 15 puntos contra penalizaciones reales de las heurísticas,
  con lo que estar en una base colaborativa excusaba un comportamiento de radio anómalo. Y la razón
  metodológica, que pesa más que las dos: esta fase existe para averiguar **si** el estado de
  verificación aporta señal, y eso no se puede medir mientras está metido dentro de la puntuación
  que sirve de referencia. Queda como etiqueta independiente en la columna `Verified`, junto a un
  score que sale solo de las 14 heurísticas. `check_export.py` imprime ya las dos mitades de esa
  comparación.
- **"No está en la base" y "no he podido preguntar" dejan de ser la misma respuesta.** Hay un quinto
  estado, `REJECTED`, para la respuesta que llega pero no supera nuestras comprobaciones: identidad
  que no cuadra, coordenada centinela, distancia imposible, cuerpo incompleto. `NOT_FOUND` queda
  reservado a las dos únicas respuestas realmente negativas — el "cell not found" documentado de
  OpenCellID y un WiGLE que contesta bien con cero resultados. Una cuota agotada, una key rechazada
  o un HTTP 404 son `ERROR` y no cuestan nada. Importa para tus datos: el historial de esta fase no
  puede registrar "antena desconocida" donde lo que pasó fue "no me fío de esa respuesta".
- **Se comprueba la identidad entera de cada respuesta**, no solo la Cell ID: operador, área, celda
  y tecnología, comparadas como números para que un "07" y un "7" no parezcan dos operadores
  distintos. La consulta a OpenCellID envía además `radio`, así que la API ya no puede contestar con
  el primer registro que cuadre con el resto.
- **Columna nueva `Radio` en el CSV** con la tecnología de cada observación (LTE/NR/UMTS/GSM),
  tomada de la clase de la lectura y no de la etiqueta de tu barra de estado. `NetType` sigue ahí,
  pero es la cadena del icono: la misma celda alterna entre "4G" y "5G" sin cambiar de identidad,
  así que nunca sirvió para analizar nada.
- **Una verificación caduca a los 30 días.** El hecho histórico se queda en el historial; lo que
  caduca es el derecho a contestar hoy con una confirmación de la primavera pasada. Las bases
  públicas cambian y los identificadores celulares se reconfiguran y se reutilizan.
- **"Amenaza estimada" pasa a "confianza de anomalía (no calibrada)"**. El número es el mismo; la
  etiqueta ha dejado de prometer una validación estadística que no existe.
- **La cabecera y el panel de auditoría ya no pueden contradecirse.** Usaban criterios distintos
  para el mismo score: una celda limpia al 90% salía ámbar arriba y verde abajo. Ahora el color
  sale del score en los dos sitios, y lo que contestaron las bases públicas se muestra aparte, en
  su propia línea. Que una celda no esté en WiGLE/OpenCellID **no** la hace sospechosa: esas bases
  están incompletas y una antena recién desplegada tarda meses en aparecer — y desde esta versión
  **tampoco resta nada**.

## Sobre el Timing Advance

Si tu móvil muestra `Timing Advance: 0 (STUB_ZERO)`, tu módem no implementa el reporte de TA:
devuelve un 0 constante en lugar de declarar que el dato no está disponible. La app lo detecta
(tres celdas distintas reportando solo 0) y deja de deducir geometría de ahí.

No es cosmético: ese 0 permanente alimentaba la rama de proximidad de H6, así que cualquier celda
con señal fuerte y sin verificar se habría llevado un −15 indefinido. Un falso positivo perpetuo
originado en el firmware del teléfono, no en la red.

**Sin root no hay forma de obtener el TA real en un teléfono así**, y no es una limitación de la
app: `getTimingAdvance()` es la única API pública que existe. GrapheneOS tampoco tiene nada que
ver — no toca el HAL de telefonía.

## Endurecimiento final de la verificación

- **Las respuestas descartadas no se abandonan.** `REJECTED` y `NOT_FOUND` tienen temporizadores
  independientes de una hora. Se reintenta cuando la misma celda continúa activa o
  vuelve a aparecer después de esa ventana. Reiniciar el servicio también elimina la espera en
  memoria.
- **La última evidencia de campo es positiva.** `alexis3.csv` contiene 71 observaciones: 33
  VERIFIED, 34 REJECTED, 3 NOT_FOUND y 1 ERROR. Catorce celdas distintas conservan coordenadas API
  verificadas, confirmando que funcionan tanto la verificación real como la distancia API/GPS.
- **Corregida la lectura de respuestas válidas de WiGLE.** La identidad llega en `results[].id`
  como `MCC+MNC_AREA_CELLID` (por ejemplo, `21407_31601_79362070`), no en campos CID separados.
  Ahora se interpreta y compara completa antes de aceptar la coordenada; no se recupera el antiguo
  atajo inseguro que aceptaba `results[0]` sin saber a qué celda correspondía.
- **Una negativa parcial ya no se presenta como negativa global.** Si una fuente responde
  `NOT_FOUND` pero la otra da error o una respuesta inutilizable, el resultado combinado será
  `REJECTED` (inconcluso). “Sin registro en bases públicas” exige que todas coincidan.
- **Los TTL ya miden evidencia de la API, no actividad de observación.** Las muestras periódicas
  copiaban el estado y renovaban su fecha indefinidamente. Una negativa queda una hora en la caché
  de sesión y después se consulta otra vez; VERIFIED solo se reutiliza desde una fila que conserve
  las coordenadas API que originaron la confirmación.
- Cada consulta muestra `OpenCellID → ESTADO` y `WiGLE → ESTADO` en el terminal.
- **OpenCellID ya lee primero sus campos canónicos documentados.** Una respuesta LTE puede traer el
  área pedida en `lac` y además un `tac: 0` auxiliar; escoger antes ese cero convertía una respuesta
  válida en identidad incompatible. Ahora se usan primero `lac`/`cellid`, con `tac`/`cid` solo como
  respaldo. Un `code: 1` con aviso de indisponibilidad temporal se marca `ERROR`, no celda ausente.
- La tecnología de radio forma parte de la identidad de la celda en memoria, base de datos y UI.
- Las actualizaciones de la base se limitan por MCC, MNC, área, Cell ID **y radio**, evitando que
  una tecnología herede el resultado de otra.
- Las filas antiguas sin radio se conservan como evidencia histórica, pero no se reutilizan como
  verificación actual: se vuelve a consultar la API.
- OpenCellID solo puede devolver `VERIFIED` tras HTTP correcto, ausencia de error de API, identidad
  coincidente y coordenada aceptada.
- El mapeo GSM/WCDMA, las identidades inválidas y el replay de la columna `Radio` quedan cubiertos
  por el código final y sus comprobaciones de regresión.
- Los callbacks de verificación API solo actualizan contexto. No pueden emitir tonos,
  notificaciones críticas ni estado de amenaza, y no avanzan `TemporalConfidence`; se solicita
  una lectura real que debe atravesar el flujo normal de confirmación de tres ciclos.
- La fila de identidad en vivo y el historial expandido muestran MCC junto a MNC (`MCC / MNC`) en
  una disposición compacta que conserva las cuatro columnas del monitor.

## Ahora: validar la compilación y comenzar tres meses de recolección

Nada de heurísticas nuevas, nada de funciones nuevas, nada de tocar pesos. Solo arreglos de fallos
reales.

El motivo es concreto: todo lo que el proyecto necesita a continuación depende de datos que
todavía no existen — qué heurísticas aportan señal de verdad y cuáles son ruido, cuál es la tasa
real de falsos positivos a lo largo de meses, si los likelihood ratios del bayesiano (hoy
estimaciones razonadas, no medidas) aguantan el contraste con la realidad, y si la huella RF,
despierta por primera vez, sirve o solo hace ruido. Nada de eso se contesta leyendo código.

**Cómo ayudar:** usa la app. Antes de mandar nada, puedes revisar tu propio export:

```
python3 tools/check_export.py historial.csv
```

Comprueba las invariantes que el diseño garantiza y te dice cómo de maduro está tu historial —
muestras por celda, cuántas llegan a los umbrales que necesitan H13 y la huella RF, y si tu módem
reporta Timing Advance.

**Si compartes un historial, vacía antes las columnas `Lat`, `Lon`, `ApiLat` y `ApiLon`.** Esas
coordenadas son dónde vives, dónde trabajas y el camino entre las dos. Nada de lo que importa para
el análisis depende de ellas.

Esto sigue siendo un detector defensivo de anomalías, no un detector garantizado de IMSI-catchers.
Si encontráis un fallo o algo que no cuadre, abrid una issue con todo el detalle que podáis.

Gracias a todos los que estáis probando la app. Vuestros datos son literalmente lo que ha hecho
posible esta versión.

Alexis.
