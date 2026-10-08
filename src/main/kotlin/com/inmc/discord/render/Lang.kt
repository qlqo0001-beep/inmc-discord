package com.inmc.discord.render

import com.google.gson.JsonParser
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.TranslatableComponent
import java.io.File
import java.io.InputStream
import java.util.logging.Logger

/**
 * 번역 키 → 글. 아이템 이름·툴팁 줄은 `item.minecraft.diamond_sword` 같은 번역 키라서, 디스코드로 보낼 글과 그림은
 * 서버가 직접 풀어야 한다(게임 안에서는 클라이언트가 푼다).
 *
 * 그림 자원이 준비되면 [com.inmc.discord.render.Renderer] 가 자원의 언어 파일로 바꿔 끼운다. 그전에는
 * 데이터 폴더 `assets/objects/assets/minecraft/lang/<code>.json` → 서버 jar 안의 en_us.json → 키 그대로.
 */
class Lang(private val entries: Map<String, String>) {

    fun has(key: String): Boolean = key in entries

    /** 번역 키를 전부 글로 바꾼 컴포넌트. 모양(색·굵기)은 그대로 둔다. */
    fun translate(component: Component): Component {
        val children = component.children()
        val base: Component = if (component is TranslatableComponent) flatten(component) else component.children(emptyList())
        if (children.isEmpty()) return base
        return base.append(children.map(::translate))
    }

    private fun flatten(component: TranslatableComponent): Component {
        val format = entries[component.key()] ?: component.fallback() ?: component.key()
        val args = component.arguments().map { translate(it.asComponent()) }
        val parts = ArrayList<Component>()
        val text = StringBuilder()
        var next = 0
        var i = 0
        fun flushText() {
            if (text.isNotEmpty()) {
                parts += Component.text(text.toString())
                text.setLength(0)
            }
        }
        while (i < format.length) {
            val c = format[i]
            if (c == '%' && i + 1 < format.length) {
                val m = ARG.find(format, i)
                if (m != null && m.range.first == i) {
                    when {
                        m.value == "%%" -> text.append('%')
                        else -> {
                            flushText()
                            val index = m.groupValues[1].takeIf { it.isNotEmpty() }?.toInt()?.minus(1) ?: next++
                            parts += args.getOrNull(index) ?: Component.empty()
                        }
                    }
                    i = m.range.last + 1
                    continue
                }
            }
            text.append(c)
            i++
        }
        flushText()
        return Component.text().style(component.style()).append(parts).build()
    }

    /** 색 없는 글(디스코드 글·로그). */
    fun plain(component: Component): String =
        net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer.plainText().serialize(translate(component))

    companion object {

        private val ARG = Regex("%(?:(\\d+)\\$)?[sd]|%%")

        val EMPTY = Lang(emptyMap())

        fun load(dataFolder: File, code: String, logger: Logger): Lang {
            val own = File(dataFolder, "assets/objects/assets/minecraft/lang/$code.json")
            if (own.isFile) runCatching { return Lang(read(own.inputStream())) }
                .onFailure { logger.warning("${own.path} 을 읽지 못했습니다: ${it.message}") }
            val bundled = Lang::class.java.classLoader.getResourceAsStream("assets/minecraft/lang/en_us.json")
                ?: Thread.currentThread().contextClassLoader?.getResourceAsStream("assets/minecraft/lang/en_us.json")
            if (bundled != null) return runCatching { Lang(read(bundled)) }.getOrDefault(EMPTY)
            logger.warning("번역 파일이 없어 아이템 이름이 번역 키로 보입니다 — 그림 자원이 준비되면 바뀝니다")
            return EMPTY
        }

        fun read(input: InputStream): Map<String, String> = input.use { stream ->
            val json = JsonParser.parseReader(stream.reader(Charsets.UTF_8)).asJsonObject
            json.entrySet().associate { it.key to it.value.asString }
        }
    }
}
