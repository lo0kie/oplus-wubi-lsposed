package com.lookie.opluswubi.ime.oplus

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.net.Uri
import com.lookie.opluswubi.Reflect
import com.lookie.opluswubi.XLog
import com.lookie.opluswubi.hookGuarded
import com.lookie.opluswubi.ui.FilePicker
import com.lookie.opluswubi.ui.FilePickers
import io.github.libxposed.api.XposedModule

/**
 * 小布输入法的文件选择实现。
 *
 * ## 为什么不用 androidx 的 ActivityResult API
 *
 * 目标 APK 把 `androidx.fragment.app.Fragment`、`androidx.activity.ComponentActivity`、
 * `androidx.activity.result.contract.ActivityResultContracts` 这些**类名全混淆了**
 * （见 OplusSettingsHook 的表格），编译期根本没法引用，也没法继承/实现它们。
 * 所以改用最朴素、对混淆最免疫的一条路：
 *
 * 1. 从任意 Context 向上解包拿到 `Activity`，调 `startActivityForResult` 拉起 SAF 选择器；
 * 2. 提前 Hook **`com.oplus.keyboard.settings.SettingsActivity`** 类链上解析出的
 *    `onActivityResult(int, int, Intent)` 收结果 —— 这个类名没被混淆，是设置页唯一的宿主 Activity，
 *    比挂 Fragment 更明确；`Reflect.method` 会自动沿父类链找到真正会被调用的那一份实现。
 *
 * 请求码用一个不常见的值，避免和目标 App 自己的 requestCode 撞车。
 */
internal object OplusFilePicker : FilePicker {

    private const val SETTINGS_ACTIVITY = "com.oplus.keyboard.settings.SettingsActivity"

    private const val REQUEST_CODE = 0x7A31

    @Volatile
    private var pending: ((Uri?) -> Unit)? = null

    fun install(module: XposedModule, loader: ClassLoader) {
        val activityClass = Reflect.findClass(loader, SETTINGS_ACTIVITY)
        if (activityClass == null) {
            XLog.w("未找到 $SETTINGS_ACTIVITY，导入功能将不可用")
            return
        }
        val onActivityResult = Reflect.method(
            activityClass,
            "onActivityResult",
            Int::class.javaPrimitiveType!!,
            Int::class.javaPrimitiveType!!,
            Intent::class.java,
        )
        if (onActivityResult == null) {
            XLog.w("未找到 onActivityResult，导入功能将不可用")
            return
        }
        module.hookGuarded(onActivityResult) { chain ->
            val result = chain.proceed()
            val requestCode = chain.getArg(0) as? Int ?: -1
            if (requestCode == REQUEST_CODE) {
                val callback = pending
                pending = null
                val data = chain.getArg(2) as? Intent
                val uri = data?.data
                XLog.i("文件选择返回：$uri")
                callback?.let { cb -> runCatching { cb(uri) }.onFailure { XLog.w("处理选择结果失败", it) } }
            }
            result
        }
        FilePickers.bind(this)
        XLog.i("已 Hook onActivityResult（${onActivityResult.declaringClass.name}）")
    }

    override fun pick(context: Context, onPicked: (Uri?) -> Unit): Boolean {
        val activity = unwrapActivity(context)
        if (activity == null) {
            XLog.w("Context 里解包不到 Activity，无法拉起文件选择器")
            return false
        }
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "*/*"
            // 部分文件管理器把 .txt 报成 octet-stream，这里放宽 mime 兜住
            putExtra(
                Intent.EXTRA_MIME_TYPES,
                arrayOf("text/plain", "text/*", "application/octet-stream"),
            )
        }
        pending = onPicked
        return runCatching { activity.startActivityForResult(intent, REQUEST_CODE) }
            .onFailure {
                pending = null
                XLog.w("拉起文件选择器失败", it)
            }
            .isSuccess
    }

    /** ContextThemeWrapper / ContextWrapper 层层解包，找到真正的 Activity。 */
    private fun unwrapActivity(context: Context?): Activity? {
        var current: Context? = context
        var depth = 0
        while (current != null && depth++ < 10) {
            if (current is Activity) return current
            current = (current as? ContextWrapper)?.baseContext
        }
        return null
    }
}
