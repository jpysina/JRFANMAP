package jp.jrfanmap.rail

import jp.jrfanmap.data.Fix
import jp.jrfanmap.data.FixPolicy
import java.util.PriorityQueue
import kotlin.math.*

data class AheadStation(val station: RailStation, val meters: Double)
data class StationInterval(val previous: AheadStation, val next: AheadStation) {
    val progress: Float get() = (previous.meters / (previous.meters + next.meters)).toFloat()
}

data class LiveMatch(
    val line: RailLine? = null,
    val reason: String = "等待精确 GPS 与连续位移",
    val offsetMeters: Double? = null,
    val next: List<AheadStation> = emptyList(),
    val interval: StationInterval? = null,
    val observedAtNanos: Long? = null
)

private const val METERS_PER_DEGREE = 111_195.0
private fun east(a: GeoPoint, b: GeoPoint) = (b.lon - a.lon) * METERS_PER_DEGREE * cos(Math.toRadians(a.lat))
private fun north(a: GeoPoint, b: GeoPoint) = (b.lat - a.lat) * METERS_PER_DEGREE
private fun distance(a: GeoPoint, b: GeoPoint) = hypot(east(a, b), north(a, b))
private fun Fix.point() = GeoPoint(longitude, latitude)

private data class Projection(val t: Double, val distance: Double)
private fun project(p: GeoPoint, a: GeoPoint, b: GeoPoint): Projection {
    val x = east(a, b)
    val y = north(a, b)
    val px = east(a, p)
    val py = north(a, p)
    val lengthSquared = x * x + y * y
    val t = if (lengthSquared > 0) ((px * x + py * y) / lengthSquared).coerceIn(0.0, 1.0) else 0.0
    return Projection(t, hypot(px - t * x, py - t * y))
}

internal data class LineCandidate(val id: Long, val offset: Double, val score: Double)

internal fun candidates(previous: Fix, current: Fix, local: List<RailSection>): List<LineCandidate> {
    val prior = previous.point()
    val now = current.point()
    val dx = east(prior, now)
    val dy = north(prior, now)
    val movement = hypot(dx, dy)
    if (movement < max(35.0, previous.accuracy + current.accuracy.toDouble()) || movement > 3_000) return emptyList()
    val tolerance = max(30.0, current.accuracy * 1.6).coerceAtMost(75.0)
    return local.groupBy { it.lineId }.mapNotNull { (id, sections) ->
        val edges = sections.flatMap { it.shape.zipWithNext() }
        val priorOffset = edges.minOfOrNull { (a, b) -> project(prior, a, b).distance } ?: return@mapNotNull null
        if (priorOffset > max(45.0, previous.accuracy * 1.8)) return@mapNotNull null
        edges.mapNotNull edge@ { (a, b) ->
            val projection = project(now, a, b)
            val x = east(a, b)
            val y = north(a, b)
            val length = hypot(x, y)
            if (length < 1 || projection.distance > tolerance) return@edge null
            val agreement = abs(dx * x + dy * y) / (length * movement)
            if (agreement < 0.55) return@edge null
            LineCandidate(id, projection.distance, projection.distance * 1.2 + priorOffset * 0.65 + (1 - agreement) * 45)
        }.minByOrNull { it.score }
    }.sortedBy { it.score }
}

class LineMatcher(private val catalog: RailCatalog) {
    private var pendingId: Long? = null
    private var graph: TrackGraph? = null
    private var lastElapsed = 0L

    fun reset() {
        pendingId = null
        graph = null
        lastElapsed = 0L
    }

    fun match(samples: List<Fix>): LiveMatch {
        val current = samples.lastOrNull() ?: return LiveMatch()
        if (current.elapsedNanos <= lastElapsed) return LiveMatch(reason = "等待新的 GPS 观测")
        lastElapsed = current.elapsedNanos
        if (current.mock || current.provider != "gps" || current.accuracy !in 0f..45f) {
            return uncertain("GPS 精度不足，已暂停线路推断")
        }
        val previous = samples.asReversed().drop(1).firstOrNull {
            val dt = (current.elapsedNanos - it.elapsedNanos) / 1e9
            val movement = FixPolicy.distance(it, current)
            !it.mock && it.provider == "gps" && it.accuracy in 0f..45f &&
                dt in 2.0..35.0 && movement in max(35.0, it.accuracy + current.accuracy.toDouble())..1_200.0 &&
                movement / dt <= 160
        } ?: return uncertain("位移不足，等待连续行进")
        val pad = 0.003
        val bounds = RailBounds(min(previous.longitude, current.longitude) - pad, min(previous.latitude, current.latitude) - pad,
            max(previous.longitude, current.longitude) + pad, max(previous.latitude, current.latitude) + pad)
        val ranked = candidates(previous, current, catalog.sections(bounds))
        val best = ranked.firstOrNull() ?: return uncertain("附近没有与轨迹一致的铁路线")
        if (ranked.getOrNull(1)?.let { it.score - best.score < 12 } == true) return uncertain("附近有平行线路，暂无法区分")
        if (pendingId != best.id) {
            pendingId = best.id
            graph = null
            return LiveMatch(reason = "发现候选线路，等待下一次观测确认")
        }
        val selected = graph ?: catalog.network(best.id)?.let(::TrackGraph)?.also { graph = it }
            ?: return uncertain("线路资料暂不可用")
        val ahead = selected.ahead(previous.point(), current.point())
        return LiveMatch(selected.network.line, ahead.reason, best.offset, ahead.stations, ahead.interval, current.elapsedNanos)
    }

    private fun uncertain(reason: String): LiveMatch {
        pendingId = null
        graph = null
        return LiveMatch(reason = reason)
    }
}

internal class TrackGraph(val network: RailNetwork) {
    private data class Edge(val a: Int, val b: Int, val meters: Double)
    private data class StationOnEdge(val station: RailStation, val edge: Edge, val fraction: Double)
    private data class QueueNode(val id: Int, val meters: Double)
    private data class Search(val distances: DoubleArray, val previous: Array<Edge?>)
    private data class RouteEdge(val from: Int, val to: Int, val meters: Double, val start: Double)
    data class Forward(val reason: String, val stations: List<AheadStation> = emptyList(), val interval: StationInterval? = null)
    private data class Traversal(val stations: List<AheadStation>, val branch: Boolean)

    private val points = mutableListOf<GeoPoint>()
    private val buckets = mutableMapOf<Pair<Int, Int>, MutableList<Int>>()
    private val links = mutableListOf<MutableList<Edge>>()
    private val edges = mutableListOf<Edge>()
    private val stationsByEdge: Map<Edge, List<StationOnEdge>>

    init {
        val seen = mutableSetOf<Pair<Int, Int>>()
        for (section in network.sections) {
            val vertices = section.shape.map(::node)
            for ((a, b) in vertices.zipWithNext()) {
                if (a == b || !seen.add(min(a, b) to max(a, b))) continue
                val edge = Edge(a, b, distance(points[a], points[b]))
                edges += edge
                links[a] += edge
                links[b] += edge
            }
        }
        stationsByEdge = network.stations.mapNotNull { station ->
            val (edge, p) = nearest(station.point) ?: return@mapNotNull null
            if (p.distance <= 150) StationOnEdge(station, edge, p.t) else null
        }.groupBy { it.edge }
    }

    private fun node(point: GeoPoint): Int {
        val x = floor(point.lon * 10_000).toInt()
        val y = floor(point.lat * 10_000).toInt()
        for (i in x - 2..x + 2) for (j in y - 2..y + 2) {
            buckets[i to j]?.firstOrNull { distance(point, points[it]) < 12 }?.let { return it }
        }
        val id = points.size
        points += point
        links.add(mutableListOf())
        buckets.getOrPut(x to y) { mutableListOf() }.add(id)
        return id
    }

    private fun nearest(point: GeoPoint): Pair<Edge, Projection>? =
        edges.asSequence().map { it to project(point, points[it.a], points[it.b]) }.minByOrNull { it.second.distance }

    /** Separate lines of traversal when the station data spans a branch or a break in the geometry. */
    fun browsingStationGroups(): List<List<RailStation>> = buildList {
        val remaining = stationsByEdge.values.flatten().toMutableList()
        while (remaining.isNotEmpty()) {
            val seed = remaining.first()
            val connectedFromSeed = search(seed.edge.a).distances
            val reachable = remaining.flatMap { listOf(it.edge.a, it.edge.b) }.distinct()
                .filter { connectedFromSeed[it].isFinite() }
            val start = reachable.maxBy { connectedFromSeed[it] }
            val fromStart = search(start)
            val end = reachable.maxBy { fromStart.distances[it] }
            val route = buildList {
                var tip = end
                while (tip != start) {
                    val edge = checkNotNull(fromStart.previous[tip])
                    val previous = if (tip == edge.a) edge.b else edge.a
                    add(RouteEdge(previous, tip, edge.meters, fromStart.distances[previous]))
                    tip = previous
                }
            }
            val ordered = remaining.mapNotNull { station ->
                if (!fromStart.distances[station.edge.a].isFinite()) return@mapNotNull null
                val closest = route.map { edge -> edge to project(station.station.point, points[edge.from], points[edge.to]) }
                    .minByOrNull { it.second.distance } ?: return@mapNotNull null
                val (edge, position) = closest
                if (position.distance > 45) null else station to edge.start + position.t * edge.meters
            }.sortedBy { it.second }
            if (ordered.isEmpty()) {
                add(listOf(seed.station))
                remaining.removeAt(0)
            } else {
                val stations = ordered.map { it.first.station }.distinctBy { it.name }
                val first = points[start]
                val last = points[end]
                add(if (first.lat > last.lat || (first.lat == last.lat && first.lon > last.lon)) stations.reversed() else stations)
                remaining.removeAll(ordered.map { it.first }.toSet())
            }
        }
    }.sortedByDescending { it.size }

    private fun search(start: Int): Search {
        val distances = DoubleArray(points.size) { Double.POSITIVE_INFINITY }
        val previous = arrayOfNulls<Edge>(points.size)
        val queue = PriorityQueue(compareBy<QueueNode> { it.meters })
        distances[start] = 0.0
        queue.add(QueueNode(start, 0.0))
        while (queue.isNotEmpty()) {
            val (id, meters) = queue.remove()
            if (meters > distances[id]) continue
            for (edge in links[id]) {
                val target = if (edge.a == id) edge.b else edge.a
                val cost = meters + edge.meters
                if (cost >= distances[target]) continue
                distances[target] = cost
                previous[target] = edge
                queue.add(QueueNode(target, cost))
            }
        }
        return Search(distances, previous)
    }

    fun ahead(previous: GeoPoint, now: GeoPoint): Forward {
        val (edge, projection) = nearest(now) ?: return Forward("站点资料不足")
        if (projection.distance > 80) return Forward("定位偏离线形，暂停站点推断")
        val a = points[edge.a]
        val b = points[edge.b]
        val dx = east(previous, now)
        val dy = north(previous, now)
        val alignment = (dx * east(a, b) + dy * north(a, b)) / (hypot(dx, dy) * edge.meters)
        if (!alignment.isFinite() || abs(alignment) < 0.55) return Forward("方向不明确，暂停站点推断")
        val forward = alignment > 0
        val traversal = trace(edge, projection.t, forward)
        val next = traversal.stations.filter { it.meters > 30 }
        val previousStop = trace(edge, projection.t, !forward, nearestOnly = true).stations.firstOrNull()
        val nextStop = traversal.stations.firstOrNull()
        val interval = if (previousStop != null && nextStop != null && previousStop.station.name != nextStop.station.name) {
            StationInterval(previousStop, nextStop)
        } else null
        val reason = when {
            traversal.branch && next.isEmpty() -> "线路前方分岔，等待设备通过分岔点"
            traversal.branch -> "仅显示分岔前的推断站点"
            next.isEmpty() && interval == null -> "前方无可确认的相连站点"
            else -> "按行驶方向推断前方站点"
        }
        return Forward(reason, next, interval)
    }

    private fun trace(start: Edge, fraction: Double, forward: Boolean, nearestOnly: Boolean = false): Traversal {
        var node = if (forward) start.b else start.a
        var meters = start.meters * (if (forward) 1 - fraction else fraction)
        var incoming = start
        val visited = mutableSetOf(node)
        val result = mutableListOf<AheadStation>()
        fun add(station: RailStation, distance: Double) {
            if (distance > 0 && distance <= 50_000) result.add(AheadStation(station, distance))
        }
        stationsByEdge[start].orEmpty().forEach {
            add(it.station, (it.fraction - fraction) * start.meters * if (forward) 1 else -1)
        }
        var branch = false
        while (meters <= 50_000 && (!nearestOnly || result.isEmpty())) {
            val options = links[node].filter { it != start && it != incoming }
            if (options.size > 1) { branch = true; break }
            val edge = options.singleOrNull() ?: break
            val fromA = edge.a == node
            val target = if (fromA) edge.b else edge.a
            if (!visited.add(target)) break
            stationsByEdge[edge].orEmpty().forEach {
                add(it.station, meters + edge.meters * if (fromA) it.fraction else 1 - it.fraction)
            }
            meters += edge.meters
            incoming = edge
            node = target
        }
        return Traversal(result.sortedBy { it.meters }.distinctBy { it.station.name }, branch)
    }
}
