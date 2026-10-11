package com.flownotes.ai

/**
 * Free, offline clean-up used when no Anthropic key is set. Rule-based, so it's far simpler than
 * the Claude rewrite: it removes hesitations and stutters, applies explicit spoken edits
 * ("scratch that", "new paragraph", "bullet point") and tidies punctuation, but it can't
 * understand meaning (e.g. "five, actually six" stays as spoken).
 */
object LocalCleaner {

    // Pure hesitation sounds: always safe to drop.
    private val hesitations = Regex("""(?i)(?<![\p{L}'])(?:u+m+|u+h+m*|e+r+m*|a+h+|h+m+|m+h*m+)(?![\p{L}'])[,.]?\s*""")

    // Discourse fillers: only dropped when set off by commas or at a sentence start, where they
    // carry no meaning ("I like it" and "you know her" stay intact).
    private val fillerPhrases = listOf("you know", "i mean", "like", "basically", "so yeah", "okay so", "you see")
    private val commaFiller = Regex(
        """(?i),\s*(?:${fillerPhrases.joinToString("|") { Regex.escape(it) }})\s*,""",
    )
    private val leadingFiller = Regex(
        """(?i)(^\s*|[.!?]\s+)(?:so\s+)?(?:${fillerPhrases.joinToString("|") { Regex.escape(it) }})\s*,\s*""",
    )

    // "I I think", "the the", "we-we"
    private val repeatedWord = Regex("""(?i)\b([\p{L}']+)(?:[\s,-]+\1\b)+""")

    // "…previous sentence. Scratch that." -> drops the previous sentence
    private val scratch = Regex("""(?i)[^.!?\n]*[.!?]?\s*(?:scratch that|delete that|strike that|forget that)[.!?,]?\s*""")

    fun clean(raw: String): String {
        var t = " " + raw.trim() + " "
        t = t.replace(hesitations, "")
        t = t.replace(commaFiller, " ") // "we should, like, go" -> "we should go"
        t = t.replace(leadingFiller, "$1")
        t = t.replace(repeatedWord, "$1")
        t = t.replace(scratch, "")
        t = spokenCommands(t)
        t = tidy(t)
        return t
    }

    fun title(text: String): String {
        val firstSentence = text.lineSequence().map { it.trim().removePrefix("- ") }.firstOrNull { it.isNotBlank() }
            ?.split(Regex("""(?<=[.!?])\s"""))?.firstOrNull().orEmpty()
        val words = firstSentence.trimEnd('.', '!', '?', ',').split(Regex("""\s+""")).filter { it.isNotBlank() }
        return words.take(7).joinToString(" ").ifBlank { "Voice note" }
    }

    private fun spokenCommands(text: String): String = text
        .replace(Regex("""(?i)[,.]?\s*\bnew paragraph\b[,.]?\s*"""), "\n\n")
        .replace(Regex("""(?i)[,.]?\s*\bnew line\b[,.]?\s*"""), "\n")
        .replace(Regex("""(?i)[,.]?\s*\b(?:bullet point|next bullet|next point)\b[,.:]?\s*"""), "\n- ")

    private fun tidy(text: String): String {
        val lines = text.split("\n").map { line ->
            var l = line.replace(Regex("""\s+"""), " ").trim()
            l = l.replace(Regex("""\s+([,.!?;:])"""), "$1")      // no space before punctuation
                .replace(Regex("""([,;:])(?=\S)"""), "$1 ")      // space after
                .replace(Regex(""",\s*([.!?])"""), "$1")         // ", ." -> "."
                .replace(Regex("""^[,.;:]\s*"""), "")            // stray leading punctuation
                .replace(Regex("""^-\s*[,.;:]?\s*"""), "- ")
            capitalizeSentences(l)
        }
        return lines.joinToString("\n").replace(Regex("""\n{3,}"""), "\n\n").trim()
    }

    private fun capitalizeSentences(line: String): String {
        val sb = StringBuilder(line)
        var capNext = true
        for (i in sb.indices) {
            val c = sb[i]
            if (capNext && c.isLetter()) {
                sb.setCharAt(i, c.uppercaseChar())
                capNext = false
            } else if (c == '.' || c == '!' || c == '?') {
                capNext = true
            } else if (!c.isWhitespace() && c != '-' && c != '"') {
                capNext = false
            }
        }
        return sb.toString()
    }
}
