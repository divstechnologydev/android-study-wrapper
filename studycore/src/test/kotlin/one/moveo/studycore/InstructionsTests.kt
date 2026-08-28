package one.moveo.studycore

import one.moveo.studycore.Instructions.Block.ListBlock
import one.moveo.studycore.Instructions.Block.Paragraph
import org.junit.Assert.assertEquals
import org.junit.Test

/// Port of the extension's `run-tests.mjs` §10 (← iOS InstructionsTests) —
/// the consent-screen rendering rules for `study.instructions` must give
/// identical blocks on every client.
class InstructionsTests {
    private fun p(s: String?) = Instructions.parse(s)

    @Test
    fun absentOrBlankYieldsNoBlocks() {
        assertEquals(emptyList<Instructions.Block>(), p(null))
        assertEquals(emptyList<Instructions.Block>(), p(""))
        assertEquals("whitespace only → section hidden", emptyList<Instructions.Block>(), p("   \n\n  "))
    }

    @Test
    fun blankLineSplitsParagraphs() {
        assertEquals(
            listOf(Paragraph(listOf("First para.")), Paragraph(listOf("Second para."))),
            p("First para.\n\nSecond para."),
        )
    }

    @Test
    fun repeatedBlankLinesCollapse() {
        assertEquals(listOf(Paragraph(listOf("A")), Paragraph(listOf("B"))), p("A\n\n\n\nB"))
    }

    @Test
    fun singleNewlineIsLineBreakInsideParagraph() {
        assertEquals(listOf(Paragraph(listOf("Line one", "Line two"))), p("Line one\nLine two"))
    }

    @Test
    fun dashAndStarLinesFormOneList() {
        assertEquals(listOf(ListBlock(listOf("One", "Two"))), p("- One\n* Two"))
    }

    @Test
    fun paragraphFollowedByBulletsNeedsNoBlankLine() {
        assertEquals(
            listOf(Paragraph(listOf("Your task:")), ListBlock(listOf("Open the site", "Add an item"))),
            p("Your task:\n- Open the site\n- Add an item"),
        )
    }

    @Test
    fun textAfterListStartsNewParagraph() {
        assertEquals(listOf(ListBlock(listOf("One")), Paragraph(listOf("Closing note."))), p("- One\nClosing note."))
    }

    @Test
    fun markerWithoutContentIsLiteral() {
        assertEquals(listOf(Paragraph(listOf("-", "-")), ListBlock(listOf("Real"))), p("-\n-   \n- Real"))
    }

    @Test
    fun dashInsideLineIsLiteral() {
        assertEquals(listOf(Paragraph(listOf("Buy milk - then bread"))), p("Buy milk - then bread"))
    }

    @Test
    fun markupIsNeverInterpreted() {
        val s = "**bold** <b>x</b> [link](https://evil.example) `code`"
        assertEquals(listOf(Paragraph(listOf(s))), p(s))
    }

    @Test
    fun crlfAndLoneCrNormalize() {
        assertEquals(listOf(Paragraph(listOf("A")), Paragraph(listOf("B", "C"))), p("A\r\n\r\nB\rC"))
    }

    @Test
    fun tabsBecomeSpacesAndLinesAreTrimmed() {
        assertEquals(listOf(Paragraph(listOf("Indented text"))), p("\tIndented\ttext  "))
    }

    @Test
    fun controlZeroWidthAndBidiCharsStripped() {
        assertEquals(listOf(Paragraph(listOf("safe txt"))), p("safe \u200B\u202Etxt"))
        // Exact extension case: "safe" + U+200B + U+202E + "txt" → "safetxt"
        assertEquals(listOf(Paragraph(listOf("safetxt"))), p("safe\u200B\u202Etxt"))
        assertEquals(listOf(Paragraph(listOf("abcd"))), p("a\u0007b\uFEFFc\u2066d"))
    }

    @Test
    fun unicodeLineSeparatorsActAsLineBreaks() {
        assertEquals(
            listOf(Paragraph(listOf("A", "B")), Paragraph(listOf("C"))),
            p("A\u2028B\u2029\u2029C"),
        )
    }

    @Test
    fun inputLongerThanCapIsTruncatedNotRejected() {
        val long = "x".repeat(Instructions.MAX_LENGTH + 50)
        assertEquals(listOf(Paragraph(listOf("x".repeat(Instructions.MAX_LENGTH)))), p(long))
    }

    @Test
    fun surrogatePairSurvivesInsideTheCap() {
        // Astral characters count as two UTF-16 units, like JS `.length`.
        val emoji = "😀" // 😀
        assertEquals(listOf(Paragraph(listOf("Tap $emoji"))), p("Tap $emoji"))
    }

    @Test
    fun realisticAuthorText() {
        val text = "Thanks for taking part!\n\nYour task:\n- Go to the site\n- Add milk\n\nBrowse as you normally would."
        assertEquals(
            listOf(
                Paragraph(listOf("Thanks for taking part!")),
                Paragraph(listOf("Your task:")),
                ListBlock(listOf("Go to the site", "Add milk")),
                Paragraph(listOf("Browse as you normally would.")),
            ),
            p(text),
        )
    }
}
