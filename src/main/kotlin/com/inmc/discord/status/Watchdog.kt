package com.inmc.discord.status

import com.inmc.discord.Discord
import com.inmc.discord.relay.Texts
import net.dv8tion.jda.api.entities.Message
import org.bukkit.Bukkit
import java.util.EnumSet

/**
 * 서버 멈춤 알림. 메인 스레드가 1초마다 심장을 뛰게 하고, 데몬 스레드 하나가 그것을 본다.
 * [WatchState] 가 언제 알릴지를 정한다(순수 — `WatchdogTest`). 멈춘 동안에도 JDA 는 자기 스레드라 보낼 수 있다.
 */
class Watchdog(private val discord: Discord) {

    @Volatile
    private var lastBeat = System.currentTimeMillis()

    @Volatile
    private var armed = false

    private var thread: Thread? = null

    private val state = WatchState()

    /** 서버가 다 켜진 뒤에(켜는 동안의 긴 틱은 멈춤이 아니다). */
    fun arm() {
        Bukkit.getScheduler().runTaskTimer(discord.plugin, Runnable { lastBeat = System.currentTimeMillis() }, 0L, 20L)
        lastBeat = System.currentTimeMillis()
        armed = true
        thread = Thread(::loop, "inmc-discord-watchdog").apply {
            isDaemon = true
            start()
        }
    }

    fun stop() {
        armed = false
        thread?.interrupt()
        thread = null
    }

    private fun loop() {
        while (armed) {
            try {
                Thread.sleep(1000)
            } catch (_: InterruptedException) {
                return
            }
            val settings = discord.settings.watchdog
            if (!settings.enabled) continue
            when (val step = state.observe(System.currentTimeMillis(), lastBeat, settings.timeoutSeconds * 1000L)) {
                is WatchState.Step.Stalled -> {
                    discord.panel.hang(lastBeat)
                    repeat(settings.messageCount) { send(settings.message, 0) }
                }
                is WatchState.Step.Recovered -> {
                    discord.panel.recovered()
                    send(settings.recovered, step.seconds)
                }
                WatchState.Step.None -> Unit
            }
        }
    }

    private fun send(format: String, seconds: Long) {
        if (format.isBlank()) return
        val bot = discord.bot
        val channel = bot.chatChannel() ?: return
        val owner = channel.guild.ownerIdLong
        val text = Texts.tokens(format, mapOf("date" to discord.date(), "guildowner" to "<@$owner>", "seconds" to seconds.toString()))
        bot.send(channel, text, EnumSet.of(Message.MentionType.USER))
    }
}

/**
 * 멈춤 판정. 마지막 심장이 [timeout] 보다 오래되면 한 번 [Step.Stalled], 다시 뛰면 한 번 [Step.Recovered].
 */
class WatchState {

    sealed interface Step {
        data object None : Step
        data object Stalled : Step
        data class Recovered(val seconds: Long) : Step
    }

    private var stalledSince: Long? = null

    fun observe(now: Long, lastBeat: Long, timeout: Long): Step {
        val stalled = now - lastBeat > timeout
        val since = stalledSince
        return when {
            stalled && since == null -> {
                stalledSince = lastBeat
                Step.Stalled
            }
            !stalled && since != null -> {
                stalledSince = null
                Step.Recovered((lastBeat - since) / 1000)
            }
            else -> Step.None
        }
    }
}
