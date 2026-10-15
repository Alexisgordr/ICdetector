package com.alexisgordr.icdetector.core

/**
 * 3.0 (V2) — Encender la sonda de latencia con el proxy Tor de OpenCellID seleccionado. Son
 * incompatibles (Tor falsearía la medida), y antes encender la latencia apagaba el proxy en
 * silencio: las consultas a OpenCellID pasaban a salir por la conexión directa sin que nadie lo
 * eligiera. Ahora se pide una elección explícita; cancelar deja todo como estaba. Sin proxy no hay
 * nada que explicar y no se pregunta. Solo cambia el estado del formulario: nada se aplica hasta
 * «Guardar ajustes», como el resto de la pantalla.
 */
object LatencyProxyChoice {
    data class Form(val latencyEnabled: Boolean, val proxyEnabled: Boolean)

    enum class Action { APPLY, ASK, IGNORE }

    /** Qué hacer cuando el usuario pide [requested] en el interruptor de latencia. */
    fun onLatencyToggle(form: Form, requested: Boolean, latencyBlocked: Boolean): Action = when {
        !requested -> Action.APPLY                       // apagar siempre se permite
        latencyBlocked -> Action.IGNORE                  // Wi-Fi o VPN
        form.proxyEnabled -> Action.ASK
        else -> Action.APPLY
    }

    /** El formulario tras aplicar [requested] (directamente o tras confirmar). */
    fun apply(form: Form, requested: Boolean): Form =
        if (requested) Form(latencyEnabled = true, proxyEnabled = false) else form.copy(latencyEnabled = false)

    /** Cancelar el diálogo: la configuración anterior, intacta. */
    fun cancel(form: Form): Form = form
}
