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

# v2.10.4 — Contexto de radio, añadido al final. Solo recolección: no entra en ninguna heurística.
RADIO_CONTEXT_COLUMNS_V2104 = [
    "ServingConnection", "BandwidthKHz", "Bands", "AdditionalPlmns", "CsgIndicator",
    "CsgIdentity", "CsgName", "SecondaryCarriers", "ServiceState", "NetworkOperator",
    "SimOperator", "NetworkRoaming",
]
EXPECTED_COLUMNS_V2104 = EXPECTED_COLUMNS_V21 + RADIO_CONTEXT_COLUMNS_V2104
# 3.0 (#23) — Instante inequívoco en UTC. Vacío en filas anteriores a 3.0 (desconocido).
EXPECTED_COLUMNS_V30 = EXPECTED_COLUMNS_V2104 + [
    "ObservedAtUtc", "NotEvaluatedHeuristics", "GpsAccuracyM", "AppVersion", "ExportDevice", "ExportAndroid",
]
# Cortes de dataset: a partir de esta versión cambió cómo se evalúa la regla indicada.
DATASET_CUTS = [
    ("2.10.9", "H10 (Ping-Pong)"),
    ("2.10.10", "H8 (frecuencia)"),
    ("3.0.0", "instante UTC y cobertura por fila (metodología)"),
]
HEURISTIC_IDS = [f"H{i}" for i in range(1, 17)]
# 3.0 (#32) — Rangos EARFCN de bajada por banda, iguales que BandPlan.kt (lo comprueba un test).
LTE_EARFCN_RANGES = {
    1: (0, 599),
    2: (600, 1199),
    3: (1200, 1949),
    4: (1950, 2399),
    5: (2400, 2649),
    7: (2750, 3449),
    8: (3450, 3799),
    9: (3800, 4149),
    10: (4150, 4749),
    11: (4750, 4949),
    12: (5010, 5179),
    13: (5180, 5279),
    14: (5280, 5379),
    17: (5730, 5849),
    18: (5850, 5999),
    19: (6000, 6149),
    20: (6150, 6449),
    21: (6450, 6599),
    22: (6600, 7399),
    24: (7700, 8039),
    25: (8040, 8689),
    26: (8690, 9039),
    27: (9040, 9209),
    28: (9210, 9659),
    29: (9660, 9769),
    30: (9770, 9869),
    31: (9870, 9919),
    32: (9920, 10359),
    33: (36000, 36199),
    34: (36200, 36349),
    35: (36350, 36949),
    36: (36950, 37549),
    37: (37550, 37749),
    38: (37750, 38249),
    39: (38250, 38649),
    40: (38650, 39649),
    41: (39650, 41589),
    42: (41590, 43589),
    43: (43590, 45589),
    44: (45590, 46589),
    45: (46590, 46789),
    46: (46790, 54539),
    47: (54540, 55239),
    48: (55240, 56739),
    49: (56740, 58239),
    50: (58240, 59089),
    51: (59090, 59139),
    52: (59140, 60139),
    53: (60140, 60254),
    54: (60255, 60304),
    65: (65536, 66435),
    66: (66436, 67335),
    67: (67336, 67535),
    68: (67536, 67835),
    69: (67836, 68335),
    70: (68336, 68585),
    71: (68586, 68935),
    72: (68936, 68985),
    73: (68986, 69035),
    74: (69036, 69465),
    75: (69466, 70315),
    76: (70316, 70365),
    85: (70366, 70545),
    87: (70546, 70595),
    88: (70596, 70645),
    103: (70646, 70655),
    106: (70656, 70705),
    107: (70706, 71105),
    108: (71106, 73385),
    111: (73386, 73485),
    112: (73486, 74865),
    113: (74866, 75785),
}
# La app no acepta fixes con una precisión peor que esta (LocationCollectionController).
MAX_ACCEPTED_ACCURACY_M = 100.0
VAGUE_ACCURACY_M = 50.0
UTC_FORMAT = "%Y-%m-%dT%H:%M:%S.%fZ"
SERVING_CONNECTION_VALUES = {"PRIMARY_SERVING", "SECONDARY_SERVING", "NONE", "UNKNOWN"}
SERVICE_STATE_VALUES = {"IN_SERVICE", "OUT_OF_SERVICE", "EMERGENCY_ONLY", "POWER_OFF", "UNKNOWN"}

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


def row_instant(row):
    """Instante UTC de la fila (datetime sin zona, en UTC), o None en filas anteriores a 3.0."""
    raw = (row.get("ObservedAtUtc") or "").strip()
    if not raw:
        return None
    try:
        return datetime.strptime(raw, UTC_FORMAT)
    except ValueError:
        return None


def instant_issues(rows):
    """
    Filas cuyo texto local no encaja con su instante UTC. La diferencia entre ambos es la
    zona horaria del momento: tiene que estar entre -14 h y +14 h y ser múltiplo de 15 min.
    Una fila con ObservedAtUtc ilegible también cuenta como problema.
    """
    issues = []
    for r in rows:
        raw = (r.get("ObservedAtUtc") or "").strip()
        if not raw:
            continue
        utc = row_instant(r)
        try:
            local = datetime.strptime(r.get("Timestamp") or "", TS_FORMAT)
        except ValueError:
            local = None
        if utc is None or local is None:
            issues.append(r)
            continue
        offset_s = (local - utc.replace(microsecond=0)).total_seconds()
        if abs(offset_s) > 14 * 3600 or round(offset_s) % 900 != 0:
            issues.append(r)
    return issues


def lte_band_of(earfcn):
    for band, (lo, hi) in LTE_EARFCN_RANGES.items():
        if lo <= earfcn <= hi:
            return band
    return None


def bands_outside_table(rows):
    """
    3.0 (#32) — Filas LTE con `Bands` informado por el módem pero cuyo EARFCN no está en la tabla
    de la app. Antes de 3.0 esas filas dejaban H14 en N/A; desde 3.0 se usa la banda declarada.
    Devuelve (filas LTE con Bands, de ellas fuera de tabla, Counter de bandas declaradas fuera).
    """
    with_bands, outside, declared = 0, 0, Counter()
    for r in rows:
        if (r.get("Radio") or "") != "LTE" or not (r.get("Bands") or "").strip():
            continue
        with_bands += 1
        earfcn = fnum(r, "ARFCN")
        if earfcn is None or lte_band_of(int(earfcn)) is None:
            outside += 1
            declared[(r.get("Bands") or "").strip()] += 1
    return with_bands, outside, declared


def evaluation_coverage(rows):
    """
    3.0 (#29) — Por regla: (filas en que se evaluó, filas con el dato). Solo cuentan las filas
    con NotEvaluatedHeuristics informado; las anteriores a 3.0 lo tienen vacío y son
    desconocidas, no "evaluadas". `NONE` significa que se evaluaron todas.
    """
    known = 0
    evaluated = {h: 0 for h in HEURISTIC_IDS}
    for r in rows:
        raw = (r.get("NotEvaluatedHeuristics") or "").strip()
        if not raw:
            continue
        known += 1
        skipped = set() if raw == "NONE" else {x.strip() for x in raw.split(";") if x.strip()}
        for h in HEURISTIC_IDS:
            if h not in skipped:
                evaluated[h] += 1
    return {h: (evaluated[h], known) for h in HEURISTIC_IDS}


def gps_accuracy_issues(rows):
    """
    3.0 (#29) — Filas 3.0 con posición pero sin precisión, o con una precisión que la app nunca
    acepta (>= 100 m, negativa o ilegible). Las filas anteriores a 3.0 no se juzgan.
    """
    issues = []
    for r in rows:
        if not (r.get("ObservedAtUtc") or "").strip():
            continue
        has_fix = fnum(r, "Lat") is not None and fnum(r, "Lon") is not None
        raw = (r.get("GpsAccuracyM") or "").strip()
        if not raw:
            if has_fix:
                issues.append(r)
            continue
        acc = fnum(r, "GpsAccuracyM")
        if not has_fix or acc is None or acc < 0 or acc >= MAX_ACCEPTED_ACCURACY_M:
            issues.append(r)
    return issues


def version_key(version):
    """'3.0.0-beta1' -> (3, 0, 0). Una pre-versión cuenta como su versión base."""
    base = (version or "").strip().split("-")[0]
    parts = []
    for p in base.split("."):
        if not p.isdigit():
            return None
        parts.append(int(p))
    return tuple(parts + [0] * (3 - len(parts))) if parts else None


def dataset_cut_summary(rows):
    """
    3.0 (#30) — Filas por versión de la app y, por cada corte, cuántas filas con versión quedan
    antes y después. Las filas sin AppVersion (anteriores a 3.0) se cuentan aparte: su versión
    es desconocida y el corte solo se les puede aplicar por fecha.
    """
    versions = Counter((r.get("AppVersion") or "").strip() for r in rows)
    unknown = versions.pop("", 0)
    cuts = []
    for cut, rule in DATASET_CUTS:
        ck = version_key(cut)
        before = sum(n for v, n in versions.items() if version_key(v) is not None and version_key(v) < ck)
        after = sum(n for v, n in versions.items() if version_key(v) is not None and version_key(v) >= ck)
        cuts.append((cut, rule, before, after))
    return versions, unknown, cuts


def calendar_period_stats(times, row_count):
    """Periodo inclusivo y densidad solo sobre días que realmente contienen observaciones."""
    dates = {t.date() for t in times}
    if not dates:
        return 0, 0, 0.0
    calendar_days = (max(dates) - min(dates)).days + 1
    distinct_days = len(dates)
    return calendar_days, distinct_days, row_count / distinct_days


def ta_unit_diagnostic(unit_rows):
    """Separa distancia calculable de ceros LTE legacy potencialmente rellenados por el módem."""
    usable = [r for r in unit_rows if fnum(r, "TAMeters") is not None]
    zero_lte = [r for r in unit_rows if fnum(r, "TA") == 0]
    possible_legacy_stub = (
        len(unit_rows) >= 10
        and len(zero_lte) / len(unit_rows) >= 0.90
    )
    reliable = len(usable)
    if possible_legacy_stub:
        reliable -= sum(1 for r in zero_lte if fnum(r, "TAMeters") is not None)
    return len(usable), reliable, possible_legacy_stub, len(zero_lte)


TA_METERS_PER_UNIT = {"LTE_INDEX": 78, "GSM_INDEX": 554}
TA_UNITS = {"LTE_INDEX", "GSM_INDEX", "NR_RAW", "STUB_ZERO", "UNKNOWN"}


def ta_consistency_issues(rows):
    """Filas cuyo TA, TAUnit y TAMeters se contradicen. Pura, para poder probarla.

    Reproduce cómo escribe la app esas tres columnas (ExportUtils.csvRow y
    TimingAdvanceUnit.toMeters):
      - sin TA, TAUnit y TAMeters van vacíos;
      - con TA, TAUnit es una unidad conocida;
      - solo LTE_INDEX (×78) y GSM_INDEX (×554) dan metros, y solo con TA >= 0;
      - NR_RAW, STUB_ZERO y UNKNOWN nunca dan metros: no hay conversión defendible.
    """
    def val(r, key):
        return (r.get(key) or "").strip()

    issues = []
    for r in rows:
        ta, unit, metres = fnum(r, "TA"), val(r, "TAUnit"), fnum(r, "TAMeters")
        if ta is None:
            ok = not unit and metres is None and not val(r, "TAMeters")
        elif unit not in TA_UNITS:
            ok = False
        elif unit in TA_METERS_PER_UNIT and ta >= 0:
            ok = metres is not None and metres == ta * TA_METERS_PER_UNIT[unit]
        else:
            ok = not val(r, "TAMeters")
        if not ok:
            issues.append(r)
    return issues


def radio_context_summary(rows):
    """Resumen del contexto de radio v2.10.4. Puro, para poder probarlo.

    Devuelve un dict con recuentos. Las filas anteriores a v2.10.4 tienen estas columnas vacías y
    no cuentan en ningún sentido: vacío significa "no se recogía", no "no había".
    """
    def val(r, key):
        return (r.get(key) or "").strip()

    with_context = [r for r in rows if val(r, "ServingConnection")]
    connection = Counter(val(r, "ServingConnection") for r in with_context)
    service = Counter(val(r, "ServiceState") for r in rows if val(r, "ServiceState"))
    invalid_connection = [r for r in with_context if val(r, "ServingConnection") not in SERVING_CONNECTION_VALUES]
    invalid_service = [r for r in rows if val(r, "ServiceState") and val(r, "ServiceState") not in SERVICE_STATE_VALUES]
    secondary = [r for r in with_context if val(r, "SecondaryCarriers")]
    csg = [r for r in with_context if val(r, "CsgIndicator") == "1"]
    operator_mismatch = [
        r for r in rows
        if val(r, "NetworkOperator") and val(r, "SimOperator")
        and val(r, "NetworkOperator") != val(r, "SimOperator")
        and val(r, "NetworkRoaming") == "0"
    ]
    return {
        "rows_with_context": len(with_context),
        "connection": connection,
        "service": service,
        "invalid_connection": len(invalid_connection),
        "invalid_service": len(invalid_service),
        "with_secondary": len(secondary),
        "csg": len(csg),
        "operator_mismatch": len(operator_mismatch),
    }


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
    # 3.0 (#23) — Las filas con instante UTC se ordenan por él (sin saltos falsos en el cambio
    # de hora); las anteriores, por su texto local como antes. Las dos series no se mezclan.
    legacy_fixes, instant_fixes = [], []
    for r in rows:
        lat, lon = fnum(r, "Lat"), fnum(r, "Lon")
        if lat is None or lon is None:
            continue
        instant = row_instant(r)
        if instant is not None:
            instant_fixes.append((instant, lat, lon, r.get("Timestamp", "")))
            continue
        try:
            ts = datetime.strptime(r["Timestamp"], TS_FORMAT)
        except (ValueError, KeyError):
            continue
        legacy_fixes.append((ts, lat, lon, r["Timestamp"]))

    jumps = []
    for fixes in (legacy_fixes, instant_fixes):
        fixes.sort(key=lambda x: x[0])
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

    # ---- 3b. Instante UTC (3.0) ----------------------------------------------------------
    if "ObservedAtUtc" in columns:
        bad_instant = instant_issues(rows)
        check(
            "ObservedAtUtc coherente con la hora local (zona entre -14 h y +14 h)",
            not bad_instant,
            f"{len(bad_instant)} filas (p. ej. {bad_instant[0].get('Timestamp')} / "
            f"{bad_instant[0].get('ObservedAtUtc')!r})" if bad_instant else "",
        )
        without = sum(1 for r in rows if not (r.get("ObservedAtUtc") or "").strip())
        if without:
            notes.append(
                f"{without} filas sin ObservedAtUtc: son anteriores a 3.0 y su instante es "
                "desconocido (solo hay hora local sin zona). No se reconstruye."
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

    if "TAUnit" in columns:
        bad_ta = ta_consistency_issues(rows)
        check(
            "TA, TAUnit y TAMeters coherentes entre sí",
            not bad_ta,
            f"{len(bad_ta)} filas (p. ej. {bad_ta[0].get('Timestamp')} TA={bad_ta[0].get('TA')!r} "
            f"TAUnit={bad_ta[0].get('TAUnit')!r} TAMeters={bad_ta[0].get('TAMeters')!r})"
            if bad_ta else "",
        )

    # ---- 5. Integridad de identidad ---------------------------------------------------------
    na_identity = [r for r in rows if r.get("CID") == "N/A" and r.get("FailedHeuristics") != "OK"]
    check("Ninguna anomalía registrada sobre una celda sin identidad", not na_identity,
          f"{len(na_identity)} filas")

    # ---- Resumen de madurez -----------------------------------------------------------------
    if "NotEvaluatedHeuristics" in columns:
        bad_acc = gps_accuracy_issues(rows)
        check(
            "GpsAccuracyM presente con cada posición 3.0 y por debajo de 100 m",
            not bad_acc,
            f"{len(bad_acc)} filas (p. ej. {bad_acc[0].get('Timestamp')} "
            f"GpsAccuracyM={bad_acc[0].get('GpsAccuracyM')!r})" if bad_acc else "",
        )

        coverage = evaluation_coverage(rows)
        known = next(iter(coverage.values()))[1]
        print("\nCOBERTURA DE EVALUACIÓN (3.0)")
        if known == 0:
            print("  Ninguna fila con NotEvaluatedHeuristics: todas son anteriores a 3.0.")
        else:
            print(f"  Filas con el dato: {known} de {len(rows)} (el resto es anterior a 3.0: desconocido)")
            for h in HEURISTIC_IDS:
                ev, total = coverage[h]
                print(f"    {h:>3}: evaluada en {100 * ev / total:5.1f} % ({ev}/{total})")
        accuracies = [fnum(r, "GpsAccuracyM") for r in rows if fnum(r, "GpsAccuracyM") is not None]
        if accuracies:
            vague = sum(1 for a in accuracies if a > VAGUE_ACCURACY_M)
            print(f"  Precisión GPS: {len(accuracies)} filas; > {VAGUE_ACCURACY_M:.0f} m: {vague} "
                  f"({100 * vague / len(accuracies):.1f} %)")
            if vague:
                notes.append(
                    f"{vague} posiciones con precisión peor que {VAGUE_ACCURACY_M:.0f} m. La detección "
                    "ya las limita (H16 exige <= 75 m y Stable-Site <= 50 m), pero conviene filtrarlas "
                    "al analizar la geometría."
                )

    if "AppVersion" in columns:
        versions, unknown, cuts = dataset_cut_summary(rows)
        print("\nVERSIONES Y CORTES DE DATASET (3.0)")
        for v, n in sorted(versions.items(), key=lambda kv: version_key(kv[0]) or ()):
            print(f"  {v}: {n} filas")
        if unknown:
            print(f"  desconocida (anterior a 3.0): {unknown} filas — el corte solo se aplica por fecha")
        for cut, rule, before, after in cuts:
            if before and after:
                print(f"  Corte {cut} ({rule}): {before} filas antes y {after} después — no mezclarlas")
        devices = Counter(
            ((r.get("ExportDevice") or "").strip(), (r.get("ExportAndroid") or "").strip()) for r in rows
        )
        if len(devices) > 1:
            notes.append(
                f"El fichero une exports de {len(devices)} teléfonos/Android distintos: "
                + "; ".join(f"{d or '?'} {a}".strip() for d, a in devices)
                + ". Analízalos por separado."
            )

    if "Bands" in columns:
        with_bands, outside, declared = bands_outside_table(rows)
        if with_bands:
            print("\nBANDAS LTE (3.0)")
            print(f"  Filas LTE con Bands del módem: {with_bands}; con EARFCN fuera de la tabla: {outside}")
            for b, n in declared.most_common(5):
                print(f"    Bands={b}: {n} filas")

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
        calendar_days, distinct_days, rows_per_observed_day = calendar_period_stats(times, len(rows))
        gaps = [(b - a).total_seconds() / 3600 for a, b in zip(times, times[1:])]
        big = [g for g in gaps if g > 6]
        print(f"  Periodo calendario: {times[0].date()} -> {times[-1].date()} "
              f"({calendar_days} días, ambos extremos incluidos)")
        print(f"  Días distintos con datos: {distinct_days}")
        print(f"  Huecos > 6 h: {len(big)}" + (f" (mayor: {max(big):.1f} h)" if big else ""))
        print(f"  Filas/día con datos de media: {rows_per_observed_day:.1f}")

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
                unit_rows = [r for r in with_ta if (r.get("TAUnit") or "").strip() == unit]
                usable, reliable, possible_stub, zero_count = ta_unit_diagnostic(unit_rows)
                if unit == "LTE_INDEX" and possible_stub:
                    print(f"    {unit:<12} {n:6d} observaciones, {reliable} con distancia "
                          f"derivable fiable; {zero_count} TA=0 ambiguos (posible stub legacy)")
                    notes.append(
                        f"Patrón agregado legacy: {zero_count}/{n} filas LTE_INDEX tienen TA=0. "
                        "TA=0 puede ser legítimo individualmente, pero esta concentración puede "
                        "proceder del antiguo stub del módem; no se cuentan automáticamente como "
                        "distancia derivable fiable."
                    )
                else:
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

    if "ServingConnection" in columns:
        rc = radio_context_summary(rows)
        print("\nCONTEXTO DE RADIO (v2.10.4, solo recolección — no puntúa)")
        print(f"  Filas con contexto de radio: {rc['rows_with_context']} de {len(rows)}")
        for estado, n in rc["connection"].most_common():
            print(f"    conexión {estado:<18} {n:6d}")
        print(f"  Filas con portadoras secundarias: {rc['with_secondary']}")
        print(f"  Filas en celda de grupo cerrado (CSG / posible femtocelda): {rc['csg']}")
        for estado, n in rc["service"].most_common():
            print(f"    servicio {estado:<18} {n:6d}")
        print(f"  Red anunciada distinta de la SIM sin roaming: {rc['operator_mismatch']}")
        check(
            "ServingConnection y ServiceState solo contienen valores conocidos",
            rc["invalid_connection"] == 0 and rc["invalid_service"] == 0,
            f"{rc['invalid_connection']} conexión / {rc['invalid_service']} servicio desconocidos",
        )
        if rc["rows_with_context"] and not rc["connection"].get("PRIMARY_SERVING"):
            notes.append(
                "Ninguna fila con ServingConnection=PRIMARY_SERVING: este módem no rellena el estado "
                "de conexión, así que la app elige la servidora como antes (primera registrada)."
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
    else:
        # v2.10.6 — Antes este aviso colgaba del `else` de REJECTED y salía siempre que no
        # hubiera respuestas descartadas, aunque ya existieran celdas VERIFIED y NOT_FOUND.
        notes.append(
            "Todavía no hay celdas de los dos estados (VERIFIED y NOT_FOUND) como para comparar. "
            "Esa comparación es la que decidirá si el verificador externo se queda o se va."
        )

    rechazadas = estados.get("REJECTED", 0)
    if rechazadas:
        notes.append(
            f"{rechazadas} observaciones con la respuesta de la API DESCARTADA (identidad que no "
            "coincide, coordenada no creíble o respuesta incompleta). No son antenas desconocidas: "
            "son respuestas de las que no se puede concluir nada. Si la cifra es alta, el problema "
            "está en la consulta o en la fuente, no en la red que te rodea."
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
