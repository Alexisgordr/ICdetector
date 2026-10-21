package com.alexisgordr.icdetector.storage

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * 3.1.0 (#47) — Único punto de apertura de la base de datos ([CellDbHelper.openThroughGate]).
 *
 * Con un APK más antiguo sobre datos de uno más nuevo, la primera apertura lanza
 * [DatabaseDowngradeException] (CellDbHelper.onDowngrade). Nadie la capturaba: la app se caía en
 * bucle al abrirse (perfil local, Stable-Site, el servicio…). Aquí se convierte en un estado
 * observable, [State.NewerSchema], que la actividad enseña como diálogo bloqueante; con él no se
 * arranca el servicio ni se abre ninguna pantalla que lea la base. Cualquier otro fallo de apertura
 * sigue su camino de siempre (CollectionHealth): este punto no lo oculta ni lo convierte.
 *
 * Puro, sin Android: [open] recibe la apertura como función.
 */
object DatabaseGate {

    sealed interface State {
        /** Aún no se ha intentado abrir. */
        data object Unchecked : State
        data object Open : State
        /** La base es de un esquema más nuevo que el que admite esta versión. No se ha tocado. */
        data class NewerSchema(val storedVersion: Int, val supportedVersion: Int) : State
    }

    private val _state = MutableStateFlow<State>(State.Unchecked)
    val state: StateFlow<State> = _state.asStateFlow()

    val blocked: Boolean get() = _state.value is State.NewerSchema

    /**
     * Abre la base con [open]. False si es de un esquema más nuevo (y entonces el estado lo dice);
     * ya bloqueada, no se vuelve a intentar. Nunca relanza [DatabaseDowngradeException].
     */
    fun open(open: () -> Unit): Boolean {
        if (blocked) return false
        return try {
            open()
            _state.value = State.Open
            true
        } catch (e: DatabaseDowngradeException) {
            _state.value = State.NewerSchema(e.storedVersion, e.supportedVersion)
            false
        }
    }

    /** Solo para pruebas: el estado es de todo el proceso. */
    internal fun resetForTest() {
        _state.value = State.Unchecked
    }
}
