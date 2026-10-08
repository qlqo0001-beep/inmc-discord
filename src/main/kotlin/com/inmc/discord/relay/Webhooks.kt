package com.inmc.discord.relay

import com.inmc.discord.Discord
import net.dv8tion.jda.api.entities.Message
import net.dv8tion.jda.api.entities.WebhookClient
import net.dv8tion.jda.api.entities.channel.concrete.TextChannel
import net.dv8tion.jda.api.utils.messages.MessageCreateBuilder
import net.dv8tion.jda.api.utils.messages.MessageCreateData
import java.util.concurrent.CompletableFuture

/**
 * 웹훅 전달 — 게임 채팅을 메시지마다 그 플레이어의 이름·얼굴로(DiscordSRV 의 Experiment_WebhookChatMessageDelivery).
 *
 * - `chat.webhook.url` 이 있으면 **그 웹훅**으로 보낸다(이미 있는 웹훅 — 다른 채널일 수 있다). 남이 만든 웹훅일 수 있어 선택 메뉴·단추는 떼고 보낸다
 *   (디스코드는 봇이 만든 웹훅에서만 받는다).
 * - 비어 있으면 채팅 채널에 **우리 봇이 만든 [NAME] 하나**를 찾아 쓰고, 없으면 만든다(채널 권한 "웹후크 관리" 필요). 선택 메뉴도 붙는다.
 *
 * 보내지 못하면 부르는 쪽이 봇 메시지로 대신 보낸다. 디스코드 → 게임 중계는 이 웹훅의 메시지를 받지 않는다([isOurs]) — 안 그러면 메아리친다.
 * **웹훅 주소는 비밀값**(토큰이 들어 있다) — 로그·메시지에 내지 않는다.
 */
class Webhooks(private val discord: Discord) {

    @Volatile
    private var created: WebhookClient<Message>? = null

    @Volatile
    private var fromUrl: Pair<String, WebhookClient<Message>>? = null

    @Volatile
    private var warned = false

    fun isOurs(authorId: Long): Boolean = created?.idLong == authorId || fromUrl?.second?.idLong == authorId

    /** 주소로 연 웹훅인가(선택 메뉴를 붙일 수 없다). */
    val external: Boolean get() = discord.settings.chat.webhook.url.isNotBlank()

    /** 보낼 웹훅. 못 쓰면 null(경고 한 번). */
    fun ensure(channel: TextChannel): CompletableFuture<WebhookClient<Message>?> {
        val url = discord.settings.chat.webhook.url.trim()
        if (url.isNotEmpty()) {
            fromUrl?.takeIf { it.first == url }?.let { return CompletableFuture.completedFuture(it.second) }
            val client = runCatching { WebhookClient.createClient(channel.jda, url) }.getOrElse {
                warnOnce("chat.webhook.url 이 웹훅 주소 모양이 아닙니다 — 봇 이름으로 보냅니다")
                return CompletableFuture.completedFuture(null)
            }
            fromUrl = url to client
            return CompletableFuture.completedFuture(client)
        }
        created?.let { hook ->
            if ((hook as? net.dv8tion.jda.api.entities.Webhook)?.channel?.idLong == channel.idLong) return CompletableFuture.completedFuture(hook)
        }
        val self = channel.jda.selfUser.idLong
        return channel.retrieveWebhooks().submit()
            .thenCompose { hooks ->
                val mine = hooks.firstOrNull { it.name == NAME && it.ownerAsUser?.idLong == self }
                if (mine != null) CompletableFuture.completedFuture(mine) else channel.createWebhook(NAME).submit()
            }
            .handle { hook, error ->
                if (error != null) {
                    warnOnce("채팅 채널에 웹훅을 만들지 못해 봇 이름으로 보냅니다(봇의 '웹후크 관리' 권한을 확인하세요): ${error.message}")
                    null
                } else {
                    warned = false
                    created = hook
                    hook
                }
            }
    }

    /** @return 보냈으면 true. 실패하면 false(부르는 쪽이 봇으로). */
    fun send(channel: TextChannel, data: MessageCreateData, username: String, avatarUrl: String): CompletableFuture<Boolean> =
        ensure(channel).thenCompose { hook ->
            if (hook == null) return@thenCompose CompletableFuture.completedFuture(false)
            val payload = if (external && data.components.isNotEmpty()) MessageCreateBuilder.from(data).setComponents(emptyList()).build() else data
            val action = hook.sendMessage(payload).setUsername(username)
            if (avatarUrl.isNotBlank()) action.setAvatarUrl(avatarUrl)
            action.submit().handle { _, error ->
                // 오류 글에 주소가 들어가지 않게 — 종류만.
                if (error != null) discord.logger.warning("웹훅으로 보내지 못해 봇 이름으로 보냅니다: ${error.javaClass.simpleName} ${(error as? net.dv8tion.jda.api.exceptions.ErrorResponseException)?.meaning.orEmpty()}")
                error == null
            }
        }

    private fun warnOnce(text: String) {
        if (warned) return
        warned = true
        discord.logger.warning(text)
    }

    companion object {
        /** 디스코드는 웹훅 이름에 "discord" 를 받지 않는다(USERNAME_INVALID_CONTAINS) — 그래서 `inmc-discord` 가 아니다. */
        const val NAME = "inmc-relay"

        /**
         * 디스코드가 웹훅 이름으로 받지 않는 것을 피한다 — 1~80자, "discord"·"clyde" 를 담을 수 없다, 백틱 셋·@·#·: 은 빼서.
         * 비면 [fallback](실명).
         */
        fun username(raw: String, fallback: String): String {
            var name = raw.replace("```", "").replace("@", "").replace("#", "").replace(":", "").trim()
            for (word in listOf("discord", "clyde")) {
                name = Regex(Regex.escape(word), RegexOption.IGNORE_CASE).replace(name) { m -> m.value[0] + "​" + m.value.substring(1) }
            }
            name = name.take(80).trim()
            return if (name.isEmpty()) fallback.take(80) else name
        }

        /**
         * DiscordSRV `AvatarUrl` 과 같은 토큰 — {texture} {username} {uuid} {uuid-nodashes} {size}. 더해서 {skin} = 스킨 해시, 없으면 uuid
         * (mc-heads 는 둘 다 받는다 — 해시면 CMI 로 바꾼 스킨도 그대로).
         */
        fun avatar(format: String, uuid: java.util.UUID, name: String, texture: String?, size: Int): String =
            format.replace("{skin}", texture?.takeIf(String::isNotBlank) ?: uuid.toString().replace("-", ""))
                .replace("{texture}", texture.orEmpty())
                .replace("{username}", name)
                .replace("{uuid-nodashes}", uuid.toString().replace("-", ""))
                .replace("{uuid}", uuid.toString())
                .replace("{size}", size.toString())
    }
}
