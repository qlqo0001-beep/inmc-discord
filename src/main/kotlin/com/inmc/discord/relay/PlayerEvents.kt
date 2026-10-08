package com.inmc.discord.relay

import com.inmc.discord.Discord
import com.inmc.discord.chat.ItemViews
import com.inmc.discord.config.Settings
import kr.inmc.core.util.Text
import net.dv8tion.jda.api.EmbedBuilder
import net.dv8tion.jda.api.entities.MessageEmbed
import net.kyori.adventure.text.Component
import org.bukkit.entity.Player
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.Listener
import org.bukkit.event.entity.PlayerDeathEvent
import org.bukkit.event.player.PlayerJoinEvent
import org.bukkit.event.player.PlayerQuitEvent

/**
 * 접속·처음 접속·퇴장·사망 → 디스코드 채팅 채널(DiscordSRV 의 MinecraftPlayer*Message 처럼 색 띠 + 얼굴 + 한 줄 임베드).
 *
 * 사건 하나에 한 번, 메인 스레드. PAPI 는 형식에 `%` 가 있을 때만 형식에만 돌리고, 사망 문구처럼 **사람이 정한 글이 섞인 값**은
 * 그 뒤에 끼운다(이름 바꾼 무기의 `%…%`·`<red>` 가 풀리지 않게). 숨은(vanish) 플레이어는 보내지 않는다 — CMI 등이 `vanished` 메타데이터를 단다.
 */
class PlayerEvents(private val discord: Discord) : Listener {

    @EventHandler(priority = EventPriority.MONITOR)
    fun onJoin(event: PlayerJoinEvent) {
        val events = discord.settings.playerEvents
        val first = !event.player.hasPlayedBefore() && events.firstJoin.text.isNotBlank()
        send(event.player, if (first) events.firstJoin else events.join, event.joinMessage())
    }

    @EventHandler(priority = EventPriority.MONITOR)
    fun onQuit(event: PlayerQuitEvent) {
        send(event.player, discord.settings.playerEvents.leave, event.quitMessage())
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun onDeath(event: PlayerDeathEvent) {
        val message = event.deathMessage() ?: return
        send(event.player, discord.settings.playerEvents.death, message)
    }

    /**
     * 관리자 시험(`/디스코드 관리 시험`) — 사건 없이 [player] 의 알림 하나를 보낸다. 숨어 있어도 보낸다(시험이다).
     * @return 보냈으면 null, 못 보냈으면 까닭
     */
    fun test(player: Player, kind: String): String? {
        val events = discord.settings.playerEvents
        val (format, message) = when (kind) {
            "접속" -> events.join to Component.translatable("multiplayer.player.joined", player.displayName())
            "처음접속" -> events.firstJoin to Component.translatable("multiplayer.player.joined", player.displayName())
            "퇴장" -> events.leave to Component.translatable("multiplayer.player.left", player.displayName())
            "사망" -> events.death to Component.translatable("death.attack.generic", player.displayName())
            else -> return "알 수 없는 알림 '$kind' (접속 · 처음접속 · 퇴장 · 사망)"
        }
        if (format.text.isBlank()) return "player-events 의 그 글이 비어 있어 꺼져 있음"
        if (discord.bot.chatChannel() == null) return "채팅 채널을 찾지 못함"
        send(player, format, message, test = true)
        return null
    }

    private fun send(player: Player, format: Settings.PlayerEvent, message: Component?, test: Boolean = false) {
        if (format.text.isBlank() || (!test && vanished(player))) return
        val channel = discord.bot.chatChannel() ?: return
        val shown = message?.let(discord.lang::plain).orEmpty()
        val values = mapOf(
            "displayname" to Texts.plain(player.displayName()),
            "username" to player.name,
            "world" to player.world.name,
            "message" to shown,
            "deathmessage" to shown,
        )
        val template = Texts.plainOf(if (format.text.indexOf('%') >= 0) Text.substituteOnly(format.text, null, player) else format.text)
        val text = Texts.tokens(template, values).trim().takeIf { it.isNotEmpty() } ?: return
        val hook = discord.settings.chat.webhook
        val face = Webhooks.avatar(hook.avatarUrl, player.uniqueId, player.name, ItemViews.skinOf(player).first?.substringAfterLast('/'), hook.avatarSize)
        val embed = EmbedBuilder()
            .setAuthor(text.take(MessageEmbed.AUTHOR_MAX_LENGTH), null, face)
            .apply { format.color?.let(::setColor) }
            .build()
        discord.bot.send(channel, "", embeds = listOf(embed))
    }

    private fun vanished(player: Player): Boolean = player.getMetadata("vanished").any { it.asBoolean() }
}
