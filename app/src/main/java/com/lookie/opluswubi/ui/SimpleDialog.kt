package com.lookie.opluswubi.ui

import android.app.AlertDialog
import android.content.Context
import android.content.DialogInterface
import android.view.View
import com.lookie.opluswubi.Reflect
import com.lookie.opluswubi.XLog
import com.lookie.opluswubi.ime.ImeEnv

/**
 * 对话框抽象。
 *
 * 目标是「UI 模式符合原设计」，所以优先使用输入法自带的 COUI 对话框
 * （`com.coui.appcompat.dialog.COUIAlertDialogBuilder`）——它继承自
 * `AlertDialog.Builder`，弹出来的样式、圆角、按钮排布、深色模式都和输入法原生弹窗一致。
 * 万一某个版本里 COUI 类不可用，再回退到 framework 的 [AlertDialog.Builder]，
 * 功能不受影响。
 */
internal interface SimpleDialog {

    fun setTitle(title: CharSequence)

    fun setItems(items: Array<CharSequence>, onClick: (Int) -> Unit)

    fun setView(view: View)

    /** which: 0=Positive 1=Neutral 2=Negative */
    fun setButton(which: Int, text: CharSequence, onClick: (() -> Unit)?)

    fun show(): Any?
}

internal object DialogFactory {

    private const val COUI_BUILDER = "com.coui.appcompat.dialog.COUIAlertDialogBuilder"

    fun create(context: Context): SimpleDialog {
        val loader = ImeEnv.classLoader
        if (loader != null) {
            val cls = Reflect.findClass(loader, COUI_BUILDER)
            val instance = cls?.let {
                runCatching { it.getConstructor(Context::class.java).newInstance(context) }.getOrNull()
            }
            if (cls != null && instance != null) return CouiDialog(instance, cls)
        }
        XLog.w("COUIAlertDialogBuilder 不可用，回退到 framework AlertDialog")
        return FrameworkDialog(context)
    }

    fun dismiss(handle: Any?) {
        if (handle == null) return
        if (handle is DialogInterface) {
            runCatching { handle.dismiss() }
            return
        }
        runCatching { Reflect.call(handle, "dismiss") }
    }
}

/** COUI 对话框：全部通过反射调用，兼容继承自 AlertDialog.Builder 的方法。 */
private class CouiDialog(private val builder: Any, private val cls: Class<*>) : SimpleDialog {

    override fun setTitle(title: CharSequence) {
        call("setTitle", arrayOf(CharSequence::class.java), title)
    }

    override fun setItems(items: Array<CharSequence>, onClick: (Int) -> Unit) {
        call(
            "setItems",
            arrayOf(Array<CharSequence>::class.java, DialogInterface.OnClickListener::class.java),
            items,
            DialogInterface.OnClickListener { _, which -> onClick(which) },
        )
    }

    override fun setView(view: View) {
        call("setView", arrayOf(View::class.java), view)
    }

    override fun setButton(which: Int, text: CharSequence, onClick: (() -> Unit)?) {
        val name = when (which) {
            0 -> "setPositiveButton"
            1 -> "setNeutralButton"
            else -> "setNegativeButton"
        }
        val listener = onClick?.let { DialogInterface.OnClickListener { _, _ -> it() } }
        call(name, arrayOf(CharSequence::class.java, DialogInterface.OnClickListener::class.java), text, listener)
    }

    /**
     * 显示并返回**对话框实例**（不是 builder）。
     *
     * 优先 `create()` 再 `show()`：这样返回的句柄一定支持 `dismiss()`，
     * 调用方才能「先关旧的再开新的」，避免弹窗越点越多。
     */
    override fun show(): Any? {
        val created = runCatching { cls.getMethod("create").invoke(builder) }.getOrNull()
        if (created != null) {
            runCatching { Reflect.call(created, "show") }
                .onFailure { XLog.w("COUI 对话框显示失败", it) }
            return created
        }
        return runCatching { cls.getMethod("show").invoke(builder) }
            .onFailure { XLog.w("COUI 对话框 show 失败", it) }
            .getOrNull()
    }

    private fun call(name: String, params: Array<Class<*>>, vararg args: Any?) {
        val method = Reflect.method(cls, name, *params) ?: run {
            XLog.w("COUI 对话框缺少方法 $name(${params.joinToString { it.simpleName }})")
            return
        }
        runCatching { method.invoke(builder, *args) }
            .onFailure { XLog.w("COUI 对话框 $name 调用失败", it) }
    }
}

/** framework 兜底对话框。 */
private class FrameworkDialog(context: Context) : SimpleDialog {

    private val builder = AlertDialog.Builder(context)

    override fun setTitle(title: CharSequence) {
        builder.setTitle(title)
    }

    override fun setItems(items: Array<CharSequence>, onClick: (Int) -> Unit) {
        builder.setItems(items) { _, which -> onClick(which) }
    }

    override fun setView(view: View) {
        builder.setView(view)
    }

    override fun setButton(which: Int, text: CharSequence, onClick: (() -> Unit)?) {
        val listener = DialogInterface.OnClickListener { _, _ -> onClick?.invoke() }
        when (which) {
            0 -> builder.setPositiveButton(text, listener)
            1 -> builder.setNeutralButton(text, listener)
            else -> builder.setNegativeButton(text, listener)
        }
    }

    override fun show(): Any? = runCatching { builder.show() }.getOrNull()
}
