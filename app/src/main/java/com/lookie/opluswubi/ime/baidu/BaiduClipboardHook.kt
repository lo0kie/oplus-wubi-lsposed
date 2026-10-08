package com.lookie.opluswubi.ime.baidu

import com.lookie.opluswubi.Reflect
import com.lookie.opluswubi.XLog
import com.lookie.opluswubi.hookGuarded
import io.github.libxposed.api.XposedModule

/**
 * 解除剪贴板历史条数上限（百度输入法）。
 *
 * ## 上限在哪
 *
 * `com.baidu.input.clipboard.config.ClipboardConfig.c():I` 就是「最多保留/查询多少条剪贴板」：
 *
 * ```java
 * int def = 300;
 * if (ClipboardSyncHelper.c()) return def;                    // 开了剪贴板同步就固定 300
 * return Math.max(def, PreferenceManager.c.getInt("clipboard.config.max_query_count", 300));
 * ```
 *
 * 调用方里 `com.baidu.input.clipboard.datamanager.clipboard.ClipboardDataManagerImpl$safeInsert$1$1`
 * 就是入库路径（超量裁剪），面板/搜索那几个是查询条数。
 *
 * ## 为什么 getter 和偏好表都要改
 *
 * 这个值**存在偏好表里**（键 `clipboard.config.max_query_count`），
 * `ClipboardConfig.a(int,boolean)` 用 `PreferenceManager.c.h(value, key)` 写它：
 *
 * - **只改 getter 返回值**：裁剪会放开，但界面显示的是偏好表里的原值
 *   （真机表现：能存 1263 条，界面仍写 1263/300）；
 * - **只改偏好表**：开了剪贴板同步时 `c()` 会被 `ClipboardSyncHelper.c()` 短路成 300，裁剪又回来了。
 *
 * 所以两处都改：偏好表写成 100000（界面跟着显示），getter 兜住同步那条短路。
 *
 * ## 界面上那个数字是怎么变过来的（2026-10-07 真机确认）
 *
 * 面板标题里的 300 是 `ClipboardPanelListView` 里的**字面量**
 * （`String.format(getString(0x7f1203cb), list.size(), 300)`），既不读偏好表也不调 `c()`。
 * 但**不需要去改它**：界面在启动时读偏好表并缓存，所以偏好表写成 100000 之后，
 * 下次进面板就会显示 `剪贴板(N/100000)`。
 *
 * 排查时曾挂过三层文本改写（`String.format` / `TextView.setText` / `ImeTextView.setText`），
 * 真机日志里**一次都没命中** —— 说明显示值确实来自偏好表，那三层已删除
 * （其中 `TextView.setText` 还在热路径上，留着纯属负担）。
 */
internal object BaiduClipboardHook {

    private const val CLS_CLIPBOARD_CONFIG = "com.baidu.input.clipboard.config.ClipboardConfig"

    /** 上限落盘的偏好键（界面读的也是它）。 */
    private const val KEY_MAX_QUERY_COUNT = "clipboard.config.max_query_count"

    /** 偏好表：`PreferenceManager.c`（常规偏好表，与 `WbPrefUtilKt` 用的是同一张）。 */
    private const val CLS_PREF_MANAGER = "com.baidu.input.manager.PreferenceManager"
    private const val FIELD_PREF = "c"

    /**
     * 放开后的条数。
     *
     * 不用 `Int.MAX_VALUE`：这个值还会进 SQL 的 `LIMIT` 和面板查询，留一个「实际到不了、
     * 但不会溢出/预分配」的数更稳。
     */
    private const val UNLIMITED = 100_000

    @Volatile
    private var logged = false

    @Volatile
    private var prefSynced = false

    @Volatile
    private var prefManagerClass: Class<*>? = null

    fun install(module: XposedModule, loader: ClassLoader) {
        // ⚠️ 这里**只做类查找，绝不碰静态字段值**（2026-10-07 真机事故：
        // 百度输入法一启动就崩，dropbox 栈是
        // `WubiModule.onPackageReady → BaiduClipboardHook.install → syncPref → Reflect.staticField
        //  → java.lang.ExceptionInInitializerError`，
        // 根因 `PreferenceManager.<clinit>` 依赖还没建好的 `BDSP` 单例，直接 NPE，
        //  之后任何访问都是 `NoClassDefFoundError`）。
        //
        // 本方法跑在 `onPackageReady` 阶段，那时宿主 App 的 Application 还没创建，
        // 它自己的单例/静态初始化顺序还没走完。**读静态字段值会触发类初始化**，
        // 抢在初始化之前就访问，宿主就炸。
        prefManagerClass = Reflect.findClass(loader, CLS_PREF_MANAGER)
        XLog.i("已找到 $CLS_PREF_MANAGER（暂不读静态字段，等输入法服务就绪后再同步偏好）")

        val cls = Reflect.findClass(loader, CLS_CLIPBOARD_CONFIG) ?: run {
            XLog.w("找不到 $CLS_CLIPBOARD_CONFIG，剪贴板条数上限保持原样（不影响其它功能）")
            return
        }
        val target = Reflect.method(cls, "c") ?: run {
            XLog.w("找不到 $CLS_CLIPBOARD_CONFIG.c()，剪贴板条数上限保持原样")
            return
        }
        module.hookGuarded(target) { chain ->
            val original = chain.proceed()
            if (!logged) {
                logged = true
                XLog.i("已解除剪贴板条数上限：${cls.simpleName}.c() 由 $original 改为 $UNLIMITED")
                // 第一次真正用到剪贴板时，宿主早已初始化完毕 —— 这才是安全的时机写偏好表。
                syncPref()
            }
            UNLIMITED
        }
        XLog.i("已 Hook $CLS_CLIPBOARD_CONFIG.c()（剪贴板历史不再按 300 条裁剪）")
    }

    /** 输入法服务就绪后再同步偏好表（宿主初始化已完成，触发类初始化是安全的）。 */
    fun syncPrefWhenReady() {
        runCatching { syncPref() }
            .onFailure { XLog.w("同步剪贴板偏好表失败（上限值仍是旧值，不影响功能）", it) }
    }

    /**
     * 把偏好表里的 `clipboard.config.max_query_count` 写成 [UNLIMITED]。
     *
     * 走百度自己的写法：`ClipboardConfig.a(int,boolean)` 就是
     * `PreferenceManager.c.h(value, key)`（`h` 是 `IPreference` 的「按字符串键写 int」）。
     * 只做一次。
     */
    private fun syncPref() {
        if (prefSynced) return
        prefSynced = true
        val cls = prefManagerClass ?: run {
            XLog.w("找不到 $CLS_PREF_MANAGER，界面上的上限值仍是旧值")
            return
        }
        // ⚠️ 整段包在 runCatching 里：**读静态字段会触发宿主类的静态初始化**，
        // 而宿主刚启动时它的单例可能还没建好（`ExceptionInInitializerError` /
        // `NoClassDefFoundError`）。这里一旦抛异常就会顺着 onPackageReady 上去把宿主带崩，
        // 所以**任何异常都必须吞掉**——写不进偏好表只是「界面显示旧值」，崩掉就什么都没了。
        runCatching {
            val pref = Reflect.staticField(cls, FIELD_PREF) ?: run {
                XLog.w("读不到 $CLS_PREF_MANAGER.$FIELD_PREF，界面上的上限值仍是旧值")
                return@runCatching
            }
            val putInt = Reflect.method(pref.javaClass, "h", Integer.TYPE, String::class.java) ?: run {
                XLog.w("找不到 IPreference.h(int,String)，界面上的上限值仍是旧值")
                return@runCatching
            }
            putInt.invoke(pref, UNLIMITED, KEY_MAX_QUERY_COUNT)
        }.onFailure {
            XLog.w("同步剪贴板偏好表失败（上限值仍是旧值，不影响其它功能；宿主可能还没初始化完）", it)
        }.onSuccess {
            XLog.i("已把偏好表里的 $KEY_MAX_QUERY_COUNT 写成 $UNLIMITED（界面显示的就是它）")
        }
    }
}
