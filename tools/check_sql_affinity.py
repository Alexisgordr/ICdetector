#!/usr/bin/env python3
"""Regression checks for SQLite type-affinity bugs (v2.3.1 verification, 3.0 local-profile retention)."""

from pathlib import Path
import sqlite3
import sys


ROOT = Path(__file__).resolve().parent.parent
DB_HELPER = ROOT / "app/src/main/java/com/alexisgordr/icdetector/storage/CellDbHelper.kt"
SCHEMA_V22 = ROOT / "app/src/main/java/com/alexisgordr/icdetector/storage/SchemaV22.kt"
PROFILE_STORE = ROOT / "app/src/main/java/com/alexisgordr/icdetector/storage/LocalProfileStore.kt"


def require_source_guards() -> None:
    source = DB_HELPER.read_text(encoding="utf-8")
    required = (
        "ABS($COLUMN_API_LAT - CAST(? AS REAL)) <= CAST(? AS REAL)",
        "ABS($COLUMN_API_LON - CAST(? AS REAL)) <= CAST(? AS REAL)",
        # 3.0 (#25): la última fila es la última observada, no la de id más alto.
        '"$COLUMN_ID=(SELECT $COLUMN_ID FROM $TABLE_HISTORY WHERE $identityWhere ORDER BY $NEWEST_FIRST LIMIT 1)"',
    )
    missing = [fragment for fragment in required if fragment not in source]
    if missing:
        raise AssertionError("CellDbHelper perdió las protecciones SQL: " + repr(missing))
    # 3.0: la retención del perfil local compara una EXPRESIÓN (COALESCE) con el corte.
    if 'PRUNE_PROFILES_WHERE = "COALESCE(last_evidence_ms, updated_at_ms) < CAST(? AS INTEGER)"' \
            not in SCHEMA_V22.read_text(encoding="utf-8"):
        raise AssertionError("SchemaV22.PRUNE_PROFILES_WHERE perdió el CAST")
    if "db.delete(P, SchemaV22.PRUNE_PROFILES_WHERE," not in PROFILE_STORE.read_text(encoding="utf-8"):
        raise AssertionError("LocalProfileStore.pruneProfiles no usa PRUNE_PROFILES_WHERE")


def check_numeric_affinity(db: sqlite3.Connection) -> None:
    db.execute("CREATE TABLE coordinates(api_lat REAL, api_lon REAL, mcc TEXT, mnc TEXT, tac TEXT)")
    db.executemany(
        "INSERT INTO coordinates VALUES (?, ?, ?, ?, ?)",
        (
            (52.5, 1.0, "1", "1", "a"),
            (39.0, 2.0, "1", "1", "b"),
            (-33.0, 3.0, "1", "1", "c"),
        ),
    )
    broken = db.execute(
        "SELECT COUNT(*) FROM coordinates WHERE ABS(api_lat - ?) <= ?",
        ("40.0", "0.0001"),
    ).fetchone()[0]
    fixed = db.execute(
        "SELECT COUNT(*) FROM coordinates "
        "WHERE ABS(api_lat - CAST(? AS REAL)) <= CAST(? AS REAL)",
        ("40.0", "0.0001"),
    ).fetchone()[0]
    assert broken == 3, f"La reproducción del bug cambió inesperadamente: {broken}"
    assert fixed == 0, f"CAST no está aplicando comparación numérica: {fixed}"


def check_profile_prune_affinity(db: sqlite3.Connection) -> None:
    """3.0: COALESCE no tiene afinidad; con el corte como texto, INTEGER < TEXT es siempre cierto."""
    db.execute("CREATE TABLE local_profile(context_key TEXT, last_evidence_ms INTEGER, updated_at_ms INTEGER)")
    rows = (("a", 1_790_000_000_000, 1_790_000_000_000), ("b", None, 1_789_900_000_000))
    cutoff = ("1779632000000",)  # 120 días antes: las dos referencias son recientes
    db.executemany("INSERT INTO local_profile VALUES (?, ?, ?)", rows)
    broken = db.execute(
        "SELECT COUNT(*) FROM local_profile WHERE COALESCE(last_evidence_ms, updated_at_ms) < ?", cutoff
    ).fetchone()[0]
    fixed = db.execute(
        "SELECT COUNT(*) FROM local_profile "
        "WHERE COALESCE(last_evidence_ms, updated_at_ms) < CAST(? AS INTEGER)", cutoff
    ).fetchone()[0]
    assert broken == 2, f"La reproducción del bug cambió inesperadamente: {broken}"
    assert fixed == 0, f"CAST no está aplicando comparación numérica: {fixed}"


def check_latest_observation_update(db: sqlite3.Connection) -> None:
    # 3.0 (#25): con varios escritores, la fila escrita en último lugar puede ser una observación
    # anterior. La respuesta se adjunta a la última OBSERVADA; las filas sin instante (anteriores
    # a 3.0) quedan detrás.
    db.execute(
        "CREATE TABLE history("
        "id INTEGER PRIMARY KEY, verified TEXT, api_lat REAL, api_lon REAL, "
        "cid TEXT, mnc TEXT, tac TEXT, mcc TEXT, radio TEXT, observed_at_ms INTEGER)"
    )
    db.executemany(
        "INSERT INTO history VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
        (
            (1, "NOT_FOUND", None, None, "10", "07", "42", "214", "LTE", None),
            (2, "NOT_FOUND", None, None, "10", "07", "42", "214", "LTE", 2000),
            (3, "NOT_FOUND", None, None, "10", "07", "42", "214", "LTE", 1000),
        ),
    )
    db.execute(
        "UPDATE history SET verified=?, api_lat=?, api_lon=? "
        "WHERE id=(SELECT id FROM history "
        "WHERE cid=? AND mnc=? AND tac=? AND mcc=? AND radio=? "
        "ORDER BY observed_at_ms DESC, id DESC LIMIT 1)",
        ("VERIFIED", 40.1, -3.2, "10", "07", "42", "214", "LTE"),
    )
    rows = db.execute(
        "SELECT id, verified, api_lat FROM history ORDER BY id"
    ).fetchall()
    assert rows == [(1, "NOT_FOUND", None), (2, "VERIFIED", 40.1), (3, "NOT_FOUND", None)], rows


def check_rf_stability_uses_identity_index(db: sqlite3.Connection) -> None:
    """3.0 (#7, O1): la consulta de H15 busca por el índice de identidad, no recorre la tabla."""
    db.execute(
        "CREATE TABLE h15(id INTEGER PRIMARY KEY AUTOINCREMENT, timestamp TEXT, cid TEXT, mnc TEXT, "
        "tac TEXT, mcc TEXT, radio TEXT, score INTEGER, failed_heuristics TEXT, pci INTEGER, "
        "arfcn INTEGER, observed_at_ms INTEGER)"
    )
    db.execute("CREATE INDEX idx_h15_identity ON h15(cid, mnc, tac, mcc, radio)")
    db.execute("CREATE INDEX idx_h15_observed ON h15(observed_at_ms)")
    db.execute("CREATE INDEX idx_h15_timestamp ON h15(timestamp)")
    plan = " ".join(
        row[3] for row in db.execute(
            "EXPLAIN QUERY PLAN SELECT pci, arfcn, timestamp, observed_at_ms FROM h15 "
            "WHERE cid=? AND mnc=? AND tac=? AND mcc=? AND radio=? "
            "AND (observed_at_ms>? OR (observed_at_ms IS NULL AND timestamp>?)) "
            "ORDER BY (observed_at_ms IS NOT NULL) ASC, observed_at_ms ASC, timestamp ASC, id ASC",
            (1,) * 7,
        )
    )
    assert "USING INDEX idx_h15_identity" in plan, plan


def main() -> int:
    require_source_guards()
    with sqlite3.connect(":memory:") as db:
        check_numeric_affinity(db)
        check_profile_prune_affinity(db)
        check_latest_observation_update(db)
        check_rf_stability_uses_identity_index(db)
    print("SQL verification regressions: OK")
    return 0


if __name__ == "__main__":
    try:
        sys.exit(main())
    except AssertionError as error:
        print(f"SQL verification regressions: FAILED: {error}", file=sys.stderr)
        sys.exit(1)
