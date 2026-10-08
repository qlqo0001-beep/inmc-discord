package com.inmc.discord.link

import com.inmc.discord.Discord
import com.inmc.discord.bot.Bot
import com.inmc.discord.util.Ph
import kr.inmc.core.util.Text
import net.dv8tion.jda.api.entities.User
import net.dv8tion.jda.api.entities.UserSnowflake
import net.dv8tion.jda.api.events.interaction.command.SlashCommandInteractionEvent
import net.dv8tion.jda.api.events.message.MessageReceivedEvent
import org.bukkit.Bukkit
import org.bukkit.entity.Player
import java.util.UUID

/**
 * 계정 연결. 게임에서 코드를 받고(`/디스코드 연결`) 봇에게 DM 으로 보내거나 디스코드 `/연결 코드` 로 쓴다.
 *
 * 디스코드 쪽(JDA 스레드)에서 코드를 확인하고, 저장·게임 메시지·명령어는 메인에서. 연결하면 `link.role` 역할을 준다.
 */
class LinkService(private val discord: Discord) {

    /** 메인 스레드 — `/디스코드 연결`. */
    fun start(player: Player) {
        val messages = discord.messages
        if (discord.links.discordOf(player.uniqueId) != null) {
            messages.send(player, "already-linked")
            return
        }
        val jda = discord.bot.jda ?: return messages.send(player, "bot-offline")
        val minutes = discord.settings.link.codeMinutes
        val code = discord.codes.issue(player.uniqueId, minutes)
        messages.send(player, "code-generated", Ph.of().code(code).bot(jda.selfUser.name).count(minutes))
    }

    /** 메인 스레드 — `/디스코드 연결해제`. */
    fun unlink(player: Player) {
        val id = discord.links.unlink(player.uniqueId) ?: return discord.messages.send(player, "not-linked")
        val name = discord.bot.jda?.getUserById(id)?.name ?: id.toString()
        discord.messages.send(player, "unlinked", Ph.of().name(name))
        role(id, add = false)
        commands(discord.settings.link.unlinkedCommands, player.name, player.uniqueId, id)
    }

    /** 메인 스레드 — 연결 상태 한 줄. */
    fun status(player: Player) {
        val id = discord.links.discordOf(player.uniqueId) ?: return discord.messages.send(player, "not-linked")
        val name = discord.bot.jda?.getUserById(id)?.name ?: id.toString()
        discord.messages.send(player, "linked-status", Ph.of().name(name))
    }

    /** JDA 스레드 — 봇에게 온 DM. */
    fun onDirectMessage(event: MessageReceivedEvent) {
        if (event.author.isBot) return
        val reply = claim(event.author, event.message.contentRaw)
        event.channel.sendMessage(reply).queue(null) { }
    }

    /** JDA 스레드 — 디스코드 `/연결 코드`. 본인에게만 보이게 답한다. */
    fun onSlash(event: SlashCommandInteractionEvent) {
        if (event.name != Bot.SLASH_LINK) return
        val code = event.getOption(Bot.SLASH_LINK_CODE)?.asString.orEmpty()
        event.reply(claim(event.user, code)).setEphemeral(true).queue(null) { }
    }

    /** 코드를 확인하고 답할 글을 돌려준다. 맞으면 저장은 메인에 넘긴다. */
    private fun claim(user: User, text: String): String {
        val messages = discord.messages
        return when (val result = discord.codes.claim(text)) {
            LinkCodes.Claim.Invalid -> messages.discord("discord-invalid-code")
            LinkCodes.Claim.Unknown -> messages.discord("discord-unknown-code")
            is LinkCodes.Claim.Ok -> {
                val existing = discord.links.playerOf(user.idLong)
                if (existing != null && !discord.settings.link.allowRelink) {
                    return messages.discord("discord-already-linked", Ph.of().name(nameOf(existing)).uuid(existing.toString()))
                }
                val uuid = result.player
                discord.runMain { complete(uuid, user.idLong, user.name) }
                messages.discord("discord-linked", Ph.of().name(nameOf(uuid)).uuid(uuid.toString()))
            }
        }
    }

    private fun complete(uuid: UUID, id: Long, userName: String) {
        discord.links.link(uuid, id)
        val player = Bukkit.getPlayer(uuid)
        player?.let { discord.messages.send(it, "linked-now", Ph.of().user(userName).id(id.toString())) }
        role(id, add = true)
        commands(discord.settings.link.linkedCommands, nameOf(uuid), uuid, id)
        discord.nicknames.sync(uuid, nameOf(uuid))
        com.inmc.discord.Signals.link(uuid, com.inmc.discord.Signals.LINK_NEW, player)
        discord.logger.info("${nameOf(uuid)} 을(를) 디스코드 $userName ($id) 에 연결했습니다")
    }

    private fun role(id: Long, add: Boolean) {
        val name = discord.settings.link.role
        if (name.isBlank()) return
        val guild = discord.bot.guild() ?: return
        val role = guild.getRolesByName(name, true).firstOrNull() ?: run {
            discord.logger.warning("연결 역할 '$name' 을 디스코드 서버에서 찾지 못했습니다")
            return
        }
        val action = if (add) guild.addRoleToMember(UserSnowflake.fromId(id), role) else guild.removeRoleFromMember(UserSnowflake.fromId(id), role)
        action.queue(null) { discord.logger.warning("연결 역할을 ${if (add) "주지" else "빼지"} 못했습니다 — 봇 역할이 '$name' 보다 위에 있는지 확인하세요: ${it.message}") }
    }

    private fun commands(list: List<String>, name: String, uuid: UUID, id: Long) {
        for (raw in list) {
            val command = Text.substituteOnly(raw.replace("{player}", name).replace("{uuid}", uuid.toString()).replace("{discord}", id.toString()))
            runCatching { Bukkit.dispatchCommand(Bukkit.getConsoleSender(), command.removePrefix("/")) }
                .onFailure { discord.logger.warning("연결 명령어 실행 실패 ($command): ${it.message}") }
        }
    }

    private fun nameOf(uuid: UUID): String = Bukkit.getOfflinePlayer(uuid).name ?: uuid.toString()
}
