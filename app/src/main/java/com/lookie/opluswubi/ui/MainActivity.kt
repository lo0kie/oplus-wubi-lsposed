package com.lookie.opluswubi.ui

import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import androidx.core.graphics.drawable.toBitmap
import androidx.core.view.WindowCompat
import com.lookie.opluswubi.R
import top.yukonga.miuix.kmp.basic.BasicComponent
import top.yukonga.miuix.kmp.basic.BasicComponentColors
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.SmallTitle
import top.yukonga.miuix.kmp.extra.SuperSwitch
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.icons.basic.ArrowRight
import top.yukonga.miuix.kmp.theme.ColorSchemeMode
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.theme.ThemeController

/**
 * 模块自己的界面（Compose + Miuix，MIUI/HyperOS 那套观感）。
 *
 * ## 边界（很重要）
 *
 * 这个 Activity 跑在**模块自己的进程**（`com.lookie.opluswubi`）里，用的是本 APK 自带的
 * androidx / Compose / Miuix —— 没问题。**注入进宿主进程的代码**（`ime/` 下所有文件）依然
 * 一个 androidx 类都不许引用：那边没有这些实现（AGENTS.md 绝对红线 1 的适用边界）。
 * 判断标准只有一条：**这个类会不会被注入侧引用**。`ui/` 下的都只在模块进程里跑。
 *
 * ## 界面
 *
 *  - 每一行是「真实应用图标 + 名称 + 注入位置」，**点整行**跳到那个输入法里
 *    模块注入条目所在的设置页，**不弹 toast**（跳没跳成看页面就知道，弹一下反而糊在最上面）；
 *  - 行里只显示「未安装」这一件事，别的状态一律不显示 —— 早先那版逐行标「已生效 / 未生效」
 *    是**问出来的**（靠广播探活性），输入法进程没跑就显示未生效，太容易误读；
 *  - 顶栏右上角一个刷新图标 = **强停全部已安装的输入法**（让它们重新加载模块）——
 *    走 root，一条 `su -c am force-stop` 搞定（见 [ModuleConsole.restartAll]）；
 *    模块有没有启用改看标题右上角的「未启用」小上标，那条用 **libxposed 官方 API**（见 [LsposedBridge]）；
 *  - 桌面图标可隐藏（开关 alias 组件）。
 */
class MainActivity : ComponentActivity() {

    private var rows by mutableStateOf<List<Row>>(emptyList())
    private var launcherHidden by mutableStateOf(false)

    /**
     * 主线程 Handler。
     *
     * 界面上的动作（`su` 强停 / root 起设置页）都是**阻塞**的，只能在后台线程做完再回主线程弹 toast。
     */
    private val main = Handler(Looper.getMainLooper())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // 模块启用状态：注册框架服务监听（幂等，状态在 [LsposedBridge] 里、进程级）
        LsposedBridge.ensureRegistered(main)
        setContent {
            val dark = isSystemInDarkTheme()
            // 状态栏 / 导航栏图标跟着这一页的深浅色反色。页面底色是 Miuix 的 surface，
            // 而顶栏就在状态栏底下（ConsoleHeader 自己加了 statusBarsPadding）；
            // 不设的话图标颜色是平台主题的默认值，浅色页面上会是白字、看不见。
            // 只能在这里设：Miuix 的深浅由 Compose 侧决定，跟资源限定符是两套来源。
            LaunchedEffect(dark) { applyBarAppearance(dark) }
            MiuixTheme(controller = remember { ThemeController(ColorSchemeMode.System) }) {
                ConsoleScreen(
                    rows = rows,
                    version = ModuleConsole.moduleVersion(this),
                    buildKind = ModuleConsole.buildKind(),
                    launcherHidden = launcherHidden,
                    moduleOn = LsposedBridge.enabled,
                    onRestartAll = {
                        // 只做「强停」这一件事：不刷新、不改任何状态。
                        // `su` 是阻塞的（首次还会弹授权框），放后台线程，结果回主线程再 toast
                        Thread {
                            val msg = ModuleConsole.restartAll(this)
                            main.post { toast(msg) }
                        }.start()
                    },
                    onToggleLauncher = { hide ->
                        if (ModuleConsole.setLauncherHidden(this, hide)) {
                            launcherHidden = ModuleConsole.launcherHidden(this)
                            toast(if (hide) "桌面图标已隐藏" else "桌面图标已恢复")
                        } else {
                            toast("系统不允许改桌面图标")
                        }
                    },
                    onRefresh = { refresh() },
                )
            }
        }
    }

    /** 状态栏 / 导航栏图标：浅色页面要深图标，深色页面要浅图标。 */
    private fun applyBarAppearance(dark: Boolean) {
        runCatching {
            val c = WindowCompat.getInsetsController(window, window.decorView)
            c.isAppearanceLightStatusBars = !dark
            c.isAppearanceLightNavigationBars = !dark
        }
    }

    override fun onResume() {
        super.onResume()
        launcherHidden = ModuleConsole.launcherHidden(this)
        refresh()
    }

    private fun toast(msg: String) {
        runCatching { Toast.makeText(this, msg, Toast.LENGTH_SHORT).show() }
    }

    /** 界面的一行：某个输入法。 */
    data class Row(
        val ime: ModuleConsole.Ime,
        val installed: Boolean,
    )

    /** 重画列表（只跟「装没装」有关 —— 那是本地就能查的，不需要问任何人）。 */
    private fun refresh() {
        rows = collectRows()
    }

    private fun collectRows(): List<Row> = ModuleConsole.IMES.map { ime ->
        Row(
            ime = ime,
            installed = ModuleConsole.installed(this, ime.pkg),
        )
    }
}

@Composable
private fun ConsoleScreen(
    rows: List<MainActivity.Row>,
    version: String,
    buildKind: String,
    launcherHidden: Boolean,
    moduleOn: Boolean,
    onRestartAll: () -> Unit,
    onToggleLauncher: (Boolean) -> Unit,
    onRefresh: () -> Unit,
) {
    // 模块没启用时，整页（含分类标题、关于那一行）一起降亮度 —— 只灰控件、标题还亮着会很割裂
    val sectionColor = if (moduleOn) {
        MiuixTheme.colorScheme.onBackgroundVariant
    } else {
        MiuixTheme.colorScheme.disabledOnSurface
    }
    Column(modifier = Modifier.fillMaxSize()) {
        ConsoleHeader(moduleOn = moduleOn, onRestartAll = onRestartAll)
        Column(
            modifier = Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 12.dp),
        ) {
            Spacer(Modifier.height(8.dp))
            SmallTitle("输入法", textColor = sectionColor)
            Card {
                rows.forEach { row ->
                    ImeRow(
                        row = row,
                        enabled = moduleOn,
                        onRefresh = onRefresh,
                    )
                }
            }

            Spacer(Modifier.height(8.dp))
            SmallTitle("桌面图标", textColor = sectionColor)
            Card {
                SuperSwitch(
                    title = "隐藏桌面图标",
                    checked = launcherHidden,
                    enabled = moduleOn,
                    onCheckedChange = onToggleLauncher,
                )
            }

            // 版本放最底下，和上面两个板块一样带分类标题 —— 后面还会往里加仓库等信息
            Spacer(Modifier.height(8.dp))
            SmallTitle("关于", textColor = sectionColor)
            Card {
                // 子标题给「版本号 + debug/release」：两个包都装着的时候，光看版本号分不出手上是哪个
                BasicComponent(
                    title = "版本",
                    summary = "$version · $buildKind",
                    titleColor = BasicComponentColors(color = sectionColor, disabledColor = sectionColor),
                    summaryColor = BasicComponentColors(color = sectionColor, disabledColor = sectionColor),
                )
            }
            Spacer(Modifier.height(24.dp))
        }
    }
}

/**
 * 顶栏。
 *
 * 不用 Miuix 的 `SmallTopAppBar`：它的标题是**居中**的（MIUI 那套观感），
 * 而这个组件的参数里没有能改标题对齐方式的口子（`SmallTopAppBar` 只有 title / navigationIcon /
 * actions / padding 这些），所以自己拼一个 —— 用同一套 token（`surface` 底色、50dp 高度、
 * 26dp 起始边距）就能跟 Miuix 原生顶栏长得一样。
 *
 * 标题字号是 `title1`（32sp，Miuix 里最大的一档；`title2/3/4` 分别是 24 / 20 / 18sp），
 * 所以顶栏也相应加高到 64dp（Miuix 原生 SmallTopAppBar 是 50dp）。
 * 高度用 `heightIn(min = …)` 而不是写死 —— 系统字体放大时才不会裁字。
 *
 * 模块没启用时，标题右上角挂一个「未启用」小上标（`footnote2` + error 色），
 * 并且这一页的控件全部置灰 —— 那种状态下点了也不会有效果。
 */
@Composable
private fun ConsoleHeader(moduleOn: Boolean, onRestartAll: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(MiuixTheme.colorScheme.surface)
            .statusBarsPadding()
            .heightIn(min = 64.dp)
            .padding(start = 26.dp, end = 8.dp, top = 32.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(modifier = Modifier.weight(1f), verticalAlignment = Alignment.Top) {
            Text(
                text = "输入法增强",
                style = MiuixTheme.textStyles.title1,
                color = MiuixTheme.colorScheme.onSurface,
            )
            if (!moduleOn) {
                Spacer(Modifier.width(6.dp))
                // 上标：贴着标题顶部，字号小一档
                Text(
                    text = "未启用",
                    style = MiuixTheme.textStyles.footnote2,
                    color = MiuixTheme.colorScheme.error,
                    modifier = Modifier.padding(top = 6.dp),
                )
            }
        }
        // ⚠️ 强停按钮**任何时候都能点**：它正是「未启用」时唯一的自救动作 ——
        // LSPosed 只在进程启动时注入模块，装完包 / 刚启用模块之后，必须重启输入法才会生效。
        // 之前把它一起灰掉，用户看到「未启用」时连重启的入口都没有了。
        IconButton(onClick = onRestartAll) {
            Icon(
                painter = painterResource(R.drawable.ic_refresh),
                contentDescription = "重启全部输入法",
                tint = MiuixTheme.colorScheme.onSurface,
            )
        }
    }
}

@Composable
private fun ImeRow(
    row: MainActivity.Row,
    enabled: Boolean,
    onRefresh: () -> Unit,
) {
    val ctx = LocalContext.current
    val main = remember { Handler(Looper.getMainLooper()) }

    // 真实的应用图标（没装就没有）
    val icon = remember(row.ime.pkg, row.installed) {
        if (!row.installed) {
            null
        } else {
            runCatching {
                ctx.packageManager.getApplicationIcon(row.ime.pkg)
                    .toBitmap(96, 96)
                    .squareByAlpha()
                    .asImageBitmap()
            }.getOrNull()
        }
    }

    // 行里只留「装没装」这一件事 —— 模块挂没挂上交给顶栏那一个总指示（见类头注释）
    val status = if (row.installed) null else "未安装"
    // 模块没启用时整行跟着降亮度：`BasicComponent` 的 enabled 只管它自己画的那部分，
    // 这里是自定义内容（标题/摘要/状态/箭头都是我们自己画的），得自己按 enabled 挑颜色
    val dim = MiuixTheme.colorScheme.disabledOnSurface
    val titleColor = if (enabled) MiuixTheme.colorScheme.onBackground else dim
    val summaryColor = if (enabled) MiuixTheme.colorScheme.onSurfaceVariantSummary else dim
    val statusColor = if (enabled) MiuixTheme.colorScheme.onSurfaceVariantSummary else dim

    BasicComponent(
        // 图标右边留 16dp（Miuix 自己的标准间距），跟标题拉开
        leftAction = {
            icon?.let {
                Box(modifier = Modifier.padding(end = 16.dp)) {
                    Image(
                        bitmap = it,
                        contentDescription = null,
                        // Fit + 上面裁成的正方形：四家图标都正好铺满这 40dp，不会被拉变形
                        contentScale = ContentScale.Fit,
                        modifier = Modifier
                            .size(40.dp)
                            .clip(RoundedCornerShape(11.dp)),
                    )
                }
            }
        },
        // 行尾只留一个右箭头
        rightActions = {
            Icon(
                imageVector = MiuixIcons.Basic.ArrowRight,
                contentDescription = null,
                tint = if (enabled) {
                    MiuixTheme.colorScheme.onSurfaceVariantActions
                } else {
                    dim
                },
                modifier = Modifier.size(width = 10.dp, height = 16.dp),
            )
        },
        onClick = {
            if (!enabled) return@BasicComponent
            // `su am start` 是阻塞的，放后台线程。成败都只进日志（不再弹 toast）
            Thread {
                ModuleConsole.openSettings(ctx, row.ime)
                main.post { onRefresh() }
            }.start()
        },
        enabled = enabled,
    ) {
        Row(verticalAlignment = Alignment.Top) {
            Text(
                text = row.ime.name,
                style = MiuixTheme.textStyles.headline1,
                color = titleColor,
            )
            status?.let {
                Spacer(Modifier.width(8.dp))
                Text(
                    text = it,
                    style = MiuixTheme.textStyles.footnote1,
                    color = statusColor,
                )
            }
        }
        Text(
            text = row.ime.where,
            style = MiuixTheme.textStyles.body2,
            color = summaryColor,
        )
    }
}

/**
 * 把应用图标裁成「不含透明边、且正方形」的一张。
 *
 * 为什么要裁：`getApplicationIcon` 各家给的东西不一样 —— 小布返回的是自带一圈透明内边距的位图
 * （真机量出来可见内容只有 104×106 px），另外三家是 140×140 px 铺满。都塞进同一个 40dp 的框里，
 * 小布那个就明显小一圈。裁掉透明边、再按长边补成正方形，四个图标才一样大。
 *
 * 顺带把边长压到 192px 以内：`Drawable.toBitmap(96, 96)` 遇到 `BitmapDrawable` 会**原样返回**
 * 它自己那张位图（宽高参数被忽略），不压一下可能拿着一张几百像素的图去画 40dp。
 */
private fun android.graphics.Bitmap.squareByAlpha(): android.graphics.Bitmap = runCatching {
    val w = width
    val h = height
    val pixels = IntArray(w * h)
    getPixels(pixels, 0, w, 0, 0, w, h)

    var left = w
    var top = h
    var right = -1
    var bottom = -1
    for (y in 0 until h) {
        val row = y * w
        for (x in 0 until w) {
            // 只认「基本不透明」的像素，免得抗锯齿的半透明边缘把框撑大
            if (pixels[row + x] ushr 24 > 8) {
                if (x < left) left = x
                if (x > right) right = x
                if (y < top) top = y
                if (y > bottom) bottom = y
            }
        }
    }
    if (right < left || bottom < top) return@runCatching this // 整张全透明，原样返回

    // 以可见内容的中心为心，按长边补成正方形（越界就夹回原位图内）
    val side = maxOf(right - left + 1, bottom - top + 1)
    val cx = (left + right + 1) / 2
    val cy = (top + bottom + 1) / 2
    val x0 = (cx - side / 2).coerceIn(0, maxOf(0, w - side))
    val y0 = (cy - side / 2).coerceIn(0, maxOf(0, h - side))
    val sw = minOf(side, w - x0)
    val sh = minOf(side, h - y0)

    val cropped = if (x0 == 0 && y0 == 0 && sw == w && sh == h) {
        this
    } else {
        android.graphics.Bitmap.createBitmap(this, x0, y0, sw, sh)
    }
    if (sw > 192) {
        android.graphics.Bitmap.createScaledBitmap(cropped, 192, 192, true)
    } else {
        cropped
    }
}.getOrDefault(this)
