package com.lookie.opluswubi

import android.util.Log
import io.github.libxposed.api.XposedModule

/**
 * 日志统一出口。
 *
 * 现代 Xposed API 不再提供 `XposedBridge.log`，模块通过 [XposedModule.log] 输出，
 * 日志会出现在 LSPosed 的「日志」页。这里做一层薄封装，避免到处传 module 实例。
 *
 * ## 正式版不带详细日志
 *
 * [d] / [i] 是**开发期**用的详细日志（逐帧候选、每一条 Hook、每个开关的取值……），
 * 条件写成 [VERBOSE_LOG] —— 它是**按构建类型各放一份的 `const val`**（`src/debug` / `src/release`），
 * 编译期就折成 `if (false)`，整段调用连同那些日志字符串一起被消掉：正式包里连字面量都没有。
 *
 * （为什么不用 `BuildConfig.DEBUG`：那要靠 R8 折，而 proguard 规则里刻意 `-keep` 了自己的类，
 * `-keep` 连优化一起关掉，死分支会原样留在包里 —— 实测能 grep 到详细日志的字面量。）
 *
 * [w] / [e] / [guard] 保留：它们是**降级诊断**（哪一环断了、宿主崩溃保护），
 * README 里承诺过「每一步失败都会写明断在哪一环」，正式版也必须有。
 */
object XLog {

    private const val TAG = "OplusWubi"

    @Volatile
    private var module: XposedModule? = null

    fun bind(module: XposedModule) {
        this.module = module
    }

    /**
     * 详细日志（仅 debug 构建）。
     *
     * `inline` 是**必须的**：只有把 `if (VERBOSE_LOG) …` 内联进调用点，Kotlin 才能在**编译期**
     * 把整段消掉 —— 顺带那个日志字符串参数也就没人用、不会进常量池。
     * 不内联的话消掉的只是本方法的方法体，调用方照样把字符串传进来，字面量会留在包里
     * （实测：改成内联前 release 包里仍能 grep 到「候选对象结构」这类详细日志）。
     */
    inline fun d(msg: String) {
        if (VERBOSE_LOG) write(Log.DEBUG, msg, null)
    }

    /** 详细日志（仅 debug 构建）。语义同 [d]。 */
    inline fun i(msg: String) {
        if (VERBOSE_LOG) write(Log.INFO, msg, null)
    }

    fun w(msg: String, tr: Throwable? = null) = write(Log.WARN, msg, tr)

    fun e(msg: String, tr: Throwable? = null) = write(Log.ERROR, msg, tr)

    /** 日志的实际出口；`@PublishedApi internal` 是为了让 [d] / [i] 这两个内联函数能调用它。 */
    @PublishedApi
    internal fun write(priority: Int, msg: String, tr: Throwable?) {
        val text = "$msg${tr?.let { " | " + it } ?: ""}"
        runCatching { Log.println(priority, TAG, text) }
        module?.let { m ->
            runCatching {
                if (tr == null) m.log(priority, TAG, msg) else m.log(priority, TAG, msg, tr)
            }
        }
    }

    /**
     * 执行一段可能碰宿主类的代码，**任何失败都吞掉**，只打日志。
     *
     * ## 为什么不能用 `runCatching`
     *
     * `runCatching` 捕获的是 `Throwable`，看起来够用，但它的语义是「异常即降级」——
     * 而模块跑在 `onPackageReady` 阶段，读宿主的静态字段会**触发宿主类初始化**，
     * 初始化顺序不对时抛的是 `ExceptionInInitializerError` / `NoClassDefFoundError`
     * （微信 Tinker、百度 `PreferenceManager` 都中过）。
     * 一旦它顺着 `onPackageReady` 抛回框架，**用户的输入法直接崩**。
     *
     * 这个函数把「模块出错」和「宿主出错」彻底隔开：模块的失败最多是功能不生效，
     * 绝不能连累宿主。
     */
    fun guard(what: String, block: () -> Unit) {
        try {
            block()
        } catch (e: Throwable) {
            // Throwable 覆盖 Error：类初始化失败属于 Error，必须一起吞
            e("安装失败（已忽略，不影响宿主启动）：$what", e)
        }
    }
}
