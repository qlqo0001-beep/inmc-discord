package com.inmc.discord.config

import org.bukkit.Material
import org.bukkit.configuration.file.YamlConfiguration
import java.time.ZoneId
import java.util.logging.Logger

/**
 * `config.yml` 의 불변 스냅샷. 리로드는 새 객체로 통째 바꾼다 — 채팅·JDA·감시 스레드가 동시에 읽으므로
 * 필드를 고치지 않는다. 참조를 오래 들고 있지 말고 쓸 때마다 `discord.settings` 를 읽는다.
 */
data class Settings(
    val bot: Bot = Bot(),
    val chatChannel: Long? = null,
    val consoleChannel: Long? = null,
    val inviteLink: String = "",
    val time: Time = Time(),
    val chat: Chat = Chat(),
    val console: Console = Console(),
    val consoleCommand: ConsoleCommand = ConsoleCommand(),
    val listCommand: ListCommand = ListCommand(),
    val topic: Topic = Topic(),
    val lifecycle: Lifecycle = Lifecycle(),
    val watchdog: Watchdog = Watchdog(),
    val link: Link = Link(),
    val keywords: Keywords = Keywords(),
    val mention: Mention = Mention(),
    val names: NameInteraction = NameInteraction(),
    val commandTags: CommandTags = CommandTags(),
    val completions: Boolean = true,
    val render: Render = Render(),
    val slash: Slash = Slash(),
    val attachments: Attachments = Attachments(),
    val playerEvents: PlayerEvents = PlayerEvents(),
) {

    /** 토큰은 `toString` 에 절대 나오지 않는다 — 설정을 통째로 로그에 찍는 실수를 막는다. */
    class Bot(val token: String = "", val status: String = "") {
        override fun toString(): String = "Bot(token=${if (token.isBlank()) "없음" else "***"}, status=$status)"
        override fun equals(other: Any?): Boolean = other is Bot && other.token == token && other.status == status
        override fun hashCode(): Int = token.hashCode() * 31 + status.hashCode()
    }

    data class Time(val format: String = "EEE, d. MMM yyyy HH:mm:ss z", val zone: ZoneId = ZoneId.of("Asia/Seoul"))

    data class Chat(
        val toDiscord: Boolean = true,
        val toGame: Boolean = true,
        val discordFormat: String = "%displayname%: %message%",
        val gameFormat: String = "[디코 | %toprolecolor%%toprole%&r] %name%: %message%",
        val gameFormatNoRole: String = "[디코] %name%: %message%",
        val replyFormat: String = " (답장 %name%)",
        val allRolesSeparator: String = " | ",
        val roleAliases: Map<String, String> = emptyMap(),
        val hiddenRoles: Set<String> = emptySet(),
        val truncate: Int = 256,
        val translateMentions: Boolean = true,
        val allowedMentions: Set<String> = setOf("user", "channel", "emoji"),
        val colorRoles: Set<String> = emptySet(),
        val blockWebhooks: Boolean = true,
        val blockBots: Boolean = false,
        val toConsole: Boolean = true,
        val gameFilters: List<Filter> = emptyList(),
        val discordFilters: List<Filter> = emptyList(),
        val webhook: Webhook = Webhook(),
    )

    /** 웹훅 전달 — 메시지마다 플레이어 이름·얼굴. */
    data class Webhook(
        val enabled: Boolean = false,
        val usernameFormat: String = "%displayname%",
        val messageFormat: String = "%message%",
        val avatarUrl: String = "https://mc-heads.net/avatar/{skin}/{size}",
        val avatarSize: Int = 128,
        /** 이미 있는 웹훅 주소. 비면 봇이 채팅 채널에 만든다. 비밀값 — `toString` 에 안 나온다. */
        val url: String = "",
    ) {
        override fun toString(): String =
            "Webhook(enabled=$enabled, usernameFormat=$usernameFormat, messageFormat=$messageFormat, avatarUrl=$avatarUrl, avatarSize=$avatarSize, url=${if (url.isBlank()) "없음" else "***"})"
    }

    /** 정규식 → 바꿀 글. 결과가 비면 그 메시지(줄)를 버린다. */
    data class Filter(val pattern: Regex, val replacement: String)

    data class Console(
        val levels: Set<String> = setOf("info", "warn", "error"),
        val filters: List<Filter> = emptyList(),
        val refreshSeconds: Int = 5,
        val codeBlocks: Boolean = true,
        val prefix: String = "[{date} {level}{name}] ",
        val timestamp: String = "EEE HH:mm:ss",
        val usageLog: String = "Console-%date%.log",
        val blockedCommands: Set<String> = setOf("?", "op", "deop", "execute"),
        val blockBots: Boolean = false,
    )

    data class ConsoleCommand(
        val enabled: Boolean = true,
        val prefix: String = "!c",
        val roles: Set<String> = emptySet(),
        val whitelist: Set<String> = emptySet(),
        val bypassRoles: Set<String> = emptySet(),
        val notifyErrors: Boolean = true,
    )

    data class ListCommand(val enabled: Boolean = true, val trigger: String = "접속목록", val deleteAfter: Int = 10)

    data class Topic(
        val intervalMinutes: Int = 10,
        val chat: String = "",
        val console: String = "",
        val chatShutdown: String = "",
        val consoleShutdown: String = "",
    )

    data class Lifecycle(val startup: String = "", val shutdown: String = "")

    data class Watchdog(
        val enabled: Boolean = true,
        val timeoutSeconds: Int = 30,
        val messageCount: Int = 3,
        val message: String = "",
        val recovered: String = "",
    )

    data class Link(
        val role: String = "",
        val allowRelink: Boolean = false,
        val codeMinutes: Int = 10,
        val linkedCommands: List<String> = emptyList(),
        val unlinkedCommands: List<String> = emptyList(),
        /** 연결한 사람의 디스코드 서버 별명을 게임 닉네임으로(사용자 결정 2026-10-07). */
        val syncNickname: Boolean = true,
    )

    data class Keyword(
        val enabled: Boolean = true,
        val pattern: Regex = Regex("(?!)"),
        val name: String = "",
        val description: String = "",
        val text: String = "",
        val singularText: String = "",
        val title: String = "",
        val hover: List<String> = emptyList(),
        val allowAir: Boolean = true,
    )

    data class Keywords(
        val timeoutMinutes: Int = 5,
        val item: Keyword = Keyword(),
        val inventory: Keyword = Keyword(),
        val ender: Keyword = Keyword(),
        val framePrimary: Material = Material.BLACK_STAINED_GLASS_PANE,
        val frameSecondary: Material = Material.WHITE_STAINED_GLASS_PANE,
    )

    data class Mention(
        val enabled: Boolean = true,
        val prefix: String = "@",
        val cooldownSeconds: Int = 3,
        val sound: String = "ENTITY_EXPERIENCE_ORB_PICKUP",
        val title: String = "",
        val titleSeconds: Double = 1.5,
        val subtitle: String = "",
        val actionbar: String = "",
        val bossbarText: String = "",
        val bossbarColor: String = "YELLOW",
        val bossbarOverlay: String = "PROGRESS",
        val bossbarSeconds: Double = 6.0,
        val highlightSelf: String = "&e{mentioned}",
        val highlightOthers: String = "&3{mentioned}",
        val hover: List<String> = emptyList(),
        val discordSubtitle: String = "",
        val discordHighlight: String = "&9{mentioned}",
    )

    data class NameInteraction(
        val enabled: Boolean = true,
        val hover: List<String> = emptyList(),
        val clickAction: String = "SUGGEST_COMMAND",
        val clickValue: String = "/msg {player} ",
    )

    /** 디스코드로 보내는 아이템 그림. */
    data class Render(
        val enabled: Boolean = true,
        val scale: Int = 2,
        val language: String = "ko_kr",
        val autoDownload: Boolean = true,
        /** plugins 폴더 기준. 빈칸이면 바닐라만. */
        val serverPack: String = "inmc-customitems/pack/output/pack.zip",
        val threads: Int = 2,
    )

    /** 디스코드 슬래시 명령어(InteractiveChat 디스코드 애드온의 DiscordCommands). */
    data class Slash(
        val playerList: Boolean = true,
        val listHeader: List<String> = emptyList(),
        val listFooter: List<String> = emptyList(),
        val listFormat: String = "{player}",
        val listMax: Int = 80,
        /** `PLACEHOLDER:%x%` · `PLACEHOLDER_REVERSE:%x%` · `PLAYERNAME` — 앞의 것부터 견준다. */
        val listOrder: List<String> = listOf("PLAYERNAME"),
        val playerInfo: Boolean = true,
        val infoTitle: String = "{player}",
        val infoOnline: List<String> = emptyList(),
        val infoOffline: List<String> = emptyList(),
        val share: Boolean = true,
        /** 남의 것을 공유할 수 있는 디스코드 역할. 비면 아무도. */
        val shareOthersRoles: Set<String> = emptySet(),
    )

    /** 디스코드 첨부 그림을 게임에서 지도로 미리보기. */
    data class Attachments(val preview: Boolean = true, val maxBytes: Int = 8 * 1024 * 1024, val keepHours: Int = 24)

    data class CommandTags(val enabled: Boolean = true, val text: String = "&b[&e{command}&b]", val hover: List<String> = emptyList())

    /** 접속·퇴장·사망 알림 하나 — 채팅 채널에 색 띠 + 얼굴 + 한 줄 임베드. 글이 비면 안 보낸다. */
    data class PlayerEvent(val text: String = "", val color: Int? = null)

    /** DiscordSRV 의 MinecraftPlayerJoin·FirstJoin·Leave·DeathMessage. */
    data class PlayerEvents(
        val join: PlayerEvent = PlayerEvent("%displayname% 님이 서버에 접속하셨습니다.", 0x00FF00),
        val firstJoin: PlayerEvent = PlayerEvent("%displayname% 님이 처음으로 서버에 접속하셨습니다!", 0xFFD700),
        val leave: PlayerEvent = PlayerEvent("%displayname% 님이 서버에서 나가셨습니다.", 0xFF0000),
        val death: PlayerEvent = PlayerEvent("%deathmessage%", 0x000000),
    )

    companion object {

        /** 읽다 틀린 값은 경고를 남기고 기본값으로 — 한 줄이 틀렸다고 플러그인 전체가 서지 않게. */
        fun from(config: YamlConfiguration, logger: Logger): Settings {
            val d = Settings()
            fun regex(source: String, where: String): Regex? = try {
                Regex(source)
            } catch (e: Exception) {
                logger.warning("$where 의 정규식이 틀려 건너뜁니다: $source (${e.message})")
                null
            }
            // 목록 모양({pattern, replace}). 맵 열쇠로 두면 정규식의 점에서 Bukkit 이 경로를 쪼갠다.
            fun filters(path: String): List<Filter> =
                config.getMapList(path).mapNotNull { entry ->
                    val pattern = entry["pattern"]?.toString() ?: return@mapNotNull null
                    regex(pattern, path)?.let { Filter(it, entry["replace"]?.toString().orEmpty()) }
                }
            fun strings(path: String, fallback: Collection<String>): Set<String> =
                if (config.isList(path)) config.getStringList(path).toSet() else fallback.toSet()
            fun channel(path: String): Long? = config.getString(path)?.trim()?.toLongOrNull()?.takeIf { it > 0 }
            fun keyword(path: String, fallback: Keyword): Keyword {
                val s = config.getConfigurationSection(path) ?: return fallback
                return Keyword(
                    enabled = s.getBoolean("enabled", true),
                    pattern = regex(s.getString("pattern") ?: "(?!)", "$path.pattern") ?: Regex("(?!)"),
                    name = s.getString("name") ?: "",
                    description = s.getString("description") ?: "",
                    text = s.getString("text") ?: "",
                    singularText = s.getString("singular-text") ?: s.getString("text") ?: "",
                    title = s.getString("title") ?: "",
                    hover = s.getStringList("hover"),
                    allowAir = s.getBoolean("allow-air", true),
                )
            }
            fun material(path: String, fallback: Material): Material =
                config.getString(path)?.let { Material.matchMaterial(it) } ?: fallback
            fun event(path: String, fallback: PlayerEvent): PlayerEvent = PlayerEvent(
                text = config.getString("$path.text") ?: fallback.text,
                color = config.getString("$path.color")?.let { it.trim().removePrefix("#").toIntOrNull(16) } ?: fallback.color,
            )

            val zone = config.getString("time.zone")?.let { runCatching { ZoneId.of(it) }.getOrNull() }
                ?: d.time.zone.also { if (config.isSet("time.zone")) logger.warning("time.zone 을 알 수 없어 ${it.id} 로 씁니다") }

            return Settings(
                bot = Bot(config.getString("bot.token")?.trim() ?: "", config.getString("bot.status") ?: ""),
                chatChannel = channel("channels.chat"),
                consoleChannel = channel("channels.console"),
                inviteLink = config.getString("invite-link") ?: "",
                time = Time(config.getString("time.format") ?: d.time.format, zone),
                chat = Chat(
                    toDiscord = config.getBoolean("chat.to-discord", true),
                    toGame = config.getBoolean("chat.to-game", true),
                    discordFormat = config.getString("chat.discord-format") ?: d.chat.discordFormat,
                    gameFormat = config.getString("chat.game-format") ?: d.chat.gameFormat,
                    gameFormatNoRole = config.getString("chat.game-format-no-role") ?: d.chat.gameFormatNoRole,
                    replyFormat = config.getString("chat.reply-format") ?: d.chat.replyFormat,
                    allRolesSeparator = config.getString("chat.all-roles-separator") ?: d.chat.allRolesSeparator,
                    roleAliases = config.getConfigurationSection("chat.role-aliases")?.let { s ->
                        s.getKeys(false).associateWith { s.getString(it) ?: it }
                    } ?: emptyMap(),
                    hiddenRoles = strings("chat.hidden-roles", emptySet()),
                    truncate = config.getInt("chat.truncate", 256).coerceAtLeast(0),
                    translateMentions = config.getBoolean("chat.translate-mentions", true),
                    allowedMentions = strings("chat.allowed-mentions", d.chat.allowedMentions).map { it.lowercase() }.toSet(),
                    colorRoles = strings("chat.color-roles", emptySet()),
                    blockWebhooks = config.getBoolean("chat.block-webhooks", true),
                    blockBots = config.getBoolean("chat.block-bots", false),
                    toConsole = config.getBoolean("chat.to-console", true),
                    gameFilters = filters("chat.game-filters"),
                    discordFilters = filters("chat.discord-filters"),
                    webhook = Webhook(
                        enabled = config.getBoolean("chat.webhook.enabled", false),
                        usernameFormat = config.getString("chat.webhook.username-format") ?: d.chat.webhook.usernameFormat,
                        messageFormat = config.getString("chat.webhook.message-format") ?: d.chat.webhook.messageFormat,
                        avatarUrl = config.getString("chat.webhook.avatar-url") ?: d.chat.webhook.avatarUrl,
                        avatarSize = config.getInt("chat.webhook.avatar-size", 128).coerceIn(16, 512),
                        url = config.getString("chat.webhook.url")?.trim().orEmpty(),
                    ),
                ),
                console = Console(
                    levels = strings("console.levels", d.console.levels).map { it.lowercase() }.toSet(),
                    filters = filters("console.filters"),
                    refreshSeconds = config.getInt("console.refresh-seconds", 5).coerceAtLeast(1),
                    codeBlocks = config.getBoolean("console.code-blocks", true),
                    prefix = config.getString("console.prefix") ?: d.console.prefix,
                    timestamp = config.getString("console.timestamp") ?: d.console.timestamp,
                    usageLog = config.getString("console.usage-log") ?: "",
                    blockedCommands = strings("console.blocked-commands", d.console.blockedCommands).map { it.lowercase() }.toSet(),
                    blockBots = config.getBoolean("console.block-bots", false),
                ),
                consoleCommand = ConsoleCommand(
                    enabled = config.getBoolean("console-command.enabled", true),
                    prefix = config.getString("console-command.prefix") ?: "!c",
                    roles = strings("console-command.roles", emptySet()),
                    whitelist = strings("console-command.whitelist", emptySet()).map { it.lowercase() }.toSet(),
                    bypassRoles = strings("console-command.bypass-roles", emptySet()),
                    notifyErrors = config.getBoolean("console-command.notify-errors", true),
                ),
                listCommand = ListCommand(
                    enabled = config.getBoolean("list-command.enabled", true),
                    trigger = config.getString("list-command.trigger") ?: d.listCommand.trigger,
                    deleteAfter = config.getInt("list-command.delete-after", 10).coerceAtLeast(0),
                ),
                topic = Topic(
                    intervalMinutes = config.getInt("topic.interval-minutes", 10).coerceAtLeast(MIN_TOPIC_MINUTES),
                    chat = config.getString("topic.chat") ?: "",
                    console = config.getString("topic.console") ?: "",
                    chatShutdown = config.getString("topic.chat-shutdown") ?: "",
                    consoleShutdown = config.getString("topic.console-shutdown") ?: "",
                ),
                lifecycle = Lifecycle(config.getString("lifecycle.startup") ?: "", config.getString("lifecycle.shutdown") ?: ""),
                watchdog = Watchdog(
                    enabled = config.getBoolean("watchdog.enabled", true),
                    timeoutSeconds = config.getInt("watchdog.timeout-seconds", 30).coerceAtLeast(5),
                    messageCount = config.getInt("watchdog.message-count", 3).coerceIn(1, 10),
                    message = config.getString("watchdog.message") ?: "",
                    recovered = config.getString("watchdog.recovered") ?: "",
                ),
                link = Link(
                    role = config.getString("link.role") ?: "",
                    allowRelink = config.getBoolean("link.allow-relink", false),
                    codeMinutes = config.getInt("link.code-minutes", 10).coerceAtLeast(1),
                    linkedCommands = config.getStringList("link.linked-commands").filter { it.isNotBlank() },
                    unlinkedCommands = config.getStringList("link.unlinked-commands").filter { it.isNotBlank() },
                    syncNickname = config.getBoolean("link.sync-nickname", true),
                ),
                keywords = Keywords(
                    timeoutMinutes = config.getInt("keywords.timeout-minutes", 5).coerceAtLeast(1),
                    item = keyword("keywords.item", d.keywords.item),
                    inventory = keyword("keywords.inventory", d.keywords.inventory),
                    ender = keyword("keywords.ender", d.keywords.ender),
                    framePrimary = material("keywords.frame.primary", d.keywords.framePrimary),
                    frameSecondary = material("keywords.frame.secondary", d.keywords.frameSecondary),
                ),
                mention = Mention(
                    enabled = config.getBoolean("mention.enabled", true),
                    prefix = config.getString("mention.prefix")?.takeIf { it.isNotEmpty() } ?: "@",
                    cooldownSeconds = config.getInt("mention.cooldown-seconds", 3).coerceAtLeast(0),
                    sound = config.getString("mention.sound") ?: "",
                    title = config.getString("mention.title") ?: "",
                    titleSeconds = config.getDouble("mention.title-seconds", 1.5),
                    subtitle = config.getString("mention.subtitle") ?: "",
                    actionbar = config.getString("mention.actionbar") ?: "",
                    bossbarText = config.getString("mention.bossbar.text") ?: "",
                    bossbarColor = config.getString("mention.bossbar.color") ?: "YELLOW",
                    bossbarOverlay = config.getString("mention.bossbar.overlay") ?: "PROGRESS",
                    bossbarSeconds = config.getDouble("mention.bossbar.seconds", 6.0),
                    highlightSelf = config.getString("mention.highlight-self") ?: d.mention.highlightSelf,
                    highlightOthers = config.getString("mention.highlight-others") ?: d.mention.highlightOthers,
                    hover = config.getStringList("mention.hover"),
                    discordSubtitle = config.getString("mention.discord-subtitle") ?: "",
                    discordHighlight = config.getString("mention.discord-highlight") ?: d.mention.discordHighlight,
                ),
                names = NameInteraction(
                    enabled = config.getBoolean("name-interaction.enabled", true),
                    hover = config.getStringList("name-interaction.hover"),
                    clickAction = config.getString("name-interaction.click-action") ?: "",
                    clickValue = config.getString("name-interaction.click-value") ?: "",
                ),
                commandTags = CommandTags(
                    enabled = config.getBoolean("command-tags.enabled", true),
                    text = config.getString("command-tags.text") ?: d.commandTags.text,
                    hover = config.getStringList("command-tags.hover"),
                ),
                completions = config.getBoolean("completions", true),
                slash = Slash(
                    playerList = config.getBoolean("slash.player-list.enabled", true),
                    listHeader = config.getStringList("slash.player-list.header"),
                    listFooter = config.getStringList("slash.player-list.footer"),
                    listFormat = config.getString("slash.player-list.player-format") ?: "{player}",
                    listMax = config.getInt("slash.player-list.max-players", 80).coerceIn(1, 200),
                    listOrder = config.getStringList("slash.player-list.order-by").ifEmpty { listOf("PLAYERNAME") },
                    playerInfo = config.getBoolean("slash.player-info.enabled", true),
                    infoTitle = config.getString("slash.player-info.title") ?: "{player}",
                    infoOnline = config.getStringList("slash.player-info.online"),
                    infoOffline = config.getStringList("slash.player-info.offline"),
                    share = config.getBoolean("slash.share.enabled", true),
                    shareOthersRoles = strings("slash.share.others-roles", emptySet()),
                ),
                attachments = Attachments(
                    preview = config.getBoolean("attachments.preview", true),
                    maxBytes = (config.getDouble("attachments.max-mb", 8.0) * 1024 * 1024).toInt().coerceIn(64 * 1024, 32 * 1024 * 1024),
                    keepHours = config.getInt("attachments.keep-hours", 24).coerceIn(1, 24 * 7),
                ),
                render = Render(
                    enabled = config.getBoolean("render.enabled", true),
                    scale = config.getInt("render.scale", 2).coerceIn(2, 4),
                    language = config.getString("render.language")?.lowercase()?.takeIf { it.matches(Regex("[a-z]{2,3}_[a-z]{2,3}")) } ?: "ko_kr",
                    autoDownload = config.getBoolean("render.auto-download", true),
                    serverPack = config.getString("render.server-pack") ?: "",
                    threads = config.getInt("render.threads", 2).coerceIn(1, 4),
                ),
                playerEvents = PlayerEvents(
                    join = event("player-events.join", d.playerEvents.join),
                    firstJoin = event("player-events.first-join", d.playerEvents.firstJoin),
                    leave = event("player-events.leave", d.playerEvents.leave),
                    death = event("player-events.death", d.playerEvents.death),
                ),
            )
        }

        /** 디스코드는 채널 주제 변경을 10분에 두 번까지만 받는다. */
        const val MIN_TOPIC_MINUTES = 10
    }
}
