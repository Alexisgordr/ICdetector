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
 * [toMeters] devuelve null siempre que no exista una conversión defendible. H6 —la heurística con
 * la penalización más alta del motor, -40— se abstiene entonces de juzgar. No es una carencia: es
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
     * Valor leído de `CellSignalStrengthNr.getTimingAdvanceMicros()` (API 34+, por reflexión).
     *
     * **NO se convierte a distancia. A propósito.** El nombre `NR_RAW` es deliberado: registra de
     * dónde salió el número sin afirmar en qué unidad está, porque eso es exactamente lo que no se
     * puede afirmar.
     *
     * Hay dos lecturas en circulación y ninguna sobrevive al contraste:
     *
     *  - **"Son microsegundos."** Es lo que dice la documentación oficial, literalmente: *"the
     *    timing advance value for a one way trip from cell to device for NR in microseconds"*,
     *    `Range: 0 us to 1282 us`. Pero entonces el extremo del rango serían 1282 µs · 300 m/µs ≈
     *    **384 km**, más de tres veces el alcance de cualquier celda terrestre. La física la
     *    desmiente.
     *  - **"Es el índice TA bruto de NR."** Encaja mejor con el rango —0..1282 es, exactamente, el
     *    rango del índice TA de **LTE**, y 1282 · 78,12 m ≈ 100 km sí es el máximo real— y explica
     *    que la documentación cite `3GPP TS 36.213`, una especificación de **LTE**, para un valor
     *    de NR. Pero ninguna fuente oficial consultable dice eso; es una inferencia, no un hecho.
     *    Y aunque fuera un índice de NR, tampoco habría un metraje fijo: 3GPP TS 38.213 §4.2
     *    define N_TA = T_A · 16 · 64 / 2^µ, así que el paso depende de la numerología (µ) de la
     *    portadora, que esta app no puede obtener de forma fiable.
     *
     * Súmese que el valor llega **por reflexión**, sobre un método cuyo comportamiento real varía
     * según el fabricante y que no hay forma de verificar sin hardware NR SA en mano.
     *
     * Bautizarlo `NR_MICROS` afirmaba la primera lectura; bautizarlo `NR_RAW_INDEX` afirmaría la
     * segunda. Las dos son afirmaciones que no se sostienen, y cambiar una por otra no es una
     * mejora. `NR_RAW` dice la verdad: es el valor crudo de NR, y no sabemos qué mide.
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
     * Unidad indeterminable: valor raspado del `toString()` de un fabricante. Ni siquiera se sabe
     * si es un índice o un tiempo.
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
        return when (this) {
            LTE_INDEX -> timingAdvance * 78
            GSM_INDEX -> timingAdvance * 554
            NR_RAW -> null      // ver la documentación de NR_RAW: deliberado
            STUB_ZERO -> null   // un cero que no mide nada no es media distancia, es ninguna
            UNKNOWN -> null
        }
    }

    /** ¿Puede esta unidad sostener una afirmación geométrica? Azúcar legible para las heurísticas. */
    val isUsableForGeometry: Boolean
        get() = this == LTE_INDEX || this == GSM_INDEX
}
