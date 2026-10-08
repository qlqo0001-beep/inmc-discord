package com.inmc.discord.link

import java.security.SecureRandom
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * 연결 코드 — 네 자리 숫자(DiscordSRV 와 같은 모양이라 안내문을 그대로 쓴다).
 *
 * 게임에서 받고(메인) 디스코드 DM 으로 쓴다(JDA 스레드). 사람마다 하나 — 다시 받으면 옛 코드는 없어진다.
 * [now] 를 바꿀 수 있게 둔 것은 테스트용이다.
 */
class LinkCodes(private val now: () -> Long = System::currentTimeMillis) {

    private data class Pending(val player: UUID, val expiresAt: Long)

    private val codes = ConcurrentHashMap<String, Pending>()
    private val random = SecureRandom()

    fun issue(player: UUID, minutes: Int): String {
        purge()
        codes.entries.removeIf { it.value.player == player }
        val expires = now() + minutes * 60_000L
        while (true) {
            val code = String.format("%04d", random.nextInt(10_000))
            if (codes.putIfAbsent(code, Pending(player, expires)) == null) return code
        }
    }

    sealed interface Claim {
        data class Ok(val player: UUID) : Claim
        data object Invalid : Claim
        data object Unknown : Claim
    }

    /** 쓰면 없어진다. 네 자리 숫자가 아니면 [Claim.Invalid], 없거나 지났으면 [Claim.Unknown]. */
    fun claim(text: String): Claim {
        val code = text.trim()
        if (!FORMAT.matches(code)) return Claim.Invalid
        val pending = codes.remove(code) ?: return Claim.Unknown
        return if (pending.expiresAt < now()) Claim.Unknown else Claim.Ok(pending.player)
    }

    private fun purge() {
        val t = now()
        codes.entries.removeIf { it.value.expiresAt < t }
    }

    companion object {
        val FORMAT = Regex("^\\d{4}$")
    }
}
