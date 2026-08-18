package one.moveo.studycore

/// Setup-code helpers — port of `normalizeCode` / `groupCode` from the
/// extension's `src/constants.js` (via iOS `Codes.swift`).
object Codes {
    /// Normalize a setup code as typed/pasted by a user or read from a URL:
    /// strip separators (whitespace and dashes), uppercase. Crockford
    /// ambiguity mapping (O→0, I/L→1) is the backend's job — the app sends
    /// the normalized code as-is. Length range is deliberately loose (4–32)
    /// so code length stays a backend decision.
    fun normalize(raw: String?): String? {
        if (raw == null) return null
        // JS \s includes Unicode space separators (NBSP…) and the BOM; Java's
        // isWhitespace misses NBSP, so pair it with isSpaceChar.
        val code = raw
            .filterNot {
                it.isWhitespace() || Character.isSpaceChar(it) || it == '-' || it == '\uFEFF'
            }
            .uppercase()
        if (code.length !in 4..32) return null
        if (!code.all { it in '0'..'9' || it in 'A'..'Z' }) return null
        return code
    }

    /// Grouped display form, e.g. `7TQ2M4K9XW3FZ` → `7TQ2-M4K9-XW3FZ`.
    /// A trailing 1–2 char group reads worse than a longer final group, so it
    /// merges into the previous one (13-char codes show as 4-4-5).
    fun group(code: String): String {
        val groups = code.chunked(4).toMutableList()
        if (groups.size > 1 && groups.last().length < 3) {
            val last = groups.removeAt(groups.size - 1)
            groups[groups.size - 1] = groups[groups.size - 1] + last
        }
        return groups.joinToString("-")
    }
}
