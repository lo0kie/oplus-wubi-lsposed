package com.lookie.opluswubi.ui

import android.app.Activity
import android.content.Context
import android.content.res.ColorStateList
import android.content.res.Configuration
import android.graphics.Outline
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.os.Handler
import android.os.Looper
import android.text.TextUtils
import android.util.TypedValue
import android.view.ContextThemeWrapper
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.ViewOutlineProvider
import android.widget.CompoundButton
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import com.lookie.opluswubi.XLog
import com.lookie.opluswubi.table.TableStore
import java.lang.ref.WeakReference

/**
 * 「自定义码表」入口 —— **浮在设置页底部的一枚胶囊**，不是平铺的大卡片。
 *
 * ## 为什么改成胶囊（2026-10-07 真机第四轮反馈）
 *
 * 前三版是一条**贴着窗口底边的平铺卡片**。为了不盖住页面内容，它必须让页面「让出」
 * 自己的高度；而微信的 RN 方案页内容正好铺满可视区、自身又**滚不动** ——
 * 真机日志里 10 个让位目标全部失败，任何让位动作都会把内容顶出屏幕却滚不回来
 * （用户看到的就是「有滚动条却滚不动」）。结论：这一页能做到的最小遮挡，
 * 就是「一个不占布局空间的小浮层」。
 *
 * 所以这一版：
 * - **不再让位** —— 一行代码都不改页面上任何 View 的 padding / margin，页面完全不受影响；
 * - 形态收成一枚胶囊（高 [CAPSULE_HEIGHT_DP] dp、宽随文字自适应），固定在窗口底部居中；
 * - **不需要拖动**，也不需要长按挪位置 —— 它就待在那一处，点一下打开模块自己的整屏页面（[ModulePage]）；
 * - 完整能力（切换 / 重命名 / 删除 / 添加 / 停用）全都在那个页面里，见 [open]。
 *
 * ## 样式从哪来（2026-10-07 真机反馈：「这也太丑了，完全是你自己写的样式，不能调内置的样式吗」）
 *
 * 不自己拍脑袋定样式：**运行时从当前页面上它自己那张白卡里采样** ——
 * 卡片底色、行文字字号 / 字色 / 是否加粗、勾选用的主题绿，以及那个对勾的画法，
 * 全部读它真实的 View（有的输入法设置页是 RN 画的，但 RN 的 shadow 树就是原生 View 子树，
 * `ReactTextView extends TextView`，颜色字号跑不掉，读得到）。
 * 采样对象就是页面里第一张「够宽 + 圆角 + 不透明」的卡片，跟它自己的分组卡片取同一套值。
 *
 * 采样拿不到（页面还没布局 / 它换了实现）就退回 [fallbackStyle]，
 * 而 fallback 的数值就是照着真机截图量的（16dp 圆角、16sp 加粗、微信绿），
 * 所以「采样成功」和「采样失败」两种情况下看起来都跟内置 UI 一致。
 *
 * ## 结构
 *
 * ```
 *                    ┌──────────────────────────────┐
 *                    │ ✓  自定义码表 · 虎码字词       │   ← 有自定义方案生效
 *                    └──────────────────────────────┘
 *                    ┌────────────────┐
 *                    │ 自定义码表      │                ← 还没导入过码表
 *                    └────────────────┘
 * ```
 *
 * - 点胶囊 = 打开模块自己的整屏页面（切方案 / 添加 / 重命名 / 删除 / 停用都在里面）；
 * - 长按胶囊 = 直接进「添加方案…」（导入 txt 码表）；
 * - 有自定义方案生效时，胶囊里带上宿主自己那套对勾，一眼能看出「正在用哪套表」。
 *
 * ## 约束
 *
 * - 只用 framework 的 `android.*`（`ui/` 包零 androidx 依赖）；
 * - 挂不上就只打日志、什么都不做（绝不能影响设置页本身）；
 * - 弱引用持住挂上去的 View，不给 Activity 造成泄漏。
 */
object EntryPanel {

    /** 挂在窗口上的标记 tag，用来判重（一个 Activity 只挂一张），采样时也用它跳过自己。 */
    private const val TAG = "opluswubi_entry_panel"

    private const val TITLE = "自定义码表"

    private const val MAX_ATTACH_ATTEMPTS = 12
    private const val ATTACH_RETRY_MS = 120L

    /** 挂上之后延迟多久做一次「按内置样式重建」—— RN 页面此时才刚把卡片量好。 */
    private const val RESTYLE_DELAY_MS = 380L

    /**
     * 胶囊尺寸（dp）：高度 / 离窗口底边的距离 / 内部左右内边距。
     *
     * 高度固定 → 圆角半径恒为高度的一半，就是个标准胶囊形；宽随文字自适应（WRAP_CONTENT），
     * 所以胶囊不会横跨整屏、也就不需要再从页面上抠空间。
     */
    private const val CAPSULE_HEIGHT_DP = 34
    private const val CAPSULE_BOTTOM_DP = 22
    private const val CAPSULE_PADDING_H_DP = 14

    private val main = Handler(Looper.getMainLooper())

    /** 采样不到主题色时的兜底微信绿。 */
    private const val WECHAT_GREEN = 0xFF07C160.toInt()

    /**
     * 入口胶囊本体。
     *
     * 结构比上一版的卡片简单得多：`root` 就是那枚胶囊（一个横向 `LinearLayout`），
     * 里面只有「文字（+ 有方案时的对勾）」这两种子 View —— 没有卡片盒子、没有行容器，
     * 所以 `adoptExisting` 可以靠「子 View 里没有 LinearLayout」认出这是新版结构。
     */
    private class Card(
        val root: LinearLayout,
        val decor: WeakReference<ViewGroup>,
        /** 首次采样到的样式；刷新时按页面当前状态重新采样。 */
        var style: Style,
    )

    /** 一套样式，字段全部来自宿主页面真实卡片（见文件头）。 */
    private class Style(
        val cardColor: Int,
        val radius: Int,
        val headerSize: Float,
        val headerColor: Int,
        val rowSize: Float,
        val rowColor: Int,
        val rowBold: Boolean,
        val rowHeight: Int,
        val paddingH: Int,
        val accent: Int,
        /** 卡片**左右外边距**：跟宿主页面上那张卡片对齐（用户要求「左右边距和原始卡片一致」）。 */
        val marginH: Int,
        /** 选中标记的样式：直接复用它自己那张卡片里对勾的画法。 */
        val check: CheckStyle,
    )

    /**
     * 选中标记（对勾）的画法。
     *
     * 用户要求「checked 样式也不一致」—— 用一个文本 `✓` 去凑永远凑不像：
     * 宿主可能是图片（`ImageView`/`ReactImageView`）画的，也可能是字体图标。
     * 所以这里两种都采：采到图片就 [drawable] 原样贴过来，采到就退 [textSize] + [tint] 的文本勾。
     */
    private class CheckStyle(
        val drawable: Drawable? = null,
        /** 控件边长（px）；0 = 让它自适应内容。 */
        val markSize: Int = 0,
        /** 文本勾的字号（sp）。**不能为 0** —— 0 会让对勾彻底看不见（2026-10-07 真机踩过）。 */
        val textSize: Float = 18f,
        val tint: Int = 0,
    )

    /** 入口卡片。 */
    @Volatile
    private var cardRef: WeakReference<Card>? = null

    /**
     * 最近一次挂卡片的 Activity。
     *
     * `cardRef` 失效时（被移除、被旧进程留下、本进程重启）靠它找回 decor 上的卡片 ——
     * 2026-10-07 真机反馈「checked 经常切换时没动」就是因为那时只能 `?: return`。
     */
    @Volatile
    private var activityRef: WeakReference<Activity>? = null

    private fun currentActivity(): Activity? = activityRef?.get()

    /**
     * 往一个 Activity 窗口底部挂入口卡片；重复调用无副作用。
     *
     * 调用时机由 Hook 负责（`onCreate` / `onResume`）。`onCreate` 那一次窗口往往还没建好
     * （`decorView` 还是 null），所以这里失败会自己重试几帧，而不是静默放弃。
     */
    fun attach(activity: Activity?) {
        if (activity == null) {
            XLog.w("入口卡片：拿不到 Activity，这一页挂不上")
            return
        }
        attach(activity, 0)
    }

    private fun attach(activity: Activity, attempt: Int) {
        runCatching {
            if (activity.isFinishing || activity.isDestroyed) {
                XLog.w("入口卡片：Activity 已结束（${activity.javaClass.simpleName}），放弃")
                return
            }
            val decor = activity.window?.decorView as? ViewGroup
            if (decor == null) {
                if (attempt >= MAX_ATTACH_ATTEMPTS) {
                    XLog.w("入口卡片：等 ${MAX_ATTACH_ATTEMPTS} 次窗口仍未建好（${activity.javaClass.simpleName}）")
                    return
                }
                if (attempt == 0) XLog.i("入口卡片：窗口还没建好（${activity.javaClass.simpleName}），稍后重试")
                main.postDelayed({ attach(activity, attempt + 1) }, ATTACH_RETRY_MS)
                return
            }
            // 判重时**必须把旧 View 认回来**（2026-10-07 真机教训）：
            // 强制停止输入法后，设置页进程还活着，decor 上仍留着上一版模块挂的卡片，
            // 但本进程的 cardRef 是空的。此时若只 `refresh(); return`，卡片就永远停留在
            // 旧状态 —— 表现为「切换方案不生效、checked 不变、留白/样式日志一条都没有」。
            // 所以这里从旧 View 上取回 body 重建引用，再刷新。
            val existing = decor.findViewWithTag<View>(TAG)
            if (existing != null) {
                val adopted = adoptExisting(existing, decor)
                if (adopted) {
                    refresh()
                    XLog.i("这一页已有入口胶囊，认回旧 View 并刷新（${activity.javaClass.simpleName}）")
                } else {
                    // 结构对不上（上一版那条平铺大卡片挂的）→ 拆掉重建，否则它会一直停在旧样子
                    XLog.i("这一页的旧卡片结构已过期，拆掉重建为浮动胶囊（${activity.javaClass.simpleName}）")
                    runCatching { decor.removeView(existing) }
                    attach(activity, attempt)
                }
                return
            }
            val card = buildCapsule(activity, decor)
            decor.post {
                runCatching {
                    if (card.root.parent == null && decor.findViewWithTag<View>(TAG) == null) {
                        card.root.tag = TAG
                        // 此刻页面已经渲染完，重新采一次样式（首次 buildCapsule 时 RN 还没量完）
                        card.style = sampleStyle(decor, activity)
                        applyCapsuleSkin(card.root, card.style)
                        fillCapsule(card.root, card.style)
                        decor.addView(card.root, capsuleLayoutParams(activity, card.style))
                        cardRef = WeakReference(card)
                        activityRef = WeakReference(activity)
                        XLog.i(
                            "已在设置页挂上「自定义码表」浮动胶囊（${activity.javaClass.simpleName}）：" +
                                "不占布局空间、不改动页面任何 View",
                        )
                        // RN 页面刚挂上时布局还没量完，稍后按它真实卡片的样式重建一次
                        main.postDelayed({ refresh() }, RESTYLE_DELAY_MS)
                    }
                }.onFailure { XLog.w("挂入口胶囊失败", it) }
            }
        }.onFailure { XLog.w("准备入口卡片失败", it) }
    }

    /**
     * 把 decor 上已有的胶囊 View 认回来，重建 [cardRef]。
     *
     * 认的是**上一个模块版本 / 上一个进程**挂上去的那个 View：只有结构真的是这一版
     * （胶囊里只有文字 + 可选的对勾，没有任何子 `LinearLayout`）才认；否则返回 false 让调用方拆掉重建。
     * 上一版那条平铺大卡片里有 `cardBox` → `body` 两层 `LinearLayout`，正好在这里被挡掉。
     */
    private fun adoptExisting(existing: View, decor: ViewGroup): Boolean {
        return runCatching {
            val root = existing as? LinearLayout ?: return@runCatching false
            if (root.childCount == 0 || root.childCount > 3) return@runCatching false
            for (i in 0 until root.childCount) {
                if (root.getChildAt(i) is LinearLayout) return@runCatching false
            }
            val context = root.context
            val style = sampleStyle(decor, context)
            cardRef = WeakReference(Card(root, WeakReference(decor), style))
            (context as? Activity)?.let { activityRef = WeakReference(it) }
            XLog.i("已认回页面上的入口胶囊（${root.childCount} 个子 View）")
            true
        }.onFailure { XLog.w("认回入口胶囊失败", it) }.getOrDefault(false)
    }

    /**
     * 把胶囊提到窗口最上层。
     *
     * 用在「自定义方案生效、整页被置灰层盖住」的时候：置灰层是后加的，
     * 会把先挂的胶囊压在下面，而那时胶囊是整页唯一的出路，必须提上来。
     */
    fun bringToFront() {
        val root = cardRef?.get()?.root ?: return
        if (root.parent == null) return
        runCatching { root.bringToFront() }
            .onFailure { XLog.w("把入口胶囊提到最上层失败", it) }
    }

    /**
     * 按宿主页面的真实样式重建胶囊内容（导入 / 切换 / 重命名 / 删除之后也调）。
     */
    fun refresh() {
        val card = cardRef?.get()
        if (card == null || card.root.parent == null) {
            // ⚠️ cardRef 拿不到 / 指向已被移除的旧 View 时（2026-10-07 真机反馈「checked 经常不动」）：
            // 从当前 Activity 的 decor 上重新认一次，认不到就什么都不做。
            // 之前直接 `?: return`，于是点了方案 Toast 弹了、checked 却不动。
            val activity = currentActivity() ?: return
            val decor = activity.window?.decorView as? ViewGroup ?: return
            val existing = decor.findViewWithTag<View>(TAG) ?: return
            if (!adoptExisting(existing, decor)) {
                XLog.w("刷新入口胶囊失败：页面上的 View 结构认不回来")
                return
            }
            refresh()
            return
        }
        val decor = card.decor.get()
        val context = card.root.context
        runCatching {
            val style = if (decor == null) fallbackStyle(context) else sampleStyle(decor, context)
            card.style = style
            applyCapsuleSkin(card.root, style)
            fillCapsule(card.root, style)
        }.onFailure { XLog.w("刷新入口胶囊失败", it) }
    }


    // ---------------------------------------------------------------- 胶囊骨架

    /**
     * 造那枚浮动胶囊的壳：一个横向 `LinearLayout`，底色（采样色）+ 全圆角 + 投影，
     * 宽随内容自适应。
     *
     * 只造壳，不掺内容 —— 内容由 [fillCapsule] 填、皮肤由 [applyCapsuleSkin] 上，
     * 刷新时只重填内容 / 重上皮肤，不用整枚重建。
     */
    private fun buildCapsule(context: Context, decor: ViewGroup): Card {
        val style = sampleStyle(decor, context)
        val root = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        applyCapsuleSkin(root, style)
        fillCapsule(root, style)
        return Card(root, WeakReference(decor), style)
    }

    /**
     * 给胶囊上皮肤：底色 / 圆角 / 投影 / 位置。
     *
     * 圆角恒为「高度的一半」，所以宿主的卡片圆角是多少都不影响 —— 出来的永远是标准胶囊形；
     * 底色与水波纹深浅仍跟着采样结果走（采样失败就是兜底值，见 [fallbackStyle]）。
     */
    private fun applyCapsuleSkin(root: LinearLayout, style: Style) {
        val context = root.context
        val height = dp(context, CAPSULE_HEIGHT_DP)
        val radius = height / 2f
        root.background = capsuleBackground(style, radius)
        root.clipToOutline = true
        root.outlineProvider = object : ViewOutlineProvider() {
            override fun getOutline(view: View, outline: Outline) {
                outline.setRoundRect(0, 0, view.width, view.height, radius)
            }
        }
        // 一层投影，让它看起来是「浮」在页面上的，而不是页面自己的一个控件
        root.elevation = dp(context, 4).toFloat()
        root.setPadding(dp(context, CAPSULE_PADDING_H_DP), 0, dp(context, CAPSULE_PADDING_H_DP), 0)
        root.layoutParams = capsuleLayoutParams(context, style)
    }

    /** 胶囊的圆角底 + 压水波纹；底色跟着采样结果走，圆角固定为胶囊形。 */
    private fun capsuleBackground(style: Style, radius: Float): Drawable {
        val box = GradientDrawable().apply {
            cornerRadius = radius
            setColor(style.cardColor)
        }
        val mask = GradientDrawable().apply {
            cornerRadius = radius
            setColor(0x00000000)
        }
        return RippleDrawable(ColorStateList.valueOf(rippleColor(style)), box, mask)
    }

    private fun rippleColor(style: Style): Int =
        if (isNight(style.cardColor)) 0x22FFFFFF else 0x14000000

    /**
     * 胶囊的位置：**窗口底部居中**，宽高都随内容（`WRAP_CONTENT`）。
     *
     * 敢用 `WRAP_CONTENT` 的依据：胶囊不占页面布局空间、也不需要页面让位，
     * 所以它自己多大都不会把页面内容顶出去 —— 上一版那条平铺卡片栽的正是这一点
     * （给页面加底部空白，页面又滚不动，内容被藏死）。
     */
    private fun capsuleLayoutParams(context: Context, style: Style): FrameLayout.LayoutParams =
        FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT,
            dp(context, CAPSULE_HEIGHT_DP),
        ).apply {
            gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
            leftMargin = style.marginH
            rightMargin = style.marginH
            bottomMargin = dp(context, CAPSULE_BOTTOM_DP)
        }

    // ---------------------------------------------------------------- 胶囊内容

    /**
     * 填胶囊里的内容 —— 一枚胶囊只放**一行**东西：宿主自己那套对勾 + 「自定义码表」+ 当前方案名。
     *
     * ```
     *   ✓  自定义码表 · 虎码字词      ← 有方案在生效
     *   自定义码表                    ← 还没导入过码表
     * ```
     *
     * 点它打开模块自己的整屏页面（切换 / 添加 / 重命名 / 删除 / 停用都在里面），长按直接进「添加方案…」。
     * 这里**不放**「停用自定义方案」：停用有两条路 —— 模块页面里的那一行，或在「五笔方案」里选内置方案
     * （自定义方案与内置方案自动互斥）。
     */
    private fun fillCapsule(root: LinearLayout, style: Style) {
        val context = root.context
        val schemes = runCatching { TableStore.list(context) }.getOrDefault(emptyList())
        val activeId = runCatching { TableStore.activeId(context) }.getOrDefault("")
        val active = schemes.firstOrNull { it.id == activeId }

        root.removeAllViews()
        root.isClickable = true
        root.isFocusable = true
        root.setOnClickListener { open(context) }
        root.setOnLongClickListener {
            TableManager.addScheme(context)
            true
        }

        if (active != null) {
            // 采样到的对勾尺寸是**宿主那一行**的尺寸，胶囊比行矮，得夹一下，
            // 否则图片勾会顶破胶囊（胶囊固定高 34dp）
            val ceiling = dp(context, CAPSULE_HEIGHT_DP) - dp(context, 12)
            val sampled = style.check.markSize
            root.addView(
                checkMark(context, style),
                LinearLayout.LayoutParams(
                    if (sampled > 0) sampled.coerceAtMost(ceiling) else ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                ).apply { rightMargin = dp(context, 6) },
            )
        }

        root.addView(
            TextView(context).apply {
                text = TITLE
                // 有方案时「自定义码表」只是前缀（小字、淡色），方案名才是主角；
                // 没方案时它就是唯一内容，用跟宿主行文字同款的字号 / 颜色。
                // 字号夹在 11~16sp：胶囊只有 34dp 高，宿主那些大字号（无障碍放大）会把它撑变形
                textSize = if (active != null) style.headerSize else style.rowSize.coerceIn(11f, 16f)
                setTextColor(if (active != null) style.headerColor else style.rowColor)
                typeface = if (active == null && style.rowBold) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
                maxLines = 1
                ellipsize = TextUtils.TruncateAt.END
            },
        )

        if (active != null) {
            root.addView(
                TextView(context).apply {
                    text = " · ${active.name}"
                    textSize = style.rowSize.coerceIn(11f, 16f)
                    setTextColor(style.rowColor)
                    typeface = if (style.rowBold) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
                    maxLines = 1
                    ellipsize = TextUtils.TruncateAt.END
                },
            )
        }
        XLog.i("入口胶囊内容：${active?.let { "正在用「${it.name}」" } ?: "还没有自定义方案（点它导入 txt 码表）"}")
    }

    /**
     * 选中标记：优先用宿主自己那张卡片里的画法（图片原样贴过来），采不到才退文本勾。
     * 画法由 [sampleCheck] 采样，跟内置列表里的对勾是同一套。
     */
    private fun checkMark(context: Context, style: Style): View {
        val check = style.check
        return check.drawable?.let { drawable ->
            ImageView(context).apply {
                setImageDrawable(drawable)
                scaleType = ImageView.ScaleType.CENTER_INSIDE
            }
        } ?: TextView(context).apply {
            text = "✓"
            setTextColor(if (check.tint != 0) check.tint else style.accent)
            textSize = check.textSize
            gravity = Gravity.CENTER
        }
    }

    // ---------------------------------------------------------------- 样式采样

    /**
     * 从当前页面上它自己那张卡片里取样式。
     *
     * 找页面里第一张「宽度过半屏 + 有圆角」的 ViewGroup 当模板，再从它内部取行文字的
     * 字号 / 字色 / 粗细 / 行高，以及强调色。任何一项取不到就用 [fallbackStyle] 的对应值。
     *
     * ⚠️ 圆角**不能只看 `GradientDrawable.cornerRadius`**（2026-10-07 真机教训）：
     * 微信设置页是 RN，卡片背景是 `ReactViewBackgroundDrawable`，压根不是 GradientDrawable，
     * 上一版因此一张都没采到、全在用兜底值。RN 会把 borderRadius 反映到
     * `outlineProvider` 上，所以圆角优先从 Outline 拿（`isRoundRect` + `radius`），
     * 拿不到再退回 GradientDrawable，最后才用兜底。
     */
    private fun sampleStyle(decor: ViewGroup, context: Context): Style {
        val fallback = fallbackStyle(context)
        val screenWidth = context.resources.displayMetrics.widthPixels
        val hostCard = findHostCard(decor, screenWidth, 0)

        var radius = fallback.radius
        var cardColor = fallback.cardColor
        var rowHeight = fallback.rowHeight
        var rowSize = fallback.rowSize
        var rowColor = fallback.rowColor
        var rowBold = fallback.rowBold
        var marginH = dp(context, 16)
        var check = CheckStyle()

        if (hostCard != null) {
            val r = radiusOf(hostCard)
            if (r > 0) radius = r.coerceIn(dp(context, 4), dp(context, 28))
            val color = solidColorOf(hostCard)
            if (color != 0) cardColor = color
            val text = findText(hostCard, 0)
            if (text != null) {
                // ⚠️ `getTextSize()` 返回的是**像素**，而 `setTextSize(float)` 收的是 **sp** ——
                // 直接搬会把字号放大 density 倍（2026-10-07 复查发现的隐患：此前采样一直失败、
                // 走的是 sp 的兜底值，所以没露出来；胶囊要用这个值排版，就地换算掉）。
                val density = context.resources.displayMetrics.scaledDensity
                if (text.textSize > 10f && density > 0f) rowSize = text.textSize / density
                val c = text.currentTextColor
                if (c != 0) rowColor = c
                rowBold = text.typeface != null && text.typeface.isBold
            }
            val line = hostCard.firstVisibleChild(0)
            if (line != null && line.height >= dp(context, 40) && line.height <= screenWidth) {
                rowHeight = line.height
            } else if (hostCard.height >= dp(context, 40) && hostCard.height <= screenWidth) {
                rowHeight = hostCard.height
            }
            // 左右边距：卡片相对它父容器的间距（用户要求与原始卡片一致）
            marginH = marginOf(hostCard, screenWidth)
            // 行内边距：卡片相对内容区的左右缩进
            val inner = innerPaddingOf(hostCard)
            if (inner != null) stylePaddingOverride = inner
            // 选中标记：直接把它自己那张卡片里的对勾搬过来
            check = sampleCheck(hostCard, rowSize, rowColor)
        }

        val accent = themeAccent(decor, context, fallback.accent)
        val style = Style(
            cardColor = cardColor,
            radius = radius,
            headerSize = 13f,
            headerColor = headerColorOf(rowColor),
            rowSize = rowSize,
            rowColor = rowColor,
            rowBold = rowBold,
            rowHeight = rowHeight,
            paddingH = stylePaddingOverride ?: dp(context, 20),
            accent = accent,
            marginH = marginH,
            check = check,
        )
        XLog.i(
            "入口卡片样式：${if (hostCard != null) "采样自 ${hostCard.javaClass.simpleName}" else "未采到卡片，用兜底样式"}" +
                " 圆角=${style.radius}px 底色=#${Integer.toHexString(style.cardColor)} " +
                "行高=${style.rowHeight}px 字号=${style.rowSize}sp 加粗=${style.rowBold} " +
                "左右边距=${style.marginH}px 行内边距=${style.paddingH}px " +
                "对勾=${if (style.check.drawable != null) "图片 ${style.check.markSize}px" else "文本 ${style.check.textSize}sp"} " +
                "强调色=#${Integer.toHexString(style.accent)}",
        )
        return style
    }

    /** 采样到的行内边距（在 sampleStyle 里写；胶囊的左右内边距用的是它自己的常量）。 */
    private var stylePaddingOverride: Int? = null

    /** 卡片相对父容器的左右外边距；取不到就用「(屏宽 - 卡片宽) / 2」。 */
    private fun marginOf(card: View, screenWidth: Int): Int {
        val lp = card.layoutParams as? android.view.ViewGroup.MarginLayoutParams
        if (lp != null && (lp.leftMargin > 0 || lp.rightMargin > 0)) {
            return if (lp.leftMargin == lp.rightMargin) lp.leftMargin else maxOf(lp.leftMargin, lp.rightMargin)
        }
        // RN 用 padding 而不是 margin 表达外边距，两者都试
        val parent = card.parent as? View
        if (parent != null && card.left > 0) {
            val byPosition = card.left
            val byPadding = parent.paddingLeft
            return if (byPosition > 0) byPosition else byPadding
        }
        val byWidth = (screenWidth - card.width) / 2
        return if (byWidth > 0) byWidth else dp(card.context, 16)
    }

    /** 卡片内部那一层相对卡片本身的左右缩进（行文字离卡片边缘多远）。 */
    private fun innerPaddingOf(card: View): Int? {
        if (card.paddingLeft > 0 && card.paddingLeft == card.paddingRight) return card.paddingLeft
        val child = (card as? ViewGroup)?.firstVisibleChild(0) ?: return null
        val byPosition = child.left
        val byPadding = card.paddingLeft
        return if (byPosition > 0) byPosition else if (byPadding > 0) byPadding else null
    }

    /**
     * 采它自己那张卡片里的「选中标记」。
     *
     * 两种可能：图片（`ImageView` 系，RN 的勾多半是这种）或文本勾（`✓` / `✔`）。
     * 采到就照抄尺寸 / 图片 / 颜色，一个文本 `✓` 硬凑是用户抱怨「checked 样式不一致」的根因。
     */
    private fun sampleCheck(card: View, rowSize: Float, rowColor: Int): CheckStyle {
        findHostCheck(card, 0)?.let { host ->
            val drawable = when (host) {
                is ImageView -> host.drawable
                else -> host.background
            }
            if (drawable != null && drawable.intrinsicWidth > 0) {
                return CheckStyle(
                    drawable = drawable.constantState?.newDrawable()?.mutate() ?: drawable,
                    markSize = if (host.width > 0) host.width else drawable.intrinsicWidth,
                    textSize = rowSize + 3f,
                    tint = 0,
                )
            }
            // 文本勾：记下它的字号与颜色，我们用同样的画法
            if (host is TextView && !host.text.isNullOrBlank()) {
                // 同样要换算单位：`getTextSize()` 是 px，而我们要把它当 sp 用
                val density = host.resources.displayMetrics.scaledDensity
                val sp = if (density > 0f) host.textSize / density else 0f
                return CheckStyle(
                    drawable = null,
                    markSize = 0,
                    textSize = sp.takeIf { it > 6f } ?: (rowSize + 3f),
                    tint = host.currentTextColor.takeIf { it != 0 } ?: rowColor,
                )
            }
        }
        return CheckStyle(drawable = null, markSize = 0, textSize = rowSize + 3f, tint = 0)
    }
    /** 卡片内部「长得像选中标记」的控件：小的、有图的，或文本就是勾。 */
    private fun findHostCheck(view: View, depth: Int): View? {
        if (depth > 8) return null
        if (view is ImageView && view.drawable != null && view.drawable.intrinsicWidth > 0) return view
        if (view is TextView) {
            val text = view.text?.toString()?.trim().orEmpty()
            if (text.isNotEmpty() && (text == "✓" || text == "✔" || text == "√")) return view
        }
        val group = view as? ViewGroup ?: return null
        // 右侧的小控件优先（选中标记都在右边）
        if (group.width > 0 && group.width <= dp(view.context, 48)) {
            for (i in group.childCount - 1 downTo 0) {
                val hit = findHostCheck(group.getChildAt(i), depth + 1)
                if (hit != null) return hit
            }
        }
        for (i in 0 until group.childCount) {
            val hit = findHostCheck(group.getChildAt(i), depth + 1)
            if (hit != null) return hit
        }
        return null
    }

    /** 兜底样式：数值照真机截图量 —— 16dp 圆角、62dp 行高、16sp 加粗、微信绿、深色一套。 */
    private fun fallbackStyle(context: Context): Style {
        val night = context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK ==
            Configuration.UI_MODE_NIGHT_YES
        return Style(
            cardColor = if (night) 0xFF2A2D33.toInt() else 0xFFFFFFFF.toInt(),
            radius = dp(context, 16),
            headerSize = 13f,
            headerColor = if (night) 0xFF9AA0A6.toInt() else 0xFF8C8C8C.toInt(),
            rowSize = 16f,
            rowColor = if (night) 0xFFF2F2F2.toInt() else 0xFF191919.toInt(),
            rowBold = true,
            rowHeight = dp(context, 62),
            paddingH = dp(context, 20),
            accent = WECHAT_GREEN,
            marginH = dp(context, 16),
            check = CheckStyle(),
        )
    }

    /**
     * 深度优先找第一张「够宽 + 有圆角」的容器（跳过我们自己挂上去的那张）。
     *
     * ⚠️ 判据不能只认 `GradientDrawable` 或 outline（2026-10-07 真机连续两版都采不到）：
     * RN 的 `ReactViewBackgroundDrawable` 既不是 GradientDrawable，也不一定设 outlineProvider，
     * 圆角只存在于它自己的 `getBorderRadius()` 里。三条都试，任一成立就算命中。
     */
    private fun findHostCard(view: View, screenWidth: Int, depth: Int): ViewGroup? {
        if (depth > 16 || view.tag == TAG) return null
        if (view is ViewGroup && view !== view.rootView) {
            val wide = view.width >= screenWidth * 0.55f && view.width > 0
            if (wide && looksLikeCard(view)) return view
            for (i in 0 until view.childCount) {
                val hit = findHostCard(view.getChildAt(i), screenWidth, depth + 1)
                if (hit != null) return hit
            }
        }
        return null
    }

    /** 「长得像它那些卡片」：有圆角，且底色不是透明的。 */
    private fun looksLikeCard(view: View): Boolean {
        val radius = radiusOf(view)
        if (radius <= 0) return false
        val color = solidColorOf(view)
        return color == 0 || (color ushr 24) != 0
    }

    /**
     * 圆角半径（px）：先问 `outlineProvider`（RN 的 borderRadius 就反映在这儿），
     * 再退回 GradientDrawable。`Outline.getRadius()` 要 API 23，反射拿。
     */
    /**
     * 圆角半径（px），三条路依次试：
     *  1. RN drawable 的 `getBorderRadius()` —— **真机上唯一管用的一条**。
     *     ⚠️ 它返回的是 `float[8]`（每角 横半径/竖半径 各 4 个），不是对象、也不是单个 float；
     *     之前按「Float 字段 / 返回 float 的 getter」去取，全都拿不到（2026-10-07 连续三版都栽在这）。
     *     取 `max(所有值)` 作为圆角；数组拿不到再退 `getRadius()`。
     *  2. `outlineProvider`（普通 Android 圆角背景会反映在这儿）。
     *  3. `GradientDrawable.cornerRadius`。
     */
    private fun radiusOf(view: View): Int {
        val fromBackground = runCatching {
            val bg = view.background ?: return@runCatching 0
            val m = bg.javaClass.methods.firstOrNull {
                it.name == "getBorderRadius" && it.parameterTypes.isEmpty()
            } ?: return@runCatching 0
            when (val radii = m.invoke(bg)) {
                // float[8]：四角各有横/竖半径，取最大那个
                is FloatArray -> radii.maxOrNull()?.toInt() ?: 0
                is IntArray -> radii.maxOrNull() ?: 0
                is Array<*> -> radii.filterIsInstance<Number>().maxOfOrNull { it.toInt() } ?: 0
                is Number -> radii.toInt()
                // 少数版本包成对象：递归问它的 float/int 字段或无参 getter
                else -> numberOf(radii)
            }
        }.getOrDefault(0)
        if (fromBackground > 0) return fromBackground

        // 2) outlineProvider（Outline.canClip()/getRadius() 是 API 23+，项目 compileSdk 低，反射拿）
        val fromOutline = runCatching {
            val outline = Outline()
            view.outlineProvider?.getOutline(view, outline)
            val canClip = Outline::class.java.getMethod("canClip").invoke(outline) as? Boolean ?: false
            if (canClip) Outline::class.java.getMethod("getRadius").invoke(outline) as? Int ?: 0 else 0
        }.getOrDefault(0)
        if (fromOutline > 0) return fromOutline

        // 3) GradientDrawable
        return view.background?.unwrapGradient()?.cornerRadius?.toInt() ?: 0
    }

    /** 从任意对象里挖出一个数值：先扫数值字段，再扫无参数值 getter。 */
    private fun numberOf(target: Any?): Int {
        if (target == null) return 0
        if (target is Number) return target.toInt()
        runCatching {
            target.javaClass.declaredFields.forEach { f ->
                f.isAccessible = true
                val v = runCatching { f.get(target) }.getOrNull()
                if (v is Number) return v.toInt()
            }
        }
        runCatching {
            target.javaClass.methods.forEach { m ->
                if (m.parameterTypes.isEmpty() && m.name.startsWith("get")) {
                    val v = runCatching { m.invoke(target) }.getOrNull()
                    if (v is Number) return v.toInt()
                }
            }
        }
        return 0
    }

    /**
     * 卡片底色。
     *
     * RN 的 drawable 没有 `getSolidColor()`（设备包 dex 里查无此名），它用的是
     * `getColor()`（`ColorDrawable` 体系的读法）；`getSolidColor` 也一并试，谁先给非 0 用谁。
     */
    private fun solidColorOf(view: View): Int {
        val background = view.background ?: return 0
        for (name in listOf("getColor", "getSolidColor", "getBackgroundColor")) {
            val value = runCatching {
                background.javaClass.methods
                    .firstOrNull { it.name == name && it.parameterTypes.isEmpty() }
                    ?.invoke(background) as? Int ?: 0
            }.getOrDefault(0)
            if (value != 0) return value
        }
        val shape = background.unwrapGradient() ?: return 0
        return shapeColor(shape)
    }

    /** 取容器里第一个有文字的 TextView（RN 的 `ReactTextView` 也是 TextView）。 */
    private fun findText(view: View, depth: Int): TextView? {
        if (depth > 12 || view.tag == TAG) return null
        if (view is TextView && !view.text.isNullOrBlank()) return view
        if (view is ViewGroup) {
            for (i in 0 until view.childCount) {
                val hit = findText(view.getChildAt(i), depth + 1)
                if (hit != null) return hit
            }
        }
        return null
    }

    /** 容器里第一个量过尺寸、且宽度接近容器的直接子 View —— 那就是它的一行。 */
    private fun ViewGroup.firstVisibleChild(depth: Int): View? {
        if (depth > 4) return null
        for (i in 0 until childCount) {
            val child = getChildAt(i)
            if (child.width > 0 && child.height > 0 && child.tag != TAG) return child
            if (child is ViewGroup) {
                val hit = child.firstVisibleChild(depth + 1)
                if (hit != null) return hit
            }
        }
        return null
    }

    /** 小标题颜色：跟行文字同色系但更淡一档。 */
    private fun headerColorOf(rowColor: Int): Int {
        val night = isNight(rowColor)
        return if (night) 0xFF9AA0A6.toInt() else 0xFF8C8C8C.toInt()
    }
    private fun isNight(cardColor: Int): Boolean {
        val r = (cardColor shr 16) and 0xFF
        val g = (cardColor shr 8) and 0xFF
        val b = cardColor and 0xFF
        return (r * 30 + g * 59 + b * 11) / 100 < 128
    }

    /**
     * 取主题强调色。
     *
     * 优先看页面上真实控件的颜色（那个绿色开关 / 对勾就是它自己的强调色），
     * 拿不到再退回主题属性，最后退回写死的兜底绿。
     */
    private fun themeAccent(decor: ViewGroup, context: Context, fallback: Int): Int {
        fromHostView(decor, 0)?.let { return it }
        val theme = (context as? ContextThemeWrapper)?.theme ?: return fallback
        val typed = TypedValue()
        // 老 API，跟 compileSdk 无关：直接从主题里读 colorAccent 的颜色值
        if (!theme.resolveAttribute(android.R.attr.colorAccent, typed, true)) return fallback
        return if (typed.type >= TypedValue.TYPE_FIRST_COLOR_INT &&
            typed.type <= TypedValue.TYPE_LAST_COLOR_INT
        ) {
            typed.data
        } else {
            fallback
        }
    }

    /** 扫页面上所有 TextView，找「纯绿/纯蓝」这类高饱和强调色（开关、对勾、选中态）。 */
    private fun fromHostView(view: View, depth: Int): Int? {
        if (depth > 14 || view.tag == TAG) return null
        if (view is TextView && !view.text.isNullOrBlank()) {
            val c = view.currentTextColor
            if (isAccentColor(c)) return c
        }
        // 「五笔设置」那三条右侧开关的 thumb 就是它自己的强调色，比扫文字靠谱
        // （thumbTintList 的类型随 SDK 变，这里反射读，compileSdk 低也能编过）
        if (view is CompoundButton && view.isChecked) {
            val thumb = runCatching {
                val list = view.javaClass.getMethod("getThumbTintList").invoke(view)
                @Suppress("UNCHECKED_CAST")
                (list as? android.content.res.ColorStateList)?.defaultColor ?: 0
            }.getOrDefault(0)
            if (isAccentColor(thumb)) return thumb
        }
        if (view is ViewGroup) {
            for (i in 0 until view.childCount) {
                fromHostView(view.getChildAt(i), depth + 1)?.let { return it }
            }
        }
        return null
    }

    /** 绿色系高饱和、且不是纯黑白的颜色才算强调色。 */
    private fun isAccentColor(color: Int): Boolean {
        val r = (color shr 16) and 0xFF
        val g = (color shr 8) and 0xFF
        val b = color and 0xFF
        val max = maxOf(r, g, b)
        val min = minOf(r, g, b)
        if (max < 90 || max - min < 40) return false
        return g >= r && g >= b
    }

    private fun Drawable.unwrapGradient(): GradientDrawable? = when (this) {
        is GradientDrawable -> this
        is RippleDrawable -> getDrawable(0)?.unwrapGradient()
        else -> null
    }

    private fun shapeColor(shape: GradientDrawable): Int =
        runCatching {
            @Suppress("DEPRECATION")
            shape.color?.defaultColor ?: 0
        }.getOrDefault(0)

    // ---------------------------------------------------------------- 方案管理

    /**
     * 点胶囊 = 打开**模块自己的整屏页面**（[ModulePage]）。
     *
     * 2026-10-07 用户要求：码表管理不再借宿主的对话框，改成「用自己的入口开一个新页面，
     * 完全由模块控制」—— 这一枚胶囊就是那个入口（它固定在窗口底部，宿主页面一个像素都不用让）。
     */
    private fun open(context: Context) {
        ModulePage.open(context)
    }

    private fun dp(context: Context, value: Int): Int =
        (value * context.resources.displayMetrics.density).toInt()
}
