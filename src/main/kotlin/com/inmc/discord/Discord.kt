package com.inmc.discord

import com.inmc.discord.bot.Bot
import com.inmc.discord.chat.MentionAlerts
import com.inmc.discord.chat.Snapshots
import com.inmc.discord.config.CustomPlaceholder
import com.inmc.discord.config.Messages
import com.inmc.discord.config.Settings
import com.inmc.discord.console.ConsoleRelay
import com.inmc.discord.link.LinkCodes
import com.inmc.discord.link.LinkService
import com.inmc.discord.link.LinkStore
import com.inmc.discord.relay.DiscordToGame
import com.inmc.discord.relay.GameToDiscord
import com.inmc.discord.render.Lang
import com.inmc.discord.render.Renderer
import com.inmc.discord.status.Watchdog
import com.inmc.discord.util.Ph
import kr.inmc.core.InmcHost
import kr.inmc.core.config.ConfigService
import kr.inmc.core.util.Placeholders
import org.bukkit.Bukkit
import org.bukkit.command.CommandSender
import org.bukkit.configuration.file.YamlConfiguration
import org.bukkit.entity.Player
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * 플러그인을 엮는 서비스 로케이터.
 *
 * 스레드가 셋이다 — 메인, 채팅(비동기 채팅 사건), JDA(디스코드). 그래서 설정·메시지는 `@Volatile` 불변 스냅샷이고,
 * 게임 상태를 바꾸는 일은 [runMain] 으로 메인에 넘긴다. **주기적으로 접속자를 훑으며 권한·PAPI 를 묻는 일은 어디에도 없다**
 * — 이 플러그인이 생긴 까닭(InteractiveChat 이 그렇게 해서 LuckPerms 잠금을 붙잡았다, ARCHITECTURE "inmc-discord").
 */
class Discord(override val plugin: DiscordPlugin) : InmcHost {

    val logger: java.util.logging.Logger = plugin.logger

    override val io = ConfigService(plugin)

    override fun tell(target: CommandSender, key: String, ph: Placeholders?) = messages.send(target, key, ph as? Ph)

    @Volatile
    var settings: Settings = Settings()

    @Volatile
    var messages: Messages = Messages.from(YamlConfiguration())

    @Volatile
    var placeholders: List<CustomPlaceholder> = emptyList()

    /** 아이템 이름·툴팁의 번역 키를 디스코드용 글로 푼다. 켤 때·리로드 때 워커에서 읽는다. */
    @Volatile
    var lang: Lang = Lang.EMPTY

    val links = LinkStore(io)

    val codes = LinkCodes()

    val bot = Bot(this)

    val console = ConsoleRelay(this)

    val watchdog = Watchdog(this)

    val snapshots = Snapshots()

    val alerts = MentionAlerts(this)

    val webhooks = com.inmc.discord.relay.Webhooks(this)

    val gameToDiscord = GameToDiscord(this)

    val discordToGame = DiscordToGame(this)

    val linking = LinkService(this)

    val nicknames = com.inmc.discord.link.Nicknames(this)

    val playerEvents = com.inmc.discord.relay.PlayerEvents(this)

    val renderer = Renderer(this)

    val interactions = com.inmc.discord.bot.Interactions(this)

    val slash = com.inmc.discord.bot.SlashCommands(this)

    val verifier = com.inmc.discord.bot.Verifier(this)

    val attachments = com.inmc.discord.preview.Attachments()

    val previews = com.inmc.discord.preview.MapPreviews(this)

    /** 설정 파일 셋을 읽는다. 켤 때(메인)와 리로드(워커)에서. */
    fun readFiles(): Triple<Settings, Messages, List<CustomPlaceholder>> = Triple(
        Settings.from(io.load(io.file("config.yml")), logger),
        Messages.from(io.load(io.file("messages.yml"))),
        CustomPlaceholder.load(io.load(io.file("placeholders.yml")), logger),
    )

    fun apply(files: Triple<Settings, Messages, List<CustomPlaceholder>>) {
        settings = files.first
        messages = files.second
        placeholders = files.third
    }

    /** 다음 틱에 메인에서. 꺼지는 중이면 버린다. */
    fun runMain(task: () -> Unit) {
        if (!plugin.isEnabled) return
        Bukkit.getGlobalRegionScheduler().run(plugin) { task() }
    }

    /** 그 플레이어의 스레드에서(일반 Paper 에서는 메인). 나갔으면 버린다. */
    fun runFor(player: Player, task: () -> Unit) {
        if (!plugin.isEnabled) return
        player.scheduler.run(plugin, { task() }, null)
    }

    /** `%date%` — 설정의 형식·시간대. */
    fun date(): String = runCatching {
        DateTimeFormatter.ofPattern(settings.time.format, Locale.KOREA).format(ZonedDateTime.now(settings.time.zone))
    }.getOrElse { ZonedDateTime.now(settings.time.zone).toString() }

    companion object {
        const val ADMIN = "inmcdiscord.admin"
        const val LINK = "inmcdiscord.link"
        const val VIEW = "inmcdiscord.view"
        const val KEYWORD_ITEM = "inmcdiscord.keyword.item"
        const val KEYWORD_INVENTORY = "inmcdiscord.keyword.inventory"
        const val KEYWORD_ENDER = "inmcdiscord.keyword.ender"
        const val MENTION_PLAYER = "inmcdiscord.mention.player"
        const val MENTION_HERE = "inmcdiscord.mention.here"
        const val MENTION_EVERYONE = "inmcdiscord.mention.everyone"

        /** core 개인 설정 열쇠 — 멘션 알림(소리·제목·보스바) 받기. */
        const val SETTING_MENTION_ALERT = "discord.mention-alert"
    }
}
