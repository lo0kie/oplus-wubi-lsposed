package com.lookie.opluswubi.ime.sogou

import com.lookie.opluswubi.Reflect
import com.lookie.opluswubi.XLog
import com.lookie.opluswubi.hookGuarded
import com.lookie.opluswubi.ime.ImeEnv
import com.lookie.opluswubi.table.ModuleConfig
import io.github.libxposed.api.XposedModule

/**
 * 解除剪贴板历史条数上限（搜狗输入法）。
 *
 * ## 上限在哪
 *
 * `com.sogou.clipboard.repository.manager.a.c():I` 就是「剪贴板最多留多少条」：
 *
 * ```java
 * int d = this.d;                      // 缓存字段
 * if (d == -1) {
 *     d = KV("clipboard_settings_mmkv").getInt("clipboard_max_item_count", 500);   // ← 默认 500
 *     this.d = d;
 * }
 * return this.d;
 * ```
 *
 * 同一个类里 `i(int)` 是它的 setter（用户改设置时写回 KV 并更新缓存）。
 * 直接把 getter 的返回值顶掉，读它的地方（入库裁剪、列表查询）就都放开了。
 *
 * ## 开关
 *
 * Hook **常驻**（装了就没法卸），由模块设置里的「解除剪贴板条数上限」决定放行还是顶掉。
 * 每次调用读一遍配置（`ModuleConfig` 按 mtime 缓存，热路径开销可忽略），
 * 所以用户在模块设置里一改就生效，**不用重启输入法**。
 */
internal object SogouClipboardHook {

    private const val CLS_CLIPBOARD_MANAGER = "com.sogou.clipboard.repository.manager.a"

    /** 放开后的条数；不用 `Int.MAX_VALUE`，留一个实际到不了、又不会溢出/预分配的数。 */
    private const val UNLIMITED = 100_000

    @Volatile
    private var loggedOn = false

    fun install(module: XposedModule, loader: ClassLoader) {
        val cls = Reflect.findClass(loader, CLS_CLIPBOARD_MANAGER) ?: run {
            XLog.w("找不到 $CLS_CLIPBOARD_MANAGER，剪贴板条数上限保持原样（不影响其它功能）")
            return
        }
        val target = Reflect.method(cls, "c") ?: run {
            XLog.w("找不到 $CLS_CLIPBOARD_MANAGER.c()，剪贴板条数上限保持原样")
            return
        }
        module.hookGuarded(target) { chain ->
            val original = chain.proceed()
            // 开关关掉 → 原样放行（恢复搜狗自己的 500 上限）
            if (!clipboardUnlimited()) return@hookGuarded original
            if (!loggedOn) {
                loggedOn = true
                XLog.i("已解除剪贴板条数上限：${cls.simpleName}.c() 由 $original 改为 $UNLIMITED")
            }
            UNLIMITED
        }
        XLog.i("已 Hook $CLS_CLIPBOARD_MANAGER.c()（受「解除剪贴板条数上限」开关控制）")
    }

    /** 读模块设置里的开关；拿不到 Context 时按「开」处理（维持原行为，别无声地把功能关掉）。 */
    private fun clipboardUnlimited(): Boolean {
        val ctx = ImeEnv.context() ?: return true
        return ModuleConfig.clipboardUnlimited(ctx)
    }
}
