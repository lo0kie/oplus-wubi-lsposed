package com.lookie.opluswubi.ui

import android.app.Dialog
import android.content.Context
import android.view.WindowManager
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.provider.OpenableColumns
import android.text.InputType
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import com.lookie.opluswubi.XLog
import com.lookie.opluswubi.table.ImportResult
import com.lookie.opluswubi.table.ModuleConfig
import com.lookie.opluswubi.table.TableMeta
import com.lookie.opluswubi.table.TableStore

/**
 * 「自定义方案」的交互逻辑。
 *
 * 方案列表本身是设置页里的条目（一个方案一条 + 底部「添加方案…」，见 OplusSettingsHook），
 * 这里只负责条目点开之后的事情：方案操作、导入、模块开关。
 *
 * 对话框同一时刻**只允许存在一个**：每次 show 之前先把上一个 dismiss 掉，
 * 否则开关类设置点一下开一个窗，会越点越多。
 */
object TableManager {

    private val main = Handler(Looper.getMainLooper())

    /** 当前正在显示的对话框句柄（用于「先关旧的再开新的」）。 */
    @Volatile
    private var currentDialog: Any? = null

    private fun show(context: Context, build: (SimpleDialog) -> Unit): Any? {
        DialogFactory.dismiss(currentDialog)
        currentDialog = null
        val dialog = DialogFactory.create(context)
        build(dialog)
        val handle = dialog.show()
        currentDialog = handle
        return handle
    }

    // ------------------------------------------------------------------ 添加方案

    /** 自定义方案数量上限。 */
    const val MAX_SCHEMES = 3

    /** 点「添加方案…」：直接拉起文件选择器，导入后自动设为当前方案。 */
    fun addScheme(context: Context) {
        if (TableStore.list(context).size >= MAX_SCHEMES) {
            toast(context, "最多 $MAX_SCHEMES 个方案")
            return
        }
        val started = FilePickers.pick(context) { uri ->
            if (uri == null) {
                XLog.i("用户取消了文件选择")
                return@pick
            }
            doImport(context, uri)
        }
        if (!started) toast(context, "无法打开文件选择器")
    }

    private fun doImport(context: Context, uri: Uri) {
        toast(context, "正在解析码表…")
        val displayName = queryDisplayName(context, uri)
        val appContext = context.applicationContext
        Thread {
            val result = runCatching {
                appContext.contentResolver.openInputStream(uri)?.use { stream ->
                    TableStore.import(
                        context = appContext,
                        displayName = displayName.substringBeforeLast('.'),
                        sourceName = displayName,
                        input = stream,
                    )
                } ?: ImportResult.Failed
            }.onFailure { XLog.e("导入码表异常", it) }.getOrDefault(ImportResult.Failed)

            main.post {
                when (result) {
                    is ImportResult.Added -> {
                        // 新方案直接生效，省一步操作
                        TableStore.setActive(appContext, result.meta.id)
                        EntryRefresher.refresh()
                        toast(
                            appContext,
                            "已添加「${result.meta.name}」",
                        )
                    }

                    is ImportResult.Duplicate -> {
                        // 内容完全一致（MD5 相同）：不重复导入，直接切到已有那份
                        TableStore.setActive(appContext, result.existing.id)
                        EntryRefresher.refresh()
                        toast(appContext, "内容相同，已启用「${result.existing.name}」")
                    }

                    ImportResult.Empty -> toast(appContext, "导入失败：没解析出条目")
                    ImportResult.Failed -> toast(appContext, "导入失败")
                }
            }
        }.apply { name = "wubi-table-import" }.start()
    }

    // ------------------------------------------------------------------ 方案操作（长按）

    fun showSchemeActions(context: Context, id: String) {
        val meta = TableStore.meta(context, id)
        if (meta == null) {
            XLog.w("方案已不存在：$id")
            EntryRefresher.refresh()
            return
        }
        // 长按只留「重命名 / 删除」：选中靠点条目，停用靠点原生方案
        val items = arrayOf<CharSequence>("重命名", "删除")
        show(context) { dialog ->
            dialog.setTitle(meta.name)
            dialog.setItems(items) { which ->
                if (which == 0) askRename(context, meta) else confirmDelete(context, meta)
            }
        }
    }

    private fun askRename(context: Context, meta: TableMeta) {
        val input = EditText(context).apply {
            inputType = InputType.TYPE_CLASS_TEXT
            setText(meta.name)
            setSelection(text.length)
            // 弹窗里的输入框要有内边距，否则文字贴着边框
            setPadding(48, 32, 48, 32)
            // 一弹出来就聚焦，省一次点击
            requestFocus()
        }
        val handle = show(context) { dialog ->
            dialog.setTitle("重命名方案")
            dialog.setView(input)
            dialog.setButton(0, "确定") {
                val name = input.text?.toString()?.trim().orEmpty()
                if (name.isNotEmpty() && name != meta.name) {
                    TableStore.rename(context, meta.id, name)
                    EntryRefresher.refresh()
                    toast(context, "已重命名为「$name」")
                }
            }
            dialog.setButton(2, "取消", null)
        }
        // 让软键盘直接弹出来（COUI 对话框也是 Dialog）
        (handle as? Dialog)?.window?.setSoftInputMode(
            WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_VISIBLE,
        )
    }

    private fun confirmDelete(context: Context, meta: TableMeta) {
        show(context) { dialog ->
            dialog.setTitle("删除方案")
            dialog.setView(TextView(context).apply {
                text = "确定删除「${meta.name}」？该操作不可撤销。"
                setPadding(48, 32, 48, 32)
            })
            dialog.setButton(0, "删除") {
                TableStore.delete(context, meta.id)
                EntryRefresher.refresh()
                toast(context, "已删除「${meta.name}」")
            }
            dialog.setButton(2, "取消", null)
        }
    }

    // ------------------------------------------------------------------ 工具

    private fun queryDisplayName(context: Context, uri: Uri): String {
        runCatching {
            context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                val idx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (idx >= 0 && cursor.moveToFirst()) {
                    cursor.getString(idx)?.let { return it }
                }
            }
        }.onFailure { XLog.w("读取文件名失败", it) }
        return uri.lastPathSegment?.substringAfterLast('/') ?: "自定义方案"
    }

    private fun toast(context: Context?, text: String) {
        if (context == null) return
        runCatching { Toast.makeText(context, text, Toast.LENGTH_SHORT).show() }
    }
}

/**
 * 方案/配置变化后重建设置页里的方案列表。
 *
 * 用接口隔离，避免 ui 包直接依赖某个输入法的 ime.xxx 包 ——
 * 每个适配器在安装时把「怎么重建自己那套设置页」注册进来即可。
 */
internal object EntryRefresher {

    @Volatile
    private var refresher: (() -> Unit)? = null

    fun bind(impl: () -> Unit) {
        refresher = impl
    }

    fun refresh() {
        runCatching { refresher?.invoke() }
    }
}
