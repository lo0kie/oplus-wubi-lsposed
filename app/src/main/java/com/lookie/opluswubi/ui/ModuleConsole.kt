package com.lookie.opluswubi.ui

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import com.lookie.opluswubi.DEBUG_BUILD
import com.lookie.opluswubi.XLog
import com.lookie.opluswubi.table.ModuleConfig
import com.lookie.opluswubi.table.ModuleSettingsProvider
import java.util.Locale

/**
 * 模块自己那个界面（[MainActivity]）要用到的全部数据与动作。
 *
 * 这个类**只跑在模块自己的进程里**（`com.lookie.opluswubi`），跟注入侧没有关系，
 * 所以可以随便用 framework / androidx 的东西。
 *
 * 两类动作对应界面上的两件事，**都走 root**（LSPosed 模块本就装在 root 环境里）：
 *  1. [openSettings] —— 跳到「模块注入了条目」的那个设置页（没导出的页用 `am start --user current`）；
 *  2. [restartAll]   —— 强停全部已安装输入法（模块装完 / 改完必须重启输入法才加载新代码）。
 *
 * 两条都不再经过「广播 + 注入侧代劳」那套：那套依赖注入侧在宿主进程里注册应答器，
 * 进程没起就没人接，行为会随进程起落而抖。
 */
object ModuleConsole {

    /** 一个受支持的输入法。 */
    class Ime(
        val name: String,
        val pkg: String,
        /**
         * 输入法自己声明的深链 action（非 root，最准）。目前只有小布有。
         */
        val action: String?,
        /**
         * 模块注入条目所在那一页的组件类名（**在宿主包内**）。
         *
         * 这些页在 manifest 里都是 `exported=false`，普通 `startActivity` 会被 Permission Denial 挡掉；
         * 走 root 的 `am start --user current -n <pkg>/<cls>` 才起得来（见 [openSettings] 第 ② 档）。
         */
        val pageClass: String? = null,
        /**
         * 或者一个 URI 深链（百度走它自己的 `bdinput://` scheme）。同样交给注入侧发，
         * 免得系统弹「输入法增强 想要打开 百度输入法」的确认框。
         */
        val pageUri: String? = null,
        /**
         * 导出的设置页 Activity（模块没挂上时的降级目标，不需要 root）。
         */
        val settings: String?,
        /** 界面上那句「注入在哪」的说明。 */
        val where: String,
    )

    /**
     * 按**拼音**给输入法排序。
     *
     * 用 `Collator`（ICU）而不是手写拼音表：`zh` 的默认排序规则就是拼音序，
     * 以后加新输入法不用再补一条。
     */
    private val PINYIN = java.text.Collator.getInstance(Locale.CHINA).apply {
        strength = java.text.Collator.PRIMARY
    }

    val IMES: List<Ime> = listOf(
        // 小布的设置页声明了一堆 action，`setting_input_wubi` 正好是「五笔设置」那一页
        //（模块的方案列表 / 自定义方案设置条目就在它里面），实测能直达。
        Ime(
            name = "小布输入法",
            pkg = "com.oplus.keyboard",
            action = "com.oplus.keyboard.action.setting_input_wubi",
            settings = "com.oplus.keyboard.settings.SettingsActivity",
            where = "五笔设置",
        ),
        // 搜狗的五笔页是 `WubiSettings`，manifest 里 `exported=false` → 交给注入侧起
        //（`SogouIMESettings` 是 Kuikly 画的设置首页，进不去五笔那一页）。
        Ime(
            name = "搜狗输入法",
            pkg = "com.sohu.inputmethod.sogou",
            action = null,
            pageClass = "com.sogou.imskit.feature.settings.activity.WubiSettings",
            settings = "com.sohu.inputmethod.sogou.SogouIMESettings",
            where = "五笔设置",
        ),
        // 百度：它的「五笔设置」是设置页里的一层 PreferenceScreen（没有独立 Activity），
        // 能到的最深就是设置页本身 —— 走它自己的 scheme。
        // ⚠️ 别用 `com.baidu.input.ImeFrontSettingActivity`：那是键盘上「前置设置」快捷面板的代理，
        // `onCreate` 里第 26 行直接 `finish()`（真机 dumpsys 实测：起了就退，界面上什么都没发生）。
        // 真正的设置页是 `ImeMainConfigActivity`（没导出、还要带 extras 才画得对），所以走 scheme。
        Ime(
            name = "百度输入法",
            pkg = "com.baidu.input",
            action = null,
            pageUri = "bdinput://openinputsettings",
            settings = null,
            where = "五笔设置",
        ),
    ).sortedWith { a, b -> PINYIN.compare(a.name, b.name) }

    // ---------------------------------------------------------------- 状态

    fun installed(context: Context, pkg: String): Boolean = runCatching {
        context.packageManager.getPackageInfo(pkg, 0)
        true
    }.getOrDefault(false)

    fun moduleVersion(context: Context): String = runCatching {
        context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: "?"
    }.getOrDefault("?")

    /**
     * 构建类型：`debug` / `release`。
     *
     * 用 [DEBUG_BUILD]（按构建类型各放一份的 `const val`）而不是 `applicationInfo.flags` ——
     * 它编译期就是常量，不会因为某个构建类型顺手打开 `debuggable` 而说谎。
     */
    fun buildKind(): String = if (DEBUG_BUILD) "debug" else "release"

    // ---------------------------------------------------------------- 动作 1：跳转

    /**
     * 跳到该输入法里「模块注入了条目」的那个设置页。
     *
     * 三级降级，**从准到不准**：
     *  1. [Ime.action] —— 输入法自己声明的深链 action（小布有，直达五笔页），普通 `startActivity` 就行；
     *  2. [Ime.pageClass] / [Ime.pageUri] —— 那些注入页在 manifest 里都是 `exported=false`，
     *     普通应用起不来（会 Permission Denial），所以走 **root** 的 `am start --user current`；
     *  3. [Ime.settings] —— 导出的设置页 Activity。
     *  最后再退回「打开输入法首页」。
     *
     * **不返回提示语、调用方也不弹 toast**：跳没跳成看屏幕就知道，弹一条反而糊在输入法设置页上面。
     * 每一档的成败都记进日志（TAG `OplusWubi`），要排查看日志。
     */
    fun openSettings(context: Context, ime: Ime) {
        val pm = context.packageManager
        if (!installed(context, ime.pkg)) {
            XLog.i("打开 ${ime.name} 设置页：没装，跳过")
            return
        }

        // ① 输入法自己声明的深链 action（最准）
        ime.action?.let { action ->
            val intent = Intent(action).setPackage(ime.pkg).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            if (runCatching { context.startActivity(intent) }.isSuccess) {
                XLog.i("打开 ${ime.name} 设置页（action=$action）")
                return
            }
            XLog.w("打开 ${ime.name} 设置页：action=$action 起不来，往下降级")
        }

        // ② root 起「注入页」：没导出的页普通应用起不来，root 的 am start 能起
        if (ime.pageUri != null || ime.pageClass != null) {
            val cmd = if (ime.pageUri != null) {
                "am start --user current -a android.intent.action.VIEW -d '${ime.pageUri}'"
            } else {
                "am start --user current -n ${ime.pkg}/${ime.pageClass}"
            }
            val r = su(cmd)
            val ok = r.started && r.exitCode == 0 && !r.output.contains("Error")
            if (ok) {
                XLog.i("打开 ${ime.name} 设置页（root am start：${ime.pageClass ?: ime.pageUri}）")
                return
            }
            XLog.w(
                "打开 ${ime.name} 设置页：root am start 失败（started=${r.started} " +
                    "exit=${r.exitCode}，输出=${r.output}），往下降级",
            )
        }

        // ③ 显式 Activity（导出的）
        val explicit = ime.settings?.let { cls ->
            val cn = ComponentName(ime.pkg, cls)
            val ok = runCatching { pm.getActivityInfo(cn, 0).exported }.getOrDefault(false)
            if (ok) cn else null
        }
        if (explicit != null) {
            val intent = Intent(Intent.ACTION_MAIN).setComponent(explicit)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            if (runCatching { context.startActivity(intent) }.isSuccess) {
                XLog.i("打开 ${ime.name} 设置页：${explicit.className}")
                return
            }
        }
        val launch = pm.getLaunchIntentForPackage(ime.pkg)
        if (launch != null) {
            launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            if (runCatching { context.startActivity(launch) }.isSuccess) {
                XLog.i("打开 ${ime.name} 首页（没找到可直达的设置页）")
                return
            }
        }
        XLog.w("打开 ${ime.name} 设置页：打不开（找不到入口 Activity）")
    }

    // ---------------------------------------------------------------- 动作 2：重启

    /**
     * 强停**全部**已安装的受支持输入法（顶栏右上角那个图标）。
     *
     * 改了 / 装了模块必须让输入法进程重启才会加载新代码，所以这个按钮的语义是「重载模块」。
     *
     * **走 root**：一条 `su -c` 里把每个包 `am force-stop` 掉 —— 这条路不依赖输入法进程此刻是否活着、
     * 也不需要注入侧配合，数量恒等于「装了几个」。
     *
     * 为什么不再走广播（2026-10-08）：广播那版只对「上一轮活性探测里回过 PONG 的包」发，
     * 而应答器是注入侧**动态注册**的 —— 宿主进程没起就没人接，于是同一个按钮会抖：
     * 真机日志 11:55:37 只停 1 个、11:56:06 停 2 个。强停不需要谁来应答。
     *
     * ⚠️ `su` 是**阻塞**的，首次还会弹授权框，所以调用方必须在后台线程调（历史教训：卡 UI）。
     * 拿不到 root 时如实说明让用户手动停，**不假装成功**（见 `host-notes.md` 的同一结论）。
     *
     * 副作用：`am force-stop` 会把应用置成 stopped，系统随即把当前输入法切走，用户得重选一次。
     */
    fun restartAll(context: Context): String {
        val targets = IMES.filter { installed(context, it.pkg) }
        if (targets.isEmpty()) return "未安装输入法"

        // 一条 su 里跑完：每个包后面跟一句 echo 把「这个包停成功了」带出来，好数个数
        val script = targets.joinToString("; ") {
            "am force-stop ${it.pkg} && echo \"[${it.pkg}]=ok\""
        }
        val r = su(script)
        if (!r.started) {
            // 设备没 root / 模块没被授权时 `su` 这个可执行文件压根不存在，拿到的是
            // `IOException: Cannot run program "su": error=2` —— 那是给排查用的，不是给用户看的
            XLog.w("强停失败：su 起不来（${r.output}）")
            return suUnavailableReason(r.output)
        }
        val stopped = r.output.lineSequence()
            .filter { it.endsWith("]=ok") }
            .map { it.substringAfter("[").substringBefore("]") }
            .toList()
        val failed = targets.map { it.pkg }.filterNot { it in stopped }
        if (stopped.isEmpty()) {
            // su 起来了却一个都没停掉：基本都是授权被拒（su 立刻退出并回 Permission denied）。
            // 具体输出仍原样进日志，别只说一句「失败」
            XLog.w("强停失败：exit=${r.exitCode}，输出=${r.output}")
            return suUnavailableReason(r.output)
        }
        if (failed.isEmpty()) {
            XLog.i("已强停 ${stopped.size} 个输入法：$stopped")
            return "已重载"
        }
        // toast 只说「不全成功」，具体哪几家没停掉进日志
        XLog.w("强停不完整：成功 $stopped / 失败 $failed（exit=${r.exitCode}；输出=${r.output}）")
        return "部分输入法未重载"
    }

    /** 一条 root 命令的结果。[started] 为 false 表示 `su` 压根起不来（没 root / 没授权）。 */
    private class SuResult(val started: Boolean, val exitCode: Int, val output: String)

    /**
     * 设备上 `su` 可执行文件的位置，**按优先级逐个试**。
     *
     * ⚠️ 不能只写 `"su"` 让系统走 PATH —— 应用进程的环境变量是 zygote 那套，
     * PATH 里**没有** `/system/bin`（真机实测：`IOException: Cannot run program "su": error=2`），
     * 而设备上 KernelSU 的 su 恰恰就在 `/system/bin/su`。所以先试绝对路径，再退回 `"su"`。
     */
    private val SU_PATHS = listOf("/system/bin/su", "/system/xbin/su", "/sbin/su", "su")

    /**
     * 跑 `su -c <script>`。**阻塞**，只能在后台线程调。
     *
     * `su` 不存在（设备没 root / 模块没被授权）时抛 IOException —— 这里把所有候选路径都试完，
     * 收成 `started=false` 并把原因放进 [SuResult.output]，让调用方如实回报而不是假装成功了。
     *
     * ⚠️ 权限管理器（KernelSU / Magisk）对**未授权**的应用会把 `su` 从它的 mount namespace 里
     * 隐藏掉，于是 `execve` 直接回 `ENOENT`（表现为 `error=2`）—— 看起来像"设备没 root"，
     * 实际是"没给这个应用授权"。所以 [suUnavailableReason] 会把这种情形翻成人话。
     */
    private fun su(script: String, timeoutMs: Long = 20_000): SuResult {
        var lastError = "没有可用的 su"
        for (path in SU_PATHS) {
            val result = runCatching { runSu(path, script, timeoutMs) }
                .getOrElse { e ->
                    // 这个路径不存在（或起不来）→ 记下原因，试下一个
                    lastError = "$path: $e"
                    null
                }
            if (result != null) return result
        }
        return SuResult(false, -1, lastError)
    }

    /** 用指定的 su 可执行文件跑一条命令。抛异常 = 这个路径不可用（调用方会试下一个）。 */
    private fun runSu(path: String, script: String, timeoutMs: Long): SuResult {
        val p = ProcessBuilder(path, "-c", script).redirectErrorStream(true).start()
        p.outputStream.close()
        // 输出必须在另一个线程里排空，否则缓冲区满了子进程会卡住（`su` 的输出量不小）
        val sb = StringBuilder()
        val drain = Thread {
            runCatching {
                p.inputStream.bufferedReader().forEachLine { line ->
                    synchronized(sb) { sb.appendLine(line) }
                }
            }
        }
        drain.isDaemon = true
        drain.start()
        if (!p.waitFor(timeoutMs, java.util.concurrent.TimeUnit.MILLISECONDS)) {
            p.destroyForcibly()
            return SuResult(true, -1, "超时 ${timeoutMs}ms")
        }
        drain.join(500)
        return SuResult(true, p.exitValue(), synchronized(sb) { sb.toString().trim() })
    }

    /**
     * `su` 起不来时给用户看的一句话。
     *
     * 三种情况分开说，因为**用户要做的事完全不同**：
     *  - `ENOENT`（`error=2`）→ 设备有 root，但没给本模块授权（KernelSU/Magisk 会把未授权应用的
     *    `su` 藏起来，表现就是"文件不存在"）；
     *  - `EACCES` / `Permission denied` → 授权了但被拒了；
     *  - 其它 → 原样带上，便于排查。
     */
    private fun suUnavailableReason(output: String): String = when {
        output.contains("error=2") || output.contains("No such file or directory") ->
            "未授权 root"
        output.contains("Permission denied", ignoreCase = true) ||
            output.contains("error=13") ->
            "root 授权被拒绝"
        else -> "拿不到 root"
    }

    // ---------------------------------------------------------------- 配置开关（走 ContentProvider）

    /**
     * 读「解除剪贴板条数上限」的当前值。
     *
     * ## 为什么不再读输入法的私有目录（2026-10-10 改）
     *
     * 原先要 `su` 去 `grep` 各输入法 `filesDir` 下的 `config.properties`（模块进程进不去那些目录），
     * 于是这个纯本地开关被 root 绑住，还要处理「KernelSU 把未授权应用的 su 藏起来」。
     * 现在真值在**模块自己**的 [ModuleSettingsProvider] 里，普通 `ContentResolver.query()` 就行。
     *
     * 返回 `null` = **真值不可用**（provider 不存在 / 查失败）。这时界面**不该瞎猜**：
     * 调用方保持当前显示值不动 —— 猜「开」会在用户刚关掉时把开关弹回「开」。
     *
     * 不阻塞，但仍建议放后台线程调（跨进程查询，别占主线程）。
     */
    fun clipboardUnlimited(context: Context): Boolean? = runCatching {
        context.contentResolver.query(
            ModuleSettingsProvider.CONTENT_URI,
            arrayOf(ModuleConfig.KEY_CLIPBOARD_UNLIMITED),
            null,
            null,
            null,
        )?.use { c -> if (c.moveToFirst()) c.getString(1) else null }
    }.getOrNull()?.let { it == "true" || it == "1" }

    /**
     * 写「解除剪贴板条数上限」。
     *
     * 写进模块自己的 provider（**不需要 root，也不走 Binder** —— 界面与 provider 同进程，
     * 直接调 [ModuleSettingsProvider.write]）。注入侧会在 2 秒内把新值抄回本地
     * `config.properties`（见 `ModuleConfig.sync`），所以改完**不用重启输入法**，
     * 切一下键盘 / 再打一次字就生效。
     */
    fun setClipboardUnlimited(context: Context, value: Boolean): String {
        ModuleSettingsProvider.write(context, ModuleConfig.KEY_CLIPBOARD_UNLIMITED, value)
        val now = clipboardUnlimited(context)
        return if (now == value) "已生效" else "写入失败"
    }

    // ---------------------------------------------------------------- 桌面图标

    /** 桌面图标那个 alias 的组件名。 */
    private const val LAUNCHER_ALIAS = "com.lookie.opluswubi.ui.LauncherAlias"

    /** 桌面图标现在是不是被隐藏了。 */
    fun launcherHidden(context: Context): Boolean = runCatching {
        context.packageManager.getComponentEnabledSetting(
            ComponentName(context.packageName, LAUNCHER_ALIAS),
        ) == PackageManager.COMPONENT_ENABLED_STATE_DISABLED
    }.getOrDefault(false)

    /**
     * 隐藏 / 恢复桌面图标：直接开关 `LauncherAlias` 这个组件。
     *
     * ⚠️ 隐藏之后桌面上就没有入口了，只能靠 `adb shell am start -n
     * com.lookie.opluswubi/.ui.MainActivity`（Activity 本身没被禁用）再打开 —— 界面上把这句话写在开关下面。
     */
    fun setLauncherHidden(context: Context, hidden: Boolean): Boolean = runCatching {
        context.packageManager.setComponentEnabledSetting(
            ComponentName(context.packageName, LAUNCHER_ALIAS),
            if (hidden) PackageManager.COMPONENT_ENABLED_STATE_DISABLED
            else PackageManager.COMPONENT_ENABLED_STATE_ENABLED,
            PackageManager.DONT_KILL_APP,
        )
        XLog.i("桌面图标：${if (hidden) "已隐藏" else "已恢复"}")
        true
    }.getOrDefault(false)

}