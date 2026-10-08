package com.inmc.discord.link

import com.inmc.discord.Discord
import com.inmc.discord.Signals
import com.inmc.discord.relay.Texts
import kr.inmc.core.integration.TitleForgeNames
import net.dv8tion.jda.api.Permission
import org.bukkit.Bukkit
import org.bukkit.entity.Player
import org.bukkit.event.Event
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.Listener
import org.bukkit.event.player.PlayerJoinEvent
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * 연결한 사람의 디스코드 서버 별명 = 게임 닉네임(타이틀포지 표시 이름, 없으면 마크 이름) — 사용자 결정 2026-10-07.
 *
 * 계기는 셋: 연결할 때([LinkService]) · 접속하고 [JOIN_DELAY_TICKS] 뒤(타이틀포지가 프로필을 읽어 오는 동안 기다린다) ·
 * 타이틀포지 `NicknameChangeEvent` 다음 틱(그 이벤트는 바꾸기 **전에** 온다). 주기적으로 훑지 않는다.
 * 연결을 끊어도 별명은 그대로 둔다(사용자 결정).
 *
 * 디스코드는 서버 주인과 봇보다 높은 역할의 사람 별명을 못 바꾸게 한다 — 사람마다 한 번만 알리고 건너뛴다.
 * 접속할 때는 업적 신호 `discord/link`(join)도 쏜다([Signals]).
 */
class Nicknames(private val discord: Discord) : Listener {

    private val refused: MutableSet<Long> = ConcurrentHashMap.newKeySet()

    @Volatile
    private var warnedPermission = false

    /**
     * 메인 스레드. 연결 안 했거나 꺼져 있으면 아무것도 안 한다.
     * [report] 를 주면 결과를 알려 준다(관리자 시험 `/디스코드 관리 별명` — ok: true 됨 · false 안 됨 · null 할 일 없음). JDA 스레드에서 불릴 수 있다.
     */
    fun sync(uuid: UUID, name: String, report: ((Boolean?, String) -> Unit)? = null) {
        if (!discord.settings.link.syncNickname) return report?.invoke(false, "link.sync-nickname 이 꺼져 있음") ?: Unit
        val id = discord.links.discordOf(uuid) ?: return report?.invoke(null, "디스코드에 연결하지 않은 사람") ?: Unit
        val guild = discord.bot.guild() ?: return report?.invoke(false, "봇이 디스코드 서버에 붙어 있지 않음") ?: Unit
        val target = nickname(TitleForgeNames.displayName(uuid, name)).ifEmpty { return report?.invoke(false, "맞출 이름이 비었음") ?: Unit }
        val self = guild.selfMember
        if (!self.hasPermission(Permission.NICKNAME_MANAGE)) {
            if (!warnedPermission) discord.logger.warning("디스코드 별명을 맞추지 못합니다 — 봇에게 '별명 관리' 권한이 없습니다")
            warnedPermission = true
            return report?.invoke(false, "봇에게 '별명 관리' 권한이 없음") ?: Unit
        }
        guild.retrieveMemberById(id).queue({ member ->
            if (member.nickname == target) return@queue report?.invoke(null, "이미 '$target'") ?: Unit
            if (!self.canInteract(member)) {
                if (refused.add(id)) discord.logger.info("${member.user.name} 의 디스코드 별명은 바꾸지 않습니다 — 서버 주인이거나 봇보다 높은 역할이라 디스코드가 막습니다")
                return@queue report?.invoke(false, "${member.user.name} 은(는) 서버 주인이거나 봇보다 높은 역할 — 디스코드가 막음") ?: Unit
            }
            guild.modifyNickname(member, target).reason("inmc-discord: 게임 닉네임").queue({
                report?.invoke(true, "${member.user.name} → '$target'")
            }) {
                discord.logger.warning("${member.user.name} 의 디스코드 별명을 바꾸지 못했습니다: ${it.message}")
                report?.invoke(false, "디스코드가 거절: ${it.message}")
            }
        }) { report?.invoke(false, "디스코드 서버에 없는 사람") }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    fun onJoin(event: PlayerJoinEvent) {
        val player = event.player
        if (discord.links.discordOf(player.uniqueId) == null) return
        Signals.link(player.uniqueId, Signals.LINK_JOIN, player)
        Bukkit.getScheduler().runTaskLater(discord.plugin, Runnable {
            if (player.isOnline) sync(player.uniqueId, player.name)
        }, JOIN_DELAY_TICKS)
    }

    /**
     * 타이틀포지 닉네임 바꿈을 듣는다. 컴파일 의존 없이 그 플러그인의 클래스로더에서 이벤트 클래스를 찾는다
     * (업적 `ForeignEventBridge` 와 같은 방식). 없으면 아무것도 안 한다.
     */
    fun bindTitleForge() {
        val titleForge = Bukkit.getPluginManager().getPlugin("InMc-TitleForge") ?: return
        val type = runCatching { Class.forName(NICKNAME_EVENT, true, titleForge.javaClass.classLoader) }.getOrNull() ?: return
        if (!Event::class.java.isAssignableFrom(type)) return
        @Suppress("UNCHECKED_CAST")
        val eventClass = type as Class<out Event>
        runCatching {
            Bukkit.getPluginManager().registerEvent(eventClass, this, EventPriority.MONITOR, { _, event ->
                if (!eventClass.isInstance(event)) return@registerEvent
                val player = runCatching { event.javaClass.getMethod("getPlayer").invoke(event) as? Player }.getOrNull() ?: return@registerEvent
                // 이벤트는 바꾸기 전에 온다 — 다음 틱에 바뀐 이름을 읽는다.
                Bukkit.getScheduler().runTask(discord.plugin, Runnable { if (player.isOnline) sync(player.uniqueId, player.name) })
            }, discord.plugin, true)
        }.onFailure { discord.logger.warning("타이틀포지 닉네임 바꿈을 듣지 못합니다: ${it.message}") }
    }

    companion object {
        private const val NICKNAME_EVENT = "kr.inmc.titleforge.api.event.NicknameChangeEvent"
        private const val JOIN_DELAY_TICKS = 100L

        /** 디스코드 별명 한도. */
        const val MAX_LENGTH = 32

        /** 색 코드 없이, [MAX_LENGTH] 자까지. */
        fun nickname(name: String): String = Texts.truncate(Texts.stripLegacy(name).trim(), MAX_LENGTH).trim()
    }
}
