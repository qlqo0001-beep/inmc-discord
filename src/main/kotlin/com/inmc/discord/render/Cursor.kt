package com.inmc.discord.render

/**
 * 마우스 커서(화살표) — 호버 그림에서 "여기에 마우스를 올렸다" 를 보이는 그림(InteractiveChat 애드온의 ShowCursorImage).
 * 마크에는 커서 그림이 없어서 흔한 OS 화살표 모양을 글자 그림으로 그린다. X = 검정 테두리, W = 흰색.
 */
object Cursor {

    private val SHAPE = listOf(
        "X...........",
        "XX..........",
        "XWX.........",
        "XWWX........",
        "XWWWX.......",
        "XWWWWX......",
        "XWWWWWX.....",
        "XWWWWWWX....",
        "XWWWWWWWX...",
        "XWWWWWWWWX..",
        "XWWWWWWWWWX.",
        "XWWWWWWXXXXX",
        "XWWWXWWX....",
        "XWWX.XWWX...",
        "XWX..XWWX...",
        "XX....XWWX..",
        "X.....XWWX..",
        ".......XWWX.",
        ".......XXX..",
    )

    /** 화살표 끝이 (x, y) 에 오도록. [s] 배. */
    fun draw(raster: Raster, x: Int, y: Int, s: Int) {
        SHAPE.forEachIndexed { row, line ->
            line.forEachIndexed { col, c ->
                val color = when (c) {
                    'X' -> 0xFF000000.toInt()
                    'W' -> 0xFFFFFFFF.toInt()
                    else -> return@forEachIndexed
                }
                raster.fillRect(x + col * s, y + row * s, s, s, color)
            }
        }
    }
}
