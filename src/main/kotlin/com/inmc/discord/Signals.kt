package com.inmc.discord

import com.inmc.discord.chat.SnapshotKind
import kr.inmc.core.event.InmcSignalEvent
import kr.inmc.core.event.SignalCatalog
import org.bukkit.entity.Player
import java.util.UUID

/**
 * 다른 플러그인(업적)에 내놓는 신호 — core `InmcSignalEvent` · `SignalCatalog`. 업적은 우리를 모르고 우리도 업적을 모른다.
 *
 * | source/type | 언제 | subject |
 * |---|---|---|
 * | `discord/link` | 계정을 연결했을 때 · 연결된 채로 접속했을 때 | `new` · `join` |
 * | `discord/chat` | 연결한 사람이 디스코드 채팅 채널에서 게임으로 글을 보냈을 때(오프라인이어도) | `chat` |
 * | `discord/share` | 연결한 사람이 슬래시 `/아이템`·`/인벤`·`/엔더` 로 **자기 것**을 공유했을 때 | `item` · `inventory` · `ender` |
 *
 * `link` 를 접속 때도 쏘는 까닭: 업적은 "지금 연결돼 있나"를 물을 길이 없고 신호만 받는다. 이 플러그인 전에 연결한 사람
 * (DiscordSRV 에서 옮긴 계정)도 다음 접속에 "연결" 업적을 받게 한다. 그래서 `link` 의 수는 연결 횟수가 아니다 — 단계는 걸지 말 것.
 *
 * 전부 메인 스레드(core 가 단언한다).
 */
object Signals {

    const val SOURCE = "discord"
    const val LINK_NEW = "new"
    const val LINK_JOIN = "join"

    fun register() {
        SignalCatalog.register(
            source = SOURCE, type = "link",
            subjectLabel = "계기",
            subjects = { listOf(LINK_NEW to "새로 연결", LINK_JOIN to "연결된 채로 접속") },
            description = "디스코드 계정을 연결했을 때 · 연결된 채로 접속했을 때 (목표 1 로만 — 접속마다 셉니다)",
        )
        SignalCatalog.register(
            source = SOURCE, type = "chat",
            subjectLabel = "채팅",
            subjects = { listOf("chat" to "디스코드 채팅") },
            description = "연결한 사람이 디스코드에서 게임으로 채팅했을 때 (오프라인이어도)",
        )
        SignalCatalog.register(
            source = SOURCE, type = "share",
            subjectLabel = "공유한 것",
            subjects = { listOf("item" to "아이템", "inventory" to "가방", "ender" to "엔더 상자") },
            description = "디스코드 슬래시 /아이템·/인벤·/엔더 로 자기 것을 공유했을 때",
        )
    }

    fun unregister() = SignalCatalog.unregisterAll(SOURCE)

    fun link(uuid: UUID, subject: String, player: Player?) =
        InmcSignalEvent.fire(SOURCE, "link", uuid, subject, player = player)

    fun chat(uuid: UUID) = InmcSignalEvent.fire(SOURCE, "chat", uuid, "chat")

    fun share(player: Player, kind: SnapshotKind) = InmcSignalEvent.fire(
        SOURCE, "share", player.uniqueId,
        when (kind) {
            SnapshotKind.ITEM -> "item"
            SnapshotKind.INVENTORY -> "inventory"
            SnapshotKind.ENDER -> "ender"
        },
        player = player,
    )
}
