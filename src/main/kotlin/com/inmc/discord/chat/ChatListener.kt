package com.inmc.discord.chat

import com.inmc.discord.Discord
import com.inmc.discord.config.CustomPlaceholder
import com.inmc.discord.config.Settings
import com.inmc.discord.relay.Texts
import io.papermc.paper.chat.ChatRenderer
import io.papermc.paper.event.player.AsyncChatEvent
import kr.inmc.core.integration.TitleForgeNames
import kr.inmc.core.util.Text
import net.kyori.adventure.audience.Audience
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.JoinConfiguration
import net.kyori.adventure.text.TextReplacementConfig
import net.kyori.adventure.text.event.ClickEvent
import net.kyori.adventure.text.event.HoverEvent
import org.bukkit.Bukkit
import org.bukkit.entity.Player
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.Listener
import org.bukkit.event.player.PlayerJoinEvent
import org.bukkit.event.player.PlayerQuitEvent
import java.util.Collections
import java.util.UUID
import java.util.WeakHashMap
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

/**
 * 채팅 꾸미기(InteractiveChat 이 하던 것)와 디스코드로 보낼 글 만들기.
 *
 * **스레드 규칙(이 플러그인의 존재 이유).** 여기는 채팅 스레드다. 권한·PAPI 는 **메시지 하나에 한 번**, 그것도 그 기능을 쓴
 * 글일 때만 묻는다. 주기적으로 접속자를 훑는 일은 없다 — 탭 완성 목록은 접속할 때 한 번 만든다.
 *
 * 순서:
 * - LOW: 글을 바꾼다(`event.message`). CMI 의 `HoverItems`(NORMAL)가 `[item]` 을 아이템 그림 글자로 먼저 바꾸면 우리 키워드가
 *   안 잡히므로 그보다 앞에서. 타이틀포지가 LOW 에서 정한 렌더러는 그리는 때에 글을 받으니 순서와 상관없다.
 * - NORMAL: 렌더러를 **감싼다**(타이틀포지가 LOW 에서 정한 뒤라야 한다). 감싼 렌더러는 보는 사람마다 멘션 강조·보는 사람 기준
 *   자리표시를 넣고, 다 그린 줄의 보낸 사람 이름에 호버·클릭을 단다.
 * - MONITOR: 취소되지 않았으면 디스코드로 보내고 멘션 알림.
 */
class ChatListener(private val discord: Discord) : Listener {

    private class Prepared(
        val discordText: String,
        val snapshots: List<Snapshot>,
        /** 본문에 들어간 호버(자리표시·명령어) — 디스코드에 툴팁 그림으로. */
        val hovers: List<List<Component>>,
        val mentions: List<MentionScan.Found>,
        val mentionTargets: Set<UUID>,
        val viewerMode: List<CustomPlaceholder>,
    )

    /** NORMAL → MONITOR 로 넘기는 것. 취소된 사건은 MONITOR 가 안 불리므로 약한 참조로 둔다. */
    private val prepared: MutableMap<AsyncChatEvent, Prepared> = Collections.synchronizedMap(WeakHashMap())

    /** 우리가 넣은 탭 완성 — 리로드 때 이것만 빼고 다시 넣는다. */
    private val completions = ConcurrentHashMap<UUID, List<String>>()

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    fun onChat(event: AsyncChatEvent) {
        val player = event.player
        val settings = discord.settings
        var message = event.message()
        var discordText = Texts.plain(message)
        val snapshots = ArrayList<Snapshot>()

        // 1. [item] · [inv] · [ender]
        for ((kind, keyword, permission) in keywords(settings.keywords)) {
            if (!keyword.enabled || !keyword.pattern.containsMatchIn(discordText)) continue
            if (!player.hasPermission(permission)) continue
            val snapshot = snapshot(player, kind) ?: continue
            if (kind == SnapshotKind.ITEM && snapshot.items.first()?.isEmpty != false && !keyword.allowAir) {
                discord.messages.send(player, "empty-hand")
                continue
            }
            val id = discord.snapshots.put(snapshot, settings.keywords.timeoutMinutes)
            val shown = KeywordText.component(kind, keyword, snapshot, id)
            message = replace(message, keyword.pattern) { shown }
            discordText = keyword.pattern.replace(discordText, Regex.escapeReplacement(discord.lang.plain(shown)))
            snapshots += snapshot
        }

        // 2. 사용자 자리표시 — 보는 사람 기준은 렌더러에서
        val viewerMode = ArrayList<CustomPlaceholder>()
        val hovers = ArrayList<List<Component>>()
        for (p in discord.placeholders) {
            val first = p.pattern.find(discordText) ?: continue
            if (p.permission.isNotBlank() && !player.hasPermission(p.permission)) continue
            // PAPI 확장이 던지면(서식이 틀린 %server_time_…% 등) 그 자리표시만 친 글 그대로 둔다 — 채팅 전체를 망치지 않게.
            runCatching {
                val hover = if (p.hover.isNotEmpty() && hovers.size < MAX_HOVERS) hoverLines(p, first.groupValues, player) else null
                val shown = if (p.viewer) message else replace(message, p.pattern) { groups -> placeholder(p, groups, player) }
                val text = p.pattern.replace(discordText) { m -> Texts.plain(placeholder(p, m.groupValues, player)) }
                hover?.let(hovers::add)
                if (p.viewer) viewerMode += p
                message = shown
                discordText = text
            }.onFailure { warnOnce(p, it) }
        }

        // 3. [/명령어]
        if (settings.commandTags.enabled && COMMAND_TAG.containsMatchIn(discordText)) {
            message = replace(message, COMMAND_TAG) { groups -> commandTag(groups[1], settings.commandTags) }
            val tags = settings.commandTags
            if (tags.hover.isNotEmpty()) for (m in COMMAND_TAG.findAll(discordText).take(MAX_HOVERS - hovers.size)) {
                hovers += tags.hover.map { Texts.mini(it, "command" to m.groupValues[1]) }
            }
        }

        // 4. @멘션
        val mentions = mentions(player, discordText, settings.mention)
        val targets = HashSet<UUID>()
        for (m in mentions) {
            if (m.everyone) Bukkit.getOnlinePlayers().forEach { targets += it.uniqueId } else targets += m.targets
        }
        targets -= player.uniqueId

        event.message(message)
        prepared[event] = Prepared(discordText, snapshots, hovers, mentions, targets, viewerMode)
    }

    @EventHandler(priority = EventPriority.NORMAL, ignoreCancelled = true)
    fun onDecorate(event: AsyncChatEvent) {
        val prep = prepared[event] ?: return
        val names = discord.settings.names
        val nameHover = if (names.enabled) nameDecoration(event.player, names) else null
        if (prep.mentions.isNotEmpty() || prep.viewerMode.isNotEmpty() || nameHover != null) {
            event.renderer(Decorated(event.renderer(), prep, nameHover))
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun onChatSent(event: AsyncChatEvent) {
        val prep = prepared.remove(event)
        discord.gameToDiscord.relay(event.player, prep?.discordText ?: Texts.plain(event.message()), prep?.snapshots.orEmpty(), prep?.hovers.orEmpty())
        if (prep != null) discord.alerts.fromGame(event.player, prep.mentionTargets)
    }

    @EventHandler
    fun onJoin(event: PlayerJoinEvent) {
        refreshCompletions(event.player)
    }

    @EventHandler
    fun onQuit(event: PlayerQuitEvent) {
        completions.remove(event.player.uniqueId)
        discord.alerts.forget(event.player.uniqueId)
    }

    /**
     * 메인 스레드 — 접속할 때 한 번, 리로드 때 한 번. 권한이 있는 자리표시 이름을 입력창 탭 완성에 넣는다.
     * InteractiveChat 은 이것을 **비동기로 10틱마다 접속자 전원 × 자리표시 수만큼** 다시 물었고, 그 `hasPermission` 이 LuckPerms
     * 잠금을 붙잡아 메인 스레드를 멈췄다. 권한이 바뀐 것은 다시 접속하거나 리로드하면 반영된다.
     */
    fun refreshCompletions(player: Player) {
        completions.remove(player.uniqueId)?.let(player::removeCustomChatCompletions)
        val settings = discord.settings
        if (!settings.completions) return
        val names = ArrayList<String>()
        for ((_, keyword, permission) in keywords(settings.keywords)) {
            if (keyword.enabled && keyword.name.isNotBlank() && player.hasPermission(permission)) names += keyword.name
        }
        for (p in discord.placeholders) {
            if (p.name.isNotBlank() && (p.permission.isBlank() || player.hasPermission(p.permission))) names += p.name
        }
        if (names.isEmpty()) return
        player.addCustomChatCompletions(names)
        completions[player.uniqueId] = names
    }

    // --- 키워드 -----------------------------------------------------------------

    private fun keywords(k: Settings.Keywords) = listOf(
        Triple(SnapshotKind.ITEM, k.item, Discord.KEYWORD_ITEM),
        Triple(SnapshotKind.INVENTORY, k.inventory, Discord.KEYWORD_INVENTORY),
        Triple(SnapshotKind.ENDER, k.ender, Discord.KEYWORD_ENDER),
    )

    /** 친 순간의 사본. 채팅 스레드면 메인에서 뜨고 최대 1초 기다린다(메인이 멈춰 있으면 키워드를 그대로 둔다). */
    private fun snapshot(player: Player, kind: SnapshotKind): Snapshot? {
        if (Bukkit.isPrimaryThread()) return Snapshot.take(player, kind)
        val future = CompletableFuture<Snapshot?>()
        player.scheduler.run(discord.plugin, { future.complete(runCatching { Snapshot.take(player, kind) }.getOrNull()) }, { future.complete(null) })
        return runCatching { future.get(SNAPSHOT_WAIT_MS, TimeUnit.MILLISECONDS) }.getOrNull()
    }

    // --- 사용자 자리표시 · 명령어 -------------------------------------------------

    /**
     * 정규식이 잡은 글(`$1`)은 **값으로만** 끼운다 — 마크업·PAPI 로 해석되지 않게. 템플릿 자체의 `%...%` 는 [papi] 기준으로 푼다.
     */
    private fun placeholder(p: CustomPlaceholder, groups: List<String>, papi: Player?): Component {
        fun fill(template: String): Component {
            var t = template
            for (i in groups.indices.reversed()) t = t.replace("$$i", "{g$i}")
            t = Text.substituteOnly(t, null, papi)
            return Texts.mini(t, *groups.mapIndexed { i, g -> "g$i" to g }.toTypedArray())
        }
        val shown = if (p.text.isEmpty()) Component.text(groups.firstOrNull().orEmpty()) else fill(p.text)
        var out = shown
        if (p.hover.isNotEmpty()) out = out.hoverEvent(HoverEvent.showText(Component.join(JoinConfiguration.newlines(), hoverLines(p, groups, papi))))
        if (p.clickAction.isNotEmpty()) {
            var value = p.clickValue
            for (i in groups.indices.reversed()) value = value.replace("$$i", groups[i])
            CustomPlaceholder.click(p.clickAction, Text.substituteOnly(value, null, papi))?.let { out = out.clickEvent(it) }
        }
        return out
    }

    /** 자리표시의 호버 줄들 — `$1` 은 값으로만, 템플릿의 `%...%` 는 [papi] 기준. */
    private fun hoverLines(p: CustomPlaceholder, groups: List<String>, papi: Player?): List<Component> = p.hover.map { template ->
        var t = template
        for (i in groups.indices.reversed()) t = t.replace("$$i", "{g$i}")
        t = Text.substituteOnly(t, null, papi)
        Texts.mini(t, *groups.mapIndexed { i, g -> "g$i" to g }.toTypedArray())
    }

    /** 자리표시마다 한 번만 경고(켜져 있는 동안). */
    private val failed: MutableSet<String> = ConcurrentHashMap.newKeySet()

    private fun warnOnce(p: CustomPlaceholder, error: Throwable) {
        if (failed.add(p.name)) discord.logger.warning("사용자 자리표시 ${p.name} 을(를) 채우지 못해 친 글 그대로 둡니다(placeholders.yml 의 %...% 를 확인하세요): $error")
    }

    private fun commandTag(command: String, tags: Settings.CommandTags): Component {
        val shown = Texts.mini(tags.text, "command" to command).clickEvent(ClickEvent.suggestCommand(command))
        if (tags.hover.isEmpty()) return shown
        return shown.hoverEvent(HoverEvent.showText(Component.join(JoinConfiguration.newlines(), tags.hover.map { Texts.mini(it, "command" to command) })))
    }

    private fun replace(component: Component, regex: Regex, make: (List<String>) -> Component): Component =
        component.replaceText(
            TextReplacementConfig.builder().match(regex.toPattern())
                .replacement { result, _ -> make((0..result.groupCount()).map { result.group(it).orEmpty() }) }
                .build(),
        )

    // --- 멘션 -------------------------------------------------------------------

    private fun mentions(player: Player, text: String, mention: Settings.Mention): List<MentionScan.Found> {
        if (!mention.enabled || !text.contains(mention.prefix)) return emptyList()
        if (!player.hasPermission(Discord.MENTION_PLAYER)) return emptyList()
        val names = HashMap<String, UUID>()
        for (online in Bukkit.getOnlinePlayers()) {
            names[online.name] = online.uniqueId
            names[TitleForgeNames.displayName(online.uniqueId, online.name)] = online.uniqueId
        }
        val everyone = HashSet<String>()
        if (text.contains(mention.prefix + "here", ignoreCase = true) && player.hasPermission(Discord.MENTION_HERE)) everyone += "here"
        if (text.contains(mention.prefix + "everyone", ignoreCase = true) && player.hasPermission(Discord.MENTION_EVERYONE)) everyone += "everyone"
        return MentionScan.scan(text, mention.prefix, names, everyone)
    }

    // --- 보낸 사람 이름 ------------------------------------------------------------

    private class NameDecoration(val nickname: String, val hover: Component?, val click: ClickEvent<*>?)

    /** 호버 줄의 PAPI 는 메시지 하나에 한 번(보낸 사람 기준). */
    private fun nameDecoration(player: Player, names: Settings.NameInteraction): NameDecoration? {
        val hover = names.hover.takeIf { it.isNotEmpty() }?.map { Text.render(it.replace("{player}", player.name), null, player) }
            ?.let { Component.join(JoinConfiguration.newlines(), it) }
        val click = CustomPlaceholder.click(names.clickAction, Text.substituteOnly(names.clickValue.replace("{player}", player.name), null, player))
        if (hover == null && click == null) return null
        return NameDecoration(TitleForgeNames.displayName(player.uniqueId, player.name), hover, click)
    }

    /** 보는 사람마다 한 번 불린다. 여기서는 PAPI 를 부르지 않는다 — 보는 사람 기준 자리표시를 친 글일 때만 예외. */
    private inner class Decorated(
        private val inner: ChatRenderer,
        private val prep: Prepared,
        private val name: NameDecoration?,
    ) : ChatRenderer {

        override fun render(source: Player, sourceDisplayName: Component, message: Component, viewer: Audience): Component {
            val viewerPlayer = viewer as? Player
            var msg = message
            for (p in prep.viewerMode) {
                msg = runCatching { replace(msg, p.pattern) { groups -> placeholder(p, groups, viewerPlayer) } }.getOrElse { warnOnce(p, it); msg }
            }
            if (prep.mentions.isNotEmpty()) msg = highlight(msg, viewerPlayer, source)
            var out = inner.render(source, sourceDisplayName, msg, viewer)
            if (name != null && name.nickname.isNotEmpty()) {
                out = out.replaceText(
                    TextReplacementConfig.builder().matchLiteral(name.nickname).once().replacement { b ->
                        var built = b
                        if (name.hover != null) built = built.hoverEvent(HoverEvent.showText(name.hover))
                        if (name.click != null) built = built.clickEvent(name.click)
                        built
                    }.build(),
                )
            }
            return out
        }

        private fun highlight(message: Component, viewer: Player?, source: Player): Component {
            val mention = discord.settings.mention
            val sender = TitleForgeNames.displayName(source.uniqueId, source.name)
            var out = message
            for (found in prep.mentions) {
                val self = viewer != null && (found.everyone || viewer.uniqueId in found.targets)
                val template = if (self) mention.highlightSelf else mention.highlightOthers
                val hover = mention.hover.map { Texts.mini(it, "sender" to sender) }
                out = out.replaceText(
                    TextReplacementConfig.builder().matchLiteral(found.text).replacement { _ ->
                        val shown = Texts.mini(template, "mentioned" to found.text)
                        if (hover.isEmpty()) shown else shown.hoverEvent(HoverEvent.showText(Component.join(JoinConfiguration.newlines(), hover)))
                    }.build(),
                )
            }
            return out
        }
    }

    private companion object {
        val COMMAND_TAG = Regex("\\[(/[^\\[\\]]+)]")
        const val SNAPSHOT_WAIT_MS = 1000L
        /** 메시지 하나에 붙일 호버 그림 수. */
        const val MAX_HOVERS = 3
    }
}
