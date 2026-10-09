package com.inmc.discord

import com.inmc.discord.config.Messages
import com.inmc.discord.config.PanelDesign
import com.inmc.discord.config.Settings
import com.inmc.discord.status.PanelLayout
import com.inmc.discord.status.PanelLayout.Data
import com.inmc.discord.status.PanelLayout.Gathered
import com.inmc.discord.status.PanelLayout.State
import org.bukkit.configuration.file.YamlConfiguration
import java.io.File
import java.io.InputStreamReader
import java.util.logging.Handler
import java.util.logging.LogRecord
import java.util.logging.Logger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** 서버 현황 패널(2026-10-09) — `panel.yml` 틀·색·줄 빼기·이름 자르기·시간 글·링크 단추·설정. */
class PanelTest {

    private fun resource(path: String): YamlConfiguration =
        javaClass.classLoader.getResourceAsStream(path)!!.use { YamlConfiguration.loadConfiguration(InputStreamReader(it, Charsets.UTF_8)) }

    private fun collecting(into: MutableList<String>): Logger = Logger.getAnonymousLogger().apply {
        useParentHandlers = false
        addHandler(object : Handler() {
            override fun publish(record: LogRecord) {
                into += record.message
            }
            override fun flush() = Unit
            override fun close() = Unit
        })
    }

    private val design: PanelDesign by lazy {
        val warnings = ArrayList<String>()
        PanelDesign.load(resource("panel.yml"), collecting(warnings)).also { assertEquals(emptyList(), warnings) }
    }

    private val now = 1_790_000_000_000L
    private val gathered = Gathered(listOf("Nine_Sik", "알로항"), 2, 20, 19.6, "26.2", PanelLayout.Today(12, 8))
    private val esc = '\u001B'

    private fun view(state: State, maintenance: String? = null, d: PanelDesign = design, g: Gathered? = gathered) =
        PanelLayout.view(Data(state, g, startedAt = now - 7_200_000, stoppedAt = now, hangSince = now - 45_000, maintenance = maintenance, updatedAt = now), d, 60)

    private fun PanelLayout.View.field(name: String) = fields.firstOrNull { it.name == name }

    @Test
    fun `배포 모양 파일이 경고 없이 읽힌다 — 칸 여섯, 링크 없음, TPS 세 등급`() {
        assertEquals(6, design.fields.size)
        assertEquals(emptyList(), design.links)
        assertEquals(listOf("쾌적", "보통", "렉"), design.tps.map { it.label })
    }

    @Test
    fun `온라인 — 칸 순서, 서버 정보는 ansi 색 블록, 주소가 비면 주소 줄이 빠진다`() {
        val v = view(State.ONLINE)
        assertEquals(listOf("상태", "서버 켜진 시간", "접속자 2 / 20", "서버 정보", "마지막 갱신"), v.fields.map { it.name })
        assertEquals(design.colors[State.ONLINE], v.color)
        assertTrue(v.buttonsEnabled)
        assertEquals("🟢 온라인", v.field("상태")!!.value)
        assertEquals("<t:${(now - 7_200_000) / 1000}:f> (<t:${(now - 7_200_000) / 1000}:R>)", v.field("서버 켜진 시간")!!.value)
        assertEquals("Nine\\_Sik, 알로항", v.field("접속자 2 / 20")!!.value)
        val info = v.field("서버 정보")!!.value
        assertTrue(info.startsWith("```ansi\n") && info.endsWith("\n```"), info)
        assertFalse("주소" in info)
        assertTrue("${esc}[0;36m26.2" in info, "버전은 하늘색")
        assertTrue("${esc}[0;32m19.6 쾌적" in info, "TPS 19.6 은 초록")
        assertTrue("${esc}[0;32m12명" in info && "${esc}[0;35m8명" in info)
        assertEquals("<t:${now / 1000}:R>", v.field("마지막 갱신")!!.value)
        assertEquals("1분마다 새로 고칩니다", v.footer)
    }

    @Test
    fun `TPS 등급 — 인게임처럼 18 이상 초록, 16 이상 주황, 나머지 빨강`() {
        assertEquals("쾌적", PanelLayout.tpsLevel(design.tps, 18.0).label)
        assertEquals("보통", PanelLayout.tpsLevel(design.tps, 17.9).label)
        assertEquals("렉", PanelLayout.tpsLevel(design.tps, 9.0).label)
        val ok = view(State.ONLINE, g = gathered.copy(tps = 16.4)).field("서버 정보")!!.value
        assertTrue("${esc}[0;33m16.4 보통" in ok, ok)
        val bad = view(State.ONLINE, g = gathered.copy(tps = 11.0)).field("서버 정보")!!.value
        assertTrue("${esc}[0;31m11.0 렉" in bad, bad)
    }

    @Test
    fun `주소를 정하면 서버 정보 첫 줄`() {
        val info = view(State.ONLINE, d = design.copy(address = "inmc.zz.am")).field("서버 정보")!!.value
        assertTrue(info.startsWith("```ansi\n${esc}[0;30m주소  ${esc}[0;33minmc.zz.am\n"), info)
    }

    @Test
    fun `오프라인 — 꺼진 시각, 접속자 칸 없음, 서버 정보는 버전만, 단추는 끈 모양`() {
        val v = view(State.OFFLINE)
        assertEquals(listOf("상태", "꺼진 시각", "서버 정보", "마지막 갱신"), v.fields.map { it.name })
        assertEquals(design.colors[State.OFFLINE], v.color)
        assertFalse(v.buttonsEnabled)
        val info = v.field("서버 정보")!!.value
        assertTrue("26.2" in info && "TPS" !in info && "오늘" !in info, info)
    }

    @Test
    fun `멈춤은 멈춘 시각부터 흐르고, 켜지는 중은 켜진 시간이 아직이고 접속자 칸이 없다`() {
        assertEquals("🟠 응답 없음 — <t:${(now - 45_000) / 1000}:R>부터 멈춤", view(State.HANG).field("상태")!!.value)
        val starting = view(State.STARTING, g = null)
        assertEquals("아직 켜지는 중입니다", starting.field("서버 켜진 시간")!!.value)
        assertNull(starting.field("접속자 2 / 20"))
        assertNull(starting.field("서버 정보"), "값이 하나도 없으면 칸째 빠진다")
        assertEquals(design.colors[State.STARTING], starting.color)
    }

    @Test
    fun `점검 표시는 상태 칸에 사유로 붙고 색이 바뀐다`() {
        val v = view(State.ONLINE, maintenance = "5시 재시작")
        assertEquals("🟢 온라인\n🔧 점검 중 — 5시 재시작", v.field("상태")!!.value)
        assertEquals(design.maintenanceColor, v.color)
    }

    @Test
    fun `틀 — 값 없는 토큰이 든 줄은 빠지고 모르는 토큰은 그대로, ansi 가 아닌 칸의 색 코드는 지운다`() {
        assertNull(PanelLayout.fill("주소 {address}", mapOf("address" to null)))
        assertEquals("버전 26.2 {zz}", PanelLayout.fill("버전 {version} {zz}", mapOf("version" to "26.2")))
        assertEquals("${esc}[0;32m초록${esc}[0m 그대로 &z", PanelLayout.ansi("&a초록&r 그대로 &z"))
        assertEquals("굵게 없음", PanelLayout.stripColors("&l굵게 &c없음"))
        val custom = design.copy(fields = listOf(PanelDesign.FieldDef("&a접속자", value = "&c{players}")))
        assertEquals("Nine\\_Sik, 알로항", view(State.ONLINE, d = custom).field("접속자")!!.value)
        val colored = design.copy(fields = listOf(PanelDesign.FieldDef("접속자", value = "&a{players}", ansi = true)))
        assertEquals("```ansi\n${esc}[0;32mNine_Sik, 알로항\n```", view(State.ONLINE, d = colored).field("접속자")!!.value)
    }

    @Test
    fun `이름은 정한 수까지, 넘치면 외 N명 — 긴 이름 200명도 한도 안`() {
        val more = { n: Int -> " 외 ${n}명" }
        val names = (1..200).map { "Long_Player_Name_$it" }
        assertEquals("Long\\_Player\\_Name\\_1, Long\\_Player\\_Name\\_2, Long\\_Player\\_Name\\_3 외 197명", PanelLayout.names(names, 3, "없음", more))
        val many = PanelLayout.names(names, 200, "없음", more)
        assertTrue(many.length <= PanelLayout.FIELD_VALUE_MAX, "${many.length}")
        assertTrue(Regex(" 외 \\d+명$").containsMatchIn(many))
        assertEquals("아무도 없습니다", view(State.ONLINE, g = gathered.copy(names = emptyList(), online = 0)).field("접속자 0 / 20")!!.value)
        val big = view(State.ONLINE, g = gathered.copy(names = names, online = 200))
        assertTrue(big.fields.all { it.value.length <= PanelLayout.FIELD_VALUE_MAX })
    }

    @Test
    fun `켤 때 남은 패널 가운데 저장된 것을, 없으면 가장 새것을 남긴다`() {
        assertEquals(10L, PanelLayout.keepPanel(10L, listOf(30L, 20L, 10L)))
        assertEquals(30L, PanelLayout.keepPanel(99L, listOf(30L, 20L)))
        assertNull(PanelLayout.keepPanel(10L, emptyList()))
    }

    @Test
    fun `시간 글`() {
        assertEquals("1분", PanelLayout.every(60))
        assertEquals("1분 30초", PanelLayout.every(90))
        assertEquals("15초", PanelLayout.every(15))
        assertEquals("2일 14시간", PanelLayout.playtime(4_497_641))
        assertEquals("23시간 12분", PanelLayout.playtime((23 * 60 + 12) * 60 * 20L))
        assertEquals("12분", PanelLayout.playtime(12 * 60 * 20L))
        assertEquals("1분 미만", PanelLayout.playtime(100))
    }

    @Test
    fun `링크 추가 글 — 이모지는 첫 낱말이 글자 없을 때, 주소는 마지막 낱말, 이름은 띄어쓰기 허용`() {
        assertEquals(PanelDesign.Link("홈페이지", "https://inmc.kr", "🌐"), PanelLayout.parseLink("🌐 홈페이지 https://inmc.kr"))
        assertEquals(PanelDesign.Link("공식 카페", "https://cafe.example.com"), PanelLayout.parseLink("  공식 카페   https://cafe.example.com "))
        assertNull(PanelLayout.parseLink("https://only.example"))
        assertEquals("주소(url)는 https:// 로 시작해야 합니다", PanelDesign.linkProblem(PanelLayout.parseLink("규칙 rules")!!))
    }

    @Test
    fun `모양 파일 — 틀린 링크·이름 없는 칸·모르는 상태는 경고하고 건너뜀, 링크 5개까지`() {
        val config = resource("panel.yml")
        config.set("links", PanelDesign.linksYaml((1..6).map { PanelDesign.Link("링크$it", "https://e$it.example") }) + listOf(mapOf("label" to "틀림", "url" to "ftp://x")))
        config.set("fields", listOf(mapOf("value" to "이름 없음"), mapOf("name" to "칸", "value" to "x", "when" to listOf("online", "어딘가"))))
        val warnings = ArrayList<String>()
        val d = PanelDesign.load(config, collecting(warnings))
        assertEquals(5, d.links.size)
        assertEquals(1, d.fields.size)
        assertEquals(setOf(State.ONLINE), d.fields.single().whenStates)
        assertEquals(4, warnings.size, warnings.toString())
    }

    @Test
    fun `링크 명령이 panel yml 을 다시 써도 주석과 다른 값이 남는다`() {
        val config = resource("panel.yml")
        val before = PanelDesign.load(config, Logger.getAnonymousLogger())
        val links = listOf(PanelDesign.Link("홈페이지", "https://inmc.kr", "🌐"), PanelDesign.Link("규칙", "https://inmc.kr/rules"))
        config.set("links", PanelDesign.linksYaml(links))
        val text = config.saveToString()
        assertTrue("# ── 토큰" in text, "주석이 사라졌다")
        val after = PanelDesign.load(YamlConfiguration().apply { loadFromString(text) }, Logger.getAnonymousLogger())
        assertEquals(before.copy(links = links), after)
    }

    @Test
    fun `설정 — 채널이 비어 꺼짐, 1분, 나만 보이는 답 10초, 주기 하한 15초`() {
        val config = resource("config.yml")
        val p = Settings.from(config, Logger.getAnonymousLogger()).panel
        assertNull(p.channel)
        assertEquals(60, p.refreshSeconds)
        assertEquals(10, p.replySeconds)
        config.set("panel.refresh-seconds", 3)
        assertEquals(Settings.MIN_PANEL_SECONDS, Settings.from(config, Logger.getAnonymousLogger()).panel.refreshSeconds)
    }

    @Test
    fun `패널 코드가 쓰는 메시지 키가 전부 있다`() {
        val code = listOf("status/Panel.kt", "command/DiscordCommand.kt", "bot/SlashCommands.kt", "bot/Bot.kt")
            .joinToString("\n") { File("src/main/kotlin/com/inmc/discord/$it").readText() }
        val used = Regex(""""((?:discord-panel|discord-info|panel)-[a-z-]+)"""").findAll(code).map { it.groupValues[1] }.toSet()
        assertTrue(used.size > 15, "키를 못 읽었다: $used")
        assertEquals(emptySet(), used - Messages.DEFAULTS.keys)
    }
}
