package com.mahdi.apphub

import android.content.res.Resources
import android.graphics.drawable.Drawable
import android.util.LruCache

/**
 * The drawn version of an app's icon, in one place.
 *
 * Masking costs a bitmap per icon, and three screens draw the same icons - the
 * board, the list of shortcuts, the list of app windows - and the app card does
 * too. One cache keyed by the package, the size and the shape means each bitmap
 * is made once, and it means the four of them cannot disagree about what an icon
 * looks like: change the icon shape in the settings and every icon in the app
 * changes with it, which is what a shape setting is promising.
 */
object IconCache {

    /** 192 entries: 20-ish icons at a handful of sizes, with room to move */
    private val cache = LruCache<String, Drawable>(192)

    /**
     * The icon for [entry], clipped into [shape] and rasterised at [sizePx].
     *
     * [IconShape.ORIGINAL] hands back the app's own drawable, untouched - the
     * one case where the artwork is the launcher's business rather than ours.
     */
    fun drawn(resources: Resources, entry: AppEntry, shape: IconShape, sizePx: Int): Drawable {
        val key = "${entry.packageName}|$sizePx|${shape.name}"
        cache.get(key)?.let { return it }
        val drawn = IconMasker.masked(resources, entry.icon, shape, sizePx)
        cache.put(key, drawn)
        return drawn
    }

    fun clear() = cache.evictAll()
}
