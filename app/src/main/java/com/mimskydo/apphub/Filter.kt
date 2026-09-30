package com.mimskydo.apphub

/**
 * What a search field matches against: letters and digits only, lower-cased.
 *
 * Comparing the label and the package as one string is what lets a package
 * fragment find its app - `apphub` finds *App Hub*, `organicmaps` finds
 * `app.organicmaps` - and dropping the spaces and the dots means a driver can
 * type the two words they see instead of the punctuation they cannot.
 *
 * One implementation, because the main board and the shortcuts list narrow the
 * same apps and "why does this find it and that one not" is a question with no
 * good answer.
 */
object Filter {

    /** The squashed form of an app's own text, computed once per entry. */
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

    /** [needle] is expected to be squashed already; an empty one matches everything. */
    fun matches(key: String, needle: String): Boolean =
        needle.isEmpty() || key.contains(needle)
}
