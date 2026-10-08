package com.inmc.discord.chat

import io.papermc.paper.inventory.tooltip.TooltipContext
import net.kyori.adventure.text.Component
import org.bukkit.entity.Player
import org.bukkit.inventory.ItemStack
import java.security.SecureRandom
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * 채팅에 [item]·[inv]·[ender] 를 친 순간의 사본. 보기 화면과 디스코드 그림이 이것을 쓴다 — 나중에 가방이 바뀌어도
 * 친 순간 그대로 보인다. [Snapshot.take] 는 **메인 스레드**(아이템을 읽고 툴팁 줄을 만든다), 꺼내기는 아무 스레드.
 */
class Snapshots(private val now: () -> Long = System::currentTimeMillis) {

    private val all = ConcurrentHashMap<String, Snapshot>()
    private val random = SecureRandom()

    /** 넣고 id 를 준다(스냅샷의 [Snapshot.id] 에도 적는다 — 디스코드 단추가 이 id 로 찾아온다). */
    fun put(snapshot: Snapshot, minutes: Int): String {
        purge(minutes)
        while (true) {
            val id = (1..ID_LENGTH).map { ALPHABET[random.nextInt(ALPHABET.length)] }.joinToString("")
            if (all.putIfAbsent(id, snapshot) == null) {
                snapshot.id = id
                return id
            }
        }
    }

    /** 없거나 [minutes] 가 지났으면 null. */
    fun get(id: String, minutes: Int): Snapshot? {
        val snapshot = all[id] ?: return null
        if (now() - snapshot.created > minutes * 60_000L) {
            all.remove(id)
            return null
        }
        return snapshot
    }

    fun size(): Int = all.size

    private fun purge(minutes: Int) {
        val limit = now() - minutes * 60_000L
        all.entries.removeIf { it.value.created < limit }
    }

    private companion object {
        const val ID_LENGTH = 8
        const val ALPHABET = "abcdefghijkmnpqrstuvwxyz23456789"
    }
}

enum class SnapshotKind { ITEM, INVENTORY, ENDER }

class Snapshot(
    val kind: SnapshotKind,
    val owner: UUID,
    val ownerName: String,
    /** ITEM 은 한 칸, INVENTORY 는 `PlayerInventory.contents`(가방 36 + 갑옷 4 + 왼손 1), ENDER 는 27칸. */
    val items: List<ItemStack?>,
    val level: Int,
    /** 칸마다의 툴팁 줄(바닐라가 만드는 그대로). 빈 칸은 빈 목록. 디스코드 그림·글이 쓴다. */
    val tooltips: List<List<Component>>,
    /** 칸마다의 그림용 값([items] 와 같은 순서). */
    val views: List<com.inmc.discord.render.model.ItemView?> = emptyList(),
    /** 손에 든 단축바 칸(가방 그림의 오른손). */
    val mainHandSlot: Int = 0,
    val skinUrl: String? = null,
    val slim: Boolean = false,
    val created: Long = System.currentTimeMillis(),
) {

    /** [Snapshots.put] 이 정한다. */
    @Volatile
    var id: String = ""

    companion object {

        /** 메인 스레드. [slot] 은 ITEM 일 때 손 대신 볼 가방 칸(디스코드 `/아이템 칸`). */
        fun take(player: Player, kind: SnapshotKind, slot: Int? = null): Snapshot {
            val items: List<ItemStack?> = when (kind) {
                SnapshotKind.ITEM -> listOf((if (slot != null) player.inventory.getItem(slot) else player.inventory.itemInMainHand)?.clone())
                SnapshotKind.INVENTORY -> player.inventory.contents.map { it?.clone() }
                SnapshotKind.ENDER -> player.enderChest.contents.map { it?.clone() }
            }
            val context = TooltipContext.create(false, player.gameMode == org.bukkit.GameMode.CREATIVE)
            val tooltips = items.map { stack ->
                if (stack == null || stack.isEmpty) emptyList() else runCatching { stack.computeTooltipLines(context, player) }.getOrDefault(emptyList())
            }
            val (skin, slim) = ItemViews.skinOf(player)
            return Snapshot(
                kind, player.uniqueId, player.name, items, player.level, tooltips,
                views = items.map(ItemViews::of),
                mainHandSlot = player.inventory.heldItemSlot,
                skinUrl = skin,
                slim = slim,
            )
        }
    }
}
