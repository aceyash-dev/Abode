package dev.abode.keyboard

import android.graphics.Path
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathNode
import androidx.compose.ui.graphics.vector.VectorGroup
import androidx.compose.ui.graphics.vector.VectorNode
import androidx.compose.ui.graphics.vector.VectorPath

/**
 * Turns a Compose [ImageVector] into an [android.graphics.Path] so the key grid
 * — which draws on a raw Canvas, not in a composition — can use the same icons
 * the rest of the app does.
 *
 * Tabler icons are stroke drawings on a 24-unit viewport: a path, a stroke
 * width, no fill. That is what this preserves, scaled to the size the caller
 * asked for. Arc segments are approximated with a line to their endpoint, which
 * is the one place a converted icon can differ from its Compose rendering.
 */
object VectorPaths {

    class Rendered(val path: Path, val strokeWidth: Float, val filled: Boolean)

    fun render(vector: ImageVector, size: Float): Rendered {
        val scale = if (vector.viewportWidth > 0f) size / vector.viewportWidth else 1f
        val path = Path()
        var strokeWidth = 0f
        var filled = false

        val paths = ArrayList<VectorPath>()
        collectPaths(vector.root, paths)

        for (vectorPath in paths) {
            if (vectorPath.fill != null) filled = true
            strokeWidth = maxOf(strokeWidth, vectorPath.strokeLineWidth * scale)

            var x = 0f
            var y = 0f
            var startX = 0f
            var startY = 0f

            for (node in vectorPath.pathData) {
                when (node) {
                    is PathNode.MoveTo -> {
                        x = node.x * scale
                        y = node.y * scale
                        path.moveTo(x, y)
                        startX = x
                        startY = y
                    }

                    is PathNode.RelativeMoveTo -> {
                        x += node.dx * scale
                        y += node.dy * scale
                        path.moveTo(x, y)
                        startX = x
                        startY = y
                    }

                    is PathNode.LineTo -> {
                        x = node.x * scale
                        y = node.y * scale
                        path.lineTo(x, y)
                    }

                    is PathNode.RelativeLineTo -> {
                        x += node.dx * scale
                        y += node.dy * scale
                        path.lineTo(x, y)
                    }

                    is PathNode.HorizontalTo -> {
                        x = node.x * scale
                        path.lineTo(x, y)
                    }

                    is PathNode.RelativeHorizontalTo -> {
                        x += node.dx * scale
                        path.lineTo(x, y)
                    }

                    is PathNode.VerticalTo -> {
                        y = node.y * scale
                        path.lineTo(x, y)
                    }

                    is PathNode.RelativeVerticalTo -> {
                        y += node.dy * scale
                        path.lineTo(x, y)
                    }

                    is PathNode.CurveTo -> {
                        path.cubicTo(
                            node.x1 * scale, node.y1 * scale,
                            node.x2 * scale, node.y2 * scale,
                            node.x3 * scale, node.y3 * scale,
                        )
                        x = node.x3 * scale
                        y = node.y3 * scale
                    }

                    is PathNode.RelativeCurveTo -> {
                        path.cubicTo(
                            x + node.dx1 * scale, y + node.dy1 * scale,
                            x + node.dx2 * scale, y + node.dy2 * scale,
                            x + node.dx3 * scale, y + node.dy3 * scale,
                        )
                        x += node.dx3 * scale
                        y += node.dy3 * scale
                    }

                    is PathNode.ReflectiveCurveTo -> {
                        path.cubicTo(x, y, node.x1 * scale, node.y1 * scale, node.x2 * scale, node.y2 * scale)
                        x = node.x2 * scale
                        y = node.y2 * scale
                    }

                    is PathNode.RelativeReflectiveCurveTo -> {
                        path.cubicTo(
                            x, y,
                            x + node.dx1 * scale, y + node.dy1 * scale,
                            x + node.dx2 * scale, y + node.dy2 * scale,
                        )
                        x += node.dx2 * scale
                        y += node.dy2 * scale
                    }

                    is PathNode.ArcTo -> {
                        // Tabler draws the globe and the microphone with arcs. A
                        // straight line here is what made the globe look like a
                        // stack of three bars, so the arc is converted to curves.
                        val endX = node.arcStartX * scale
                        val endY = node.arcStartY * scale
                        arcToCurves(
                            path,
                            x, y,
                            node.horizontalEllipseRadius * scale,
                            node.verticalEllipseRadius * scale,
                            node.theta,
                            node.isMoreThanHalf,
                            node.isPositiveArc,
                            endX, endY,
                        )
                        x = endX
                        y = endY
                    }

                    is PathNode.RelativeArcTo -> {
                        // Skipped: the relative arc's fields differ between Compose
                        // versions, and Tabler's icons do not use it. Better a
                        // missing segment than a converter that will not compile.
                        Unit
                    }

                    is PathNode.Close -> {
                        path.close()
                        x = startX
                        y = startY
                    }

                    // Quad and relative-arc nodes: not used by Tabler, and their
                    // field names have moved between Compose versions.
                    else -> Unit
                }
            }
        }

        return Rendered(path, strokeWidth.coerceAtLeast(1.4f), filled)
    }

    /**
     * Converts an SVG-style elliptical arc into cubic Bézier segments, following
     * the endpoint-to-centre conversion in the SVG 1.1 implementation notes. Each
     * segment spans at most a quarter turn, which keeps the approximation within a
     * fraction of a pixel at key sizes.
     */
    private fun arcToCurves(
        path: Path,
        x1: Float,
        y1: Float,
        radiusX: Float,
        radiusY: Float,
        rotationDeg: Float,
        largeArc: Boolean,
        sweep: Boolean,
        x2: Float,
        y2: Float,
    ) {
        var rx = Math.abs(radiusX).toDouble()
        var ry = Math.abs(radiusY).toDouble()
        if (rx == 0.0 || ry == 0.0 || (x1 == x2 && y1 == y2)) {
            path.lineTo(x2, y2)
            return
        }

        val phi = Math.toRadians(rotationDeg.toDouble())
        val cosPhi = Math.cos(phi)
        val sinPhi = Math.sin(phi)

        val halfDx = (x1 - x2) / 2.0
        val halfDy = (y1 - y2) / 2.0
        val x1p = cosPhi * halfDx + sinPhi * halfDy
        val y1p = -sinPhi * halfDx + cosPhi * halfDy

        // Radii too small to span the endpoints are scaled up, not rejected.
        val lambda = (x1p * x1p) / (rx * rx) + (y1p * y1p) / (ry * ry)
        if (lambda > 1.0) {
            val scaleUp = Math.sqrt(lambda)
            rx *= scaleUp
            ry *= scaleUp
        }

        val rx2 = rx * rx
        val ry2 = ry * ry
        val numerator = rx2 * ry2 - rx2 * y1p * y1p - ry2 * x1p * x1p
        val denominator = rx2 * y1p * y1p + ry2 * x1p * x1p
        var coefficient = Math.sqrt(Math.max(0.0, numerator / denominator))
        if (largeArc == sweep) coefficient = -coefficient

        val cxp = coefficient * rx * y1p / ry
        val cyp = -coefficient * ry * x1p / rx
        val cx = cosPhi * cxp - sinPhi * cyp + (x1 + x2) / 2.0
        val cy = sinPhi * cxp + cosPhi * cyp + (y1 + y2) / 2.0

        val startAngle = angleBetween(1.0, 0.0, (x1p - cxp) / rx, (y1p - cyp) / ry)
        var sweepAngle = angleBetween(
            (x1p - cxp) / rx, (y1p - cyp) / ry,
            (-x1p - cxp) / rx, (-y1p - cyp) / ry,
        )
        if (!sweep && sweepAngle > 0) sweepAngle -= 2 * Math.PI
        if (sweep && sweepAngle < 0) sweepAngle += 2 * Math.PI

        val segments = Math.ceil(Math.abs(sweepAngle) / (Math.PI / 2)).toInt().coerceAtLeast(1)
        val delta = sweepAngle / segments
        val t = 4.0 / 3.0 * Math.tan(delta / 4.0)

        fun pointAt(theta: Double): DoubleArray {
            val c = Math.cos(theta)
            val s = Math.sin(theta)
            return doubleArrayOf(
                cx + rx * c * cosPhi - ry * s * sinPhi,
                cy + rx * c * sinPhi + ry * s * cosPhi,
            )
        }

        fun tangentAt(theta: Double): DoubleArray {
            val c = Math.cos(theta)
            val s = Math.sin(theta)
            return doubleArrayOf(
                -rx * s * cosPhi - ry * c * sinPhi,
                -rx * s * sinPhi + ry * c * cosPhi,
            )
        }

        var theta = startAngle
        for (i in 0 until segments) {
            val thetaEnd = theta + delta
            val pa = pointAt(theta)
            val pb = pointAt(thetaEnd)
            val da = tangentAt(theta)
            val db = tangentAt(thetaEnd)
            path.cubicTo(
                (pa[0] + t * da[0]).toFloat(), (pa[1] + t * da[1]).toFloat(),
                (pb[0] - t * db[0]).toFloat(), (pb[1] - t * db[1]).toFloat(),
                pb[0].toFloat(), pb[1].toFloat(),
            )
            theta = thetaEnd
        }
    }

    /** The signed angle from vector u to vector v. */
    private fun angleBetween(ux: Double, uy: Double, vx: Double, vy: Double): Double =
        Math.atan2(ux * vy - uy * vx, ux * vx + uy * vy)

    /**
     * An ImageVector keeps its paths inside a tree of groups, and a group can
     * carry its own transform. The transform is ignored here: every Tabler icon
     * is flat, and honouring it would mean carrying a Matrix through the walk.
     */
    private fun collectPaths(group: VectorGroup, out: MutableList<VectorPath>) {
        for (node in group) {
            when (node) {
                is VectorPath -> out.add(node)
                is VectorGroup -> collectPaths(node, out)
                else -> Unit
            }
        }
    }
}
