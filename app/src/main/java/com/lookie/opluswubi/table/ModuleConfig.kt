package com.lookie.opluswubi.table

import android.content.Context
import android.os.Handler
import android.os.HandlerThread
import com.lookie.opluswubi.XLog
import java.io.File

/**
 * 模块配置：一个**跨进程可读**的极简键值存储。
 *
 * ## 为什么不用 SharedPreferences
 *
 * 有些输入法把「设置界面」和「输入法服务」放进**不同进程**（小布输入法则都在同一进程）。
 * Android 的 SharedPreferences 在每个进程里各自缓存一份，**别的进程写进去的值本进程看不到**
 * ——设置页里切换了方案，输入法进程永远读的还是旧值，必须重启输入法才生效。
 *
 * 所以这里改成「存在目标输入法私有目录下的一个纯文本文件」，每次访问按
 * `(mtime, size)` 判断是否需要重新读，跨进程天然可见。
 *
 * 文件写在**目标输入法自己的** filesDir 下（见 [TableStore.root]），
 * 因此每个输入法各有一份独立配置，互不污染。
 */
object ModuleConfig {

    /** 配置文件名（相对码表根目录）。 */
    private const val FILE_NAME = "config.properties"

    const val KEY_ACTIVE_TABLE = "active_table"

    /**
     * 「支持简词」。
     *
     * 简词 = 虎码方案里 1～3 码的高频简码词（一简词 / 二简词 / 三简词），
     * 属可选项：打开后打一码（如 `u`）首词出「的」。
     */
    const val KEY_SIMPLE_WORD = "support_simple_word"

    /**
     * 「修复五笔方案」（百度输入法适配用的开关）。
     *
     * 打开时模块会在每次下发核心配置前把 `ImePref.a0` 与已保存的方案对齐并重下发；
     * 关掉就完全不碰百度的方案。
     */
    const val KEY_BAIDU_FIX = "baidu_fix_wb_scheme"

    /**
     * 「解除剪贴板条数上限」（四个输入法通用）。
     *
     * 打开时剪贴板历史不再按各家自己的上限（小布 500 / 搜狗 1000 / 百度 300）裁剪；
     * 关掉就恢复各家原生行为。**默认开**（原来的行为就是一直开，不能因为加了开关就变了默认）。
     */
    const val KEY_CLIPBOARD_UNLIMITED = "clipboard_unlimited"

    // ---------------------------------------------------------------- 读写

    private fun file(context: Context): File = File(TableStore.root(context), FILE_NAME)

    /** 读一个字符串配置；不存在返回 [def]。 */
    fun getString(context: Context, key: String, def: String = ""): String =
        read(context)[key] ?: def

    /** 写一个字符串配置（空串等于删除该 key）。 */
    fun putString(context: Context, key: String, value: String) {
        val map = read(context).toMutableMap()
        if (value.isEmpty()) map.remove(key) else map[key] = value
        write(context, map)
    }

    fun getBoolean(context: Context, key: String, def: Boolean = false): Boolean =
        read(context)[key]?.let { it == "true" || it == "1" } ?: def

    fun putBoolean(context: Context, key: String, value: Boolean) =
        putString(context, key, if (value) "true" else "false")

    fun getInt(context: Context, key: String, def: Int = 0): Int =
        read(context)[key]?.toIntOrNull() ?: def

    fun putInt(context: Context, key: String, value: Int) =
        putString(context, key, value.toString())

    /**
     * 是否支持简词，默认开（搜狗输入法适配用的开关）。
     *
     * 同样走文件存储：搜狗的设置页与输入法本体虽然同进程，但统一走一套读写实现更省心。
     */
    fun simpleWord(context: Context): Boolean {
        sync(context)
        return getBoolean(context, KEY_SIMPLE_WORD, true)
    }

    fun setSimpleWord(context: Context, value: Boolean) =
        putBoolean(context, KEY_SIMPLE_WORD, value)

    /** 是否自动修复五笔方案，默认开（百度输入法适配用的开关）。 */
    fun baiduFixEnabled(context: Context): Boolean {
        sync(context)
        return getBoolean(context, KEY_BAIDU_FIX, true)
    }

    fun setBaiduFixEnabled(context: Context, value: Boolean) =
        putBoolean(context, KEY_BAIDU_FIX, value)

    /**
     * 是否解除剪贴板条数上限，默认开（四个输入法通用）。
     *
     * 剪贴板 Hook 是**常驻**的（Hook 一旦卸载不了），由这个开关在每次调用时决定
     * 「放行原值」还是「顶成上限」。所以改完立刻生效，不需要重启输入法。
     */
    fun clipboardUnlimited(context: Context): Boolean {
        sync(context)
        return getBoolean(context, KEY_CLIPBOARD_UNLIMITED, true)
    }

    fun setClipboardUnlimited(context: Context, value: Boolean) =
        putBoolean(context, KEY_CLIPBOARD_UNLIMITED, value)

    // ---------------------------------------------------------------- 与模块进程同步

    /**
     * 从**模块进程**（`ModuleSettingsProvider`）把配置抄回本地 `config.properties`。
     *
     * ## 为什么需要（2026-10-10）
     *
     * 模块界面（`com.lookie.opluswubi` 进程）要改这些开关，而本地配置文件在**输入法自己的
     * 私有目录**里 —— 模块进程进不去，只能靠 `su`（还依赖设备侧给 root 授权）。
     * 现在改成：模块把值写进自己的 `ContentProvider`，这里读回来落盘，**完全不需要 root**。
     *
     * ## 时序
     *
     * 每个读方法（[simpleWord] / [baiduFixEnabled] / [clipboardUnlimited]）开头都调它，
     * 但它是**节流**的（[SYNC_INTERVAL_MS] 内只查一次 provider），所以热路径上多数时候
     * 只是比一下时间戳。真正的 `query()` 一次只花几毫秒，且不在 UI 线程。
     *
     * 拿不到 provider（模块没装 / 没启用 / 老版本）就**什么都不做**，沿用本地旧值。
     */
    @Volatile
    private var lastSyncAt = 0L

    private fun sync(context: Context) {
        val now = android.os.SystemClock.elapsedRealtime()
        if (now - lastSyncAt < SYNC_INTERVAL_MS) return
        lastSyncAt = now
        syncNow(context, "节流到期")
    }

    /**
     * 立即同步一次（跳过节流）。
     *
     * 之所以要有一个「跳过节流」的入口，是因为节流版（[sync]）只在热路径上被调到 ——
     * 用户在模块界面改完开关、回头一看本地文件还是旧值，看着就像「改了不生效」。
     * 现在启动 / 轮询 / 收到推送都走这个无节流版，热路径仍走节流版。
     */
    fun syncNow(context: Context, reason: String = "手动") {
        runCatching {
            val cached = read(context)
            val changed = mutableMapOf<String, String>()
            val seen = mutableMapOf<String, String?>()
            for (key in ModuleSettingsProvider.EXPOSED_KEYS) {
                val remote = queryRemote(context, key)
                seen[key] = remote
                if (remote == null) continue
                if (cached[key] != remote) changed[key] = remote
            }
            // 留痕**无条件**打（成功/无变化/全查不到都看得见）—— 老毛病是「只在成功时打」，
            // 失败路径一个字都不留，看起来像函数没被调用（2026-10-10 白跑一轮的教训）。
            XLog.i("配置同步[$reason]：provider=$seen 本地=${cached.toMap()} 待更新=${changed.toMap()}")
            if (changed.isEmpty()) return@runCatching
            val map = cached.toMutableMap().apply { putAll(changed) }
            write(context, map)
            XLog.i("已从模块同步配置：${changed.keys.sorted()}")
        }.onFailure { XLog.w("从模块同步配置失败（沿用本地旧值）", it) }
    }

    /** 向模块的 provider 查一个键；查不到返回 null。 */
    private fun queryRemote(context: Context, key: String): String? = runCatching {
        context.contentResolver.query(
            ModuleSettingsProvider.CONTENT_URI,
            arrayOf(key),
            null,
            null,
            null,
        )?.use { c ->
            if (c.moveToFirst()) c.getString(1) else null
        }
    }.onFailure { XLog.w("查模块 provider 失败（key=$key）", it) }.getOrNull()

    // ---------------------------------------------------------------- 常驻同步（2026-10-10）

    /**
     * 常驻轮询：**不依赖任何功能被用到**，每隔 [POLL_INTERVAL_MS] 拉一次 provider。
     *
     * ## 为什么必须常驻（真机踩到）
     *
     * 原先只在「读配置的那一刻」顺带同步 —— 而读配置的调用点全在**功能的热路径**上：
     * 小布是「插一条剪贴板记录」时、搜狗是「候选重排」时。用户在模块界面改完开关，
     * 如果不巧没有再触发那条路径（小布尤其：改完不看剪贴板就永远不同步），
     * 本地 `config.properties` 就一直停在旧值 —— 用户看到的就是「改了不生效」。
     *
     * 真机证据（PLK110）：provider 里 `clipboard_unlimited=false`（用户在模块界面关掉了），
     * 而小布/搜狗私有目录里的文件都还是 `clipboard_unlimited=true`，两边对不上。
     *
     * 所以改成：适配器装 Hook 时起一个后台线程，**无条件**周期拉取。
     * 拉取本身是跨进程 `query()`，一次几毫秒，5 秒一次对功耗没有可感知影响。
     *
     * ⚠️ 但 `query()` 会被 **Android 11+ 包可见性**挡住（目标输入法 manifest 里没有
     * `<queries>`，而我们改不了它），真机表现是「找不到 provider」、`query()` 返回 null，
     * 且**冷启动时几乎必然失败**。所以轮询只是兜底 —— 主通道是 [ModuleSettingsBus] 的广播。
     */
    @Volatile
    private var poller: Handler? = null

    private const val POLL_INTERVAL_MS = 5_000L

    /** 幂等：每个输入法进程只会有一个轮询线程。 */
    @Synchronized
    fun startPolling(context: Context) {
        if (poller != null) return
        val app = context.applicationContext ?: context

        // 主通道：注册广播接收器，模块界面改开关时直接推过来（不用等轮询）。
        // 这一步必须在轮询之前 —— 推送是即时的，轮询只是兜底（见 ModuleSettingsBus 注释）。
        ModuleSettingsBus.registerReceiver(app)

        val thread = HandlerThread("wubi-config-sync").apply { start() }
        val handler = Handler(thread.looper)
        poller = handler
        // 先立刻同步一次（进程刚起来，本地文件可能是上次留下的旧值）
        syncNow(app, "启动")
        handler.postDelayed(object : Runnable {
            override fun run() {
                syncNow(app, "轮询")
                handler.postDelayed(this, POLL_INTERVAL_MS)
            }
        }, POLL_INTERVAL_MS)
        XLog.i("配置同步已启动（推送接收器 + 每 ${POLL_INTERVAL_MS}ms 拉一次 provider 兜底）")
    }

    /**
     * 收到模块推来的一条配置，直接落盘。
     *
     * 不做任何「换算」—— 推送里的值就是模块界面上那个开关的值。
     * 落盘后 `read()` 的缓存会因为 `write()` 里调的 `invalidate()` 自动失效，
     * 所以下一次热路径读取立刻是新值（改完**不用重启输入法**）。
     */
    fun applyPushed(context: Context, key: String, value: String) {
        runCatching {
            val map = read(context).toMutableMap()
            if (map[key] == value) {
                XLog.i("配置推送[$key=$value]与本地一致，无需落盘")
                return@runCatching
            }
            map[key] = value
            write(context, map)
            XLog.i("已落盘模块推送的配置：$key=$value")
        }.onFailure { XLog.w("落盘模块推送的配置失败：$key", it) }
    }

    /**
     * 两次向 provider 拉取的**最小间隔**。
     *
     * 2 秒：比人手点开关的节奏快得多（改完下一次输入就生效），又不会让热路径上
     * 每个键都去跨进程查一次。
     */
    private const val SYNC_INTERVAL_MS = 2_000L

    // ---------------------------------------------------------------- 实现

    @Volatile
    private var cachedFile: File? = null

    @Volatile
    private var cachedStamp: Long = -1L

    @Volatile
    private var cached: Map<String, String> = emptyMap()

    /** 读取全部键值；文件没变就直接返回缓存（输入法热路径上会被频繁调用）。 */
    @Synchronized
    private fun read(context: Context): Map<String, String> {
        val f = file(context)
        val stamp = if (f.isFile) f.lastModified() * 31 + f.length() else 0L
        if (cachedFile?.absolutePath == f.absolutePath && cachedStamp == stamp) return cached
        val map = LinkedHashMap<String, String>()
        runCatching {
            if (f.isFile) {
                f.bufferedReader(Charsets.UTF_8).useLines { seq ->
                    for (line in seq) {
                        val trimmed = line.trim()
                        if (trimmed.isEmpty() || trimmed.startsWith("#")) continue
                        val eq = trimmed.indexOf('=')
                        if (eq <= 0) continue
                        map[trimmed.substring(0, eq)] = trimmed.substring(eq + 1)
                    }
                }
            }
        }.onFailure { XLog.w("读取模块配置失败：${f.absolutePath}", it) }
        cachedFile = f
        cachedStamp = stamp
        cached = map
        return map
    }

    /** 写回：先写临时文件再 rename，避免读写两个进程同时操作时读到半截文件。 */
    private fun write(context: Context, map: Map<String, String>) {
        val f = file(context)
        runCatching {
            val tmp = File(f.parentFile, "$FILE_NAME.tmp")
            tmp.bufferedWriter(Charsets.UTF_8).use { w ->
                for ((k, v) in map) w.append(k).append('=').append(v).append('\n')
            }
            if (!tmp.renameTo(f)) {
                tmp.copyTo(f, overwrite = true)
                tmp.delete()
            }
        }.onFailure { XLog.w("写入模块配置失败：${f.absolutePath}", it) }
        // 写完了把自己这份缓存作废，下次读会重新加载（也让别的进程能读到）
        invalidate()
    }

    fun invalidate() {
        cachedFile = null
        cachedStamp = -1L
        cached = emptyMap()
    }
}
