package com.mrj.fancyai.memory

/** Language-agnostic keyword tokens shared by recall ranking and the memory graph. */
fun memoryKeywords(text: String): List<String> {
    return text.lowercase().split(Regex("[^\\p{L}\\p{N}]+"))
        .filter { it.length >= 2 }.distinct()
        .flatMap { token ->
            if (token.any { it in '㐀'..'鿿' || it in '぀'..'ヿ' || it in '가'..'힯' }) listOf(token) + token.windowed(2)
            else listOf(token)
        }.distinct()
}
