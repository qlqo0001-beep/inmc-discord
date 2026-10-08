package com.inmc.discord.render.raster

import com.inmc.discord.render.Raster
import com.inmc.discord.render.assets.AssetPack
import com.inmc.discord.render.model.BlockModel
import com.inmc.discord.render.model.Direction
import com.inmc.discord.render.model.Element
import com.inmc.discord.render.model.ItemModels
import com.inmc.discord.render.model.ItemView
import com.inmc.discord.render.special.SpecialModels
import org.joml.Matrix3f
import org.joml.Matrix4f
import org.joml.Vector3f
import java.awt.image.BufferedImage
import kotlin.math.cos

/**
 * 아이템 칸 아이콘(GUI 16×16)을 [size] 픽셀로 그린다 — 마크 인벤토리에서 보이는 그대로.
 *
 * 변환 순서(마크 `GuiGraphics.renderItem` + `ItemTransform` + 26.x 아이템 정의 변환):
 * 칸 가운데 → (16, -16, 16) 배 → 모델의 `display.gui` → (-0.5, -0.5, -0.5) → 정의의 `transformation` → 모델 좌표(픽셀/16).
 * 조명은 마크의 GUI 3D 아이템 조명(빛 둘 + 바탕 0.4)을 같은 공간에서 계산한다.
 */
class ItemIcons(private val assets: AssetPack, private val items: ItemModels, private val specials: SpecialModels) {

    fun render(view: ItemView, size: Int): Raster {
        val raster = Raster(size, size)
        if (view.empty) return raster
        val canvas = Canvas3D(raster)
        for (layer in items.layers(view)) {
            when (layer) {
                is ItemModels.ModelLayer -> drawModel(canvas, layer.model, layer.tints, layer.transform, size)
                is ItemModels.SpecialLayer -> {
                    val gui = layer.base?.gui() ?: com.inmc.discord.render.model.Transform.IDENTITY
                    val pose = basePose(size).mul(gui.matrix()).translate(-0.5f, -0.5f, -0.5f)
                    layer.transform?.let { pose.mul(it) }
                    val light = Lights.gui(layer.base?.guiLight ?: "side")
                    if (!specials.render(canvas, layer.spec, view, pose, light)) {
                        layer.base?.resolve("#particle")?.let(assets::texture)?.let { flat(raster, it, -1, size, 0.8f) }
                    }
                }
            }
        }
        if (view.glint) glint(raster)
        return raster
    }

    /**
     * 칸이 아닌 곳(플레이어 손)에 그린다. [outer] 는 손까지의 변환(블록 단위 → 화면). 표시 변환은 [context] 의 것,
     * 왼손은 마크처럼 오른손 변환을 거울로(이동 x·회전 y·z 를 뒤집어). 납작한 아이템은 앞뒤 두 면(두께 1/16)으로.
     */
    fun drawInHand(canvas: Canvas3D, view: ItemView, outer: Matrix4f, context: String, light: Lights) {
        if (view.empty) return
        val left = context.endsWith("lefthand")
        for (layer in items.layers(view, context)) {
            val model = when (layer) {
                is ItemModels.ModelLayer -> layer.model
                is ItemModels.SpecialLayer -> layer.base
            } ?: continue
            val display = model.display[context] ?: model.display[context.replace("left", "right")] ?: com.inmc.discord.render.model.Transform.IDENTITY
            val pose = Matrix4f(outer).mul(if (left) mirrored(display) else display.matrix()).translate(-0.5f, -0.5f, -0.5f)
            layer.transform?.let { pose.mul(it) }
            when (layer) {
                is ItemModels.ModelLayer -> {
                    val elements = if (model.generated) generatedElements(model) else model.elements
                    for (element in elements) drawElement(canvas, model, element, layer.tints, pose, light)
                }
                is ItemModels.SpecialLayer -> specials.render(canvas, layer.spec, view, pose, light)
            }
        }
    }

    private fun mirrored(t: com.inmc.discord.render.model.Transform): Matrix4f =
        com.inmc.discord.render.model.Transform(
            Vector3f(t.rotation.x, -t.rotation.y, -t.rotation.z),
            Vector3f(-t.translation.x, t.translation.y, t.translation.z),
            t.scale,
        ).matrix()

    /** `builtin/generated` 의 층마다 앞뒤 면(마크 `ItemModelGenerator` 의 큰 면 둘 — 가장자리 띠는 손 크기에서 안 보여 뺐다). */
    private fun generatedElements(model: BlockModel): List<Element> =
        model.layers().indices.map { i ->
            Element(
                Vector3f(0f, 0f, 7.5f), Vector3f(16f, 16f, 8.5f), null,
                mapOf(
                    Direction.SOUTH to com.inmc.discord.render.model.Face(floatArrayOf(0f, 0f, 16f, 16f), "#layer$i", 0, i),
                    Direction.NORTH to com.inmc.discord.render.model.Face(floatArrayOf(16f, 0f, 0f, 16f), "#layer$i", 0, i),
                ),
                true,
            )
        }

    private fun basePose(size: Int): Matrix4f = Matrix4f().translate(size / 2f, size / 2f, 0f).scale(size.toFloat(), -size.toFloat(), size.toFloat())

    private fun drawModel(canvas: Canvas3D, model: BlockModel, tints: List<Int>, transform: Matrix4f?, size: Int) {
        if (model.generated) {
            val gui = model.gui()
            // 납작한 아이템 — 마크도 GUI 에서는 앞면 그대로 보인다. 크기·위치 변환만 반영.
            val scale = gui.scale.x.let { if (it == 0f) 1f else kotlin.math.abs(it) }
            model.layers().forEachIndexed { i, location ->
                val texture = assets.texture(location) ?: return@forEachIndexed
                flat(canvas.raster, texture, tints.getOrNull(i) ?: -1, size, scale, gui.translation.x, -gui.translation.y)
            }
            return
        }
        val pose = basePose(size).mul(model.gui().matrix()).translate(-0.5f, -0.5f, -0.5f)
        transform?.let { pose.mul(it) }
        val light = Lights.gui(model.guiLight)
        for (element in model.elements) drawElement(canvas, model, element, tints, pose, light)
    }

    /** 텍스처를 칸에 납작하게(가운데 기준 [scale] 배, [dx]/[dy] 는 블록 단위 이동). */
    private fun flat(raster: Raster, texture: BufferedImage, tint: Int, size: Int, scale: Float, dx: Float = 0f, dy: Float = 0f) {
        val target = size * scale
        val left = (size - target) / 2f + dx * size
        val top = (size - target) / 2f + dy * size
        val canvas = Canvas3D(raster)
        val v = arrayOf(
            Canvas3D.Vertex(left, top, 0f, 0f, 0f),
            Canvas3D.Vertex(left + target, top, 0f, 1f, 0f),
            Canvas3D.Vertex(left + target, top + target, 0f, 1f, 1f),
            Canvas3D.Vertex(left, top + target, 0f, 0f, 1f),
        )
        canvas.quad(v, texture, tint, 1f)
    }

    fun drawElement(canvas: Canvas3D, model: BlockModel, element: Element, tints: List<Int>, pose: Matrix4f, light: Lights) {
        val rot = element.rotation?.let { elementMatrix(it) }
        val normalPose = Matrix3f(pose).invert().transpose()
        for ((dir, face) in element.faces) {
            val texture = model.resolve(face.texture)?.let(assets::texture) ?: missing
            val corners = corners(element, dir)
            val uv = face.uv ?: defaultUv(element, dir)
            val base = arrayOf(floatArrayOf(uv[0], uv[1]), floatArrayOf(uv[2], uv[1]), floatArrayOf(uv[2], uv[3]), floatArrayOf(uv[0], uv[3]))
            val k = ((face.rotation / 90) % 4 + 4) % 4
            val verts = Array(4) { i ->
                val p = Vector3f(corners[i])
                rot?.transformPosition(p)
                p.div(16f)
                pose.transformPosition(p)
                val t = base[(i - k + 4) % 4]
                Canvas3D.Vertex(p.x, p.y, p.z, t[0] / 16f, t[1] / 16f)
            }
            val normal = Vector3f(dir.nx, dir.ny, dir.nz)
            rot?.transformDirection(normal)
            normalPose.transform(normal).normalize()
            val brightness = if (element.shade) light.at(normal) else 1f
            val tint = if (face.tintIndex >= 0) tints.getOrNull(face.tintIndex) ?: -1 else -1
            canvas.quad(verts, texture, tint, brightness)
        }
    }

    // --- 요소 기하 -----------------------------------------------------------------

    private fun elementMatrix(r: com.inmc.discord.render.model.ElementRotation): Matrix4f {
        val rad = Math.toRadians(r.angle.toDouble()).toFloat()
        val m = Matrix4f().translate(r.origin)
        when (r.axis) {
            'x' -> m.rotateX(rad)
            'y' -> m.rotateY(rad)
            else -> m.rotateZ(rad)
        }
        if (r.rescale && r.angle != 0f) {
            val f = 1f / cos(rad.toDouble()).toFloat()
            when (r.axis) {
                'x' -> m.scale(1f, f, f)
                'y' -> m.scale(f, 1f, f)
                else -> m.scale(f, f, 1f)
            }
        }
        return m.translate(Vector3f(r.origin).negate())
    }

    companion object {

        private const val GLINT_STRENGTH = 0.75f

        private val missing: BufferedImage = BufferedImage(2, 2, BufferedImage.TYPE_INT_ARGB).apply {
            setRGB(0, 0, 0xFFF800F8.toInt()); setRGB(1, 1, 0xFFF800F8.toInt()); setRGB(1, 0, 0xFF000000.toInt()); setRGB(0, 1, 0xFF000000.toInt())
        }

        /** 면의 네 꼭짓점 — 바깥에서 볼 때 텍스처 왼위·오른위·오른아래·왼아래 순(마크 기본 UV 와 맞춘 것). */
        fun corners(e: Element, d: Direction): Array<Vector3f> {
            val f = e.from
            val t = e.to
            return when (d) {
                Direction.NORTH -> arrayOf(Vector3f(t.x, t.y, f.z), Vector3f(f.x, t.y, f.z), Vector3f(f.x, f.y, f.z), Vector3f(t.x, f.y, f.z))
                Direction.SOUTH -> arrayOf(Vector3f(f.x, t.y, t.z), Vector3f(t.x, t.y, t.z), Vector3f(t.x, f.y, t.z), Vector3f(f.x, f.y, t.z))
                Direction.WEST -> arrayOf(Vector3f(f.x, t.y, f.z), Vector3f(f.x, t.y, t.z), Vector3f(f.x, f.y, t.z), Vector3f(f.x, f.y, f.z))
                Direction.EAST -> arrayOf(Vector3f(t.x, t.y, t.z), Vector3f(t.x, t.y, f.z), Vector3f(t.x, f.y, f.z), Vector3f(t.x, f.y, t.z))
                Direction.UP -> arrayOf(Vector3f(f.x, t.y, f.z), Vector3f(t.x, t.y, f.z), Vector3f(t.x, t.y, t.z), Vector3f(f.x, t.y, t.z))
                Direction.DOWN -> arrayOf(Vector3f(f.x, f.y, t.z), Vector3f(t.x, f.y, t.z), Vector3f(t.x, f.y, f.z), Vector3f(f.x, f.y, f.z))
            }
        }

        /** uv 를 안 적은 면의 마크 기본값. */
        fun defaultUv(e: Element, d: Direction): FloatArray {
            val f = e.from
            val t = e.to
            return when (d) {
                Direction.DOWN -> floatArrayOf(f.x, 16 - t.z, t.x, 16 - f.z)
                Direction.UP -> floatArrayOf(f.x, f.z, t.x, t.z)
                Direction.NORTH -> floatArrayOf(16 - t.x, 16 - t.y, 16 - f.x, 16 - f.y)
                Direction.SOUTH -> floatArrayOf(f.x, 16 - t.y, t.x, 16 - f.y)
                Direction.WEST -> floatArrayOf(f.z, 16 - t.y, t.z, 16 - f.y)
                Direction.EAST -> floatArrayOf(16 - t.z, 16 - t.y, 16 - f.z, 16 - f.y)
            }
        }

    }

    // --- 인첸트 반짝임 ------------------------------------------------------------------

    /** 마크 GUI 반짝임의 정지 화면 근사 — 반짝임 텍스처를 10° 기울여 깔고 더한다(움직이지 않는 그림이라 한 순간). */
    private fun glint(raster: Raster) {
        val texture = assets.texture("minecraft:misc/enchanted_glint_item") ?: return
        val angle = Math.toRadians(10.0)
        val c = cos(angle).toFloat()
        val s = kotlin.math.sin(angle).toFloat()
        val scale = texture.width / raster.width.toFloat()
        for (y in 0 until raster.height) for (x in 0 until raster.width) {
            val u = ((x * c - y * s) * scale).toInt().mod(texture.width)
            val v = ((x * s + y * c) * scale).toInt().mod(texture.height)
            raster.add(x, y, texture.getRGB(u, v), GLINT_STRENGTH)
        }
    }

}
