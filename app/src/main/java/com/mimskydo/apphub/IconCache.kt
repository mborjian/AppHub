package com.mimskydo.apphub

import android.content.res.Resources
import android.graphics.drawable.Drawable
import android.util.LruCache

object IconCache {

    private val cache = LruCache<String, Drawable>(192)

    fun drawn(resources: Resources, entry: AppEntry, shape: IconShape, sizePx: Int): Drawable {
        val key = "${entry.packageName}|$sizePx|${shape.name}"
        cache.get(key)?.let { return it }
        val drawn = IconMasker.masked(resources, entry.icon, shape, sizePx)
        cache.put(key, drawn)
        return drawn
    }

    fun clear() = cache.evictAll()
}
