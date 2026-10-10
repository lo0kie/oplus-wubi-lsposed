package com.lookie.opluswubi.table

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Handler
import android.os.Looper
import com.lookie.opluswubi.XLog

/**
 * 模块配置的**推送通道**（模块进程 → 注入侧）。
 *
 * ## 为什么要有它（2026-10-10 真机踩坑）
 *
 * 原来只有一条路：注入侧去 `query()` [ModuleSettingsProvider]。但 Android 11+ 的
 * **包可见性**会挡住没在 manifest 里声明 `<queries>` 的调用方 ——
 * 实测报 `Failed to find provider info for com.lookie.opluswubi.settings`，返回 null。
 * 目标输入法的 manifest 我们改不了，所以这条路**冷启动时必然失败**，
 * 表现就是「在模块界面改了开关，输入法侧一点反应都没有」。
 *
 * 广播不受这个限制：只要 `Intent.setPackage(目标包名)` 显式指定，系统就会投递
 * （不需要调用方"看得见"对方）。所以改成——**模块界面一改开关，立刻推一条广播过去**。
 *
 * ## 谁收
 *
 * 注入侧的 [ModuleConfig] 在拿到宿主 Context 时注册一个**运行时 receiver**
 * （见 [ModuleConfig.startPolling]），收到就把值写回本地 `config.properties`。
 * 是「推 + 拉」双通道：推送负责**即时**，provider 轮询负责**兜底**
 * （推送时输入法进程没起 → 它起来后会自己拉一次）。
 *
 * ## 安全
 *
 * 广播带的是 `action` + `包名` + `key/value`，**不含任何码表内容**；
 * 且只推给白名单里的三个输入法包名。接收侧还会再按 [ModuleSettingsProvider.EXPOSED_KEYS]
 * 过滤一次 key，别的键不认。
 *
 * 发送时用 `setPackage()`（而不是 `setComponent`）——注入侧的 receiver 是**运行时注册**的，
 * 没有 component 名可指定；而 `setPackage` 正是让广播绕过包可见性的那把钥匙。
 */
object ModuleSettingsBus {

    /** 广播 action。带模块包名前缀，避免和别的应用撞。 */
    const val ACTION = "com.lookie.opluswubi.action.SETTINGS_CHANGED"

    /** 受推送的下游（有适配器的输入法）。改这里要同步 `ImeRegistry`。 */
    private val TARGETS = listOf(
        "com.sohu.inputmethod.sogou",
        "com.baidu.input",
        "com.oplus.keyboard",
    )

    /** 广播里携带的键名。 */
    const val EXTRA_KEY = "key"

    /** 广播里携带的值（`"true"` / `"false"`）。 */
    const val EXTRA_VALUE = "value"

    /**
     * 把一次变更推给所有输入法。
     *
     * 逐个 `setPackage()` 发（不能一条广播带多个包名 —— 那是 `setPackages`，
     * 但会和 `setPackage` 冲突，这里用最直白的循环）。
     *
     * 收不到广播的包（没装 / 进程没起）不影响别的包：`sendBroadcast` 是即发即忘的，
     * 不会抛也不会等。没收到的那家下次起来时会用 provider 兜底拉一次。
     */
    fun pushAll(context: Context, key: String, value: Boolean) {
        for (pkg in TARGETS) push(context, pkg, key, value)
    }

    private fun push(context: Context, pkg: String, key: String, value: Boolean) {
        runCatching {
            val intent = Intent(ACTION)
                .setPackage(pkg)
                .putExtra(EXTRA_KEY, key)
                .putExtra(EXTRA_VALUE, if (value) "true" else "false")
            context.sendBroadcast(intent)
            XLog.i("已推送配置变更给 $pkg：$key=$value")
        }.onFailure { XLog.w("推送配置变更失败：$pkg / $key", it) }
    }

    /**
     * 在**注入侧**（输入法进程内）注册接收器。
     *
     * ⚠️ `RECEIVER_EXPORTED` 是必须的：发送方是**另一个应用**（模块自己）。
     * 标 `NOT_EXPORTED` 的话只收得到本应用发的广播，模块推过来的一条都收不到
     * —— 这个坑很隐蔽，因为不报错，只是静默不收。
     *
     * 幂等：注册一次就够（注入侧每个进程一份）。
     */
    @Volatile
    private var registered = false

    @Synchronized
    fun registerReceiver(context: Context) {
        if (registered) return
        registered = true
        val app = context.applicationContext ?: context
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(ctx: Context?, intent: Intent?) {
                val key = intent?.getStringExtra(EXTRA_KEY) ?: return
                val value = intent.getStringExtra(EXTRA_VALUE) ?: return
                if (key !in ModuleSettingsProvider.EXPOSED_KEYS) return
                // 留痕**无条件**打 —— 老毛病是「只在成功时打」，失败路径一个字都没留
                XLog.i("收到配置推送：$key=$value")
                ModuleConfig.applyPushed(app, key, value)
            }
        }
        runCatching {
            // 不走主线程 handler：applyPushed 只写文件，放调用线程（binder 线程）也无妨
            ContextCompatRegister(app, receiver)
            XLog.i("配置推送接收器已注册（$ACTION）")
        }.onFailure { XLog.w("注册配置推送接收器失败", it) }
    }

    /** 抽出来只是为了让上面那段 `runCatching` 读起来干净些。 */
    private fun ContextCompatRegister(context: Context, receiver: BroadcastReceiver) {
        context.registerReceiver(receiver, IntentFilter(ACTION), Context.RECEIVER_EXPORTED)
    }

    /** 用主线程 Handler 发广播（模块界面回调可能不在主线程）。 */
    private val main = Handler(Looper.getMainLooper())

    fun pushAllOnMain(context: Context, key: String, value: Boolean) {
        main.post { pushAll(context, key, value) }
    }
}
