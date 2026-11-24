package io.tinychat.feature.chat

import java.util.regex.Pattern

private const val BURST_FRAMES = 24
private const val MAX_GRAPHEMES_PER_FRAME = 18
private val graphemePattern = Pattern.compile("\\X")

/** Advances through buffered reasoning at a steady pace, including when a provider sends a burst. */
internal class ThinkingReveal(initialEnd: Int = 0) {
    private var target: String? = null
    private val graphemes = graphemePattern.matcher("")
    private var step = 1

    var end = initialEnd
        private set
    private var available = 0
    val hasPending: Boolean get() = end < available

    fun update(text: String) {
        val previous = target
        if (previous === text) return

        // A provider may flush between UTF-16 code units. Hold the unmatched half until the next chunk.
        available = text.length - if (text.lastOrNull()?.isHighSurrogate() == true) 1 else 0
        if (end > available || (previous != null && !text.regionMatches(0, previous, 0, end))) {
            // A replacement is already current. Avoid replaying the whole block.
            end = available
        }
        target = text
        graphemes.reset(text).region(end, available)
        step = ((available - end + BURST_FRAMES - 1) / BURST_FRAMES)
            .coerceIn(1, MAX_GRAPHEMES_PER_FRAME)
    }

    fun advance(): Int {
        repeat(step) {
            if (graphemes.find()) end = graphemes.end() else return end
        }
        return end
    }

    fun skip(): Int {
        end = available
        graphemes.region(end, available)
        return end
    }
}
