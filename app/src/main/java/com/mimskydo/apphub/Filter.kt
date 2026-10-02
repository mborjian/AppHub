package com.mimskydo.apphub

object Filter {

    fun key(vararg parts: CharSequence?): String {
        val key = StringBuilder()
        for (part in parts) {
            if (part == null) continue
            for (ch in part) {
                if (ch.isLetterOrDigit()) key.append(Character.toLowerCase(ch))
            }
        }
        return key.toString()
    }

    fun matches(key: String, needle: String): Boolean =
        needle.isEmpty() || key.contains(needle)
}
