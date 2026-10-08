package com.lookie.opluswubi.ime.sogou

import com.lookie.opluswubi.Reflect
import com.lookie.opluswubi.XLog
import com.lookie.opluswubi.hookGuarded
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
 */
internal object SogouClipboardHook {

    private const val CLS_CLIPBOARD_MANAGER = "com.sogou.clipboard.repository.manager.a"

    /** 放开后的条数；不用 `Int.MAX_VALUE`，留一个实际到不了、又不会溢出/预分配的数。 */
    private const val UNLIMITED = 100_000

    @Volatile
    private var logged = false

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
            if (!logged) {
                logged = true
                XLog.i("已解除剪贴板条数上限：${cls.simpleName}.c() 由 $original 改为 $UNLIMITED")
            }
            UNLIMITED
        }
        XLog.i("已 Hook $CLS_CLIPBOARD_MANAGER.c()（剪贴板历史不再按 500 条裁剪）")
    }
}
