package com.lookie.opluswubi.ime.baidu

import android.content.Context
import android.os.Handler
import android.os.Looper
import com.lookie.opluswubi.Reflect
import com.lookie.opluswubi.XLog
import com.lookie.opluswubi.hookGuarded
import com.lookie.opluswubi.ime.ImeEnv
import com.lookie.opluswubi.table.ModuleConfig
import io.github.libxposed.api.XposedModule
import java.lang.reflect.Field
import java.lang.reflect.Method

/**
 * 百度输入法「自定义五笔方案切过来不生效」的修复。
 *
 * ## Bug 现象
 *
 * 从其它输入法切到百度时，已经启用的**自定义五笔方案**不生效（用内置方案打字）；
 * 手动切一次「拼音 → 五笔」才正常。
 *
 * ## 反编译出来的链路
 *
 * ```
 * 设置里选方案
 *   WbPref  ──► WbPrefUtilKt.d(scheme)          // 收敛到 0..3 并落盘
 *                  └─ PreferenceManager.b.e("pref_key_wb_mode_new", scheme)
 *
 * IME 启动/配置下发
 *   ImePref.i(boolean)                          // 重新读一遍偏好
 *                  └─ ImePref.a0 = WbPrefUtilKt.b()   // ← 方案索引 0~3
 *
 *   CoreConfigHandler2.d()                      // 把整包配置刷进 CoreConfig
 *                  └─ 读 ImePref.a0，映射成引擎方案 id：
 *                       0(86五笔) → 1
 *                       1(98五笔) → 0
 *                       2(新世纪) → 4
 *                       3(自定义) → 3
 *                     coreConfig.set(ConfigKey.p0, id)
 * ```
 *
 * `WbPrefUtilKt$rangePredict$1` 确认索引合法区间就是 `0..3`。
 *
 * **根因**：`ImePref.i()` 是异步（AspectJ `AjcClosure*.run`）跑的，而
 * `CoreConfigHandler2.d()` 在初始化时同步先跑了一遍 —— 那一遍 `ImePref.a0` 还是默认值 0，
 * 于是下发的是「86五笔」；等切一次输入方式触发第二次 `d()` 时，`a0` 已经被 `i()` 写对了，
 * 自定义方案才生效。
 *
 * ## 修法
 *
 * 1. **自动**：Hook `CoreConfigHandler2.d()`，在它真正组装配置**之前**把 `ImePref.a0` 与持久化的
 *    `pref_key_wb_mode_new` 对齐；对齐发生过时，再补一次 `CoreConfigHandler2.c(int)`
 *    —— `d()` 只是把整包配置刷进 `CoreConfig`，真正让引擎当场换方案的是 `c(int)`
 *    （百度自己的「五笔方案」对话框选中新方案时走的就是它，见 `WbPref$1.onClick`）。
 * 2. **手动**：[applyNow] 供设置页的「修复五笔方案」入口调用，无条件把当前方案重下发一遍
 *    —— 等价于「手动切一次拼音 → 五笔」，但一键完成。
 *
 * 注意百度自己的方案对话框带 `if (当前选中项 != 新选中项)` 的短路，所以**重选同一个方案是空操作**，
 * 这也正是用户「必须切走再切回来」才恢复的原因。
 *
 * ⚠️ 铁律：模块 ClassLoader 看不到百度的 dex，所有类/字段/方法一律从目标 ClassLoader 解析后反射。
 */
internal object BaiduWubiSchemeFix {

    private const val CLS_IME_PREF = "com.baidu.input.ime.ImePref"
    private const val CLS_PREF_MANAGER = "com.baidu.input.manager.PreferenceManager"
    private const val CLS_CORE_CONFIG_HANDLER =
        "com.baidu.input.ime.newcore.operator.CoreConfigHandler2"
    private const val CLS_IME_SERVICE = "com.baidu.input.ImeService"

    /** 百度自己的方案读写封装：`b()` 读、`d(int)` 收敛到 0..3 并落盘。 */
    private const val CLS_WB_PREF_UTIL = "com.baidu.input.util.WbPrefUtilKt"

    /** 全局单例表：`u()` 返回当前的 `ICoreConfigHandler`。 */
    private const val CLS_GLOBAL = "com.baidu.input.pub.Global"

    /**
     * 内核桥。`CoreConfig.e()` / `CoreConfig.c()` 的第一句都是
     * `if (!IptCoreInterface.get().isCoreOpened()) return;` —— 核心没打开时
     * `setInt` 会被**静默丢弃**，所以下发成功与否必须看这个状态，不能只看有没有抛异常。
     */
    private const val CLS_IPT_CORE = "com.baidu.iptcore.IptCoreInterface"

    /** `ImePref.a0`：当前五笔方案索引（0=86 / 1=98 / 2=新世纪 / 3=自定义）。 */
    private const val FIELD_SCHEME = "a0"

    /** `PreferenceManager.b`：常规偏好表。 */
    private const val FIELD_PREF = "b"

    /** 方案索引落盘的 key（`WbPrefUtilKt` 里 `0x7f1210ce` 对应的字符串）。 */
    private const val KEY_SCHEME = "pref_key_wb_mode_new"

    /** 合法索引区间，见 `WbPrefUtilKt$rangePredict$1`。 */
    private const val SCHEME_MIN = 0
    private const val SCHEME_MAX = 3

    /** `ConfigKey.p0` = 82：五笔方案下发给引擎用的键。 */
    private const val KEY_SCHEME_ID = 82

    /** `ConfigKey.r0` = 84：`d()` 里同样按方案映射写的一个键，一起读回来做对照。 */
    private const val KEY_R0_ID = 84

    /** 等核心打开的轮询间隔与次数（真机实测核心起来得比 `onStartInputViewInternal` 晚）。 */
    private const val CORE_WAIT_INTERVAL_MS = 500L
    private const val CORE_WAIT_ATTEMPTS = 20

    private val main = Handler(Looper.getMainLooper())

    /** 诊断：同一状态只打一次。 */
    private val logged: MutableSet<String> = java.util.Collections.synchronizedSet(mutableSetOf())

    @Volatile
    private var imePrefClass: Class<*>? = null

    @Volatile
    private var schemeField: Field? = null

    @Volatile
    private var prefManagerClass: Class<*>? = null

    @Volatile
    private var wbPrefUtilClass: Class<*>? = null

    @Volatile
    private var globalClass: Class<*>? = null

    @Volatile
    private var coreClass: Class<*>? = null

    /** `ImePref.i(true)` 每个进程只复刻一次。 */
    @Volatile
    private var reloaded = false

    fun install(module: XposedModule, loader: ClassLoader) {
        val imePref = Reflect.findClass(loader, CLS_IME_PREF)
        imePrefClass = imePref
        schemeField = imePref?.let { Reflect.field(it, FIELD_SCHEME) }
        if (schemeField == null) {
            XLog.e("百度里找不到 $CLS_IME_PREF.$FIELD_SCHEME，跳过五笔方案修复")
            return
        }
        prefManagerClass = Reflect.findClass(loader, CLS_PREF_MANAGER)
        wbPrefUtilClass = Reflect.findClass(loader, CLS_WB_PREF_UTIL)
        if (wbPrefUtilClass == null) {
            XLog.w("找不到 $CLS_WB_PREF_UTIL，读方案退化为直接读偏好表")
        }
        globalClass = Reflect.findClass(loader, CLS_GLOBAL)
        if (globalClass == null) {
            XLog.w("找不到 $CLS_GLOBAL，无法主动把方案下发给引擎")
        }
        coreClass = Reflect.findClass(loader, CLS_IPT_CORE)

        hookCoreConfig(module, loader)
        runCatching { hookStartInputView(module, loader) }
            .onFailure { XLog.w("onStartInputViewInternal 诊断 Hook 不可用", it) }
        runCatching { hookCoreOpen(module) }
            .onFailure { XLog.w("openCore Hook 不可用", it) }
    }

    // ------------------------------------------------------------ 手动入口

    /**
     * 读回已保存的方案并**强制**重新下发给引擎。
     *
     * 供设置页注入的「修复五笔方案」入口调用。返回一句可以直接给用户看的结果说明。
     */
    fun applyNow(): String {
        val persisted = persistedScheme() ?: return "读不到已保存的五笔方案，未做改动"
        val field = schemeField ?: return "找不到 ImePref.$FIELD_SCHEME，未做改动"
        val written = runCatching { field.setInt(null, persisted) }
            .onFailure { XLog.w("写入 ImePref.$FIELD_SCHEME 失败", it) }
            .isSuccess
        if (!written) return "写入 ImePref.$FIELD_SCHEME 失败，未做改动"
        if (coreOpened() != true) {
            // 核心没打开时 setInt 会被 CoreConfig 直接丢弃，排一次等待，起来后自动补下发
            waitCoreThenApply("applyNow")
            return "核心还没打开，已排队：引擎起来后自动重新应用（索引 $persisted）"
        }
        if (!pushScheme(persisted, "applyNow")) {
            return "已对齐方案 $persisted，但引擎未就绪（Global.u() 为空）"
        }
        return "已重新应用五笔方案（索引 $persisted）"
    }

    // ------------------------------------------------------------ 等核心就绪

    /**
     * 等核心打开再下发方案。
     *
     * `CoreConfig.e()`（单键）和 `CoreConfig.c()`（整包）的**第一句都是**
     * `if (!IptCoreInterface.get().isCoreOpened()) return;` —— 核心没打开时 `setInt`
     * 不抛异常也不生效。真机日志：`onStartInputViewInternal` 时 `isCoreOpened=false`，
     * 而 `ImePref.i()` → `CoreConfigHandler2.d()` 是在核心起来**之前**跑的，方案就这么被丢掉了，
     * 之后再没人补推。手动切一次拼音 → 五笔之所以有效，就是它让 `d()` 在核心已打开时又跑了一遍。
     */
    private fun waitCoreThenApply(from: String, attempt: Int = 0) {
        val context = ImeEnv.context()
        if (context != null && !ModuleConfig.baiduFixEnabled(context)) {
            logOnce("off:$from", "[$from] 「修复五笔方案」开关已关闭，不干预")
            return
        }
        val opened = coreOpened()
        // 每个 tick 都真推一次：核心没打开时 `setInt` 是静默 no-op，推了不亏。
        // **不拿 isCoreOpened() 当唯一触发条件** —— 真机日志里出现过键盘已经在画、它却还是 false
        // 的情况，只等这个标志会把补推无限拖下去（2026-10-07：排队 7 秒后一次都没落地）。
        forceApply(from)
        if (opened == true) {
            reloadPrefs(from)
            return
        }
        if (attempt >= CORE_WAIT_ATTEMPTS) {
            XLog.w("[$from] 补推 $CORE_WAIT_ATTEMPTS 次后 isCoreOpened 仍为 $opened，停止补推")
            return
        }
        if (attempt == 0) {
            XLog.i(
                "[$from] 核心还没确认打开，边补推边等（每 ${CORE_WAIT_INTERVAL_MS}ms 一次，" +
                    "最多 $CORE_WAIT_ATTEMPTS 次）",
            )
        }
        main.postDelayed({ waitCoreThenApply(from, attempt + 1) }, CORE_WAIT_INTERVAL_MS)
    }

    // ------------------------------------------------------------ 核心修复

    private fun hookCoreConfig(module: XposedModule, loader: ClassLoader) {
        val cls = Reflect.findClass(loader, CLS_CORE_CONFIG_HANDLER)
        if (cls == null) {
            XLog.e("找不到 $CLS_CORE_CONFIG_HANDLER，无法修复五笔方案")
            return
        }
        val target = Reflect.method(cls, "d") ?: run {
            XLog.e("找不到 ${cls.simpleName}.d()，无法修复五笔方案")
            return
        }
        module.hookGuarded(target) { chain ->
            // 必须在组装配置之前对齐，d() 内部紧接着就会读 ImePref.a0
            val corrected = runCatching { alignScheme("CoreConfigHandler2.d") }
                .onFailure { XLog.w("对齐五笔方案失败", it) }
                .getOrNull()
            val result = chain.proceed()
            // d() 只把整包配置刷进 CoreConfig；纠正过方案时再补一次 c(int)，
            // 让引擎当场换方案（百度自己的方案对话框走的就是这条）。
            if (corrected != null) {
                runCatching { pushScheme(corrected, "CoreConfigHandler2.d") }
                    .onFailure { XLog.w("下发五笔方案失败", it) }
            }
            result
        }
        XLog.i("已 Hook CoreConfigHandler2.d()（下发配置前对齐五笔方案）")
    }

    /** 把方案索引写进引擎：`((CoreConfigHandler2) Global.u()).c(index)`。 */
    private fun pushScheme(index: Int, from: String): Boolean {
        val handler = coreConfigHandler() ?: run {
            logOnce("nohandler:$from", "[$from] Global.u() 还没就绪，无法把方案下发给引擎")
            return false
        }
        val setter = Reflect.method(handler.javaClass, "c", Integer.TYPE) ?: run {
            logOnce(
                "nomethod:$from:${handler.javaClass.name}",
                "[$from] 找不到 ${handler.javaClass.simpleName}.c(int)，无法把方案下发给引擎",
            )
            return false
        }
        val ok = runCatching { setter.invoke(handler, index) }
            .onFailure { XLog.w("[$from] 调用 ${handler.javaClass.simpleName}.c($index) 失败", it) }
            .isSuccess
        if (!ok) return false
        val opened = coreOpened()
        // 每次切输入框都会走一遍，所以同一个状态只打一条，别刷屏
        logOnce(
            "push:$from:$index:$opened",
            "[$from] 已把方案 $index 下发给引擎（CoreConfigHandler2.c，isCoreOpened=$opened）" +
                if (opened == false) " —— 核心没打开，这次下发会被 CoreConfig 直接丢弃" else "",
        )
        return true
    }

    private fun coreConfigHandler(): Any? {
        val cls = globalClass ?: return null
        return Reflect.callStatic(cls, "u")
    }

    // ------------------------------------------------------------ 核心打开时机

    /**
     * 挂 `IptCoreInterface.openCore(Context, String, PackageInfo, int)`。
     *
     * 这是**最早能拿到已打开核心**的时机（`ImeCoreManager.h()` 调它，之后才 `open(...)`）。
     * 两件事：
     *  1. 读回 native 里的方案键（82 = `ConfigKey.p0`，84 = `ConfigKey.r0`）—— 用来回答
     *     「核心刚打开时，native 里到底是不是 3」。这一步决定后面是「写太晚」还是「写了不认」。
     *  2. 立刻下发一次方案。核心每次弹键盘都会重开，这是最贴近它初始化时刻的下发点。
     */
    private fun hookCoreOpen(module: XposedModule) {
        val cls = coreClass ?: run {
            XLog.w("找不到 $CLS_IPT_CORE，无法在核心打开时下发方案")
            return
        }
        val target = Reflect.methodByNameAndArity(cls, "openCore", 4) ?: run {
            XLog.w("找不到 $CLS_IPT_CORE.openCore(...)，无法在核心打开时下发方案")
            return
        }
        module.hookGuarded(target) { chain ->
            val result = chain.proceed()
            runCatching {
                if (result == true) {
                    XLog.i(
                        "[openCore] 核心已打开：native 里的方案键 82=${coreInt(KEY_SCHEME_ID)}，" +
                            "84=${coreInt(KEY_R0_ID)}",
                    )
                    forceApply("openCore")
                }
            }
            result
        }
        XLog.i("已 Hook IptCoreInterface.openCore（核心打开即读回 native 值 + 下发方案）")
    }

    /** 直接读 native 侧的 int 配置（`IptCoreInterface.get().getInt(key)`）；拿不到返回 null。 */
    private fun coreInt(key: Int): Int? {
        val cls = coreClass ?: return null
        val instance = Reflect.callStatic(cls, "get") ?: return null
        return Reflect.call(instance, "getInt", key) as? Int
    }

    /**
     * 复刻「手动切一次拼音 → 五笔」实际执行的 `ImePref.i(true)`。
     *
     * 单键 `c(int)` 只写 `ConfigKey.p0`；真机日志证明它到 native 了（`isCoreOpened=true`）
     * 但方案没换，所以再把用户手动恢复时真正跑的那条路走一遍：`i(true)` 重读全量偏好，
     * 末尾自己就会调 `CoreConfigHandler2.d()` 把整包配置下发给引擎。
     *
     * 每个进程只做一次，避免每次弹键盘都重读一遍全量偏好。
     */
    private fun reloadPrefs(from: String): Boolean {
        if (reloaded) return true
        // 1) 先按百度自己的写法把方案写回偏好表：`WbPrefUtilKt.d(int)` = clamp 到 0..3 +
        //    `PreferenceManager.b.e(key, v).apply()`。我们之前只改静态字段 `a0`，从不写偏好，
        //    也就不会触发百度自己的偏好变更监听 —— 那很可能才是「方案真正生效」的开关。
        persistedScheme()?.let { value ->
            val util = wbPrefUtilClass
            val setter = util?.let { Reflect.method(it, "d", Integer.TYPE) }
            if (setter == null) {
                logOnce("nod:$from", "[$from] 找不到 $CLS_WB_PREF_UTIL.d(int)，跳过写回偏好")
            } else {
                runCatching { setter.invoke(null, value) }
                    .onFailure { XLog.w("[$from] 调用 WbPrefUtilKt.d($value) 失败", it) }
                    .onSuccess { XLog.i("[$from] 已按百度自己的写法写回方案偏好：d($value)") }
            }
        }
        val cls = imePrefClass ?: run {
            logOnce("noimepref:$from", "[$from] 找不到 $CLS_IME_PREF，无法复刻 ImePref.i()")
            return false
        }
        val method = Reflect.methodByNameAndArity(cls, "i", 1) ?: run {
            logOnce("noimethod:$from", "[$from] 找不到 $CLS_IME_PREF.i(boolean)，无法复刻手动切换")
            return false
        }
        val ok = runCatching { method.invoke(null, true) }
            .onFailure { XLog.w("[$from] 调用 ImePref.i(true) 失败", it) }
            .isSuccess
        if (ok) {
            reloaded = true
            XLog.i("[$from] 已复刻「手动切一次拼音→五笔」：ImePref.i(true) 跑完（整包配置随 d() 下发）")
        }
        return ok
    }

    /** `IptCoreInterface.get().isCoreOpened()`；拿不到返回 null。 */
    private fun coreOpened(): Boolean? {
        val cls = coreClass ?: return null
        val instance = Reflect.callStatic(cls, "get") ?: return null
        return Reflect.call(instance, "isCoreOpened") as? Boolean
    }

    // ------------------------------------------------------------ 诊断

    private fun hookStartInputView(module: XposedModule, loader: ClassLoader) {
        val cls = Reflect.findClass(loader, CLS_IME_SERVICE) ?: return
        val target = Reflect.method(
            cls,
            "onStartInputViewInternal",
            android.view.inputmethod.EditorInfo::class.java,
            java.lang.Boolean.TYPE,
        ) ?: return
        module.hookGuarded(target) { chain ->
            runCatching {
                // 顺手把输入法进程的 Context 记下来：「修复五笔方案」开关存在模块配置里，
                // 读它需要一个 Context，而这条 Hook 是唯一能直接拿到 ImeService 的地方。
                (chain.getThisObject() as? Context)?.let { ImeEnv.bindContext(it) }
                // 每次切入都打一条**不去重**的状态行：方便对照「切进来（坏）→ 切走再切回（好）」
                // 两次切入的状态差在哪。
                XLog.i(
                    "[onStartInputViewInternal] 切入：持久化方案=${persistedScheme()} " +
                        "a0=${currentScheme()} isCoreOpened=${coreOpened()}",
                )
                // 「从别的输入法切过来」正是在这里发生的，但此刻核心往往还没打开
                // （真机日志 isCoreOpened=false），所以这里只负责「排一次等待」，
                // 真正下发要等核心就绪 —— 否则 setInt 会被 CoreConfig 静默丢弃。
                waitCoreThenApply("onStartInputViewInternal")
            }
            val result = chain.proceed()
            runCatching { dumpState("onStartInputViewInternal") }
            result
        }
        XLog.i("已 Hook ImeService.onStartInputViewInternal（切入时强制下发方案 + 诊断）")
    }

    private fun dumpState(from: String) {
        val persisted = persistedScheme() ?: return
        val current = currentScheme() ?: return
        val key = "state:$from:$persisted:$current"
        if (!logged.add(key)) return
        XLog.i("[$from] 持久化方案=$persisted，ImePref.a0=$current" + if (persisted == current) "（一致）" else "（不一致）")
    }

    // ------------------------------------------------------------ 对齐

    /** 把 `ImePref.a0` 对齐到持久化方案；**确实改动了**才返回新值，否则返回 null。 */
    private fun alignScheme(from: String): Int? {
        // 设置页的「修复五笔方案」开关关掉后就不干预；读不到 Context 时按打开处理
        // （Context 要等第一次 onStartInputViewInternal 才拿到，那之前的对齐不能漏）
        val context = ImeEnv.context()
        if (context != null && !ModuleConfig.baiduFixEnabled(context)) {
            logOnce("off:$from", "[$from] 「修复五笔方案」开关已关闭，不对齐方案")
            return null
        }
        val persisted = persistedScheme() ?: run {
            logOnce("no-pref:$from", "[$from] 读不到持久化方案（偏好表还没就绪？）")
            return null
        }
        val field = schemeField ?: return null
        val current = currentScheme() ?: return null
        if (current == persisted) {
            logOnce("ok:$from:$persisted", "[$from] 五笔方案已是 $persisted，无需纠正")
            return null
        }
        val written = runCatching { field.setInt(null, persisted) }
            .onFailure { XLog.w("写入 ImePref.$FIELD_SCHEME 失败", it) }
            .isSuccess
        if (!written) return null
        XLog.i("[$from] 纠正五笔方案：$current → $persisted（下发核心配置前）")
        return persisted
    }

    /**
     * 无条件把已保存的方案写回 `ImePref.a0` 并推给引擎。
     *
     * 用在「切到百度输入法」这一刻：不假设 `a0` 当前是什么、也不假设引擎会重新读配置，
     * 直接把方案怼进 `CoreConfig`。开关关掉时什么都不做。
     */
    private fun forceApply(from: String): Int? {
        val context = ImeEnv.context()
        if (context != null && !ModuleConfig.baiduFixEnabled(context)) {
            logOnce("off:$from", "[$from] 「修复五笔方案」开关已关闭，不干预")
            return null
        }
        val persisted = persistedScheme() ?: run {
            logOnce("no-pref:$from", "[$from] 读不到持久化方案（偏好表还没就绪？）")
            return null
        }
        schemeField?.let { field ->
            runCatching { field.setInt(null, persisted) }
                .onFailure { XLog.w("写入 ImePref.$FIELD_SCHEME 失败", it) }
        }
        if (!pushScheme(persisted, from)) return null
        logOnce("apply:$from:$persisted", "[$from] 已把方案 $persisted 推给引擎（a0=$persisted）")
        return persisted
    }

    /**
     * 读持久化的方案索引。
     *
     * 首选百度自己的读法 `WbPrefUtilKt.b()` —— `ImePref.i()` 用的就是它，自带 0..3 收敛，
     * 还包含「首次继承」那条分支；拿不到时才退化成直接读偏好表里的 key。
     */
    private fun persistedScheme(): Int? {
        wbPrefUtilClass?.let { cls ->
            val value = Reflect.callStatic(cls, "b") as? Int
            if (value != null && value in SCHEME_MIN..SCHEME_MAX) return value
        }
        return rawPersistedScheme()
    }

    /** 退化路径：直接读 `PreferenceManager.b` 里的 `pref_key_wb_mode_new`；拿不到返回 null。 */
    private fun rawPersistedScheme(): Int? {
        val prefClass = prefManagerClass ?: return null
        // ⚠️ 整段包 runCatching：`Reflect.staticField` 读静态字段会**触发宿主类初始化**，
        // 百度刚启动时 `PreferenceManager` 的静态块依赖还没建好的单例，会抛
        // `ExceptionInInitializerError`（真机：百度输入法一启动就崩）。这里抛出去就把宿主带崩了。
        return runCatching {
            val pref = Reflect.staticField(prefClass, FIELD_PREF) ?: return@runCatching null
            val getInt: Method = Reflect.method(
                pref.javaClass,
                "getInt",
                String::class.java,
                java.lang.Integer.TYPE,
            ) ?: return@runCatching null
            val value = getInt.invoke(pref, KEY_SCHEME, SCHEME_MIN) as? Int ?: return@runCatching null
            if (value in SCHEME_MIN..SCHEME_MAX) value else null
        }.onFailure { XLog.w("读百度五笔方案偏好失败（功能降级，不影响其它）", it) }
            .getOrDefault(null)
    }

    private fun currentScheme(): Int? {
        val field = schemeField ?: return null
        return runCatching { field.getInt(null) }.getOrNull()
    }

    private fun logOnce(key: String, message: String) {
        if (logged.add(key)) XLog.i(message)
    }
}
