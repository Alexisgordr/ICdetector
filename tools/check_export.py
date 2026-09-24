#!/usr/bin/env python3
"""
check_export.py — validador de invariantes del CSV exportado por ICdetection.

POR QUÉ EXISTE.
Dos de los cuatro fallos principales corregidos en v2.1 llevaban meses en el historial exportado
y se veían a simple vista en cuanto se cruzaba el CSV con el código. Ninguno de los dos lo detectó
una lectura atenta: los detectó una comprobación. La conclusión de v2.1 fue que la validación de
campo necesita una comprobación automática, no una lectura cuidadosa. Esto es esa comprobación.

Se ejecuta sobre cualquier export, sin dependencias externas:

    python3 tools/check_export.py historial.csv

Salida: una línea por invariante (OK / FALLO) y un resumen de madurez del historial. Código de
salida 1 si alguna invariante falla, para poder encadenarlo en CI.

Las invariantes NO son opiniones sobre la detección: son afirmaciones que el propio diseño de la
app dice que deben cumplirse siempre. Si una falla, hay un bug, no un falso positivo.
"""

import csv
import math
import sys
from collections import Counter, defaultdict
from datetime import datetime

TS_FORMAT = "%Y-%m-%d %H:%M:%S"
SUBTHRESHOLD_PREFIX = "[sub-umbral]"

# Columnas de v2.1 en adelante. Las tres últimas no existían antes.
EXPECTED_COLUMNS_V21 = [
    "Timestamp", "NetType", "CID", "MNC", "TAC", "MCC", "DBM", "Verified",
    "SecurityScore", "FailedHeuristics", "Lat", "Lon", "PCI", "ARFCN", "RSRQ", "SINR",
    "AnomalyConfidence", "ApiLat", "ApiLon", "TA", "TAUnit", "TAMeters", "Radio",
]

failures = []
notes = []


def check(name, ok, detail=""):
    print(f"  [{'OK  ' if ok else 'FALLO'}] {name}" + (f" — {detail}" if detail else ""))
    if not ok:
        failures.append(name)


def haversine_m(lat1, lon1, lat2, lon2):
    r = 6371000.0
    p1, p2 = math.radians(lat1), math.radians(lat2)
    dp = math.radians(lat2 - lat1)
    dl = math.radians(lon2 - lon1)
    a = math.sin(dp / 2) ** 2 + math.cos(p1) * math.cos(p2) * math.sin(dl / 2) ** 2
    return 2 * r * math.atan2(math.sqrt(a), math.sqrt(1 - a))


def fnum(row, key):
    """Valor numérico de una columna, o None si está vacía o no es un número.

    OJO con el patrón `fnum(...) or <defecto>`: en Python, 0.0 es falso, así que ese idiom
    convierte silenciosamente un cero legítimo en el valor por defecto. Aquí eso significaría
    que un SecurityScore de 0 —la puntuación MÁS grave posible— se leería como 100 y la fila se
    escaparía de la comprobación más importante del validador. Por eso existe fnum_or() y por eso
    en este fichero no se usa `or` para dar valores por defecto a números.
    """
    v = (row.get(key) or "").strip()
    if v == "":
        return None
    try:
        return float(v)
    except ValueError:
        return None


def fnum_or(row, key, default):
    """fnum() con valor por defecto SOLO cuando el dato está ausente. Un 0 real se respeta."""
    v = fnum(row, key)
    return default if v is None else v


def load(path):
    with open(path, newline="", encoding="utf-8") as fh:
        reader = csv.DictReader(fh)
        return reader.fieldnames or [], list(reader)


def main(path):
    columns, rows = load(path)
    if not rows:
        print("El fichero no tiene filas.")
        return 1

    print(f"\n=== {path} — {len(rows)} filas ===\n")
    legacy = "AnomalyConfidence" not in columns and "ThreatProb" not in columns
    if legacy:
        notes.append(
            "Export anterior a v2.1: sin AnomalyConfidence/ApiLat/ApiLon. Las invariantes que "
            "dependen de esas columnas se omiten, y Lat/Lon puede mezclar tu posición con la de "
            "la antena (ese era justamente el bug)."
        )

    print("INVARIANTES")

    # ---- 1. Coherencia score / motivo -------------------------------------------------------
    # Si el score bajó de 100, alguna heurística falló, y su motivo TIENE que estar registrado.
    # Una fila "85 / OK" es una contradicción interna: fue el bug principal de v2.0.
    contradictory = [
        r for r in rows
        if fnum_or(r, "SecurityScore", 100) < 100
        and (r.get("FailedHeuristics") or "").strip() in ("", "OK")
    ]
    check(
        "Ninguna fila con score < 100 y motivo 'OK'",
        not contradictory,
        f"{len(contradictory)} filas contradictorias (p. ej. {contradictory[0]['Timestamp']} "
        f"score={contradictory[0]['SecurityScore']})" if contradictory else "",
    )

    # ---- 2. Coordenada centinela ------------------------------------------------------------
    # Una coordenada de antena pertenece a UNA antena. Si la misma aparece para varias Cell ID
    # distintas, es un valor por defecto de la API y no verifica nada.
    def sentinel_check(lat_key, lon_key, label, max_grupos, por_area=False, unidad="celdas"):
        """Cuenta cuántos grupos distintos comparten exactamente una misma coordenada.

        `por_area=True` agrupa por área de seguimiento (MCC-MNC-TAC) en vez de por Cell ID. Es la
        misma corrección que se hizo en la app: los sectores y bandas de un mismo mástil comparten
        coordenada de forma legítima, así que contarlos como celdas distintas marcaba como centinela
        a emplazamientos perfectamente normales, y cada vez a más según crecía el historial. Un
        centinela de verdad aparece en áreas sin relación entre sí.
        """
        by_coord = defaultdict(set)
        for r in rows:
            lat, lon = fnum(r, lat_key), fnum(r, lon_key)
            if lat is None or lon is None:
                continue
            key = (round(lat, 4), round(lon, 4))
            grupo = ((r.get("MCC"), r.get("MNC"), r.get("TAC")) if por_area
                     else (r.get("CID"), r.get("MNC"), r.get("TAC"), r.get("MCC")))
            by_coord[key].add(grupo)
        worst = max(by_coord.items(), key=lambda kv: len(kv[1]), default=None)
        if worst is None:
            check(label, True, "sin coordenadas que comprobar")
            return
        coord, grupos = worst
        check(
            label,
            len(grupos) <= max_grupos,
            f"{coord} aparece en {len(grupos)} {unidad} distintas",
        )

    if not legacy:
        sentinel_check(
            "ApiLat", "ApiLon",
            "Ninguna coordenada de API compartida por >2 áreas de seguimiento",
            2, por_area=True, unidad="áreas de seguimiento",
        )
    # La coordenada GPS sí puede repetirse entre celdas (estás en el mismo sitio viendo varias
    # antenas), pero un valor idéntico a 4 decimales en decenas de celdas delata un centinela.
    sentinel_check("Lat", "Lon", "Ninguna coordenada GPS repetida en >20 celdas", 20)

    # ---- 3. Saltos físicamente imposibles ---------------------------------------------------
    # Lat/Lon es la posición del dispositivo. Entre dos observaciones consecutivas no puede
    # implicar una velocidad terrestre absurda.
    fixes = []
    for r in rows:
        lat, lon = fnum(r, "Lat"), fnum(r, "Lon")
        if lat is None or lon is None:
            continue
        try:
            ts = datetime.strptime(r["Timestamp"], TS_FORMAT)
        except (ValueError, KeyError):
            continue
        fixes.append((ts, lat, lon, r["Timestamp"]))
    fixes.sort(key=lambda x: x[0])

    jumps = []
    for (t1, la1, lo1, s1), (t2, la2, lo2, s2) in zip(fixes, fixes[1:]):
        dt = max((t2 - t1).total_seconds(), 1.0)
        kmh = (haversine_m(la1, lo1, la2, lo2) / dt) * 3.6
        if kmh > 400:
            jumps.append((s1, s2, round(kmh)))
    check(
        "Ningún salto entre fixes por encima de 400 km/h",
        not jumps,
        f"{len(jumps)} saltos (el primero {jumps[0][0]} -> {jumps[0][1]}: {jumps[0][2]} km/h)"
        if jumps else "",
    )

    # ---- 4. Rangos físicos ------------------------------------------------------------------
    bad_dbm = [r for r in rows if not (-145 <= fnum_or(r, "DBM", -90) <= -30)]
    check("DBM dentro de rango físico (-145..-30)", not bad_dbm, f"{len(bad_dbm)} filas")

    # El rango del identificador físico depende de la tecnología de la lectura (columna Radio),
    # igual que en StableSiteNeighbourEvidence: LTE 0..503, NR 0..1007, UMTS (PSC) 0..511.
    # Sin Radio (exports antiguos) o con otra tecnología se usa el rango más amplio, 0..1007.
    pci_max = {"LTE": 503, "NR": 1007, "UMTS": 511}
    bad_pci = []
    for r in rows:
        pci = fnum(r, "PCI")
        if pci is None:
            continue
        radio = (r.get("Radio") or "").strip().upper()
        if not (0 <= pci <= pci_max.get(radio, 1007)):
            bad_pci.append(r)
    check(
        "PCI dentro de rango 3GPP por tecnología (LTE 0..503, NR 0..1007, UMTS 0..511)",
        not bad_pci,
        f"{len(bad_pci)} filas" + (
            f" (p. ej. {bad_pci[0]['Timestamp']} Radio={bad_pci[0].get('Radio') or '?'} "
            f"PCI={bad_pci[0]['PCI']})" if bad_pci else ""
        ),
    )

    bad_score = [r for r in rows if not (0 <= fnum_or(r, "SecurityScore", 100) <= 100)]
    check("SecurityScore entre 0 y 100", not bad_score, f"{len(bad_score)} filas")

    if not legacy:
        conf_key = "AnomalyConfidence" if "AnomalyConfidence" in columns else "ThreatProb"
        bad_conf = [r for r in rows if not (0 <= fnum_or(r, conf_key, 0.0) <= 95)]
        check(f"{conf_key} entre 0 y 95 (techo epistémico)", not bad_conf, f"{len(bad_conf)} filas")

    # ---- 5. Integridad de identidad ---------------------------------------------------------
    na_identity = [r for r in rows if r.get("CID") == "N/A" and r.get("FailedHeuristics") != "OK"]
    check("Ninguna anomalía registrada sobre una celda sin identidad", not na_identity,
          f"{len(na_identity)} filas")

    # ---- Resumen de madurez -----------------------------------------------------------------
    print("\nMADUREZ DEL HISTORIAL")
    per_cell = Counter((r["CID"], r["MNC"], r["TAC"], r["MCC"]) for r in rows)
    counts = sorted(per_cell.values())
    median = counts[len(counts) // 2] if counts else 0
    ge20 = sum(1 for c in counts if c >= 20)
    ge30 = sum(1 for c in counts if c >= 30)
    print(f"  Celdas distintas: {len(per_cell)}")
    print(f"  Muestras por celda — mediana: {median} | máx: {max(counts) if counts else 0}")
    print(f"  Celdas con >=20 muestras (H13 aplica percentil P99): {ge20} "
          f"({100 * ge20 / len(per_cell):.1f} %)")
    print(f"  Celdas con >=30 muestras (huella RSRQ/SINR despierta): {ge30} "
          f"({100 * ge30 / len(per_cell):.1f} %)")
    if ge30 == 0:
        notes.append("La huella RF sigue dormida: ninguna celda llega a 30 muestras todavía.")

    times = []
    for r in rows:
        try:
            times.append(datetime.strptime(r["Timestamp"], TS_FORMAT))
        except (ValueError, KeyError):
            pass
    if times:
        times.sort()
        span_days = max((times[-1] - times[0]).days, 1)
        gaps = [(b - a).total_seconds() / 3600 for a, b in zip(times, times[1:])]
        big = [g for g in gaps if g > 6]
        print(f"  Periodo: {times[0].date()} -> {times[-1].date()} ({span_days} días)")
        print(f"  Días distintos con datos: {len({t.date() for t in times})}")
        print(f"  Huecos > 6 h: {len(big)}" + (f" (mayor: {max(big):.1f} h)" if big else ""))
        print(f"  Filas/día de media: {len(rows) / span_days:.1f}")

    # ---- Disponibilidad del Timing Advance ---------------------------------------------------
    # La pregunta "¿mi teléfono reporta TA?" no se podía contestar antes de v2.1 porque el dato
    # no se exportaba. Aquí se contesta con números, no deduciéndola de que una heurística no
    # salte nunca (que puede deberse a que sus condiciones son estrechas).
    if "TA" in columns:
        print("\nTIMING ADVANCE (H6)")
        with_ta = [r for r in rows if fnum(r, "TA") is not None]
        print(f"  Observaciones con TA reportado: {len(with_ta)} de {len(rows)} "
              f"({100 * len(with_ta) / len(rows):.1f} %)")
        if not with_ta:
            notes.append(
                "Tu módem no ha reportado Timing Advance ni una sola vez en este historial. "
                "H6 no puede juzgar geometría en este dispositivo — no es un fallo de la app, es "
                "que el HAL del teléfono no expone el dato."
            )
        else:
            units = Counter((r.get("TAUnit") or "?").strip() for r in with_ta)
            for unit, n in units.most_common():
                usable = sum(1 for r in with_ta
                             if (r.get("TAUnit") or "").strip() == unit
                             and fnum(r, "TAMeters") is not None)
                print(f"    {unit:<12} {n:6d} observaciones, {usable} con distancia derivable")
            metres = [fnum(r, "TAMeters") for r in with_ta if fnum(r, "TAMeters") is not None]
            if metres:
                metres.sort()
                print(f"  Distancia implícita — mín: {int(metres[0])} m | "
                      f"mediana: {int(metres[len(metres) // 2])} m | máx: {int(metres[-1])} m")
                if metres[-1] > 100_000:
                    check("Distancia implícita por TA dentro de lo físicamente posible", False,
                          f"máximo {int(metres[-1])} m — ninguna celda terrestre llega ahí")
            stub = units.get("STUB_ZERO", 0)
            if stub:
                notes.append(
                    f"{stub} observaciones marcadas STUB_ZERO: el módem devuelve 0 en todas las "
                    "celdas, así que no es una medida sino un campo que el firmware no rellena. "
                    "H6 no deduce geometría en este teléfono — correcto, y sin root no hay forma "
                    "de obtener el TA real."
                )
            nr = units.get("NR_RAW", 0)
            if nr:
                notes.append(
                    f"{nr} observaciones con TA de NR. Se registran en crudo pero NO se convierten "
                    "a distancia: la unidad no es verificable (ver TimingAdvanceUnit). Si además "
                    "aparecen TA de LTE en el mismo sitio, comparar ambos es la vía para "
                    "determinar el factor de forma empírica."
                )

    # ---- Verificación externa: la pregunta que esta fase tiene que contestar ----------------
    # Desde v2.1 el estado de verificación NO puntúa: es una etiqueta independiente del score,
    # precisamente para poder cruzarla contra él al final de la fase. Esto imprime las dos mitades
    # de ese cruce — cuánta cobertura tienen las bases públicas en tu zona, y si el estado tiene
    # alguna relación con que las heurísticas fallen.
    if "Radio" not in columns:
        notes.append(
            "Export sin la columna Radio (anterior a la v2.1 final). NetType no la sustituye: esa "
            "cadena viene de TelephonyDisplayInfo y describe el icono del móvil, no la tecnología "
            "de la lectura — la misma celda alterna entre 4G y 5G sin cambiar de identidad."
        )
    else:
        radios = Counter((r.get("Radio") or "?").strip() for r in rows)
        desconocidas = radios.get("UNKNOWN", 0)
        print("\nTECNOLOGÍA DE RADIO (dato firme, no la etiqueta de pantalla)")
        for tec, n in radios.most_common():
            print(f"  {tec:<10} {n:6d} observaciones")
        if desconocidas > len(rows) * 0.2:
            notes.append(
                f"{desconocidas} observaciones con Radio=UNKNOWN ({100 * desconocidas / len(rows):.0f} %). "
                "Si no estás en CDMA, eso apunta a lecturas de CellInfo que el parser no reconoce."
            )

    print("\nVERIFICACIÓN EXTERNA (etiqueta, no puntúa)")
    estados = Counter((r.get("Verified") or "?").strip() for r in rows)
    celdas_por_estado = defaultdict(set)
    for r in rows:
        celdas_por_estado[(r.get("Verified") or "?").strip()].add(
            (r.get("CID"), r.get("MNC"), r.get("TAC"), r.get("MCC"))
        )
    for estado, n in estados.most_common():
        print(f"  {estado:<10} {n:6d} observaciones · {len(celdas_por_estado[estado]):4d} celdas distintas")

    con_fallo = lambda r: (r.get("FailedHeuristics") or "").strip() not in ("", "OK")
    nf = [r for r in rows if (r.get("Verified") or "").strip() == "NOT_FOUND"]
    ver = [r for r in rows if (r.get("Verified") or "").strip() == "VERIFIED"]
    if nf and ver:
        tasa_nf = sum(1 for r in nf if con_fallo(r)) / len(nf) * 100
        tasa_ver = sum(1 for r in ver if con_fallo(r)) / len(ver) * 100
        print(f"  Heurísticas fallidas en celdas NOT_FOUND: {tasa_nf:.1f}%")
        print(f"  Heurísticas fallidas en celdas VERIFIED:  {tasa_ver:.1f}%")
        print("  (si las dos cifras se parecen, el estado de verificación no aporta señal)")

    rechazadas = estados.get("REJECTED", 0)
    if rechazadas:
        notes.append(
            f"{rechazadas} observaciones con la respuesta de la API DESCARTADA (identidad que no "
            "coincide, coordenada no creíble o respuesta incompleta). No son antenas desconocidas: "
            "son respuestas de las que no se puede concluir nada. Si la cifra es alta, el problema "
            "está en la consulta o en la fuente, no en la red que te rodea."
        )
    else:
        notes.append(
            "Todavía no hay celdas de los dos estados (VERIFIED y NOT_FOUND) como para comparar. "
            "Esa comparación es la que decidirá si el verificador externo se queda o se va."
        )

    print("\nOBSERVACIONES DEL HISTORIAL")
    sub = sum(1 for r in rows if (r.get("FailedHeuristics") or "").startswith(SUBTHRESHOLD_PREFIX))
    alarms = sum(
        1 for r in rows
        if (r.get("FailedHeuristics") or "").strip() not in ("", "OK")
        and not (r.get("FailedHeuristics") or "").startswith(SUBTHRESHOLD_PREFIX)
    )
    print(f"  Observaciones sub-umbral registradas: {sub}")
    print(f"  Alarmas (motivo sin prefijo sub-umbral): {alarms}")
    if sub:
        top = Counter()
        for r in rows:
            fh = r.get("FailedHeuristics") or ""
            if fh.startswith(SUBTHRESHOLD_PREFIX):
                for part in fh[len(SUBTHRESHOLD_PREFIX):].split("|"):
                    top[part.strip().split("(")[0].strip()] += 1
        print("  Heurísticas sub-umbral más frecuentes:")
        for reason, n in top.most_common(5):
            print(f"    {n:5d}  {reason}")

    if notes:
        print("\nNOTAS")
        for n in notes:
            print(f"  - {n}")

    print()
    if failures:
        print(f"RESULTADO: {len(failures)} invariante(s) incumplida(s): {', '.join(failures)}")
        return 1
    print("RESULTADO: todas las invariantes se cumplen.")
    return 0


if __name__ == "__main__":
    if len(sys.argv) != 2:
        print(__doc__)
        print("Uso: python3 tools/check_export.py <historial.csv>")
        sys.exit(2)
    sys.exit(main(sys.argv[1]))
