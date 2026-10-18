package eu.tsernikov.slminiwifi.ui

import androidx.compose.ui.text.TextLayoutResult
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue

/**
 * Compose's semantics recreates a paragraph at the full parent constraint width, even when
 * Text measured itself to its shorter content. Its hasVisualOverflow therefore reports short
 * labels (e.g. Back) as overflowing. Check actual line bounds and truncation instead.
 */
internal fun assertTextFits(layout: TextLayoutResult) {
    val label = "Text '${layout.layoutInput.text}' (${layout.size})"
    assertFalse("$label exceeds its line limit", layout.multiParagraph.didExceedMaxLines)
    assertTrue("$label exceeds its height", layout.multiParagraph.height <= layout.size.height)
    repeat(layout.lineCount) { line ->
        assertFalse("$label is ellipsized on line $line", layout.isLineEllipsized(line))
        assertTrue("$label starts outside its width on line $line", layout.getLineLeft(line) >= 0f)
        assertTrue("$label ends outside its width on line $line: ${layout.getLineRight(line)}",
            layout.getLineRight(line) <= layout.size.width)
    }
}
