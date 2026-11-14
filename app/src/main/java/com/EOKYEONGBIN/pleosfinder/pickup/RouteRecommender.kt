package com.EOKYEONGBIN.pleosfinder.pickup

import android.os.Handler
import android.os.Looper
import android.util.Log
import com.EOKYEONGBIN.pleosfinder.core.Member
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale
import kotlin.concurrent.thread

/** 여러 명 픽업의 더 빠른 순서 추천 */
data class Recommendation(
    /** 운전자가 고른 순서(지금 안내 중) */
    val current: List<Member>,
    /** 추천 순서 */
    val recommended: List<Member>,
    /** 차 → 사람들 → 최종 목적지(있으면)까지 예상 주행 시간 */
    val currentSeconds: Int,
    val recommendedSeconds: Int,
) {
    val savedMinutes: Int get() = maxOf(1, (currentSeconds - recommendedSeconds + 30) / 60)

    fun orderText(order: List<Member>) = order.joinToString(" → ") { it.name }
}

/**
 * 스마트 멀티 픽업 2단계: 실제 도로 이동시간으로 순서를 비교해 더 빠른 순서를 찾는다.
 * - 이동시간: NaviHelper 는 '현재 차 위치 → 한 지점'만 계산해 사람 ↔ 사람 구간을 얻을 수 없어,
 *   시연용으로 OSRM(OpenStreetMap 도로망) 공개 서버의 이동시간 표를 쓴다. 차·사람·목적지 좌표가 외부로 나간다.
 *   상용화 때는 Pleos 제공 API 또는 계약한 경로 API로 바꾼다(기획서 5장).
 * - 출발지(차)와 최종 목적지는 고정하고, 사람 방문 순서를 모두 비교한다(6명 = 720가지).
 * - 고정한 사람(pinned)은 항상 맨 앞이다. 고정 조건을 어기는 순서는 비교하지 않는다.
 * - 운전자가 고른 순서보다 MIN_SAVING_SECONDS 이상 빠를 때만 추천한다. 실패하면 추천 없이(null) 끝난다.
 */
class RouteRecommender(private val endpoint: String = OSRM_TABLE_URL) {

    private val mainHandler = Handler(Looper.getMainLooper())

    /** 결과는 메인 스레드로 돌려준다. */
    fun recommend(
        origin: Pair<Double, Double>,
        stops: List<Pair<Member, Pair<Double, Double>>>,
        pinnedId: String?,
        destination: Pair<Double, Double>?,
        onResult: (Recommendation?) -> Unit,
    ) {
        if (stops.size < 2 || stops.size > MAX_PEOPLE) {
            onResult(null)
            return
        }
        thread(name = "route-recommender") {
            val result = try {
                val points = listOf(origin) + stops.map { it.second } + listOfNotNull(destination)
                val durations = fetchDurations(points)
                best(stops.map { it.first }, pinnedId, durations, hasDestination = destination != null)
            } catch (e: Exception) {
                Log.w(TAG, "순서 추천 실패: ${e.message}")
                null
            }
            mainHandler.post { onResult(result) }
        }
    }

    /** OSRM table: points[i] → points[j] 주행 시간(초) */
    private fun fetchDurations(points: List<Pair<Double, Double>>): Array<DoubleArray> {
        val coordinates = points.joinToString(";") { (lat, lng) -> String.format(Locale.US, "%.6f,%.6f", lng, lat) }
        val connection = URL("$endpoint/$coordinates?annotations=duration").openConnection() as HttpURLConnection
        connection.connectTimeout = TIMEOUT_MS
        connection.readTimeout = TIMEOUT_MS
        connection.setRequestProperty("User-Agent", "PleosFinder-demo")
        try {
            val body = connection.inputStream.bufferedReader().use { it.readText() }
            val json = JSONObject(body)
            require(json.optString("code") == "Ok") { "OSRM ${json.optString("code")}" }
            val rows = json.getJSONArray("durations")
            return Array(rows.length()) { i ->
                val row = rows.getJSONArray(i)
                DoubleArray(row.length()) { j -> if (row.isNull(j)) Double.MAX_VALUE else row.getDouble(j) }
            }
        } finally {
            connection.disconnect()
        }
    }

    private fun best(people: List<Member>, pinnedId: String?, durations: Array<DoubleArray>, hasDestination: Boolean): Recommendation? {
        val destinationIndex = if (hasDestination) people.size + 1 else -1
        // 지점 번호: 0 = 차, 1..n = 사람(고른 순서), n+1 = 최종 목적지
        fun cost(order: List<Int>): Double {
            var total = 0.0
            var from = 0
            for (to in order) {
                total += durations[from][to]
                from = to
            }
            if (destinationIndex >= 0) total += durations[from][destinationIndex]
            return total
        }
        val chosen = people.indices.map { it + 1 }
        val pinnedIndex = pinnedId?.let { id -> people.indexOfFirst { it.id == id }.takeIf { it >= 0 }?.plus(1) }
        val currentCost = cost(chosen)
        var bestOrder = chosen
        var bestCost = currentCost
        for (order in permutations(chosen)) {
            if (pinnedIndex != null && order.first() != pinnedIndex) continue
            val c = cost(order)
            if (c < bestCost) {
                bestCost = c
                bestOrder = order
            }
        }
        val saving = currentCost - bestCost
        Log.d(TAG, "순서 비교: 지금 ${(currentCost / 60).toInt()}분, 가장 빠른 순서 ${(bestCost / 60).toInt()}분")
        if (bestOrder == chosen || saving < MIN_SAVING_SECONDS) return null
        return Recommendation(
            current = people,
            recommended = bestOrder.map { people[it - 1] },
            currentSeconds = currentCost.toInt(),
            recommendedSeconds = bestCost.toInt(),
        )
    }

    private fun permutations(items: List<Int>): Sequence<List<Int>> = sequence {
        if (items.size <= 1) {
            yield(items)
            return@sequence
        }
        for (i in items.indices) {
            val rest = items.toMutableList().also { it.removeAt(i) }
            for (tail in permutations(rest)) yield(listOf(items[i]) + tail)
        }
    }

    companion object {
        const val OSRM_TABLE_URL = "https://router.project-osrm.org/table/v1/driving"
        const val MAX_PEOPLE = 6
        /** 2분 이상 빠를 때만 추천한다(작은 차이로 운전자를 귀찮게 하지 않도록). */
        const val MIN_SAVING_SECONDS = 120.0
        private const val TIMEOUT_MS = 6_000
        private const val TAG = "RouteRecommender"
    }
}
