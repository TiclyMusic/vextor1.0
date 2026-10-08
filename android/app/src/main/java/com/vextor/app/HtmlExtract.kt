package com.vextor.app

/** Parte di una risposta: testo normale o blocco di codice. */
sealed interface Segment {
    data class Text(val text: String) : Segment
    data class Code(val lang: String, val code: String, val complete: Boolean) : Segment {
        val isHtml: Boolean
            get() = lang.equals("html", true) || lang.isEmpty() && code.contains("<html", true) ||
                code.trimStart().startsWith("<!DOCTYPE", true)
    }
}

object HtmlExtract {
    private val fence = Regex("```([\\w+-]*)[^\\n]*\\n")

    /** Divide la risposta in testo e blocchi di codice (anche se l'ultimo non è chiuso). */
    fun segments(answer: String): List<Segment> {
        val out = mutableListOf<Segment>()
        var pos = 0
        while (pos < answer.length) {
            val open = fence.find(answer, pos)
            if (open == null) {
                answer.substring(pos).takeIf { it.isNotBlank() }?.let { out += Segment.Text(it.trim()) }
                break
            }
            answer.substring(pos, open.range.first).takeIf { it.isNotBlank() }?.let { out += Segment.Text(it.trim()) }
            val start = open.range.last + 1
            val close = answer.indexOf("```", start)
            if (close < 0) {
                out += Segment.Code(open.groupValues[1], answer.substring(start), complete = false)
                break
            }
            out += Segment.Code(open.groupValues[1], answer.substring(start, close).trimEnd(), complete = true)
            pos = close + 3
        }
        // risposta con HTML "nudo" senza blocco ```
        if (out.size == 1 && out[0] is Segment.Text && (out[0] as Segment.Text).text.contains("<html", true)) {
            val t = (out[0] as Segment.Text).text
            val i = t.indexOf("<!DOCTYPE", ignoreCase = true).takeIf { it >= 0 } ?: t.indexOf("<html", ignoreCase = true)
            return listOfNotNull(
                t.substring(0, i).takeIf { it.isNotBlank() }?.let { Segment.Text(it.trim()) },
                Segment.Code("html", t.substring(i), complete = t.contains("</html>", true)),
            )
        }
        return out
    }

    /** L'ultimo documento HTML presente nella risposta, se c'è. */
    fun lastHtml(answer: String): String? =
        segments(answer).filterIsInstance<Segment.Code>().lastOrNull { it.isHtml }?.code
}
