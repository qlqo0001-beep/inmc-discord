package com.inmc.discord.relay

import com.inmc.discord.Discord
import com.inmc.discord.console.ConsoleCommands
import com.inmc.discord.util.Ph
import kr.inmc.core.util.Text
import net.dv8tion.jda.api.entities.Message
import net.dv8tion.jda.api.entities.Role
import net.dv8tion.jda.api.events.message.MessageReceivedEvent
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.TextReplacementConfig
import net.kyori.adventure.text.event.ClickEvent
import net.kyori.adventure.text.event.HoverEvent
import net.kyori.adventure.text.minimessage.MiniMessage
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer
import org.bukkit.Bukkit
import org.bukkit.entity.Player
import java.util.UUID
import java.util.concurrent.TimeUnit

/**
 * 디스코드에서 온 메시지 전부의 입구(JDA 스레드). 어디서 왔느냐로 나눈다 —
 * DM = 연결 코드, 콘솔 채널 = 콘솔 명령어, 채팅 채널 = `!c` · 접속목록 · 게임으로 중계.
 *
 * 게임 상태에 닿는 일(방송·명령 실행·알림)은 전부 메인으로 넘긴다.
 */
class DiscordToGame(private val discord: Discord) {

    private val commands = ConsoleCommands(discord)

    fun onMessage(event: MessageReceivedEvent) {
        if (event.author.idLong == event.jda.selfUser.idLong) return
        if (!event.isFromGuild) {
            discord.linking.onDirectMessage(event)
            return
        }
        val settings = discord.settings
        val channel = event.channel.idLong
        if (channel == settings.consoleChannel) {
            commands.fromConsoleChannel(event)
            return
        }
        if (channel != settings.chatChannel) return
        val chat = settings.chat
        if (event.isWebhookMessage && (chat.blockWebhooks || discord.webhooks.isOurs(event.author.idLong))) return
        if (event.author.isBot && chat.blockBots) return
        if (commands.fromChatChannel(event)) return
        if (listCommand(event)) return
        if (chat.toGame) relay(event)
    }

    private fun relay(event: MessageReceivedEvent) {
        val chat = discord.settings.chat
        val message = event.message
        val member = event.member
        val attachments = message.attachments
        val filtered = Texts.filter(message.contentDisplay, chat.discordFilters)
        if (filtered == null && attachments.isEmpty()) return
        val text = Texts.truncate(filtered.orEmpty(), chat.truncate)

        val roles = member?.roles.orEmpty().filter { it.name !in chat.hiddenRoles }
        val top = roles.firstOrNull()
        fun alias(role: Role) = chat.roleAliases[role.name] ?: role.name
        val colored = member != null && member.roles.any { r -> chat.colorRoles.any { it.equals(r.name, ignoreCase = true) } }

        var body: Component = if (colored) LEGACY.deserialize(text) else Component.text(text)
        // 디스코드에서 멘션된 사람 가운데 연결된 접속자 — 강조하고 알린다.
        val mentioned = linkedOnline(message)
        if (mentioned.isNotEmpty()) body = highlight(body, mentioned.map { it.second })
        for (attachment in attachments) body = body.append(Component.space()).append(attachment(attachment))

        val reply = message.referencedMessage?.let { ref ->
            Texts.tokens(chat.replyFormat, mapOf("name" to (ref.member?.effectiveName ?: ref.author.effectiveName)))
        }.orEmpty()
        val line = render(
            format = if (top == null) chat.gameFormatNoRole else chat.gameFormat,
            name = member?.effectiveName ?: event.author.effectiveName,
            role = top?.let(::alias).orEmpty(),
            roleColor = top?.let(::roleColor).orEmpty(),
            allRoles = roles.joinToString(chat.allRolesSeparator) { alias(it) },
            reply = reply,
            message = body,
        )
        val userName = member?.effectiveName ?: event.author.effectiveName
        val channelName = event.channel.name
        val author = discord.links.playerOf(event.author.idLong)
        discord.runMain {
            for (player in Bukkit.getOnlinePlayers()) player.sendMessage(line)
            if (discord.settings.chat.toConsole) Bukkit.getConsoleSender().sendMessage(line)
            for ((uuid, _) in mentioned) Bukkit.getPlayer(uuid)?.let { discord.alerts.fromDiscord(it, userName, channelName) }
            author?.let(com.inmc.discord.Signals::chat)
        }
    }

    /** 멘션된 디스코드 사용자 → (연결된 접속자 uuid, 게임에 보이는 "@별명"). */
    private fun linkedOnline(message: Message): List<Pair<UUID, String>> =
        message.mentions.users.mapNotNull { user ->
            val uuid = discord.links.playerOf(user.idLong) ?: return@mapNotNull null
            if (Bukkit.getPlayer(uuid) == null) return@mapNotNull null
            val shown = message.guild.getMember(user)?.effectiveName ?: user.effectiveName
            uuid to "@$shown"
        }

    private fun highlight(body: Component, texts: List<String>): Component {
        val format = discord.settings.mention.discordHighlight
        var out = body
        for (text in texts.distinct()) {
            out = out.replaceText(
                TextReplacementConfig.builder().matchLiteral(text)
                    .replacement { _ -> Texts.mini(format, "mentioned" to text) }
                    .build(),
            )
        }
        return out
    }

    /**
     * `[파일이름]` — 그림이면 누르면 게임에서 미리보기(`/디스코드 그림 <id>`), 아니면 원본 열기. 뒤에 `(원본)` 링크.
     */
    private fun attachment(attachment: Message.Attachment): Component {
        val messages = discord.messages
        val settings = discord.settings.attachments
        val image = settings.preview && attachment.size <= settings.maxBytes &&
            com.inmc.discord.preview.Attachments.isImage(attachment.fileName, attachment.contentType)
        val id = discord.attachments.put(attachment.url, attachment.fileName, attachment.size, image, settings.keepHours)
        val name = Texts.mini(messages.raw("attachment"), "name" to attachment.fileName)
        val link = Texts.mini(messages.raw("attachment-link"))
            .hoverEvent(HoverEvent.showText(Texts.mini(messages.raw("attachment-link-hover"))))
            .clickEvent(ClickEvent.openUrl(attachment.url))
        if (!image) return name.hoverEvent(HoverEvent.showText(Texts.mini(messages.raw("attachment-link-hover")))).clickEvent(ClickEvent.openUrl(attachment.url))
        return name.hoverEvent(HoverEvent.showText(Texts.mini(messages.raw("attachment-hover"))))
            .clickEvent(ClickEvent.runCommand("/디스코드 그림 $id"))
            .append(link)
    }

    /** 채팅 채널에 `접속목록` — 메인에서 이름을 모아 답하고, 정한 초 뒤 둘 다 지운다. */
    private fun listCommand(event: MessageReceivedEvent): Boolean {
        val settings = discord.settings.listCommand
        if (!settings.enabled || !event.message.contentRaw.trim().equals(settings.trigger, ignoreCase = true)) return false
        val channel = event.channel.asTextChannel()
        discord.runMain {
            val names = Bukkit.getOnlinePlayers().map { Texts.plain(it.displayName()) }.sortedBy { it.lowercase() }
            val messages = discord.messages
            val text = if (names.isEmpty()) {
                messages.discord("discord-list-empty")
            } else {
                messages.discord("discord-list-header", Ph.of().count(names.size)) + "\n```\n" +
                    Texts.escapeCodeBlock(names.joinToString(messages.discord("discord-list-separator"))) + "\n```"
            }
            val data = discord.bot.message(text) ?: return@runMain
            channel.sendMessage(data).queue({ reply ->
                if (settings.deleteAfter > 0) {
                    reply.delete().queueAfter(settings.deleteAfter.toLong(), TimeUnit.SECONDS, null) { }
                    event.message.delete().queueAfter(settings.deleteAfter.toLong(), TimeUnit.SECONDS, null) { }
                }
            }) { discord.logger.warning("접속목록 답을 보내지 못했습니다: ${it.message}") }
        }
        return true
    }

    private fun render(format: String, name: String, role: String, roleColor: String, allRoles: String, reply: String, message: Component): Component {
        var f = format.replace("%toprolecolor%", roleColor)
        for ((token, tag) in TAGS) f = f.replace(token, tag)
        return MiniMessage.miniMessage().deserialize(
            Text.legacyToMiniMessage(f),
            Placeholder.unparsed("ds_name", name),
            Placeholder.unparsed("ds_role", role),
            Placeholder.unparsed("ds_initial", role.take(1)),
            Placeholder.unparsed("ds_roles", allRoles),
            Placeholder.unparsed("ds_reply", reply),
            Placeholder.component("ds_message", message),
        )
    }

    private companion object {

        /** 디스코드 사람이 친 &색(색 역할만). hex 도. */
        val LEGACY: LegacyComponentSerializer = LegacyComponentSerializer.builder().character('&').hexColors().build()

        val TAGS = listOf(
            "%name%" to "<ds_name>",
            "%toprole%" to "<ds_role>",
            "%toproleinitial%" to "<ds_initial>",
            "%allroles%" to "<ds_roles>",
            "%reply%" to "<ds_reply>",
            "%message%" to "<ds_message>",
        )

        /** 역할 색 → `&#rrggbb`. 색이 없는 역할은 빈칸. */
        @Suppress("DEPRECATION")
        fun roleColor(role: Role): String {
            val raw = role.colorRaw
            return if (raw == Role.DEFAULT_COLOR_RAW) "" else String.format("&#%06x", raw and 0xFFFFFF)
        }
    }
}
