# Historiales para reproducir (replay)

Deja aquí CSV exportados desde la app y `CsvReplayTest` los volverá a pasar por el motor entero
(`ThreatAnalyzer` → `TemporalConfidence` → alarma), contando cuántas alarmas habría producido la
versión actual sobre datos reales. Sin ficheros, el test pasa sin hacer nada.

## Privacidad — importante

Un export real lleva las coordenadas GPS de dónde has estado. **Los `.csv` de esta carpeta están
en `.gitignore` a propósito y no deben subirse nunca a un repositorio público.**

Si quieres compartir un caso concreto, vacía antes las columnas `Lat`, `Lon`, `ApiLat` y `ApiLon`.
Las heurísticas que el replay ejercita no usan ninguna de ellas, así que no se pierde nada:

```bash
python3 - <<'PY'
import csv, sys
src, dst = "historial.csv", "historial-anonimo.csv"
with open(src, newline="", encoding="utf-8") as f, open(dst, "w", newline="", encoding="utf-8") as o:
    r = csv.DictReader(f); w = csv.DictWriter(o, fieldnames=r.fieldnames); w.writeheader()
    for row in r:
        for k in ("Lat", "Lon", "ApiLat", "ApiLon"):
            if k in row: row[k] = ""
        w.writerow(row)
print("escrito", dst)
PY
```
