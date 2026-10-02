package com.mimskydo.apphub

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

object IconMasker {

    fun masked(resources: Resources, source: Drawable, shape: IconShape, sizePx: Int): Drawable {
        if (shape == IconShape.ORIGINAL || sizePx <= 0) return source

        val bitmap = Bitmap.createBitmap(sizePx, sizePx, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        canvas.clipPath(path(shape, sizePx.toFloat()))
        drawInto(resources, source, canvas, sizePx)
        return BitmapDrawable(resources, bitmap)
    }

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

    private fun copy(resources: Resources, source: Drawable?): Drawable? =
        source?.constantState?.newDrawable(resources)?.mutate() ?: source

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

            IconShape.IPHONE -> superellipse(path, size, 5f)

            IconShape.SAMSUNG -> superellipse(path, size, 3.1f)

            IconShape.ORIGINAL ->
                path.addRect(0f, 0f, size, size, Path.Direction.CW)
        }
        return path
    }

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
