package com.inmc.discord

import com.inmc.discord.config.CustomPlaceholder
import com.inmc.discord.config.Messages
import com.inmc.discord.config.Settings
import org.bukkit.configuration.file.YamlConfiguration
import java.io.File
import java.io.InputStreamReader
import java.util.logging.Handler
import java.util.logging.LogRecord
import java.util.logging.Logger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** 배포 파일. */
class ResourceTest {

    private fun yaml(path: String): YamlConfiguration =
        javaClass.classLoader.getResourceAsStream(path)!!.use { YamlConfiguration.loadConfiguration(InputStreamReader(it, Charsets.UTF_8)) }

    private val code: String by lazy {
        File("src/main/kotlin").walkTopDown().filter { it.extension == "kt" }.joinToString("\n") { it.readText() }
    }

    /** 경고가 나오면 실패 — 배포 파일의 정규식·값이 다 읽혀야 한다. */
    private fun strictLogger(): Logger = Logger.getAnonymousLogger().apply {
        useParentHandlers = false
        addHandler(object : Handler() {
            override fun publish(record: LogRecord) = throw AssertionError("경고: ${record.message}")
            override fun flush() = Unit
            override fun close() = Unit
        })
    }

    @Test
    fun `배포 메시지와 기본값 표의 키가 정확히 같다`() {
        assertEquals(Messages.DEFAULTS.keys, yaml("messages.yml").getKeys(false))
    }

    @Test
    fun `배포 메시지 값이 기본값 표와 같다`() {
        val file = yaml("messages.yml")
        for ((key, value) in Messages.DEFAULTS) assertEquals(value, file.getString(key), key)
    }

    @Test
    fun `코드가 부르는 메시지 키가 전부 있다`() {
        // 없는 키는 오류 없이 빈 줄이 된다 — 무엇이 실패했는지 아무도 모른다.
        // 첫 인자는 이름이나 `sender(ctx)` 같은 부름 하나 — 그 뒤 따옴표가 키다.
        val used = Regex("""\.(?:send|discord)\(\s*(?:[\w.]+(?:\([^()"]*\))?\s*,\s*)?"([a-z-]+)"""").findAll(code).map { it.groupValues[1] }.toSet() +
            Regex("""raw\("([a-z-]+)"\)""").findAll(code).map { it.groupValues[1] }.toSet() +
            Regex("""if \(snapshot\.kind == SnapshotKind\.INVENTORY\) "([a-z-]+)" else "([a-z-]+)"""").findAll(code)
                .flatMap { listOf(it.groupValues[1], it.groupValues[2]) }.toSet()
        assertTrue(used.size > 25, "키를 못 읽었다: $used")
        assertEquals(emptySet(), used - Messages.DEFAULTS.keys)
    }

    @Test
    fun `배포 설정이 경고 없이 읽히고 현재 서버 값과 같다`() {
        val settings = Settings.from(yaml("config.yml"), strictLogger())
        assertEquals("", settings.bot.token)
        assertEquals(256, settings.chat.truncate)
        assertEquals(setOf("say", "lag", "tps"), settings.consoleCommand.whitelist)
        assertEquals(10, settings.topic.intervalMinutes)
        assertTrue(settings.keywords.item.pattern.containsMatchIn("이거 봐 [I]"))
        assertTrue(settings.keywords.inventory.pattern.containsMatchIn("[inventory]"))
        assertTrue(settings.keywords.ender.pattern.containsMatchIn("[e]"))
        assertEquals(1, settings.chat.discordFilters.size)
    }

    @Test
    fun `배포 자리표시 여섯 개가 다 읽힌다`() {
        val list = CustomPlaceholder.load(yaml("placeholders.yml"), strictLogger())
        assertEquals(listOf("money", "gametime", "match", "time", "pos", "ping"), list.map { it.id })
        val match = list.first { it.id == "match" }
        assertTrue(match.viewer)
        assertEquals("RUN_COMMAND", match.clickAction)
        assertEquals("abc", match.pattern.find("[match: abc]")!!.groupValues[1])
    }

    @Test
    fun `코드가 쓰는 권한은 전부 선언돼 있다`() {
        // 선언하지 않은 권한은 Bukkit 이 op 기본으로 다룬다 — 일반 플레이어가 [item] 을 못 쓴다.
        val yml = File("src/main/resources/paper-plugin.yml").readText()
        val nodes = listOf(
            Discord.ADMIN, Discord.LINK, Discord.VIEW, Discord.KEYWORD_ITEM, Discord.KEYWORD_INVENTORY, Discord.KEYWORD_ENDER,
            Discord.MENTION_PLAYER, Discord.MENTION_HERE, Discord.MENTION_EVERYONE,
        )
        for (node in nodes) assertTrue(Regex("""(?m)^  ${Regex.escape(node)}:""").containsMatchIn(yml), node)
        for (open in listOf(Discord.LINK, Discord.VIEW, Discord.KEYWORD_ITEM, Discord.MENTION_PLAYER)) {
            assertTrue(Regex("""(?ms)^  ${Regex.escape(open)}:\n[^\n]*\n    default: true""").containsMatchIn(yml), "$open 은 누구나")
        }
    }

    @Test
    fun `설정을 문자열로 찍어도 토큰이 나오지 않는다`() {
        val config = yaml("config.yml").apply { set("bot.token", "MTIz.SECRET.token") }
        val settings = Settings.from(config, Logger.getAnonymousLogger())
        assertEquals("MTIz.SECRET.token", settings.bot.token)
        assertTrue("SECRET" !in settings.toString())
    }
}
