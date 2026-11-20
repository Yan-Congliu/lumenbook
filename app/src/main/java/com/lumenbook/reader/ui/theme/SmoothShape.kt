package com.lumenbook.reader.ui.theme

import androidx.compose.foundation.shape.CornerBasedShape
import androidx.compose.foundation.shape.CornerSize
import androidx.compose.runtime.Immutable
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.hypot
import kotlin.math.min

/**
 * 曲率连续（G2）的圆角形状。
 *
 * 普通 RoundedCornerShape 的四角是圆弧：直边曲率 0，圆弧曲率 1/r，两者拼接处曲率直接跳变，
 * 所以「直边拐进圆角」看起来生硬。这里每个角由两段三次贝塞尔组成：
 *
 *   直边 ——[过渡段 κ: 0 → 1/r]—— 45° 点 ——[过渡段 κ: 1/r → 0]—— 直边
 *
 * - 过渡段起点与第二个控制点都落在直边所在的直线上 ⇒ 起点曲率严格为 0，与直边 G2 连续；
 * - 两个 45° 点曲率都是 1/r 且镜像相接 ⇒ 中间也 G2 连续；
 * - 圆角半径 r 的定义不变（仍是原来的圆弧半径），只是把「起弯」向直边外延 `SMOOTHING * r`，
 *   视觉上更柔和舒展，尺寸不会跑。
 */
@Immutable
class SmoothCornerShape(
    topStart: CornerSize,
    topEnd: CornerSize,
    bottomEnd: CornerSize,
    bottomStart: CornerSize,
) : CornerBasedShape(topStart, topEnd, bottomEnd, bottomStart) {

    constructor(all: CornerSize) : this(all, all, all, all)

    constructor(
        topStart: Dp = 0.dp,
        topEnd: Dp = 0.dp,
        bottomEnd: Dp = 0.dp,
        bottomStart: Dp = 0.dp,
    ) : this(
        CornerSize(topStart),
        CornerSize(topEnd),
        CornerSize(bottomEnd),
        CornerSize(bottomStart),
    )

    override fun createOutline(
        size: Size,
        topStart: Float,
        topEnd: Float,
        bottomEnd: Float,
        bottomStart: Float,
        layoutDirection: LayoutDirection,
    ): Outline {
        if (size.width <= 0f || size.height <= 0f) {
            return Outline.Rectangle(Rect(0f, 0f, size.width, size.height))
        }
        val (lt, rt, rb, lb) = if (layoutDirection == LayoutDirection.Rtl) {
            listOf(topEnd, topStart, bottomStart, bottomEnd)
        } else {
            listOf(topStart, topEnd, bottomEnd, bottomStart)
        }
        return Outline.Generic(smoothRoundedPath(size.width, size.height, lt, rt, rb, lb))
    }

    override fun copy(
        topStart: CornerSize,
        topEnd: CornerSize,
        bottomEnd: CornerSize,
        bottomStart: CornerSize,
    ): SmoothCornerShape = SmoothCornerShape(topStart, topEnd, bottomEnd, bottomStart)
}

/** 四角同半径的 G2 圆角。 */
fun SmoothRoundedCornerShape(radius: Dp): SmoothCornerShape =
    SmoothCornerShape(CornerSize(radius))

/** 底部弹层：上圆下方（M3 默认 28dp 顶角）的 G2 版本。 */
val SmoothSheetShape: CornerBasedShape = SmoothCornerShape(topStart = 28.dp, topEnd = 28.dp)

/** 胶囊形（短边一半）的 G2 版本：长边方向的直角接缝也被磨平。 */
@Immutable
class SmoothPillShape : Shape {

    override fun createOutline(
        size: Size,
        layoutDirection: LayoutDirection,
        density: Density,
    ): Outline {
        if (size.width <= 0f || size.height <= 0f) {
            return Outline.Rectangle(Rect(0f, 0f, size.width, size.height))
        }
        val r = min(size.width, size.height) / 2f
        return Outline.Generic(smoothRoundedPath(size.width, size.height, r, r, r, r))
    }

    override fun equals(other: Any?): Boolean = other is SmoothPillShape
    override fun hashCode(): Int = "SmoothPillShape".hashCode()
    override fun toString(): String = "SmoothPillShape"
}

/** 起弯外延相对半径的比例：0 = 普通圆弧，1 = 外延到整个半径。 */
private const val SMOOTHING = 0.62f

/** 第一段第一个控制点落在 S→C2 的这条比例上（越小越「慢起弯」）。 */
private const val EDGE_CONTROL_RATIO = 0.55f

private const val INV_SQRT2 = 0.70710678f

private const val CACHE_MAX = 32

private val pathCache = object : LinkedHashMap<String, Path>(CACHE_MAX, 0.75f, true) {
    override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Path>): Boolean =
        size > CACHE_MAX
}

private fun smoothRoundedPath(
    width: Float,
    height: Float,
    topLeft: Float,
    topRight: Float,
    bottomRight: Float,
    bottomLeft: Float,
): Path {
    val key = "${fmt(width)}|${fmt(height)}|${fmt(topLeft)}|${fmt(topRight)}|" +
        "${fmt(bottomRight)}|${fmt(bottomLeft)}"
    synchronized(pathCache) {
        pathCache[key]?.let { return it }
    }
    val path = buildPath(width, height, topLeft, topRight, bottomRight, bottomLeft)
    synchronized(pathCache) { pathCache[key] = path }
    return path
}

private fun fmt(value: Float): String = (value * 100f).toInt().toString()

private fun buildPath(
    width: Float,
    height: Float,
    topLeft: Float,
    topRight: Float,
    bottomRight: Float,
    bottomLeft: Float,
): Path {
    val maxRadius = min(width, height) / 2f
    var tl = topLeft.coerceIn(0f, maxRadius)
    var tr = topRight.coerceIn(0f, maxRadius)
    var br = bottomRight.coerceIn(0f, maxRadius)
    var bl = bottomLeft.coerceIn(0f, maxRadius)

    // 相邻两角半径之和不能超过边长
    val horizontal = tl + tr
    if (horizontal > width && horizontal > 0f) {
        val k = width / horizontal
        tl *= k; tr *= k; bl *= k; br *= k
    }
    val vertical = tr + br
    if (vertical > height && vertical > 0f) {
        val k = height / vertical
        tl *= k; tr *= k; bl *= k; br *= k
    }

    val path = Path()
    // 顺时针：上边 → 右上角 → 右边 → 右下角 → 下边 → 左下角 → 左边 → 左上角
    val (topRightCorner, bottomRightCorner, bottomLeftCorner, topLeftCorner) =
        buildCorners(width, height, tl, tr, br, bl)

    val startPoint = topLeftCorner.end
    path.moveTo(startPoint[0], startPoint[1])
    path.addCorner(topRightCorner)
    path.addCorner(bottomRightCorner)
    path.addCorner(bottomLeftCorner)
    path.addCorner(topLeftCorner)
    path.close()
    return path
}

internal fun buildCorners(
    width: Float,
    height: Float,
    tl: Float,
    tr: Float,
    br: Float,
    bl: Float,
): List<Corner> = listOf(
    Corner(
        ox = width - tr, oy = tr,
        nax = 0f, nay = -1f,
        nbx = 1f, nby = 0f,
        radius = tr,
        extensionA = min(SMOOTHING * tr, ((width - tl - tr) / 2f).coerceAtLeast(0f)),
        extensionB = min(SMOOTHING * tr, ((height - tr - br) / 2f).coerceAtLeast(0f)),
    ),
    Corner(
        ox = width - br, oy = height - br,
        nax = 1f, nay = 0f,
        nbx = 0f, nby = 1f,
        radius = br,
        extensionA = min(SMOOTHING * br, ((height - tr - br) / 2f).coerceAtLeast(0f)),
        extensionB = min(SMOOTHING * br, ((width - bl - br) / 2f).coerceAtLeast(0f)),
    ),
    Corner(
        ox = bl, oy = height - bl,
        nax = 0f, nay = 1f,
        nbx = -1f, nby = 0f,
        radius = bl,
        extensionA = min(SMOOTHING * bl, ((width - bl - br) / 2f).coerceAtLeast(0f)),
        extensionB = min(SMOOTHING * bl, ((height - tl - bl) / 2f).coerceAtLeast(0f)),
    ),
    Corner(
        ox = tl, oy = tl,
        nax = -1f, nay = 0f,
        nbx = 0f, nby = -1f,
        radius = tl,
        extensionA = min(SMOOTHING * tl, ((height - tl - bl) / 2f).coerceAtLeast(0f)),
        extensionB = min(SMOOTHING * tl, ((width - tl - tr) / 2f).coerceAtLeast(0f)),
    ),
)

internal class Corner(
    val ox: Float,
    val oy: Float,
    val nax: Float,
    val nay: Float,
    val nbx: Float,
    val nby: Float,
    val radius: Float,
    val extensionA: Float,
    val extensionB: Float,
) {
    /** 沿入边的切向（指向角内）。 */
    val tax: Float get() = -nay
    val tay: Float get() = nax

    /** 沿出边的切向（背离角）。 */
    val tbx: Float get() = -nby
    val tby: Float get() = nbx

    /** 入边上的连接点（已外延）。 */
    val start: FloatArray
        get() = floatArrayOf(
            ox + nax * radius - tax * extensionA,
            oy + nay * radius - tay * extensionA,
        )

    /** 出边上的连接点（已外延）。 */
    val end: FloatArray
        get() = floatArrayOf(
            ox + nbx * radius + tbx * extensionB,
            oy + nby * radius + tby * extensionB,
        )
}

internal data class CornerPoints(
    val sx: Float, val sy: Float,
    val c1x: Float, val c1y: Float,
    val c2x: Float, val c2y: Float,
    val mx: Float, val my: Float,
    val c3x: Float, val c3y: Float,
    val c4x: Float, val c4y: Float,
    val ex: Float, val ey: Float,
)

/**
 * 把一角换算成两段三次贝塞尔的控制点（纯数学，绘制与单测共用）。
 *
 * 构造保证：入边连接点 S 处曲率为 0（C1、C2 都落在入边直线上），
 * 45° 点 M 处切线沿对角方向（第二段是镜像，于是 M 处曲率连续）。
 */
internal fun cornerPoints(corner: Corner): CornerPoints {
    val r = corner.radius
    val start = corner.start
    val end = corner.end
    val mx = corner.ox + (corner.nax + corner.nbx) * INV_SQRT2 * r
    val my = corner.oy + (corner.nay + corner.nby) * INV_SQRT2 * r
    val tax = corner.tax
    val tay = corner.tay
    val tmx = (corner.tax + corner.tbx) * INV_SQRT2
    val tmy = (corner.tay + corner.tby) * INV_SQRT2

    // 求 C2：既在入边直线上（S + u * tA），又在过 M 的对角切线线上（M + v * tM）。
    val det = tax * (-tmy) - tay * (-tmx)
    var c2x = start[0]
    var c2y = start[1]
    if (abs(det) > 1e-4f) {
        val dx = mx - start[0]
        val dy = my - start[1]
        val v = (tax * dy - tay * dx) / det
        c2x = mx + v * tmx
        c2y = my + v * tmy
    }
    val c1x = start[0] + (c2x - start[0]) * EDGE_CONTROL_RATIO
    val c1y = start[1] + (c2y - start[1]) * EDGE_CONTROL_RATIO

    // 第二段是第一段关于「O→M 对角轴」（方向 = 两边法线和，即 (nA+nB)/√2）的镜像。
    val ubx = (corner.nax + corner.nbx) * INV_SQRT2
    val uby = (corner.nay + corner.nby) * INV_SQRT2
    fun reflect(px: Float, py: Float): Pair<Float, Float> {
        val dx = px - corner.ox
        val dy = py - corner.oy
        val dot = dx * ubx + dy * uby
        return (corner.ox + 2f * dot * ubx - dx) to (corner.oy + 2f * dot * uby - dy)
    }

    val (c3x, c3y) = reflect(c2x, c2y)
    val (c4x, c4y) = reflect(c1x, c1y)

    return CornerPoints(
        sx = start[0], sy = start[1],
        c1x = c1x, c1y = c1y,
        c2x = c2x, c2y = c2y,
        mx = mx, my = my,
        c3x = c3x, c3y = c3y,
        c4x = c4x, c4y = c4y,
        ex = end[0], ey = end[1],
    )
}

private fun Path.addCorner(corner: Corner) {
    val r = corner.radius
    if (r <= 0.5f) {
        lineTo(corner.ox, corner.oy)
        return
    }
    val start = corner.start
    val end = corner.end
    lineTo(start[0], start[1])

    if (corner.extensionA <= r * 0.15f || corner.extensionB <= r * 0.15f) {
        // 空间不够（例如胶囊的短边方向），退回普通圆弧
        val startDegrees = Math.toDegrees(
            atan2(corner.nay.toDouble(), corner.nax.toDouble())
        ).toFloat()
        arcTo(
            rect = Rect(corner.ox - r, corner.oy - r, corner.ox + r, corner.oy + r),
            startAngleDegrees = startDegrees,
            sweepAngleDegrees = 90f,
            forceMoveTo = false,
        )
        lineTo(end[0], end[1])
        return
    }

    val p = cornerPoints(corner)
    cubicTo(p.c1x, p.c1y, p.c2x, p.c2y, p.mx, p.my)
    cubicTo(p.c3x, p.c3y, p.c4x, p.c4y, p.ex, p.ey)
}

/**
 * 单测辅助：按与绘制完全相同的几何参数，把轮廓采样成折线（不依赖 Android 的 Path）。
 */
internal fun smoothOutlinePoints(
    width: Float,
    height: Float,
    radius: Float,
    stepsPerSegment: Int = 16,
): List<Pair<Float, Float>> {
    val maxRadius = min(width, height) / 2f
    val r = radius.coerceIn(0f, maxRadius)
    val corners = buildCorners(width, height, r, r, r, r)
    val points = ArrayList<Pair<Float, Float>>()

    fun addLine(x0: Float, y0: Float, x1: Float, y1: Float) {
        for (i in 0..stepsPerSegment) {
            val t = i.toFloat() / stepsPerSegment
            points.add((x0 + (x1 - x0) * t) to (y0 + (y1 - y0) * t))
        }
    }

    fun addCubic(
        x0: Float, y0: Float,
        x1: Float, y1: Float,
        x2: Float, y2: Float,
        x3: Float, y3: Float,
    ) {
        for (i in 1..stepsPerSegment) {
            val t = i.toFloat() / stepsPerSegment
            val mt = 1f - t
            val a = mt * mt * mt
            val b = 3f * mt * mt * t
            val c = 3f * mt * t * t
            val d = t * t * t
            points.add(
                (a * x0 + b * x1 + c * x2 + d * x3) to
                    (a * y0 + b * y1 + c * y2 + d * y3)
            )
        }
    }

    val start = corners[3].end
    points.add(start[0] to start[1])
    var prevX = start[0]
    var prevY = start[1]
    corners.forEach { corner ->
        val p = cornerPoints(corner)
        addLine(prevX, prevY, p.sx, p.sy)
        addCubic(p.sx, p.sy, p.c1x, p.c1y, p.c2x, p.c2y, p.mx, p.my)
        addCubic(p.mx, p.my, p.c3x, p.c3y, p.c4x, p.c4y, p.ex, p.ey)
        prevX = p.ex
        prevY = p.ey
    }
    addLine(prevX, prevY, start[0], start[1])
    return points
}

/** 单测辅助：由三点估曲率。 */
internal fun curvatureOf(points: List<Pair<Float, Float>>, index: Int): Float {
    val n = points.size
    if (index <= 0 || index >= n - 1) return 0f
    val (x0, y0) = points[index - 1]
    val (x1, y1) = points[index]
    val (x2, y2) = points[index + 1]
    val a = hypot((x1 - x0).toDouble(), (y1 - y0).toDouble())
    val b = hypot((x2 - x1).toDouble(), (y2 - y1).toDouble())
    val c = hypot((x2 - x0).toDouble(), (y2 - y0).toDouble())
    if (a < 1e-4 || b < 1e-4 || c < 1e-4) return 0f
    val area = abs((x1 - x0) * (y2 - y0) - (x2 - x0) * (y1 - y0)) / 2.0
    return (4.0 * area / (a * b * c)).toFloat()
}
