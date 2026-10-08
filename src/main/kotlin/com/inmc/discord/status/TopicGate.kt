package com.inmc.discord.status

/**
 * 채널 주제 변경 한도를 **우리가 먼저** 지킨다 — 디스코드는 채널마다 10분에 2번이다(`TopicGateTest`).
 *
 * 전에는 그냥 보냈다. 재시작마다 켤 때 한 번·끌 때 한 번이라, 재시작을 두 번 하면 JDA 가 429 를 받아 **7분 넘게**
 * 요청을 들고 있다가 꺼질 때 취소됐고, 그 오류가 "봇에게 채널 관리 권한이 있는지 확인하세요" 로 찍혔다(2026-10-08).
 * 채널마다 최근에 바꾼 시각을 들고 있다가 창 안에 둘이 있으면 보내지 않는다. 재시작을 넘겨야 하므로 `Topics` 가 파일에 둔다.
 */
class TopicGate(private val windowMillis: Long = WINDOW_MILLIS, private val limit: Int = LIMIT) {

    private val recent = HashMap<Long, MutableList<Long>>()

    @Synchronized
    fun allow(channel: Long, now: Long): Boolean {
        val times = recent[channel] ?: return true
        prune(times, now)
        return times.size < limit
    }

    @Synchronized
    fun record(channel: Long, at: Long) {
        val times = recent.getOrPut(channel) { ArrayList() }
        times += at
        prune(times, at)
    }

    /** 저장용 — 채널 → 바꾼 시각들. */
    @Synchronized
    fun export(): Map<Long, List<Long>> = recent.mapValues { it.value.toList() }

    @Synchronized
    fun import(saved: Map<Long, List<Long>>) {
        recent.clear()
        for ((channel, times) in saved) recent[channel] = times.toMutableList()
    }

    private fun prune(times: MutableList<Long>, now: Long) {
        times.removeAll { now - it >= windowMillis || it > now + windowMillis }
    }

    companion object {
        const val WINDOW_MINUTES = 10
        const val WINDOW_MILLIS = WINDOW_MINUTES * 60_000L
        const val LIMIT = 2
    }
}
