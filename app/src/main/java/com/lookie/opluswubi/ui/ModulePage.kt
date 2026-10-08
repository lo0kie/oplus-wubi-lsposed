package com.lookie.opluswubi.ui

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.res.ColorStateList
import android.content.res.Configuration
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.os.Handler
import android.os.Looper
import android.text.TextUtils
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.lookie.opluswubi.XLog
import com.lookie.opluswubi.hookGuarded
import com.lookie.opluswubi.table.ModuleConfig
import com.lookie.opluswubi.table.TableStore
import io.github.libxposed.api.XposedModule
import java.lang.ref.WeakReference

/**
 * 模块自己的「自定义码表」页面 —— **整屏、完全由模块绘制**，不借用宿主的条目框架。
 *
 * ## 为什么要有它（2026-10-07 用户要求）
 *
 * 原先码表管理是「借宿主设置页的条目树」：方案列表 + 添加方案注入到「五笔方案」页，
 * 其余交互塞进宿主的对话框。那样每一步都要跟宿主的列表框架、条目类、混淆字段名对齐，
 * 一旦宿主的行样式/字段名变了，能改的东西就只剩「换个位置」。
 *
 * 现在改成：**宿主页面只留一条入口**，
 * 完整能力（切换 / 导入 / 重命名 / 删除 / 停用）全部收进这一页 —— 页面里的每一个像素
 * 都是模块画的，不依赖宿主的条目类、不依赖它的混淆字段名。
 *
 * ```
 * ┌──────────────────────────────────────────┐
 * │ 返回            自定义码表                │   ← 顶部标题栏（返回 = 关闭本页）
 * ├──────────────────────────────────────────┤
 * │ ┌──────────────────────────────────────┐ │
 * │ │ 虎码字词              使用中 · 171431 条│ │   ← 点 = 启用该方案
 * │ │ 五笔新世纪                        48213 条│ │   ← 长按 = 重命名 / 删除
 * │ └──────────────────────────────────────┘ │
 * │ ┌──────────────────────────────────────┐ │
 * │ │ 添加方案…            导入 txt 码表     │ │
 * │ └──────────────────────────────────────┘ │
 * │ ┌──────────────────────────────────────┐ │
 * │ │ 停用自定义方案        恢复输入法自带词库│ │   ← 有方案生效时才出现
 * │ └──────────────────────────────────────┘ │
 * └──────────────────────────────────────────┘
 * ```
 *
 * ## 挂在哪、怎么退（不新增组件）
 *
 * 模块是 LSPosed 模块，**没有自己的 Activity**（也不该有：那得改输入法的 manifest），
 * 所以这一页是**叠在当前设置页窗口 decor 上的整屏视图**：
 *
 * - 挂上去就吃掉整屏的触摸（`isClickable`），宿主页面在下面既看不到也点不到；
 * - 关闭有三条路：左上角「返回」、系统返回键（[install] 里挂了
 *   `android.app.Activity.onBackPressed` / `onKeyDown`，只在这一页开着时才消费返回键）、
 *   宿主页面被销毁（视图跟着 decor 一起没了）。
 * - 页面里的所有操作都调 [TableStore] / [TableManager]，与别的入口同一套数据，
 *   所以哪边改完另一边的 `refresh()` 都会跟上。
 *
 * ## 约束
 *
 * - 只用 framework 的 `android.*`（`ui/` 包零 androidx 依赖），也不碰宿主的任何类；
 * - 任何一步失败都只打日志、什么都不做（绝不能影响设置页本身）；
 * - 全程弱引用持住 Activity 与根视图，不给页面造成泄漏。
 */
internal object ModulePage {

    /** 挂在 decor 上的标记 tag：判重、以及采样时跳过自己。 */
    private const val TAG = "opluswubi_module_page"

    private const val TITLE = "自定义码表"

    /** 采样不到主题色时的兜底强调绿。 */
    private const val WECHAT_GREEN = 0xFF07C160.toInt()

    private val main = Handler(Looper.getMainLooper())

    /** 已经挂过「返回键」Hook（按进程去重）。 */
    @Volatile
    private var installed = false

    /** 这一页当前挂在哪个 Activity 上。 */
    @Volatile
    private var activityRef: WeakReference<Activity>? = null

    /** 根视图（就是盖住 decor 的那个整屏 FrameLayout）。 */
    @Volatile
    private var rootRef: WeakReference<View>? = null

    /** 列表容器（刷新时只重填它）。 */
    @Volatile
    private var bodyRef: WeakReference<LinearLayout>? = null

    /** 已经打过「这一页没有可展示的 Activity」的日志，避免刷屏。 */
    @Volatile
    private var loggedNoActivity = false

    // ---------------------------------------------------------------- 安装 / 开 / 关

    /**
     * 挂返回键 Hook（框架类 `android.app.Activity`，不受宿主混淆与多 ClassLoader 影响）。
     *
     * 只在这一页**开着**时才消费返回键，其余情况原样放行 —— 宿主自己的返回逻辑一个都不改。
     */
    fun install(module: XposedModule) {
        if (installed) return
        installed = true

        // 两条都挂：`onBackPressed` 是绝大多数页面的返回入口；`onKeyDown` 兜住
        // 「子类重写了 onBackPressed 但没处理 KEYCODE_BACK」那一类（真机验证时按实际命中看日志）。
        runCatching {
            val cls = Activity::class.java
            val back = runCatching { cls.getDeclaredMethod("onBackPressed") }.getOrNull()
            if (back != null) {
                module.hookGuarded(back) { chain ->
                    val self = chain.getThisObject()
                    if (self is Activity && isOpenOn(self)) {
                        close("系统返回键")
                        return@hookGuarded null
                    }
                    chain.proceed()
                }
                XLog.i("已 Hook android.app.Activity.onBackPressed（模块页面开着时才消费返回键）")
            } else {
                XLog.w("android.app.Activity 上没有 onBackPressed()，模块页面只能靠「返回」按钮关闭")
            }
            val keyDown = runCatching {
                cls.getDeclaredMethod("onKeyDown", Int::class.javaPrimitiveType!!, KeyEvent::class.java)
            }.getOrNull()
            if (keyDown != null) {
                module.hookGuarded(keyDown) { chain ->
                    val self = chain.getThisObject()
                    val code = chain.getArg(0) as? Int
                    if (self is Activity && code == KeyEvent.KEYCODE_BACK && isOpenOn(self)) {
                        close("系统返回键")
                        return@hookGuarded true
                    }
                    chain.proceed()
                }
                XLog.i("已 Hook android.app.Activity.onKeyDown（模块页面开着时吃掉返回键）")
            }
        }.onFailure { XLog.w("模块页面的返回键 Hook 安装失败（页面仍可用「返回」按钮关闭）", it) }
    }

    /** 打开这一页（已开着就刷新）。[context] 里能解出 Activity 才打得开。 */
    fun open(context: Context?) {
        val activity = activityOf(context)
        if (activity == null) {
            if (!loggedNoActivity) {
                loggedNoActivity = true
                XLog.w("拿不到 Activity，模块页面打不开（调用方传的是 applicationContext？）")
            }
            return
        }
        runCatching { attach(activity) }
            .onFailure { XLog.w("打开模块页面失败", it) }
    }

    /**
     * 当前这一页是不是挂在 [activity] 上、而且还在窗口里。
     *
     * 宿主每次重建列表都会造新的页面实例，所以判据认**实例身份**而不是类名。
     */
    private fun isOpenOn(activity: Activity): Boolean {
        val root = rootRef?.get() ?: return false
        if (root.parent == null) return false
        return activityRef?.get() === activity
    }

    /** 刷新列表内容（导入 / 切换 / 重命名 / 删除之后调；没开着就什么都不做）。 */
    fun refresh() {
        val body = bodyRef?.get() ?: return
        val activity = activityRef?.get() ?: return
        if (body.parent == null) return
        main.post {
            runCatching { fillBody(activity, body) }
                .onFailure { XLog.w("刷新模块页面失败", it) }
        }
    }

    /** 关掉这一页。 */
    fun close(reason: String) {
        val root = rootRef?.get()
        activityRef = null
        rootRef = null
        bodyRef = null
        if (root == null) return
        runCatching { (root.parent as? ViewGroup)?.removeView(root) }
            .onFailure { XLog.w("关闭模块页面失败", it) }
        XLog.i("模块页面已关闭（$reason）")
    }

    // ---------------------------------------------------------------- 挂载

    private fun attach(activity: Activity) {
        if (activity.isFinishing) {
            XLog.w("模块页面：Activity 正在结束（${activity.javaClass.simpleName}），放弃")
            return
        }
        val decor = activity.window?.decorView as? ViewGroup
        if (decor == null) {
            // 窗口还没建好：重试几帧（与入口胶囊同一套做法）
            XLog.i("模块页面：窗口还没建好（${activity.javaClass.simpleName}），稍后重试")
            main.postDelayed({
                runCatching { attach(activity) }.onFailure { XLog.w("重试挂模块页面失败", it) }
            }, 150L)
            return
        }
        // 已经开着（同一个页面实例）就只刷新，不重建视图
        if (isOpenOn(activity)) {
            XLog.i("模块页面已经开着，只刷新内容")
            refresh()
            return
        }
        // 换了个页面实例（宿主的设置页被重建 / 从另一页点进来）：先把旧的收掉
        close("换页面")

        val existing = decor.findViewWithTag<View>(TAG)
        if (existing != null) {
            // 上一个进程 / 上一个模块版本留下的整屏视图：认不回来就拆掉重建
            XLog.i("这一页已有模块页面视图，拆掉重建（${activity.javaClass.simpleName}）")
            runCatching { decor.removeView(existing) }
        }

        val body = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }
        // 整屏视图连状态栏那一块也盖住了：标题栏自己让出状态栏高度，否则「返回」会被顶到通知栏底下
        val topInset = topInsetOf(decor, activity)
        val root = FrameLayout(activity).apply {
            tag = TAG
            setBackgroundColor(pageColor(activity))
            // 吃掉整屏触摸：底下宿主页面的滚动、点击都碰不到
            isClickable = true
            isFocusable = true
            setOnClickListener { }
            addView(buildHeader(activity, topInset), headerParams(activity, topInset))
            addView(
                ScrollView(activity).apply {
                    addView(body, FrameLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT,
                    ))
                },
                bodyParams(activity, topInset),
            )
        }

        decor.addView(root, FrameLayout.LayoutParams(MATCH_PARENT_LP, MATCH_PARENT_LP))
        rootRef = WeakReference(root)
        bodyRef = WeakReference(body)
        activityRef = WeakReference(activity)
        fillBody(activity, body)
        XLog.i("已打开模块自己的「自定义码表」页面（${activity.javaClass.simpleName}）")
    }

    private const val MATCH_PARENT_LP = ViewGroup.LayoutParams.MATCH_PARENT

    private fun buildHeader(context: Context, topInset: Int): View {
        val bar = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setBackgroundColor(pageColor(context))
            setPadding(dp(context, 4), topInset, dp(context, 16), 0)
        }
        bar.addView(
            TextView(context).apply {
                text = "返回"
                textSize = 15f
                setTextColor(accentColor(context))
                gravity = Gravity.CENTER
                isClickable = true
                setPadding(dp(context, 12), dp(context, 8), dp(context, 12), dp(context, 8))
                setOnClickListener { close("点了返回") }
            },
        )
        bar.addView(
            TextView(context).apply {
                text = TITLE
                textSize = 17f
                typeface = Typeface.DEFAULT_BOLD
                setTextColor(primaryColor(context))
                maxLines = 1
                ellipsize = TextUtils.TruncateAt.END
            },
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f),
        )
        return bar
    }

    private fun headerParams(context: Context, topInset: Int): FrameLayout.LayoutParams =
        FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            dp(context, 52) + topInset,
        ).apply {
            gravity = Gravity.TOP
        }

    private fun bodyParams(context: Context, topInset: Int): FrameLayout.LayoutParams =
        FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT).apply {
            topMargin = dp(context, 52) + topInset
        }

    /**
     * 状态栏那一条的高度（px）。
     *
     * 先问窗口自己的 insets —— 页面若是 edge-to-edge 布局，`decor` 从屏幕顶边开始，
     * 这一条会给出状态栏高度；非 edge-to-edge 时窗口本来就起点在状态栏之下，它是 0，
     * 我们也就不会再叠一层。insets 拿不到（老系统 / 反射失败）才退回资源里的状态栏高度。
     */
    private fun topInsetOf(decor: View, context: Context): Int = runCatching {
        val insets = decor.rootWindowInsets
        val fromInsets = insets?.let {
            runCatching {
                it.javaClass.getMethod("getSystemWindowInsetTop").invoke(it) as? Int
            }.getOrNull()
        }
        if (fromInsets != null && fromInsets > 0) return@runCatching fromInsets
        val id = context.resources.getIdentifier("status_bar_height", "dimen", "android")
        if (id > 0) context.resources.getDimensionPixelSize(id) else 0
    }.getOrDefault(0)

    // ---------------------------------------------------------------- 内容

    private fun fillBody(activity: Activity, body: LinearLayout) {
        val schemes = runCatching { TableStore.list(activity) }.getOrDefault(emptyList())
        val activeId = runCatching { TableStore.activeId(activity) }.getOrDefault("")
        val active = schemes.firstOrNull { it.id == activeId }

        body.removeAllViews()
        body.setPadding(0, dp(activity, 8), 0, dp(activity, 24))

        body.addView(
            hint(
                activity,
                when {
                    active != null -> "正在用「${active.name}」：输入法自带的五笔设置会整页置灰，候选整条由码表接管。"
                    schemes.isEmpty() -> "还没有码表：先导入一份 txt（列顺序自动识别，内容相同的会自动去重）。"
                    else -> "点下面的方案即可启用；自定义方案与输入法自带的方案自动互斥。"
                },
            ),
        )

        for (meta in schemes) {
            val isActive = meta.id == activeId
            body.addView(
                row(
                    context = activity,
                    title = meta.name,
                    desc = if (isActive) "使用中 · ${meta.entryCount} 条" else "${meta.entryCount} 条",
                    descAccent = isActive,
                    onClick = { activate(activity, meta.id, meta.name) },
                    onLongClick = { TableManager.showSchemeActions(activity, meta.id) },
                ),
            )
        }

        val limitReached = schemes.size >= TableManager.MAX_SCHEMES
        body.addView(
            row(
                context = activity,
                title = "添加方案…",
                desc = if (limitReached) {
                    "已达上限 ${TableManager.MAX_SCHEMES} 个（先删掉一个再导）"
                } else {
                    "导入 txt 码表，导入后自动启用"
                },
                descAccent = false,
                onClick = { TableManager.addScheme(activity) },
                onLongClick = null,
            ),
        )

        if (active != null) {
            body.addView(
                row(
                    context = activity,
                    title = "停用自定义方案",
                    desc = "恢复输入法自带词库（你也可以在「五笔方案」里选内置方案）",
                    descAccent = false,
                    onClick = { deactivate(activity) },
                    onLongClick = null,
                ),
            )
        }

        body.addView(hint(activity, "长按方案可以重命名 / 删除；码表与配置都存在输入法自己的私有目录里。"))
        XLog.i(
            "模块页面内容：${schemes.size} 个方案，当前=${active?.name ?: "无"}",
        )
    }

    /** 启用某个方案（点条目 = 启用，与宿主方案列表的点击语义一致）。 */
    private fun activate(context: Context, id: String, name: String) {
        if (runCatching { TableStore.activeId(context) }.getOrDefault("") == id) return
        runCatching { TableStore.setActive(context, id) }
            .onFailure { XLog.w("启用自定义方案失败：$name", it) }
        XLog.i("模块页面启用方案：$name")
        notifyChanged()
    }

    /** 停用自定义方案（清空当前方案，输入法自带词库恢复出词）。 */
    private fun deactivate(context: Context) {
        runCatching { TableStore.setActive(context, "") }
            .onFailure { XLog.w("停用自定义方案失败", it) }
        XLog.i("模块页面停用自定义方案")
        notifyChanged()
    }

    /** 方案变了：本页、宿主页面里的入口（条目 / 胶囊）一起刷。 */
    private fun notifyChanged() {
        refresh()
        EntryRefresher.refresh()
        runCatching { EntryPanel.refresh() }
    }

    // ---------------------------------------------------------------- 视图小件

    /**
     * 一张卡片式的一行：标题 + 说明，整块可点、可长按。
     *
     * 圆角卡片 + 左右 16dp 外边距，跟宿主设置页那些卡片是同一套视觉习惯；颜色按深色 / 浅色两套取
     * （不采样宿主，因为这一页本来就不打算长得跟宿主一模一样 —— 它是模块自己的页面）。
     */
    private fun row(
        context: Context,
        title: CharSequence,
        desc: CharSequence?,
        descAccent: Boolean,
        onClick: () -> Unit,
        onLongClick: (() -> Unit)?,
    ): View {
        val card = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            background = cardBackground(context)
            isClickable = true
            isFocusable = true
            setPadding(dp(context, 18), dp(context, 14), dp(context, 18), dp(context, 14))
            setOnClickListener { runCatching { onClick() }.onFailure { XLog.w("模块页面点击处理失败：$title", it) } }
            onLongClick?.let { action ->
                isLongClickable = true
                setOnLongClickListener {
                    runCatching { action() }.onFailure { XLog.w("模块页面长按处理失败：$title", it) }
                    true
                }
            }
        }
        card.addView(
            TextView(context).apply {
                text = title
                textSize = 16f
                typeface = Typeface.DEFAULT_BOLD
                setTextColor(primaryColor(context))
                maxLines = 1
                ellipsize = TextUtils.TruncateAt.END
            },
        )
        if (!desc.isNullOrEmpty()) {
            card.addView(
                TextView(context).apply {
                    text = desc
                    textSize = 13f
                    setTextColor(if (descAccent) accentColor(context) else secondaryColor(context))
                    maxLines = 2
                    ellipsize = TextUtils.TruncateAt.END
                    setPadding(0, dp(context, 5), 0, 0)
                },
            )
        }
        return wrap(card, context)
    }

    /** 纯说明文字，不可点。 */
    private fun hint(context: Context, text: CharSequence): View {
        val view = TextView(context).apply {
            this.text = text
            textSize = 12.5f
            setTextColor(secondaryColor(context))
            setPadding(dp(context, 20), dp(context, 6), dp(context, 20), dp(context, 12))
        }
        return wrap(view, context)
    }

    private fun wrap(content: View, context: Context): View {
        val holder = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            addView(content, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        }
        holder.layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
        ).apply {
            leftMargin = dp(context, 16)
            rightMargin = dp(context, 16)
            bottomMargin = dp(context, 8)
        }
        return holder
    }

    private fun cardBackground(context: Context): Drawable {
        val shape = GradientDrawable().apply {
            cornerRadius = dp(context, 14).toFloat()
            setColor(cardColor(context))
        }
        val mask = GradientDrawable().apply {
            cornerRadius = dp(context, 14).toFloat()
            setColor(0x00000000)
        }
        return RippleDrawable(ColorStateList.valueOf(rippleColor(context)), shape, mask)
    }

    private fun rippleColor(context: Context): Int = if (isNight(context)) 0x22FFFFFF else 0x14000000

    // ---------------------------------------------------------------- 颜色 / 尺寸

    private fun isNight(context: Context): Boolean =
        context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK ==
            Configuration.UI_MODE_NIGHT_YES

    private fun pageColor(context: Context): Int =
        if (isNight(context)) 0xFF111111.toInt() else 0xFFF2F2F2.toInt()

    private fun cardColor(context: Context): Int =
        if (isNight(context)) 0xFF2A2D33.toInt() else 0xFFFFFFFF.toInt()

    private fun primaryColor(context: Context): Int =
        if (isNight(context)) 0xFFF2F2F2.toInt() else 0xFF191919.toInt()

    private fun secondaryColor(context: Context): Int =
        if (isNight(context)) 0xFF9AA0A6.toInt() else 0xFF8C8C8C.toInt()

    private fun accentColor(context: Context): Int = WECHAT_GREEN

    private fun dp(context: Context, value: Int): Int =
        (value * context.resources.displayMetrics.density).toInt()

    /** 从任意 Context 里解出 Activity（`ContextThemeWrapper` / `ContextWrapper` 都往上剥）。 */
    private fun activityOf(context: Context?): Activity? {
        var ctx: Context? = context
        var depth = 0
        while (ctx != null && depth++ < 8) {
            if (ctx is Activity) return ctx
            ctx = (ctx as? ContextWrapper)?.baseContext
        }
        return null
    }
}
