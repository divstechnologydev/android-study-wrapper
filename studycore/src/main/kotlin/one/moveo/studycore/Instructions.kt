package one.moveo.studycore

/// `study.instructions` — participant-facing task text written by the study
/// author and shown on the consent screen. Port of the extension's
/// `src/instructions.js` (config-schema.md §2.1; ← iOS Instructions.swift);
/// the rule table there is normative and `InstructionsTests` pins the same
/// cases.
///
/// Security: this is the only place author prose reaches the app's own UI,
/// so it is PLAIN TEXT, always. There is no Markdown, no HTML, no links —
/// the parser emits a small block structure and the screen renders every
/// string as a literal Compose `Text` (no annotated strings, no linkify), so
/// nothing in the string can become markup or a tappable link.
///
/// Rules, in order:
///   1. Line endings normalize (`\r\n`, `\r`, U+2028, U+2029 → newline).
///   2. Tabs become spaces; every line is trimmed.
///   3. Unsafe characters are stripped: C0/C1 controls, zero-width
///      characters and bidi overrides (which could visually reorder a
///      sentence — never allowed on a consent screen).
///   4. A blank line ends the current block; repeats collapse.
///   5. A line starting with `- ` or `* ` (marker + whitespace + content)
///      is a bullet; consecutive bullets form one list.
///   6. A single newline inside a paragraph is kept as a line break.
///   7. Everything else is literal text.
///   8. Longer than `MAX_LENGTH` is rejected by the validator; the parser
///      additionally truncates as defence in depth.
///   9. Nothing to show ⇒ no blocks (the section is hidden).
object Instructions {
    const val MAX_LENGTH = 2000

    sealed class Block {
        /// Lines are rendered one under another (rule 6).
        data class Paragraph(val lines: List<String>) : Block()

        /// Marker already removed.
        data class ListBlock(val items: List<String>) : Block()
    }

    fun parse(raw: String?): List<Block> {
        if (raw.isNullOrEmpty()) return emptyList()

        // Kotlin String.length is UTF-16 units, exactly JS `.length` /
        // `slice`; a split surrogate pair renders as U+FFFD, same as JS's
        // lone surrogate would.
        var text = if (raw.length > MAX_LENGTH) raw.substring(0, MAX_LENGTH) else raw
        text = text
            .replace("\r\n", "\n")
            .replace('\r', '\n')
            .replace('\u2028', '\n')
            .replace('\u2029', '\n')
            .replace('\t', ' ')
        text = buildString(text.length) {
            var i = 0
            while (i < text.length) {
                val cp = text.codePointAt(i)
                if (!isUnsafe(cp)) appendCodePoint(cp)
                i += Character.charCount(cp)
            }
        }

        val blocks = mutableListOf<Block>()
        var paragraph: MutableList<String>? = null
        var list: MutableList<String>? = null

        fun flush() {
            paragraph?.let { blocks += Block.Paragraph(it.toList()) }
            list?.let { blocks += Block.ListBlock(it.toList()) }
            paragraph = null
            list = null
        }

        for (rawLine in text.split('\n')) {
            val line = rawLine.trim()

            if (line.isEmpty()) {
                flush() // blank line = block boundary (repeats collapse to one)
                continue
            }

            // Only a marker WITH content is a bullet (`^[-*]\s+`): at least
            // one whitespace after "-"/"*", so a lone "-" line stays literal.
            val item = bulletContent(line)
            if (item != null) {
                paragraph?.let {
                    blocks += Block.Paragraph(it.toList())
                    paragraph = null
                }
                list = (list ?: mutableListOf()).apply { add(item) }
                continue
            }

            list?.let {
                blocks += Block.ListBlock(it.toList())
                list = null
            }
            paragraph = (paragraph ?: mutableListOf()).apply { add(line) }
        }

        flush()
        return blocks
    }

    /// `line.replace(/^[-*]\s+/, "").trim()` when the line matches, else null.
    private fun bulletContent(line: String): String? {
        val marker = line.firstOrNull() ?: return null
        if (marker != '-' && marker != '*') return null
        val rest = line.substring(1)
        val content = rest.trimStart()
        if (content.length == rest.length) return null // no whitespace after the marker
        return content.trim()
    }

    /// The extension's UNSAFE_CHARS class (newline and tab are handled
    /// before this runs).
    private fun isUnsafe(cp: Int): Boolean = when (cp) {
        in 0x0000..0x0008, 0x000B, 0x000C, in 0x000E..0x001F, in 0x007F..0x009F,
        in 0x200B..0x200F, in 0x202A..0x202E, in 0x2066..0x2069, 0xFEFF -> true
        else -> false
    }
}
