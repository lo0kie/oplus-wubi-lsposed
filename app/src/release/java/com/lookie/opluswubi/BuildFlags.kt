package com.lookie.opluswubi

/**
 * 详细日志开关（**release 构建**：关）。
 *
 * 关掉之后，`XLog.d` / `XLog.i` 的调用点会在**编译期**被消掉（`const val` 折成 `if (false)`），
 * 连那些日志字符串都不会进包；`XLog.w` / `XLog.e` / `XLog.guard`（降级诊断）保留。
 * 细节与理由见 `src/debug/java/com/lookie/opluswubi/BuildFlags.kt`。
 */
@PublishedApi
internal const val VERBOSE_LOG = false

/** 构建类型（**release 构建**：false）。说明见 `src/debug/.../BuildFlags.kt`。 */
internal const val DEBUG_BUILD = false
