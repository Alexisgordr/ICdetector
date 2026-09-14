package com.alexisgordr.icdetector.models

/**
 * Resultado de consultar a las bases públicas (WiGLE / OpenCellID) por una celda.
 *
 * ── POR QUÉ HAY CINCO Y NO CUATRO ────────────────────────────────────────────────────────────
 * Durante toda la v2.0 y buena parte de la v2.1 hubo cuatro estados, y [NOT_FOUND] hacía de cajón
 * de sastre: la celda no está en la base, la respuesta era de otra celda, la coordenada no era
 * creíble, el JSON venía raro… todo acababa en el mismo sitio. Y eso es un error de significado,
 * no de estilo: "esta antena no está registrada" es una **afirmación sobre la antena**, mientras
 * que "la respuesta no me sirve" es una afirmación **sobre la respuesta**. Meterlas en la misma
 * casilla convierte un problema nuestro en un dato sobre el mundo, y ese dato acaba escrito en el
 * historial que luego se analiza.
 *
 * [REJECTED] separa las dos. El historial de la fase de recolección distingue ahora entre lo que
 * las bases públicas dicen y lo que la app no ha podido confirmar, que es justo la distinción que
 * hace falta para decidir en diciembre si el verificador externo aporta algo.
 */
enum class VerificationStatus {

    /** Todavía no se ha consultado, o la consulta está en vuelo. */
    PENDING,

    /**
     * La base respondió con **la celda que se preguntó** —identidad comprobada campo a campo— y la
     * respuesta superó las comprobaciones de coordenada.
     */
    VERIFIED,

    /**
     * Negativa explícita y fiable. Solo dos respuestas en el mundo entran aquí:
     *  - OpenCellID con `code == 1` (literalmente "cell not found"),
     *  - WiGLE con `success: true` y `results: []` (consulta válida, cero resultados).
     *
     * Cualquier otra cosa que "no sea un sí" es [REJECTED] o [ERROR], nunca esto.
     */
    NOT_FOUND,

    /**
     * La base devolvió algo, pero la respuesta **no supera nuestras comprobaciones**: identidad que
     * no coincide, coordenada centinela, coordenada a una distancia imposible, respuesta incompleta.
     *
     * No dice nada sobre la antena. No penaliza, no cuenta como negativa y se vuelve a intentar
     * pasado el TTL. Existe para que el historial no registre como "antena desconocida" lo que en
     * realidad fue "no me fío de esta respuesta".
     */
    REJECTED,

    /**
     * No se ha podido obtener una respuesta interpretable: sin red, timeout, cuota agotada, 401,
     * 403, 429, 503, JSON corrupto. Se reintenta enseguida.
     */
    ERROR;

    /**
     * ¿Es una respuesta firme sobre la antena, que merece guardarse como conclusión?
     *
     * Solo [VERIFIED] y [NOT_FOUND]. [REJECTED] y [ERROR] son estados de la conversación con la
     * API, no conclusiones, y por eso no se consolidan en el historial como veredicto.
     */
    val isConclusive: Boolean
        get() = this == VERIFIED || this == NOT_FOUND
}
