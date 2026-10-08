package com.lookie.opluswubi.ime.oplus

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.inputmethodservice.InputMethodService
import android.os.Handler
import android.os.Looper
import android.view.View
import android.view.inputmethod.BaseInputConnection
import android.view.inputmethod.InputConnection
import android.widget.TextView
import com.lookie.opluswubi.Reflect
import com.lookie.opluswubi.XLog
import com.lookie.opluswubi.hookGuarded
import com.lookie.opluswubi.ime.ImeEnv
import com.lookie.opluswubi.table.CodeTable
import com.lookie.opluswubi.table.ModuleConfig
import com.lookie.opluswubi.table.TableStore
import io.github.libxposed.api.XposedInterface
import io.github.libxposed.api.XposedModule
import java.lang.reflect.Constructor
import java.lang.reflect.Method
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap

/**
 * 候选注入 Hook（小布输入法）。
 *
 * ## 设计原则：只做注入，不写输入业务
 *
 * 上屏、顶屏、清 composing 这些都属于输入法自己的业务，模块只做两件事：
 *  1. 在候选列表里**插入**自定义方案的候选（`Kernel.getProcessedLocalCandidateList`）；
 *  2. 在引擎已经决定要提交文本时**替换**成码表里的词（`Kernel.getCommitText`）。
 * 引擎不认识码表里的码时它自己不会顶屏，这时就该按空格/点候选 —— 模块不再自己调
 * `InputConnection` 提交（那样会闪屏、吞掉触发键，而且等于重写输入法行为）。
 *
 * **唯一例外：回车**。小布**内置方案**回车时上屏的就是原始输入，是模块自己的接管把它改坏的 ——
 * [hookCommitText] 里那条「引擎提交的是一串字母编码 → 换成码表首选词」的兜底分支本意是给
 * 顶屏/自动上屏用，但回车时引擎提交的正好也是那串编码，于是被一起换成了候选词。
 * 所以修法是**在回车时不要接管**（[hookEnterKey] 在引擎按键入口标一个时间窗，[hookCommitText]
 * 见到这个窗口就原样放行），而不是去 hook 输入法的提交方法再实现一遍 —— 那层多余，已经删掉。
 *
 * ## 为什么选这几个 Hook 点
 *
 * 小布输入法的候选链路是：
 * ```
 * Engine.processKey()            // native，五笔解码
 *   -> Engine.getCandidatesByRange()   // native，拿候选
 *     -> Kernel.getProcessedLocalCandidateList()   // Java，候选后处理（★ 注入点 1）
 *       -> KernelInputProcessor.buildFinalCandidates()
 *         -> DataKt.fromLocalCandidates() -> Candidate -> UI
 * ```
 * `Kernel.getProcessedLocalCandidateList(List, boolean)` 是三条候选路径的公共汇合点，
 * 进出都是纯 Java 的 `LocalCandidate`，所以在这里插候选最干净、覆盖面最广。
 *
 * ## 候选栏显示的是哪个字段（真机验证）
 *
 * - `LocalCandidate` 第 1 个参数 = `pinyin`（引擎填大写编码，如 `J`），第 2 个 = `text`（汉字）；
 *   构造器映射由 [probeCtorMapping] 在真机上探测，不靠猜；
 * - `tip` 会落到 UI 模型的 `comment` 字段，而**候选栏在 `comment` 非空时显示的就是它**，
 *   所以 `tip` 必须留空（早先拿它放编码，结果候选栏全显示成编码）；
 * - 自定义方案生效时**只**返回自定义候选（固定行为、没有开关）：内置方案的词和「原样提交编码」那条都不混进来。
 */
internal object OplusCandidateHook {

    /** 一次最多注入多少个候选（这个输入法自己会分页，所以固定值就够）。 */
    private const val MAX_CANDIDATES = 12

    /** 前缀候选上限（码没打全时给的提示候选），0 表示关闭前缀匹配。 */
    private const val PREFIX_LIMIT = 8

    /** 自定义候选使用的下标起点，远离引擎真实下标区间。 */
    private const val SENTINEL_BASE = 900_000

    /** `CandiType.LOCAL` 的值；必须让 `type and EXTENDED_TYPES_MASK == 0`，
     *  否则 `Kernel.getCandidateLiveData()` 会跳过它。 */
    private const val CANDI_TYPE_LOCAL = 1

    /** `DataKt.LOCAL_WUBI`，与引擎自己产出的五笔候选同源，便于下游识别。 */
    private const val SOURCE_WUBI = "wubi"

    private const val KERNEL = "com.oplus.keyboard.kernel.Kernel"
    private const val LOCAL_CANDIDATE = "com.oplus.keyboard.kernel.LocalCandidate"

    /**
     * 按键真正进入引擎的入口。
     *
     * 反编译 `KernelInput.processKeyCodeV2(Integer num, Integer num2, Integer num3, …)`：
     * 第一个参数在协程任务里叫 `$x11KeyCode`，就是键码。回车的提交调用链也汇到它下面的
     * `KernelInputProcessor.processCommitKeyOnly`（真机栈证实），所以用第一个参数判定回车。
     */
    private const val KERNEL_INPUT = "com.oplus.keyboard.kernel.KernelInput"

    /**
     * 回车键码。
     *
     * ⚠️ `KernelInput.processKeyCodeV2` 的第一个参数用的是**安卓标准键码**
     * （`android.view.KeyEvent.KEYCODE_ENTER`），不是 OPlus 自己那套
     * `com.oplus.keyboard.base.enums.KeyCode` 的 value。
     *
     * 2026-10-07 真机键码采样（`按键键码采样`）：`j=106 x=120`（ASCII 字母）、
     * **回车 = 13**、退格 = 8、空格 = 32。早先按 `KeyCode.ENTER.getValue() == 66` 判定，
     * 因此一次都没命中。
     */
    private const val KEYCODE_ENTER = 13



    /** 回车后多久内到达的提交算「回车触发的」（引擎提交可能是异步的，留一个时间窗）。 */
    private const val ENTER_WINDOW_MS = 600L



    /**
     * 接管「引擎自动上屏」的最短编码长度。
     *
     * 引擎自己的四码唯一上屏发生在 native 里：它认识这个码时直接提交**自己词库里的字**，
     * 模块的候选注入 Hook 在这条路上不会被调用（引擎先上屏、后刷候选），所以只能在提交出口
     * 接管。只对四码及以上的提交做这件事，避免把两三码的正常输入卷进来。
     */
    private const val AUTO_COMMIT_MIN_LEN = 4

    /** 用户点了我们注入的候选之后，多久内不再接管引擎提交（那是用户自己的选择）。 */
    private const val OUR_PICK_WINDOW_MS = 800L




    /**
     * 接管提交之后，把候选栏清干净的窗口。
     *
     * 引擎提交完**自己不清候选**（真机日志：上屏后 50ms 还按残留编码写来一排输入时的候选），
     * 于是候选栏会先闪一排旧候选再变成联想词 —— 这一帧要清掉。
     */
    private const val COMMIT_BAR_WINDOW_MS = 200L

    /** 字母键码区间（`processKeyCodeV2` 的第一个参数用的是 ASCII 键码，真机采样证实）。 */
    private const val KEY_A = 97
    private const val KEY_Z = 122

    /** 按键时拼出来的编码能用多久（引擎的提交紧跟按键，隔久了就不认了）。 */
    private const val KEYED_CODE_WINDOW_MS = 1000L

    /** 候选栏数据源的候选方法名（不同版本可能不同，按顺序试）。 */
    private val LIVE_DATA_NAMES = listOf(
        "getCandidateLiveData",
        "getCandidatesLiveData",
        "getLocalCandidateLiveData",
    )

    private var kernelClass: Class<*>? = null
    private var kernelInstance: Any? = null
    private var localCandidateCtor: Constructor<*>? = null

    private var mGetInput: Method? = null
    private var mClear: Method? = null
    private var mGetCurrentInputMode: Method? = null
    private var mSetInput: Method? = null

    /** 哨兵下标 -> 词，供 selectCandidate 拦截使用。 */
    private val injectedByIndex = ConcurrentHashMap<Int, String>()

    /** 候选列表 Hook 被调用的次数，用于确认 Hook 点是否真的在链路上。 */
    private var hookCalls = 0

    /** 诊断日志节流：同一个原因 3 秒内只打一次，避免热路径刷屏。 */
    private var lastDiagAt = 0L
    private var lastDiag = ""

    private var lastInjectedCode = ""

    private var injectedDumpDone = false

    /** 构造器第 1 个参数是否落在 `pinyin` 字段（真机探测得出）。 */
    private var firstParamIsPinyin = true

    /** 最近一次注入的词，供「候选栏渲染」诊断过滤用。 */
    private val injectedWords: MutableSet<String> = Collections.synchronizedSet(mutableSetOf())

    private var textRenderLogs = 0

    /** 候选栏数据更新日志：每个编码最多打 3 条。 */
    private var lastLiveDataCode = ""

    /** 提交路径诊断日志条数。 */
    private var commitLogs = 0

    /** 提交路径探测日志条数。 */
    private var commitProbeLogs = 0

    /** commitText 调用链诊断日志条数。 */
    private var commitCallerLogs = 0

    /** 最近一次回车的时间戳（0 = 没有待处理的车回）。 */
    @Volatile
    private var enterAt = 0L

    /** 回车识别日志条数。 */
    private var enterLogs = 0

    /** 界面文本探测日志条数。 */
    private var schemeTextLogs = 0

    /** 顶屏状态诊断是否已打印。 */
    private var autoCommitDumped = false

    /** 已挂上观测的候选 LiveData 类（诊断用）。 */
    private val liveDataClasses: MutableSet<String> = Collections.synchronizedSet(mutableSetOf())

    private var liveDataLogs = 0

    private var liveDataItemDumped = false

    private val main = Handler(Looper.getMainLooper())

    /** 当前 InputConnection（适配上屏用）。 */
    @Volatile
    private var inputConnection: InputConnection? = null

    /** 已经上屏过的编码，避免同一个码重复提交。 */
    private var lastCommittedCode = ""

    @Volatile
    private var pendingCommitWord: String? = null

    @Volatile
    private var pendingCommitCode: String = ""

    /** 用户最近一次点我们注入的候选的时刻（0 = 没点过）。 */
    @Volatile
    private var ourPickAt = 0L

    /** 接管提交后要清候选栏的截止时刻（0 = 不在窗口内）。 */
    @Volatile
    private var commitBarUntil = 0L

    /** 触发清空的那次提交用的编码，用来判断后面推来的帧是不是残留。 */
    @Volatile
    private var commitBarCode = ""

    /**
     * 最近一次**字母键**按下之后引擎应有的编码（按键前的编码 + 这一键）。
     *
     * 引擎自己的四码自动上屏是 native 完成的，提交那一刻 `Kernel.getInput()` 可能已经被清掉
     * （真机现象：提交出口读不到编码，接管分支因此空转），所以在按键入口先拼一份留着。
     */
    @Volatile
    private var lastKeyedCode = ""

    /** [lastKeyedCode] 的产生时刻。 */
    @Volatile
    private var lastKeyedAt = 0L

    fun install(module: XposedModule, loader: ClassLoader) {
        val kernel = Reflect.findClass(loader, KERNEL)
        if (kernel == null) {
            XLog.w("未找到 $KERNEL，跳过候选注入 Hook")
            return
        }
        val localCandidate = Reflect.findClass(loader, LOCAL_CANDIDATE)
        if (localCandidate == null) {
            XLog.w("未找到 $LOCAL_CANDIDATE，跳过候选注入 Hook")
            return
        }
        kernelClass = kernel
        kernelInstance = Reflect.staticField(kernel, "INSTANCE")
        localCandidateCtor = runCatching {
            localCandidate.getConstructor(
                String::class.java,      // pinyin（引擎填大写编码）
                String::class.java,      // text（汉字）
                String::class.java,      // source
                Int::class.javaPrimitiveType!!,     // type
                Int::class.javaPrimitiveType!!,     // index
                String::class.java,      // tip
                Double::class.javaPrimitiveType!!,  // quality
                Boolean::class.javaPrimitiveType!!, // hasCorrection
                Boolean::class.javaPrimitiveType!!, // isFullMatch
                String::class.java,      // associationAbbr
                String::class.java,      // symbol
                Int::class.javaPrimitiveType!!,     // replace_len
            ).apply { isAccessible = true }
        }.onFailure { XLog.e("LocalCandidate 构造器签名不匹配", it) }.getOrNull()
        val ctor = localCandidateCtor ?: return

        // 构造器参数顺序（第 1 个参数落到 pinyin 还是 text 字段）**用真机探测**，不靠猜
        probeCtorMapping(ctor)

        mGetInput = Reflect.method(kernel, "getInput")
        mClear = Reflect.method(
            kernel,
            "clear",
            Boolean::class.javaPrimitiveType!!,
            Boolean::class.javaPrimitiveType!!,
        )
        mGetCurrentInputMode = Reflect.method(kernel, "getCurrentInputMode")
        mSetInput = Reflect.method(
            kernel,
            "setInput",
            String::class.java,
            Boolean::class.javaPrimitiveType!!,
        )
        if (mSetInput == null) XLog.w("未找到 Kernel.setInput，五码顶屏的触发键无法还给引擎")

        // 引擎自己的「四码 / 五码上屏」开关：按键要不要吞、模块要不要补顶屏，都以它为准
        XLog.i(
            "引擎自动上屏开关：4code=${imeFlag("getWubi4CodeAutoCommit")} " +
                "5code=${imeFlag("getWubi5CodeAutoCommit")}",
        )
        dumpAutoCommitMethods(kernel)

        // 核心 Hook：必须装上，任何一步失败都要看得见
        hookInputConnection(module)
        hookCandidateList(module, kernel)
        hookSelectCandidate(module, kernel)
        hookCommitText(module, kernel)
        hookEnterKey(module, loader)
        // 诊断 Hook：全部包 runCatching —— 诊断出问题绝不能影响上面这些核心能力
        // （真机踩过：hook 接口方法抛异常，把后面所有核心 Hook 都带崩了）
        runCatching { probeCommitCaller(module) }.onFailure { XLog.w("诊断 hook 失败", it) }
        runCatching { probeCommitPaths(module, kernel) }.onFailure { XLog.w("诊断 hook 失败", it) }
        runCatching { dumpKeyEntry(loader, kernel) }.onFailure { XLog.w("诊断 hook 失败", it) }
        runCatching { probeCandidateLiveData(module, kernel) }.onFailure { XLog.w("诊断 hook 失败", it) }
        runCatching { probeTextRender(module) }.onFailure { XLog.w("诊断 hook 失败", it) }
    }

    /**
     * 抓当前 InputConnection：适配上屏（四码唯一 / 五码顶屏）时要用它把词送进输入框。
     *
     * 用 framework 的 `InputMethodService.getCurrentInputConnection()`，模块不碰输入法
     * 自己的类，只借用它的输入通道。
     */
    private fun hookInputConnection(module: XposedModule) {
        val target = Reflect.method(
            InputMethodService::class.java,
            "getCurrentInputConnection",
        ) ?: run {
            XLog.w("未找到 InputMethodService.getCurrentInputConnection，上屏适配不可用")
            return
        }
        module.hookGuarded(target) { chain ->
            val result = chain.proceed()
            (result as? InputConnection)?.let { inputConnection = it }
            result
        }
        XLog.i("已 Hook InputMethodService.getCurrentInputConnection（上屏适配用）")
    }

    /**
     * 诊断：输入法自己提交候选时走的是哪条路。
     *
     * 内建顶屏不闪烁，是因为它由输入法自己完成（一次 UI 更新）；我们现在靠 InputConnection，
     * 天然会多一次界面刷新。挂住 `InputConnection.commitText` 把调用栈打出来，就能找到
     * 输入法自己的「提交候选文本」入口，之后改成调它，闪烁就没了。
     */
    private fun probeCommitCaller(module: XposedModule) {
        var hooked = 0
        // ⚠️ 不能 hook 接口方法：InputConnection 是接口，hook 它的方法会抛异常，
        // 异常会顺着 install() 冒上去，导致后面所有核心 Hook 都装不上（真机踩过，
        // 表现为「自定义码表完全不生效、退回内置王码」）。所以改 hook 具体实现类，
        // 并且每个 hook 都单独包 runCatching，诊断永远不能影响核心功能。
        val targets = listOfNotNull(
            runCatching {
                Reflect.method(
                    BaseInputConnection::class.java,
                    "commitText",
                    CharSequence::class.java,
                    Int::class.javaPrimitiveType!!,
                )
            }.getOrNull(),
            runCatching {
                Reflect.method(
                    BaseInputConnection::class.java,
                    "commitText",
                    CharSequence::class.java,
                    Int::class.javaPrimitiveType!!,
                    android.os.Bundle::class.java,
                )
            }.getOrNull(),
        )
        for (target in targets) {
            runCatching {
                module.hookGuarded(target) { chain ->
                    val text = chain.getArg(0)?.toString()
                    if (!text.isNullOrEmpty() && commitCallerLogs < 8) {
                        commitCallerLogs++
                        val stack = Thread.currentThread().stackTrace
                            .map { it.className + "." + it.methodName }
                            .filter { it.startsWith("com.oplus") }
                            .take(6)
                        XLog.i("commitText('$text') 的输入法调用链：$stack")
                    }
                    chain.proceed()
                }
                hooked++
            }.onFailure { XLog.w("诊断 hook（commitText）失败，跳过", it) }
        }
        XLog.i("已 Hook commitText ×$hooked（诊断输入法自己的提交路径）")
    }

    // ------------------------------------------------------------------ 诊断

    /**
     * 探测「顶屏 / 自动上屏」的提交路径。
     *
     * 实测 `getCommitText` 只覆盖一部分情况（`fmfm` 走了它、`eqjx` 一次都没走），
     * 所以把 Kernel 上名字含 commit/select/choose/confirm 的方法、以及返回 String 的
     * getText 类方法都列出来并挂上，看真正提交文本的是哪一个。
     */
    private fun probeCommitPaths(module: XposedModule, kernel: Class<*>) {
        val methods = Reflect.methodsOf(kernel).filter { m ->
            if (m.returnType == Void.TYPE || m.parameterTypes.size > 2) return@filter false
            val name = m.name.lowercase()
            name.contains("commit") || name.contains("select") || name.contains("choose") ||
                name.contains("confirm") || (name.startsWith("get") && name.contains("text"))
        }.distinctBy { it.name + it.parameterTypes.joinToString { p -> p.name } }
        XLog.i(
            "Kernel 提交/文本相关方法：" +
                methods.map {
                    "${it.name}(${it.parameterTypes.joinToString { p -> p.simpleName }}):${it.returnType.simpleName}"
                },
        )
        var hooked = 0
        for (method in methods) {
            if (method.name == "getCommitText" || method.name == "selectCandidate") continue
            runCatching {
                module.hookGuarded(method) { chain ->
                    val result = chain.proceed()
                    runCatching {
                        val text = result as? String
                        if (!text.isNullOrEmpty() && commitProbeLogs < 40) {
                            commitProbeLogs++
                            XLog.i("提交路径探测：${method.name}() -> '$text'")
                        }
                    }
                    result
                }
                hooked++
            }
        }
        XLog.i("已 Hook Kernel 提交/文本方法 ×$hooked（探测顶屏提交路径）")
    }

    /**
     * 诊断：找引擎的按键入口方法。
     *
     * 目的：五码顶屏的触发字母目前会被吃掉（它已经进了引擎的编码缓冲）。要把它还给
     * 引擎当下一段输入的首字，就得拦在按键进入引擎之前，所以先把入口方法找出来。
     */
    private fun dumpKeyEntry(loader: ClassLoader, kernel: Class<*>) {
        val names = Reflect.methodsOf(kernel)
            .filter { m ->
                val n = m.name.lowercase()
                n.contains("key") || n.contains("input") || n.contains("process") || n.contains("type")
            }
            .map { sig(it) }
            .distinct()
        XLog.i("Kernel 按键/输入相关方法：$names")
        val engine = Reflect.findClass(loader, "com.oplus.keyboard.kernel.Engine")
        if (engine != null) {
            val engineNames = Reflect.methodsOf(engine)
                .filter { m ->
                    val n = m.name.lowercase()
                    n.contains("key") || n.contains("input") || n.contains("process") ||
                        n.contains("candidate")
                }
                .map { sig(it) }
                .distinct()
            XLog.i("Engine 按键/候选相关方法：$engineNames")
        }
    }

    private fun sig(method: java.lang.reflect.Method): String {
        val params = method.parameterTypes.joinToString { p -> p.simpleName }
        return method.name + "(" + params + "):" + method.returnType.simpleName
    }

    /**
     * 诊断：候选栏到底把哪个字符串画出来了。
     *
     * 只关心「我们注入的词」和「当前编码」这两种文本，其他 `setText` 一律忽略。
     */
    private fun probeTextRender(module: XposedModule) {
        var hooked = 0
        val overloads = listOf(
            arrayOf<Class<*>>(CharSequence::class.java),
            arrayOf<Class<*>>(CharSequence::class.java, TextView.BufferType::class.java),
        )
        for (params in overloads) {
            val setText = Reflect.method(TextView::class.java, "setText", *params) ?: continue
            module.hookGuarded(setText) { chain ->
                val view = chain.getThisObject() as? TextView
                val text = chain.getArg(0)?.toString()
                logSchemeText(text, "setText")
                val replacement = schemeNameReplacement(view, text)
                if (replacement != null) {
                    // 把界面上的内置方案名换成当前自定义方案名（键盘选择面板等）。
                    // 注意 setText 有两个重载（1 参 / 2 参），换参必须按原方法的参数个数来，
                    // 否则框架会抛参数不匹配。
                    val args = chain.getArgs().toMutableList()
                    args[0] = replacement
                    return@hookGuarded chain.proceed(args.toTypedArray())
                }
                val result = chain.proceed()
                runCatching { logRenderedText(chain.getArg(0)) }
                result
            }
            hooked++
        }
        // 有些界面（例如键盘选择面板）是用资源 id 设文本的
        Reflect.method(TextView::class.java, "setText", Int::class.javaPrimitiveType!!)?.let { setTextRes ->
            // 换文字得调 `setText(CharSequence)` —— 而那个重载本模块上面刚 Hook 过，
            // `view.text = name` 会**再进一次自己的钩子**（多一次匹配，还可能白读一次码表元信息）。
            // 官方为「直接执行原实现、跳过全部 Hook」提供的正是 Invoker(Type.ORIGIN)，用它换文字。
            val setTextOrigin = Reflect.method(TextView::class.java, "setText", CharSequence::class.java)
                ?.let { runCatching { module.getInvoker(it) }.getOrNull() }
                ?.setType(XposedInterface.Invoker.Type.ORIGIN)
            module.hookGuarded(setTextRes) { chain ->
                val view = chain.getThisObject() as? TextView
                val name = builtinSchemeNameFromRes(view, chain.getArg(0) as? Int)
                if (view != null && name != null) {
                    if (setTextOrigin != null) setTextOrigin.invoke(view, name) else view.setText(name)
                    return@hookGuarded null
                }
                chain.proceed()
            }
            hooked++
        }
        // 还有一种是自定义控件直接画字（不是 TextView）
        Reflect.method(
            Canvas::class.java,
            "drawText",
            String::class.java,
            Float::class.javaPrimitiveType!!,
            Float::class.javaPrimitiveType!!,
            Paint::class.java,
        )?.let { drawText ->
            module.hookGuarded(drawText) { chain ->
                val text = chain.getArg(0) as? String
                logSchemeText(text, "Canvas.drawText(String)")
                val name = schemeNameForText(text, "Canvas.drawText")
                if (name != null) {
                    // 内容换成自定义方案名，位置和画笔不变。
                    // 用 proceed(新参数) 往下传 —— 别反射再调一次 drawText：那是**已被本模块 Hook 的**
                    // 同一个方法，反射调用会再进一次这个 Hook（重复匹配、重复日志）。
                    chain.proceed(
                        arrayOf<Any?>(
                            name,
                            chain.getArg(1),
                            chain.getArg(2),
                            chain.getArg(3),
                        ),
                    )
                    return@hookGuarded null
                }
                chain.proceed()
            }
            hooked++
        }
        // 还有一个重载：drawText(CharSequence, start, end, x, y, Paint)
        Reflect.method(
            Canvas::class.java,
            "drawText",
            CharSequence::class.java,
            Int::class.javaPrimitiveType!!,
            Int::class.javaPrimitiveType!!,
            Float::class.javaPrimitiveType!!,
            Float::class.javaPrimitiveType!!,
            Paint::class.java,
        )?.let { drawTextRange ->
            module.hookGuarded(drawTextRange) { chain ->
                runCatching {
                    val cs = chain.getArg(0) as? CharSequence
                    val start = chain.getArg(1) as? Int ?: 0
                    val end = chain.getArg(2) as? Int ?: 0
                    if (cs != null && end in 0..cs.length && start in 0..end) {
                        logSchemeText(cs.subSequence(start, end).toString(), "Canvas.drawText(CharSequence)")
                    }
                }
                chain.proceed()
            }
            hooked++
        }
        // StaticLayout / 自定义控件排版绘制走 drawTextRun
        Reflect.method(
            Canvas::class.java,
            "drawTextRun",
            CharSequence::class.java,
            Int::class.javaPrimitiveType!!,
            Int::class.javaPrimitiveType!!,
            Int::class.javaPrimitiveType!!,
            Int::class.javaPrimitiveType!!,
            Float::class.javaPrimitiveType!!,
            Float::class.javaPrimitiveType!!,
            Boolean::class.javaPrimitiveType!!,
            Paint::class.java,
        )?.let { drawTextRun ->
            module.hookGuarded(drawTextRun) { chain ->
                runCatching {
                    val cs = chain.getArg(0) as? CharSequence
                    val start = chain.getArg(1) as? Int ?: 0
                    val end = chain.getArg(2) as? Int ?: 0
                    if (cs != null && end in 0..cs.length && start in 0..end) {
                        logSchemeText(cs.subSequence(start, end).toString(), "Canvas.drawTextRun")
                    }
                }
                chain.proceed()
            }
            hooked++
        }
        if (hooked == 0) {
            XLog.w("未找到可用的文本设置入口，无法替换界面上的方案名")
            return
        }
        XLog.i("已 Hook 文本设置入口 ×$hooked（含 setText(int) / Canvas.drawText）")
    }

    /**
     * 把界面上的内置方案名换成当前自定义方案名。
     *
     * 「键盘选择」面板里那个「新世纪…」不是偏好条目，只能从 setText 上拦；但要避开偏好条目
     * 的 title / summary —— 那里是内置方案条目自己的名字（86五笔方案 / 新世纪五笔方案），
     * 改了就是之前那个 bug。
     */
    /** 诊断：把界面上出现过的「含五笔字样的文本」打出来（限流），用来定位方案名到底从哪来。 */
    private fun logSchemeText(text: String?, from: String) {
        if (text == null || schemeTextLogs >= 20) return
        if (!text.contains("五笔")) return
        schemeTextLogs++
        XLog.i("界面文本探测：'$text'（来源：$from）")
    }

    /** 内置方案的显示名：键盘选择面板用短名，设置页用长名。 */
    private val BUILTIN_SCHEME_NAMES = setOf(
        "86五笔", "98五笔", "新世纪五笔", "王码五笔",
        "86五笔方案", "98五笔方案", "新世纪五笔方案", "王码五笔方案",
    )

    /** 判断一段文本是不是内置方案名，是就返回自定义方案名。 */
    private fun schemeNameForText(text: String?, from: String): String? {
        if (text == null) return null
        // 键盘选择面板用的是短名（「98五笔」「新世纪五笔」），设置页用的是长名
        // （「98五笔方案」），两种都要认；其余一律不动（避免误伤用户输入）。
        val matched = text in BUILTIN_SCHEME_NAMES ||
            (text.length <= 12 && text.endsWith("五笔方案"))
        if (!matched) return null
        val context = ImeEnv.context() ?: return null
        val name = TableStore.activeMeta(context)?.name ?: return null
        if (text == name) return null
        XLog.i("界面上的内置方案名「$text」→「$name」（来源：$from）")
        return name
    }

    /** 用资源 id 设文本的那种：先解出字符串再判断。 */
    private fun builtinSchemeNameFromRes(view: TextView?, resId: Int?): String? {
        if (view == null || resId == null) return null
        val text = runCatching { view.context.resources.getString(resId) }.getOrNull() ?: return null
        return schemeNameForText(text, "setText(资源id)")
    }

    private fun schemeNameReplacement(view: TextView?, text: String?): String? {
        if (view == null || text == null) return null
        if (view.id == android.R.id.title || view.id == android.R.id.summary) return null
        // 只认内置方案名那种短文本（86五笔方案 / 98五笔方案 / 新世纪五笔方案），
        // 否则会把用户输入、搜索词之类的文本也改掉（真机踩过：搜索框里的整段话被替换了）
        return schemeNameForText(text, "setText")
    }

    private fun logRenderedText(value: Any?) {
        val text = value?.toString() ?: return
        if (text.isEmpty() || textRenderLogs >= 40) return
        if (!injectedWords.contains(text) && !text.equals(lastInjectedCode, true)) return
        textRenderLogs++
        XLog.i("候选栏渲染文本：'$text'")
    }

    /**
     * 观测候选栏的数据源。
     *
     * 目标 App 拿不到 `androidx.lifecycle.Observer`（真机实测 ClassNotFound），所以不去
     * observe，而是 Hook 返回的 LiveData 自身的读写方法：候选数据每次被写入/读取都打出来。
     */
    private fun probeCandidateLiveData(module: XposedModule, kernel: Class<*>) {
        val target = LIVE_DATA_NAMES.firstNotNullOfOrNull { Reflect.method(kernel, it) }
        if (target == null) {
            XLog.w("未找到 Kernel 的候选 LiveData 方法（${LIVE_DATA_NAMES.joinToString()}）")
            return
        }
        module.hookGuarded(target) { chain ->
            val result = chain.proceed()
            runCatching { main.post { hookLiveDataWriters(result) } }
            result
        }
        XLog.i("已挂候选 LiveData 观测（${target.name}）")
    }

    private fun hookLiveDataWriters(liveData: Any?) {
        if (liveData == null) return
        val cls = liveData.javaClass
        if (!liveDataClasses.add(cls.name)) return
        val module = ImeEnv.module ?: return
        dumpMethods(cls)
        var hooked = 0
        for (method in Reflect.methodsOf(cls)) {
            val isWriter = method.parameterTypes.size == 1 && (
                Collection::class.java.isAssignableFrom(method.parameterTypes[0]) ||
                    method.name.startsWith("set") || method.name.startsWith("post") ||
                    method.name.startsWith("update") || method.name.startsWith("emit") ||
                    method.name.startsWith("notify")
                )
            val isReader = method.parameterTypes.isEmpty() &&
                Collection::class.java.isAssignableFrom(method.returnType)
            if (!isWriter && !isReader) continue
            runCatching {
                module.hookGuarded(method) { chain ->
                    val result = chain.proceed()
                    runCatching {
                        if (isWriter) logCandidateValue(chain.getArg(0)) else logCandidateValue(result)
                    }
                    result
                }
                hooked++
            }
        }
        XLog.i("已 Hook ${cls.simpleName} 的 $hooked 个候选读写方法（诊断候选栏）")
    }

    private fun dumpMethods(cls: Class<*>) {
        val signatures = Reflect.methodsOf(cls)
            .map {
                "${it.name}(${it.parameterTypes.joinToString { p -> p.simpleName }}):${it.returnType.simpleName}"
            }
            .distinct()
        XLog.i("${cls.simpleName} 的方法：$signatures")
    }

    private fun logCandidateValue(value: Any?) {
        val list = value as? List<*> ?: return
        val first = list.firstOrNull() ?: return
        if (!first.javaClass.name.contains("Candidate")) return
        val code = currentCode() ?: ""
        if (code == lastLiveDataCode && liveDataLogs >= 3) return
        if (code != lastLiveDataCode) {
            lastLiveDataCode = code
            liveDataLogs = 0
        }
        liveDataLogs++
        val ours = list.count { c -> (numberOf(c, "getIndex") ?: -1) >= SENTINEL_BASE }
        XLog.i(
            "候选栏数据更新：共 ${list.size} 个，其中自定义 $ours 个，" +
                "前 3 个=${list.take(3).map { describe(it) }}",
        )
        if (!liveDataItemDumped) {
            liveDataItemDumped = true
            XLog.i("候选栏数据项类型=${first.javaClass.name}")
            XLog.i("候选栏数据项字段（前 3）：${list.take(3).map { dumpStringFields(it) }}")
        }
    }

    /** 诊断：构造器第 1 个参数到底落到 `pinyin` 还是 `text` 字段。 */
    private fun probeCtorMapping(ctor: Constructor<*>) {
        val probe = runCatching {
            ctor.newInstance("P1TEST", "P2TEST", "", 0, 0, null, 0.0, false, true, "", "", 0)
        }.getOrNull() ?: return
        val fields = dumpStringFields(probe)
        XLog.i("LocalCandidate 构造器映射：$fields")
        firstParamIsPinyin = fields.contains("pinyin=P1TEST")
        XLog.i("=> 第 1 个参数是 ${if (firstParamIsPinyin) "pinyin" else "text"} 字段")
    }

    /** 候选对象的可读描述（text/index/type/quality）。 */
    private fun describe(candidate: Any?): String {
        if (candidate == null) return "null"
        val text = Reflect.call(candidate, "getText")
            ?: Reflect.fieldValue(candidate, candidate.javaClass, "text")
        return "'$text'#" + numberOf(candidate, "getIndex") +
            "/t" + numberOf(candidate, "getType") +
            "/q" + (Reflect.call(candidate, "getQuality")
            ?: Reflect.fieldValue(candidate, candidate.javaClass, "quality"))
    }

    /** 把对象里所有 String 字段打出来：用来确认候选栏到底显示的是哪个字段。 */
    private fun dumpStringFields(target: Any?): String {
        if (target == null) return "null"
        val out = StringBuilder()
        var cur: Class<*>? = target.javaClass
        while (cur != null && cur != Any::class.java) {
            val fields = runCatching { cur.declaredFields }.getOrDefault(emptyArray())
            for (field in fields) {
                if (field.type != String::class.java) continue
                runCatching { field.isAccessible = true }
                val value = runCatching { field.get(target) }.getOrNull()
                out.append("${field.name}=$value ")
            }
            cur = cur.superclass
        }
        return out.toString().trim()
    }

    private fun numberOf(receiver: Any?, getter: String): Int? {
        if (receiver == null) return null
        return (Reflect.call(receiver, getter) as? Number)?.toInt()
    }

    // ------------------------------------------------------------------ Hook 1

    private fun hookCandidateList(module: XposedModule, kernel: Class<*>) {
        val target = Reflect.method(
            kernel,
            "getProcessedLocalCandidateList",
            List::class.java,
            Boolean::class.javaPrimitiveType!!,
        ) ?: run {
            XLog.w("未找到 Kernel.getProcessedLocalCandidateList，候选注入不可用")
            return
        }
        module.hookGuarded(target) { chain ->
            val original = chain.proceed()
            hookCalls++
            // 前几次调用一定打出来：用来确认这个 Hook 点到底有没有在候选链路上
            if (hookCalls <= 3) {
                val list = original as? List<*>
                XLog.i(
                    "候选列表 Hook 第 $hookCalls 次被调用：引擎候选 ${list?.size ?: -1} 个，" +
                        "当前编码='${currentCode()}'，首个引擎候选=${describe(list?.firstOrNull())}",
                )
                XLog.i("引擎候选字段：${dumpStringFields(list?.firstOrNull())}")
            }
            runCatching { inject(original) }.getOrElse { original }
        }
        XLog.i("已 Hook Kernel.getProcessedLocalCandidateList（候选注入）")
    }

    /**
     * 把自定义码表的候选插到引擎候选前面。
     *
     * 任何一步不满足（非五笔模式、没启用的码表、编码非法、没匹配到词）都原样返回，
     * 保证不干扰原生输入；每一步不满足的原因都会记一条（带节流的）诊断日志，
     * 这样「输入没候选」时日志能直接指出断在哪一环。
     */
    private fun inject(original: Any?): Any? {
        val context = ImeEnv.context() ?: run {
            diag("拿不到目标进程 Context")
            return original
        }
        if (!OplusKeyboardAdapter.isWubiKeyboard(context)) {
            diag(
                "当前不是五笔键盘（Key_KeyboardType='${OplusKeyboardAdapter.keyboardType(context)}'，" +
                    "引擎模式='${currentInputMode()}'）",
            )
            return original
        }

        val code = currentCode().orEmpty()
        if (code.isEmpty()) {
            diag("Kernel.getInput() 返回空")
            return original
        }
        if (code.length > 6) {
            diag("编码超长：'$code'")
            return original
        }
        if (code.any { it !in 'a'..'z' }) {
            diag("编码含非小写字母：'$code'")
            return original
        }

        // 刚接管过一次提交：引擎提交完自己不清候选，还会按残留编码再推一帧回来
        // （真机日志：上屏后 50ms 仍写来一排输入时的候选）→ 这一帧清空，
        // 否则候选栏会先闪一排旧候选、再被紧接着的联想词顶掉。
        if (residualAfterCommit(code)) {
            diag("提交后残留候选帧，已清空")
            return emptyList<Any?>()
        }

        val table = TableStore.activeTable(context)
        if (table == null) {
            diag("没有可用的当前方案（activeId='${TableStore.activeId(context)}'）")
            pendingCommitWord = null
            return original
        }

        // 先做上屏适配（四码唯一 / 五码顶屏）：**必须在「匹配为空就返回」之前**，
        // 因为五码顶屏正是发生在「码表里没有这个 5 码」的时候。
        // 另外：输入**真的变短**（= 上一段输入已经结束）才清掉「已上屏」标记，
        // 否则连着打同一个码第二次不会上屏。
        // ⚠️ 必须用严格小于：长度相等就是同一个码，用 <= 会把守卫清掉，
        // 结果同一个码每触发一次注入就上屏一次（真机实测：一个码被提交两三次）。
        if (lastCommittedCode.isNotEmpty() && code.length < lastCommittedCode.length) {
            lastCommittedCode = ""
        }
        val autoCommit = planAutoCommit(table, code)
        if (autoCommit != null) {
            pendingCommitWord = null
            pendingCommitCode = ""
            // 上屏整体排到主线程队列（不插进引擎的处理过程里 —— 同步调 InputConnection
            // 会干扰引擎按键处理，真机实测会引发输入行为异常；也不延迟，避免多一帧旧编码）。
            main.post {
                runCatching { commitWord(autoCommit.first, autoCommit.second, autoCommit.third) }
                    .onFailure { XLog.w("上屏失败", it) }
            }
            // ⚠️ 这里**不能**返回 original：引擎这一帧给的候选是它自己的「原样编码」echo
            // （真机 `xbld`：只给一条 `xbld`），候选栏会先闪成这样一条、再被我们的候选顶掉。
            // 继续往下走正常注入，从按键到上屏候选栏一直是我们的候选。
        }

        val maxCandidates = MAX_CANDIDATES
        val prefixLimit = PREFIX_LIMIT
        val matches = table.lookup(code, prefixLimit)
        if (matches.isEmpty()) {
            diag("方案「${TableStore.activeMeta(context)?.name}」(${table.entryCount} 条) 里没有 '$code' 的匹配")
            pendingCommitWord = null
            pendingCommitCode = code
            // 码表里没有这个码：给一条「原样编码」候选，**不能返回空列表**
            // （真机验证：空列表会让候选栏渲染成一条奇怪的空白工具条）。
            // 内置方案（新世纪五笔）的词也不能放行，那是另一个方案的字。
            return listOfNotNull(rawCodeCandidate(code))
        }

        val ctor = localCandidateCtor ?: return original
        val list = original as? List<*> ?: emptyList<Any?>()

        // 下标一律用哨兵（900000+）：真机验证过，改成 0..n-1 会让展开候选面板按 index
        // 分页取数据时全部指向同一段，出现「一堆重复候选」。
        val indexBase = SENTINEL_BASE

        val out = ArrayList<Any?>(list.size + matches.size)
        injectedByIndex.clear()
        injectedWords.clear()
        var i = 0
        for (word in matches) {
            if (i >= maxCandidates) break
            val index = indexBase + i
            injectedByIndex[index] = word
            injectedWords.add(word)
            val candidate = runCatching {
                // 引擎自己的候选是 pinyin=大写编码（如 J）、text=汉字，我们按同样的语义填，
                // 这样输入法自己的「五笔编码提示」等渲染逻辑直接适用；
                // 哪个参数对应哪个字段由 probeCtorMapping 在真机上探测得出，不靠猜。
                val (first, second) = if (firstParamIsPinyin) {
                    code.uppercase() to word
                } else {
                    word to code.uppercase()
                }
                ctor.newInstance(
                    first,
                    second,
                    SOURCE_WUBI,
                    CANDI_TYPE_LOCAL,
                    index,
                    // ⚠️ 编码提示在 App 里只认 `comment` 字段，而 comment 非空时**会顶掉候选文字**
                    // （真机验证：塞编码进去，候选栏全变成编码）；tip / symbol / associationAbbr
                    // 三个字段都试过，App 都不拿来当提示。所以这里全部留空，并在设置页把
                    // 「编码提示」开关禁掉（见 OplusSettingsHook），避免用户以为能用。
                    null,
                    0.0,
                    false,
                    true,
                    "",
                    "",
                    0,
                )
            }.getOrNull() ?: continue
            out.add(candidate)
            i++
        }
        if (out.isEmpty()) {
            diag("LocalCandidate 构造全部失败（构造器签名可能变了）")
            return original
        }
        // 只显示自定义候选（固定行为）：内置方案的候选是另一个方案的字，不混进来。

        if (code != lastInjectedCode) {
            lastInjectedCode = code
            XLog.i(
                "已注入 $i 个自定义候选：code='$code' -> ${matches.take(i)}" +
                    "（下标从 $indexBase 起，内置候选 ${list.size} 个，已丢弃）",
            )
        }
        if (!injectedDumpDone && out.isNotEmpty()) {
            injectedDumpDone = true
            XLog.i("我们注入的候选字段：${dumpStringFields(out.first())}")
        }

        pendingCommitWord = matches.first()
        pendingCommitCode = code
        return out
    }

    // ------------------------------------------------------------------ 顶屏适配

    /**
     * 判断这次输入要不要由模块补一次上屏，返回 (触发码, 要上屏的词, 要交回引擎的键)。
     *
     * 只补**五码顶屏**（四码为什么不做，理由写在方法体里）；引擎万一真的自己上屏了，
     * 由 [hookCommitText] 在提交出口纠正成码表首选词。
     */
    private fun planAutoCommit(table: CodeTable, code: String): Triple<String, String, String?>? {
        val maxLength = table.maxCodeLength
        if (maxLength <= 0) return null

        dumpAutoCommitState()
        // 只补「五码顶屏」：四码唯一时上屏在自定义方案下已经不做 —— 引擎那边的判定改不了、
        // 模块自己补又只能在引擎提交之前抢到（抢不到就残缺），所以干脆取消这个功能，
        // 并在设置页把「四码上屏」开关禁掉（见 OplusSettingsHook.disableFourCodeSwitch）。
        if (code.length == maxLength + 1 && imeFlag("getWubi5CodeAutoCommit")) {
            val base = code.dropLast(1)
            val exact = table.lookupExact(base)
            // 触发码用完整的 5 码（不是 base），这样下一次输入开始时才能识别成「新的一段」
            if (exact.isNotEmpty()) {
                // 触发键交给引擎当下一段输入的首字（否则这一键就被吃掉了）
                return Triple(code, exact.first(), code.last().toString())
            }
        }
        return null
    }

    /**
     * 读引擎自己的开关（四码顶屏 / 五码顶屏）。
     *
     * 现读、不缓存：用户在输入法设置里改这个开关要立刻生效（真机反馈过：关掉「四码上屏」之后
     * 模块还在拦按键 / 补顶屏）。
     */
    private fun imeFlag(getter: String): Boolean =
        kernelInstance?.let { Reflect.call(it, getter) as? Boolean } ?: false

    /** 诊断：把引擎上跟「自动上屏」相关的方法名列出来（换版本后一眼能看出 setter 有没有变）。 */
    private fun dumpAutoCommitMethods(kernel: Class<*>) {
        val keywords = listOf("wubi", "auto", "commit", "four", "five", "stroke", "emit")
        val names = Reflect.methodsOf(kernel).map { it.name }.distinct()
            .filter { n -> keywords.any { k -> n.lowercase().contains(k) } }
        XLog.i("Kernel 上相关方法：$names")
    }

    /**
     * 诊断：打印引擎的顶屏标志 + 输入法自己 SharedPreferences 里相关的键值。
     *
     * 目的是找出「输入法设置里那个顶屏开关」的真实来源 —— 实测 `getWubi4CodeAutoCommit`
     * 很可能是按键盘类型恒真的，不是用户在设置里看到的那个开关。
     */
    private fun dumpAutoCommitState() {
        if (autoCommitDumped) return
        autoCommitDumped = true
        XLog.i(
            "顶屏状态：4code=${imeFlag("getWubi4CodeAutoCommit")} " +
                "5code=${imeFlag("getWubi5CodeAutoCommit")}",
        )
        val context = ImeEnv.context() ?: return
        val prefs = context.getSharedPreferences(
            context.packageName + "_preferences",
            Context.MODE_PRIVATE,
        )
        val related = prefs.all
            .filterKeys { key ->
                val k = key.lowercase()
                k.contains("wubi") || k.contains("five") || k.contains("four") ||
                    k.contains("commit") || k.contains("auto") || k.contains("code") ||
                    k.contains("tip")
            }
            .map { "${it.key}=${it.value}" }
        XLog.i("输入法偏好里的相关项：$related")
    }

    /**
     * 上屏 + 收尾，**合成一次输入法操作**（beginBatchEdit…endBatchEdit），减少界面闪烁。
     *
     * 提交用 InputConnection（这是唯一能让文字进输入框的途径），直接 `commitText`，
     * 不再先 `setComposingText("")`，减少闪烁；同一个码只提交一次。
     * [restoreKey] 非空时用它把触发键交回引擎（`Kernel.setInput(输入, 是否重算候选)`），
     * 这一键就成了下一段输入的第一个字，和 RIME 的满码顶屏一致（不吞键、不多消耗）。
     */
    private fun commitWord(triggerCode: String, word: String, restoreKey: String? = null): Boolean {
        if (triggerCode == lastCommittedCode) return true
        val connection = inputConnection ?: run {
            XLog.w("还没拿到 InputConnection，跳过上屏（code=$triggerCode）")
            return false
        }
        lastCommittedCode = triggerCode
        runCatching {
            connection.beginBatchEdit()
            connection.commitText(word, 1)
            // 先让引擎退出 composing（否则界面上那串编码会残留在候选区，真机实测）
            mClear?.invoke(kernelInstance, true, false)
            // 再把触发键放回引擎，作为下一段输入的第一个字（不吞键）
            if (restoreKey != null) {
                val ok = mSetInput?.invoke(kernelInstance, restoreKey, true)
                XLog.i("触发键已交回引擎：'$restoreKey'（setInput=$ok）")
            }
            connection.endBatchEdit()
        }.onFailure { XLog.w("InputConnection 提交失败", it) }
        // 上屏之后引擎还会按残留编码推一帧候选回来（它自己不清候选栏，真机实测）→ 记下窗口
        // 把那一帧清掉，否则候选栏先闪一排输到一半的候选、再变联想词。
        // 五码顶屏（restoreKey != null）不设：触发键已经交回引擎，紧接着推来的是下一段输入的正常候选。
        if (restoreKey == null) {
            commitBarCode = triggerCode
            commitBarUntil = System.currentTimeMillis() + COMMIT_BAR_WINDOW_MS
        }
        XLog.i("适配上屏：'$triggerCode' -> '$word'")
        return true
    }

    /** 造一条「原样编码」候选（码表里没有这个码时占位用，行为与引擎自己的 echo 候选一致）。 */
    private fun rawCodeCandidate(code: String): Any? {
        val ctor = localCandidateCtor ?: return null
        val index = SENTINEL_BASE
        injectedByIndex.clear()
        injectedWords.clear()
        injectedByIndex[index] = code
        return runCatching {
            val (first, second) = if (firstParamIsPinyin) {
                code.uppercase() to code
            } else {
                code to code.uppercase()
            }
            ctor.newInstance(
                first,
                second,
                SOURCE_WUBI,
                CANDI_TYPE_LOCAL,
                index,
                null,
                0.0,
                false,
                true,
                "",
                "",
                0,
            )
        }.getOrNull()
    }

    /** 诊断日志（带节流）。 */
    private fun diag(reason: String) {
        val now = System.currentTimeMillis()
        if (reason == lastDiag && now - lastDiagAt < 3000) return
        lastDiag = reason
        lastDiagAt = now
        XLog.i("候选注入未生效：$reason")
    }

    // ------------------------------------------------------------------ Hook 2

    private fun hookSelectCandidate(module: XposedModule, kernel: Class<*>) {
        val target = Reflect.method(
            kernel,
            "selectCandidate",
            Int::class.javaPrimitiveType!!,
            String::class.java,
            Boolean::class.javaPrimitiveType!!,
        ) ?: run {
            XLog.w("未找到 Kernel.selectCandidate，自定义候选点击将不可用")
            return
        }
        module.hookGuarded(target) { chain ->
            val index = chain.getArg(0) as? Int ?: return@hookGuarded chain.proceed()
            val word = injectedByIndex[index]
            if (word == null) {
                return@hookGuarded chain.proceed()
            }
            // 是我们注入的候选：清掉引擎的 composing，让调用方把返回文本送进输入框
            pendingCommitWord = null
            // 记一个时刻：这是用户自己挑的词，紧接着的引擎提交不能再被「按编码查表」接管掉
            ourPickAt = System.currentTimeMillis()
            runCatching { mClear?.invoke(kernelInstance, true, false) }
                .onFailure { XLog.w("Kernel.clear 调用失败", it) }
            XLog.d("自定义候选被选中：$word（index=$index）")
            word
        }
        XLog.i("已 Hook Kernel.selectCandidate（自定义候选选中）")
    }

    // ------------------------------------------------------------------ Hook 3

    private fun hookCommitText(module: XposedModule, kernel: Class<*>) {
        val target = Reflect.method(kernel, "getCommitText") ?: return
        module.hookGuarded(target) { chain ->
            val raw = chain.proceed()
            val original = raw as? String ?: return@hookGuarded raw
            if (original.isEmpty()) return@hookGuarded original

            val context = ImeEnv.context()
            if (context == null) return@hookGuarded original
            if (!OplusKeyboardAdapter.isWubiKeyboard(context)) return@hookGuarded original

            // 回车：始终上屏**原始输入**。引擎在回车时提交的就是当前编码本身，
            // 这里必须原样放行 —— 一旦落到下面的接管分支，用户看到的就是候选词而不是自己敲的码。
            if (enterRecent()) {
                enterAt = 0L
                pendingCommitWord = null
                logCommit("回车放行（原始输入）", original, null, pendingCommitCode)
                return@hookGuarded original
            }

            val table = TableStore.activeTable(context)
            val code = commitCode()

            // 引擎自己的「四码唯一时上屏」：native 里直接提交**它词库里的字**（提交的不是编码，
            // 下面的兜底接不住），而且这条路**不会经过候选注入 Hook** —— 引擎先上屏、再刷候选，
            // `getProcessedLocalCandidateList` 那次注入根本来不及跑（真机日志 18:44:31：按第 4 键后
            // 引擎直接提交，候选列表 Hook 一次都没被调用）。
            // 旧代码在这里换成 `pendingCommitWord`，而它是**上一帧**（`aaa`）留下的首选词「卍」，
            // 于是引擎提交的 `aaal` →「花花世界」被改成了「卍」。
            // 所以按**当前编码**回查码表：引擎提交的不是码表首选词，就换成首选词。
            // 放在 pending 之前 —— pending 是上一帧的状态，当次的编码才作数。
            if (table != null && !ourPickRecent()) {
                if (code.length >= AUTO_COMMIT_MIN_LEN && code.length <= 6) {
                    val words = table.lookupExact(code)
                    // 码表里没有这个编码时**不再干预**：那是引擎按它自己方案上屏的字（真机：`aaaq`
                    // →「工区」）。四码上屏在自定义方案下已经取消（设置页也把那个开关禁掉了），
                    // 这里不做任何动作，让引擎按原生流程走。
                    if (words.isNotEmpty() && words.first() != original) {
                        lastKeyedAt = 0L
                        // 引擎提交完自己不清候选栏，紧接着还会按残留编码推一帧回来 → 记下窗口
                        commitBarCode = code
                        commitBarUntil = System.currentTimeMillis() + COMMIT_BAR_WINDOW_MS
                        logCommit("接管提交（引擎自动上屏）", original, words.first(), code)
                        return@hookGuarded words.first()
                    }
                }
            }

            // 主路径：引擎为「刚输入的那串编码」提交了它自己的候选，换成码表里的首选词。
            // ⚠️ `pendingCommitWord` 是**单个全局槽**，注入每一帧都会覆写；引擎的提交可能晚一两帧到，
            // 于是它可能还停在上一段编码上（真机：`aaal` 的提交被换成上一帧 `aaa` 的首选词「卍」）。
            // 提交对应的编码比它记的那个更长 → 过期，不能用（宁可上屏引擎自己的词）。
            val pending = pendingCommitWord
            if (pending != null && original != pending && !pendingStale(code)) {
                pendingCommitWord = null
                logCommit("接管提交", original, pending, pendingCommitCode)
                return@hookGuarded pending
            }

            // 兜底：引擎直接把编码本身提交出来（顶屏 / 自动上屏），按码表再查一次
            if (table != null && original.length in 1..6 &&
                original.all { it in 'a'..'z' || it in 'A'..'Z' }
            ) {
                val engineCode = original.lowercase()
                val words = table.lookupExact(engineCode)
                if (words.isNotEmpty() && words.first() != original) {
                    logCommit("接管提交（按编码查表）", original, words.first(), engineCode)
                    return@hookGuarded words.first()
                }
            }

            logCommit("提交未接管", original, null, pendingCommitCode)
            original
        }
        XLog.i("已 Hook Kernel.getCommitText（空格/自动上屏文本接管）")
    }

    // ------------------------------------------------------------------ Hook 4

    /**
     * 识别回车键。
     *
     * 为什么需要它：回车和空格/顶屏都走「引擎提交文本」这条路（真机调用链证实两者都汇到
     * `KernelInputProcessor.processCommitKeyOnly`），光看提交的文本分不出来，
     * 而用户要的行为正好相反 —— 回车要**原始输入**，空格/顶屏要**码表首选词**。
     *
     * 挂 `KernelInput.processKeyCodeV2(Integer, …)`，第一个参数就是键码（见 [KEYCODE_ENTER]）。
     * 前 10 次按键会把键码打出来（`按键键码采样`），换版本后能一眼看出映射有没有变。
     */
    private fun hookEnterKey(module: XposedModule, loader: ClassLoader) {
        val cls = Reflect.findClass(loader, KERNEL_INPUT) ?: run {
            XLog.w("未找到 $KERNEL_INPUT，回车键无法识别（回车仍按候选词上屏）")
            return
        }
        val target = Reflect.methodByNameAndArity(cls, "processKeyCodeV2", 11) ?: run {
            XLog.w("未找到 $KERNEL_INPUT.processKeyCodeV2，回车键无法识别（回车仍按候选词上屏）")
            return
        }
        module.hookGuarded(target) { chain ->
            runCatching {
                val key = chain.getArg(0) as? Int
                if (key == KEYCODE_ENTER) {
                    enterAt = System.currentTimeMillis()
                    if (enterLogs < 10) {
                        enterLogs++
                        XLog.i("识别到回车键（processKeyCodeV2 key=$key）")
                    }
                } else if (key != null) {
                    enterAt = 0L
                    // 记下「这一键按下去之后引擎应有的编码」。引擎自己的自动上屏在 native 里完成，
                    // 提交那一刻它可能已经把编码清掉了（提交出口读不到编码就无从接管），
                    // 所以在按键入口先拼一份留着（`currentCode()` 此时还是**按键前**的编码）。
                    if (key in KEY_A..KEY_Z) {
                        val before = currentCode().orEmpty()
                        if (before.length <= 6 && before.all { it in 'a'..'z' }) {
                            lastKeyedCode = before + key.toChar()
                            lastKeyedAt = System.currentTimeMillis()
                        }
                    } else {
                        // 空格 / 退格 / 标点之后，编码已经不是拼出来的那一份了，作废
                        lastKeyedAt = 0L
                    }
                    if (enterLogs < 10) {
                        enterLogs++
                        XLog.i("按键键码采样：$key")
                    }
                }
            }
            chain.proceed()
        }
        XLog.i("已 Hook KernelInput.processKeyCodeV2（识别回车键：回车始终上屏原始输入）")
    }



    /** 最近 600ms 内是否按过回车。 */
    private fun enterRecent(): Boolean {
        val at = enterAt
        return at != 0L && System.currentTimeMillis() - at <= ENTER_WINDOW_MS
    }

    /** 用户刚刚点过我们注入的候选（那一笔提交是他自己挑的，不许再被换掉）。 */
    private fun ourPickRecent(): Boolean {
        val at = ourPickAt
        return at != 0L && System.currentTimeMillis() - at <= OUR_PICK_WINDOW_MS
    }

    /**
     * `pendingCommitWord` 是不是已经过期。
     *
     * 它是**单个全局槽**，注入每一帧都会覆写它，而引擎的提交可能晚一两帧才到 —— 真机上
     * `aaal` 的那次提交就是被它换成了上一帧 `aaa` 的首选词「卍」。提交对应的编码比它记的那个
     * 更长，就说明它属于上一段输入，不能用。
     */
    private fun pendingStale(code: String): Boolean {
        if (code.isEmpty()) return false
        val p = pendingCommitCode
        if (p.isEmpty()) return false
        return code.length > p.length
    }

    /**
     * 这一帧是不是「刚接管过的那次提交」留下的残留候选。
     *
     * 是残留就清空候选栏。窗口内**编码没有前进**（[code] 是提交时那个编码的前缀）才算残留；
     * 用户一旦接着打字，立刻放弃清空，不误伤正在输的候选。
     */
    private fun residualAfterCommit(code: String): Boolean {
        val until = commitBarUntil
        if (until == 0L) return false
        if (System.currentTimeMillis() > until) {
            commitBarUntil = 0L
            return false
        }
        val base = commitBarCode
        if (base.isEmpty() || code.length > base.length || !base.startsWith(code)) {
            commitBarUntil = 0L
            return false
        }
        return true
    }

    /** 提交路径的诊断日志（限流），用于排查「顶屏没上屏正确候选」。 */
    private fun logCommit(action: String, original: String, replacement: String?, code: String) {
        if (commitLogs >= 30) return
        commitLogs++
        XLog.i("$action：'$original'${replacement?.let { " -> '$it'" } ?: ""}（pendingCode=$code）")
    }

    // ------------------------------------------------------------------ 工具

    /** 当前引擎里的原始输入（五笔模式即用户敲的编码）。 */
    private fun currentCode(): String? {
        val instance = kernelInstance ?: return null
        val raw = Reflect.call(instance, mGetInput) as? String ?: return null
        return raw.lowercase()
    }

    /**
     * 这次提交对应哪个编码：先信引擎自己的输入（`getInput()`），引擎已经清掉了就退回
     * 按键时拼出来的那份（[lastKeyedCode]，只在一秒内有效）。
     */
    private fun commitCode(): String {
        val raw = currentCode().orEmpty()
        val live = if (raw.isNotEmpty() && raw.all { it in 'a'..'z' }) raw else ""
        val at = lastKeyedAt
        val keyed = if (at != 0L && System.currentTimeMillis() - at <= KEYED_CODE_WINDOW_MS) lastKeyedCode else ""
        // 引擎提交时常常已经把编码退回去了（真机：`aaal` 提交那一刻 `getInput()` 只剩 `aaa`），
        // 按键时拼出来的那份才是这次提交真正对应的编码 → 取更长、且互为前缀的那个。
        if (keyed.isNotEmpty() && keyed.length > live.length && keyed.startsWith(live)) return keyed
        return live
    }

    /** 供设置页展示：当前引擎模式串。 */
    fun currentInputMode(): String {
        val instance = kernelInstance ?: return ""
        return Reflect.call(instance, mGetCurrentInputMode) as? String ?: ""
    }

    fun resetPending() {
        pendingCommitWord = null
        pendingCommitCode = ""
        injectedByIndex.clear()
    }
}
