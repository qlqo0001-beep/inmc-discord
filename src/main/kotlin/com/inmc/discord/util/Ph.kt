package com.inmc.discord.util

import kr.inmc.core.util.TokenBag

/**
 * 메시지 한 번 렌더링에 쓰이는 토큰 주머니. 한글/영문 둘 다 받는다 — 다른 INMC 플러그인들과 같은 관례.
 */
class Ph : TokenBag<Ph>() {

    override val aliases: Map<String, List<String>> get() = ALIASES

    fun player(name: String): Ph = put(PLAYER, name)

    fun value(text: String): Ph = put(VALUE, text)

    fun count(value: Int): Ph = put(COUNT, value.toString())

    fun code(text: String): Ph = put(CODE, text)

    fun name(text: String): Ph = put(NAME, text)

    fun uuid(text: String): Ph = put(UUID, text)

    fun id(text: String): Ph = put(ID, text)

    fun user(text: String): Ph = put(USER, text)

    fun error(text: String): Ph = put(ERROR, text)

    fun bot(text: String): Ph = put(BOT, text)

    fun command(text: String): Ph = put(COMMAND, text)

    companion object {

        fun of(): Ph = Ph()

        const val PLAYER = "player"
        const val VALUE = "value"
        const val COUNT = "count"
        const val CODE = "code"
        const val NAME = "name"
        const val UUID = "uuid"
        const val ID = "id"
        const val USER = "user"
        const val ERROR = "error"
        const val BOT = "bot"
        const val COMMAND = "command"

        private val ALIASES: Map<String, List<String>> = mapOf(
            PLAYER to listOf("{플레이어}", "{player}"),
            VALUE to listOf("{값}", "{value}"),
            COUNT to listOf("{개수}", "{count}"),
            CODE to listOf("{코드}", "{code}"),
            NAME to listOf("{이름}", "{name}"),
            UUID to listOf("{uuid}"),
            ID to listOf("{id}"),
            USER to listOf("{사용자}", "{user}"),
            ERROR to listOf("{오류}", "{error}"),
            BOT to listOf("{봇}", "{bot}"),
            COMMAND to listOf("{명령어}", "{command}"),
        )
    }
}
