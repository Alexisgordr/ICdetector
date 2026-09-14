# ICdetection Status

## v2.1 — released

---

## ⚠️ Read this before installing: uninstall the old version first

**v2.1 will not install as an update. Uninstall ICdetection, then install v2.1 as a fresh install.**

If you try to update in place, Android refuses it. There are two independent reasons, and both point the same way:

1. **Signature.** If the v2.1 APK was not signed with the exact same keystore as the copy already on your phone, Android blocks the update (`INSTALL_FAILED_UPDATE_INCOMPATIBLE`). Any rebuilt APK signed with a different key hits this.
2. **A clean history, which you want anyway.** Records written before v2.1 may hold an antenna coordinate from the verification APIs in the `lat`/`lon` fields, where your own GPS position belongs. The database migration deliberately does not try to rewrite them: after the fact there is no reliable way to tell which is which. Uninstalling clears the database, so the new baseline is built only from correct data.

**If you want to keep your old records, export the CSV before uninstalling.** Be aware that in that old export `Lat`/`Lon` may be either your position or the antenna's, with no way to tell them apart — which is precisely the ambiguity v2.1 ends.

Once v2.1 is installed, future versions signed with the same keystore will update in place normally. This is a one-time step.

---

## What v2.1 is

A **data-integrity release**. It adds no new detection claims and no new heuristics.

The first field-collection phase is finished. It produced 1,065 records over 59 days, and reviewing that export against the source code showed something uncomfortable but useful: the collection could not answer the questions it was designed to answer.

- Heuristic failures that did not reach the alarm threshold were written to history with their reason erased — 33 of the 34 penalised rows said `OK` while carrying a real score of 85 or 75. That is exactly the material a false-positive study needs.
- The impossible ~1,100 km coordinates that kept appearing never came from the GPS at all. They came from the verification APIs, and were being written over the device's own position. That is why none of v2.0's GPS hardening could stop them — the coordinate never passed through it.
- Two heuristics were firing on carrier-aggregation artifacts: the phone's own modem attributing a secondary carrier's PCI and ARFCN to the serving cell.
- With a median of **2 samples per cell**, the RSRQ/SINR fingerprint had been dormant for the whole period and would have stayed dormant for months.

All four are fixed. Every change is backed by a measurement from the field data — the full evidence and reasoning is in `CHANGELOG-v2.1.md`, and the roadmap in `v2.1Roadmap.md` lists what is done and what remains.

## What changes for you in daily use

- **The history now records observations that failed a heuristic without reaching the alarm threshold.** They are marked `[sub-umbral]` and shown in grey. These are **not** alerts: nothing sounds, nothing is flagged as a threat, and they are excluded from the "anomalous cells" counter. They exist so false positives can be studied.
- **The app records a periodic sample of the serving cell** while you stay camped on it — roughly every 5 minutes with the screen on, every 15 with it off. It does not wake the GPS and does not force extra radio reads; it just saves the analysis the service was already doing. This is what finally lets the signal baselines and the RF fingerprint accumulate enough data to be worth anything.
- **The CSV export has three new columns:** `ThreatProb`, `ApiLat` and `ApiLon`. `Lat`/`Lon` are now your GPS position and nothing else; where the antenna supposedly is lives in its own columns.
- **Fewer spurious score deductions.** In the field data, the two heuristics above accounted for roughly 27 of the 34 deductions in two months, and none of them was a real threat.

## What v2.1 does *not* do

It does not detect more than v2.0 did. It is quieter, more honest and — for the first time — it produces data that can be analysed. If you were hoping for a new signal against a sophisticated IMSI-catcher, that is not what this is, and Android userland does not allow it: the app cannot inspect RRC/NAS/baseband traffic the way dedicated hardware can.

This remains a defensive anomaly detector, not a guaranteed IMSI-catcher detector. If you find a bug or something that does not look right, please open an issue with as much detail as possible.

---

Spanish

En español:

## ⚠️ Antes de instalar: desinstala la versión anterior

**v2.1 no se instala como actualización. Desinstala ICdetection y luego instala v2.1 como instalación limpia.** Si intentas actualizar encima, Android lo rechaza.

Dos motivos independientes, y los dos apuntan a lo mismo:

1. **La firma.** Si el APK de v2.1 no está firmado exactamente con el mismo keystore que la copia que ya tienes en el móvil, Android bloquea la actualización (`INSTALL_FAILED_UPDATE_INCOMPATIBLE`). Cualquier APK recompilado y firmado con otra clave se topa con esto.
2. **Empezar con el historial limpio, que además es lo que interesa.** Los registros anteriores a v2.1 pueden llevar en `lat`/`lon` la coordenada de la antena que devolvieron las APIs de verificación, en lugar de tu posición GPS. La migración de la base de datos no intenta reescribirlos a propósito: después no hay forma fiable de distinguir unos de otros. Al desinstalar se borra la base de datos y la nueva línea base se construye solo con datos correctos.

**Si quieres conservar tus registros antiguos, exporta el CSV antes de desinstalar.** Ten en cuenta que en ese export `Lat`/`Lon` puede ser tu posición o la de la antena, sin manera de saberlo — justo la ambigüedad que v2.1 termina.

Una vez instalada v2.1, las siguientes versiones firmadas con el mismo keystore ya se actualizarán encima con normalidad. Esto es un paso de una sola vez.

## Qué es v2.1

Una versión de **integridad de datos**. No añade ni una detección nueva ni una heurística nueva.

Se acabó la primera fase de recolección, y ha servido justo para lo que tenía que servir: enseñar dónde fallaba la app de verdad. Los 1.065 registros de 59 días, cruzados con el código, mostraron cuatro cosas:

- Que el historial borraba el motivo de las anomalías que no llegaban al umbral de alarma — 33 de 34 filas penalizadas decían `OK` con un score real de 85 o 75. Ese es exactamente el material que hace falta para estudiar falsos positivos.
- Que las coordenadas imposibles de los Países Bajos no venían del GPS. Venían de las APIs de verificación, y se escribían encima de tu propia posición. Por eso ninguno de los arreglos de GPS de v2.0 podía cazarlas: la coordenada nunca pasaba por ahí.
- Que dos heurísticas disparaban por agregación de portadoras: el propio módem del móvil atribuye a la celda servidora el PCI y el ARFCN de una portadora secundaria.
- Que con una mediana de **2 muestras por celda**, la huella RF llevaba dos meses dormida y así iba a seguir durante meses.

Los cuatro están arreglados. Todo lo que cambia está justificado con una medición de los datos de campo; el detalle completo está en `CHANGELOG-v2.1.md` y el estado de cada punto del plan, en `v2.1Roadmap.md`.

## Qué notarás en el uso diario

- **El historial ya guarda las observaciones que fallan una heurística sin llegar al umbral de alarma**, marcadas como `[sub-umbral]` y en gris. **No son alertas**: no suena nada, no se señalan como amenaza y no cuentan en el contador de celdas anómalas. Están para poder estudiar los falsos positivos.
- **La app registra una muestra periódica de la celda servidora** mientras sigas en ella (unos 5 minutos con la pantalla encendida, 15 con ella apagada). No despierta el GPS ni fuerza lecturas extra de radio: simplemente guarda el análisis que ya estaba haciendo. Es lo que por fin permite que los baselines y la huella RF acumulen datos suficientes para valer de algo.
- **El CSV tiene tres columnas nuevas:** `ThreatProb`, `ApiLat` y `ApiLon`. `Lat`/`Lon` son ya tu posición GPS y nada más; dónde dicen las APIs que está la antena vive en sus propias columnas.
- **Menos penalizaciones injustificadas.** En los datos de campo, esas dos heurísticas explicaban unas 27 de las 34 penalizaciones de dos meses, y ninguna era una amenaza real.

## Qué NO hace v2.1

No detecta más que v2.0. Es más silenciosa, más honesta y —por primera vez— produce datos que se pueden analizar. Si esperabas una señal nueva contra un IMSI-catcher sofisticado, esto no es eso, y el userland de Android no lo permite: la app no puede inspeccionar tráfico RRC/NAS/banda base como sí hace el hardware dedicado.

Esto sigue siendo un detector defensivo de anomalías, no un detector garantizado de IMSI-catchers. Si encontráis un fallo o algo que no cuadre, abrid una issue con todo el detalle que podáis.

Gracias a todos los que estáis probando la app. Vuestros datos son literalmente lo que ha hecho posible esta versión.

Alexis.
