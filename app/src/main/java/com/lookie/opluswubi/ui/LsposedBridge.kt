package com.lookie.opluswubi.ui

import android.os.Handler
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.lookie.opluswubi.XLog
import io.github.libxposed.service.XposedService
import io.github.libxposed.service.XposedServiceHelper

/**
 * 「模块在 LSPosed 里启用了没有」—— 用 **libxposed 的官方 service API**。
 *
 * ## 机制
 *
 * 模块**启用时**，LSPosed 才会通过本 APK 里的 `io.github.libxposed.service.XposedProvider`
 * （`io.github.libxposed:service` 这个 AAR 自带，manifest 会自动合并）把框架的 binder 递过来，
 * [XposedServiceHelper] 随即回调 `onServiceBind`；停用时 binder 断开 → `onServiceDied`。
 *
 * 所以判据只有一条：**binder 在不在**。没有超时、没有轮询、没有反推 ——
 * 启用就立刻 `onServiceBind`，停用就立刻 `onServiceDied`，是框架**推**过来的。
 * 权威、不需要 root，也不依赖任何输入法进程。
 *
 * ## 为什么是单例
 *
 * 状态必须**进程级**：放 Activity 的实例字段里，Activity 一重建就丢（踩过 —— 重建后界面又变回「未启用」）。
 *
 * ## 线程
 *
 * `onServiceBind` / `onServiceDied` 跑在 **binder 线程**，所以状态统一回主线程改。
 */
internal object LsposedBridge {

    /**
     * 模块有没有启用。**默认 false** —— 还没拿到 binder 就是没启用，
     * 框架一递过来就变 true（实测很快，不用等）。
     */
    var enabled by mutableStateOf(false)
        private set

    /** 监听器只注册一次（它是静态的，重复注册会把前一个换掉）。 */
    @Volatile
    private var registered = false

    /**
     * 注册框架服务监听（幂等）。在 [MainActivity.onCreate] 里调。
     *
     * `XposedServiceHelper.registerListener` 会把已经缓存下来的服务**同步**重放一遍，
     * 所以 Activity 重建时重新进来也能立刻拿到当前状态。
     */
    fun ensureRegistered(main: Handler) {
        if (registered) return
        registered = true
        XposedServiceHelper.registerListener(object : XposedServiceHelper.OnServiceListener {
            override fun onServiceBind(service: XposedService) {
                XLog.i("模块启用状态：框架服务已连接 → 已启用（API ${service.getApiVersion()}）")
                main.post { enabled = true }
            }

            override fun onServiceDied(service: XposedService) {
                XLog.w("模块启用状态：框架服务断开 → 未启用")
                main.post { enabled = false }
            }
        })
    }
}
