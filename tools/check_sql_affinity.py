#!/usr/bin/env python3
"""Regression checks for SQLite type-affinity bugs (3.0 local-profile retention) and the H15 query plan.

3.1.0: the external-verification queries (API coordinates, latest-row update) were removed with
the verification itself, and so were their checks.
"""

from pathlib import Path
import sqlite3
import sys


ROOT = Path(__file__).resolve().parent.parent
SCHEMA_V22 = ROOT / "app/src/main/java/com/alexisgordr/icdetector/storage/SchemaV22.kt"
PROFILE_STORE = ROOT / "app/src/main/java/com/alexisgordr/icdetector/storage/LocalProfileStore.kt"


def require_source_guards() -> None:
    # 3.0: la retención del perfil local compara una EXPRESIÓN (COALESCE) con el corte.
    if 'PRUNE_PROFILES_WHERE = "COALESCE(last_evidence_ms, updated_at_ms) < CAST(? AS INTEGER)"' \
            not in SCHEMA_V22.read_text(encoding="utf-8"):
        raise AssertionError("SchemaV22.PRUNE_PROFILES_WHERE perdió el CAST")
    if "db.delete(P, SchemaV22.PRUNE_PROFILES_WHERE," not in PROFILE_STORE.read_text(encoding="utf-8"):
        raise AssertionError("LocalProfileStore.pruneProfiles no usa PRUNE_PROFILES_WHERE")


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
        check_profile_prune_affinity(db)
        check_rf_stability_uses_identity_index(db)
    print("SQL verification regressions: OK")
    return 0


if __name__ == "__main__":
    try:
        sys.exit(main())
    except AssertionError as error:
        print(f"SQL verification regressions: FAILED: {error}", file=sys.stderr)
        sys.exit(1)
