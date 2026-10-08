package com.inmc.discord.console

import com.inmc.discord.Discord
import com.inmc.discord.util.Ph
import net.dv8tion.jda.api.events.message.MessageReceivedEvent
import org.bukkit.Bukkit

/**
 * 디스코드에서 콘솔 명령어 — 콘솔 채널에 친 글, 채팅 채널의 `!c 명령어`.
 *
 * 누가 콘솔 채널에 쓸 수 있는지는 디스코드 채널 권한이 정한다(DiscordSRV 와 같다). `!c` 는 역할과 허용 목록을 본다.
 * 실행은 메인 스레드에서 콘솔로. 결과는 콘솔 채널로 그대로 흘러간다.
 */
class ConsoleCommands(private val discord: Discord) {

    /** 콘솔 채널의 메시지. */
    fun fromConsoleChannel(event: MessageReceivedEvent) {
        val settings = discord.settings.console
        if (event.author.isBot && settings.blockBots) return
        val command = event.message.contentRaw.trim().removePrefix("/").trim()
        if (command.isEmpty()) return
        val root = root(command)
        if (root in settings.blockedCommands) {
            discord.bot.send(event.channel.asTextChannel(), discord.messages.discord("discord-command-blocked", Ph.of().command(root)), emptySet())
            return
        }
        run(event, command)
    }

    /** 채팅 채널의 메시지가 `!c` 로 시작하면 여기서 끝낸다(게임으로 보내지 않는다). */
    fun fromChatChannel(event: MessageReceivedEvent): Boolean {
        val settings = discord.settings.consoleCommand
        if (!settings.enabled) return false
        val content = event.message.contentRaw.trim()
        if (!content.startsWith(settings.prefix + " ") && content != settings.prefix) return false
        val command = content.removePrefix(settings.prefix).trim().removePrefix("/").trim()
        if (command.isEmpty()) return true
        val member = event.member
        val roles = member?.roles?.map { it.name }.orEmpty()
        val error = when {
            member == null || !hasAny(roles, settings.roles) -> discord.messages.discord("discord-command-no-role")
            !hasAny(roles, settings.bypassRoles) && root(command) !in settings.whitelist ->
                discord.messages.discord("discord-command-not-allowed", Ph.of().command(root(command)))
            else -> null
        }
        if (error != null) {
            if (settings.notifyErrors) {
                discord.bot.send(
                    event.channel.asTextChannel(),
                    discord.messages.discord("discord-command-error", Ph.of().user(member?.asMention ?: event.author.asMention).error(error)),
                )
            }
            return true
        }
        run(event, command)
        return true
    }

    private fun run(event: MessageReceivedEvent, command: String) {
        val who = "${event.author.name} (${event.author.id})"
        val settings = discord.settings
        discord.bot.scheduler.execute {
            ConsoleRelay.usage(discord.plugin.dataFolder, settings.console.usageLog, settings.time.zone, who, command)
        }
        discord.logger.info("디스코드 $who 가 콘솔 명령어를 실행합니다: $command")
        discord.runMain {
            runCatching { Bukkit.dispatchCommand(Bukkit.getConsoleSender(), command) }
                .onFailure { discord.logger.warning("디스코드 명령어 실행 실패 ($command): ${it.message}") }
        }
    }

    companion object {

        /** `minecraft:op x` → `op`. 소문자. */
        fun root(command: String): String =
            command.trim().removePrefix("/").substringBefore(' ').substringAfter(':').lowercase()

        private fun hasAny(roles: List<String>, wanted: Set<String>): Boolean =
            wanted.isNotEmpty() && roles.any { r -> wanted.any { it.equals(r, ignoreCase = true) } }
    }
}
