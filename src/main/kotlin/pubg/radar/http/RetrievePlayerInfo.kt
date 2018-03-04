package pubg.radar.http

import okhttp3.OkHttpClient
import okhttp3.Request
import pubg.radar.GameListener
import pubg.radar.register
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread

data class PlayerInfo(
        val roundMostKill: Int,
        val win: Int,
        val totalPlayed: Int,
        val killDeathRatio: Float,
        val headshotKillRatio: Float)

class PlayerProfile {
    companion object : GameListener {
        init {
            register(this)
        }

        override fun onGameStart() {
            running.set(true)
            scheduled.set(false)
        }

        override fun onGameOver() {
            running.set(false)
            completedPlayerInfo.clear()
            pendingPlayerInfo.clear()
            baseCount.clear()
        }

        /**
         * 已获取玩家信息
         */
        val completedPlayerInfo = ConcurrentHashMap<String, PlayerInfo>()
        /**
         * 未获取玩家信息
         */
        val pendingPlayerInfo = ConcurrentHashMap<String, Int>()
        /**
         * 计数器，用于标记是否需要获取玩家信息
         */
        private val baseCount = ConcurrentHashMap<String, Int>()
        private val client = OkHttpClient()
        private val scheduled = AtomicBoolean(false)
        private val running = AtomicBoolean(true)

//        var playerInfoQueryCount = ConcurrentHashMap<String, Int>()

        fun query(name: String) {
            if (completedPlayerInfo.containsKey(name)) return
            baseCount.putIfAbsent(name, 0)
            pendingPlayerInfo.compute(name) { _, count ->
                (count ?: 0) + 1
            }
            if (scheduled.compareAndSet(false, true))
                thread(isDaemon = true) {
                    while (running.get()) {
                        var next = pendingPlayerInfo.maxBy {
                            //                            println("===>$it,${it.value + baseCount[it.key]!!}")
                            it.value + baseCount[it.key]!!
                        }
                        if (next == null) {
                            scheduled.set(false)
                            next = pendingPlayerInfo.maxBy { it.value + baseCount[it.key]!! }
                            if (next == null || !scheduled.compareAndSet(false, true))
                                break
                        }
                        val (name) = next
//                        println("===>${completedPlayerInfo.size},${pendingPlayerInfo.size}")
                        if (completedPlayerInfo.containsKey(name)) {
                            pendingPlayerInfo.remove(name)
                            continue
                        }
                        val playerInfo = search(name)
                        if (playerInfo == null) {
                            baseCount.compute(name) { _, count ->
                                count!! - 1
                            }
                            Thread.sleep(2000)
                        } else {
                            completedPlayerInfo[name] = playerInfo
                            pendingPlayerInfo.remove(name)
                        }
                    }
                }
        }

        private fun ee(c: Int, a: Int = base): String {
            val first = if (c < a) ""
            else ee(c / a, a)

            val c = c % a
            return first + if (c > 35)
                (c + 29).toChar()
            else c.toString(36)
        }

        private const val base = 62

        private fun parseData(p: String, k: List<String>): String {
            var c = k.size
            val d = HashMap<String, String>()
            while (c-- > 0)
                d[ee(c, base)] = if (k[c].isBlank()) ee(c) else k[c]
            return p.replace(Regex("\\b\\w+\\b")) {
                d[it.value] ?: ""
            }
        }

        private val roundMostKillRegex = Regex("\"records_roundmostkills\":\\s*\"([0-9]+)\"")
        private val winRegex = Regex("\"records_wins\":\\s*\"([0-9]+)\"")
        private val totalPlayedRegex = Regex("\"records_roundsplayed\":\\s*\"([0-9]+)\"")
        private val killDeathRatioRegex = Regex("\"records_killdeathratio\":\\s*\"([0-9.]+)\"")
        private val headshotKillRatioRegex = Regex("\"records_headshotkillratio\":\\s*\"([0-9.]+)\"")

        private fun search(name: String): PlayerInfo? {
            val url = "http://pubg.ali213.net/pubg10/ajax?nickname=$name"
//            val url = "http://pubg.ali213.net/pubg10/overview?nickname=$name"
            val request = Request.Builder().url(url)
                    .addHeader("User-Agent","Mozilla/5.0")
                    .build()
            client.newCall(request).execute().use {
                val result = it.body()?.string()
//                println("$it")
                if (result != null) {
                    try {
                        val idx = result.indices
                        val indices = IntArray(6)
                        var found = 0
                        for (i in idx.endInclusive - 1 downTo idx.start)
                            if (result[i] == '\'') {
                                indices[found++] = i
                                if (found > indices.lastIndex)
                                    break
                            }
                        val keys = result.substring(indices[3] + 1, indices[2]).split("|")
                        val data = result.substring(indices[5] + 1, indices[4])
                        val jsonData = parseData(data, keys)
                        if (jsonData.length < 1000)
                            return null;
                        return PlayerInfo(roundMostKillRegex.find(jsonData)!!.groups[1]!!.value.toInt(),
                                winRegex.find(jsonData)!!.groups[1]!!.value.toInt(),
                                totalPlayedRegex.find(jsonData)!!.groups[1]!!.value.toInt(),
                                killDeathRatioRegex.find(jsonData)!!.groups[1]!!.value.toFloat(),
                                headshotKillRatioRegex.find(jsonData)!!.groups[1]!!.value.toFloat())
                    } catch (e: Exception) {
//            e.printStackTrace()
                    }
                }
            }
            return null
        }
    }
}