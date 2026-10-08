package com.lookie.opluswubi.table

import com.lookie.opluswubi.XLog
import java.io.BufferedReader
import java.io.File
import java.io.InputStream
import java.io.InputStreamReader

/**
 * 一份已索引的自定义码表。
 *
 * 设计取舍：码表可能有十几万条（例如「虎码字词」约 17 万条），
 * 因此不用 `Map<String, List<String>>`（对象开销过大），而是
 * 「按码排序的两个平行数组 + 二分查找」。等长区间就是同一个码的多个词。
 */
class CodeTable private constructor(
    private val codes: Array<String>,
    private val words: Array<String>,
) {

    /** 条目总数。 */
    val entryCount: Int get() = codes.size

    /** 最长编码长度，用于判断「还该不该做前缀匹配」。 */
    val maxCodeLength: Int = codes.maxOfOrNull { it.length } ?: 0

    /**
     * 精确匹配：返回编码完全等于 [code] 的所有词，顺序沿用码表文件里的原始顺序。
     */
    fun lookupExact(code: String): List<String> {
        val at = lowerBound(code)
        if (at >= codes.size || codes[at] != code) return emptyList()
        val out = ArrayList<String>(2)
        var i = at
        while (i < codes.size && codes[i] == code) {
            out.add(words[i])
            i++
        }
        return out
    }

    /**
     * 前缀匹配：返回编码以 [code] 开头、但比 [code] 更长的词，按「码越短越靠前」排序。
     *
     * 上限 [limit] 用来防止用户只敲一个字母时把候选列表刷爆。
     */
    fun lookupPrefix(code: String, limit: Int): List<String> {
        if (limit <= 0 || code.length >= maxCodeLength) return emptyList()
        val start = lowerBound(code)
        if (start >= codes.size || !codes[start].startsWith(code)) return emptyList()

        // 先收集，再按码长稳定排序：五笔里短码优先，更符合肌肉记忆
        val collected = ArrayList<Pair<String, String>>(limit * 2)
        var i = start
        while (i < codes.size && codes[i].startsWith(code)) {
            val c = codes[i]
            if (c.length > code.length) {
                collected.add(c to words[i])
                if (collected.size >= limit * 4) break
            }
            i++
        }
        if (collected.isEmpty()) return emptyList()
        collected.sortWith(compareBy({ it.first.length }, { it.first }))
        val out = ArrayList<String>(limit)
        val seen = HashSet<String>(limit * 2)
        for ((_, word) in collected) {
            if (seen.add(word)) out.add(word)
            if (out.size >= limit) break
        }
        return out
    }

    /**
     * 表里是否还存在「以 [code] 为前缀、但更长」的编码。
     *
     * 用来判断一个码是不是已经打完整了（判断「自定义方案顶屏」该不该上屏）。
     */
    fun hasLongerCode(code: String): Boolean {
        var i = lowerBound(code)
        while (i < codes.size && codes[i].startsWith(code)) {
            if (codes[i].length > code.length) return true
            i++
        }
        return false
    }

    /** 该编码在表中的所有可能候选（精确 + 前缀），[maxPrefix] 为前缀候选上限。 */
    fun lookup(code: String, maxPrefix: Int): List<String> {        val exact = lookupExact(code)
        val prefix = if (maxPrefix > 0) lookupPrefix(code, maxPrefix) else emptyList()
        if (prefix.isEmpty()) return exact
        if (exact.isEmpty()) return prefix
        val out = ArrayList<String>(exact.size + prefix.size)
        out.addAll(exact)
        val seen = HashSet<String>(exact)
        for (w in prefix) if (seen.add(w)) out.add(w)
        return out
    }

    /** 找出第一个 codes[i] >= code 的下标。 */
    private fun lowerBound(code: String): Int {
        var lo = 0
        var hi = codes.size
        while (lo < hi) {
            val mid = (lo + hi) ushr 1
            if (codes[mid] < code) lo = mid + 1 else hi = mid
        }
        return lo
    }

    companion object {

        private const val TAG = "CodeTable"

        /** 从「已规范化」的码表文件加载（每行 `编码\t词`）。 */
        fun load(file: File): CodeTable? {
            if (!file.isFile || file.length() == 0L) return null
            val codes = ArrayList<String>(1 shl 18)
            val words = ArrayList<String>(1 shl 18)
            return try {
                file.bufferedReader(Charsets.UTF_8).useLines { seq ->
                    for (line in seq) {
                        if (line.isEmpty()) continue
                        val tab = line.indexOf('\t')
                        if (tab <= 0 || tab == line.length - 1) continue
                        codes.add(line.substring(0, tab))
                        words.add(line.substring(tab + 1))
                    }
                }
                if (codes.isEmpty()) return null
                // 文件在写入时已排序，这里只做一次防御性校验
                val table = CodeTable(codes.toTypedArray(), words.toTypedArray())
                XLog.i("码表已加载：${file.name}，共 ${table.entryCount} 条，最长码长 ${table.maxCodeLength}")
                table
            } catch (t: Throwable) {
                XLog.e("码表加载失败：${file.absolutePath}", t)
                null
            }
        }

        /**
         * 解析用户导入的原始 txt 码表。
         *
         * 兼容三种常见格式：
         *  - `词<TAB>码`（「虎码字词」等大多数分享码表）
         *  - `码<TAB>词`
         *  - `词,码` / `词 码`（搜狗、百度自定义短语导出）
         *
         * 列顺序靠采样自动判定，判不出来时按「第二列是码」处理。
         */
        fun parse(input: InputStream): ParseResult {
            val rows = ArrayList<Pair<String, String>>(1 shl 18)
            val samples = ArrayList<Pair<String, String>>(512)
            var total = 0
            var skipped = 0
            var firstColumnIsCode = -1 // -1 未知, 0 否, 1 是

            BufferedReader(InputStreamReader(input, Charsets.UTF_8), 1 shl 16).useLines { seq ->
                for (rawLine in seq) {
                    val line = rawLine.trim().trimStart('\uFEFF')
                    if (line.isEmpty() || line.startsWith("#") || line.startsWith("//")) continue
                    total++
                    val pair = splitLine(line)
                    if (pair == null) {
                        skipped++
                        continue
                    }
                    if (samples.size < 512) samples.add(pair)
                    rows.add(pair)
                }
            }

            if (samples.isNotEmpty()) {
                var firstLooksLikeCode = 0
                var secondLooksLikeCode = 0
                for ((a, b) in samples) {
                    if (isCode(a)) firstLooksLikeCode++
                    if (isCode(b)) secondLooksLikeCode++
                }
                firstColumnIsCode = when {
                    secondLooksLikeCode >= samples.size * 9 / 10 -> 0
                    firstLooksLikeCode >= samples.size * 9 / 10 -> 1
                    firstLooksLikeCode > secondLooksLikeCode -> 1
                    else -> 0
                }
            }

            val entries = ArrayList<Pair<String, String>>(rows.size)
            for ((a, b) in rows) {
                val code = (if (firstColumnIsCode == 1) a else b).lowercase()
                val word = if (firstColumnIsCode == 1) b else a
                if (!isCode(code) || word.isEmpty() || word.any { it == '\t' || it == '\n' || it == '\r' }) {
                    skipped++
                    continue
                }
                entries.add(code to word)
            }

            // 按码排序，让后续二分查找成立；Kotlin 的 sort 是稳定排序，
            // 因此同一码的多个词会保持文件里的原始优先级。
            entries.sortBy { it.first }

            XLog.i("码表解析完成：总行 $total，有效 ${entries.size}，跳过 $skipped，首列为码=${firstColumnIsCode == 1}")
            return ParseResult(entries, total, skipped, firstColumnIsCode == 1)
        }

        private fun splitLine(line: String): Pair<String, String>? {
            val tab = line.indexOf('\t')
            if (tab > 0) {
                val a = line.substring(0, tab).trim()
                val b = line.substring(tab + 1).trim()
                if (a.isNotEmpty() && b.isNotEmpty()) return a to b
            }
            val comma = line.indexOf(',')
            if (comma > 0) {
                val a = line.substring(0, comma).trim()
                val b = line.substring(comma + 1).trim()
                if (a.isNotEmpty() && b.isNotEmpty()) return a to b
            }
            // 空格分隔：最后一个 token 当码，前面整体当词
            val sp = line.lastIndexOf(' ')
            if (sp > 0) {
                val a = line.substring(0, sp).trim()
                val b = line.substring(sp + 1).trim()
                if (a.isNotEmpty() && b.isNotEmpty()) return a to b
            }
            return null
        }

        private fun isCode(s: String): Boolean {
            if (s.isEmpty() || s.length > 6) return false
            for (ch in s) {
                if (ch !in 'a'..'z' && ch !in 'A'..'Z') return false
            }
            return true
        }
    }

    /** 解析结果。 */
    class ParseResult(
        val entries: List<Pair<String, String>>,
        val totalLines: Int,
        val skippedLines: Int,
        val firstColumnIsCode: Boolean,
    )
}
