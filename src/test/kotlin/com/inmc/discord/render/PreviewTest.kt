package com.inmc.discord.render

import com.google.gson.JsonParser
import com.inmc.discord.render.assets.AssetPack
import com.inmc.discord.render.font.Fonts
import com.inmc.discord.render.font.TextRenderer
import com.inmc.discord.render.images.Tooltips
import com.inmc.discord.render.model.ItemModels
import com.inmc.discord.render.model.ItemView
import com.inmc.discord.render.model.Models
import com.inmc.discord.render.raster.ItemIcons
import com.inmc.discord.render.special.SpecialModels
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.NamedTextColor
import net.kyori.adventure.text.format.TextDecoration
import java.io.File
import java.util.logging.Logger
import kotlin.test.Test

/**
 * 이 PC 의 마인크래프트 26.2 클라이언트 자원으로 미리보기 그림을 만든다(`build/preview/`). 자원이 없으면 건너뛴다.
 * 눈으로 클라이언트 화면과 견주는 용도 — 단언은 "그려졌다" 뿐이다.
 */
class PreviewTest {

    private val minecraft = File(System.getenv("APPDATA") ?: "", ".minecraft")
    private val jar = File(minecraft, "versions/26.2/26.2.jar")

    /** 클라이언트 jar 에 없는 자산 객체(한글 글꼴·번역) — 서버의 `assets/objects` 층과 같은 것. */
    private fun objects(): AssetPack.Layer {
        val index = JsonParser.parseString(File(minecraft, "assets/indexes/32.json").readText()).asJsonObject.getAsJsonObject("objects")
        val wanted = listOf("minecraft/font/include/unifont.json", "minecraft/font/unifont.zip", "minecraft/lang/ko_kr.json")
        val files = wanted.associate { key ->
            val hash = index.getAsJsonObject(key).get("hash").asString
            "assets/$key" to File(minecraft, "assets/objects/${hash.take(2)}/$hash").readBytes()
        }
        return AssetPack.MapLayer("objects", files)
    }

    private fun pack(): AssetPack? {
        if (!jar.isFile) return null
        return AssetPack(listOf(AssetPack.ZipLayer(jar), objects()))
    }

    @Test
    fun `아이콘과 툴팁 미리보기`() {
        val assets = pack() ?: return
        val out = File("build/preview").apply { mkdirs() }
        val models = Models(assets)
        val icons = ItemIcons(assets, ItemModels(assets, models), SpecialModels(assets, null))
        val views = listOf(
            ItemView("minecraft:diamond_sword"), ItemView("minecraft:diamond_sword", glint = true), ItemView("minecraft:grass_block"),
            ItemView("minecraft:stone"), ItemView("minecraft:oak_log"), ItemView("minecraft:oak_stairs"), ItemView("minecraft:oak_fence"),
            ItemView("minecraft:glass_pane"), ItemView("minecraft:torch"), ItemView("minecraft:poppy"),
            ItemView("minecraft:chest"), ItemView("minecraft:ender_chest"), ItemView("minecraft:shulker_box"), ItemView("minecraft:red_shulker_box"),
            ItemView("minecraft:player_head"), ItemView("minecraft:skeleton_skull"), ItemView("minecraft:zombie_head"), ItemView("minecraft:creeper_head"),
            ItemView("minecraft:piglin_head"), ItemView("minecraft:shield"),
            ItemView("minecraft:white_banner"), ItemView("minecraft:red_banner", patterns = listOf("minecraft:stripe_bottom" to "blue")),
            ItemView("minecraft:red_bed"), ItemView("minecraft:anvil"), ItemView("minecraft:hopper"), ItemView("minecraft:beacon"),
            ItemView("minecraft:enchanting_table"), ItemView("minecraft:conduit"), ItemView("minecraft:lantern"), ItemView("minecraft:iron_bars"),
            ItemView("minecraft:potion", potionColor = 0xFFF82423.toInt()), ItemView("minecraft:leather_helmet", dyedColor = 0xFF3C44AA.toInt()),
            ItemView("minecraft:bow"), ItemView("minecraft:crossbow"), ItemView("minecraft:compass"), ItemView("minecraft:clock"),
            ItemView("minecraft:water_bucket"), ItemView("minecraft:trident"), ItemView("minecraft:decorated_pot"), ItemView("minecraft:dragon_head"),
        )
        val s = 4
        val cell = 16 * s + 8
        val columns = 10
        val sheet = Raster(columns * cell, ((views.size + columns - 1) / columns) * cell)
        sheet.fill(0xFF8B8B8B.toInt())
        views.forEachIndexed { i, v ->
            val icon = icons.render(v, 16 * s)
            sheet.draw(icon, (i % columns) * cell + 4, (i / columns) * cell + 4)
        }
        File(out, "icons.png").writeBytes(sheet.png())

        val big = listOf(
            ItemView("minecraft:white_banner"), ItemView("minecraft:shield"), ItemView("minecraft:shulker_box"), ItemView("minecraft:chest"),
        )
        val bigSheet = Raster(big.size * 272, 272).apply { fill(0xFF8B8B8B.toInt()) }
        big.forEachIndexed { i, v -> bigSheet.draw(icons.render(v, 256), i * 272 + 8, 8) }
        File(out, "big.png").writeBytes(bigSheet.png())

        val fonts = Fonts(assets)
        val textRenderer = TextRenderer(fonts)
        val langKo = com.inmc.discord.render.Lang(com.inmc.discord.render.Lang.read(assets.bytes("assets/minecraft/lang/ko_kr.json")!!.inputStream()))
        val containers = com.inmc.discord.render.images.Containers(
            assets, textRenderer, icons, com.inmc.discord.render.special.PlayerModel(assets, icons), { langKo },
        )
        val inv = arrayOfNulls<ItemView>(41).toMutableList()
        inv[0] = ItemView("minecraft:diamond_sword", glint = true, damage = 400, maxDamage = 1561)
        inv[1] = ItemView("minecraft:cooked_beef", count = 32)
        inv[2] = ItemView("minecraft:torch", count = 64)
        inv[3] = ItemView("minecraft:chest")
        inv[9] = ItemView("minecraft:grass_block", count = 12)
        inv[20] = ItemView("minecraft:player_head")
        inv[36] = ItemView("minecraft:diamond_boots", equipment = "minecraft:diamond")
        inv[37] = ItemView("minecraft:iron_leggings", equipment = "minecraft:iron")
        inv[38] = ItemView("minecraft:leather_chestplate", equipment = "minecraft:leather", dyedColor = 0xFF3C44AA.toInt())
        inv[39] = ItemView("minecraft:golden_helmet", equipment = "minecraft:gold")
        inv[40] = ItemView("minecraft:shield")
        File(out, "inventory.png").writeBytes(containers.inventory(inv, 0, 30, null, false, 2).png())
        val ender = (0 until 27).map { if (it % 4 == 0) ItemView("minecraft:diamond", count = it + 1) else null }
        File(out, "ender.png").writeBytes(containers.chest(ender, Component.translatable("container.enderchest"), 2).png())

        val pages = com.inmc.discord.render.images.Pages(assets, textRenderer) { langKo }
        val bookPages = listOf(
            Component.text("첫 쪽입니다. 마인크래프트 책처럼 너비 114 픽셀에서 줄이 넘어갑니다. ").append(Component.text("빨간 글").color(NamedTextColor.DARK_RED))
                .append(Component.text(" 과 굵은 글").decorate(TextDecoration.BOLD)).append(Component.text("\n\n줄바꿈도 됩니다. The quick brown fox jumps over the lazy dog.")),
            Component.text("둘째 쪽"),
        )
        File(out, "book.png").writeBytes(pages.book(bookPages, 0, 2).png())
        val colors = ByteArray(128 * 128) { i -> (((i % 128) / 16 + (i / 128) / 16) % 8 * 4 + 4 + 2).toByte() }
        File(out, "map.png").writeBytes(pages.map(colors, 2).png())

        val steve = assets.texture("minecraft:entity/player/wide/steve")
        val tab = com.inmc.discord.render.images.Tablist(assets, textRenderer).render(
            (1..25).map { i ->
                com.inmc.discord.render.images.Tablist.Entry(
                    Component.text("[칭호] ").color(NamedTextColor.GOLD).append(Component.text("플레이어$i").color(NamedTextColor.WHITE)),
                    steve, listOf(30, 200, 400, 800, 1500, -1)[i % 6],
                )
            },
            listOf(Component.text("접속 중인 플레이어 (25/100)").color(NamedTextColor.GREEN)), emptyList(), 2,
        )
        File(out, "tablist.png").writeBytes(tab.png())

        // 실제 스킨 — 이 PC 클라이언트의 스킨 캐시(assets/skins/<앞 두 글자>/<해시>, 64×64 만)를 주소 캐시처럼 넣어 둔다(받지 않는다).
        val skinFiles = File(minecraft, "assets/skins").walkTopDown().filter { it.isFile }
            .filter { f -> runCatching { javax.imageio.ImageIO.read(f).let { it.width == 64 && it.height == 64 } }.getOrDefault(false) }
            .take(6).toList()
        if (skinFiles.isNotEmpty()) {
            val cache = File(out, "skin-cache").apply { deleteRecursively(); mkdirs() }
            val urls = skinFiles.map { f ->
                val url = "https://textures.minecraft.net/texture/${f.name}"
                f.copyTo(File(cache, Skins.sha1(url) + ".png"), overwrite = true)
                url
            }
            val skins = Skins(cache, Logger.getAnonymousLogger())
            val skinIcons = ItemIcons(assets, ItemModels(assets, models), SpecialModels(assets, skins))
            val heads = Raster(urls.size * cell, cell).apply { fill(0xFF8B8B8B.toInt()) }
            urls.forEachIndexed { i, url -> heads.draw(skinIcons.render(ItemView("minecraft:player_head", skinUrl = url), 16 * s), i * cell + 4, 4) }
            File(out, "skin-heads.png").writeBytes(heads.png())
            val skinContainers = com.inmc.discord.render.images.Containers(
                assets, textRenderer, skinIcons, com.inmc.discord.render.special.PlayerModel(assets, skinIcons), { langKo },
            )
            File(out, "skin-inventory.png").writeBytes(skinContainers.inventory(inv, 0, 12, skins.get(urls.first()), false, 2).png())
            val skinTab = com.inmc.discord.render.images.Tablist(assets, textRenderer).render(
                urls.mapIndexed { i, url -> com.inmc.discord.render.images.Tablist.Entry(Component.text("스킨$i"), skins.get(url), 50) },
                emptyList(), emptyList(), 2,
            )
            File(out, "skin-tablist.png").writeBytes(skinTab.png())
        }

        // 테섭 커스텀아이템 팩(읽기만) — 있으면.
        val serverPack = File("../server/plugins/inmc-customitems/pack/output/pack.zip")
        if (serverPack.isFile) {
            val format = AssetPack.resourceFormat(AssetPack.ZipLayer(jar).bytes("version.json"))!!
            val withPack = AssetPack(listOf(AssetPack.ZipLayer(jar), objects(), AssetPack.zipPack(serverPack, format)))
            val packIcons = ItemIcons(withPack, ItemModels(withPack, Models(withPack)), SpecialModels(withPack, null))
            val custom = listOf(
                "inmc:advanced_rod", "inmc:andesitebricks", "inmc:abyssal_shark", "inmc:celestial_whale", "inmc:a_rare",
                "inmc:preview/inmc/weapon/28", "inmc:preview/1_splatus/item/key", "inmc:preview/1_splatus/item/ice_staff_ready",
                "inmc:preview/inmc/item/ia_auto/gem_stone14", "inmc:preview/inmc/item/ia_auto/pick0",
            )
            val customSheet = Raster(custom.size * cell, cell).apply { fill(0xFF8B8B8B.toInt()) }
            custom.forEachIndexed { i, id -> customSheet.draw(packIcons.render(ItemView(id), 16 * s), i * cell + 4, 4) }
            File(out, "custom.png").writeBytes(customSheet.png())
            // 서버 팩을 얹어도(팩에 자기 글리프만 든 default.json 이 있다) 한글·영문이 그대로 그려지는지
            val packLang = com.inmc.discord.render.Lang(
                withPack.stack("assets/minecraft/lang/ko_kr.json").fold(LinkedHashMap<String, String>()) { m, b -> m.apply { putAll(com.inmc.discord.render.Lang.read(b.inputStream())) } },
            )
            val packLines = listOf(
                Component.translatable("item.minecraft.diamond_sword").color(NamedTextColor.AQUA),
                Component.text("팩을 얹은 툴팁 — 한글 ABC abc 123").color(NamedTextColor.GRAY),
            ).map(packLang::translate)
            File(out, "tooltip-pack.png").writeBytes(Tooltips(withPack, TextRenderer(Fonts(withPack))).render(packLines, null, 2).png())
            withPack.close()
        }

        val text = TextRenderer(Fonts(assets))
        val lang = com.inmc.discord.render.Lang(com.inmc.discord.render.Lang.read(assets.bytes("assets/minecraft/lang/ko_kr.json")!!.inputStream()))
        val lines = listOf(
            Component.translatable("item.minecraft.diamond_sword").color(NamedTextColor.AQUA),
            Component.translatable("enchantment.minecraft.sharpness").append(Component.text(" ")).append(Component.translatable("enchantment.level.5")).color(NamedTextColor.GRAY),
            Component.text("전설의 검 — 한글 줄 ABC abc 123").color(NamedTextColor.DARK_PURPLE).decorate(TextDecoration.ITALIC),
            Component.text("굵게 ").decorate(TextDecoration.BOLD).append(Component.text("밑줄").decorate(TextDecoration.UNDERLINED)).append(Component.text(" 취소").decorate(TextDecoration.STRIKETHROUGH)),
            Component.empty(),
            Component.translatable("item.modifiers.mainhand").color(NamedTextColor.GRAY),
            Component.text(" 8 ").append(Component.translatable("attribute.name.attack_damage")).color(NamedTextColor.DARK_GREEN),
        ).map(lang::translate)
        File(out, "tooltip.png").writeBytes(Tooltips(assets, text).render(lines, null, 2).png())
        Logger.getAnonymousLogger().info("미리보기: ${out.absolutePath}")
    }
}
