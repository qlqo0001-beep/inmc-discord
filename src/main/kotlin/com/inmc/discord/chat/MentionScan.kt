package com.inmc.discord.chat

import java.util.UUID

/**
 * 채팅 글에서 `@이름` 찾기 — 순수(`MentionScanTest`).
 * 긴 이름부터 맞추고(`@abc` 가 `@abcd` 를 먹지 않게), 이름 뒤가 영문·숫자·밑줄이면 다른 낱말이라 넘어간다. 대소문자는 가리지 않는다.
 */
object MentionScan {

    /** [text] 는 글에 적힌 그대로(강조할 때 그 글자를 찾는다). [everyone] 이면 접속자 전원. */
    data class Found(val text: String, val targets: Set<UUID>, val everyone: Boolean)

    /**
     * @param names 부를 수 있는 이름 → 그 사람. 실명과 닉네임을 둘 다 넣는다.
     * @param everyoneWords 전원을 부르는 낱말(`here`·`everyone` 가운데 권한이 있는 것).
     */
    fun scan(text: String, prefix: String, names: Map<String, UUID>, everyoneWords: Set<String>): List<Found> {
        if (prefix.isEmpty() || !text.contains(prefix)) return emptyList()
        val candidates = names.entries.filter { it.key.isNotBlank() }.map { it.key to setOf(it.value) } +
            everyoneWords.map { it to emptySet<UUID>() }
        val sorted = candidates.sortedByDescending { it.first.length }
        val found = LinkedHashMap<String, Found>()
        var i = 0
        outer@ while (i < text.length) {
            if (text.startsWith(prefix, i)) {
                val start = i + prefix.length
                for ((name, targets) in sorted) {
                    val end = start + name.length
                    if (end <= text.length && text.regionMatches(start, name, 0, name.length, ignoreCase = true) &&
                        (end == text.length || !isNameChar(text[end]))
                    ) {
                        val written = text.substring(i, end)
                        val everyone = name in everyoneWords && targets.isEmpty()
                        val previous = found[written]
                        found[written] = Found(written, (previous?.targets ?: emptySet()) + targets, everyone || previous?.everyone == true)
                        i = end
                        continue@outer
                    }
                }
            }
            i++
        }
        return found.values.toList()
    }

    /** 마인크래프트 이름 글자(영문·숫자·밑줄). 한글 조사(`@Steve님`)는 이름 밖이다 — 한글 닉네임은 긴 것부터 맞춰서 가린다. */
    private fun isNameChar(c: Char): Boolean = c in 'a'..'z' || c in 'A'..'Z' || c in '0'..'9' || c == '_'
}
