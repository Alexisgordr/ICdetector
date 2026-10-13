package com.alexisgordr.icdetector.core

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * 3.0 — Modo avión durante un análisis pendiente: la invalidación y el vaciado van en el hilo
 * principal, igual que la publicación del ciclo, y los datos antiguos no se vuelven a publicar.
 * Se usa la CollectionGeneration real y un ejecutor de un solo hilo como "hilo principal".
 */
class AirplanePublicationTest {

    private fun scenario(airplaneOnMain: Boolean): List<String> {
        val mainExecutor = Executors.newSingleThreadExecutor()
        val main = mainExecutor.asCoroutineDispatcher()
        val generation = CollectionGeneration()
        val cells = MutableStateFlow(listOf("A@t0"))
        val ticket = generation.deliver("A")
        val passedCheck = CountDownLatch(1)
        try {
            runBlocking {
                // El ciclo publica en "main": comprueba mayPublish y tarda un poco en terminar.
                val cycle = async(Dispatchers.Default) {
                    withContext(main) {
                        if (!generation.mayPublish(ticket)) return@withContext
                        passedCheck.countDown()
                        Thread.sleep(150)
                        cells.value = listOf("A@t1")
                    }
                }
                // Modo avión detectado en el bucle (Dispatchers.Default) justo después del check.
                val airplane = async(Dispatchers.Default) {
                    assertTrue(passedCheck.await(5, TimeUnit.SECONDS))
                    val loseAndClear = { generation.lose(); cells.value = emptyList() }
                    if (airplaneOnMain) withContext(main) { loseAndClear() } else loseAndClear()
                }
                cycle.await(); airplane.await()
            }
        } finally {
            main.close()
        }
        return cells.value
    }

    @Test fun `airplane mode on the main thread never leaves old data published`() {
        assertEquals(emptyList<String>(), scenario(airplaneOnMain = true))
    }

    @Test fun `the old off-main ordering could republish stale data`() {
        // Lo que podía pasar antes: el vaciado se cuela entre el check y la publicación.
        assertEquals(listOf("A@t1"), scenario(airplaneOnMain = false))
    }

    @Test fun `a cycle that has not checked yet is invalidated and does not publish`() {
        val generation = CollectionGeneration()
        val ticket = generation.deliver("A")
        generation.lose()
        assertEquals(false, generation.mayPublish(ticket))
    }

    @Test fun `the service runs the airplane loss and clearing on the main thread`() {
        val main = listOf(File("src/main/java"), File("app/src/main/java")).first { it.exists() }
        val service = main.resolve("com/alexisgordr/icdetector/service/MiniICService.kt").readText()
        val branch = service.substringAfter("if (isAirplaneModeOn) {").substringBefore("} else {")
        val onMain = branch.substringAfter("withContext(Dispatchers.Main) {").substringBefore("}")
        listOf("onSignalLost()", "_cellFlow.value = emptyList()", "updateNotificationText(getString(R.string.no_signal_airplane))")
            .forEach { assertTrue(it, onMain.contains(it)) }
        assertTrue("el ajuste se sigue leyendo fuera", service.substringBefore("if (isAirplaneModeOn) {")
            .substringAfterLast("scope.launch(Dispatchers.Default) {").contains("Settings.Global.AIRPLANE_MODE_ON"))
    }
}
