package com.alexisgordr.icdetector.core

import com.alexisgordr.icdetector.core.RouteReview.Current
import com.alexisgordr.icdetector.core.RouteReview.Failures
import com.alexisgordr.icdetector.models.CellTransitionSummary
import com.alexisgordr.icdetector.models.HeuristicStatus
import com.alexisgordr.icdetector.models.RouteFailureHistory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 3.0 (UI2) — Presentación de una ruta: última observación frente a último evaluado, REVISAR y lo
 * que se sabe de los fallos. Las secuencias con SQLite real están en SchemaV25MigrationTest.
 */
class RouteReviewTest {
    private fun route(last: HeuristicStatus, obs: Int = 3, evaluated: HeuristicStatus? = null, evaluatedMs: Long? = null,
                      failed: Int = 0, history: RouteFailureHistory = RouteFailureHistory.COMPLETE) =
        CellTransitionSummary("A", "B", obs, 0, last, 5_000, lastEvaluatedStatus = evaluated, lastEvaluatedMs = evaluatedMs,
            failedCount = failed, failureHistory = history)

    @Test fun `N-A is not evaluated, never learning, and alone never REVIEW`() {
        val r = route(HeuristicStatus.NOT_EVALUATED)
        assertEquals(Current.NOT_EVALUATED, RouteReview.current(r))
        assertFalse(RouteReview.needsReview(r))
        assertFalse(RouteReview.needsReview(route(HeuristicStatus.NOT_EVALUATED, history = RouteFailureHistory.LEGACY_UNKNOWN)))
    }

    @Test fun `a route only seen in trips has no reading`() {
        assertEquals(Current.NO_HANDOVER_OBSERVED, RouteReview.current(route(HeuristicStatus.NOT_EVALUATED, obs = 0)))
    }

    @Test fun `the last evaluated result is shown apart only when the current reading is N-A`() {
        assertEquals(HeuristicStatus.FAILED to 1_000L, RouteReview.previousEvaluated(route(HeuristicStatus.NOT_EVALUATED, evaluated = HeuristicStatus.FAILED, evaluatedMs = 1_000)))
        assertNull(RouteReview.previousEvaluated(route(HeuristicStatus.PASSED, evaluated = HeuristicStatus.PASSED, evaluatedMs = 5_000)))
        assertNull("sin fecha no se muestra", RouteReview.previousEvaluated(route(HeuristicStatus.NOT_EVALUATED, evaluated = HeuristicStatus.PASSED)))
    }

    @Test fun `REVIEW means a FAILED recorded, counted or kept from before`() {
        assertTrue(RouteReview.needsReview(route(HeuristicStatus.PASSED, failed = 1)))
        assertTrue(RouteReview.needsReview(route(HeuristicStatus.NOT_EVALUATED, history = RouteFailureHistory.LEGACY_FAILED)))
        assertFalse(RouteReview.needsReview(route(HeuristicStatus.PASSED)))
    }

    @Test fun `the past is never invented`() {
        assertEquals(Failures.Complete(0), RouteReview.failures(route(HeuristicStatus.PASSED)))
        assertEquals(Failures.AtLeast(2), RouteReview.failures(route(HeuristicStatus.PASSED, failed = 2, history = RouteFailureHistory.LEGACY_FAILED)))
        assertEquals(Failures.UnknownBefore(0), RouteReview.failures(route(HeuristicStatus.PASSED, history = RouteFailureHistory.LEGACY_UNKNOWN)))
        assertEquals(RouteFailureHistory.COMPLETE, RouteFailureHistory.fromStored(null))
        assertEquals(RouteFailureHistory.LEGACY_FAILED, RouteFailureHistory.fromStored("LEGACY_FAILED"))
        assertEquals("valor desconocido: no se afirma nada", RouteFailureHistory.LEGACY_UNKNOWN, RouteFailureHistory.fromStored("???"))
        assertNull(RouteReview.evaluatedFrom("NOT_EVALUATED")); assertNull(RouteReview.evaluatedFrom(null))
        assertEquals(HeuristicStatus.FAILED, RouteReview.evaluatedFrom("FAILED"))
    }

    private fun src(p: String) = listOf("app/src/main/$p", "src/main/$p").map(::File).first { it.exists() }.readText()

    @Test fun `screens use the policy and the labels exist in both languages`() {
        val ui = src("java/com/alexisgordr/icdetector/ui/HistoryScreen.kt")
        assertTrue(ui.contains("\"REVIEW\" -> transitions.filter { com.alexisgordr.icdetector.core.RouteReview.needsReview(it) }"))
        assertFalse(ui.contains("it.lastStatus != HeuristicStatus.PASSED"))
        assertFalse(ui.contains("routeStatusLabel(route.lastStatus)"))
        assertFalse(ui.contains("topology_status_learning"))
        assertTrue(src("java/com/alexisgordr/icdetector/ui/StableSiteScreen.kt").contains("routeStatusDetails(route).forEach { InfoLine(it, Dim) }"))
        val en = src("res/values/strings.xml"); val es = src("res/values-es/strings.xml")
        fun v(x: String, k: String) = Regex("<string name=\"$k\">(.*?)</string>").find(x)?.groupValues?.get(1)
        val markers = { s: String -> Regex("%\\d\\$[sd]").findAll(s).map { it.value }.toList() }
        listOf("route_not_evaluated", "route_no_handover", "route_not_evaluated_reason", "route_last_evaluated",
            "route_failures_complete", "route_failures_at_least", "route_failures_unknown", "route_review_note").forEach { k ->
            assertEquals(k, markers(v(en, k)!!), markers(v(es, k)!!))
        }
        assertEquals("SIN EVALUAR", v(es, "route_not_evaluated"))
        assertNull(v(es, "topology_status_learning")); assertNull(v(en, "topology_status_learning"))
        assertFalse("nunca «nunca falló»", (en + es).contains("never failed", true) || es.contains("nunca falló", true))
    }
}
