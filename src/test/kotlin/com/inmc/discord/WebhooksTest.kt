package com.inmc.discord

import com.inmc.discord.config.Migration
import com.inmc.discord.config.Settings
import com.inmc.discord.relay.Webhooks
import org.bukkit.configuration.file.YamlConfiguration
import java.util.UUID
import java.util.logging.Logger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class WebhooksTest {

    @Test
    fun `웹훅 이름은 디스코드가 받는 모양으로`() {
        assertEquals("[칭호] 철수", Webhooks.username("[칭호] 철수", "steve"))
        // "discord"·"clyde" 는 담을 수 없다 — 글자 사이를 끊는다(보이는 글은 같다)
        val name = Webhooks.username("Discord왕 clyde", "steve")
        assertTrue("discord" !in name.lowercase() && "clyde" !in name.lowercase(), name)
        assertEquals("steve", Webhooks.username("  @#:  ", "steve"))
        assertEquals(80, Webhooks.username("가".repeat(100), "steve").length)
    }

    @Test
    fun `얼굴 주소 토큰은 DiscordSRV 와 같다`() {
        val uuid = UUID.fromString("069a79f4-44e9-4726-a5be-fca90e38aaf5")
        assertEquals("https://mc-heads.net/avatar/abc/128", Webhooks.avatar(Settings.Webhook().avatarUrl, uuid, "Notch", "abc", 128))
        // 스킨이 없으면 {skin} 은 uuid
        assertEquals("https://mc-heads.net/avatar/069a79f444e94726a5befca90e38aaf5/128", Webhooks.avatar(Settings.Webhook().avatarUrl, uuid, "Notch", null, 128))
        assertEquals("https://x/Notch/069a79f4-44e9-4726-a5be-fca90e38aaf5/", Webhooks.avatar("https://x/{username}/{uuid}/{texture}", uuid, "Notch", null, 64))
    }

    @Test
    fun `DiscordSRV 웹훅 설정을 옮긴다`() {
        val srv = YamlConfiguration().apply {
            set("Experiment_WebhookChatMessageDelivery", true)
            set("Experiment_WebhookChatMessageUsernameFormat", "%displayname%")
            set("Experiment_WebhookChatMessageFormat", "%message%")
            set("AvatarUrl", "https://crafatar.com/avatars/{uuid-nodashes}?size={size}")
        }
        val target = YamlConfiguration()
        Migration.fromDiscordSrv(srv, YamlConfiguration(), target, YamlConfiguration())
        val hook = Settings.from(target, Logger.getAnonymousLogger()).chat.webhook
        assertTrue(hook.enabled)
        assertEquals("https://crafatar.com/avatars/{uuid-nodashes}?size={size}", hook.avatarUrl)
    }

    @Test
    fun `없는 mc-heads avatars 주소는 옮기지 않는다`() {
        val srv = YamlConfiguration().apply { set("AvatarUrl", "https://mc-heads.net/avatars/{uuid-nodashes}?size={size}&overlay#{texture}") }
        val target = YamlConfiguration()
        Migration.fromDiscordSrv(srv, YamlConfiguration(), target, YamlConfiguration())
        assertEquals(Settings.Webhook().avatarUrl, Settings.from(target, Logger.getAnonymousLogger()).chat.webhook.avatarUrl)
    }
}
