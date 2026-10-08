package com.inmc.discord

import com.inmc.discord.config.Settings
import com.inmc.discord.relay.Texts
import net.kyori.adventure.text.Component
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class TextsTest {

    @Test
    fun `색이 섞인 접두사는 글만 남는다`() {
        assertEquals("[회원] 칭호", Texts.plainOf("&7[&a회원&7] <gold>칭호</gold>"))
        assertEquals("VIP", Texts.plainOf("§x§f§f§0§0§0§0VIP"))
        assertEquals("ab", Texts.plainOf("&#ff0000a&rb"))
    }

    @Test
    fun `플레이어 글의 색 코드는 해석하지 않고 걷어 낸다`() {
        assertEquals("<red>안녕 [item]", Texts.stripLegacy("§a<red>안녕 \u001B[0;32m[item]"))
    }

    @Test
    fun `거르기 결과가 비면 버린다`() {
        val filters = listOf(Settings.Filter(Regex("@(everyone|here)"), "$1"), Settings.Filter(Regex(".*Online players\\(.*"), ""))
        assertEquals("everyone 모여", Texts.filter("@everyone 모여", filters))
        assertNull(Texts.filter("Online players(3)", filters))
    }

    @Test
    fun `자르기는 대리 쌍을 끊지 않는다`() {
        assertEquals("가나", Texts.truncate("가나다", 2))
        assertEquals("a😀", Texts.truncate("a😀b", 2))
        assertEquals("그대로", Texts.truncate("그대로", 0))
    }

    @Test
    fun `멘션은 긴 이름부터 낱말 경계로 대소문자 없이`() {
        val targets = listOf("Steve" to "<@1>", "SteveJobs" to "<@2>", "철수" to "<@3>")
        assertEquals("<@2> 와 <@1> 그리고 <@3>님", Texts.mentionsToDiscord("@stevejobs 와 @STEVE 그리고 @철수님", targets))
        assertEquals("<@1>님 안녕", Texts.mentionsToDiscord("@Steve님 안녕", targets))
        assertEquals("<@3> 안녕", Texts.mentionsToDiscord("@철수 안녕", targets))
        assertEquals("@Stever", Texts.mentionsToDiscord("@Stever", targets))
        assertEquals("<#9> 봐", Texts.mentionsToDiscord("#공지 봐", listOf("공지" to "<#9>"), '#'))
    }

    @Test
    fun `토큰은 PAPI 전에 바꾸고 사람 글은 넣지 않는다`() {
        assertEquals("Steve: %message%", Texts.tokens("%username%: %message%", mapOf("username" to "Steve")))
    }

    @Test
    fun `메시지 나누기는 한도를 넘지 않는다`() {
        val lines = List(50) { "줄 $it " + "x".repeat(90) }
        val chunks = Texts.chunks(lines, 500)
        assertTrue(chunks.all { it.length <= 500 })
        assertEquals(lines.joinToString("\n"), chunks.joinToString("\n"))
        assertEquals(listOf("x".repeat(10)), Texts.chunks(listOf("x".repeat(30)), 10))
    }

    @Test
    fun `남이 친 값은 마크업으로 해석되지 않는다`() {
        val c = Texts.mini("&e[{name}]", "name" to "<red>%server_name%")
        assertEquals("[<red>%server_name%]", Texts.plain(c))
        assertEquals("아이템 x3", Texts.plain(Texts.mini("{item} x{amount}", "item" to Component.text("아이템"), "amount" to 3)))
    }

    @Test
    fun `코드 블록은 닫히지 않는다`() {
        assertTrue("```" !in Texts.escapeCodeBlock("a```b"))
    }
}
