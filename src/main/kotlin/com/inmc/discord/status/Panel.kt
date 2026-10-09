package com.inmc.discord.status

import com.inmc.discord.Discord
import com.inmc.discord.config.PanelDesign
import com.inmc.discord.config.Settings
import com.inmc.discord.status.PanelLayout.Data
import com.inmc.discord.status.PanelLayout.Gathered
import com.inmc.discord.status.PanelLayout.State
import com.inmc.discord.util.Ph
import kr.inmc.core.integration.TitleForgeNames
import net.dv8tion.jda.api.EmbedBuilder
import net.dv8tion.jda.api.JDA
import net.dv8tion.jda.api.components.actionrow.ActionRow
import net.dv8tion.jda.api.components.buttons.Button
import net.dv8tion.jda.api.entities.Message
import net.dv8tion.jda.api.entities.MessageEmbed
import net.dv8tion.jda.api.interactions.InteractionHook
import net.dv8tion.jda.api.entities.channel.middleman.GuildMessageChannel
import net.dv8tion.jda.api.entities.emoji.Emoji
import net.dv8tion.jda.api.events.interaction.component.ButtonInteractionEvent
import net.dv8tion.jda.api.exceptions.ErrorResponseException
import net.dv8tion.jda.api.requests.ErrorResponse
import net.dv8tion.jda.api.utils.messages.MessageCreateBuilder
import net.dv8tion.jda.api.utils.messages.MessageEditBuilder
import org.bukkit.Bukkit
import org.bukkit.entity.Player
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.Listener
import org.bukkit.event.player.PlayerJoinEvent
import java.text.Collator
import java.time.Instant
import java.time.LocalDate
import java.util.Locale
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionException
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 서버 현황 패널(2026-10-09, 사용자 요청) — `panel.channel` 의 봇 메시지 하나를 `panel.refresh-seconds` 마다 고친다.
 *
 * **봇이 서버 안에 있다** — 서버가 꺼지면 패널을 고칠 것도 없다. 그래서 상태는 꺼지기 직전(🔴, [shutdown])과 켜지자마자(🟡 → 🟢) 적고,
 * 멈춤은 감시 스레드가 메인 없이 적는다(🟠, [hang]). 크래시는 적을 틈이 없다 — "마지막 갱신 N분 전"(디스코드 타임스탬프)이 대신 알린다.
 *
 * 주기마다 메인에서 뜨는 것은 접속자 이름(타이틀포지 캐시)·수·TPS·버전·오늘 수뿐이다. **권한·PAPI 를 사람마다 묻지 않는다**(규칙 1) —
 * 그래서 랭크 접두사는 패널에 없다. 앞 갱신이 안 끝났으면 건너뛴다(멈춘 동안 쌓이지 않게).
 */
class Panel(private val discord: Discord) : Listener {

    val store = PanelStore(discord.io)

    @Volatile
    var state: State = State.STARTING
        private set

    @Volatile
    private var startedAt: Long? = null

    @Volatile
    private var stoppedAt: Long? = null

    @Volatile
    private var hangSince: Long? = null

    /** 마지막으로 메인에서 뜬 값 — 멈춤·꺼짐 패널은 이것으로 그린다. */
    @Volatile
    private var last: Gathered? = null

    @Volatile
    private var stopping = false

    @Volatile
    private var lastManual = 0L

    @Volatile
    private var lastWarning: String? = null

    private val gathering = AtomicBoolean(false)
    private val posting = AtomicBoolean(false)

    /** 켤 때 한 번 — 채널에 남은 우리 패널을 하나로 정리했나([reconcile]). */
    private val reconciled = AtomicBoolean(false)
    private val reconciling = AtomicBoolean(false)
    private var task: ScheduledFuture<*>? = null

    private val enabled: Boolean get() = discord.settings.panel.channel != null

    // --- 수명 -----------------------------------------------------------------------

    /** 봇이 붙었을 때(봇 일꾼). */
    fun start() = reschedule(0)

    /** 서버가 첫 틱을 돌았을 때(메인) — 이때가 "서버 켜진 시간". */
    fun serverStarted() {
        startedAt = System.currentTimeMillis()
        if (state == State.STARTING) state = State.ONLINE
        later { refresh() }
    }

    /** 리로드 — 주기·채널·모양이 바뀌었을 수 있다. */
    fun reloaded() = reschedule(0)

    /** 감시 스레드 — 메인이 [since] 부터 멈췄다. */
    fun hang(since: Long) {
        if (state != State.ONLINE) return
        hangSince = since
        state = State.HANG
        later { publish(data(last)) }
    }

    /** 감시 스레드 — 메인이 다시 돈다. */
    fun recovered() {
        if (state != State.HANG) return
        hangSince = null
        state = State.ONLINE
        later { refresh() }
    }

    /**
     * 꺼질 때(메인, `Bot.stop` 이 JDA 를 닫기 전에). 🔴 와 꺼진 시각을 적고 단추를 끈 모양으로 — 그 요청을 돌려준다(최대 5초 기다림).
     */
    fun shutdown(): CompletableFuture<*> {
        task?.cancel(false)
        stopping = true
        if (!enabled) return DONE
        stoppedAt = System.currentTimeMillis()
        state = State.OFFLINE
        return runCatching { publish(data(last)) }.getOrDefault(DONE)
    }

    private fun reschedule(initialDelay: Long) {
        task?.cancel(false)
        if (stopping) return
        val seconds = discord.settings.panel.refreshSeconds.toLong()
        task = runCatching {
            discord.bot.scheduler.scheduleAtFixedRate({
                runCatching { refresh() }.onFailure { warn("패널을 새로 고치지 못했습니다: ${it.message}") }
            }, initialDelay, seconds, TimeUnit.SECONDS)
        }.getOrNull()
    }

    /** 봇 일꾼에서(꺼지는 중이면 버린다). */
    private fun later(work: () -> Unit) {
        runCatching { discord.bot.scheduler.execute { runCatching(work).onFailure { warn("패널: ${it.message}") } } }
    }

    // --- 새로 고치기 ------------------------------------------------------------------

    fun refresh() {
        if (stopping || !enabled || discord.bot.jda == null || !store.loaded) return
        if (!reconciled.get()) {
            if (reconciling.compareAndSet(false, true)) {
                reconcile().whenComplete { _, _ ->
                    reconciled.set(true)
                    reconciling.set(false)
                    later { refresh() }
                }
            }
            return
        }
        when (state) {
            State.STARTING -> publish(data(null))
            State.HANG -> publish(data(last))
            State.OFFLINE -> Unit
            State.ONLINE -> {
                if (!gathering.compareAndSet(false, true)) return
                gather().whenComplete { gathered, _ ->
                    gathering.set(false)
                    if (gathered == null) return@whenComplete
                    last = gathered
                    if (state == State.ONLINE && !stopping) later { publish(data(gathered)) }
                }
            }
        }
    }

    /**
     * 켤 때 한 번 — 패널 채널의 최근 메시지에서 **우리 패널**(봇이 쓴 것 + `inmcd:panel:` 단추)을 찾아 하나만 남긴다.
     * 저장된 것이 있으면 그것, 없으면 가장 새것([PanelLayout.keepPanel]). `panel.yml` 을 잃었거나 예전에 둘이 된 패널을 정리한다.
     * "메시지 기록 보기" 권한이 없으면 건너뛴다.
     */
    private fun reconcile(): CompletableFuture<*> {
        val jda = discord.bot.jda ?: return DONE
        val channelId = discord.settings.panel.channel ?: return DONE
        val channel = channel(channelId) ?: return DONE
        return channel.history.retrievePast(RECONCILE_LOOKBACK).submit().thenAccept { messages ->
            val ours = messages.filter { it.author.idLong == jda.selfUser.idLong && isPanel(it) }
            val stored = store.message?.takeIf { it.channel == channelId }?.id
            val keep = PanelLayout.keepPanel(stored, ours.map { it.idLong })
            if (keep != null && keep != stored) store.setMessage(PanelStore.Message(channelId, keep))
            val extra = ours.filter { it.idLong != keep }
            for (message in extra) message.delete().queue(null) { }
            if (extra.isNotEmpty()) discord.logger.info("패널 채널에 남은 패널 ${extra.size}개를 지웠습니다")
        }.exceptionally {
            warn("패널 채널의 지난 메시지를 읽지 못해 남은 패널을 정리하지 않았습니다 — 봇에게 '메시지 기록 보기' 권한이 있으면 정리합니다")
            null
        }
    }

    private fun isPanel(message: Message): Boolean =
        runCatching { message.componentTree.findAll(Button::class.java).any { it.customId?.startsWith(PREFIX) == true } }.getOrDefault(false)

    /** `/디스코드 관리 패널` — 옛 메시지를 지우고 새로 올린다(메인). */
    fun repost() {
        later {
            store.message?.let { old -> channel(old.channel)?.deleteMessageById(old.id)?.queue(null) { } }
            store.setMessage(null)
            refresh()
        }
    }

    /** `/디스코드 관리 패널 점검 [사유]` — null 이면 뗀다(메인). 재시작을 넘어 남는다(`panel.yml`). */
    fun maintenance(reason: String?) {
        store.setMaintenance(reason)
        store.flush()
        later { refresh() }
    }

    private fun data(gathered: Gathered?) = Data(state, gathered, startedAt, stoppedAt, hangSince, store.maintenance, System.currentTimeMillis())

    private fun gather(): CompletableFuture<Gathered> {
        val future = CompletableFuture<Gathered>()
        discord.runMain {
            runCatching {
                val players = Bukkit.getOnlinePlayers().filterNot(::vanished)
                val names = players.map { TitleForgeNames.displayName(it.uniqueId, it.name) }.sortedWith(Collator.getInstance(Locale.KOREAN))
                val today = store.today(LocalDate.now(discord.settings.time.zone), players.size)
                store.flush()
                Gathered(names, players.size, Bukkit.getMaxPlayers(), Bukkit.getTPS().firstOrNull() ?: 20.0, Bukkit.getMinecraftVersion(), today)
            }.fold(future::complete, future::completeExceptionally)
        }
        return future.orTimeout(GATHER_TIMEOUT_SECONDS, TimeUnit.SECONDS)
    }

    private fun vanished(player: Player): Boolean = player.getMetadata("vanished").any { it.asBoolean() }

    @EventHandler(priority = EventPriority.MONITOR)
    fun onJoin(event: PlayerJoinEvent) {
        store.recordJoin(event.player.uniqueId, Bukkit.getOnlinePlayers().size, LocalDate.now(discord.settings.time.zone))
    }

    // --- 디스코드 ----------------------------------------------------------------------

    private fun channel(id: Long): GuildMessageChannel? = discord.bot.jda?.getChannelById(GuildMessageChannel::class.java, id)

    /** 올렸거나 고친 요청. 저장된 메시지가 지워졌으면 새로 올린다. */
    private fun publish(data: Data): CompletableFuture<*> {
        val jda = discord.bot.jda ?: return DONE
        val panel = discord.settings.panel
        val channelId = panel.channel ?: return DONE
        val channel = channel(channelId) ?: run {
            warn("패널 채널 $channelId 을 찾지 못했습니다 — 봇이 그 채널을 볼 수 있는지 확인하세요")
            return DONE
        }
        val design = discord.panelDesign
        val view = PanelLayout.view(data, design, panel.refreshSeconds)
        val embed = embed(view, design, jda)
        val rows = rows(view, panel, design)
        val stored = store.message
        if (stored != null && stored.channel == channelId) {
            return channel.editMessageById(stored.id, MessageEditBuilder().setEmbeds(embed).setComponents(rows).build()).submit()
                .thenApply { lastWarning = null }
                .exceptionallyCompose { error ->
                    if (unknownMessage(error)) {
                        store.setMessage(null)
                        post(channel, embed, rows)
                    } else {
                        warn("패널을 고치지 못했습니다: ${cause(error).message}")
                        CompletableFuture.completedFuture(Unit)
                    }
                }
        }
        if (stored != null) {
            // 채널을 바꿨다 — 옛 채널의 패널은 지운다.
            channel(stored.channel)?.deleteMessageById(stored.id)?.queue(null) { }
            store.setMessage(null)
        }
        return post(channel, embed, rows)
    }

    private fun post(channel: GuildMessageChannel, embed: MessageEmbed, rows: List<ActionRow>): CompletableFuture<Unit> {
        // 올리는 중에 다른 갱신이 또 올리면 패널이 둘이 된다.
        if (!posting.compareAndSet(false, true)) return CompletableFuture.completedFuture(Unit)
        return channel.sendMessage(MessageCreateBuilder().setEmbeds(embed).setComponents(rows).build()).submit()
            .thenApply { message ->
                store.setMessage(PanelStore.Message(channel.idLong, message.idLong))
                lastWarning = null
            }
            .whenComplete { _, error ->
                posting.set(false)
                if (error != null) warn("패널을 올리지 못했습니다(#${channel.name}): ${cause(error).message}")
            }
    }

    private fun embed(view: PanelLayout.View, design: PanelDesign, jda: JDA): MessageEmbed {
        val builder = EmbedBuilder()
            .setTitle(view.title)
            .setColor(view.color)
            .setThumbnail(design.thumbnail.ifBlank { jda.selfUser.effectiveAvatarUrl })
            .setFooter(view.footer)
            .setTimestamp(Instant.now())
        for (field in view.fields) builder.addField(field.name, field.value, field.inline)
        return builder.build()
    }

    private fun rows(view: PanelLayout.View, panel: Settings.Panel, design: PanelDesign): List<ActionRow> {
        fun styled(button: Button, key: String): Button {
            val emoji = design.button(key).emoji
            return if (emoji.isBlank()) button else runCatching { button.withEmoji(Emoji.fromFormatted(emoji)) }.getOrDefault(button)
        }
        val actions = mutableListOf(
            styled(Button.primary(PREFIX + ME, design.button(ME).label), ME),
            styled(Button.secondary(PREFIX + REFRESH, design.button(REFRESH).label), REFRESH),
        )
        if (panel.notifyRole.isNotBlank()) actions += styled(Button.success(PREFIX + NOTIFY, design.button(NOTIFY).label), NOTIFY)
        val rows = mutableListOf(ActionRow.of(actions.map { it.withDisabled(!view.buttonsEnabled) }))
        // 링크 단추는 디스코드가 여는 것이라 꺼져 있어도 둔다.
        val links = design.links.mapNotNull { link ->
            runCatching {
                val button = Button.link(link.url, link.label.take(Button.LABEL_MAX_LENGTH))
                if (link.emoji.isBlank()) button else button.withEmoji(Emoji.fromFormatted(link.emoji))
            }.getOrNull()
        }
        if (links.isNotEmpty()) rows += ActionRow.of(links.take(PanelDesign.MAX_LINKS))
        return rows
    }

    /** 패널 단추(JDA 스레드). @return 우리 것이었으면 true */
    fun onButton(event: ButtonInteractionEvent): Boolean {
        val id = event.componentId
        if (!id.startsWith(PREFIX)) return false
        when (id.removePrefix(PREFIX)) {
            ME -> me(event)
            REFRESH -> manual(event)
            NOTIFY -> notify(event)
            else -> event.deferEdit().queue(null) { }
        }
        return true
    }

    /** 내 정보 — 누른 사람에게만, `panel.reply-seconds` 뒤 지운다. `/정보` 와 같은 길(설정 `slash.player-info`). */
    private fun me(event: ButtonInteractionEvent) {
        val uuid = discord.links.playerOf(event.user.idLong)
            ?: return replyEphemeral(event, discord.messages.discord("discord-not-linked"))
        event.deferReply(true).queue({ hook -> discord.slash.sendInfo(hook, null, uuid) { sent -> expire(hook, sent) } }) { }
    }

    private fun replyEphemeral(event: ButtonInteractionEvent, text: String) {
        event.reply(text).setEphemeral(true).queue({ hook -> expire(hook, null) }) { }
    }

    /** 나만 보이는 답을 `panel.reply-seconds` 뒤 지운다(2026-10-09 사용자 요청 "10초쯤"). 보낸 메시지가 있으면 그것을, 없으면 첫 답을. */
    private fun expire(hook: InteractionHook, sent: Message?) {
        val seconds = discord.settings.panel.replySeconds
        if (seconds <= 0) return
        val delete = if (sent != null) hook.deleteMessageById(sent.idLong) else hook.deleteOriginal()
        delete.queueAfter(seconds.toLong(), TimeUnit.SECONDS, null) { }
    }

    /** 새로고침 — 그 자리에서 고친다. 모두가 함께 [MANUAL_COOLDOWN_MILLIS] 에 한 번. 주기 타이머도 처음부터. */
    private fun manual(event: ButtonInteractionEvent) {
        event.deferEdit().queue(null) { }
        val now = System.currentTimeMillis()
        if (now - lastManual < MANUAL_COOLDOWN_MILLIS) return
        lastManual = now
        later {
            refresh()
            reschedule(discord.settings.panel.refreshSeconds.toLong())
        }
    }

    /** 켜지면 알림 받기 — 역할을 넣거나 뺀다. */
    private fun notify(event: ButtonInteractionEvent) {
        val name = discord.settings.panel.notifyRole
        val guild = event.guild
        val member = event.member
        val role = guild?.getRolesByName(name, true)?.firstOrNull()
        if (name.isBlank() || guild == null || member == null || role == null) {
            replyEphemeral(event, discord.messages.discord("discord-panel-notify-failed"))
            if (role == null && name.isNotBlank()) warn("알림 역할 '$name' 이 디스코드 서버에 없습니다")
            return
        }
        val had = member.roles.any { it.idLong == role.idLong }
        event.deferReply(true).queue({ hook ->
            val action = if (had) guild.removeRoleFromMember(member, role) else guild.addRoleToMember(member, role)
            action.queue({
                val key = if (had) "discord-panel-notify-removed" else "discord-panel-notify-added"
                hook.sendMessage(discord.messages.discord(key, Ph.of().name(role.name))).queue({ expire(hook, it) }) { }
            }) { error ->
                warn("알림 역할을 바꾸지 못했습니다 — 봇의 역할 관리 권한과 역할 순서를 보세요: ${error.message}")
                hook.sendMessage(discord.messages.discord("discord-panel-notify-failed")).queue({ expire(hook, it) }) { }
            }
        }) { }
    }

    /** 서버 시작 알림이 멘션할 알림 역할 id. 없으면 null. */
    fun notifyRoleId(): Long? {
        val name = discord.settings.panel.notifyRole
        if (name.isBlank()) return null
        return discord.bot.guild()?.getRolesByName(name, true)?.firstOrNull()?.idLong
    }

    /** `/디스코드 관리 검증` 이 보는 한 줄. */
    fun describe(): String = when {
        !enabled -> "꺼짐(panel.channel 비어 있음)"
        else -> "$state · " + (store.message?.let { "메시지 ${it.id}" } ?: "아직 안 올림") + (lastWarning?.let { " · $it" } ?: "")
    }

    /** 같은 경고를 주기마다 되풀이하지 않는다. */
    private fun warn(text: String) {
        if (text == lastWarning) return
        lastWarning = text
        discord.logger.warning(text)
    }

    private fun cause(error: Throwable): Throwable = if (error is CompletionException && error.cause != null) error.cause!! else error

    private fun unknownMessage(error: Throwable): Boolean =
        (cause(error) as? ErrorResponseException)?.errorResponse == ErrorResponse.UNKNOWN_MESSAGE

    companion object {
        const val PREFIX = "inmcd:panel:"
        const val ME = "me"
        const val REFRESH = "refresh"
        const val NOTIFY = "notify"

        const val MANUAL_COOLDOWN_MILLIS = 10_000L

        /** 켤 때 남은 패널을 찾는 최근 메시지 수. 패널 채널은 전용이라 넉넉하다. */
        const val RECONCILE_LOOKBACK = 50
        const val GATHER_TIMEOUT_SECONDS = 20L

        private val DONE: CompletableFuture<*> = CompletableFuture.completedFuture(null)
    }
}
