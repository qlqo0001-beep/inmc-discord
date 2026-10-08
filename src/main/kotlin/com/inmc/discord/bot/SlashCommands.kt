package com.inmc.discord.bot

import com.inmc.discord.Discord
import com.inmc.discord.chat.ItemViews
import com.inmc.discord.chat.KeywordText
import com.inmc.discord.chat.Snapshot
import com.inmc.discord.chat.SnapshotKind
import com.inmc.discord.relay.SnapshotEmbeds
import com.inmc.discord.relay.Texts
import com.inmc.discord.util.Ph
import kr.inmc.core.integration.TitleForgeNames
import kr.inmc.core.util.Text
import net.dv8tion.jda.api.EmbedBuilder
import net.dv8tion.jda.api.events.interaction.command.SlashCommandInteractionEvent
import net.dv8tion.jda.api.interactions.InteractionHook
import net.dv8tion.jda.api.interactions.commands.OptionType
import net.dv8tion.jda.api.interactions.commands.build.Commands
import net.dv8tion.jda.api.interactions.commands.build.OptionData
import net.dv8tion.jda.api.interactions.commands.build.SlashCommandData
import net.dv8tion.jda.api.utils.FileUpload
import net.kyori.adventure.text.Component
import org.bukkit.Bukkit
import org.bukkit.OfflinePlayer
import org.bukkit.entity.Player
import java.util.concurrent.CompletableFuture

/**
 * 디스코드 슬래시 명령어(InteractiveChat 디스코드 애드온의 DiscordCommands).
 * - `/접속목록` — 탭리스트 그림
 * - `/정보 [플레이어]` — 자리표시 줄 + 머리 그림(비우면 내 연결 계정)
 * - `/아이템 [칸] [멤버]` · `/인벤 [멤버]` · `/엔더 [멤버]` — 연결된 계정의 것을 디스코드와 게임 채팅에 같이(멤버는 `slash.share.others-roles` 역할만)
 *
 * 게임 상태는 메인에서 모으고(PAPI·스냅샷은 명령어 한 번에 한 번), 그림은 그림 일꾼에서.
 */
class SlashCommands(private val discord: Discord) {

    private val embeds = SnapshotEmbeds(discord)

    fun definitions(): List<SlashCommandData> {
        val slash = discord.settings.slash
        val out = ArrayList<SlashCommandData>()
        if (slash.playerList) out += Commands.slash(LIST, "접속 중인 플레이어를 탭리스트 그림으로")
        if (slash.playerInfo) out += Commands.slash(INFO, "플레이어 정보").addOption(OptionType.STRING, OPT_PLAYER, "마인크래프트 이름 또는 닉네임 (비우면 내 연결 계정)", false)
        if (slash.share) {
            val member = OptionData(OptionType.USER, OPT_MEMBER, "다른 사람의 것(관리 역할만)", false)
            out += Commands.slash(ITEM, "손에 든 아이템을 공유").addOptions(
                OptionData(OptionType.INTEGER, OPT_SLOT, "단축바 칸 1~9 (비우면 손에 든 것)", false).setRequiredRange(1, 9), member,
            )
            out += Commands.slash(INVENTORY, "가방을 공유").addOptions(member)
            out += Commands.slash(ENDER, "엔더 상자를 공유").addOptions(member)
        }
        return out
    }

    /** @return 우리 것이었으면 true */
    fun handle(event: SlashCommandInteractionEvent): Boolean {
        when (event.name) {
            LIST -> list(event)
            INFO -> info(event)
            ITEM -> share(event, SnapshotKind.ITEM)
            INVENTORY -> share(event, SnapshotKind.INVENTORY)
            ENDER -> share(event, SnapshotKind.ENDER)
            else -> return false
        }
        return true
    }

    // --- /접속목록 -----------------------------------------------------------------------

    private fun list(event: SlashCommandInteractionEvent) {
        event.deferReply().queue({ hook ->
            onMain {
                val slash = discord.settings.slash
                val players = sorted(Bukkit.getOnlinePlayers().toList(), slash.listOrder).take(slash.listMax)
                val online = Bukkit.getOnlinePlayers().size.toString()
                val max = Bukkit.getMaxPlayers().toString()
                fun banner(raw: String) = Text.render(raw.replace("{online}", online).replace("{max}", max))
                val entries = players.map { p ->
                    Triple(Text.render(slash.listFormat.replace("{player}", p.name), null, p), ItemViews.skinOf(p).first, p.ping)
                }
                Triple(entries, slash.listHeader.map(::banner), slash.listFooter.map(::banner))
            }.thenCompose { (entries, header, footer) ->
                if (entries.isEmpty()) CompletableFuture.completedFuture(null) else discord.renderer.tablistPng(entries, header, footer)
            }.whenComplete { png, _ ->
                if (png == null) hook.sendMessage(discord.messages.discord("discord-list-empty")).queue(null) { }
                else hook.sendFiles(FileUpload.fromData(png, "players.png")).queue(null) { }
            }
        }) { }
    }

    /** `order-by` 규칙 — 앞의 것부터. 자리표시 값은 수면 수로, 아니면 글로 견준다. */
    private fun sorted(players: List<Player>, rules: List<String>): List<Player> {
        val keys = players.associateWith { p ->
            rules.map { rule ->
                when {
                    rule.startsWith("PLACEHOLDER_REVERSE:") -> Key(Text.substituteOnly(rule.substringAfter(':'), null, p), reverse = true)
                    rule.startsWith("PLACEHOLDER:") -> Key(Text.substituteOnly(rule.substringAfter(':'), null, p), reverse = false)
                    else -> Key(p.name.lowercase(), reverse = false)
                }
            }
        }
        return players.sortedWith { a, b ->
            val ka = keys.getValue(a)
            val kb = keys.getValue(b)
            for (i in ka.indices) {
                val c = ka[i].compareTo(kb[i])
                if (c != 0) return@sortedWith c
            }
            0
        }
    }

    private class Key(val value: String, val reverse: Boolean) : Comparable<Key> {
        override fun compareTo(other: Key): Int {
            val a = value.replace(",", "").toDoubleOrNull()
            val b = other.value.replace(",", "").toDoubleOrNull()
            val c = if (a != null && b != null) a.compareTo(b) else value.compareTo(other.value)
            return if (reverse) -c else c
        }
    }

    // --- /정보 ---------------------------------------------------------------------------

    private fun info(event: SlashCommandInteractionEvent) {
        val name = event.getOption(OPT_PLAYER)?.asString
        val self = discord.links.playerOf(event.user.idLong)
        if (name == null && self == null) return replyEphemeral(event, discord.messages.discord("discord-not-linked"))
        event.deferReply().queue({ hook ->
            onMain {
                val target: OfflinePlayer? = if (name != null) resolve(name.trim()) else self?.let(Bukkit::getOfflinePlayer)
                if (target == null || (!target.hasPlayedBefore() && !target.isOnline)) return@onMain null
                val online = target.player
                val slash = discord.settings.slash
                val playerName = target.name ?: name ?: "?"
                val linked = discord.links.discordOf(target.uniqueId)?.let { id -> discord.bot.jda?.getUserById(id)?.name ?: id.toString() }
                fun line(raw: String) = Texts.plainOf(Text.substituteOnly(raw.replace("{player}", playerName).replace("{discord}", linked ?: "-"), null, online))
                val lines = (if (online != null) slash.infoOnline else slash.infoOffline).map(::line)
                val skin = online?.let { ItemViews.skinOf(it).first } ?: runCatching { target.playerProfile.textures.skin?.toString() }.getOrNull()
                Info(line(slash.infoTitle), lines, linked, skin, target.uniqueId.toString())
            }.thenCompose { info ->
                if (info == null) CompletableFuture.completedFuture(null to null)
                else discord.renderer.headPng(info.skin, info.owner).thenApply { png -> info to png }
            }.whenComplete { result, _ ->
                val (info, png) = result ?: (null to null)
                if (info == null) {
                    hook.sendMessage(discord.messages.discord("discord-unknown-player")).queue(null) { }
                    return@whenComplete
                }
                val embed = EmbedBuilder().setTitle(info.title.take(256)).setDescription(info.lines.joinToString("\n").take(4000))
                info.linked?.let { embed.setFooter(discord.messages.discord("discord-info-linked", Ph.of().user(it))) }
                if (png != null) {
                    embed.setThumbnail("attachment://head.png")
                    hook.sendFiles(FileUpload.fromData(png, "head.png")).setEmbeds(embed.build()).queue(null) { }
                } else hook.sendMessageEmbeds(embed.build()).queue(null) { }
            }
        }) { }
    }

    private class Info(val title: String, val lines: List<String>, val linked: String?, val skin: String?, val owner: String)

    /**
     * 메인 스레드. 마크 이름(접속자 → 서버에 들어온 적 있는 이름), 없으면 타이틀포지 닉네임(오프라인 포함). 웹 조회는 하지 않는다.
     * 닉네임은 타이틀포지의 비교 규칙(공백·전각·대소문자)을 그대로 쓰려고 `TitleForgePlugin.uuidOfNickname` 을 리플렉션으로 부른다.
     */
    private fun resolve(name: String): OfflinePlayer? {
        if (name.isEmpty()) return null
        Bukkit.getPlayerExact(name)?.let { return it }
        Bukkit.getOfflinePlayerIfCached(name)?.let { return it }
        val titleForge = Bukkit.getPluginManager().getPlugin("InMc-TitleForge") ?: return null
        val uuid = runCatching { titleForge.javaClass.getMethod("uuidOfNickname", String::class.java).invoke(titleForge, name) as? java.util.UUID }
            .getOrNull() ?: return null
        return Bukkit.getOfflinePlayer(uuid)
    }

    // --- /아이템 · /인벤 · /엔더 ------------------------------------------------------------

    private fun share(event: SlashCommandInteractionEvent, kind: SnapshotKind) {
        val other = event.getOption(OPT_MEMBER)?.asUser
        if (other != null && other.idLong != event.user.idLong) {
            val roles = discord.settings.slash.shareOthersRoles
            val allowed = event.member?.roles?.any { r -> roles.any { it.equals(r.name, ignoreCase = true) } } == true
            if (!allowed) return replyEphemeral(event, discord.messages.discord("discord-no-permission"))
        }
        val uuid = discord.links.playerOf((other ?: event.user).idLong)
            ?: return replyEphemeral(event, discord.messages.discord("discord-not-linked"))
        val slot = event.getOption(OPT_SLOT)?.asInt?.minus(1)
        event.deferReply().queue({ hook ->
            onMain {
                val player = Bukkit.getPlayer(uuid) ?: return@onMain null
                val snapshot = Snapshot.take(player, kind, slot)
                val id = discord.snapshots.put(snapshot, discord.settings.keywords.timeoutMinutes)
                announce(player, kind, snapshot, id)
                // 업적은 자기 것을 공유했을 때만 — 관리 역할이 남의 것을 보인 것은 그 사람이 한 일이 아니다.
                if (other == null || other.idLong == event.user.idLong) com.inmc.discord.Signals.share(player, kind)
                snapshot
            }.whenComplete { snapshot, _ ->
                if (snapshot == null) {
                    val name = Bukkit.getOfflinePlayer(uuid).name ?: uuid.toString()
                    hook.sendMessage(discord.messages.discord("discord-player-offline", Ph.of().name(name))).queue(null) { }
                    return@whenComplete
                }
                post(hook, snapshot)
            }
        }) { }
    }

    /** 게임 채팅에 "누가 디스코드에서 공유했다" + [item] 같은 글(누르면 보기 화면). */
    private fun announce(player: Player, kind: SnapshotKind, snapshot: Snapshot, id: String) {
        val keywords = discord.settings.keywords
        val keyword = when (kind) {
            SnapshotKind.ITEM -> keywords.item
            SnapshotKind.INVENTORY -> keywords.inventory
            SnapshotKind.ENDER -> keywords.ender
        }
        val key = when (kind) {
            SnapshotKind.ITEM -> "share-item"
            SnapshotKind.INVENTORY -> "share-inventory"
            SnapshotKind.ENDER -> "share-ender"
        }
        val line = Texts.mini(
            discord.messages.raw(key),
            "player" to TitleForgeNames.displayName(player.uniqueId, player.name),
            "item" to KeywordText.component(kind, keyword, snapshot, id),
        )
        for (p in Bukkit.getOnlinePlayers()) p.sendMessage(line)
        Bukkit.getConsoleSender().sendMessage(line)
    }

    private fun post(hook: InteractionHook, snapshot: Snapshot) {
        discord.renderer.images(listOf(snapshot)).whenComplete { images, _ ->
            val image = images?.firstOrNull()
            val rows = discord.interactions.rows(snapshot)
            if (image == null) {
                hook.sendMessageEmbeds(embeds.text(snapshot)).setComponents(rows).queue(null) { }
            } else {
                hook.sendFiles(FileUpload.fromData(image.png, image.name)).setEmbeds(embeds.image(image)).setComponents(rows).queue(null) { }
            }
        }
    }

    // --- 도움 --------------------------------------------------------------------------

    private fun replyEphemeral(event: SlashCommandInteractionEvent, text: String) {
        event.reply(text).setEphemeral(true).queue(null) { }
    }

    private fun <T> onMain(work: () -> T): CompletableFuture<T> {
        val future = CompletableFuture<T>()
        discord.runMain { runCatching(work).fold(future::complete, future::completeExceptionally) }
        return future
    }

    companion object {
        const val LIST = "접속목록"
        const val INFO = "정보"
        const val ITEM = "아이템"
        const val INVENTORY = "인벤"
        const val ENDER = "엔더"
        const val OPT_PLAYER = "플레이어"
        const val OPT_MEMBER = "멤버"
        const val OPT_SLOT = "칸"
    }
}
