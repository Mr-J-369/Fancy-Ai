package com.mrj.fancyai.service.memory

import java.text.Normalizer
import java.util.Locale

internal fun isCjk(c: Char): Boolean =
    (c in '㐀'..'鿿') || (c in '぀'..'ヿ') || (c in '가'..'힯')

private fun normalizeArabic(token: String): String {
    var out = token.replace(Regex("[\u064B-\u0652\u0640]"), "")
    out = out.replace(Regex("[\u0623\u0625\u0622]"), "\u0627")
        .replace("\u0629", "\u0647")
        .replace("\u0649", "\u064A")
    return out
}

private val stopwords = setOf(
    "the", "a", "an", "and", "or", "but", "is", "are", "was", "were", "be", "to", "of", "in", "on",
    "for", "with", "what", "how", "do", "does", "did", "i", "my", "you", "your", "it", "this", "that",
    "el", "la", "los", "las", "un", "una", "es", "son", "de", "en", "para", "con", "que", "como",
    "mi", "tu", "su", "este", "esta",
    "le", "les", "une", "des", "et", "ou", "est", "sont", "pour", "avec", "comment", "faire",
    "mon", "ton", "ce", "cette",
    "der", "die", "das", "ein", "eine", "und", "oder", "ist", "sind", "war", "von", "für", "mit",
    "wie", "mein", "dein", "sein", "diese",
    "os", "as", "uma", "é", "são", "era", "em", "fazer", "meu", "seu", "isso",
    "и", "или", "это", "есть", "был", "для", "что", "как", "делать", "мой", "твой", "его",
    "этот", "эта", "не",
    "و", "أو", "هو", "هي", "في", "على", "من", "ما", "كيف", "هذا", "هذه", "الذي", "التي", "لا",
    "और", "या", "है", "थे", "में", "पर", "लिए", "से", "क्या", "कैसे", "मेरा", "यह", "वह", "नहीं",
    "dan", "atau", "adalah", "untuk", "dengan", "apa", "bagaimana", "saya", "kamu", "dia",
    "ini", "itu", "yang", "tidak",
    "的", "了", "是", "在", "和", "或", "这", "那", "吗", "呢",
)

/** Language-agnostic keyword tokens shared by recall ranking and the memory graph. */
fun memoryKeywords(text: String, filterStops: Boolean = true): List<String> {
    val normalized = Normalizer.normalize(text.lowercase(Locale.ROOT), Normalizer.Form.NFKC)
    val tokens = normalized.split(Regex("[^\\p{L}\\p{N}]+"))
        .asSequence()
        .filter { it.length >= 2 || it.any(::isCjk) }.distinct()
        .flatMap { token ->
            if (token.any(::isCjk)) {
                listOf(token) + token.windowed(2) + token.map(Char::toString)
            } else {
                listOf(normalizeArabic(token))
            }
        }.filter { it.length >= 2 || (it.length == 1 && it[0].let(::isCjk)) }.distinct()
        .toList()
    return if (filterStops) tokens.filter { it !in stopwords } else tokens
}
