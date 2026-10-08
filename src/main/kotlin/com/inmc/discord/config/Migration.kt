package com.inmc.discord.config

import org.bukkit.configuration.ConfigurationSection
import org.bukkit.configuration.file.YamlConfiguration
import java.io.File
import java.util.UUID
import java.util.logging.Logger

/**
 * DiscordSRV · InteractiveChat 에서 옮기기 — **처음 켤 때 한 번**.
 *
 * 우리 `config.yml` 에 `migrated-from` 이 없고 봇 토큰이 비어 있을 때만 돈다(관리자가 이미 손댄 설정은 건드리지 않는다).
 * - DiscordSRV: 봇·채널·중계 형식·콘솔·`!c`·접속목록·주제·감시·계정 연결 설정과 그 메시지, `accounts.aof` 의 연결 계정.
 *   DiscordSRV 의 메시지는 이 서버에서 이미 한국어로 고쳐 둔 것이라 글까지 옮긴다.
 * - InteractiveChat: 켜고 끄기·정규식·멘션 수치·탭 완성처럼 **동작**만. 글은 우리 한국어 기본값을 둔다(IC 의 글은 영어 기본 그대로였다).
 *   사용자 자리표시는 IC 기본 일곱 개면 우리 한국어 기본값을 두고, 그 밖에 관리자가 더한 것만 그대로 옮긴다.
 *
 * **봇 토큰은 파일에서 파일로만** 옮긴다. 로그·메시지에 값을 내지 않는다(`MigrationTest` 가 지킨다).
 */
class Migration(private val dataFolder: File, private val pluginsFolder: File, private val logger: Logger) {

    data class Result(val config: Boolean, val placeholders: Int, val links: Int)

    /** 옮겼으면 무엇을 옮겼는지, 할 일이 없었으면 null. */
    fun runIfNeeded(): Result? {
        val configFile = File(dataFolder, "config.yml")
        val config = YamlConfiguration.loadConfiguration(configFile)
        if (config.isSet(MARK) || config.getString("bot.token").orEmpty().isNotBlank()) return null

        val srvFolder = File(pluginsFolder, "DiscordSRV")
        val icFolder = File(pluginsFolder, "InteractiveChat")
        val srvConfig = File(srvFolder, "config.yml").takeIf { it.isFile }
        val icConfig = File(icFolder, "config.yml").takeIf { it.isFile }
        if (srvConfig == null && icConfig == null) return null

        val from = ArrayList<String>()
        var links = 0
        var placeholders = 0
        val messagesFile = File(dataFolder, "messages.yml")
        val messages = YamlConfiguration.loadConfiguration(messagesFile)

        if (srvConfig != null) {
            val srvMessages = File(srvFolder, "messages.yml").takeIf { it.isFile }?.let(::loadFlat) ?: YamlConfiguration()
            fromDiscordSrv(loadFlat(srvConfig), srvMessages, config, messages)
            from += "DiscordSRV"
            val aof = File(srvFolder, "accounts.aof")
            if (aof.isFile) {
                val parsed = parseAccounts(aof.readLines(Charsets.UTF_8))
                if (parsed.isNotEmpty()) {
                    val linksFile = File(dataFolder, "links.yml")
                    val existing = YamlConfiguration.loadConfiguration(linksFile)
                    for ((uuid, id) in parsed) if (!existing.isSet("links.$uuid")) existing.set("links.$uuid", id.toString())
                    existing.save(linksFile)
                    links = parsed.size
                }
            }
        }
        if (icConfig != null) {
            val placeholdersFile = File(dataFolder, "placeholders.yml")
            val target = YamlConfiguration.loadConfiguration(placeholdersFile)
            placeholders = fromInteractiveChat(YamlConfiguration.loadConfiguration(icConfig), config, target)
            if (placeholders > 0) target.save(placeholdersFile)
            from += "InteractiveChat"
        }

        config.set(MARK, from.joinToString(" · "))
        config.save(configFile)
        messages.save(messagesFile)
        logger.info(
            "${from.joinToString("·")} 설정을 옮겼습니다 — 연결 계정 ${links}명, 사용자 자리표시 ${placeholders}개" +
                (if (config.getString("bot.token").orEmpty().isNotBlank()) ", 봇 토큰(값은 표시하지 않음)" else ""),
        )
        return Result(true, placeholders, links)
    }

    companion object {

        const val MARK = "migrated-from"

        /** 설정 열쇠에 나올 리 없는 글자(U+0001). */
        private const val FLAT_SEPARATOR = '\u0001'

        /**
         * DiscordSRV 파일은 점이 든 열쇠(`".*Online players\(.*": ""`)를 쓴다. Bukkit 은 점을 경로로 쪼개므로
         * 쓰이지 않는 글자를 경로 구분자로 두고 읽는다. DiscordSRV 설정은 전부 맨 위 열쇠라 경로가 필요 없다.
         */
        fun loadFlat(file: File): YamlConfiguration =
            YamlConfiguration().apply {
                options().pathSeparator(FLAT_SEPARATOR)
                load(file)
            }

        /** DiscordSRV 의 `%이름%` 토큰 → 우리 messages.yml 의 `{이름}`. */
        private val SRV_MESSAGES: List<Triple<String, String, Map<String, String>>> = listOf(
            Triple("UnknownCode", "discord-unknown-code", emptyMap()),
            Triple("InvalidCode", "discord-invalid-code", emptyMap()),
            Triple("DiscordAccountLinked", "discord-linked", mapOf("%name%" to "{name}", "%uuid%" to "{uuid}")),
            Triple("DiscordAccountAlreadyLinked", "discord-already-linked", mapOf("%username%" to "{name}", "%uuid%" to "{uuid}")),
            Triple("MinecraftAccountLinked", "linked-now", mapOf("%username%" to "{user}", "%id%" to "{id}")),
            Triple("MinecraftAccountAlreadyLinked", "already-linked", emptyMap()),
            Triple("LinkedCommandSuccess", "linked-status", mapOf("%name%" to "{name}")),
            Triple("UnlinkCommandSuccess", "unlinked", mapOf("%name%" to "{name}")),
            Triple("MinecraftNoLinkedAccount", "not-linked", emptyMap()),
            Triple("DiscordChatChannelListCommandFormatOnlinePlayers", "discord-list-header", mapOf("%playercount%" to "{count}")),
            Triple("DiscordChatChannelListCommandFormatNoOnlinePlayers", "discord-list-empty", emptyMap()),
            Triple("DiscordChatChannelListCommandAllPlayersSeparator", "discord-list-separator", emptyMap()),
            Triple("DiscordChatChannelConsoleCommandNotifyErrorsFormat", "discord-command-error", mapOf("%user%" to "{user}", "%error%" to "{error}")),
        )

        /** 연결·해제 명령어의 DiscordSRV 토큰. */
        private val SRV_COMMAND_TOKENS = mapOf(
            "%minecraftplayername%" to "{player}",
            "%minecraftuuid%" to "{uuid}",
            "%discordid%" to "{discord}",
        )

        fun fromDiscordSrv(src: YamlConfiguration, srvMessages: YamlConfiguration, target: YamlConfiguration, messages: YamlConfiguration) {
            fun copy(from: String, to: String, transform: (Any) -> Any? = { it }) {
                val value = src.get(from) ?: return
                transform(value)?.let { target.set(to, it) }
            }
            fun copyMessage(from: String, to: String) {
                srvMessages.getString(from)?.let { target.set(to, it) }
            }

            copy("BotToken", "bot.token") { (it as? String)?.trim()?.takeIf(String::isNotEmpty) }
            copy("DiscordGameStatus", "bot.status") { if (it is List<*>) it.firstOrNull()?.toString().orEmpty() else it.toString() }
            src.getConfigurationSection("Channels")?.let { channels ->
                (channels.getString("global") ?: channels.getKeys(false).firstOrNull()?.let(channels::getString))
                    ?.let { target.set("channels.chat", it) }
            }
            copy("DiscordConsoleChannelId", "channels.console") { it.toString().takeIf { id -> id.all(Char::isDigit) && id.isNotEmpty() && id.any { c -> c != '0' } } }
            copy("DiscordInviteLink", "invite-link") { it.toString().takeUnless { link -> "changethisintheconfig" in link } }
            copy("TimestampFormat", "time.format")
            copy("Timezone", "time.zone") { it.toString().takeUnless { zone -> zone.equals("default", ignoreCase = true) } }

            copy("DiscordChatChannelMinecraftToDiscord", "chat.to-discord")
            copy("DiscordChatChannelDiscordToMinecraft", "chat.to-game")
            copyMessage("MinecraftChatToDiscordMessageFormat", "chat.discord-format")
            copyMessage("DiscordToMinecraftChatMessageFormat", "chat.game-format")
            copyMessage("DiscordToMinecraftChatMessageFormatNoRole", "chat.game-format-no-role")
            copyMessage("DiscordToMinecraftMessageReplyFormat", "chat.reply-format")
            copyMessage("DiscordToMinecraftAllRolesSeparator", "chat.all-roles-separator")
            section(src, "DiscordChatChannelRoleAliases")?.let { target.set("chat.role-aliases", it) }
            if (!src.getBoolean("DiscordChatChannelRolesSelectionAsWhitelist", false)) {
                copy("DiscordChatChannelRolesSelection", "chat.hidden-roles")
            }
            copy("DiscordChatChannelTruncateLength", "chat.truncate")
            copy("DiscordChatChannelTranslateMentions", "chat.translate-mentions")
            copy("DiscordChatChannelAllowedMentions", "chat.allowed-mentions") { value ->
                (value as? List<*>)?.map { if (it.toString().equals("emote", true)) "emoji" else it.toString().lowercase() }
            }
            copy("DiscordChatChannelRolesAllowedToUseColorCodesInChat", "chat.color-roles")
            copy("DiscordChatChannelBlockWebhooks", "chat.block-webhooks")
            copy("DiscordChatChannelBlockBots", "chat.block-bots")
            copy("DiscordChatChannelBroadcastDiscordMessagesToConsole", "chat.to-console")
            copy("Experiment_WebhookChatMessageDelivery", "chat.webhook.enabled")
            copy("Experiment_WebhookChatMessageUsernameFormat", "chat.webhook.username-format")
            copy("Experiment_WebhookChatMessageFormat", "chat.webhook.message-format")
            // `mc-heads.net/avatars/…` 는 없는 주소(404)라 옮기지 않는다 — 이 서버의 DiscordSRV 에 들어 있던 값. 디스코드가 기본 로고를 보였다.
            copy("AvatarUrl", "chat.webhook.avatar-url") { it.toString().takeIf { url -> url.isNotBlank() && "mc-heads.net/avatars/" !in url } }
            section(src, "DiscordChatChannelGameFilters")?.let { target.set("chat.game-filters", filterList(it)) }
            section(src, "DiscordChatChannelDiscordFilters")?.let { target.set("chat.discord-filters", filterList(it)) }

            copy("DiscordConsoleChannelLevels", "console.levels")
            section(src, "DiscordConsoleChannelFilters")?.let { target.set("console.filters", filterList(it)) }
            copy("DiscordConsoleChannelLogRefreshRateInSeconds", "console.refresh-seconds")
            copy("DiscordConsoleChannelUseCodeBlocks", "console.code-blocks")
            copy("DiscordConsoleChannelUsageLog", "console.usage-log")
            if (!src.getBoolean("DiscordConsoleChannelBlacklistActsAsWhitelist", false)) {
                copy("DiscordConsoleChannelBlacklistedCommands", "console.blocked-commands")
            }
            copy("DiscordConsoleChannelBlockBots", "console.block-bots")
            copyMessage("DiscordConsoleChannelPrefix", "console.prefix")
            copyMessage("DiscordConsoleChannelTimestampFormat", "console.timestamp")

            copy("DiscordChatChannelConsoleCommandEnabled", "console-command.enabled")
            copy("DiscordChatChannelConsoleCommandPrefix", "console-command.prefix")
            copy("DiscordChatChannelConsoleCommandRolesAllowed", "console-command.roles")
            copy("DiscordChatChannelConsoleCommandWhitelist", "console-command.whitelist") { (it as? List<*>)?.map(Any?::toString)?.distinct() }
            copy("DiscordChatChannelConsoleCommandWhitelistBypassRoles", "console-command.bypass-roles")
            copy("DiscordChatChannelConsoleCommandNotifyErrors", "console-command.notify-errors")

            copy("DiscordChatChannelListCommandEnabled", "list-command.enabled")
            copy("DiscordChatChannelListCommandMessage", "list-command.trigger")
            copy("DiscordChatChannelListCommandExpiration", "list-command.delete-after")

            copy("ChannelTopicUpdaterRateInMinutes", "topic.interval-minutes")
            copyMessage("ChannelTopicUpdaterChatChannelTopicFormat", "topic.chat")
            copyMessage("ChannelTopicUpdaterConsoleChannelTopicFormat", "topic.console")
            if (src.getBoolean("ChannelTopicUpdaterChannelTopicsAtShutdownEnabled", true)) {
                copyMessage("ChannelTopicUpdaterChatChannelTopicAtServerShutdownFormat", "topic.chat-shutdown")
                copyMessage("ChannelTopicUpdaterConsoleChannelTopicAtServerShutdownFormat", "topic.console-shutdown")
            } else {
                target.set("topic.chat-shutdown", "")
                target.set("topic.console-shutdown", "")
            }

            copyMessage("DiscordChatChannelServerStartupMessage", "lifecycle.startup")
            copyMessage("DiscordChatChannelServerShutdownMessage", "lifecycle.shutdown")

            copy("ServerWatchdogEnabled", "watchdog.enabled")
            copy("ServerWatchdogTimeout", "watchdog.timeout-seconds")
            copy("ServerWatchdogMessageCount", "watchdog.message-count")
            copyMessage("ServerWatchdogMessage", "watchdog.message")

            copy("MinecraftDiscordAccountLinkedRoleNameToAddUserTo", "link.role")
            copy("MinecraftDiscordAccountLinkedAllowRelinkBySendingANewCode", "link.allow-relink")
            copy("MinecraftDiscordAccountLinkedConsoleCommands", "link.linked-commands") { commands(it) }
            copy("MinecraftDiscordAccountUnlinkedConsoleCommands", "link.unlinked-commands") { commands(it) }

            for ((from, to, tokens) in SRV_MESSAGES) {
                var text = srvMessages.getString(from) ?: continue
                for ((a, b) in tokens) text = text.replace(a, b)
                messages.set(to, text)
            }

            // 접속·처음 접속·퇴장·사망 — 우리는 임베드 한 줄만 보낸다: 임베드가 켜져 있으면 그 작성자 줄(없으면 설명), 꺼져 있으면 본문.
            // 토큰(%displayname% 등)은 같은 이름으로 받으므로 글 그대로.
            val sep = srvMessages.options().pathSeparator()
            for ((from, to) in SRV_PLAYER_EVENTS) {
                val s = srvMessages.getConfigurationSection(from) ?: continue
                fun str(vararg path: String) = s.getString(path.joinToString(sep.toString())).orEmpty()
                val embed = s.getBoolean("Embed${sep}Enabled", false)
                val text = when {
                    !s.getBoolean("Enabled", true) -> ""
                    embed -> str("Embed", "Author", "Name").ifBlank { str("Embed", "Description") }
                    else -> str("Content")
                }
                target.set("player-events.$to.text", text)
                if (embed) str("Embed", "Color").takeIf(String::isNotBlank)?.let { target.set("player-events.$to.color", it) }
            }
        }

        private val SRV_PLAYER_EVENTS = listOf(
            "MinecraftPlayerJoinMessage" to "join",
            "MinecraftPlayerFirstJoinMessage" to "first-join",
            "MinecraftPlayerLeaveMessage" to "leave",
            "MinecraftPlayerDeathMessage" to "death",
        )

        private fun commands(value: Any): List<String>? =
            (value as? List<*>)?.map(Any?::toString)?.filter(String::isNotBlank)?.map { command ->
                SRV_COMMAND_TOKENS.entries.fold(command) { acc, (a, b) -> acc.replace(a, b) }
            }

        private fun filterList(map: Map<String, String>): List<Map<String, String>> =
            map.map { (pattern, replace) -> mapOf("pattern" to pattern, "replace" to replace) }

        /** DiscordSRV 의 `{"a": "b"}` 모양 맵. */
        private fun section(src: YamlConfiguration, path: String): Map<String, String>? {
            val s: ConfigurationSection = src.getConfigurationSection(path) ?: return null
            return s.getKeys(false).associateWith { s.getString(it).orEmpty() }
        }

        /** IC 의 사용자 자리표시 기본 일곱 개 — 이것들은 우리 한국어 기본값이 대신한다. */
        private val IC_STOCK_KEYWORDS = setOf(
            "(?i)\\[money\\]|\\[m\\]",
            "(?i)\\[loohpjames\\]",
            "(?i)\\[gametime\\]",
            "(?i)\\[match: *([^\\[\\]]*)\\]",
            "(?i)\\[time\\]",
            "(?i)\\[pos\\]",
            "(?i)\\[ping\\]",
        )

        /** @return 옮긴 사용자 자리표시 수 */
        fun fromInteractiveChat(src: YamlConfiguration, target: YamlConfiguration, placeholders: YamlConfiguration): Int {
            fun copy(from: String, to: String) {
                src.get(from)?.let { target.set(to, it) }
            }
            for ((ic, ours) in listOf("Item" to "item", "Inventory" to "inventory", "EnderChest" to "ender")) {
                copy("ItemDisplay.$ic.Enabled", "keywords.$ours.enabled")
                copy("ItemDisplay.$ic.Keyword", "keywords.$ours.pattern")
            }
            copy("ItemDisplay.Item.EmptyItemSettings.AllowAir", "keywords.item.allow-air")
            copy("ItemDisplay.Settings.Timeout", "keywords.timeout-minutes")
            copy("ItemDisplay.Item.Frame.Primary", "keywords.frame.primary")
            copy("ItemDisplay.Item.Frame.Secondary", "keywords.frame.secondary")

            copy("Chat.AllowMention", "mention.enabled")
            copy("Chat.MentionPrefix", "mention.prefix")
            copy("Chat.MentionCooldown", "mention.cooldown-seconds")
            copy("Chat.MentionedSound", "mention.sound")
            copy("Chat.MentionedTitleDuration", "mention.title-seconds")
            copy("Chat.MentionBossBar.Color", "mention.bossbar.color")
            copy("Chat.MentionBossBar.Overlay", "mention.bossbar.overlay")
            copy("Chat.MentionBossBar.Duration", "mention.bossbar.seconds")

            copy("Player.UsePlayerNameInteraction", "name-interaction.enabled")
            copy("Player.Click.Action", "name-interaction.click-action")
            src.getString("Player.Click.Value")?.let { target.set("name-interaction.click-value", it.replace("%player_name%", "{player}")) }
            copy("Commands.Enabled", "command-tags.enabled")
            copy("TabCompletion.ChatTabCompletions.Enabled", "completions")

            val custom = src.getConfigurationSection("CustomPlaceholders") ?: return 0
            var moved = 0
            for (key in custom.getKeys(false)) {
                val s = custom.getConfigurationSection(key) ?: continue
                val keyword = s.getString("Keyword") ?: continue
                if (keyword in IC_STOCK_KEYWORDS) continue
                val id = "ic-$key"
                placeholders.set("$id.pattern", keyword)
                placeholders.set("$id.name", s.getString("Name") ?: keyword)
                placeholders.set("$id.description", s.getString("Description").orEmpty())
                placeholders.set("$id.parse-player", s.getString("ParsePlayer") ?: "sender")
                placeholders.set("$id.text", if (s.getBoolean("Replace.Enable")) s.getString("Replace.ReplaceText").orEmpty() else "")
                placeholders.set("$id.hover", if (s.getBoolean("Hover.Enable")) s.getStringList("Hover.Text") else emptyList<String>())
                placeholders.set("$id.click-action", if (s.getBoolean("Click.Enable")) s.getString("Click.Action").orEmpty() else "")
                placeholders.set("$id.click-value", if (s.getBoolean("Click.Enable")) s.getString("Click.Value").orEmpty() else "")
                placeholders.set("$id.permission", "")
                moved++
            }
            return moved
        }

        private val SNOWFLAKE = Regex("^\\d{15,22}$")

        /**
         * DiscordSRV `accounts.aof` — 한 줄에 `디스코드id uuid`. 같은 사람이 다시 나오면 뒤의 것이 이긴다
         * (덧붙이기만 하는 파일이라 나중 줄이 최신이다). 모양이 다른 줄은 건너뛴다.
         */
        fun parseAccounts(lines: List<String>): Map<UUID, Long> {
            val byUuid = LinkedHashMap<UUID, Long>()
            for (line in lines) {
                val parts = line.trim().split(Regex("\\s+"))
                if (parts.size != 2 || !SNOWFLAKE.matches(parts[0])) continue
                val uuid = runCatching { UUID.fromString(parts[1]) }.getOrNull() ?: continue
                val id = parts[0].toLong()
                byUuid.entries.removeIf { it.value == id }
                byUuid[uuid] = id
            }
            return byUuid
        }
    }
}
