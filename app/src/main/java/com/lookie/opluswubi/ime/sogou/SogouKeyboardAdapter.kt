package com.lookie.opluswubi.ime.sogou

import com.lookie.opluswubi.XLog
import com.lookie.opluswubi.ime.ImeAdapter
import com.lookie.opluswubi.table.TableStore
import io.github.libxposed.api.XposedModule

/**
 * 搜狗输入法（包名 `com.sohu.inputmethod.sogou`）适配器。
 *
 * 已按 `20.17.0`（versionCode 2620）反编译验证：
 *  - 设置页是标准 `androidx.preference.PreferenceFragmentCompat`（搜狗**没有混淆 androidx**，
 *    这点和小布完全不同，所以类名/方法名都是真名，反射代码可以直接按名字找）；
 *  - 五笔设置页 = `com.sogou.imskit.feature.settings.preference.WubiSettingFragment`，
 *    由 `addPreferencesFromResource(R.xml.xxx)` 膨胀（资源名被混淆成 `r/ai/aw.xml`）；
 *  - 搜狗自己的条目控件：`com.sogou.lib.preference.SogouSwitchPreference`（继承
 *    `androidx.preference.SwitchPreferenceCompat`）、`SogouPreference`、`SogouCategory`、
 *    `SogouDividerPreference` —— 注入时直接克隆这些类，样式天然一致。
 *
 * 设置 UI（`com.sohu.inputmethod.sogou.SogouIMESettings`）与输入法本体**同进程**
 * （manifest 里没有 `android:process`），所以配置读写不需要跨进程。
 */
object SogouKeyboardAdapter : ImeAdapter {

    const val PKG = "com.sohu.inputmethod.sogou"

    /** 反编译验证过的版本。 */
    private const val VERIFIED_VERSION_NAME = "20.17.0"
    private const val VERIFIED_VERSION_CODE = 2620L

    override val id: String = "sogou_ime"

    override val packageName: String = PKG

    override val displayName: String = "搜狗输入法"

    override fun supports(versionName: String, versionCode: Long): Boolean {
        if (versionCode != VERIFIED_VERSION_CODE) {
            XLog.w(
                "搜狗输入法版本 $versionName($versionCode) 非已验证版本 " +
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
        // 让码表/配置落到这个输入法自己的私有目录（多输入法隔离）
        TableStore.storageDirName = storageDirName()

        XLog.guard("搜狗设置入口注入失败") { SogouSettingsHook.install(module, loader) }

        XLog.guard("搜狗简词排序修正安装失败") { SogouCandidateHook.install(module, loader) }

        XLog.guard("剪贴板条数上限解除失败") { SogouClipboardHook.install(module, loader) }
    }
}
