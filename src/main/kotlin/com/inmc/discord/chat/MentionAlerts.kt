package com.inmc.discord.chat

import com.inmc.discord.Discord
import com.inmc.discord.relay.Texts
import kr.inmc.core.integration.PlayerSettings
import kr.inmc.core.integration.TitleForgeNames
import net.kyori.adventure.bossbar.BossBar
import net.kyori.adventure.text.Component
import net.kyori.adventure.title.Title
import org.bukkit.Bukkit
import org.bukkit.Material
import org.bukkit.Registry
import org.bukkit.Sound
import org.bukkit.entity.Player
import java.time.Duration
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * 멘션 알림 — 소리 · 제목/부제 · 액션바 · 보스바. 받는 사람이 개인 설정 [Discord.SETTING_MENTION_ALERT] 를 끄면 안 울린다.
 * 화면에 닿는 일은 전부 받는 사람의 스레드(메인)에서.
 */
class MentionAlerts(private val discord: Discord) {

    private val lastSent = ConcurrentHashMap<UUID, Long>()

    fun register() {
        PlayerSettings.register(
            PlayerSettings.Setting(
                key = Discord.SETTING_MENTION_ALERT,
                owner = OWNER,
                label = "멘션 알림",
                icon = Material.BELL,
                description = listOf("채팅·디스코드에서 누가 나를 @ 로 부르면", "소리·제목·보스바로 알립니다"),
                kind = PlayerSettings.Toggle(true),
            ),
        )
    }

    fun unregister() {
        PlayerSettings.unregisterAll(OWNER)
    }

    /** 채팅 스레드. 보낸 사람마다 쿨타임 — 쿨타임 안이면 강조만 되고 소리·제목은 없다(InteractiveChat 과 같다). */
    fun fromGame(sender: Player, targets: Collection<UUID>) {
        if (targets.isEmpty()) return
        val mention = discord.settings.mention
        val now = System.currentTimeMillis()
        val last = lastSent[sender.uniqueId]
        if (last != null && now - last < mention.cooldownSeconds * 1000L) return
        lastSent[sender.uniqueId] = now
        val name = TitleForgeNames.displayName(sender.uniqueId, sender.name)
        for (uuid in targets) {
            if (uuid == sender.uniqueId) continue
            val target = Bukkit.getPlayer(uuid) ?: continue
            discord.runFor(target) {
                alert(target, Texts.mini(mention.subtitle, "sender" to name), Texts.mini(mention.bossbarText, "sender" to name))
            }
        }
    }

    /** 메인 스레드 — 디스코드에서 연결된 사람을 멘션했다. */
    fun fromDiscord(target: Player, user: String, channel: String) {
        val mention = discord.settings.mention
        alert(
            target,
            Texts.mini(mention.discordSubtitle, "user" to user, "channel" to channel),
            Texts.mini(mention.bossbarText, "sender" to user),
        )
    }

    fun forget(player: UUID) {
        lastSent.remove(player)
    }

    private fun alert(target: Player, subtitle: Component, bossbar: Component) {
        if (!target.isOnline || !PlayerSettings.enabled(target.uniqueId, Discord.SETTING_MENTION_ALERT, true)) return
        val mention = discord.settings.mention
        sound(mention.sound)?.let { target.playSound(target.location, it, 1f, 1f) }
        if (mention.title.isNotBlank() || Texts.plain(subtitle).isNotEmpty()) {
            val stay = Duration.ofMillis((mention.titleSeconds * 1000).toLong().coerceAtLeast(0))
            target.showTitle(Title.title(Texts.mini(mention.title), subtitle, Title.Times.times(Duration.ofMillis(100), stay, Duration.ofMillis(300))))
        }
        if (mention.actionbar.isNotBlank()) target.sendActionBar(Texts.mini(mention.actionbar))
        if (mention.bossbarText.isNotBlank() && mention.bossbarSeconds > 0) {
            val bar = BossBar.bossBar(bossbar, 1f, enumOr(mention.bossbarColor, BossBar.Color.YELLOW), enumOr(mention.bossbarOverlay, BossBar.Overlay.PROGRESS))
            target.showBossBar(bar)
            Bukkit.getScheduler().runTaskLater(discord.plugin, Runnable { target.hideBossBar(bar) }, (mention.bossbarSeconds * 20).toLong())
        }
    }

    private companion object {

        const val OWNER = "inmc-discord"

        /** `ENTITY_EXPERIENCE_ORB_PICKUP`(Bukkit 이름) 이나 `entity.experience_orb.pickup`(키) 둘 다. */
        fun sound(name: String): Sound? {
            if (name.isBlank()) return null
            if ('.' in name || ':' in name) {
                return runCatching { Registry.SOUNDS.get(org.bukkit.NamespacedKey.fromString(name.lowercase())!!) }.getOrNull()
            }
            return runCatching { Sound::class.java.getField(name.uppercase()).get(null) as Sound }.getOrNull()
        }

        inline fun <reified E : Enum<E>> enumOr(name: String, fallback: E): E =
            runCatching { enumValueOf<E>(name.uppercase()) }.getOrDefault(fallback)
    }
}
