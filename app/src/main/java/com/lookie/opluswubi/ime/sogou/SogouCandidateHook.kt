package com.lookie.opluswubi.ime.sogou

import android.content.Context
import com.lookie.opluswubi.Reflect
import com.lookie.opluswubi.XLog
import com.lookie.opluswubi.hookGuarded
import com.lookie.opluswubi.ime.ImeEnv
import com.lookie.opluswubi.table.ModuleConfig
import io.github.libxposed.api.XposedModule
import java.io.File
import java.lang.reflect.Method
import java.nio.ByteBuffer
import java.nio.charset.Charset
import java.nio.charset.CodingErrorAction
import java.util.Collections
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 「支持简词」的候选排序修正（搜狗输入法）。
 *
 * ## 简词是什么
 *
 * 简词是虎码方案里的**简码词**（官方定义：一简词取词的第一码、二简词取两个字的首码、
 * 三简词取三个字的首码），属**可选项**。所以用户要一个开关来控制它。
 *
 * 用户已经把虎码码表导进搜狗的「管理五笔方案」里了，数据源就是搜狗自己那份：
 * ```
 * <搜狗 filesDir>/wubi/dict/custom_dict_1.txt   （导入的原始码表）
 * <搜狗 filesDir>/wubi/dict/custom_dict_1.zip   （txt 缺失时兜底）
 * ```
 * 目录常量来自 `com.sogou.lib.common.content.a`（`/data/data/<pkg>/files/` + `wubi/dict/`），
 * 文件名来自 `com.sogou.bu.basic.data.support.env.g`。
 *
 * ## 搜狗自己的导入规则（App 内《使用说明》原文）
 *
 * > 1、支持导入 TXT 格式的文本文件。
 * > 2、文件编码格式支持：ANSI、UTF-8、UTF-16 LE。
 * > 3、每行包含 1 个编码及 1 个或多个候选，通过空格或 Tab 键分隔；
 * >    编码长度：1-4；候选长度：1-20；
 * >    **编码相同时，候选项排序依据导入时的顺序排列**；
 * >    候选项仅支持中文字词；不符合规则会直接舍弃整行。
 *
 * 最后一条就是「修正排序」的依据：**码表里的顺序才是权威顺序**。
 *
 * ## 要修的是什么
 *
 * 搜狗自己的引擎会给出候选，但排序被它的词频 / 动态调频带偏，简词被压在后面
 * （打 `u` 首选是「工作」，应该首选「的」）。所以这里做的事是**只调序、不造词**：
 * 在候选里挑出「在码表里排得最靠前」的那个，挪到首位。
 *
 * ## Hook 点
 *
 * `CandsInfo.H(boolean)` —— 反编译确认它是候选组装的收口：
 * ```java
 * public final void H(boolean z) {
 *     M(true);
 *     mIMEInterface.appendCandidateWords(this.b, this.a, this.g, z);   // 组装候选
 *     d(); h(); ...
 * }
 * ```
 * `CandsInfo.a` / `CandsInfo.b` 是两个**并行的 ArrayList**（词 / 码），
 * 所以只要找到目标词在其中一个里的下标，两个列表一起搬到 0 位即可。
 * 当前编码从 `IMEInterface.getUnCommittedText(StringBuilder)` 取。
 */
internal object SogouCandidateHook {

    private const val CLS_CANDS_INFO = "com.sogou.core.input.chinese.engine.candidate.CandsInfo"

    /** 搜狗五笔数据的相对目录（相对 filesDir）。 */
    private const val WUBI_DICT_DIR = "wubi/dict"

    /** 导入的自定义五笔方案，最多 3 个槽位。 */
    private const val MAX_CUSTOM_DICTS = 3

    /** 诊断日志里最多列出多少个候选 / 多少行样例。 */
    private const val LOG_CAND_LIMIT = 12
    private const val LOG_LINE_SAMPLES = 12

    /** `CandsInfo` 里两个并行候选列表的字段名（混淆名，反编译确认）。 */
    private val LIST_FIELDS = listOf("b", "a")

    /** 合法编码：1~4 个字母。 */
    private val CODE_RE = Regex("^[a-zA-Z]{1,4}$")

    /** 简词最大码长（虎码：一简/二简/三简）。 */
    private const val SIMPLE_WORD_MAX_CODE = 3

    /** 提交改写要挂的输入连接类（**没被混淆**，反编译确认它在候选提交链上）。 */
    private const val CLS_CACHED_IC = "com.sogou.imskit.core.input.inputconnection.CachedInputConnection"

    /** 一次置顶之后，多久之内到达的提交才做改写。 */
    private const val REMAP_WINDOW_MS = 8000L

    /**
     * 一次「界面顺序 ≠ 引擎顺序」的置换。
     *
     * 为什么要记它：我们只改了**给界面看的那份列表**（`CandsInfo.a` / `.b`），
     * 引擎自己那份顺序没动 —— 用户点候选 / 按空格时，引擎按**它的**顺序把词交出来
     * （真机现象：打 `u`，界面首位已经是「的」，空格上屏的还是「工作」）。
     * 所以提交时按这个置换把引擎给的词换回**用户实际看到的那个词**。
     */
    private class Remap(
        val code: String,
        val engine: List<String>,
        val shown: List<String>,
        val at: Long,
    )

    @Volatile
    private var pendingRemap: Remap? = null

    // ------------------------------------------------------------ 码表缓存
    //
    // ⚠️ **铁律：输入路径上绝不解析码表。**
    //
    // `reorder()` 跑在 `CandsInfo.H(boolean)` 里，那是候选帧、一次敲键会走很多次。
    // 而解析搜狗目录下那三份 `custom_dict_*.txt` 是重活：真机实测 188KB + 485KB + 2.78MB、
    // 共 22 万行，光解析就要 434ms（2026-10-08 日志：12:07:18.610 读到第一份 →
    // 12:07:19.044 三份解析完）。原先这段是**同步**跑在候选帧里的，于是
    // 「强停输入法后第一次敲键」要卡 1 秒左右 —— 用户报的就是这个。
    //
    // 所以现在分成两半：解析只在后台线程做（[loadAsync] / [load]），
    // 输入路径只读 [dictCache]（[dictFor]），没就绪就先不置顶、下一帧再补。

    @Volatile
    private var dictCache: Map<String, List<String>> = emptyMap()

    @Volatile
    private var cacheStamp: String = ""

    /** 后台加载是**单飞**的：同一时刻只允许一个在跑。 */
    private val loading = AtomicBoolean(false)

    /** 诊断：已经打过日志的编码。 */
    private val loggedCodes: MutableSet<String> = Collections.synchronizedSet(mutableSetOf())

    // ------------------------------------------------------------ 安装

    fun install(module: XposedModule, loader: ClassLoader) {
        hookApplicationContext(module)
        hookCommitRemap(module, loader)

        val cls = Reflect.findClass(loader, CLS_CANDS_INFO)
        if (cls == null) {
            XLog.e("搜狗里找不到 $CLS_CANDS_INFO，跳过简词排序修正")
            return
        }
        val target = Reflect.method(cls, "H", java.lang.Boolean.TYPE)
        if (target == null) {
            XLog.e("未找到 ${cls.simpleName}.H(boolean)，跳过简词排序修正")
            return
        }
        module.hookGuarded(target) { chain ->
            val result = chain.proceed()
            runCatching { reorder(chain.getThisObject()) }
                .onFailure { XLog.w("简词排序修正异常", it) }
            result
        }
        XLog.i("已 Hook CandsInfo.H(boolean)（简词排序修正）")
    }

    /**
     * 抓一个目标进程的 Context（读码表文件要用）。
     *
     * 搜狗的 Application 是 `SogouTinkerApplication`，自己没声明 `onCreate`
     * （继承自 RFixApplication / TinkerApplication），逐层去猜不如直接挂框架的
     * `Application.onCreate` —— 模块只在作用域进程里加载，所以这里只会命中搜狗自己。
     */
    private fun hookApplicationContext(module: XposedModule) {
        val target = Reflect.method(android.app.Application::class.java, "onCreate") ?: return
        module.hookGuarded(target) { chain ->
            val result = chain.proceed()
            runCatching {
                (chain.getThisObject() as? Context)?.let {
                    ImeEnv.bindContext(it)
                    // 顺手预热码表：这是每个进程里最早能拿到 Context 的地方，
                    // 在这里后台把码表读出来，用户第一次敲键时缓存基本已就位（见「码表缓存」那节的铁律）
                    warmUp(it)
                }
            }
            result
        }
        XLog.i("已 Hook Application.onCreate（抓 Context + 预热码表）")
    }

    // ------------------------------------------------------------ 提交改写

    /**
     * 挂 `CachedInputConnection.commitText(CharSequence, int)`：把「引擎按自己顺序给出的词」
     * 换成「界面上同一位置显示的词」。
     *
     * 为什么需要它（2026-10-07 真机）：只把候选列表搬到首位，**界面**对了，
     * 但空格 / 点候选上屏的仍是引擎那边的原词 —— 见 [Remap] 的说明。
     *
     * 只改**一次置顶之后 8 秒内**、且文本正好等于那次引擎候选里的某一个时才换；
     * 换出来的词与原文相同就不动，且**一次置顶只改一次**（改完立刻作废）。
     * 其余提交（英文、符号、云候选……）一律原样放行。
     */
    private fun hookCommitRemap(module: XposedModule, loader: ClassLoader) {
        val cls = Reflect.findClass(loader, CLS_CACHED_IC)
        if (cls == null) {
            XLog.e("找不到 $CLS_CACHED_IC，简词置顶后提交上屏的仍是引擎原词")
            return
        }
        val target = Reflect.method(
            cls,
            "commitText",
            CharSequence::class.java,
            java.lang.Integer.TYPE,
        )
        if (target == null) {
            XLog.e("${cls.simpleName} 上没有 commitText(CharSequence, int)，简词提交改写不可用")
            return
        }
        module.hookGuarded(target) { chain ->
            val text = chain.getArg(0)?.toString()
            val fixed = remapForCommit(text)
            if (fixed == null || fixed == text) {
                chain.proceed()
            } else {
                val args = chain.getArgs().toMutableList()
                args[0] = fixed
                chain.proceed(args.toTypedArray())
            }
        }
        XLog.i("已 Hook ${cls.simpleName}.commitText(CharSequence, int)（简词提交改写）")
    }

    /** 按 [pendingRemap] 把引擎给的词换回界面上显示的那个；不需要换就返回 null。 */
    private fun remapForCommit(text: String?): String? {
        if (text.isNullOrEmpty()) return null
        val p = pendingRemap ?: return null
        if (System.currentTimeMillis() - p.at > REMAP_WINDOW_MS) {
            pendingRemap = null
            return null
        }
        val index = p.engine.indexOf(text)
        if (index < 0) return null
        val shown = p.shown.getOrNull(index) ?: return null
        if (shown == text) return null
        // 一次置换只改写一次：改完立刻作废，后面的提交再撞上这份快照也不会被改写
        pendingRemap = null
        XLog.i(
            "简词提交改写：code=${p.code} 引擎给「$text」→ 界面上第 ${index + 1} 位是「$shown」，改上屏这个",
        )
        return shown
    }

    // ------------------------------------------------------------ 排序修正

    private fun reorder(candsInfo: Any?) {
        if (candsInfo == null) return
        val context = ImeEnv.context() ?: return
        if (!ModuleConfig.simpleWord(context)) {
            pendingRemap = null
            return
        }

        val code = currentCode(candsInfo)

        // 编码一变就丢掉上一次的置换记录 —— 否则用户接着打下一段时，
        // 提交里万一撞上旧快照里的词就会被错误改写。
        //
        // ⚠️ 这一段必须排在下面那些早退**之前**：打到第 4 码时不是简词、下面直接 `return`，
        // 但置换记录得先作废。真机 2026-10-09：打 `yjk` 把「输入法」置顶（引擎那份是「軡」在前），
        // 接着敲 `g` 变 `yjkg` —— 4 码在下面早退、没清记录，提交「输入法」时被按旧快照改写成「軡」，
        // 而界面显示的仍是「输入法」。读不到编码（`code == null`）时同样作废。
        if (pendingRemap?.code != code) pendingRemap = null

        if (code.isNullOrEmpty() || code.length > SIMPLE_WORD_MAX_CODE) return

        val words = dictFor(context)[code].orEmpty()
        if (words.isEmpty()) {
            // 还没载入过（后台正在载）→ 这一帧先不置顶，别把「没载入」说成「码表里没有」
            if (cacheStamp.isEmpty()) return
            logOnce(code, "码表里没有该编码的简词")
            return
        }

        val lists = LIST_FIELDS.mapNotNull { Reflect.field(candsInfo.javaClass, it) }
            .mapNotNull { runCatching { it.get(candsInfo) }.getOrNull() }
        if (lists.size < 2) {
            logOnce(code, "CandsInfo 候选列表字段取不到")
            return
        }

        // 在候选里挑「在码表里排得最靠前」的那个。
        // ⚠️ 元素不一定就是 String（真机实测一个列表是词、另一个是
        // `...engine.base.model.d` 对象），所以一律按 toString() 比对。
        var hitIndex = -1
        var bestRank = Int.MAX_VALUE
        var hitList: List<*>? = null
        for (list in lists) {
            if (list !is List<*>) continue
            for (i in list.indices) {
                val s = list[i]?.toString() ?: continue
                val rank = words.indexOf(s)
                if (rank in 0 until bestRank) {
                    bestRank = rank
                    hitIndex = i
                    hitList = list
                }
            }
        }

        if (hitIndex < 0) {
            logOnce(code, "简词不在候选里：候选=" + dump(lists) + " 期望=" + words.take(4))
            return
        }
        if (hitIndex == 0) {
            logOnce(code, "简词已在首位：" + words[bestRank])
            return
        }

        // 动列表**之前**先记下引擎给的那一份顺序，提交时要用它把词换回来（见 [remapForCommit]）
        val before = hitList?.map { it?.toString().orEmpty() }.orEmpty()

        var moved = 0
        for (list in lists) if (moveToFront(list, hitIndex)) moved++

        val after = hitList?.map { it?.toString().orEmpty() }.orEmpty()
        if (moved > 0 && before.isNotEmpty() && before != after) {
            pendingRemap = Remap(code, before, after, System.currentTimeMillis())
        }
        val sizes = lists.joinToString("/") { (it as? List<*>)?.size?.toString() ?: "?" }
        XLog.i(
            "简词置顶：code=$code 词=${words[bestRank]}（码表第 ${bestRank + 1} 位）" +
                "从候选第 $hitIndex 位搬到首位，列表 $moved 个，尺寸=$sizes",
        )
    }

    /** 当前输入编码（未上屏的字母串）。 */
    private fun currentCode(candsInfo: Any): String? {
        val engine = Reflect.fieldValue(candsInfo, candsInfo.javaClass, "f") ?: return null
        val method: Method = Reflect.method(
            engine.javaClass,
            "getUnCommittedText",
            StringBuilder::class.java,
        ) ?: return null
        val sb = StringBuilder()
        runCatching { method.invoke(engine, sb) }
        return sb.toString().trim().lowercase().takeIf { it.all { c -> c in 'a'..'z' } }
    }

    // ------------------------------------------------------------ 列表操作

    @Suppress("UNCHECKED_CAST")
    private fun moveToFront(list: Any?, from: Int): Boolean {
        val m = list as? MutableList<Any?> ?: return false
        if (from <= 0 || from >= m.size) return false
        val item = m.removeAt(from)
        m.add(0, item)
        return true
    }

    private fun dump(lists: List<Any?>): String {
        val out = StringBuilder()
        for (list in lists) {
            if (list !is List<*>) continue
            out.append(list.take(LOG_CAND_LIMIT).joinToString("|") { it?.toString() ?: "null" })
            out.append("   ")
        }
        return out.toString().trim()
    }

    private fun logOnce(code: String, message: String) {
        if (!loggedCodes.add("$code:$message")) return
        XLog.i("简词诊断[$code] $message")
    }

    // ------------------------------------------------------------ 码表读取

    /**
     * 输入路径用：只读缓存，**不解析**。
     *
     * 缓存过期（或还没载入）时踢一次后台加载就返回 —— 这一帧可能拿不到码表、简词不置顶，
     * 但绝不会把候选帧卡住。
     */
    private fun dictFor(context: Context): Map<String, List<String>> {
        val dir = File(context.filesDir, WUBI_DICT_DIR)
        if (stampOf(dir) != cacheStamp) loadAsync(context, dir)
        return dictCache
    }

    /** 拿到宿主 Context 就调：把解析提前到「用户还没敲键」的时候（见本节开头的铁律）。 */
    private fun warmUp(context: Context) {
        loadAsync(context, File(context.filesDir, WUBI_DICT_DIR))
    }

    /** 后台加载（单飞）。失败也照样记上 stamp —— 免得每帧都重试、每帧起一个线程。 */
    private fun loadAsync(context: Context, dir: File) {
        if (!loading.compareAndSet(false, true)) return
        Thread {
            runCatching { load(dir) }.onFailure { XLog.w("加载搜狗码表失败", it) }
            loading.set(false)
        }.apply {
            isDaemon = true
            name = "opluswubi-sogou-dict"
        }.start()
    }

    /** 真正读盘 + 解析（只在后台线程跑）。解析完才整体换缓存，输入路径看不到半份。 */
    private fun load(dir: File) {
        val stamp = stampOf(dir)
        val loaded = runCatching { parseDir(dir) }
            .onFailure { XLog.w("解析自定义五笔方案失败：${dir.absolutePath}", it) }
            .getOrDefault(emptyMap())
        dictCache = loaded
        cacheStamp = stamp
        loggedCodes.clear()
        XLog.i("已加载自定义五笔方案：${dir.absolutePath}，简词 ${loaded.size} 个编码")
        logSamples(loaded)
    }

    /** 诊断：把码表里几个编码的候选顺序打出来，确认解析方向对不对。 */
    private fun logSamples(dict: Map<String, List<String>>) {
        val picks = listOf("u", "w", "i", "a", "e", "f", "r", "v", "h")
            .mapNotNull { code -> dict[code]?.let { "$code=${it.take(4)}" } }
        if (picks.isNotEmpty()) XLog.i("简词样例：" + picks.joinToString("  "))
        val lens = dict.keys.groupingBy { it.length }.eachCount().toSortedMap()
        XLog.i("简词编码长度分布：$lens")
    }

    private fun stampOf(dir: File): String {
        val files = dir.listFiles() ?: return "none"
        return files.filter { it.name.startsWith("custom_dict_") }
            .sortedBy { it.name }
            .joinToString(",") { "${it.name}:${it.length()}:${it.lastModified()}" }
    }

    private fun parseDir(dir: File): Map<String, List<String>> {
        val out = LinkedHashMap<String, MutableList<String>>()
        for (n in 1..MAX_CUSTOM_DICTS) {
            val txt = File(dir, "custom_dict_$n.txt")
            if (txt.isFile) {
                val raw = runCatching { txt.readBytes() }.getOrElse { ByteArray(0) }
                XLog.i(
                    "读到自定义方案 ${txt.name}（${raw.size} 字节），头 32 字节：" +
                        hex(raw.take(32).toByteArray()),
                )
                parseText(decode(raw), out, txt.name)
                continue
            }
            val zip = File(dir, "custom_dict_$n.zip")
            if (zip.isFile) {
                runCatching { parseZip(zip, out) }
                    .onFailure { XLog.w("解压 ${zip.name} 失败", it) }
            }
        }
        return out
    }

    private fun parseZip(zip: File, out: MutableMap<String, MutableList<String>>) {
        java.util.zip.ZipInputStream(zip.inputStream().buffered()).use { zis ->
            var entry = zis.nextEntry
            while (entry != null) {
                if (!entry.isDirectory && entry.name.endsWith(".txt", true)) {
                    parseText(decode(zis.readBytes()), out, zip.name)
                }
                zis.closeEntry()
                entry = zis.nextEntry
            }
        }
    }

    /**
     * 按搜狗文档的编码规则解码：UTF-8（带/不带 BOM）、UTF-16 LE（带 BOM）、ANSI(GBK) 兜底。
     */
    private fun decode(bytes: ByteArray): String {
        if (bytes.size >= 2) {
            if (bytes[0] == 0xFF.toByte() && bytes[1] == 0xFE.toByte()) {
                return String(bytes, 2, bytes.size - 2, Charsets.UTF_16LE)
            }
            if (bytes[0] == 0xFE.toByte() && bytes[1] == 0xFF.toByte()) {
                return String(bytes, 2, bytes.size - 2, Charsets.UTF_16BE)
            }
        }
        if (bytes.size >= 3 &&
            bytes[0] == 0xEF.toByte() && bytes[1] == 0xBB.toByte() && bytes[2] == 0xBF.toByte()
        ) {
            return String(bytes, 3, bytes.size - 3, Charsets.UTF_8)
        }
        // 无 BOM：先按严格 UTF-8 试；解不动就说明是 ANSI(GBK)
        val strict = Charsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
        val utf8 = runCatching { strict.decode(ByteBuffer.wrap(bytes)).toString() }.getOrNull()
        if (utf8 != null) {
            // UTF-16 无 BOM 时也能「合法」解成 UTF-8，但会满是 \u0000，这里挡一下
            val nuls = utf8.count { it == '\u0000' }
            if (nuls * 4 > utf8.length) {
                return String(bytes, Charsets.UTF_16LE)
            }
            return utf8
        }
        val gbk = runCatching { String(bytes, Charset.forName("GBK")) }.getOrNull()
        if (gbk != null && gbk.none { it == '\uFFFD' }) return gbk
        return gbk ?: String(bytes, Charsets.ISO_8859_1)
    }

    private fun hex(bytes: ByteArray): String =
        bytes.joinToString(" ") { "%02x".format(it) }

    /**
     * 解析一行一条的码表：`编码<空格或Tab>候选 [候选…]`。
     *
     * 只收 1~3 码的条目（简词），并按**文件出现顺序**记录候选，这个顺序就是权威排序。
     */
    private fun parseText(
        text: String,
        out: MutableMap<String, MutableList<String>>,
        fileName: String,
    ) {
        var lines = 0
        var accepted = 0
        val samples = ArrayList<String>(LOG_LINE_SAMPLES)
        for (raw in text.lineSequence()) {
            lines++
            if (samples.size < LOG_LINE_SAMPLES) samples.add(raw.take(60))
            val line = raw.trim().removePrefix("\uFEFF").trim()
            if (line.isEmpty() || line.startsWith("#")) continue
            var parts = line.split(' ', '\t', '\u3000').map { it.trim() }.filter { it.isNotEmpty() }
            if (parts.size < 2) {
                // 兜底：没有分隔符的「编码+词」连写，例如 `u的`
                val m = Regex("^([a-zA-Z]{1,4})([^a-zA-Z].*)$").find(line)
                if (m != null) parts = listOf(m.groupValues[1], m.groupValues[2].trim())
            }
            if (parts.size < 2) continue
            // 编码正常在行首；万一行首不是编码，再往后找一个像编码的 token
            val codeIdx = if (CODE_RE.matches(parts[0])) {
                0
            } else {
                parts.indexOfFirst { CODE_RE.matches(it) }
            }
            if (codeIdx < 0) continue
            val code = parts[codeIdx].lowercase()
            if (code.length > SIMPLE_WORD_MAX_CODE) continue
            val list = out.getOrPut(code) { ArrayList() }
            for (i in parts.indices) {
                if (i == codeIdx) continue
                val word = parts[i]
                if (word.isNotEmpty() && !list.contains(word)) list.add(word)
            }
            accepted++
        }
        XLog.i("$fileName 解析：$lines 行，收下 $accepted 条；样例=" + samples.take(4))
    }
}
