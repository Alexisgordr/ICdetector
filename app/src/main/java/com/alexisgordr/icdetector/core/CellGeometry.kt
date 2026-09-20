package com.alexisgordr.icdetector.core

import com.alexisgordr.icdetector.models.CellLocationSample
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Geometría de celdas a partir EXCLUSIVAMENTE de las observaciones propias del dispositivo.
 *
 * ── QUÉ ES UN PERFIL Y QUÉ NO ES ────────────────────────────────────────────────────────────
 *
 * [Profile] NO es la posición de la antena. Es el **centro de observación**: la mediana de las
 * coordenadas desde las que este teléfono estuvo enganchado a esa celda, con el radio que abarca
 * el 90 % de esas observaciones. La diferencia no es cosmética:
 *
 *  - Si a una celda solo se la ve desde el sur, el centro queda desplazado cientos de metros
 *    hacia el sur respecto al emplazamiento real.
 *  - Los sectores de una misma torre apuntan a lados distintos, así que cada uno arrastra su
 *    centro hacia su lóbulo. Dos sectores del mismo sitio físico pueden salir separados 1 km.
 *
 * Por eso la UI debe llamarlo "centro de observación" y nunca "posición de la antena", igual que
 * `BayesianScorer` dejó de llamarse "probabilidad" cuando se vio que no estaba calibrada.
 *
 * ── POR QUÉ NO SE USA OPENCELLID / WIGLE AQUÍ ───────────────────────────────────────────────
 *
 * Tres razones, ninguna de ellas "por no depender de internet":
 *
 *  1. **Autoconsistencia.** Comparar un centroide propio con uno de una base externa mezcla dos
 *     métodos de cálculo distintos y hace que las distancias dejen de ser comparables entre sí.
 *     Midiendo todo igual, la comparación es válida aunque el valor absoluto esté sesgado.
 *  2. **Procedencia.** De cada punto propio se conoce cuándo, con qué precisión y con qué filtros
 *     se tomó. De una base colaborativa no se conoce ni quién lo subió.
 *  3. **Privacidad.** Esta geometría se dibuja sin una sola petición de red. Un SDK de mapas
 *     pediría tiles, y cada tile revela dónde vive el usuario.
 *
 * Todo en este objeto es puro y determinista: sin Android, sin base de datos, sin reloj. Es el
 * único sitio donde vive la matemática del centroide, y [TransitionCoherence] la consume desde
 * aquí — si la pantalla calculase su propio centro y H16 el suyo, acabarían divergiendo y la
 * pantalla mentiría sobre lo que de verdad decide el detector.
 */
object CellGeometry {

    /** Muestras mínimas para publicar un perfil. Por debajo, la mediana no significa nada. */
    const val MIN_SAMPLES_FOR_PROFILE = 3

    /**
     * Separación máxima tolerable entre los centros de observación de dos sectores del MISMO
     * emplazamiento. Generosa a propósito: el sesgo por lóbulo de sector ya produce cientos de
     * metros de forma legítima (medido en campo: hasta 1.087 m entre sectores reales). Tres
     * kilómetros no se explican por sesgo de observación en ninguna macrocelda urbana.
     */
    const val MAX_SITE_SPREAD_M = 3_000.0

    /**
     * Hueco sin explicar a partir del cual una ruta de handover se marca para revisión. Mismo
     * criterio que [TransitionCoherence.MIN_UNEXPLAINED_GAP_M] pero aplicado en agregado sobre el
     * historial completo, no sobre un salto concreto.
     */
    const val MIN_ROUTE_GAP_M = 10_000.0

    /** Centro de observación de una celda y dispersión de las muestras que lo sostienen. */
    data class Profile(
        val latitude: Double,
        val longitude: Double,
        val radiusP90: Double,
        val sampleCount: Int
    )

    /**
     * Perfil a partir de las muestras dadas. La mediana resiste mucho mejor un fix aislado
     * erróneo que la media, y el P90 ignora la cola sin descartarla del todo.
     *
     * Lanza si la lista está vacía: pedirle el centro a cero observaciones es un error de quien
     * llama, no un caso a tratar. Use [profileOrNull] cuando el número de muestras sea incierto.
     */
    fun profile(samples: List<CellLocationSample>): Profile {
        require(samples.isNotEmpty()) { "profile() necesita al menos una muestra" }
        val lat = median(samples.map { it.latitude })
        val lon = median(samples.map { it.longitude })
        val radii = samples.map { distanceMeters(lat, lon, it.latitude, it.longitude) }.sorted()
        val p90Index = ceil(radii.size * 0.90).toInt().coerceIn(1, radii.size) - 1
        return Profile(lat, lon, radii[p90Index], samples.size)
    }

    /** Perfil solo si hay muestras suficientes para que signifique algo; null en caso contrario. */
    fun profileOrNull(samples: List<CellLocationSample>): Profile? =
        if (samples.size >= MIN_SAMPLES_FOR_PROFILE) profile(samples) else null

    // ── Descomposición de la identidad ──────────────────────────────────────────────────────

    /**
     * Identificador lógico del eNodeB codificado dentro del Cell ID.
     *
     * En LTE el ECI son 28 bits: los 20 altos identifican el eNodeB y los 8 bajos el sector, de
     * modo que `eNodeB = CID / 256` y `sector = CID % 256`. Es aritmética del estándar 3GPP, no
     * una heurística: no hace falta ninguna base de datos para agrupar dos CID bajo el mismo
     * eNodeB. Esa agrupación no demuestra por sí sola que compartan una torre física: existen
     * despliegues distribuidos y cabezas de radio remotas.
     *
     * **Solo LTE.** En NR el NCI son 36 bits y el reparto entre gNB y celda lo elige el operador
     * (`gNBIdLength` va de 22 a 32 bits), así que dividir por una constante daría agrupaciones
     * falsas. Ante la duda, no se agrupa: devuelve null. GSM y UMTS tampoco codifican
     * emplazamiento de forma portable.
     */
    fun enodebOf(identity: String): Long? {
        val cid = lteCellIdOf(identity) ?: return null
        return cid / 256L
    }

    /** Sector dentro del emplazamiento (los 8 bits bajos del ECI). Solo LTE. */
    fun sectorOf(identity: String): Int? {
        val cid = lteCellIdOf(identity) ?: return null
        return (cid % 256L).toInt()
    }

    /**
     * Cell ID numérico de una identidad `MCC-MNC-TAC-CID-RADIO`, solo si es LTE y el CID es un
     * número plausible. Cualquier otra cosa devuelve null en vez de adivinar.
     */
    private fun lteCellIdOf(identity: String): Long? {
        val parts = identity.split('-')
        if (parts.size < 5) return null
        if (parts.last() != "LTE") return null
        val cid = parts[parts.size - 2].toLongOrNull() ?: return null
        // El ECI de LTE son 28 bits. Un valor fuera de rango no es un ECI, sea lo que sea.
        return if (cid in 0..268_435_455L) cid else null
    }

    // ── Coherencia de emplazamiento ─────────────────────────────────────────────────────────

    /**
     * Resultado de medir si los sectores atribuidos a un mismo eNodeB se observan próximos. Una
     * separación grande es señal de revisión, no prueba automática de una celda falsa.
     */
    data class SiteCheck(
        val enodeb: Long,
        val identities: List<String>,
        val minSpreadM: Double,
        val maxSpreadM: Double,
        val coherent: Boolean
    )

    /**
     * Agrupa los perfiles por emplazamiento y mide la separación entre los centros de cada par de
     * sectores.
     *
     * **Esto es una comprobación diagnóstica que ninguna heurística puntúa todavía.** Un atacante
     * que clona un Cell ID hereda el eNodeB codificado en él, por lo que una separación grande es
     * útil para investigar. También puede tener causas legítimas (red distribuida, remote radio
     * heads o reutilización operativa), de modo que nunca debe presentarse como prueba aislada.
     *
     * Solo se informa de emplazamientos con dos o más sectores perfilados: con uno solo no hay
     * nada que comparar.
     */
    fun siteCoherence(profiles: Map<String, Profile>): List<SiteCheck> =
        profiles.entries
            .mapNotNull { (identity, p) -> enodebOf(identity)?.let { it to (identity to p) } }
            .groupBy({ it.first }, { it.second })
            .filterValues { it.size >= 2 }
            .map { (enodeb, members) ->
                val spreads = mutableListOf<Double>()
                for (i in members.indices) {
                    for (j in i + 1 until members.size) {
                        spreads += distanceMeters(
                            members[i].second.latitude, members[i].second.longitude,
                            members[j].second.latitude, members[j].second.longitude
                        )
                    }
                }
                SiteCheck(
                    enodeb = enodeb,
                    identities = members.map { it.first }.sorted(),
                    minSpreadM = spreads.min(),
                    maxSpreadM = spreads.max(),
                    coherent = spreads.max() <= MAX_SITE_SPREAD_M
                )
            }
            .sortedByDescending { it.maxSpreadM }

    // ── Coherencia de ruta ──────────────────────────────────────────────────────────────────

    /** Una arista del grafo de handovers, medida contra la geografía aprendida de sus extremos. */
    data class RouteCheck(
        val fromIdentity: String,
        val toIdentity: String,
        val observations: Int,
        val trustedObservations: Int,
        val centerDistanceM: Double,
        val unexplainedGapM: Double,
        val coherent: Boolean
    )

    /**
     * Para cada ruta con perfil en ambos extremos, cuánta distancia queda sin explicar tras
     * descontar el radio P90 aprendido de las dos celdas.
     *
     * Dos celdas que se pasan el móvil directamente tienen que solaparse en cobertura, así que su
     * hueco sin explicar debería ser cero o casi. Un hueco de kilómetros significa que la identidad
     * de uno de los dos extremos se ha observado en dos sitios incompatibles.
     *
     * A diferencia de H16, que juzga un salto concreto con el GPS de ese instante, esto mira el
     * historial completo de la ruta. Es diagnóstico: **no puntúa, no alarma y no toca el score**.
     */
    fun routeCoherence(
        routes: List<Triple<String, String, Pair<Int, Int>>>,
        profiles: Map<String, Profile>
    ): List<RouteCheck> = routes.mapNotNull { (from, to, counts) ->
        val a = profiles[from] ?: return@mapNotNull null
        val b = profiles[to] ?: return@mapNotNull null
        val centers = distanceMeters(a.latitude, a.longitude, b.latitude, b.longitude)
        val gap = (centers - a.radiusP90 - b.radiusP90).coerceAtLeast(0.0)
        RouteCheck(
            fromIdentity = from,
            toIdentity = to,
            observations = counts.first,
            trustedObservations = counts.second,
            centerDistanceM = centers,
            unexplainedGapM = gap,
            coherent = gap < MIN_ROUTE_GAP_M
        )
    }.sortedByDescending { it.unexplainedGapM }

    // ── Proyección para dibujo ──────────────────────────────────────────────────────────────

    /**
     * Rectángulo que contiene todos los perfiles, para proyectar el grafo sobre un lienzo sin
     * mapas ni tiles.
     *
     * La proyección es equirectangular con corrección de coseno en la longitud: a las latitudes
     * europeas un grado de longitud mide ~0,74 de uno de latitud, y sin corregirlo el grafo sale
     * estirado en horizontal. Para unos pocos kilómetros de extensión el error de esta proyección
     * es de centímetros — no hace falta nada más elaborado.
     */
    data class Bounds(
        val minLat: Double, val maxLat: Double,
        val minLon: Double, val maxLon: Double,
        val lonScale: Double
    ) {
        val spanLat: Double get() = (maxLat - minLat).coerceAtLeast(MIN_SPAN_DEG)
        val spanLon: Double get() = ((maxLon - minLon) * lonScale).coerceAtLeast(MIN_SPAN_DEG)

        /** Posición relativa (0..1) de un punto dentro del rectángulo; y crece hacia abajo. */
        fun relativeX(lon: Double): Float = (((lon - minLon) * lonScale) / spanLon).toFloat()
        fun relativeY(lat: Double): Float = ((maxLat - lat) / spanLat).toFloat()

        companion object {
            /** Evita dividir por cero cuando todas las celdas caen prácticamente en un punto. */
            private const val MIN_SPAN_DEG = 1e-6
        }
    }

    fun boundsOf(profiles: Collection<Profile>): Bounds? {
        if (profiles.isEmpty()) return null
        val minLat = profiles.minOf { it.latitude }
        val maxLat = profiles.maxOf { it.latitude }
        val minLon = profiles.minOf { it.longitude }
        val maxLon = profiles.maxOf { it.longitude }
        val midLat = (minLat + maxLat) / 2.0
        return Bounds(minLat, maxLat, minLon, maxLon, abs(cos(Math.toRadians(midLat))))
    }

    // ── Utilidades ──────────────────────────────────────────────────────────────────────────

    private fun median(values: List<Double>): Double {
        val sorted = values.sorted()
        val middle = sorted.size / 2
        return if (sorted.size % 2 == 0) (sorted[middle - 1] + sorted[middle]) / 2.0 else sorted[middle]
    }

    /** Haversine. Duplicar esto en otro sitio es cómo empiezan las divergencias silenciosas. */
    fun distanceMeters(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val earthRadius = 6_371_000.0
        val dLat = Math.toRadians(lat2 - lat1)
        val dLon = Math.toRadians(lon2 - lon1)
        val a = sin(dLat / 2).pow(2) + cos(Math.toRadians(lat1)) *
            cos(Math.toRadians(lat2)) * sin(dLon / 2).pow(2)
        return earthRadius * 2 * atan2(sqrt(a), sqrt(1 - a))
    }
}
