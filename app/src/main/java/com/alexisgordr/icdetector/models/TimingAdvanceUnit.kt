package com.alexisgordr.icdetector.models

/**
 * Unidad en la que viene el Timing Advance de una celda, y la única puerta por la que ese valor
 * puede convertirse en una distancia.
 *
 * ── POR QUÉ EXISTE ───────────────────────────────────────────────────────────────────────────
 * El TA que expone Android no tiene una sola unidad, y hasta v2.1 todos los orígenes acababan en
 * el mismo campo `CellData.timingAdvance`, con el multiplicador elegido a partir de la CADENA de
 * tipo de red. Esa cadena viene de `TelephonyDisplayInfo`: describe el icono que enseña el móvil,
 * no la clase de `CellInfo` de la que se leyó el valor — y en el historial de campo hay 54 celdas
 * que alternan entre la etiqueta 4G y la 5G sin cambiar de identidad. La unidad se decide ahora
 * donde se lee el dato (`CellParser`) y viaja con él.
 *
 * ── LA REGLA ─────────────────────────────────────────────────────────────────────────────────
 * [toMeters] devuelve null siempre que no exista una conversión defendible. H6 (proximidad por TA;
 * la rama geométrica de -40 se retiró en 3.0 por no tener datos en producción) se abstiene entonces
 * de juzgar. No es una carencia: es
 * la política. Una geometría inventada es peor que no tener geometría.
 */
enum class TimingAdvanceUnit {

    /**
     * Índice TA de LTE (`CellSignalStrengthLte.timingAdvance`), 0..1282.
     *
     * Conversión firme: el paso del TA en LTE es 16·Ts con Ts = 1/(15000·2048) s ≈ 0,52 µs de ida
     * y vuelta, es decir **≈78,12 m por unidad**. El extremo del rango encaja con la física —
     * 1282 · 78,12 m ≈ 100 km, el radio máximo de una celda LTE.
     */
    LTE_INDEX,

    /**
     * Índice TA de GSM, en periodos de bit de 3,69 µs de ida y vuelta → **≈554 m por unidad**.
     */
    GSM_INDEX,

    /**
     * Valor leído de `CellSignalStrengthNr.getTimingAdvanceMicros()` (API pública 34+; antes de 3.0,
     * por reflexión).
     *
     * **NO se convierte a distancia. A propósito.** El nombre `NR_RAW` es deliberado: registra de
     * dónde salió el número sin afirmar en qué unidad está, porque eso es exactamente lo que no se
     * puede afirmar.
     *
     * 3.0 — La documentación vigente de Android lo aclara: pese al nombre del método, el valor es
     * el **índice T_A crudo** de 3GPP TS 38.213 §4.2, no microsegundos, con rango **0..3846**
     * (antes la documentación decía "microseconds, 0..1282" citando TS 36.213, de LTE). Aun siendo
     * un índice, no hay un metraje fijo: TS 38.213 §4.2 define N_TA = T_A · 16 · 64 / 2^µ, así que
     * el paso depende de la numerología (µ) de la portadora, que esta app no puede obtener de forma
     * fiable. Por eso sigue sin convertirse a distancia, y cada fabricante puede implementarlo a su
     * manera (no hay forma de verificarlo sin hardware NR SA).

     * `NR_RAW` registra de dónde sale el número (el getter público) sin afirmar una distancia; no se
     * renombra para no cambiar el valor de `TAUnit` en las filas ya exportadas.
     *
     * Se conserva como variante propia —en vez de tratarlo como [UNKNOWN]— porque registrar la
     * procedencia tiene valor forense: si algún día aparece TA de NR junto a TA de LTE en el mismo
     * emplazamiento, comparar ambos es la vía **empírica** para zanjar la cuestión. Para la
     * geometría, mientras tanto, vale exactamente lo mismo que UNKNOWN: nada.
     *
     * *(Nota: durante el desarrollo se llegó a convertir este valor con 150 m/µs — el factor de un
     * trayecto de ida y vuelta — sobre un dato que la documentación describe como de ida. Incluso
     * aceptando la etiqueta de microsegundos al pie de la letra, la conversión estaba equivocada
     * por un factor de 2. Un motivo más para no convertir nada aquí.)*
     */
    NR_RAW,

    /**
     * El módem entrega un 0 constante que NO es una medida — v2.1.
     *
     * Muchos módems (los Exynos de los Pixel entre ellos) no implementan el reporte de Timing
     * Advance, pero en lugar de devolver `CellInfo.UNAVAILABLE` devuelven **0**. Desde fuera es
     * indistinguible de "estás a menos de 78 m de la antena"… salvo por una cosa: un cero real no
     * se sostiene al cambiar de celda y de sitio. Si varias celdas distintas reportan 0 y ninguna
     * reporta jamás otro valor, no es geometría: es un campo sin rellenar.
     *
     * Detectarlo importa porque un 0 permanente **no es inocuo**: alimenta la rama de proximidad
     * de H6 (`metros <= 100`), así que cualquier celda con señal fuerte y sin verificar se llevaría
     * un -15 por "Proximidad anómala (TA)" de forma indefinida. Un falso positivo silencioso y
     * perpetuo, nacido de un campo que el fabricante no rellena.
     *
     * Marcar la unidad así hace que [toMeters] devuelva null por el camino de siempre: H6 se
     * abstiene, la gráfica no dibuja, y el CSV deja constancia de por qué. El valor crudo se sigue
     * registrando: que el módem devuelva 0 es, en sí mismo, un dato sobre ese teléfono.
     *
     * Ver [com.alexisgordr.icdetector.core.TimingAdvanceSanity].
     */
    STUB_ZERO,

    /**
     * Unidad indeterminable: valor raspado del `toString()` de un fabricante (desde 3.0 solo en NR y
     * como contexto). Ni siquiera se sabe si es un índice o un tiempo.
     */
    UNKNOWN;

    /**
     * Distancia implícita en metros, o **null si no existe una conversión defendible** para esta
     * unidad. Un TA negativo no es físico y también devuelve null.
     *
     * Devolver null no es un fallo: es la forma que tiene este tipo de decir "sé de dónde viene
     * este número, y aun así no puedo afirmar a qué distancia estás".
     */
    fun toMeters(timingAdvance: Int): Int? {
        if (timingAdvance < 0) return null
        // 3.0 — Los mismos límites que el parser, también aquí: protegen filas antiguas y entradas
        // ajenas al parser. Fuera de rango no hay distancia (y no puede desbordarse: 1282·78 y
        // 219·554 caben de sobra en un Int).
        return when (this) {
            LTE_INDEX -> if (timingAdvance in LTE_INDEX_RANGE) timingAdvance * 78 else null
            GSM_INDEX -> if (timingAdvance in GSM_INDEX_RANGE) timingAdvance * 554 else null
            NR_RAW -> null      // ver la documentación de NR_RAW: deliberado
            STUB_ZERO -> null   // un cero que no mide nada no es media distancia, es ninguna
            UNKNOWN -> null
        }
    }

    companion object {
        /** Índice TA de LTE válido (3GPP TS 36.213). */
        val LTE_INDEX_RANGE = 0..1282
        /** Índice TA de GSM válido. */
        val GSM_INDEX_RANGE = 0..219
    }

    /** ¿Puede esta unidad sostener una afirmación geométrica? Azúcar legible para las heurísticas. */
    val isUsableForGeometry: Boolean
        get() = this == LTE_INDEX || this == GSM_INDEX
}
