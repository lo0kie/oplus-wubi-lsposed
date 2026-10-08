package com.lookie.opluswubi.ui

import android.content.Context
import android.net.Uri

/**
 * 文件选择能力。
 *
 * 各个输入法的宿主不一样（Fragment / Activity 基类可能被混淆、可用 API 也不同），
 * 所以这里只定义契约，由具体输入法适配器提供实现（见 `ime/oplus/OplusFilePicker`）。
 */
internal interface FilePicker {

    /**
     * 拉起系统文件选择器。
     *
     * @param context 任意 Context（内部会向上解包到 Activity）
     * @param onPicked 结果回调；用户取消时回调 null
     * @return 是否成功拉起
     */
    fun pick(context: Context, onPicked: (Uri?) -> Unit): Boolean
}

internal object FilePickers {

    @Volatile
    private var impl: FilePicker? = null

    fun bind(picker: FilePicker) {
        impl = picker
    }

    fun pick(context: Context, onPicked: (Uri?) -> Unit): Boolean =
        impl?.pick(context, onPicked) ?: false
}
