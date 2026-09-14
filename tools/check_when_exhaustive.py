#!/usr/bin/env python3
"""
check_when_exhaustive.py — detecta expresiones `when` incompletas sobre los enums del proyecto.

POR QUÉ EXISTE.
Añadir un valor a un enum rompe, en silencio para quien lo añade, todos los `when` exhaustivos que
lo recorren. El compilador de Kotlin los señala uno a uno... si puedes compilar. Esta comprobación
no necesita el SDK de Android ni Gradle: lee el código fuente, encuentra los enums del proyecto y
comprueba que cada `when` que use sus valores los cubra todos o tenga `else`.

Se ejecuta sin dependencias:

    python3 tools/check_when_exhaustive.py

Código de salida 1 si encuentra alguno incompleto, para poder encadenarlo en CI.

No sustituye al compilador: es una red de seguridad barata para el error más común al ampliar un
enum, y sirve en entornos donde compilar el módulo completo no es posible.
"""

import pathlib
import re
import sys

RAIZ = pathlib.Path(__file__).resolve().parent.parent / "app" / "src"


def enums_del_proyecto(ficheros):
    """{NombreEnum: {VALOR, ...}} para cada `enum class` declarado en el proyecto."""
    enums = {}
    for f, txt in ficheros:
        for m in re.finditer(r"enum\s+class\s+(\w+)[^{]*\{", txt):
            nombre = m.group(1)
            ini = m.end()
            prof, i = 1, ini
            while i < len(txt) and prof > 0:
                if txt[i] == "{":
                    prof += 1
                elif txt[i] == "}":
                    prof -= 1
                i += 1
            cuerpo = txt[ini:i]
            # Los valores van antes del primer ';' (si lo hay): después vienen miembros.
            cabecera = cuerpo.split(";")[0]
            # Se descartan comentarios para no confundir palabras en mayúsculas del texto.
            cabecera = re.sub(r"/\*.*?\*/", " ", cabecera, flags=re.S)
            cabecera = re.sub(r"//[^\n]*", " ", cabecera)
            valores = set(re.findall(r"\b([A-Z][A-Z0-9_]{1,})\b", cabecera))
            if valores:
                enums[nombre] = valores
    return enums


def bloques_when(txt):
    """Cada `when (...) { ... }` del fichero, como (línea, cuerpo)."""
    for m in re.finditer(r"\bwhen\s*\([^)]*\)\s*\{", txt):
        ini = m.end()
        prof, i = 1, ini
        while i < len(txt) and prof > 0:
            if txt[i] == "{":
                prof += 1
            elif txt[i] == "}":
                prof -= 1
            i += 1
        yield txt[: m.start()].count("\n") + 1, txt[ini:i]


def main():
    ficheros = [(f, f.read_text(encoding="utf-8")) for f in RAIZ.rglob("*.kt")]
    enums = enums_del_proyecto(ficheros)
    if not enums:
        print("No se ha encontrado ningún enum. ¿Ruta incorrecta?")
        return 1

    print(f"Enums del proyecto: {', '.join(sorted(enums))}\n")

    incompletos = []
    revisados = 0
    for f, txt in ficheros:
        for linea, cuerpo in bloques_when(txt):
            tiene_else = re.search(r"^\s*else\s*->", cuerpo, re.M) is not None
            # `null ->` cierra la exhaustividad de un `when` sobre un tipo anulable.
            tiene_null = re.search(r"^\s*null\s*->", cuerpo, re.M) is not None
            cubierto = False
            for nombre, valores in enums.items():
                usados = set(re.findall(rf"{nombre}\.([A-Z][A-Z0-9_]*)\s*->", cuerpo))
                if not usados:
                    continue
                cubierto = True
                revisados += 1
                faltan = valores - usados
                if faltan and not (tiene_else or tiene_null):
                    incompletos.append((f, linea, nombre, sorted(faltan)))

            # Ramas sin cualificar: `when (this)` dentro del propio enum, o un `when` sobre una
            # variable ya tipada. Solo se juzga si esas ramas encajan con UN enum y con ninguno
            # más — si hay ambigüedad, callarse es mejor que avisar en falso.
            if cubierto:
                continue
            desnudas = set(re.findall(r"^\s*([A-Z][A-Z0-9_]{1,})\s*->", cuerpo, re.M))
            if not desnudas:
                continue
            candidatos = [(n, v) for n, v in enums.items() if desnudas <= v]
            if len(candidatos) != 1:
                continue
            nombre, valores = candidatos[0]
            revisados += 1
            faltan = valores - desnudas
            if faltan and not (tiene_else or tiene_null):
                incompletos.append((f, linea, nombre, sorted(faltan)))

    for f, linea, nombre, faltan in incompletos:
        rel = f.relative_to(RAIZ.parent.parent)
        print(f"  [FALLO] {rel}:{linea} — when sobre {nombre} sin {', '.join(faltan)}")

    print(f"\n{revisados} expresión(es) `when` sobre enums revisadas.")
    if incompletos:
        print(f"RESULTADO: {len(incompletos)} incompleta(s).")
        return 1
    print("RESULTADO: todas cubren su enum o tienen else.")
    return 0


if __name__ == "__main__":
    sys.exit(main())
