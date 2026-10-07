package com.alexisgordr.icdetector.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * v2.10.6 — Con la interfaz en inglés el terminal mostraba líneas a medio traducir: frases largas
 * rotas por entradas cortas aplicadas antes ("La condición anómala…") y mensajes sin entrada.
 * Cada línea es la que generan hoy el servicio, la auditoría y los diagnósticos; tras traducir no
 * debe quedar ninguna palabra en castellano.
 */
class TerminalLocalizationCoverageTest {
    private val spanish = Regex(
        "[áéíóúñ¿¡]|\\b(de|del|la|las|el|los|en|con|sin|por|para|que|una|se|es|ya|al|hay|celda|celdas|" +
            "antena|ciclo|ciclos|muestras|registro|registrado|sitio|movimiento|vecinos|vecina|capacidad|" +
            "madurez|datos|red|respuesta|origen|destino|pero|zonas|recorridos|centros|todavía|aún|sobre|" +
            "fantasma|cifrado|potencia|espectro|portadoras|ubicación|alimentación|verificar|pendiente|" +
            "resultado|alarma|observaciones|reglas|frecuencia|sospechosa|huella|identidad|confirmando)\\b",
        RegexOption.IGNORE_CASE
    )

    private val lines = listOf(
        "[AUDIT] Celda 1234 — índice heurístico: 0% (10/16 reglas evaluadas; 6 sin datos) · sin registro en OpenCellID; no resta puntos porque esa base es incompleta",
        "[AUDIT] Celda 1234 — índice heurístico: 0% (10/16 reglas evaluadas; 6 sin datos) · respuesta descartada o no creíble; no concluye nada sobre la antena",
        "[AUDIT] Celda 1234 — índice heurístico: 0% (10/16 reglas evaluadas; 6 sin datos) · pendiente de verificar",
        "[AUDIT] --- CICLO DE AUDITORÍA (16 REGLAS) · Celda 1234 (214-7-2801) ---",
        "[AUDIT] 7. Espectro Fantasma: SUPERADA",
        "[AUDIT] 9. Cifrado Hardware: NO EVALUADA",
        "[AUDIT] 11. Consistencia Geográfica (Cell ID móvil): SUPERADA",
        "[AUDIT] 13. Potencia vs Histórico (Baseline + huella RSRQ/SINR): FALLIDA",
        "[AUDIT] 15. Estabilidad de Identidad RF (PCI): SUPERADA",
        "[AUDIT] Resultado provisional (falta la verificación): índice heurístico 0% sobre 10 reglas evaluadas; 6 sin datos.",
        "[SYS] Sin alarma, pero con observaciones: Vecinos fantasma",
        "[SYS] ✅ Sin anomalías en las reglas que pudieron evaluarse.",
        "[TA] El módem no reporta Timing Advance en esta celda (H6 no juzga geometría).",
        "[TA] El módem devuelve 0 en todas las celdas (4 distintas comprobadas): no es una medida, es un campo sin rellenar. No se deduce distancia.",
        "[TA] TA=0 (LTE) → a menos de un paso de TA de la antena (< 78 m). No es una medida de 0 m: es el escalón mínimo.",
        "[TA] TA=3 (LTE) → ~234 m de la antena.",
        "[TA] TA=3 (UNKNOWN) — sin conversión defendible; no se usa para geometría.",
        "[TA] Diagnóstico de TA recuperado del arranque anterior: el módem no rellena el campo (4 celdas distintas con 0). No se deduce distancia.",
        "[RADIO] Primaria declarada sin señal utilizable: ciclo en abstención.",
        "[RADIO] Servidora LTE 1301/48 · portadoras secundarias=[LTE 3350]",
        "[SYS] Ubicación continua activa (24/7). Consumo de batería elevado por diseño: las coordenadas son necesarias para H11, H13 y H16.",
        "[SYS] ⚠ Batería crítica (<10 %): ubicación continua en pausa hasta conectar el cargador.",
        "[SYS] Alimentación recuperada: ubicación continua y recolección 24/7 reanudadas.",
        "[GPS] Incidente localizado: 2 registro(s) con coordenadas precisas",
        "[GPS] Fix preciso obtenido (sin registros pendientes de coordenadas)",
        "[GPS] Coordenadas frescas rellenadas en 3 registro(s)",
        "[GPS] Celda ya verificada en DB, sin necesidad de API",
        "[SITE] Protección=SHADOW, sitio=detectado, movimiento=STATIONARY (SPEED), vecinos=3 días, capacidad=FULL_IDENTITY, madurez=MATURE, aplicación=SOMBRA: aplicaría SITE_UNVERIFIED",
        "[SITE] Protección=OFF, sitio=no disponible, movimiento=UNKNOWN (NO_FIX), vecinos=0 días, capacidad=NO_NEIGHBOUR_DATA, madurez=NO_SITE_DATA, aplicación=NO ACTIVA",
        "[RADIO] Ping-Pong observado (sin confirmar; sin tono).",
        "[RADIO] Ping-Pong detectado a 42.0 km/h. Ignorando alerta.",
        "[RADIO] Cambios rápidos de celda observados a 0,0 km/h.",
        "🚨 Efecto Ping-Pong confirmado por TemporalConfidence.",
        "N/A: sin handover reciente; esperando el siguiente para comprobar la movilidad.",
        "⚠️ Tope forense superado (25000 muestras) con solo casos abiertos. No se recortan: una captura en curso no se mutila.",
        "No se pudo registrar el fix GPS preciso: timeout",
        "Fix GPS preciso no disponible en 30 s (timeout)",
        "⚠ Sin credenciales configuradas. Ve a Ajustes para añadir OpenCellID.",
        "OpenCellID: respuesta descartada por coordenada no creíble. No se concluye nada sobre la antena.",
        "Antena no identificada en OpenCellID. Se reintentará en 1 h.",
        "Respuesta descartada: no permite afirmar ni desmentir nada sobre esta antena.",
        "OpenCellID: HTTP 404 (endpoint no encontrado). No dice nada sobre la antena.",
        "OpenCellID: respuesta no interpretable (HTTP 500). Respuesta: <html>",
        "OpenCellID: error 7 de la API. Respuesta: {}",
        "OpenCellID: la respuesta no es de la celda preguntada. No verifica nada, y tampoco demuestra que la antena no esté. Respuesta: {}",
        "OpenCellID: sin conexión o respuesta ilegible.",
        "N/A: sin lectura celular válida.",
        "N/A: Wi-Fi activo; se evita atribuir el contexto de red al enlace celular.",
        "Estabilidad de potencia",
        "N/A: no hay TAC válidos de vecinas para contrastar.",
        "Coherencia geométrica (TA)",
        "Espectro de vecinos",
        "N/A: no hay celdas vecinas disponibles para contrastar el espectro.",
        "Cifrado hardware",
        "N/A: todavía no existe una secuencia temporal utilizable.",
        "N/A: aún no hay observaciones previas geolocalizadas de esta celda.",
        "Baseline y huella RF",
        "Estabilidad de identidad RF",
        "Coherencia de transición celular",
        "N/A: todavía no se ha observado un handover evaluable.",
        "N/A: no hubo cambio de celda.",
        "N/A: el intervalo entre observaciones no permite atribuir el desplazamiento al handover.",
        "N/A: baseline geográfico en aprendizaje (3/5 origen, 1/5 destino).",
        "Handover incoherente: el móvil recorrió 120 m, pero las zonas aprendidas quedan a 4000 m y no se solapan. La celda destino se anunciaba como vecina.",
        "Desplazamiento compatible con las zonas históricas (120 m recorridos; centros a 800 m).",
        "La celda destino ya era vecina visible antes del handover.",
        "Inconsistencia RF (PCI/ARFCN) detectada a gran distancia",
        "Multitud de MNCs",
        "Frecuencia (ARFCN) 5G sospechosa",
        "Frecuencia (EARFCN) 4G sospechosa",
        "Cifrado de red anulado (A5/0)",
        "Efecto Ping-Pong (Cambios rápidos en parado)",
        "Huella RF incoherente con el historial (RSRQ/SINR muy desviados)",
        "Downgrade de banda forzado (1800MHz→800MHz, B3→B20)",
        "Identidad RF inestable: misma Cell ID alternando PCI en la misma portadora (posible clon)",
        "[2/3 ciclos confirmando]",
        "[sub-umbral] Multitud de MNCs",
        "[sub-umbral] Cambio en identidad celular consolidada (%)",
        "[sub-umbral] Huella RF incoherente con el historial (RSRQ/SINR muy desviados)",
        "[SERVICIO] Estado de servicio: EN SERVICIO · red=21407 (Movistar) · SIM=21407 · datos=LTE no registrado · roaming=no"
    )

    @Test fun `no spanish words remain in english terminal lines`() {
        val leftovers = lines.map { it to localizeTerminalLine(it) }
            .filter { (_, translated) -> spanish.containsMatchIn(translated) }
            .map { (source, translated) -> "$source\n  → $translated" }
        assertTrue(leftovers.joinToString("\n"), leftovers.isEmpty())
    }

    @Test fun `long phrases win over the short words they contain`() {
        assertEquals("Hardware ciphering", localizeTerminalLine("Cifrado hardware"))
        assertEquals("[2/3 cycles confirming]", localizeTerminalLine("[2/3 ciclos confirmando]"))
    }

    @Test fun `subthreshold prefix is translated for display only`() {
        // v2.10.8 — El prefijo guardado (SUBTHRESHOLD_PREFIX) no cambia; solo se traduce al mostrarlo.
        assertEquals("[sub-threshold] Many MNCs", localizeTerminalLine("[sub-umbral] Multitud de MNCs"))
        assertEquals("[sub-umbral]", com.alexisgordr.icdetector.models.SUBTHRESHOLD_PREFIX)
    }

    @Test fun `technical values survive translation`() {
        val translated = localizeTerminalLine(
            "Downgrade de banda forzado (1800MHz→800MHz, B3→B20)"
        )
        assertTrue(translated, translated.contains("(1800MHz→800MHz, B3→B20)"))
        val site = localizeTerminalLine("[SITE] Protección=SHADOW, sitio=detectado, vecinos=3 días, capacidad=FULL_IDENTITY")
        assertTrue(site, site.contains("SHADOW") && site.contains("neighbours=3 days, capability=FULL_IDENTITY"))
    }
}
