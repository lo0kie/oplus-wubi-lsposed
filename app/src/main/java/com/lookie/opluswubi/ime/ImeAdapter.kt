package com.lookie.opluswubi.ime

import io.github.libxposed.api.XposedModule

/**
 * 一个输入法的适配器。
 *
 * 「多输入法隔离」的关键约束：
 *  1. 每个适配器只关心自己的包名 / 版本区间，不匹配就完全不碰目标进程；
 *  2. 每个适配器的配置、码表都存放在**各自输入法的私有目录**下（见 [storageDir]），
 *     不同输入法之间不共享任何文件，互不污染；
 *  3. 适配器内部自己决定 Hook 哪些类、注入到哪个设置页、候选怎么拼，
 *     公共层（码表解析 / 存储 / 导入 UI 骨架）只提供能力，不做假设。
 *
 * 目前只有 OPPO 小布输入法一个实现；后续接其它输入法（搜狗 / 百度 / 微信键盘 …）
 * 只需要新增一个实现类并注册进 [ImeRegistry]。
 */
interface ImeAdapter {

    /** 稳定标识，用于日志与配置命名空间。 */
    val id: String

    /** 输入法包名。 */
    val packageName: String

    /** 展示名，用于模块 UI。 */
    val displayName: String

    /**
     * 版本兼容判断。目标 App 的资源名被混淆、类名也可能变，
     * 因此只对「已验证过的版本区间」生效，避免在未知版本上乱 Hook。
     */
    fun supports(versionName: String, versionCode: Long): Boolean

    /**
     * 码表/配置的存放目录名（相对目标 App 的 filesDir）。
     * 默认按包名隔离，保证不同输入法不共用一份数据。
     */
    fun storageDirName(): String = "custom_wubi_${packageName.replace('.', '_')}"

    /** 安装全部 Hook。实现内部应自行 try/catch，任何一个 Hook 失败都不能影响输入法启动。 */
    fun install(module: XposedModule, loader: ClassLoader, versionName: String, versionCode: Long)
}
