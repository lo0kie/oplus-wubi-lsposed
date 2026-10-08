package com.lookie.opluswubi.ime

import com.lookie.opluswubi.XLog
import com.lookie.opluswubi.ime.baidu.BaiduKeyboardAdapter
import com.lookie.opluswubi.ime.oplus.OplusKeyboardAdapter
import com.lookie.opluswubi.ime.sogou.SogouKeyboardAdapter

/**
 * 适配器注册表。
 *
 * 新增输入法支持时，只要在 [adapters] 里追加一个实现即可，
 * 不需要改动任何公共逻辑。
 */
object ImeRegistry {

    private val adapters: List<ImeAdapter> = listOf(
        OplusKeyboardAdapter,
        SogouKeyboardAdapter,
        BaiduKeyboardAdapter,
    )

    fun find(packageName: String, versionName: String, versionCode: Long): ImeAdapter? {
        val adapter = adapters.firstOrNull { it.packageName == packageName } ?: return null
        if (!adapter.supports(versionName, versionCode)) {
            XLog.w(
                "已注册输入法 ${adapter.displayName} 但版本不匹配：$versionName($versionCode)，" +
                    "本次不注入（请在 ImeAdapter.supports 里确认后再放开）",
            )
            return null
        }
        return adapter
    }

    fun all(): List<ImeAdapter> = adapters
}
