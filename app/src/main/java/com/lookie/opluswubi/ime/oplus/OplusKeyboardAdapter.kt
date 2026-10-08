package com.lookie.opluswubi.ime.oplus

import android.content.Context
import com.lookie.opluswubi.Reflect
import com.lookie.opluswubi.XLog
import com.lookie.opluswubi.hookGuarded
import com.lookie.opluswubi.ime.ImeAdapter
import com.lookie.opluswubi.ime.ImeEnv
import com.lookie.opluswubi.table.TableStore
import io.github.libxposed.api.XposedModule

/**
 * OPPO / 一加 / realme 「小布输入法」（包名 `com.oplus.keyboard`）适配器。
 *
 * 已按 `1.8.33.17-mkt` 反编译验证；所有 Hook 点都做了「类/方法不存在就跳过」的降级处理，
 * 因此其它小版本一般也能用，最坏情况是某一个功能不生效而输入法本身不受影响。
 */
object OplusKeyboardAdapter : ImeAdapter {

    const val PKG = "com.oplus.keyboard"

    /** 反编译验证过的版本。 */
    private const val VERIFIED_VERSION_NAME = "1.8.33.17-mkt"

    /**
     * 版本身份以 versionCode 为准。
     *
     * 真机上 `PackageReadyParam.applicationInfo.versionName` 读到的是空串
     * （同一个对象里 `longVersionCode` 却是正常的 1518033），所以不能用 versionName 判断。
     */
    private const val VERIFIED_VERSION_CODE = 1518033L

    private const val APP_CLASS = "com.oplus.keyboard.ImeApp"

    /** 目标 App 自己的全局 Context 提供者。 */
    private const val CONTEXT_HOLDER = "com.oplus.keyboard.b"

    /** 目标 App 把「当前键盘类型」存在默认 SharedPreferences 里。 */
    private const val PREF_KEY_KEYBOARD_TYPE = "Key_KeyboardType"

    override val id: String = "oplus_keyboard"

    override val packageName: String = PKG

    override val displayName: String = "小布输入法"

    override fun supports(versionName: String, versionCode: Long): Boolean {
        if (versionCode != VERIFIED_VERSION_CODE) {
            XLog.w(
                "小布输入法版本 $versionName($versionCode) 非已验证版本 " +
                    "$VERIFIED_VERSION_NAME($VERIFIED_VERSION_CODE)，将按兼容模式注入",
            )
        }
        return true
    }

    override fun install(
        module: XposedModule,
        loader: ClassLoader,
        versionName: String,
        versionCode: Long,
    ) {
        // 让码表/配置落到这个输入法自己的私有目录（多输入法隔离）
        TableStore.storageDirName = storageDirName()

        installContextCapture(module, loader)

        XLog.guard("设置入口注入失败") { OplusSettingsHook.install(module, loader) }
        XLog.guard("候选注入 Hook 安装失败") { OplusCandidateHook.install(module, loader) }
        XLog.guard("剪贴板条数上限解除失败") { OplusClipboardHook.install(module, loader) }
    }

    /**
     * 尽早抓到目标进程的 Context。
     *
     * `onPackageReady` 阶段 Application 还没创建，拿不到 Context；而候选 Hook 在输入法
     * 热路径上被高频调用，不能每次去反射取 Context。所以 Hook `ImeApp.onCreate()` 抓一次。
     */
    private fun installContextCapture(module: XposedModule, loader: ClassLoader) {
        // 兜底：万一 Application.onCreate 的 Hook 还没跑到（或者别的版本没这个类），
        // 用目标 App 自己的全局 Context 提供者（`com.oplus.keyboard.b.a()`）取一次。
        ImeEnv.bindContextProvider {
            val holder = Reflect.findClass(loader, CONTEXT_HOLDER) ?: return@bindContextProvider null
            Reflect.callStatic(holder, "a") as? Context
        }

        val appClass = Reflect.findClass(loader, APP_CLASS) ?: run {
            XLog.w("未找到 $APP_CLASS，Context 捕获降级为按需反射")
            return
        }
        val onCreate = Reflect.method(appClass, "onCreate") ?: return
        module.hookGuarded(onCreate) { chain ->
            val result = chain.proceed()
            runCatching {
                val app = chain.getThisObject() as? Context
                if (app != null) {
                    ImeEnv.bindContext(app)
                    XLog.i("已捕获目标进程 Context：${app.packageName}")
                }
            }
            result
        }
    }

    /**
     * 当前是否处于五笔键盘。
     *
     * 目标 App 的 `KeyboardType.getType()` 对五笔返回 `wubi86` / `wubi98` / `wubi06`，
     * 切换键盘时会写进默认 SharedPreferences 的 `Key_KeyboardType`。
     * 这是最便宜、最稳的判断方式；`Kernel.getCurrentInputMode()` 作为二次确认。
     */
    fun isWubiKeyboard(context: Context): Boolean {
        // 正常路径：落盘值就是权威答案，避免每次按键都去问 native 引擎
        val type = keyboardType(context)
        if (type.isNotEmpty()) return type.startsWith("wubi")
        // 兜底：有些版本刚切过去还没落盘，用引擎模式再确认一次
        return OplusCandidateHook.currentInputMode().startsWith("wubi")
    }

    /** 当前键盘类型（`Key_KeyboardType`），仅用于日志诊断。 */
    fun keyboardType(context: Context): String =
        context.getSharedPreferences(context.packageName + "_preferences", Context.MODE_PRIVATE)
            .getString(PREF_KEY_KEYBOARD_TYPE, "") ?: ""
}
