package com.inmc.discord.bot

import com.inmc.discord.Discord
import com.inmc.discord.render.model.ItemView
import com.inmc.discord.status.PanelLayout
import com.inmc.discord.util.Ph
import net.dv8tion.jda.api.EmbedBuilder
import net.dv8tion.jda.api.Permission
import net.dv8tion.jda.api.entities.channel.concrete.TextChannel
import net.dv8tion.jda.api.requests.GatewayIntent
import net.dv8tion.jda.api.utils.FileUpload
import net.dv8tion.jda.api.utils.messages.MessageCreateBuilder
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.NamedTextColor
import org.bukkit.command.CommandSender
import java.util.concurrent.TimeUnit

/**
 * `/디스코드 관리 검증 [보내기]` — 디스코드 쪽이 실제로 되는지 서버 안에서 확인한다(봇 일꾼에서, 메인을 기다리게 하지 않는다).
 *
 * 연결·채널·채널 권한·인텐트·슬래시 명령어·연결 역할·번역·그림·웹훅. `보내기` 면 채팅 채널에 시험 메시지(그림 첨부)를 지금 설정의 길
 * (웹훅이나 봇)로 보내고, 디스코드에서 그 메시지를 다시 읽어 확인한 뒤 [CLEANUP_SECONDS] 초 뒤 지운다.
 * 결과는 부른 사람에게 줄마다(통과 ✔ · 실패 ✘ · 주의 !).
 */
class Verifier(private val discord: Discord) {

    private class Result(val name: String, val ok: Boolean?, val detail: String = "")

    fun run(sender: CommandSender, send: Boolean) {
        discord.messages.send(sender, "verify-start")
        discord.bot.scheduler.execute {
            val results = runCatching { checks(send) }.getOrElse { listOf(Result("검증", false, it.message.orEmpty())) }
            discord.runMain { report(sender, results) }
        }
    }

    private fun checks(send: Boolean): List<Result> {
        val out = ArrayList<Result>()
        val settings = discord.settings
        val jda = discord.bot.jda
        out += Result("봇 연결", jda != null, discord.bot.state)
        if (jda == null) return out + panelChecks(null) + renderChecks()

        out += Result("멤버 인텐트", if (GatewayIntent.GUILD_MEMBERS in jda.gatewayIntents) true else null,
            if (GatewayIntent.GUILD_MEMBERS in jda.gatewayIntents) "" else "꺼짐 — @이름 은 연결 계정만 멘션")
        val chat = discord.bot.chatChannel()
        out += Result("채팅 채널", chat != null, chat?.let { "#${it.name}" } ?: "${settings.chatChannel} 을 못 찾음")
        val console = discord.bot.consoleChannel()
        if (settings.consoleChannel != null) out += Result("콘솔 채널", console != null, console?.let { "#${it.name}" } ?: "${settings.consoleChannel} 을 못 찾음")

        chat?.let { channel ->
            val required = mutableListOf(
                Permission.VIEW_CHANNEL, Permission.MESSAGE_SEND, Permission.MESSAGE_EMBED_LINKS,
                Permission.MESSAGE_ATTACH_FILES, Permission.MESSAGE_HISTORY,
            )
            if (settings.chat.webhook.enabled) required += Permission.MANAGE_WEBHOOKS
            out += permissions("채팅 채널 권한", channel, required)
            out += permissions("채팅 채널 주제·접속목록 지우기", channel, listOf(Permission.MANAGE_CHANNEL, Permission.MESSAGE_MANAGE), soft = true)
        }
        console?.let { out += permissions("콘솔 채널 권한", it, listOf(Permission.VIEW_CHANNEL, Permission.MESSAGE_SEND, Permission.MESSAGE_HISTORY)) }

        val guild = discord.bot.guild()
        if (guild != null) {
            val expected = listOf(Bot.SLASH_LINK) + discord.slash.definitions().map { it.name }
            val registered = runCatching { guild.retrieveCommands().complete().map { it.name }.toSet() }.getOrNull()
            out += if (registered == null) Result("슬래시 명령어", false, "목록을 못 읽음")
            else Result("슬래시 명령어", expected.all { it in registered }, (expected - registered).joinToString().ifEmpty { expected.joinToString(" · ") { "/$it" } })

            val roleName = settings.link.role
            if (roleName.isNotBlank()) {
                val role = guild.getRolesByName(roleName, true).firstOrNull()
                out += when {
                    role == null -> Result("연결 역할", null, "'$roleName' 이 디스코드 서버에 없음 — 역할 없이 연결만 된다")
                    !guild.selfMember.canInteract(role) -> Result("연결 역할", false, "봇 역할이 '$roleName' 보다 아래")
                    !guild.selfMember.hasPermission(Permission.MANAGE_ROLES) -> Result("연결 역할", false, "역할 관리 권한 없음")
                    else -> Result("연결 역할", true, roleName)
                }
            }
            if (settings.link.syncNickname) {
                val can = guild.selfMember.hasPermission(Permission.NICKNAME_MANAGE)
                out += Result("별명 맞추기", can, if (can) "서버 주인·봇보다 높은 역할은 디스코드가 막음" else "봇에게 '별명 관리' 권한 없음")
            }
        }
        out += Result("연결 계정", true, "${discord.links.count()}명")
        out += panelChecks(jda)
        out += renderChecks()

        if (chat != null && settings.chat.webhook.enabled) {
            val hook = runCatching { discord.webhooks.ensure(chat).get(10, TimeUnit.SECONDS) }.getOrNull()
            out += Result("웹훅", hook != null, when {
                hook == null -> "만들지 못함 — 서버 로그를 보세요"
                discord.webhooks.external -> "chat.webhook.url 의 웹훅 (${hook.id})"
                else -> "${(hook as? net.dv8tion.jda.api.entities.Webhook)?.name} (${hook.id})"
            })
        }
        if (send && chat != null) out += sendTest(chat)
        return out
    }

    private fun permissions(name: String, channel: TextChannel, perms: List<Permission>, soft: Boolean = false): Result {
        val self = channel.guild.selfMember
        val missing = perms.filterNot { self.hasPermission(channel, it) }
        return Result(name, if (missing.isEmpty()) true else if (soft) null else false, missing.joinToString { it.getName() })
    }

    /** 서버 현황 패널(2026-10-09) — 채널·권한·알림 역할, 그리고 접속자 200명으로 네 상태를 그려 디스코드 한도 안인지. */
    private fun panelChecks(jda: net.dv8tion.jda.api.JDA?): List<Result> {
        val out = ArrayList<Result>()
        val panel = discord.settings.panel
        out += Result("서버 현황 패널", if (panel.channel == null) null else true, discord.panel.describe())
        if (jda != null && panel.channel != null) {
            val channel = jda.getChannelById(net.dv8tion.jda.api.entities.channel.middleman.GuildMessageChannel::class.java, panel.channel)
            out += Result("패널 채널", channel != null, channel?.let { "#${it.name}" } ?: "${panel.channel} 을 못 찾음")
            if (channel != null) {
                val self = channel.guild.selfMember
                val missing = listOf(Permission.VIEW_CHANNEL, Permission.MESSAGE_SEND, Permission.MESSAGE_EMBED_LINKS).filterNot { self.hasPermission(channel, it) }
                out += Result("패널 채널 권한", missing.isEmpty(), missing.joinToString { it.getName() })
                val history = self.hasPermission(channel, Permission.MESSAGE_HISTORY)
                out += Result("패널 정리(메시지 기록 보기)", if (history) true else null, if (history) "켤 때 남은 패널을 하나로" else "권한 없음 — 패널이 둘이 되면 손으로 지워야 함")
            }
        }
        val guild = discord.bot.guild()
        if (panel.notifyRole.isNotBlank() && guild != null) {
            val role = guild.getRolesByName(panel.notifyRole, true).firstOrNull()
            out += when {
                role == null -> Result("패널 알림 역할", false, "'${panel.notifyRole}' 이 디스코드 서버에 없음")
                !guild.selfMember.canInteract(role) -> Result("패널 알림 역할", false, "봇 역할이 '${panel.notifyRole}' 보다 아래")
                !guild.selfMember.hasPermission(Permission.MANAGE_ROLES) -> Result("패널 알림 역할", false, "역할 관리 권한 없음")
                else -> Result("패널 알림 역할", true, panel.notifyRole)
            }
        }
        val design = discord.panelDesign
        out += Result("패널 모양 파일", design.fields.isNotEmpty(), if (design.fields.isEmpty()) "panel.yml 에 칸(fields)이 없음" else "칸 ${design.fields.size}개 · 링크 단추 ${design.links.size}개")
        val names = (1..200).map { "Verify_Long_Name_$it" }
        val gathered = PanelLayout.Gathered(names, 200, 200, 19.5, "26.2", PanelLayout.Today(250, 200))
        val now = System.currentTimeMillis()
        val problems = PanelLayout.State.entries.mapNotNull { state ->
            val view = PanelLayout.view(PanelLayout.Data(state, gathered, now - 3_600_000, now, now - 45_000, "검증", now), design, panel.refreshSeconds)
            val total = view.title.length + view.footer.length + view.fields.sumOf { it.name.length + it.value.length }
            when {
                view.fields.any { it.value.length > PanelLayout.FIELD_VALUE_MAX || it.name.length > PanelLayout.FIELD_NAME_MAX } -> "$state 필드가 한도를 넘음"
                total > EMBED_TOTAL_MAX -> "$state 글자 $total (${EMBED_TOTAL_MAX} 넘음)"
                else -> null
            }
        }
        out += Result("패널 모양", problems.isEmpty(), problems.joinToString().ifEmpty { "상태 4가지 · 접속자 200명 · 한도 안" })
        return out
    }

    private fun renderChecks(): List<Result> {
        val out = ArrayList<Result>()
        out += Result("번역", discord.lang.has("item.minecraft.diamond_sword"), discord.settings.render.language)
        out += Result("아이템 그림", discord.renderer.ready, discord.renderer.state)
        if (discord.renderer.ready) {
            val png = runCatching { testImage().get(20, TimeUnit.SECONDS) }.getOrNull()
            out += Result("그림 그리기", png != null, png?.let { "${it.size / 1024}KB" } ?: "실패")
        }
        return out
    }

    private fun testImage() = discord.renderer.itemPng(
        ItemView("minecraft:diamond_sword", glint = true),
        listOf(
            Component.translatable("item.minecraft.diamond_sword").color(NamedTextColor.AQUA),
            Component.text("inmc-discord 검증").color(NamedTextColor.GRAY),
        ),
    )

    /** 지금 설정의 길로 보내고, 디스코드에서 다시 읽어 확인하고, 지운다. */
    private fun sendTest(chat: TextChannel): List<Result> {
        val png = runCatching { testImage().get(20, TimeUnit.SECONDS) }.getOrNull()
        fun data() = MessageCreateBuilder()
            .setContent(discord.messages.discord("discord-verify-message"))
            .apply {
                if (png != null) {
                    setFiles(FileUpload.fromData(png, "verify.png"))
                    setEmbeds(EmbedBuilder().setImage("attachment://verify.png").build())
                }
            }
            .build()
        val hook = discord.settings.chat.webhook
        val message = runCatching {
            if (hook.enabled) {
                val webhook = discord.webhooks.ensure(chat).get(10, TimeUnit.SECONDS) ?: error("웹훅 없음")
                webhook.sendMessage(data()).setUsername(com.inmc.discord.relay.Webhooks.username("inmc 검증", "inmc")).submit().get(15, TimeUnit.SECONDS)
            } else chat.sendMessage(data()).submit().get(15, TimeUnit.SECONDS)
        }
        val sent = message.getOrNull() ?: return listOf(Result("보내기", false, message.exceptionOrNull()?.message.orEmpty()))
        val readBack = runCatching { chat.retrieveMessageById(sent.idLong).complete() }.getOrNull()
        // 임베드가 `attachment://` 로 쓴 파일은 디스코드가 `attachments` 에 넣지 않고 임베드 그림 주소(CDN)로만 돌려준다.
        val image = readBack != null && (readBack.attachments.any { it.fileName == "verify.png" } ||
            readBack.embeds.any { it.image?.url?.contains("verify.png") == true })
        val out = listOf(
            Result("보내기", true, if (hook.enabled) "웹훅" else "봇"),
            Result("디스코드에서 다시 읽기", readBack != null && image == (png != null),
                when {
                    readBack == null -> "못 읽음"
                    image -> "그림 있음"
                    else -> "그림 없음"
                }),
        )
        chat.deleteMessageById(sent.idLong).queueAfter(CLEANUP_SECONDS, TimeUnit.SECONDS, null) { }
        return out
    }

    private fun report(sender: CommandSender, results: List<Result>) {
        val messages = discord.messages
        for (r in results) {
            val key = when (r.ok) {
                true -> "verify-ok"
                false -> "verify-fail"
                null -> "verify-warn"
            }
            messages.sendRaw(sender, messages.raw(key), Ph.of().name(r.name).value(r.detail))
        }
        val failed = results.count { it.ok == false }
        messages.send(sender, "verify-done", Ph.of().count(results.count { it.ok == true }).value(failed.toString()))
    }

    private companion object {
        const val CLEANUP_SECONDS = 10L

        /** 디스코드 임베드 하나의 글자 합 한도. */
        const val EMBED_TOTAL_MAX = 6000
    }
}
