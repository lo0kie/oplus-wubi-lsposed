package com.lookie.opluswubi.table

import android.content.Context
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
    fun simpleWord(context: Context): Boolean =
        getBoolean(context, KEY_SIMPLE_WORD, true)

    fun setSimpleWord(context: Context, value: Boolean) =
        putBoolean(context, KEY_SIMPLE_WORD, value)

    /** 是否自动修复五笔方案，默认开（百度输入法适配用的开关）。 */
    fun baiduFixEnabled(context: Context): Boolean =
        getBoolean(context, KEY_BAIDU_FIX, true)

    fun setBaiduFixEnabled(context: Context, value: Boolean) =
        putBoolean(context, KEY_BAIDU_FIX, value)

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
