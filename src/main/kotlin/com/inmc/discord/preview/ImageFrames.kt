package com.inmc.discord.preview

import org.w3c.dom.Node
import java.awt.RenderingHints
import java.awt.image.BufferedImage
import javax.imageio.ImageIO
import javax.imageio.metadata.IIOMetadataNode

/**
 * 첨부 그림 → 지도 한 장(128×128 색 번호) 프레임들. 움직이는 GIF 는 프레임마다(겹쳐 그리기·지우기 방식 반영), 아래 두 줄에 재생 막대.
 * 순수 — `ImageFramesTest`. 색 번호 고르기는 [palette] 를 넘겨받는다(서버에서는 마크 지도 팔레트).
 */
object ImageFrames {

    class Frame(val colors: ByteArray, val delayMs: Int)

    /** @return 못 읽는 형식이면 빈 목록. */
    fun decode(bytes: ByteArray, maxFrames: Int = MAX_FRAMES): List<Pair<BufferedImage, Int>> {
        val stream = ImageIO.createImageInputStream(bytes.inputStream()) ?: return emptyList()
        val reader = ImageIO.getImageReaders(stream).asSequence().firstOrNull() ?: return emptyList()
        return stream.use {
            reader.input = stream
            if (reader.formatName.equals("gif", ignoreCase = true)) gif(reader, maxFrames)
            else listOfNotNull(reader.read(0)?.let { img -> img to 0 })
        }.also { reader.dispose() }
    }

    private fun gif(reader: javax.imageio.ImageReader, maxFrames: Int): List<Pair<BufferedImage, Int>> {
        val count = runCatching { reader.getNumImages(true) }.getOrDefault(1).coerceAtMost(maxFrames)
        var width = 0
        var height = 0
        runCatching {
            val root = reader.streamMetadata?.getAsTree("javax_imageio_gif_stream_1.0") as? IIOMetadataNode
            val screen = root?.getElementsByTagName("LogicalScreenDescriptor")?.item(0)
            width = attr(screen, "logicalScreenWidth")
            height = attr(screen, "logicalScreenHeight")
        }
        val out = ArrayList<Pair<BufferedImage, Int>>()
        var canvas: BufferedImage? = null
        for (i in 0 until count) {
            val frame = runCatching { reader.read(i) }.getOrNull() ?: break
            val meta = runCatching { reader.getImageMetadata(i).getAsTree("javax_imageio_gif_image_1.0") as IIOMetadataNode }.getOrNull()
            val descriptor = meta?.getElementsByTagName("ImageDescriptor")?.item(0)
            val control = meta?.getElementsByTagName("GraphicControlExtension")?.item(0)
            val x = attr(descriptor, "imageLeftPosition")
            val y = attr(descriptor, "imageTopPosition")
            val delay = attr(control, "delayTime") * 10
            val disposal = (control as? IIOMetadataNode)?.getAttribute("disposalMethod").orEmpty()
            if (canvas == null) canvas = BufferedImage(maxOf(width, frame.width + x), maxOf(height, frame.height + y), BufferedImage.TYPE_INT_ARGB)
            val before = if (disposal == "restoreToPrevious") copy(canvas) else null
            canvas.createGraphics().apply { drawImage(frame, x, y, null); dispose() }
            out += copy(canvas) to (if (delay <= 0) DEFAULT_DELAY_MS else delay)
            when (disposal) {
                "restoreToBackgroundColor" -> canvas.createGraphics().apply {
                    composite = java.awt.AlphaComposite.Clear
                    fillRect(x, y, frame.width, frame.height)
                    dispose()
                }
                "restoreToPrevious" -> canvas = before
            }
        }
        return out
    }

    /** 128×128 에 맞춰(비율 유지, 가운데) 색 번호로. 프레임이 둘 이상이면 아래 두 줄에 재생 막대. */
    fun toMap(frames: List<Pair<BufferedImage, Int>>, palette: (Int) -> Byte): List<Frame> {
        val cache = HashMap<Int, Byte>()
        fun match(argb: Int): Byte {
            if (argb ushr 24 < 128) return 0
            return cache.getOrPut(argb and 0xFFFFFF) { palette(argb and 0xFFFFFF) }
        }
        val playing = frames.size > 1
        return frames.mapIndexed { index, (image, delay) ->
            val fitted = fit(image)
            if (playing) {
                val filled = ((index + 1) * SIZE) / frames.size
                for (y in SIZE - 2 until SIZE) for (x in 0 until SIZE) fitted.setRGB(x, y, if (x < filled) BAR_FILLED else BAR_EMPTY)
            }
            val colors = ByteArray(SIZE * SIZE) { i -> match(fitted.getRGB(i % SIZE, i / SIZE)) }
            Frame(colors, delay)
        }
    }

    private fun fit(image: BufferedImage): BufferedImage {
        val out = BufferedImage(SIZE, SIZE, BufferedImage.TYPE_INT_ARGB)
        val scale = minOf(SIZE.toDouble() / image.width, SIZE.toDouble() / image.height)
        val w = maxOf(1, (image.width * scale).toInt())
        val h = maxOf(1, (image.height * scale).toInt())
        out.createGraphics().apply {
            setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR)
            drawImage(image, (SIZE - w) / 2, (SIZE - h) / 2, w, h, null)
            dispose()
        }
        return out
    }

    private fun copy(image: BufferedImage): BufferedImage =
        BufferedImage(image.width, image.height, BufferedImage.TYPE_INT_ARGB).also { it.createGraphics().apply { drawImage(image, 0, 0, null); dispose() } }

    private fun attr(node: Node?, name: String): Int =
        (node as? IIOMetadataNode)?.getAttribute(name)?.toIntOrNull() ?: 0

    const val SIZE = 128
    const val MAX_FRAMES = 200
    const val DEFAULT_DELAY_MS = 100

    /** InteractiveChat 애드온 PlaybackBar 와 같은 색. */
    private const val BAR_FILLED = 0xFFFF0000.toInt()
    private const val BAR_EMPTY = 0xFF938B86.toInt()
}
