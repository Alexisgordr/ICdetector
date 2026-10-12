package com.alexisgordr.icdetector.backup

/**
 * 3.0 — Pregunta de la primera apertura: «¿Restaurar una copia de seguridad?». Sin Android, para
 * probarla en la JVM.
 *
 * Hasta que se resuelve, no se recoge ni se escribe nada: ni la pantalla arranca la monitorización
 * ni el servicio la empieza aunque lo arranque otra vía. La decisión se guarda como resuelta solo
 * después de restaurar y reabrir correctamente, o de terminar el borrado de «Empezar de cero».
 *
 * Instalaciones que ya existían antes de esta versión: las preferencias de la app nunca viajan en
 * copias ni transferencias (ver data_extraction_rules), así que si hay preferencias propias, la app
 * ya se usó en este móvil y no se pregunta (EXISTING). Una base de datos sin preferencias (copiada
 * por otra vía) sí lleva a la pregunta.
 */
object FirstRunPolicy {
    const val PREF_DECISION = "first_run_decision"
    /** Resultado de la última restauración (ver RestoreStartup). */
    const val PREF_RESTORE_RESULT = "restore_last_result"
    /** Nombre de la copia de los datos anteriores a una restauración (ver RestoreStartup). */
    const val PREF_PRE_BACKUP_NAME = "restore_pre_backup_name"

    /** 3.0 — La base actual viene de una copia restaurada (ver LocalProfileController). */
    const val PREF_HISTORY_RESTORED = "local_profile_history_restored"

    enum class Decision { PENDING, RESTORED, FRESH, EXISTING }

    /** Preferencias que escribe esta misma función o la restauración: no prueban un uso previo. */
    val OWN_KEYS = setOf(PREF_DECISION, PREF_RESTORE_RESULT, PREF_PRE_BACKUP_NAME, PREF_HISTORY_RESTORED)

    fun parse(value: String?): Decision? = Decision.entries.firstOrNull { it.name == value }

    /**
     * Decisión al arrancar. [storedDecision]: la guardada (o null). [prefKeys]: claves presentes en
     * las preferencias de la app. Devuelve la decisión y si hay que guardarla.
     */
    fun resolveAtStart(storedDecision: String?, prefKeys: Set<String>): Pair<Decision, Boolean> {
        parse(storedDecision)?.let { return it to false }
        val usedBefore = (prefKeys - OWN_KEYS).isNotEmpty()
        return (if (usedBefore) Decision.EXISTING else Decision.PENDING) to true
    }

    /** ¿Se puede recoger y escribir? Nunca con la decisión pendiente ni con una restauración a medias. */
    fun allowsCollection(decision: Decision?, restorePending: Boolean): Boolean =
        !restorePending && decision != null && decision != Decision.PENDING

    /**
     * «Empezar de cero» ha terminado de verdad: no queda ninguna base ni fichero de la app, y en las
     * preferencias solo la propia decisión (todavía pendiente).
     */
    fun freshCleanupComplete(
        remainingDatabaseFiles: List<String>,
        remainingFiles: List<String>,
        remainingPrefKeys: Set<String>
    ): Boolean = remainingDatabaseFiles.isEmpty() && remainingFiles.isEmpty() &&
        (remainingPrefKeys - PREF_DECISION).isEmpty()

    /** Tras arrancar: una restauración terminada bien resuelve la pregunta pendiente. */
    fun afterRestore(decision: Decision?, outcome: RestoreEngine.RestoreOutcome): Decision? =
        if (decision == Decision.PENDING && outcome == RestoreEngine.RestoreOutcome.RESTORED) Decision.RESTORED else null
}
