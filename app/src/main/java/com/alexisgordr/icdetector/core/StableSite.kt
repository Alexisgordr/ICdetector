package com.alexisgordr.icdetector.core

import kotlin.math.*

enum class MotionState { UNKNOWN, STATIC_CONFIRMED, MOVING }
enum class MotionReason { FIRST_FIX, INSUFFICIENT_DURATION, ACCURACY_POOR, NO_RECENT_FIX, STATIC, MOVEMENT }
enum class StableSiteFeatureState { UNAVAILABLE, BOOTSTRAP, LEARNING, SHADOW_READY, ACTIVE }
enum class StableSiteNovelty { KNOWN_AT_SITE, SITE_NOVEL, GLOBALLY_NOVEL }
enum class NeighbourEvidenceCapability { FULL_NEIGHBOUR_IDENTITY, RF_NEIGHBOUR_ONLY, NO_NEIGHBOUR_DATA }

/** Local RF context, deliberately not a globally unique cellular identity. */
data class RfNeighbourFingerprint(
    val value: String,
    val radio: com.alexisgordr.icdetector.models.RadioTech,
    val arfcn: Int,
    val pci: Int
)

data class NeighbourDiagnostic(
    val raw: Int,
    val fullIdentity: Int,
    val rfOnly: Int,
    val withoutUsefulRf: Int
) {
    val capability: NeighbourEvidenceCapability get() = when {
        fullIdentity > 0 -> NeighbourEvidenceCapability.FULL_NEIGHBOUR_IDENTITY
        rfOnly > 0 -> NeighbourEvidenceCapability.RF_NEIGHBOUR_ONLY
        else -> NeighbourEvidenceCapability.NO_NEIGHBOUR_DATA
    }
}

object StableSiteNeighbourEvidence {
    private const val ANDROID_UNAVAILABLE = Int.MAX_VALUE

    fun isValidArfcn(radio: com.alexisgordr.icdetector.models.RadioTech, value: Int): Boolean =
        value != ANDROID_UNAVAILABLE && when (radio) {
            com.alexisgordr.icdetector.models.RadioTech.LTE -> value in 0..262_143
            com.alexisgordr.icdetector.models.RadioTech.NR -> value in 0..3_279_165
            com.alexisgordr.icdetector.models.RadioTech.UMTS -> value in 0..16_383
            else -> false // GSM has ARFCN but this model receives no BSIC/PCI counterpart.
        }

    fun isValidPhysicalId(radio: com.alexisgordr.icdetector.models.RadioTech, value: Int): Boolean =
        value != ANDROID_UNAVAILABLE && when (radio) {
            com.alexisgordr.icdetector.models.RadioTech.LTE -> value in 0..503
            com.alexisgordr.icdetector.models.RadioTech.NR -> value in 0..1007
            com.alexisgordr.icdetector.models.RadioTech.UMTS -> value in 0..511
            else -> false
        }

    fun rfFingerprint(cell: com.alexisgordr.icdetector.models.CellData): RfNeighbourFingerprint? {
        if (cell.cellId != "N/A") return null
        val radio = cell.radioTech.takeIf { it != com.alexisgordr.icdetector.models.RadioTech.UNKNOWN } ?: return null
        val arfcn = cell.arfcn?.takeIf { isValidArfcn(radio, it) } ?: return null
        val pci = cell.pci?.takeIf { isValidPhysicalId(radio, it) } ?: return null
        return RfNeighbourFingerprint("RFCTX:v1:${radio.name}:$arfcn:$pci", radio, arfcn, pci)
    }

    fun diagnostic(neighbours: List<com.alexisgordr.icdetector.models.CellData>): NeighbourDiagnostic {
        val full = neighbours.count { it.cellId != "N/A" }
        val rf = neighbours.count { it.cellId == "N/A" && rfFingerprint(it) != null }
        return NeighbourDiagnostic(neighbours.size, full, rf, neighbours.size - full - rf)
    }
}

data class StableSiteMaturity(
    val state: StableSiteFeatureState,
    val capability: NeighbourEvidenceCapability,
    val reason: String
)

object StableSiteMaturityPolicy {
    fun evaluate(servingDays: Int, staticDays: Int, fullNeighbourDays: Int, rfNeighbourDays: Int, servingObservations: Int): StableSiteMaturity {
        val capability = when {
            fullNeighbourDays >= 3 -> NeighbourEvidenceCapability.FULL_NEIGHBOUR_IDENTITY
            rfNeighbourDays > 0 -> NeighbourEvidenceCapability.RF_NEIGHBOUR_ONLY
            fullNeighbourDays > 0 -> NeighbourEvidenceCapability.FULL_NEIGHBOUR_IDENTITY
            else -> NeighbourEvidenceCapability.NO_NEIGHBOUR_DATA
        }
        val neighbourReady = fullNeighbourDays >= 3 || rfNeighbourDays >= 3
        val state = when {
            servingDays == 0 -> StableSiteFeatureState.BOOTSTRAP
            servingDays < 5 || staticDays < 2 -> StableSiteFeatureState.LEARNING
            servingDays < 7 || staticDays < 3 || !neighbourReady || servingObservations < 30 -> StableSiteFeatureState.SHADOW_READY
            else -> StableSiteFeatureState.ACTIVE
        }
        val reason = when {
            state == StableSiteFeatureState.ACTIVE -> "MATURE_${capability.name}"
            servingDays < 7 -> "NEEDS_SERVING_DAYS"
            staticDays < 3 -> "NEEDS_STATIC_DAYS"
            servingObservations < 30 -> "NEEDS_SERVING_OBSERVATIONS"
            fullNeighbourDays in 1..2 -> "NEEDS_FULL_NEIGHBOUR_DAYS"
            rfNeighbourDays in 1..2 -> "NEEDS_RF_NEIGHBOUR_DAYS"
            !neighbourReady && capability == NeighbourEvidenceCapability.NO_NEIGHBOUR_DATA -> "NO_USABLE_NEIGHBOUR_DATA"
            else -> "MATURE"
        }
        return StableSiteMaturity(state, capability, reason)
    }
}

// Field rollout switch. v2.9.1 records wouldTrigger but does not freeze trust learning.
const val STABLE_SITE_ENFORCEMENT_ENABLED = false

data class MotionFix(val latitude: Double, val longitude: Double, val timeMs: Long, val accuracyM: Float, val speedMps: Float?)
data class MotionEvidence(
    val state: MotionState,
    val durationSeconds: Long = 0,
    val accuracyM: Float? = null,
    val displacementM: Float? = null,
    val reason: MotionReason? = null
)

/** Conservative rolling classifier driven only by distinct GNSS fixes. */
class MotionClassifier(
    private val requiredStaticMs: Long = 120_000L,
    private val expectedFixIntervalMs: Long = 15_000L
) {
    private val fixes = ArrayDeque<MotionFix>()
    private val gracePeriodMs = expectedFixIntervalMs * 4
    private var lastAcceptedFixTime = Long.MIN_VALUE
    private var latest = MotionEvidence(MotionState.UNKNOWN, reason = MotionReason.NO_RECENT_FIX)

    @Synchronized fun observe(fix: MotionFix): MotionEvidence {
        // A Location can arrive through both the continuous and one-shot listeners. It is
        // evidence only once, and an older callback must not rewind or inflate the window.
        if (fix.timeMs <= lastAcceptedFixTime) return latest
        if (fix.timeMs <= 0 || fix.accuracyM > MAX_ACCURACY_M || !fix.accuracyM.isFinite()) {
            if (lastAcceptedFixTime == Long.MIN_VALUE || fix.timeMs - lastAcceptedFixTime > gracePeriodMs) fixes.clear()
            latest = MotionEvidence(MotionState.UNKNOWN, accuracyM = fix.accuracyM, reason = MotionReason.ACCURACY_POOR)
            return latest
        }
        if (lastAcceptedFixTime != Long.MIN_VALUE && fix.timeMs - lastAcceptedFixTime > gracePeriodMs) fixes.clear()
        lastAcceptedFixTime = fix.timeMs
        fixes.addLast(fix)
        while (fixes.isNotEmpty() && fix.timeMs - fixes.first().timeMs > requiredStaticMs + 60_000L) fixes.removeFirst()
        val first = fixes.first()
        val distance = distanceMeters(first.latitude, first.longitude, fix.latitude, fix.longitude)
        val duration = ((fix.timeMs - first.timeMs) / 1_000L).coerceAtLeast(0)
        val reliableSpeed = fix.speedMps?.takeIf { it.isFinite() && it >= 0f }
        val movementThreshold = max(40f, first.accuracyM + fix.accuracyM)
        if ((reliableSpeed != null && reliableSpeed >= 2.5f) || distance > movementThreshold) {
            fixes.clear(); fixes.addLast(fix)
            latest = MotionEvidence(MotionState.MOVING, duration, fix.accuracyM, distance, MotionReason.MOVEMENT)
            return latest
        }
        val envelope = max(25f, fixes.maxOf { it.accuracyM } * 1.5f)
        val maxDistance = fixes.maxOf { distanceMeters(first.latitude, first.longitude, it.latitude, it.longitude) }
        val static = duration * 1_000L >= requiredStaticMs && maxDistance <= envelope && fixes.none { (it.speedMps ?: 0f) > 1.5f }
        latest = MotionEvidence(
            if (static) MotionState.STATIC_CONFIRMED else MotionState.UNKNOWN,
            duration, fix.accuracyM, maxDistance,
            if (static) MotionReason.STATIC else if (fixes.size == 1) MotionReason.FIRST_FIX else MotionReason.INSUFFICIENT_DURATION
        )
        return latest
    }

    @Synchronized fun current(nowMs: Long): MotionEvidence {
        if (lastAcceptedFixTime == Long.MIN_VALUE || nowMs - lastAcceptedFixTime > gracePeriodMs) {
            fixes.clear()
            latest = MotionEvidence(MotionState.UNKNOWN, reason = MotionReason.NO_RECENT_FIX)
        }
        return latest
    }
    companion object {
        private const val MAX_ACCURACY_M = 50f
        fun distanceMeters(aLat: Double, aLon: Double, bLat: Double, bLon: Double): Float {
            val r = 6_371_000.0; val p1 = Math.toRadians(aLat); val p2 = Math.toRadians(bLat)
            val dp = p2 - p1; val dl = Math.toRadians(bLon - aLon)
            val h = sin(dp/2).pow(2) + cos(p1)*cos(p2)*sin(dl/2).pow(2)
            return (2*r*asin(sqrt(h))).toFloat()
        }
    }
}

/** 500 m Web-Mercator bucket, hashed before persistence to avoid exact-coordinate storage. */
object StableSiteKey {
    const val GRID_METERS = 500.0
    fun from(latitude: Double, longitude: Double): String = candidates(latitude, longitude).first()
    /** Four overlapping grids ensure that ordinary GNSS jitter cannot split every site view. */
    fun candidates(latitude: Double, longitude: Double): List<String> {
        val lat = latitude.coerceIn(-85.0, 85.0)
        val x = 6_378_137.0 * Math.toRadians(longitude)
        val y = 6_378_137.0 * ln(tan(Math.PI / 4 + Math.toRadians(lat) / 2))
        return listOf(0.0 to 0.0, GRID_METERS / 2 to 0.0, 0.0 to GRID_METERS / 2, GRID_METERS / 2 to GRID_METERS / 2)
            .mapIndexed { grid, (ox, oy) -> hash("v2:$grid:${floor((x + ox) / GRID_METERS).toLong()}:${floor((y + oy) / GRID_METERS).toLong()}") }
    }
    private fun hash(raw: String): String {
        val digest = java.security.MessageDigest.getInstance("SHA-256").digest(raw.toByteArray())
        return digest.take(10).joinToString("") { "%02x".format(it) }
    }
}

data class StableSiteEvidence(
    val siteKey: String?, val featureState: StableSiteFeatureState,
    val siteServingDays: Int = 0, val siteStaticDays: Int = 0, val siteNeighbourDays: Int = 0,
    val currentServingDays: Int = 0, val currentNeighbourDays: Int = 0,
    val currentSeenGlobally: Boolean = false, val previousServingDays: Int = 0,
    val motion: MotionEvidence = MotionEvidence(MotionState.UNKNOWN),
    val servingObservations: Int = 0,
    val previousIdentity: String? = null,
    val fullNeighbourDays: Int = siteNeighbourDays,
    val rfNeighbourDays: Int = 0,
    val neighbourCapability: NeighbourEvidenceCapability = if (siteNeighbourDays > 0) NeighbourEvidenceCapability.FULL_NEIGHBOUR_IDENTITY else NeighbourEvidenceCapability.NO_NEIGHBOUR_DATA,
    val maturityReason: String = "UNKNOWN"
)

data class StableSiteDecision(
    val featureState: StableSiteFeatureState = StableSiteFeatureState.UNAVAILABLE,
    val novelty: StableSiteNovelty = StableSiteNovelty.KNOWN_AT_SITE,
    val wouldTrigger: Boolean = false, val enforced: Boolean = false,
    val siteKey: String? = null, val reason: String = "NO_SITE_DATA",
    val evidence: StableSiteEvidence? = null
)

object StableSiteEvaluator {
    fun evaluate(e: StableSiteEvidence, enforcementEnabled: Boolean = STABLE_SITE_ENFORCEMENT_ENABLED): StableSiteDecision {
        val novelty = when { e.currentServingDays >= 3 -> StableSiteNovelty.KNOWN_AT_SITE; e.currentSeenGlobally -> StableSiteNovelty.SITE_NOVEL; else -> StableSiteNovelty.GLOBALLY_NOVEL }
        val would = e.featureState in setOf(StableSiteFeatureState.SHADOW_READY, StableSiteFeatureState.ACTIVE) &&
            e.motion.state == MotionState.STATIC_CONFIRMED && e.previousServingDays >= 3 &&
            e.currentServingDays < 3 && e.currentNeighbourDays < 2
        return StableSiteDecision(e.featureState, novelty, would, enforcementEnabled && would && e.featureState == StableSiteFeatureState.ACTIVE,
            e.siteKey, if (would) "NEW_SERVING_WHILE_STATIC_AT_MATURE_SITE" else "INSUFFICIENT_CONTEXT", e)
    }
}

/** Selects spatial context by maturity only. The verdict never influences canonical-site choice. */
object StableSiteCandidateSelector {
    fun select(candidates: List<StableSiteDecision>): StableSiteDecision? = candidates.maxWithOrNull(
        compareBy<StableSiteDecision> { it.featureState.ordinal }
            .thenBy { it.evidence?.siteServingDays ?: 0 }
            .thenBy { it.evidence?.siteStaticDays ?: 0 }
            .thenBy { it.evidence?.siteNeighbourDays ?: 0 }
            .thenBy { it.evidence?.servingObservations ?: 0 }
            .thenByDescending { it.siteKey ?: "" }
    )
}
