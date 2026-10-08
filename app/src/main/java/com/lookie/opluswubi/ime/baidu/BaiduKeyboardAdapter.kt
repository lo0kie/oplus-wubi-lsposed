package com.lookie.opluswubi.ime.baidu

import com.lookie.opluswubi.XLog
import com.lookie.opluswubi.ime.ImeAdapter
import com.lookie.opluswubi.table.TableStore
import io.github.libxposed.api.XposedModule

/**
 * 百度输入法（包名 `com.baidu.input`）适配器。
 *
 * 百度**自带**自定义五笔方案（设置 → 五笔输入 → 管理五笔方案），所以这个适配器不做码表管理，
 * 只修它自己的一个 bug（见 [BaiduWubiSchemeFix]）：
 * 从其它输入法切过来时，已启用的自定义五笔方案不生效，会退回内置方案；
 * 手动切一次「拼音 → 五笔」才正常。
 *
 * 除了自动对齐，还在五笔设置页的「五笔方案」条目后面注入一条「修复五笔方案」
 * （见 [BaiduSettingsHook]），点一下就把当前方案重新下发给引擎。
 *
 * 已按 `13.3.16.2`（versionCode 1177）反编译验证。
 */
object BaiduKeyboardAdapter : ImeAdapter {

    const val PKG = "com.baidu.input"

    private const val VERIFIED_VERSION_NAME = "13.3.16.2"
    private const val VERIFIED_VERSION_CODE = 1177L

    override val id: String = "baidu_input"

    override val packageName: String = PKG

    override val displayName: String = "百度输入法"

    override fun supports(versionName: String, versionCode: Long): Boolean {
        if (versionCode != VERIFIED_VERSION_CODE) {
            XLog.w(
                "百度输入法版本 $versionName($versionCode) 非已验证版本 " +
                    "$VERIFIED_VERSION_NAME($VERIFIED_VERSION_CODE)，将按兼容模式注入",
            )
        }
        return true
    }

    override fun install(
        module: XposedModule,
        loader: ClassLoader,
        versionName: String,
        versionCode: Long,
    ) {
        TableStore.storageDirName = storageDirName()

        XLog.guard("百度五笔方案修复安装失败") { BaiduWubiSchemeFix.install(module, loader) }

        XLog.guard("百度设置入口注入失败") { BaiduSettingsHook.install(module, loader) }

        XLog.guard("剪贴板条数上限解除失败") { BaiduClipboardHook.install(module, loader) }
    }
}
