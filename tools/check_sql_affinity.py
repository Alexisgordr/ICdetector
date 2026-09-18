#!/usr/bin/env python3
"""Regression checks for the two SQLite verification bugs fixed in v2.3.1."""

from pathlib import Path
import sqlite3
import sys


ROOT = Path(__file__).resolve().parent.parent
DB_HELPER = ROOT / "app/src/main/java/com/alexisgordr/icdetector/storage/CellDbHelper.kt"


def require_source_guards() -> None:
    source = DB_HELPER.read_text(encoding="utf-8")
    required = (
        "ABS($COLUMN_API_LAT - CAST(? AS REAL)) <= CAST(? AS REAL)",
        "ABS($COLUMN_API_LON - CAST(? AS REAL)) <= CAST(? AS REAL)",
        '"$COLUMN_ID=(SELECT MAX($COLUMN_ID) FROM $TABLE_HISTORY WHERE $identityWhere)"',
    )
    missing = [fragment for fragment in required if fragment not in source]
    if missing:
        raise AssertionError("CellDbHelper perdió las protecciones SQL: " + repr(missing))


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


def check_latest_observation_update(db: sqlite3.Connection) -> None:
    db.execute(
        "CREATE TABLE history("
        "id INTEGER PRIMARY KEY, verified TEXT, api_lat REAL, api_lon REAL, "
        "cid TEXT, mnc TEXT, tac TEXT, mcc TEXT, radio TEXT)"
    )
    db.executemany(
        "INSERT INTO history VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)",
        (
            (1, "NOT_FOUND", None, None, "10", "07", "42", "214", "LTE"),
            (2, "NOT_FOUND", None, None, "10", "07", "42", "214", "LTE"),
        ),
    )
    db.execute(
        "UPDATE history SET verified=?, api_lat=?, api_lon=? "
        "WHERE id=(SELECT MAX(id) FROM history "
        "WHERE cid=? AND mnc=? AND tac=? AND mcc=? AND radio=?)",
        ("VERIFIED", 40.1, -3.2, "10", "07", "42", "214", "LTE"),
    )
    rows = db.execute(
        "SELECT id, verified, api_lat FROM history ORDER BY id"
    ).fetchall()
    assert rows == [(1, "NOT_FOUND", None), (2, "VERIFIED", 40.1)], rows


def main() -> int:
    require_source_guards()
    with sqlite3.connect(":memory:") as db:
        check_numeric_affinity(db)
        check_latest_observation_update(db)
    print("SQL verification regressions: OK")
    return 0


if __name__ == "__main__":
    try:
        sys.exit(main())
    except AssertionError as error:
        print(f"SQL verification regressions: FAILED: {error}", file=sys.stderr)
        sys.exit(1)
