package com.inmc.discord.bot

import com.inmc.discord.Discord
import com.inmc.discord.status.Topics
import net.dv8tion.jda.api.EmbedBuilder
import net.dv8tion.jda.api.JDA
import net.dv8tion.jda.api.JDABuilder
import net.dv8tion.jda.api.entities.Activity
import net.dv8tion.jda.api.entities.Guild
import net.dv8tion.jda.api.entities.Message
import net.dv8tion.jda.api.entities.MessageEmbed
import net.dv8tion.jda.api.entities.channel.concrete.TextChannel
import net.dv8tion.jda.api.events.interaction.command.SlashCommandInteractionEvent
import net.dv8tion.jda.api.events.message.MessageReceivedEvent
import net.dv8tion.jda.api.events.session.ShutdownEvent
import net.dv8tion.jda.api.exceptions.InvalidTokenException
import net.dv8tion.jda.api.hooks.ListenerAdapter
import net.dv8tion.jda.api.interactions.commands.OptionType
import net.dv8tion.jda.api.interactions.commands.build.Commands
import net.dv8tion.jda.api.requests.CloseCode
import net.dv8tion.jda.api.requests.GatewayIntent
import net.dv8tion.jda.api.utils.ChunkingFilter
import net.dv8tion.jda.api.utils.MemberCachePolicy
import net.dv8tion.jda.api.utils.cache.CacheFlag
import net.dv8tion.jda.api.utils.messages.MessageCreateBuilder
import net.dv8tion.jda.api.utils.messages.MessageCreateData
import java.time.Duration
import java.util.EnumSet
import java.util.concurrent.CompletableFuture
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import java.util.logging.Level

/**
 * JDA 의 수명. 연결·끊기는 전부 우리 스레드(`inmc-discord-bot`) 하나에서 한다 — 메인 스레드는 기다리지 않는다
 * (꺼질 때만 예외: 종료 알림·주제를 보내고 닫기까지 최대 [SHUTDOWN_WAIT] 를 기다린다).
 *
 * 인텐트: 서버 메시지·메시지 내용·DM(연결 코드)·멤버(이름 → 멘션 바꾸기). 멤버 인텐트를 포털에서 켜지 않았으면
 * (닫힘 코드 4014) 멤버 없이 한 번 더 붙는다 — 그때는 연결된 계정만 멘션으로 바뀐다.
 */
class Bot(private val discord: Discord) {

    private val executor: ScheduledExecutorService = Executors.newSingleThreadScheduledExecutor { runnable ->
        Thread(runnable, "inmc-discord-bot").apply { isDaemon = true }
    }

    @Volatile
    var jda: JDA? = null
        private set

    /** `/디스코드 관리 상태` 에 보이는 한 줄. */
    @Volatile
    var state: String = "꺼짐"
        private set

    @Volatile
    private var closeCode: CloseCode? = null

    @Volatile
    private var serverStarted = false

    @Volatile
    private var announced = false

    private var topicTask: ScheduledFuture<*>? = null

    /** 우리 일꾼에서 돌린다 — 콘솔 묶음·주제처럼 시간을 맞춰 도는 일. */
    val scheduler: ScheduledExecutorService get() = executor

    fun start() {
        executor.execute(::connect)
    }

    /** 서버가 첫 틱을 돌았다(모든 플러그인이 켜졌다). 봇도 붙었으면 시작 알림. */
    fun markServerStarted() {
        serverStarted = true
        executor.execute(::announceStartIfReady)
    }

    private fun connect() {
        val settings = discord.settings
        val token = settings.bot.token
        if (token.isBlank()) {
            state = "토큰 없음"
            discord.logger.warning("config.yml 의 bot.token 이 비어 있어 디스코드에 연결하지 않습니다")
            return
        }
        val intents = EnumSet.of(
            GatewayIntent.GUILD_MESSAGES,
            GatewayIntent.MESSAGE_CONTENT,
            GatewayIntent.DIRECT_MESSAGES,
            GatewayIntent.GUILD_MEMBERS,
        )
        state = "연결 중"
        while (true) {
            closeCode = null
            val built = try {
                build(token, settings.bot.status, intents)
            } catch (e: InvalidTokenException) {
                state = "토큰이 틀림"
                discord.logger.severe("봇 토큰이 틀려 디스코드에 연결하지 못했습니다 — config.yml 의 bot.token 을 확인하세요")
                return
            } catch (e: Exception) {
                state = "연결 실패"
                discord.logger.log(Level.SEVERE, "디스코드 봇을 만들지 못했습니다: ${e.javaClass.simpleName}")
                return
            }
            try {
                built.awaitReady()
                jda = built
                state = "연결됨 (${built.selfUser.name})"
                discord.logger.info("디스코드에 연결했습니다 — ${built.selfUser.name}" + if (GatewayIntent.GUILD_MEMBERS in intents) "" else " (멤버 인텐트 없이)")
                onReady(built)
                return
            } catch (e: Exception) {
                if (closeCode == CloseCode.DISALLOWED_INTENTS && GatewayIntent.GUILD_MEMBERS in intents) {
                    discord.logger.warning("개발자 포털에서 Server Members 인텐트가 꺼져 있어 멤버 없이 다시 붙습니다 — 게임의 @이름 은 연결된 계정만 멘션이 됩니다")
                    intents.remove(GatewayIntent.GUILD_MEMBERS)
                    continue
                }
                state = "연결 실패" + (closeCode?.let { " (${it.code} ${it.meaning})" } ?: "")
                discord.logger.severe("디스코드에 연결하지 못했습니다: $state")
                runCatching { built.shutdownNow() }
                return
            }
        }
    }

    private fun build(token: String, status: String, intents: EnumSet<GatewayIntent>): JDA {
        val members = GatewayIntent.GUILD_MEMBERS in intents
        return JDABuilder.create(token, intents)
            .disableCache(
                CacheFlag.ACTIVITY, CacheFlag.CLIENT_STATUS, CacheFlag.ONLINE_STATUS, CacheFlag.VOICE_STATE,
                CacheFlag.SCHEDULED_EVENTS, CacheFlag.EMOJI, CacheFlag.STICKER, CacheFlag.SOUNDBOARD_SOUNDS,
            )
            .setMemberCachePolicy(if (members) MemberCachePolicy.ALL else MemberCachePolicy.DEFAULT)
            .setChunkingFilter(if (members) ChunkingFilter.ALL else ChunkingFilter.NONE)
            .setActivity(status.takeIf { it.isNotBlank() }?.let(Activity::playing))
            // 끄기는 플러그인이 한다 — JVM 종료 훅이 먼저 끊으면 종료 알림을 못 보낸다.
            .setEnableShutdownHook(false)
            .addEventListeners(Listener())
            .build()
    }

    private inner class Listener : ListenerAdapter() {
        override fun onMessageReceived(event: MessageReceivedEvent) {
            runCatching { discord.discordToGame.onMessage(event) }
                .onFailure { discord.logger.log(Level.WARNING, "디스코드 메시지 처리 실패", it) }
        }

        override fun onStringSelectInteraction(event: net.dv8tion.jda.api.events.interaction.component.StringSelectInteractionEvent) {
            runCatching { discord.interactions.onSelect(event) }
                .onFailure { discord.logger.log(Level.WARNING, "선택 메뉴 처리 실패", it) }
        }

        override fun onButtonInteraction(event: net.dv8tion.jda.api.events.interaction.component.ButtonInteractionEvent) {
            runCatching { discord.interactions.onButton(event) }
                .onFailure { discord.logger.log(Level.WARNING, "단추 처리 실패", it) }
        }

        override fun onSlashCommandInteraction(event: SlashCommandInteractionEvent) {
            runCatching { if (event.name == SLASH_LINK) discord.linking.onSlash(event) else discord.slash.handle(event) }
                .onFailure { discord.logger.log(Level.WARNING, "슬래시 명령어 처리 실패", it) }
        }

        override fun onShutdown(event: ShutdownEvent) {
            closeCode = event.closeCode
        }
    }

    private fun onReady(jda: JDA) {
        val settings = discord.settings
        if (settings.chatChannel != null && chatChannel() == null) {
            discord.logger.warning("채팅 채널 ${settings.chatChannel} 을 찾지 못했습니다 — 봇이 그 서버에 있고 채널을 볼 수 있는지 확인하세요")
        }
        if (settings.consoleChannel != null && consoleChannel() == null) {
            discord.logger.warning("콘솔 채널 ${settings.consoleChannel} 을 찾지 못했습니다")
        }
        val commands = listOf(
            Commands.slash(SLASH_LINK, "마인크래프트 계정 연결 — 게임에서 /디스코드 연결 로 받은 코드")
                .addOption(OptionType.STRING, SLASH_LINK_CODE, "네 자리 코드", true),
        ) + discord.slash.definitions()
        guild()?.updateCommands()?.addCommands(commands)
            ?.queue(null) { discord.logger.warning("슬래시 명령어를 올리지 못했습니다: ${it.message}") }
        restartTopics(initial = true)
        announceStartIfReady()
    }

    private fun announceStartIfReady() {
        if (announced || !serverStarted || jda == null) return
        announced = true
        val text = discord.settings.lifecycle.startup
        if (text.isNotBlank()) chatChannel()?.let { send(it, text) }
    }

    /** 리로드 때 주기가 바뀌었을 수 있다. */
    fun restartTopics(initial: Boolean = false) {
        topicTask?.cancel(false)
        val minutes = discord.settings.topic.intervalMinutes.toLong()
        topicTask = executor.scheduleAtFixedRate(
            { runCatching { Topics(discord).update(shutdown = false) } },
            if (initial) 0 else minutes, minutes, TimeUnit.MINUTES,
        )
    }

    // --- 찾기 ------------------------------------------------------------------

    fun chatChannel(): TextChannel? = discord.settings.chatChannel?.let { jda?.getTextChannelById(it) }

    fun consoleChannel(): TextChannel? = discord.settings.consoleChannel?.let { jda?.getTextChannelById(it) }

    /** 채팅 채널이 있는 서버. 역할·멤버는 여기서 찾는다. */
    fun guild(): Guild? = chatChannel()?.guild ?: consoleChannel()?.guild

    // --- 보내기 ----------------------------------------------------------------

    /** 비동기로 보낸다(JDA 가 레이트리밋을 맞춘다). 어느 스레드에서 불러도 된다. */
    fun send(channel: TextChannel, text: String, mentions: Set<Message.MentionType> = SAFE_MENTIONS, embeds: List<MessageEmbed> = emptyList()) {
        val data = message(text, mentions, embeds) ?: return
        channel.sendMessage(data).queue(null) { discord.logger.warning("디스코드로 보내지 못했습니다: ${it.message}") }
    }

    fun message(text: String, mentions: Set<Message.MentionType> = SAFE_MENTIONS, embeds: List<MessageEmbed> = emptyList()): MessageCreateData? {
        if (text.isBlank() && embeds.isEmpty()) return null
        return MessageCreateBuilder()
            .setContent(text.take(Message.MAX_CONTENT_LENGTH))
            .setEmbeds(embeds.take(Message.MAX_EMBED_COUNT))
            .setAllowedMentions(mentions)
            .build()
    }

    fun embed(title: String, lines: List<String>, color: Int? = null): MessageEmbed =
        EmbedBuilder().setTitle(title.take(MessageEmbed.TITLE_MAX_LENGTH))
            .setDescription(lines.joinToString("\n").take(MessageEmbed.DESCRIPTION_MAX_LENGTH))
            .apply { if (color != null) setColor(color) }
            .build()

    /**
     * 꺼질 때(메인 스레드). 종료 알림과 주제를 보내고 끝날 때까지 기다린 다음 JDA 를 닫는다.
     * 기다리는 것은 최대 [SHUTDOWN_WAIT] — 디스코드가 느려도 서버 종료를 붙잡지 않는다.
     */
    fun stop() {
        topicTask?.cancel(false)
        val jda = jda
        if (jda != null) {
            val pending = ArrayList<CompletableFuture<*>>()
            val text = discord.settings.lifecycle.shutdown
            val chat = chatChannel()
            if (text.isNotBlank() && chat != null) message(text)?.let { pending += chat.sendMessage(it).submit() }
            pending += Topics(discord).update(shutdown = true)
            runCatching { CompletableFuture.allOf(*pending.toTypedArray()).get(SHUTDOWN_WAIT.toMillis(), TimeUnit.MILLISECONDS) }
            jda.shutdown()
            if (!runCatching { jda.awaitShutdown(SHUTDOWN_WAIT) }.getOrDefault(false)) jda.shutdownNow()
        }
        this.jda = null
        state = "꺼짐"
        executor.shutdownNow()
    }

    companion object {
        /** 게임 → 디스코드 기본 멘션 허용 — 사람·채널·이모지. @everyone·역할은 막는다. */
        val SAFE_MENTIONS: Set<Message.MentionType> = EnumSet.of(Message.MentionType.USER, Message.MentionType.CHANNEL, Message.MentionType.EMOJI)

        val SHUTDOWN_WAIT: Duration = Duration.ofSeconds(5)

        const val SLASH_LINK = "연결"
        const val SLASH_LINK_CODE = "코드"

        /** 설정의 `allowed-mentions` 이름 → JDA 종류. */
        fun mentionTypes(names: Set<String>): Set<Message.MentionType> {
            val out = EnumSet.noneOf(Message.MentionType::class.java)
            for (name in names) when (name.lowercase()) {
                "user" -> out += Message.MentionType.USER
                "role" -> out += Message.MentionType.ROLE
                "channel" -> out += Message.MentionType.CHANNEL
                "emoji", "emote" -> out += Message.MentionType.EMOJI
                "everyone" -> out += Message.MentionType.EVERYONE
                "here" -> out += Message.MentionType.HERE
            }
            return out
        }
    }
}
