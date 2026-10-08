package com.inmc.discord

import com.inmc.discord.chat.MentionScan
import com.inmc.discord.config.Settings
import com.inmc.discord.console.ConsoleCommands
import com.inmc.discord.console.ConsoleRelay
import com.inmc.discord.link.LinkCodes
import com.inmc.discord.link.Nicknames
import com.inmc.discord.render.Lang
import com.inmc.discord.status.WatchState
import net.kyori.adventure.text.Component
import java.time.ZoneId
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class PureLogicTest {

    private val steve = UUID(0, 1)
    private val chulsoo = UUID(0, 2)

    @Test
    fun `멘션 찾기 - 실명·닉네임·전원`() {
        val names = mapOf("Steve" to steve, "철수" to chulsoo, "Steve2" to UUID(0, 3))
        val found = MentionScan.scan("@steve 하이 @철수야 @here @Steve2 @nobody", "@", names, setOf("here"))
        assertEquals(listOf("@steve", "@철수", "@here", "@Steve2"), found.map { it.text })
        assertEquals(setOf(steve), found[0].targets)
        assertTrue(found[2].everyone)
        // 권한이 없으면 @everyone 은 그냥 글
        assertEquals(emptyList(), MentionScan.scan("@everyone", "@", names, emptySet()))
        // 같은 글이 두 번 나와도 한 번
        assertEquals(1, MentionScan.scan("@Steve @Steve", "@", names, emptySet()).size)
    }

    @Test
    fun `연결 코드 - 네 자리·한 번만·시간 지나면 무효·다시 받으면 옛 코드 없음`() {
        var now = 0L
        val codes = LinkCodes { now }
        val code = codes.issue(steve, 10)
        assertTrue(LinkCodes.FORMAT.matches(code))
        assertEquals(LinkCodes.Claim.Invalid, codes.claim("12a4"))
        assertEquals(LinkCodes.Claim.Ok(steve), codes.claim(" $code "))
        assertEquals(LinkCodes.Claim.Unknown, codes.claim(code))

        val first = codes.issue(steve, 10)
        val second = codes.issue(steve, 10)
        if (first != second) assertEquals(LinkCodes.Claim.Unknown, codes.claim(first))
        now += 11 * 60_000L
        assertEquals(LinkCodes.Claim.Unknown, codes.claim(second))
    }

    @Test
    fun `감시 - 멈추면 한 번, 풀리면 한 번`() {
        val state = WatchState()
        assertEquals(WatchState.Step.None, state.observe(10_000, 9_000, 30_000))
        assertEquals(WatchState.Step.Stalled, state.observe(50_000, 9_000, 30_000))
        assertEquals(WatchState.Step.None, state.observe(51_000, 9_000, 30_000))
        val recovered = assertIs<WatchState.Step.Recovered>(state.observe(60_000, 59_500, 30_000))
        assertEquals(50, recovered.seconds)
        assertEquals(WatchState.Step.None, state.observe(61_000, 60_500, 30_000))
    }

    @Test
    fun `콘솔 줄 - 레벨·거르기·접두사·코드블록`() {
        val console = Settings.Console(
            levels = setOf("info", "warn"),
            filters = listOf(Settings.Filter(Regex(".*(?i)async chat thread.*"), "")),
            prefix = "[{date} {level}{name}] ",
            timestamp = "HH:mm:ss",
        )
        val lines = listOf(
            ConsoleRelay.Line(0, "INFO", "", "§a서버 켜짐"),
            ConsoleRelay.Line(0, "WARN", "inmc-urb", "경고\n둘째 줄"),
            ConsoleRelay.Line(0, "ERROR", "x", "오류는 안 보냄"),
            ConsoleRelay.Line(0, "INFO", "", "Async Chat Thread - #3 무언가"),
            ConsoleRelay.Line(0, "INFO", "", "코드 ``` 블록"),
        )
        val out = ConsoleRelay.format(lines, console, ZoneId.of("UTC"))
        assertEquals("[00:00:00 INFO] 서버 켜짐", out[0])
        assertEquals("[00:00:00 WARN inmc-urb] 경고", out[1])
        assertEquals("[00:00:00 WARN inmc-urb] 둘째 줄", out[2])
        assertEquals(4, out.size)
        assertTrue("```" !in out[3])
    }

    @Test
    fun `콘솔 명령어 뿌리`() {
        assertEquals("op", ConsoleCommands.root("/minecraft:OP Steve"))
        assertEquals("tps", ConsoleCommands.root("tps"))
    }

    @Test
    fun `번역 - 인자 순서·퍼센트·없는 키`() {
        val lang = Lang(mapOf("a" to "%s 의 %s", "b" to "%2\$s<-%1\$s 100%%", "item.minecraft.stick" to "막대기"))
        assertEquals("철수 의 막대기", lang.plain(Component.translatable("a", Component.text("철수"), Component.translatable("item.minecraft.stick"))))
        assertEquals("y<-x 100%", lang.plain(Component.translatable("b", Component.text("x"), Component.text("y"))))
        assertEquals("no.such.key", lang.plain(Component.translatable("no.such.key")))
        assertEquals("앞 막대기 뒤", lang.plain(Component.text("앞 ").append(Component.translatable("item.minecraft.stick")).append(Component.text(" 뒤"))))
    }

    @Test
    fun `디스코드 별명 - 색 코드 없이 32자까지`() {
        assertEquals("나인", Nicknames.nickname(" &6나인§r "))
        assertEquals(Nicknames.MAX_LENGTH, Nicknames.nickname("가".repeat(40)).length)
        assertEquals("", Nicknames.nickname("&a"))
    }
}
