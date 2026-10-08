package com.inmc.discord

import com.inmc.discord.status.TopicGate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class TopicGateTest {

    private val minute = 60_000L

    @Test
    fun `채널마다 10분에 두 번까지 — 세 번째는 막고, 창이 지나면 다시`() {
        val gate = TopicGate()
        assertTrue(gate.allow(1, 0))
        gate.record(1, 0)
        assertTrue(gate.allow(1, 1 * minute), "켤 때 한 번")
        gate.record(1, 1 * minute)
        assertFalse(gate.allow(1, 2 * minute), "끌 때 — 재시작 두 번째가 여기서 429 를 맞았다")
        assertTrue(gate.allow(2, 2 * minute), "다른 채널은 따로 센다")
        assertFalse(gate.allow(1, 9 * minute + 59_000))
        assertTrue(gate.allow(1, 10 * minute), "첫 번째가 창을 벗어나면 하나 비는다")
    }

    @Test
    fun `저장했다 읽어도 같은 판단`() {
        val gate = TopicGate()
        gate.record(7, 0)
        gate.record(7, minute)
        val again = TopicGate().apply { import(gate.export()) }
        assertFalse(again.allow(7, 2 * minute))
        assertEquals(gate.export(), again.export())
    }

    @Test
    fun `시계가 뒤로 간(미래에 찍힌) 기록은 버린다`() {
        val gate = TopicGate()
        gate.record(1, 100 * minute)
        gate.record(1, 101 * minute)
        assertTrue(gate.allow(1, 5 * minute), "저장된 시각이 지금보다 한 창 이상 뒤면 믿지 않는다")
    }
}
