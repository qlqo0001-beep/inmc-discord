package com.inmc.discord.render

import com.inmc.discord.Discord
import com.inmc.discord.chat.Snapshot
import com.inmc.discord.chat.SnapshotKind
import com.inmc.discord.render.assets.AssetPack
import com.inmc.discord.render.assets.AssetSetup
import com.inmc.discord.render.font.Fonts
import com.inmc.discord.render.font.TextRenderer
import com.inmc.discord.render.images.Containers
import com.inmc.discord.render.images.Tooltips
import com.inmc.discord.render.model.ItemModels
import com.inmc.discord.render.model.Models
import com.inmc.discord.render.raster.ItemIcons
import com.inmc.discord.render.special.PlayerModel
import com.inmc.discord.render.special.SpecialModels
import net.kyori.adventure.text.Component
import org.bukkit.Bukkit
import java.io.File
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger
import java.util.logging.Level

/** 자원 한 벌로 묶은 그림 도구들. 서버 팩이 바뀌면 통째로 새로 만든다. */
class Engine(val assets: AssetPack, val lang: Lang, skins: Skins) {
    val fonts = Fonts(assets)
    val text = TextRenderer(fonts)
    val models = Models(assets)
    val items = ItemModels(assets, models)
    val icons = ItemIcons(assets, items, SpecialModels(assets, skins))
    val tooltips = Tooltips(assets, text)
    val containers = Containers(assets, text, icons, PlayerModel(assets, icons)) { lang }
    val pages = com.inmc.discord.render.images.Pages(assets, text) { lang }
    val tablist = com.inmc.discord.render.images.Tablist(assets, text)

    fun close() = assets.close()
}

/**
 * 그림 일꾼. **메인 스레드에서 그리지 않는다** — 낮은 우선순위 데몬 스레드 [Settings.Render.threads] 개.
 * 켤 때 자원을 확인하고(없으면 받기), 서버 팩(`render.server-pack`)이 바뀌면 다음 그림 때 다시 읽는다.
 */
class Renderer(private val discord: Discord) {

    private val counter = AtomicInteger()
    /** 설정을 읽은 뒤에 만든다(일꾼 수가 설정에 있다). */
    private val pool: ExecutorService by lazy {
        Executors.newFixedThreadPool(discord.settings.render.threads) { runnable ->
            Thread(runnable, "inmc-discord-render-${counter.incrementAndGet()}").apply {
                isDaemon = true
                priority = Thread.MIN_PRIORITY
            }
        }
    }
    private val setup = AssetSetup(discord.plugin.dataFolder, discord.logger)
    private val skins = Skins(File(discord.plugin.dataFolder, "cache/skins"), discord.logger)

    @Volatile
    private var engine: Engine? = null

    @Volatile
    private var packStamp = 0L

    /** `/디스코드 관리 상태` 한 줄. */
    @Volatile
    var state: String = "준비 중"
        private set

    val ready: Boolean get() = engine != null

    /** 켤 때 — 자원 확인·받기·엔진 만들기를 일꾼에서. */
    fun start() {
        pool.execute {
            runCatching { prepare() }.onFailure {
                state = "준비 실패: ${it.message}"
                discord.logger.log(Level.WARNING, "아이템 그림을 준비하지 못했습니다 — 디스코드에는 글로 보냅니다", it)
            }
        }
    }

    private fun prepare() {
        val render = discord.settings.render
        if (!render.enabled) {
            state = "꺼짐"
            return
        }
        if (!setup.ready(render.language)) {
            if (!render.autoDownload) {
                state = "자원 없음: ${setup.missing(render.language).joinToString()}"
                discord.logger.warning("아이템 그림 자원이 없습니다(${setup.missing(render.language).joinToString()}) — GUIDE 의 '그림 자원' 을 보세요")
                return
            }
            state = "자원 받는 중"
            setup.download(Bukkit.getMinecraftVersion(), render.language)
        }
        build()
    }

    private fun serverPack(): File? {
        val path = discord.settings.render.serverPack
        if (path.isBlank()) return null
        return File(discord.plugin.dataFolder.parentFile, path).takeIf { it.isFile }
    }

    private fun build() {
        val render = discord.settings.render
        val pack = serverPack()
        val client = AssetPack.ZipLayer(setup.clientJar)
        // 서버 팩의 overlay 는 클라이언트 판의 리소스팩 형식으로 고른다(26.2 = 88).
        val format = AssetPack.resourceFormat(client.bytes("version.json")) ?: DEFAULT_FORMAT
        val layers = listOfNotNull<AssetPack.Layer>(
            client,
            AssetPack.DirLayer(setup.objects),
            pack?.let { AssetPack.zipPack(it, format) },
        )
        val assets = AssetPack(layers)
        // 마크처럼 합친다 — en_us(모든 층) 위에 고른 언어(모든 층). 팩의 ko_kr.json 은 자기 항목만 든다.
        val entries = LinkedHashMap<String, String>()
        for (code in listOf("en_us", render.language).distinct()) {
            for (bytes in assets.stack("assets/minecraft/lang/$code.json")) runCatching { entries.putAll(Lang.read(bytes.inputStream())) }
        }
        val lang = if (entries.isEmpty()) Lang.EMPTY else Lang(entries)
        val old = engine
        engine = Engine(assets, lang, skins)
        packStamp = pack?.lastModified() ?: 0L
        discord.lang = lang
        old?.close()
        state = "준비됨 (${assets.layerNames().joinToString(" < ")})"
    }

    /** 일꾼에서 — 서버 팩이 바뀌었으면 다시 읽고 엔진을 준다. */
    private fun current(): Engine? {
        val e = engine ?: return null
        val stamp = serverPack()?.lastModified() ?: 0L
        if (stamp != packStamp) {
            runCatching { build() }.onFailure { discord.logger.warning("서버 팩을 다시 읽지 못했습니다: ${it.message}") }
        }
        return engine ?: e
    }

    class Image(val name: String, val png: ByteArray, val snapshot: Snapshot)

    private class Lookup(val url: String?, val at: Long)

    private val lookups = java.util.concurrent.ConcurrentHashMap<String, Lookup>()

    /**
     * 주인만 아는 머리의 스킨 주소 — Paper 프로필 조회(서버 캐시, 없으면 Mojang). 그림 일꾼에서만(최대 5초 기다림).
     * 찾은 것은 [LOOKUP_KEEP_MS], 못 찾은 것은 [LOOKUP_RETRY_MS] 동안 다시 묻지 않는다.
     */
    fun skinOf(owner: String): String? {
        val now = System.currentTimeMillis()
        lookups[owner]?.let { if (now - it.at < (if (it.url != null) LOOKUP_KEEP_MS else LOOKUP_RETRY_MS)) return it.url }
        val url = runCatching {
            val uuid = runCatching { java.util.UUID.fromString(owner) }.getOrNull()
            val profile = if (uuid != null) Bukkit.createProfile(uuid) else Bukkit.createProfile(owner)
            profile.update().get(5, java.util.concurrent.TimeUnit.SECONDS).textures.skin?.toString()
        }.getOrNull()
        lookups[owner] = Lookup(url, now)
        return url
    }

    private fun withSkins(views: List<com.inmc.discord.render.model.ItemView?>): List<com.inmc.discord.render.model.ItemView?> =
        views.map { v -> if (v != null && v.skinUrl == null && v.skinTexture == null && v.skinOwner != null) v.copy(skinUrl = skinOf(v.skinOwner)) else v }

    /** 스냅샷마다 그림 한 장. 준비가 안 됐거나 실패하면 빈 목록(부르는 쪽이 글로 보낸다). */
    fun images(snapshots: List<Snapshot>): CompletableFuture<List<Image>> {
        if (!ready || snapshots.isEmpty()) return CompletableFuture.completedFuture(emptyList())
        return CompletableFuture.supplyAsync({
            val e = current() ?: return@supplyAsync emptyList()
            snapshots.mapIndexedNotNull { i, snapshot ->
                runCatching { Image("${snapshot.kind.name.lowercase()}-$i.png", draw(e, snapshot).png(), snapshot) }
                    .onFailure { discord.logger.log(Level.WARNING, "아이템 그림을 그리지 못했습니다", it) }
                    .getOrNull()
            }
        }, pool)
    }

    private fun draw(e: Engine, snapshot: Snapshot): Raster {
        val s = discord.settings.render.scale
        val views = withSkins(snapshot.views)
        return when (snapshot.kind) {
            SnapshotKind.ITEM -> item(e, views.firstOrNull(), snapshot.tooltips.firstOrNull().orEmpty(), s)
            SnapshotKind.INVENTORY -> e.containers.inventory(
                views, snapshot.mainHandSlot, snapshot.level,
                (snapshot.skinUrl ?: skinOf(snapshot.owner.toString()))?.let(skins::get), snapshot.slim, s,
            )
            SnapshotKind.ENDER -> e.containers.chest(views, Component.translatable("container.enderchest"), s)
        }
    }

    // --- 디스코드 상호작용용 낱장 그림 ----------------------------------------------------------

    /** 일꾼에서 그려 PNG 로. 준비가 안 됐거나 실패하면 null. */
    private fun png(work: (Engine, Int) -> Raster): CompletableFuture<ByteArray?> {
        if (!ready) return CompletableFuture.completedFuture(null)
        return CompletableFuture.supplyAsync({
            val e = current() ?: return@supplyAsync null
            runCatching { work(e, discord.settings.render.scale).png() }
                .onFailure { discord.logger.log(Level.WARNING, "그림을 그리지 못했습니다", it) }
                .getOrNull()
        }, pool)
    }

    fun itemPng(view: com.inmc.discord.render.model.ItemView?, lines: List<Component>) = png { e, s -> item(e, withSkins(listOf(view)).first(), lines, s) }

    fun chestPng(views: List<com.inmc.discord.render.model.ItemView?>, title: Component, rows: Int) = png { e, s -> e.containers.chest(withSkins(views), title, s, rows) }

    fun bookPng(pages: List<Component>, page: Int) = png { e, s -> e.pages.book(pages, page, s) }

    fun mapPng(colors: ByteArray) = png { e, s -> e.pages.map(colors, s) }

    /** 탭리스트 — (이름, 스킨 주소, 핑). 이름·머리말은 번역 전이어도 된다. 스킨을 못 받은 사람은 기본 스킨 얼굴. */
    fun tablistPng(entries: List<Triple<Component, String?, Int>>, header: List<Component>, footer: List<Component>) = png { e, s ->
        e.tablist.render(
            entries.map { (name, skin, ping) ->
                com.inmc.discord.render.images.Tablist.Entry(e.lang.translate(name), skin?.let(skins::get) ?: e.assets.texture("minecraft:entity/player/wide/steve"), ping)
            },
            header.map(e.lang::translate), footer.map(e.lang::translate), s,
        )
    }

    /** 호버 글 그림들 — 툴팁 + 마우스 커서(마크처럼 커서 오른쪽 위에 툴팁). 실패한 것은 빠진다. */
    fun hoverPngs(hovers: List<List<Component>>): CompletableFuture<List<ByteArray>> {
        if (!ready || hovers.isEmpty()) return CompletableFuture.completedFuture(emptyList())
        return CompletableFuture.supplyAsync({
            val e = current() ?: return@supplyAsync emptyList()
            val s = discord.settings.render.scale
            hovers.mapNotNull { lines ->
                runCatching {
                    val tooltip = e.tooltips.render(lines.map(e.lang::translate), null, s)
                    val raster = Raster(tooltip.width + CURSOR_GAP * s, tooltip.height + CURSOR_GAP * s)
                    raster.draw(tooltip, CURSOR_GAP * s, 0)
                    Cursor.draw(raster, 0, tooltip.height - CURSOR_GAP * s / 2, s)
                    raster.png()
                }.getOrNull()
            }
        }, pool)
    }

    /** 플레이어 머리 아이콘(정보 임베드의 작은 그림). 주소가 없으면 [owner](uuid) 로 프로필을 조회한다. */
    fun headPng(skinUrl: String?, owner: String?) = png { e, s ->
        val url = skinUrl ?: owner?.let(::skinOf)
        e.icons.render(com.inmc.discord.render.model.ItemView("minecraft:player_head", skinUrl = url), 16 * s * 2)
    }

    /** 아이템 하나 — 왼쪽에 칸 아이콘, 오른쪽에 툴팁(마크에서 마우스를 올린 모습). [lines] 는 번역 전이어도 된다. */
    private fun item(e: Engine, view: com.inmc.discord.render.model.ItemView?, rawLines: List<Component>, s: Int): Raster {
        val lines = rawLines.map(e.lang::translate)
        val tooltip = if (lines.isEmpty()) null else e.tooltips.render(lines, view?.tooltipStyle, s)
        val iconSize = 16 * s
        val gap = 4 * s
        val width = iconSize + gap * 2 + (tooltip?.width ?: 0)
        val height = maxOf(iconSize + gap * 2, tooltip?.height ?: 0)
        val raster = Raster(width, height)
        if (view != null) {
            raster.draw(e.icons.render(view, iconSize), gap, gap)
            e.containers.decorate(raster, view, gap / s, gap / s, s)
        }
        tooltip?.let { raster.draw(it, iconSize + gap * 2, 0) }
        return raster
    }

    fun stop() {
        pool.shutdownNow()
        engine?.close()
        engine = null
    }

    private companion object {
        const val DEFAULT_FORMAT = 88
        const val CURSOR_GAP = 12
        const val LOOKUP_KEEP_MS = 30 * 60_000L
        const val LOOKUP_RETRY_MS = 10 * 60_000L
    }
}
