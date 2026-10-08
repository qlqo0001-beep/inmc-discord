package com.inmc.discord.chat

import com.inmc.discord.Discord
import kr.inmc.core.gui.Menu
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.NamedTextColor
import net.kyori.adventure.text.format.TextDecoration
import org.bukkit.Bukkit
import org.bukkit.Material
import org.bukkit.inventory.ItemStack
import org.bukkit.inventory.meta.SkullMeta

/**
 * [item]·[inv]·[ender] 를 눌렀을 때 열리는 **읽기 전용** 화면(InteractiveChat 의 보기 화면).
 * core [Menu] 가 클릭·드래그·쉬프트 넣기를 다 막는다 — 칸을 열어 둔 곳이 없다.
 */
class ViewMenu(private val discord: Discord, private val snapshot: Snapshot, title: Component) :
    Menu(sizeOf(snapshot.kind), title) {

    override val owner: Any get() = discord

    override fun draw() {
        clear()
        val keywords = discord.settings.keywords
        val primary = pane(keywords.framePrimary)
        val secondary = pane(keywords.frameSecondary)
        when (snapshot.kind) {
            SnapshotKind.ITEM -> {
                for (slot in 0 until size) set(slot, if ((slot + slot / 9) % 2 == 0) primary else secondary)
                set(ITEM_SLOT, snapshot.items.firstOrNull()?.takeUnless { it.isEmpty })
            }
            SnapshotKind.ENDER -> snapshot.items.forEachIndexed { slot, stack -> set(slot, stack) }
            SnapshotKind.INVENTORY -> {
                val items = snapshot.items
                fun at(index: Int) = items.getOrNull(index)?.takeUnless { it.isEmpty }
                // 윗줄: 투구·갑옷·바지·신발 · 판 · 왼손 · 판 · 판 · 머리
                ARMOR_SLOTS.forEachIndexed { slot, index -> set(slot, at(index)) }
                set(4, primary)
                set(5, at(OFFHAND))
                set(6, primary)
                set(7, primary)
                set(8, head())
                // 가방 27칸, 맨 아랫줄은 단축바
                for (index in 9..35) set(index, at(index))
                for (index in 0..8) set(36 + index, at(index))
            }
        }
    }

    private fun head(): ItemStack {
        val stack = ItemStack(Material.PLAYER_HEAD)
        stack.editMeta(SkullMeta::class.java) { meta ->
            meta.playerProfile = Bukkit.createProfile(snapshot.owner, snapshot.ownerName)
            meta.displayName(Component.text(snapshot.ownerName, NamedTextColor.YELLOW).decoration(TextDecoration.ITALIC, false))
            meta.lore(listOf(Component.text("레벨 ${snapshot.level}", NamedTextColor.GREEN).decoration(TextDecoration.ITALIC, false)))
        }
        return stack
    }

    private fun pane(material: org.bukkit.Material): ItemStack =
        ItemStack(material).apply { editMeta { it.displayName(Component.text(" ")); it.isHideTooltip = true } }

    companion object {

        const val ITEM_SLOT = 13

        /** `PlayerInventory.contents` 의 갑옷 자리 — 투구(39)·갑옷(38)·바지(37)·신발(36). */
        val ARMOR_SLOTS = listOf(39, 38, 37, 36)
        const val OFFHAND = 40

        fun sizeOf(kind: SnapshotKind): Int = when (kind) {
            SnapshotKind.ITEM -> 27
            SnapshotKind.INVENTORY -> 45
            SnapshotKind.ENDER -> 27
        }
    }
}
