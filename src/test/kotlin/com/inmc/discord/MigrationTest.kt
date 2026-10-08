package com.inmc.discord

import com.inmc.discord.config.CustomPlaceholder
import com.inmc.discord.config.Migration
import com.inmc.discord.config.Settings
import org.bukkit.configuration.file.YamlConfiguration
import java.io.File
import java.nio.file.Files
import java.util.UUID
import java.util.logging.Handler
import java.util.logging.LogRecord
import java.util.logging.Logger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class MigrationTest {

    private val token = "MTIzNDU2.SECRET-TOKEN.abc"

    private class Capture : Handler() {
        val lines = ArrayList<String>()
        override fun publish(record: LogRecord) {
            lines += record.message
        }
        override fun flush() = Unit
        override fun close() = Unit
    }

    /** 이 서버의 DiscordSRV·IC 설정과 같은 모양(값은 꾸민 것). */
    private fun server(): Pair<File, File> {
        val plugins = Files.createTempDirectory("plugins").toFile()
        val ours = File(plugins, "inmc-discord").apply { mkdirs() }
        for (name in listOf("config.yml", "messages.yml", "placeholders.yml")) {
            javaClass.classLoader.getResourceAsStream(name)!!.use { input -> File(ours, name).outputStream().use(input::copyTo) }
        }
        File(plugins, "DiscordSRV").apply { mkdirs() }.let { srv ->
            File(srv, "config.yml").writeText(
                """
                BotToken: "$token"
                Channels: {"global": "519134289263198209", "urb": "1147368064095092746"}
                DiscordConsoleChannelId: "500313817264422942"
                DiscordInviteLink: "discord.gg/changethisintheconfig.yml"
                TimestampFormat: EEE, d. MMM yyyy HH:mm:ss z
                Timezone: UTC
                DiscordGameStatus: "inmc.kr"
                DiscordChatChannelAllowedMentions: [user, channel, emote]
                DiscordChatChannelRolesAllowedToUseColorCodesInChat: ["Developer", "Owner", "Admin", "어드민"]
                DiscordChatChannelDiscordFilters: {".*Online players\\(.*": "", "@(everyone|here)": "${'$'}1"}
                DiscordChatChannelConsoleCommandWhitelist: ["say", "lag", "tps", "say"]
                DiscordChatChannelConsoleCommandRolesAllowed: ["어드민", "Owner"]
                DiscordConsoleChannelFilters: {".*(?i)async chat thread.*": ""}
                ChannelTopicUpdaterRateInMinutes: 10
                ServerWatchdogTimeout: 30
                MinecraftDiscordAccountLinkedRoleNameToAddUserTo: "Linked"
                MinecraftDiscordAccountLinkedConsoleCommands: ["", "", ""]
                """.trimIndent(),
            )
            File(srv, "messages.yml").writeText(
                """
                MinecraftChatToDiscordMessageFormat: "%cmi_user_prefix% | %titleforge_nickname%&r: %message%"
                DiscordToMinecraftChatMessageFormat: "[&b디코 &r| %toprolecolor%%toprole%&r] %name%: %message%"
                DiscordAccountLinked: "%name% (UUID : %uuid%)의 연동이 성공하였습니다!"
                DiscordChatChannelListCommandFormatOnlinePlayers: "**온라인 중인 플레이어 (%playercount% 명):**"
                DiscordChatChannelServerStartupMessage: ":white_check_mark: **서버가 시작되었습니다**"
                MinecraftPlayerJoinMessage:
                  Enabled: true
                  Content: ""
                  Embed:
                    Enabled: true
                    Color: "#00ff00"
                    Author:
                      Name: "%displayname% 님이 서버에 접속하셨습니다."
                MinecraftPlayerLeaveMessage:
                  Enabled: false
                  Embed:
                    Enabled: true
                    Author:
                      Name: "%displayname% 님이 나갔습니다."
                MinecraftPlayerDeathMessage:
                  Enabled: true
                  Content: "%deathmessage%"
                  Embed:
                    Enabled: false
                """.trimIndent(),
            )
            File(srv, "accounts.aof").writeText(
                "111111111111111111 ${UUID(0, 1)}\n222222222222222222 ${UUID(0, 2)}\nbroken line\n333333333333333333 ${UUID(0, 1)}\n",
            )
        }
        File(plugins, "InteractiveChat").apply { mkdirs() }.let { ic ->
            File(ic, "config.yml").writeText(
                """
                ItemDisplay:
                  Settings:
                    Timeout: 5
                  Item:
                    Enabled: true
                    Keyword: "(?i)\\[item\\]|\\[i\\]"
                Chat:
                  MentionCooldown: 7
                TabCompletion:
                  ChatTabCompletions:
                    Enabled: true
                CustomPlaceholders:
                  1:
                    Keyword: "(?i)\\[money\\]|\\[m\\]"
                    Name: "[money]"
                  9:
                    ParsePlayer: sender
                    Keyword: "(?i)\\[shop\\]"
                    Name: "[shop]"
                    Replace: {Enable: true, ReplaceText: "&a[상점]"}
                    Hover: {Enable: false, Text: []}
                    Click: {Enable: true, Action: RUN_COMMAND, Value: "/상점"}
                """.trimIndent(),
            )
        }
        return plugins to ours
    }

    @Test
    fun `DiscordSRV 와 IC 에서 옮기고 토큰은 로그에 안 나온다`() {
        val (plugins, ours) = server()
        val capture = Capture()
        val logger = Logger.getAnonymousLogger().apply { useParentHandlers = false; addHandler(capture) }

        val result = assertNotNull(Migration(ours, plugins, logger).runIfNeeded())
        assertEquals(2, result.links)
        assertEquals(1, result.placeholders)
        assertTrue(capture.lines.isNotEmpty())
        assertTrue(capture.lines.none { token in it || "SECRET" in it }, capture.lines.toString())

        val config = YamlConfiguration.loadConfiguration(File(ours, "config.yml"))
        val settings = Settings.from(config, Logger.getAnonymousLogger())
        assertEquals(token, settings.bot.token)
        assertEquals(519134289263198209L, settings.chatChannel)
        assertEquals(500313817264422942L, settings.consoleChannel)
        assertEquals("", settings.inviteLink)
        assertEquals("UTC", settings.time.zone.id)
        assertEquals(setOf("user", "channel", "emoji"), settings.chat.allowedMentions)
        assertEquals(setOf("say", "lag", "tps"), settings.consoleCommand.whitelist)
        assertEquals("%cmi_user_prefix% | %titleforge_nickname%&r: %message%", settings.chat.discordFormat)
        assertEquals(2, settings.chat.discordFilters.size)
        assertEquals(emptyList(), settings.link.linkedCommands)
        assertEquals(7, settings.mention.cooldownSeconds)
        // 접속·퇴장·사망: 임베드면 작성자 줄, 아니면 본문, 꺼져 있으면 빈칸. 처음 접속은 DiscordSRV 파일에 없어 기본값.
        assertEquals(Settings.PlayerEvent("%displayname% 님이 서버에 접속하셨습니다.", 0x00FF00), settings.playerEvents.join)
        assertEquals("", settings.playerEvents.leave.text)
        assertEquals("%deathmessage%", settings.playerEvents.death.text)
        assertEquals(Settings.PlayerEvents().firstJoin, settings.playerEvents.firstJoin)
        assertTrue(config.isSet(Migration.MARK))

        val messages = YamlConfiguration.loadConfiguration(File(ours, "messages.yml"))
        assertEquals("{name} (UUID : {uuid})의 연동이 성공하였습니다!", messages.getString("discord-linked"))
        assertEquals("**온라인 중인 플레이어 ({count} 명):**", messages.getString("discord-list-header"))

        val links = YamlConfiguration.loadConfiguration(File(ours, "links.yml"))
        assertEquals("333333333333333333", links.getString("links.${UUID(0, 1)}"))
        assertEquals("222222222222222222", links.getString("links.${UUID(0, 2)}"))

        // 기본 여섯은 한국어 기본값 그대로 두고, 관리자가 더한 것만 붙는다.
        val placeholders = CustomPlaceholder.load(YamlConfiguration.loadConfiguration(File(ours, "placeholders.yml")), Logger.getAnonymousLogger())
        assertEquals(7, placeholders.size)
        assertTrue(placeholders.first { it.id == "money" }.text.contains("잔고"))
        val shop = placeholders.first { it.id == "ic-9" }
        assertEquals("RUN_COMMAND", shop.clickAction)
        assertEquals("&a[상점]", shop.text)

        // 두 번째는 아무것도 안 한다.
        assertNull(Migration(ours, plugins, logger).runIfNeeded())
    }

    @Test
    fun `토큰을 이미 넣은 설정은 건드리지 않는다`() {
        val (plugins, ours) = server()
        val file = File(ours, "config.yml")
        YamlConfiguration.loadConfiguration(file).apply { set("bot.token", "mine"); save(file) }
        assertNull(Migration(ours, plugins, Logger.getAnonymousLogger()).runIfNeeded())
        assertEquals("mine", YamlConfiguration.loadConfiguration(file).getString("bot.token"))
    }

    @Test
    fun `accounts aof 는 뒤의 줄이 이기고 모양이 다른 줄은 건너뛴다`() {
        val a = UUID(0, 1)
        val parsed = Migration.parseAccounts(
            listOf(
                "111111111111111111 $a",
                "nonsense",
                "12 $a",
                "222222222222222222 not-a-uuid",
                "333333333333333333 $a",
                "333333333333333333 ${UUID(0, 2)}",
            ),
        )
        assertEquals(mapOf(UUID(0, 2) to 333333333333333333L), parsed)
    }
}
