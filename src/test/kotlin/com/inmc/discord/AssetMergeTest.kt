package com.inmc.discord

import com.inmc.discord.render.assets.AssetPack
import com.inmc.discord.render.font.Fonts
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

/** 마크가 팩들을 합치는 것(글꼴 정의)을 우리도 합치는지 — 서버 팩이 자기 글리프만 든 default.json 을 넣어도 바닐라 글자가 남아야 한다. */
class AssetMergeTest {

    private fun font(vararg advances: Pair<Char, Int>): ByteArray =
        """{"providers":[{"type":"space","advances":{${advances.joinToString(",") { (c, a) -> "\"$c\":$a" }}}}]}""".toByteArray()

    @Test
    fun `글꼴은 층마다 합치고 위 팩이 먼저`() {
        val path = "assets/minecraft/font/default.json"
        val vanilla = AssetPack.MapLayer("vanilla", mapOf(path to font('a' to 6, ' ' to 4)))
        val pack = AssetPack.MapLayer("pack", mapOf(path to font('b' to 3, 'a' to 9)))
        val fonts = Fonts(AssetPack(listOf(vanilla, pack)))
        val set = fonts.default()
        assertEquals(9f, assertNotNull(set.glyph('a'.code)).advance) // 위 팩이 덮음
        assertEquals(3f, assertNotNull(set.glyph('b'.code)).advance) // 팩에만 있는 것
        assertEquals(4f, assertNotNull(set.glyph(' '.code)).advance) // 바닐라에만 있는 것이 남음
    }

    @Test
    fun `stack 은 아래 층부터 모든 층의 같은 파일`() {
        val a = AssetPack.MapLayer("a", mapOf("x" to byteArrayOf(1)))
        val b = AssetPack.MapLayer("b", mapOf("y" to byteArrayOf(2)))
        val c = AssetPack.MapLayer("c", mapOf("x" to byteArrayOf(3)))
        assertEquals(listOf(1, 3), AssetPack(listOf(a, b, c)).stack("x").map { it[0].toInt() })
    }
}
