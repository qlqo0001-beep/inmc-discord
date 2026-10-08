package com.inmc.discord.render.model

/**
 * 그림에 필요한 아이템의 값만 — 서버 객체를 모른다. 서버에서는 `ItemViews.of(stack)` 이 만들고(메인 스레드),
 * 테스트·미리보기는 손으로 만든다. 그림 일꾼은 이것만 본다.
 *
 * 색은 전부 ARGB.
 */
data class ItemView(
    /** `items/<id>.json` 을 고르는 열쇠 — `item_model` 컴포넌트가 있으면 그것, 없으면 아이템 종류(`minecraft:diamond_sword`). */
    val model: String,
    val count: Int = 1,
    val maxStack: Int = 64,
    val damage: Int = 0,
    val maxDamage: Int = 0,
    val floats: List<Float> = emptyList(),
    val flags: List<Boolean> = emptyList(),
    val strings: List<String> = emptyList(),
    val colors: List<Int> = emptyList(),
    val dyedColor: Int? = null,
    val potionColor: Int? = null,
    val fireworkColor: Int? = null,
    val mapColor: Int? = null,
    val trimMaterial: String? = null,
    val glint: Boolean = false,
    /** 머리 아이템의 스킨 그림 주소(textures.minecraft.net). */
    val skinUrl: String? = null,
    val skinSlim: Boolean = false,
    /** 26.x `profile` 의 스킨 덮어쓰기(`texture`) — 리소스팩 텍스처(`ns:path`, textures/ 기준). 있으면 이것이 먼저. */
    val skinTexture: String? = null,
    /** 스킨을 못 찾은 머리의 주인(uuid 또는 이름) — 그림 일꾼이 프로필을 조회해 [skinUrl] 을 채운다. */
    val skinOwner: String? = null,
    /** 깃발·방패 바탕 색(염료 이름 `white` …). */
    val baseColor: String? = null,
    /** (무늬 자원 위치 `minecraft:stripe_bottom`, 염료 이름). */
    val patterns: List<Pair<String, String>> = emptyList(),
    val tooltipStyle: String? = null,
    /** `has_component` 판정용 — 있는 컴포넌트 열쇠(`minecraft:damage` …). */
    val components: Set<String> = emptySet(),
    /** `block_state` 판정용. */
    val blockState: Map<String, String> = emptyMap(),
    /** 쇠뇌 `charge_type` — none · arrow · rocket. */
    val chargeType: String = "none",
    /** 입었을 때의 겉모습(`equippable` 의 asset_id, `minecraft:diamond` …). 없으면 입은 모습이 없다. */
    val equipment: String? = null,
) {
    val empty: Boolean get() = model == AIR || count <= 0

    companion object {
        const val AIR = "minecraft:air"

        /** 염료 이름 → 마크 텍스처 색(깃발·침대·양털과 같은 `DyeColor.textureDiffuseColor`). */
        val DYES: Map<String, Int> = mapOf(
            "white" to 0xFFF9FFFE.toInt(), "orange" to 0xFFF9801D.toInt(), "magenta" to 0xFFC74EBD.toInt(),
            "light_blue" to 0xFF3AB3DA.toInt(), "yellow" to 0xFFFED83D.toInt(), "lime" to 0xFF80C71F.toInt(),
            "pink" to 0xFFF38BAA.toInt(), "gray" to 0xFF474F52.toInt(), "light_gray" to 0xFF9D9D97.toInt(),
            "cyan" to 0xFF169C9C.toInt(), "purple" to 0xFF8932B8.toInt(), "blue" to 0xFF3C44AA.toInt(),
            "brown" to 0xFF835432.toInt(), "green" to 0xFF5E7C16.toInt(), "red" to 0xFFB02E26.toInt(),
            "black" to 0xFF1D1D21.toInt(),
        )
    }
}
