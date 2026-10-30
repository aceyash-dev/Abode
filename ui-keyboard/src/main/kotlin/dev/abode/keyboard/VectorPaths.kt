package dev.abode.keyboard

import android.graphics.Path
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathNode

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

        for (vectorPath in vector.paths) {
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
                        x = node.x1 * scale
                        y = node.y1 * scale
                        path.lineTo(x, y)
                    }

                    is PathNode.RelativeArcTo -> {
                        x += node.dx1 * scale
                        y += node.dy1 * scale
                        path.lineTo(x, y)
                    }

                    is PathNode.Close -> {
                        path.close()
                        x = startX
                        y = startY
                    }
                }
            }
        }

        return Rendered(path, strokeWidth.coerceAtLeast(1.4f), filled)
    }
}
