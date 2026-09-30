package com.mahdi.apphub

import android.content.res.Resources
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Path
import android.graphics.drawable.AdaptiveIconDrawable
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sin

/**
 * Clipping app icons into a shape.
 *
 * The launcher's own art is an [android.graphics.drawable.AdaptiveIconDrawable]
 * whose mask we cannot influence from here, so the icon is rasterised at the
 * size the grid actually needs and then clipped. Results are cached by the
 * adapter, since this costs a bitmap per icon.
 */
object IconMasker {

    /**
     * @param sizePx edge length to rasterise at.
     * @return [source] untouched for [IconShape.ORIGINAL], else a masked copy.
     */
    fun masked(resources: Resources, source: Drawable, shape: IconShape, sizePx: Int): Drawable {
        if (shape == IconShape.ORIGINAL || sizePx <= 0) return source

        val bitmap = Bitmap.createBitmap(sizePx, sizePx, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        canvas.clipPath(path(shape, sizePx.toFloat()))
        drawInto(resources, source, canvas, sizePx)
        return BitmapDrawable(resources, bitmap)
    }

    /**
     * Draws [source] so that it actually fills the clipped square.
     *
     * An [AdaptiveIconDrawable] draws itself inside its own mask, inset to the
     * central two thirds of its bounds - so clipping the *outside* of one
     * changes nothing at all: every shape comes out identical. Its two layers
     * are drawn separately instead. The background layer then fills the whole
     * square and the foreground layer keeps the glyph where the designer put
     * it, which is what makes the chosen shape visible.
     *
     * The layers are *copies*. An [AdaptiveIconDrawable] is one instance shared
     * by every view that shows it, and giving its own layers the bounds of this
     * raster leaves the other views drawing an icon that has been stretched to
     * the wrong size - which is exactly how a black square ended up beside an
     * app's name in the shortcuts list while the same icon was fine on the board.
     */
    private fun drawInto(resources: Resources, source: Drawable, canvas: Canvas, size: Int) {
        if (source is AdaptiveIconDrawable) {
            val background = copy(resources, source.background)
            val foreground = copy(resources, source.foreground)
            if (background != null && foreground != null) {
                background.setBounds(0, 0, size, size)
                background.draw(canvas)
                foreground.setBounds(0, 0, size, size)
                foreground.draw(canvas)
                return
            }
        }
        source.setBounds(0, 0, size, size)
        source.draw(canvas)
    }

    /**
     * A drawable of our own that draws like [source].
     *
     * `mutate()` is what makes it ours: without it the copy would still write
     * every bounds change back into the constant state the app's own icons are
     * built from.
     */
    private fun copy(resources: Resources, source: Drawable?): Drawable? =
        source?.constantState?.newDrawable(resources)?.mutate() ?: source

    /** The clip outline for [shape], filling a [size] x [size] square. */
    fun path(shape: IconShape, size: Float): Path {
        val path = Path()
        when (shape) {
            IconShape.CIRCLE ->
                path.addOval(0f, 0f, size, size, Path.Direction.CW)

            IconShape.ROUNDED ->
                path.addRoundRect(
                    0f, 0f, size, size,
                    size * 0.24f, size * 0.24f, Path.Direction.CW,
                )

            // iOS-style squircle: a superellipse a good deal squarer than a
            // rounded rectangle, which is what makes it read as "iPhone".
            IconShape.IPHONE -> superellipse(path, size, 5f)

            // One UI sits between a circle and the iOS squircle.
            IconShape.SAMSUNG -> superellipse(path, size, 3.1f)

            // ORIGINAL is never masked (handled in `masked`), but a full rect
            // keeps this function total for every shape.
            IconShape.ORIGINAL ->
                path.addRect(0f, 0f, size, size, Path.Direction.CW)
        }
        return path
    }

    /**
     * Samples |x/a|^n + |y/b|^n = 1 into a polygon.
     *
     * n = 2 is a circle, and the shape gets squarer as n grows; the sampled
     * outline is smooth because the points are dense, and it stays cheap
     * because it is built once per (icon, size).
     */
    private fun superellipse(path: Path, size: Float, n: Float) {
        val radius = size / 2f
        val steps = 180
        for (i in 0..steps) {
            val t = i.toFloat() / steps * TWO_PI
            val cosT = cos(t)
            val sinT = sin(t)
            val x = radius + radius * Math.signum(cosT) * abs(cosT).pow(2f / n)
            val y = radius + radius * Math.signum(sinT) * abs(sinT).pow(2f / n)
            if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
        }
        path.close()
    }

    private const val TWO_PI = 6.283_185_5f
}
