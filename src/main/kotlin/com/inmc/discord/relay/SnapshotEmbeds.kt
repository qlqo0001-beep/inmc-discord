package com.inmc.discord.relay

import com.inmc.discord.Discord
import com.inmc.discord.chat.Snapshot
import com.inmc.discord.chat.SnapshotKind
import com.inmc.discord.render.Renderer
import com.inmc.discord.util.Ph
import net.dv8tion.jda.api.EmbedBuilder
import net.dv8tion.jda.api.entities.MessageEmbed

/**
 * [item]·[inv]·[ender] 의 디스코드 임베드 — 그림이 있으면 그림(`attachment://`), 없으면 글(툴팁 줄 · 가방 목록).
 * 색은 InteractiveChat 디스코드 애드온과 같게(가방 #55FFFF, 엔더 #FF55FF).
 */
class SnapshotEmbeds(private val discord: Discord) {

    fun title(snapshot: Snapshot): String {
        val ph = Ph.of().player(snapshot.ownerName)
        return when (snapshot.kind) {
            SnapshotKind.ITEM -> snapshot.items.firstOrNull()?.takeUnless { it.isEmpty }
                ?.let { stack -> discord.lang.plain(stack.effectiveName()) + if (stack.amount > 1) " x${stack.amount}" else "" }
                ?: discord.messages.discord("discord-item-title", ph)
            SnapshotKind.INVENTORY -> discord.messages.discord("discord-inventory-title", ph)
            SnapshotKind.ENDER -> discord.messages.discord("discord-ender-title", ph)
        }
    }

    fun color(snapshot: Snapshot): Int? = when (snapshot.kind) {
        SnapshotKind.ITEM -> null
        SnapshotKind.INVENTORY -> 0x55FFFF
        SnapshotKind.ENDER -> 0xFF55FF
    }

    fun image(image: Renderer.Image): MessageEmbed =
        EmbedBuilder().setTitle(title(image.snapshot).take(MessageEmbed.TITLE_MAX_LENGTH))
            .setImage("attachment://${image.name}")
            .apply { color(image.snapshot)?.let(::setColor) }
            .build()

    /** 그림이 없을 때. */
    fun text(snapshot: Snapshot): MessageEmbed {
        val messages = discord.messages
        val lang = discord.lang
        val lines = when (snapshot.kind) {
            SnapshotKind.ITEM -> snapshot.tooltips.firstOrNull().orEmpty().drop(1).map(lang::plain)
            else -> {
                val all = snapshot.items.mapNotNull { stack ->
                    if (stack == null || stack.isEmpty) null else "${lang.plain(stack.effectiveName())} x${stack.amount}"
                }
                all.take(LINES) + if (all.size > LINES) listOf(messages.discord("discord-and-more", Ph.of().count(all.size - LINES))) else emptyList()
            }
        }.ifEmpty { listOf(messages.discord("discord-empty")) }
        return discord.bot.embed(title(snapshot), lines, color(snapshot))
    }

    private companion object {
        const val LINES = 30
    }
}
