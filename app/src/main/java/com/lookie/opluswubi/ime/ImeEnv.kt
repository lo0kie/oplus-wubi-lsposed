package com.lookie.opluswubi.ime

import android.content.Context
import io.github.libxposed.api.XposedModule

/**
 * 被 Hook 进程的运行时环境（模块 ↔ 目标 App 的桥）。
 *
 * 每个被 Hook 的输入法进程只有一个实例；不同输入法进程互相独立，
 * 因此这里按进程维度保存状态，天然与「多输入法隔离」兼容。
 */
object ImeEnv {

    @Volatile
    var module: XposedModule? = null
        private set

    @Volatile
    var packageName: String = ""
        private set

    /**
     * 当前进程名（`onModuleLoaded` 时写入）。
     *
     * 输入法通常有多个进程：设置页在主进程，输入法服务在 `:xxx` 子进程。
     * 适配器靠它决定「这一份 Hook 装在哪个进程」。
     */
    @Volatile
    var processName: String = ""
        private set

    @Volatile
    var classLoader: ClassLoader? = null
        private set

    @Volatile
    var versionName: String = ""
        private set

    @Volatile
    var versionCode: Long = 0L
        private set

    @Volatile
    private var appContext: Context? = null

    /** 由适配器提供的兜底 Context 来源（拿不到 Activity/Service 实例时用）。 */
    @Volatile
    private var contextProvider: (() -> Context?)? = null

    fun bindProcess(name: String) {
        this.processName = name
    }

    fun bind(
        module: XposedModule,
        packageName: String,
        classLoader: ClassLoader,
        versionName: String,
        versionCode: Long,
    ) {
        this.module = module
        this.packageName = packageName
        this.classLoader = classLoader
        this.versionName = versionName
        this.versionCode = versionCode
    }

    fun bindContext(context: Context) {
        appContext = context.applicationContext ?: context
        // 拿到 Context 就把「配置同步轮询」拉起来（幂等）。
        // ⚠️ 必须在这里起、而不是在某个功能的热路径里 —— 见 ModuleConfig.startPolling 的注释：
        // 只在「功能被用到」时同步，会让「改完开关不生效」看起来像模块坏了（2026-10-10 真机）。
        runCatching { com.lookie.opluswubi.table.ModuleConfig.startPolling(this.appContext!!) }
    }

    /** 适配器注册「实在抓不到 Context 时怎么兜底」——不同输入法的全局 Context 提供者不一样。 */
    fun bindContextProvider(provider: () -> Context?) {
        contextProvider = provider
    }

    /**
     * 目标 App 的 Context。
     *
     * 优先用 Hook 生命周期抓到的实例；万一还没抓到，退回到适配器注册的兜底来源
     * （例如小布输入法的 `com.oplus.keyboard.b.a()` 全局 Context 提供者）。
     */
    fun context(): Context? {
        appContext?.let { return it }
        val ctx = runCatching { contextProvider?.invoke() }.getOrNull() ?: return null
        bindContext(ctx)
        return ctx
    }
}
