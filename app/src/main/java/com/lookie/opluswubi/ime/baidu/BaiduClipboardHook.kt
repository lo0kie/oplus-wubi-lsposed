package com.lookie.opluswubi.ime.baidu

import android.widget.TextView
import com.lookie.opluswubi.Reflect
import com.lookie.opluswubi.XLog
import com.lookie.opluswubi.hookGuarded
import com.lookie.opluswubi.ime.ImeEnv
import com.lookie.opluswubi.table.ModuleConfig
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
 * ## 上限要做三件事，缺一件都会露馅
 *
 * 1. **裁剪逻辑** —— 改 `ClipboardConfig.c()` 的返回值（下面第一个 Hook）；
 * 2. **落盘偏好** —— 键 `clipboard.config.max_query_count` 写成 100000，
 *    否则「开了剪贴板同步」那条短路又把 `c()` 拉回 300；
 * 3. **面板上的数字** —— `剪贴板(N/300)` 里的 300 是 `ClipboardSubTabView.setCount(int)`
 *    里**硬编码的字符串字面量**（连 `String.format` 都没走），既不读偏好表也不调 `c()`，
 *    所以前两步做了它照样显示 300（2026-10-10 真机：能存 2101 条，仍写 `2101/300`）。
 *
 * ## 面板数字的定位过程（2026-10-10）—— 记录一条弯路
 *
 * 我先按资源反查：`string/clipboard_with_count` = `"剪贴板(%d/%d)"`（id `0x7f1203cb`）
 * 全 dex 只有 `com.baidu.input.ime.front.ClipboardPanelListView.h(java.util.List)` 一处引用，
 * 于是照它改。**结果真机毫无反应**（`h()` 一次都没被调用）。
 *
 * 教训：**「资源 id 唯一引用点」≠「真机走的那条路」** —— dex 里同时存在
 * `ClipboardPanelListView`（硬键盘）和 `ClipboardPanelViewImpl`（触屏）两套面板。
 *
 * 于是换成**反向抓凶手**：临时 Hook `android.widget.TextView.setText(CharSequence)`，
 * 只打「文本含 `/` 且调用栈里有 `clipboard` 包」的，一次就抓到了：
 *
 * ```
 * [嗅探] 面板写文本："0/300"    ← ClipboardSubTabView.<init>(Proguard:104)
 * [嗅探] 面板写文本："2101/300" ← ClipboardSubTabView.setCount(Proguard:38)
 * ```
 *
 * ## 面板数字的字节码证据（`classes2.dex`，`ClipboardSubTabView.setCount(I)`）
 *
 * 两条分支都把 `/300` 写死：
 *
 * ```
 * 0006  iput v4, v3, ClipboardSubTabView.c              ; 存条数
 * 0008  const/16 v1, #300                               ; 比较用
 * 0010  const-string v2, "<font color ='-65536'>"       ; 超限分支（红字）
 * 0018  const-string v4, '</font>/300'                  ; ★ 硬编码字面量
 * 0031  const-string v4, '/300'                         ; ★ 另一条分支，同样硬编码
 * 003a  TextView->setText(...)                          ; 字段 b（ImeTextView）
 * ```
 *
 * 处理：Hook `setCount(int)`，`proceed()` 之后把条数 TextView（字段 `b`）上的文本重写一遍
 * （`N/300` → `N/100000`）。取「最后一段连续数字」当上限替换，对前缀/颜色/括号样式都不敏感；
 * 已是目标值就直接返回（`setCount` 刷新频繁，要零成本）。
 *
 * 反证记录：`Global.Y0`（一度以为它是上限）其实由 `DefaultCandSizeStrategy.e(I)` 写入，
 * 是**候选栏尺寸**，与剪贴板上限无关。
 */
internal object BaiduClipboardHook {

    private const val CLS_CLIPBOARD_CONFIG = "com.baidu.input.clipboard.config.ClipboardConfig"

    /**
     * 真机触屏面板里画「条数/上限」的那个 View。
     *
     * 2026-10-10 用 `TextView.setText` 嗅探反查确认（**不是** `ClipboardPanelListView`，
     * 那套是硬键盘的，`h(List)` 在真机上一次都没被调用）。
     */
    private const val CLS_SUB_TAB_VIEW = "com.baidu.input.clipboard.panel.view.ClipboardSubTabView"

    /** `ClipboardSubTabView` 里存条数文本的 `ImeTextView` 字段名（字节码里就是单字母 `b`）。 */
    private const val FIELD_COUNT_TEXT = "b"

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

    /** 上限数字前面的分隔符，用来判断「标题里的上限是不是已经改过了」。 */
    private const val LIMIT_SEP = "/"

    @Volatile
    private var logged = false

    @Volatile
    private var prefSynced = false

    @Volatile
    private var prefManagerClass: Class<*>? = null

    /** 「最后一段连续数字」= 上限。只在没匹配到时返回 null。 */
    private val TRAILING_INT = Regex("(\\d+)(?!.*\\d)")

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

        hookLimit(module, loader)
        hookSubTabCount(module, loader)
    }

    /**
     * ③ 面板标题：`ClipboardSubTabView.setCount(int)` 里硬编码的 `/300`。
     *
     * 真机（2026-10-10）**用 `TextView.setText` 嗅探反查出来的落笔处**：
     *
     * ```
     * [嗅探] 面板写文本："0/300"    ← ClipboardSubTabView.<init>(Proguard:104)
     * [嗅探] 面板写文本："2101/300" ← ClipboardSubTabView.setCount(Proguard:38)
     * ```
     *
     * `setCount(I)` 的字节码（`classes2.dex`，两条分支都把 `/300` 写死）：
     *
     * ```
     * 0006  iput v4, v3, ClipboardSubTabView.c              ; 存条数
     * 0008  const/16 v1, #300                               ; 比较用
     * 0010  const-string v2, "<font color ='-65536'>"       ; 超限分支（红字）
     * 0018  const-string v4, '</font>/300'                  ; ★ 硬编码
     * 0031  const-string v4, '/300'                         ; ★ 另一条分支，同样硬编码
     * 003a  TextView->setText(...)                          ; 字段 b（ImeTextView）
     * ```
     *
     * 处理：Hook 它，`proceed()` 之后把标题字段 `b` 上的文本重写一遍
     * （`剪贴板(N/300)` → `剪贴板(N/100000)`）。取「最后一段连续数字」当上限替换，
     * 对前缀/颜色/括号样式都不敏感。已是目标值就直接返回（`setCount` 刷新频繁，要零成本）。
     */
    private fun hookSubTabCount(module: XposedModule, loader: ClassLoader) {
        val cls = Reflect.findClass(loader, CLS_SUB_TAB_VIEW) ?: run {
            XLog.w("找不到 $CLS_SUB_TAB_VIEW，面板标题里的上限值保持百度默认")
            return
        }
        val target = Reflect.method(cls, "setCount", Integer.TYPE) ?: run {
            XLog.w("找不到 $CLS_SUB_TAB_VIEW.setCount(int)，面板标题里的上限值保持百度默认")
            return
        }
        module.hookGuarded(target) { chain ->
            val result = chain.proceed()
            if (!clipboardUnlimited()) return@hookGuarded result
            runCatching { rewriteCountText(chain.getThisObject(), cls) }
                .onFailure { XLog.w("重写剪贴板条数文本失败（仍显示原上限，不影响裁剪）", it) }
            result
        }
        XLog.i("已 Hook $CLS_SUB_TAB_VIEW.setCount(int)（把标题里的 /300 顶成 /$UNLIMITED）")
    }

    /** 把条数 TextView（字段 `b`）上的 `N/300` 改成 `N/$UNLIMITED`。 */
    private fun rewriteCountText(view: Any?, cls: Class<*>) {
        if (view == null) return
        val field = Reflect.field(cls, FIELD_COUNT_TEXT) ?: run {
            XLog.w("$CLS_SUB_TAB_VIEW 里没有字段 $FIELD_COUNT_TEXT，条数文本改不了")
            return
        }
        val raw = runCatching { field.get(view) }.getOrNull()
        val tv = raw as? TextView ?: run {
            XLog.w("字段 $FIELD_COUNT_TEXT 拿到的不是 TextView：${raw?.javaClass?.name}")
            return
        }
        val current = tv.text?.toString() ?: return
        // 已经是目标值（`.../100000`）→ 什么都不做，保证这层在刷新路径上是零成本。
        if (current.endsWith("$LIMIT_SEP$UNLIMITED")) return
        val match = TRAILING_INT.find(current) ?: return
        val replaced = current.substring(0, match.range.first) + UNLIMITED +
            current.substring(match.range.last + 1)
        tv.text = replaced
        XLog.i("条数文本已改写：$current → $replaced")
    }

    /** ① 裁剪/查询条数：直接改 `ClipboardConfig.c()` 的返回值。 */
    private fun hookLimit(module: XposedModule, loader: ClassLoader) {
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
            // 开关关掉 → 原样放行（恢复百度自己的 300 上限）
            if (!clipboardUnlimited()) return@hookGuarded original
            if (!logged) {
                logged = true
                XLog.i("已解除剪贴板条数上限：${cls.simpleName}.c() 由 $original 改为 $UNLIMITED")
                // 第一次真正用到剪贴板时，宿主早已初始化完毕 —— 这才是安全的时机写偏好表。
                syncPref()
            }
            UNLIMITED
        }
        XLog.i("已 Hook $CLS_CLIPBOARD_CONFIG.c()（受「解除剪贴板条数上限」开关控制）")
    }

    /** ② 输入法服务就绪后再同步偏好表（宿主初始化已完成，触发类初始化是安全的）。 */
    fun syncPrefWhenReady() {
        // 开关关着就别把偏好表写成上限 —— 否则界面会显示 100000，跟实际裁剪行为对不上
        if (!clipboardUnlimited()) return
        runCatching { syncPref() }
            .onFailure { XLog.w("同步剪贴板偏好表失败（上限值仍是旧值，不影响功能）", it) }
    }

    /** 读模块设置里的开关；拿不到 Context 时按「开」处理（维持原行为，别无声地把功能关掉）。 */
    private fun clipboardUnlimited(): Boolean {
        val ctx = ImeEnv.context() ?: return true
        return ModuleConfig.clipboardUnlimited(ctx)
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
