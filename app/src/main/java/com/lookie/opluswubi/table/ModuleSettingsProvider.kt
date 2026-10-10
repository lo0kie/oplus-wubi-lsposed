package com.lookie.opluswubi.table

import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.content.UriMatcher
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import com.lookie.opluswubi.XLog

/**
 * 模块配置的**跨应用读取**入口。
 *
 * ## 为什么需要它（2026-10-10，用户问「解除上限为什么要 root」）
 *
 * 原先模块界面改一个开关（「解除剪贴板条数上限」）要写进**目标输入法自己的私有目录**
 * （`/data/data/<输入法>/files/custom_wubi_.../config.properties`，见 [TableStore]），
 * 而模块进程进不去别人的私有目录 —— 只能靠 `su`。于是这个纯本地开关被 root 绑住了，
 * 还要处理「KernelSU 把未授权应用的 su 藏起来」这类设备侧问题。
 *
 * 但真正需要的其实只是**把「用户改了开关」这件事传达给注入侧**。那就用一个
 * `exported=true` 的 ContentProvider：注入侧在输入法进程里 `query()` 一下就能读到，
 * 完全不需要 root，也不受输入法进程生死影响（进程没起就下次起时读到）。
 *
 * ## ⚠️ 真机踩坑（2026-10-10）：provider 会被**包可见性**挡住
 *
 * provider 不是万能的。Android 11+ 起，跨应用 `query()` 前要先「看得见」对方：
 * 调用方 manifest 里得有 `<queries><provider android:authorities="…"/></queries>`，
 * 否则 `ActivityThread.acquireProvider` 直接
 * `Failed to find provider info for com.lookie.opluswubi.settings`，返回 null
 * —— **注意是「找不到 provider」，不是权限拒绝，看着特别像"模块没装"**。
 *
 * 而目标输入法（搜狗 / 百度 / 小布）的 manifest 我们改不了，不可能给它们加 `<queries>`。
 * 实测：模块界面刚装完/刚打开（provider 进程热着）时 query 能成，冷启动就全 null
 * —— 于是「改了开关不生效」时有时无。
 *
 * 所以**真值源改成推送**（见 [ModuleSettingsBus]）：模块界面改开关时直接
 * `sendBroadcast` 一条**带 `setPackage(输入法包名)` 的显式广播** —— 广播不受包可见性
 * 限制（显式指定包名即可送达），注入侧用运行时注册的 receiver 收下、写回本地文件。
 * provider 保留为**兜底**（新装模块、注入侧还没收到过推送时用）。
 *
 * ## 数据流（注入侧**照旧读本地文件**，热路径一行没改）
 *
 * ```
 * 模块界面 --(① ModuleSettingsBus 显式广播，主通道)------------------+
 *          --(② ContentResolver.query → 本 Provider，兜底)----------+
 *                                                                    |
 *                          注入侧写回 /data/data/<输入法>/files/.../config.properties
 *                                                                    |
 *                          ModuleConfig.clipboardUnlimited() 照旧读本地文件（不变）
 * ```
 *
 * 也就是说：**推送/ provider 是"真值源"，本地 `config.properties` 是热路径缓存**。
 *
 * ## 为什么不用 `readPermission`
 *
 * 用自定义的**签名级**权限最严谨，但那要求模块与输入法用同一份签名（不可能），
 * 于是只能退化成 `normal` 级 —— 与「不声明权限」安全性等同（`normal` 权限任何应用
 * 只要在 manifest 里声明就拿到）。所以这里**不声明权限**，改用**只读白名单 key** 来收窄面：
 * 只有 [EXPOSED_KEYS] 里的键能被读到，其它键（比如码表内容）一概不暴露；
 * 写入也**只允许模块自己的 uid**（见 [callerIsSelf]）。
 */
class ModuleSettingsProvider : ContentProvider() {

    companion object {
        /** 与 `AndroidManifest.xml` 里的 `android:authorities` 一致。 */
        const val AUTHORITY = "com.lookie.opluswubi.settings"

        /** `content://com.lookie.opluswubi.settings/config` */
        val CONTENT_URI: Uri = Uri.parse("content://$AUTHORITY/config")

        private const val CODE_CONFIG = 1

        /** 允许被**别的应用**读到的键（白名单，别把码表之类的键漏出去）。 */
        val EXPOSED_KEYS: Set<String> = setOf(
            ModuleConfig.KEY_CLIPBOARD_UNLIMITED,
            ModuleConfig.KEY_SIMPLE_WORD,
            ModuleConfig.KEY_BAIDU_FIX,
        )

        private val MATCHER = UriMatcher(UriMatcher.NO_MATCH).apply {
            addURI(AUTHORITY, "config", CODE_CONFIG)
        }

        /**
         * **模块界面写开关就走这里**（同进程直接调，不经过 Binder）。
         *
         * 写完立刻把这次变更**推**给三个输入法（见 [ModuleSettingsBus.pushAll]）——
         * 光靠注入侧来 `query()` 会被包可见性挡住（类注释里那段真机踩坑），
         * 推送不受这个限制，改完 1 秒内输入法侧就落盘。
         *
         * 见 [update] 的注释：provider 对外只读，写入只提供这个进程内方法。
         */
        fun write(context: Context, key: String, value: Boolean) {
            if (key !in EXPOSED_KEYS) {
                XLog.w("忽略不在白名单里的配置键：$key")
                return
            }
            context.getSharedPreferences("module_settings", Context.MODE_PRIVATE)
                .edit().putString(key, if (value) "true" else "false").apply()
            ModuleSettingsBus.pushAll(context, key, value)
        }
    }

    override fun onCreate(): Boolean = true

    override fun query(
        uri: Uri,
        projection: Array<out String>?,
        selection: String?,
        selectionArgs: Array<out String>?,
        sortOrder: String?,
    ): Cursor? {
        if (MATCHER.match(uri) != CODE_CONFIG) return null
        val ctx = context ?: return null

        // 只回白名单里的键；`selection` 当成「只取这一个键」用（`module_config` 的键名）
        val wanted = projection?.filter { it in EXPOSED_KEYS }
            ?: selection?.takeIf { it in EXPOSED_KEYS }?.let { listOf(it) }
            ?: EXPOSED_KEYS.toList()

        val cursor = MatrixCursor(arrayOf("key", "value"))
        for (key in wanted) {
            val v = readRaw(ctx, key) ?: continue
            cursor.addRow(arrayOf<Any>(key, v))
        }
        return cursor
    }

    /**
     * 写入：**一律拒绝**。
     *
     * ## 为什么不接受写入（2026-10-10 真机踩坑）
     *
     * 原先想靠 `Binder.getCallingUid() == applicationInfo.uid` 来"只放行模块自己"，
     * 但真机实测 `adb shell content update` 竟然写进去了 —— 也就是说这个判据在
     * **ContentProvider 被 CLI / 框架代理调用时并不可靠**。既然如此，就不该把写入
     * 挂在 provider 上：**模块界面和 provider 本来就在同一个进程**，直接调 [write] 即可，
     * 完全不需要跨进程写。于是这里把 `insert`/`update`/`delete` 全部堵死，
     * provider 对外**只读**（并且只读白名单键）。
     */
    override fun update(
        uri: Uri,
        values: ContentValues?,
        selection: String?,
        selectionArgs: Array<out String>?,
    ): Int {
        XLog.w("拒绝写模块配置（provider 只读；模块自己请用 ModuleSettingsProvider.write）")
        return 0
    }

    override fun insert(uri: Uri, values: ContentValues?): Uri? {
        XLog.w("拒绝写模块配置（provider 只读）")
        return null
    }

    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int {
        XLog.w("拒绝删模块配置（provider 只读）")
        return 0
    }

    override fun getType(uri: Uri): String? = "vnd.android.cursor.dir/vnd.opluswubi.config"

    // ---------------------------------------------------------------- 落盘（SharedPreferences）

    /**
     * provider 自己的存储：模块进程的 `SharedPreferences`。
     *
     * ⚠️ **不能**用 [ModuleConfig] —— 那个是「目标输入法私有目录」里的文件，
     * 在模块进程里 `TableStore.storageDirName` 还停在默认值 `"custom_wubi"`（适配器是在宿主
     * 进程里改它的），照它写会落到错误的目录。provider 要的是「模块进程内的真值源」，
     * 用模块自己的 SharedPreferences 最直接，也不需要任何权限。
     */
    private fun prefs(ctx: Context) = ctx.getSharedPreferences("module_settings", Context.MODE_PRIVATE)

    private fun readRaw(ctx: Context, key: String): String? =
        runCatching { prefs(ctx).getString(key, null) }.getOrNull()

    /** 内部写：给模块界面用（同进程，不走 Binder）。 */
    private fun writeRaw(ctx: Context, key: String, value: String) {
        runCatching { prefs(ctx).edit().putString(key, value).apply() }
            .onFailure { XLog.w("写模块配置失败：$key", it) }
    }
}
