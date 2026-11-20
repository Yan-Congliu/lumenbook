package com.lumenbook.reader.ui.theme

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.pow

/**
 * 曲率连续（G2）形状的几何自检。
 *
 * 普通圆弧圆角在「直边 → 圆弧」的接缝处曲率从 0 直接跳到 1/r；
 * 这里要求曲率从 0 连续爬升到峰值，再对称落回 0，全程没有跳变。
 */
class SmoothShapeTest {

    private data class P(val x: Float, val y: Float)

    /** 一段三次贝塞尔在 t 处的曲率。 */
    private fun curvature(
        p0: P, p1: P, p2: P, p3: P, t: Float,
    ): Float {
        val mt = 1f - t
        // 一阶导
        val d1x = 3f * mt * mt * (p1.x - p0.x) + 6f * mt * t * (p2.x - p1.x) + 3f * t * t * (p3.x - p2.x)
        val d1y = 3f * mt * mt * (p1.y - p0.y) + 6f * mt * t * (p2.y - p1.y) + 3f * t * t * (p3.y - p2.y)
        // 二阶导
        val d2x = 6f * mt * (p2.x - 2f * p1.x + p0.x) + 6f * t * (p3.x - 2f * p2.x + p1.x)
        val d2y = 6f * mt * (p2.y - 2f * p1.y + p0.y) + 6f * t * (p3.y - 2f * p2.y + p1.y)
        val speed = (d1x * d1x + d1y * d1y)
        if (speed < 1e-8f) return 0f
        return abs(d1x * d2y - d1y * d2x) / speed.pow(1.5f)
    }

    private fun segments(corner: Corner): List<List<P>> {
        val c = cornerPoints(corner)
        val s = P(c.sx, c.sy)
        val c1 = P(c.c1x, c.c1y)
        val c2 = P(c.c2x, c.c2y)
        val m = P(c.mx, c.my)
        val c3 = P(c.c3x, c.c3y)
        val c4 = P(c.c4x, c.c4y)
        val e = P(c.ex, c.ey)
        return listOf(listOf(s, c1, c2, m), listOf(m, c3, c4, e))
    }

    private fun curvatureProfile(corner: Corner, steps: Int = 32): List<Float> {
        val (a, b) = segments(corner)
        val out = ArrayList<Float>(2 * steps + 2)
        for (seg in listOf(a, b)) {
            for (i in 0..steps) {
                out.add(curvature(seg[0], seg[1], seg[2], seg[3], i.toFloat() / steps))
            }
        }
        return out
    }

    private fun cornersOf(w: Float, h: Float, r: Float): List<Corner> =
        buildCorners(w, h, r, r, r, r)

    @Test
    fun curvatureIsZeroWhereTheCornerLeavesTheStraightEdge() {
        val r = 24f
        for (corner in cornersOf(240f, 160f, r)) {
            val k = curvatureProfile(corner)
            assertTrue(
                "接缝处曲率应为 0，实际 ${k.first()}",
                k.first() < 0.02f / r,
            )
            assertTrue(
                "另一端也应回到 0，实际 ${k.last()}",
                k.last() < 0.02f / r,
            )
        }
    }

    @Test
    fun curvatureIsContinuousAcrossTheDiagonalJoint() {
        val r = 24f
        for (corner in cornersOf(240f, 160f, r)) {
            val k = curvatureProfile(corner)
            val mid = k.size / 2
            val before = k[mid - 1]
            val after = k[mid]
            assertTrue("对角处曲率必须一致：$before vs $after", abs(before - after) < 0.02f / r)
        }
    }

    @Test
    fun curvatureRampsUpMonotonicallyWithoutJumps() {
        val r = 24f
        for (corner in cornersOf(240f, 160f, r)) {
            val k = curvatureProfile(corner, steps = 64)
            val peak = k.max()
            var maxDrop = 0f
            for (i in 1 until k.size / 2) {
                maxDrop = maxOf(maxDrop, k[i - 1] - k[i])
            }
            assertTrue("曲率在爬升段出现回落：$maxDrop", maxDrop < peak * 0.02f)
            assertEquals("曲率应一路升到峰值", 1f, k[k.size / 2 - 1] / peak, 0.02f)
        }
    }

    @Test
    fun peakCurvatureIsAbout1_29OverR() {
        for (r in listOf(8f, 16f, 24f, 40f)) {
            for (corner in cornersOf(240f, 160f, r)) {
                val peak = curvatureProfile(corner, steps = 64).max()
                assertEquals(
                    "r=$r 的峰值曲率",
                    1.29f / r,
                    peak,
                    0.30f / r,
                )
            }
        }
    }

    @Test
    fun outlineSamplesStayInsideTheBox() {
        val w = 240f
        val h = 160f
        val points = smoothOutlinePoints(w, h, 24f, stepsPerSegment = 16)
        assertTrue(points.isNotEmpty())
        for ((x, y) in points) {
            assertTrue("x 越界：$x", x > -0.5f && x < w + 0.5f)
            assertTrue("y 越界：$y", y > -0.5f && y < h + 0.5f)
        }
    }

    @Test
    fun outlineIsSymmetric() {
        val w = 240f
        val h = 160f
        val points = smoothOutlinePoints(w, h, 24f, stepsPerSegment = 16)
        var matched = 0
        for ((x, y) in points) {
            val mirrored = points.any { abs(it.first - (w - x)) < 0.6f && abs(it.second - y) < 0.6f }
            if (mirrored) matched++
        }
        assertTrue("左右不对称：${matched}/${points.size}", matched >= points.size - 2)
    }

    @Test
    fun degenerateRadiiDoNotProduceNaN() {
        val zero = smoothOutlinePoints(100f, 60f, 0f, stepsPerSegment = 8)
        assertTrue(zero.isNotEmpty())
        assertTrue(zero.all { it.first.isFinite() && it.second.isFinite() })

        val capsule = smoothOutlinePoints(200f, 40f, 400f, stepsPerSegment = 16)
        assertTrue(capsule.isNotEmpty())
        assertTrue(capsule.all { it.first.isFinite() && it.second.isFinite() })
        assertTrue("胶囊应撑满宽度", capsule.minOf { it.first } < 1f)
        assertTrue("胶囊应撑满高度", capsule.minOf { it.second } < 1f)
        assertTrue("胶囊高度应为 40", capsule.maxOf { it.second } > 39f)
        assertTrue(hypot(0f, 0f) == 0f)
    }
}
