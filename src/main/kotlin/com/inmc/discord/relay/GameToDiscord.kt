package com.inmc.discord.relay

import com.inmc.discord.Discord
import com.inmc.discord.bot.Bot
import com.inmc.discord.chat.Snapshot
import kr.inmc.core.util.Text
import net.dv8tion.jda.api.EmbedBuilder
import net.dv8tion.jda.api.entities.Message
import net.dv8tion.jda.api.entities.MessageEmbed
import net.kyori.adventure.text.Component
import net.dv8tion.jda.api.utils.FileUpload
import net.dv8tion.jda.api.utils.messages.MessageCreateBuilder
import org.bukkit.Bukkit
import org.bukkit.entity.Player
import java.util.concurrent.TimeUnit

/**
 * 게임 채팅 → 디스코드 채팅 채널. 채팅 스레드에서 **메시지 하나에 한 번** 불린다.
 *
 * 형식의 PAPI 는 [MESSAGE] 앞뒤 조각에만 돌린다 — 플레이어가 친 글은 PAPI 를 지나지 않는다(친 `%player_name%` 이 풀리면 안 된다).
 * [item]·[inv]·[ender] 가 있으면 그림 일꾼이 그린 PNG 를 붙여 보낸다(최대 [IMAGE_WAIT_SECONDS] 초 기다리고, 못 그리면 글 임베드).
 */
class GameToDiscord(private val discord: Discord) {

    private val embeds = SnapshotEmbeds(discord)

    fun relay(player: Player, message: String, snapshots: List<Snapshot>, hovers: List<List<Component>> = emptyList()) {
        val settings = discord.settings
        if (!settings.chat.toDiscord) return
        val channel = discord.bot.chatChannel() ?: return
        var body = Texts.filter(message, settings.chat.gameFilters) ?: return
        if (settings.chat.translateMentions) body = translateMentions(body, channel.guild)

        val values = mapOf(
            "username" to player.name,
            "displayname" to Texts.plain(player.displayName()),
            "world" to player.world.name,
        )
        fun part(raw: String) = if (raw.isEmpty()) "" else Texts.plainOf(Text.substituteOnly(Texts.tokens(raw, values), null, player))
        /** 형식의 `%message%` 자리에 본문 — PAPI 는 앞뒤 조각에만. */
        fun format(format: String): String {
            val at = format.indexOf(MESSAGE)
            if (at < 0) return part(format)
            return part(format.substring(0, at)) + body + part(format.substring(at + MESSAGE.length))
        }
        val hook = settings.chat.webhook
        // 봇으로 보낼 때는 이름이 든 형식, 웹훅일 때는 본문 형식(이름은 웹훅 이름으로).
        val botText = format(settings.chat.discordFormat)
        val hookText = if (hook.enabled) format(hook.messageFormat) else botText
        val mentions = Bot.mentionTypes(settings.chat.allowedMentions)
        // 웹훅이면 메시지 위 이름·얼굴(얼굴 주소의 {texture} 는 지금 스킨의 해시 — CMI 로 바꾼 스킨도 따라간다).
        val sender = if (hook.enabled) {
            val skin = com.inmc.discord.chat.ItemViews.skinOf(player).first
            Webhooks.username(part(hook.usernameFormat), player.name) to
                Webhooks.avatar(hook.avatarUrl, player.uniqueId, player.name, skin?.substringAfterLast('/'), hook.avatarSize)
        } else null

        if (snapshots.isEmpty() && hovers.isEmpty()) {
            deliver(channel, sender) { text -> discord.bot.message(if (text) hookText else botText, mentions) }
            return
        }
        val images = discord.renderer.images(snapshots).completeOnTimeout(emptyList(), IMAGE_WAIT_SECONDS, TimeUnit.SECONDS)
        val hoverImages = discord.renderer.hoverPngs(hovers).completeOnTimeout(emptyList(), IMAGE_WAIT_SECONDS, TimeUnit.SECONDS)
        images.thenCombine(hoverImages) { a, b -> a to b }.whenComplete { result, _ ->
            val drawn = result?.first.orEmpty()
            val hoverDrawn = result?.second.orEmpty()
            val files = ArrayList<Pair<String, ByteArray>>()
            val list = ArrayList<MessageEmbed>()
            if (drawn.size == snapshots.size) {
                for (image in drawn) {
                    files += image.name to image.png
                    list += embeds.image(image)
                }
            } else list += snapshots.map(embeds::text)
            hoverDrawn.forEachIndexed { i, png ->
                val name = "hover-$i.png"
                files += name to png
                list += EmbedBuilder().setImage("attachment://$name").build()
            }
            val rows = snapshots.flatMap(discord.interactions::rows).take(MAX_ROWS)
            // 첨부는 한 번 보내면 닫힌다 — 웹훅이 실패해 봇으로 다시 보낼 때를 위해 매번 새로 만든다.
            deliver(channel, sender) { viaHook ->
                MessageCreateBuilder()
                    .setContent((if (viaHook) hookText else botText).take(Message.MAX_CONTENT_LENGTH))
                    .setAllowedMentions(mentions)
                    .setFiles(files.map { (name, png) -> FileUpload.fromData(png, name) })
                    .setEmbeds(list.take(Message.MAX_EMBED_COUNT))
                    .setComponents(rows)
                    .build()
            }
        }
    }

    /**
     * 웹훅이 켜져 있으면 그 사람 이름·얼굴로, 실패하거나 꺼져 있으면 봇으로. [data] 는 보낼 때마다 새로 만든다
     * (인자 = 웹훅으로 보내는가 — 본문 형식이 다르다).
     */
    private fun deliver(
        channel: net.dv8tion.jda.api.entities.channel.concrete.TextChannel,
        sender: Pair<String, String>?,
        data: (Boolean) -> net.dv8tion.jda.api.utils.messages.MessageCreateData?,
    ) {
        fun viaBot() = data(false)?.let { channel.sendMessage(it).queue(null) { e -> discord.logger.warning("디스코드로 보내지 못했습니다: ${e.message}") } }
        if (sender == null) return run { viaBot() }
        val first = data(true) ?: return
        discord.webhooks.send(channel, first, sender.first, sender.second).thenAccept { sent -> if (!sent) viaBot() }
    }

    /**
     * `@이름` → 디스코드 멘션. 접속 중이고 연결된 플레이어의 마인크래프트 이름, 디스코드 멤버(별명·이름), 역할, `#채널`.
     * 멤버는 멤버 인텐트가 있을 때만 캐시에 있다.
     */
    private fun translateMentions(text: String, guild: net.dv8tion.jda.api.entities.Guild): String {
        if (text.indexOf('@') < 0 && text.indexOf('#') < 0) return text
        val users = ArrayList<Pair<String, String>>()
        for (online in Bukkit.getOnlinePlayers()) {
            discord.links.discordOf(online.uniqueId)?.let { users += online.name to "<@$it>" }
        }
        guild.memberCache.forEach { member ->
            users += member.effectiveName to member.asMention
            users += member.user.name to member.asMention
        }
        for (role in guild.roles) if (!role.isPublicRole) users += role.name to role.asMention
        val channels = guild.textChannels.map { it.name to it.asMention }
        return Texts.mentionsToDiscord(Texts.mentionsToDiscord(text, users), channels, '#')
    }

    private companion object {
        const val MESSAGE = "%message%"
        const val IMAGE_WAIT_SECONDS = 10L
        /** 디스코드 메시지 하나의 컴포넌트 줄 한도. */
        const val MAX_ROWS = 5
    }
}
