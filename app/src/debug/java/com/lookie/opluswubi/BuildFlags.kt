package com.lookie.opluswubi

/**
 * 详细日志开关（**debug 构建**：开）。
 *
 * 为什么要用「按构建类型各放一份的 `const val`」而不是 `BuildConfig.DEBUG`：
 * `const val` 是**编译期常量**，Kotlin 编译器就会把 `if (VERBOSE_LOG)` 折成 `if (true/false)`
 * 并把整个分支连同里面的日志字符串一起消掉 —— 正式包里连字面量都不存在。
 * 而 `BuildConfig.DEBUG` 只有 R8 才会折，可本项目的 proguard 规则里有
 * `-keep class com.lookie.opluswubi.** { *; }`（刻意保留自己的类名），`-keep` 会连**优化**一起关掉，
 * 死分支因此原样留在包里 —— 实测 release 包里仍能 grep 到「已 Hook 设置 KV 读写」这类详细日志。
 *
 * 改这个值时，**两份文件都要改**（`src/debug` 与 `src/release` 各一份，类名/常量名必须一致）。
 */
@PublishedApi
internal const val VERBOSE_LOG = true

/**
 * 构建类型（**debug 构建**：true）。
 *
 * 界面「关于 → 版本」那一行的子标题要用它显示 `debug` / `release` ——
 * 用户经常两个包都装着，光看版本号分不出手上这个是哪个。
 * 和 [VERBOSE_LOG] 一样是 `const val`，编译期就定死，改的时候两份文件都要改。
 */
internal const val DEBUG_BUILD = true
