package com.inmc.discord.chat

import com.google.gson.JsonParser
import com.inmc.discord.render.model.ItemView
import io.papermc.paper.datacomponent.DataComponentTypes
import org.bukkit.Bukkit
import org.bukkit.Keyed
import org.bukkit.entity.Player
import org.bukkit.inventory.ItemStack
import org.bukkit.profile.PlayerTextures
import java.util.Base64

/**
 * 서버 아이템 → 그림용 [ItemView]. **메인 스레드**(스냅샷을 뜰 때 같이). 컴포넌트를 못 읽으면 그 값만 빠진다.
 */
object ItemViews {

    fun of(stack: ItemStack?): ItemView? {
        if (stack == null || stack.isEmpty) return null
        fun <T> safe(block: () -> T?): T? = runCatching(block).getOrNull()
        val cmd = safe { stack.getData(DataComponentTypes.CUSTOM_MODEL_DATA) }
        val profile = safe { stack.getData(DataComponentTypes.PROFILE) }
        // 박힌 텍스처 → 그 uuid·이름의 접속자 → (그래도 없으면) 그림 일꾼이 프로필 조회.
        val textures = profile?.properties()?.firstOrNull { it.name == "textures" }?.value?.let(::skin)
            ?: profile?.let { p -> (p.uuid()?.let(Bukkit::getPlayer) ?: p.name()?.let(Bukkit::getPlayerExact))?.let(::skinOf) }
                ?.let { (url, slim) -> url?.let { it to slim } }
        val patch = safe { profile?.skinPatch()?.takeUnless { it.isEmpty } }
        return ItemView(
            model = safe { stack.getData(DataComponentTypes.ITEM_MODEL)?.asString() } ?: stack.type.key.asString(),
            count = stack.amount,
            maxStack = safe { stack.getData(DataComponentTypes.MAX_STACK_SIZE) } ?: stack.maxStackSize,
            damage = safe { stack.getData(DataComponentTypes.DAMAGE) } ?: 0,
            maxDamage = safe { stack.getData(DataComponentTypes.MAX_DAMAGE) } ?: 0,
            floats = cmd?.floats().orEmpty(),
            flags = cmd?.flags().orEmpty(),
            strings = cmd?.strings().orEmpty(),
            colors = cmd?.colors()?.map { it.asARGB() or 0xFF000000.toInt() }.orEmpty(),
            dyedColor = safe { stack.getData(DataComponentTypes.DYED_COLOR)?.color()?.asARGB()?.or(0xFF000000.toInt()) },
            potionColor = safe { stack.getData(DataComponentTypes.POTION_CONTENTS)?.computeEffectiveColor()?.asARGB()?.or(0xFF000000.toInt()) },
            fireworkColor = safe {
                stack.getData(DataComponentTypes.FIREWORK_EXPLOSION)?.colors?.takeIf { it.isNotEmpty() }?.let { colors ->
                    val r = colors.sumOf { it.red } / colors.size
                    val g = colors.sumOf { it.green } / colors.size
                    val b = colors.sumOf { it.blue } / colors.size
                    (0xFF shl 24) or (r shl 16) or (g shl 8) or b
                }
            },
            mapColor = safe { stack.getData(DataComponentTypes.MAP_COLOR)?.color()?.asARGB()?.or(0xFF000000.toInt()) },
            trimMaterial = safe { (stack.getData(DataComponentTypes.TRIM)?.armorTrim()?.material as? Keyed)?.key?.asString() },
            glint = safe { stack.getData(DataComponentTypes.ENCHANTMENT_GLINT_OVERRIDE) }
                ?: (safe { stack.getData(DataComponentTypes.ENCHANTMENTS)?.enchantments()?.isNotEmpty() } == true),
            skinUrl = textures?.first,
            skinSlim = patch?.model()?.let { it == PlayerTextures.SkinModel.SLIM } ?: (textures?.second == true),
            skinTexture = patch?.body()?.asString(),
            skinOwner = if (profile != null && textures == null) profile.uuid()?.toString() ?: profile.name() else null,
            baseColor = safe { stack.getData(DataComponentTypes.BASE_COLOR)?.name?.lowercase() },
            patterns = safe {
                stack.getData(DataComponentTypes.BANNER_PATTERNS)?.patterns()?.map { (it.pattern as Keyed).key.asString() to it.color.name.lowercase() }
            }.orEmpty(),
            tooltipStyle = safe { stack.getData(DataComponentTypes.TOOLTIP_STYLE)?.asString() },
            chargeType = safe {
                val projectiles = stack.getData(DataComponentTypes.CHARGED_PROJECTILES)?.projectiles().orEmpty()
                when {
                    projectiles.isEmpty() -> "none"
                    projectiles.any { it.type == org.bukkit.Material.FIREWORK_ROCKET } -> "rocket"
                    else -> "arrow"
                }
            } ?: "none",
            equipment = safe { stack.getData(DataComponentTypes.EQUIPPABLE)?.assetId()?.asString() },
        )
    }

    /** 플레이어 스킨 (주소, 슬림?) — 메인 스레드. */
    fun skinOf(player: Player): Pair<String?, Boolean> = runCatching {
        val textures = player.playerProfile.textures
        textures.skin?.toString() to (textures.skinModel == PlayerTextures.SkinModel.SLIM)
    }.getOrDefault(null to false)

    /** 프로필 `textures` 속성(base64 JSON) → (스킨 주소, 슬림?). */
    fun skin(base64: String): Pair<String, Boolean>? = runCatching {
        val json = JsonParser.parseString(String(Base64.getDecoder().decode(base64), Charsets.UTF_8)).asJsonObject
        val skin = json.getAsJsonObject("textures").getAsJsonObject("SKIN")
        val slim = skin.getAsJsonObject("metadata")?.get("model")?.asString == "slim"
        skin.get("url").asString to slim
    }.getOrNull()
}
