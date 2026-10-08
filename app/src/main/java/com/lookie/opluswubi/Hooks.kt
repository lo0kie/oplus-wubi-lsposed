package com.lookie.opluswubi

import io.github.libxposed.api.XposedInterface
import io.github.libxposed.api.XposedModule
import java.lang.reflect.Executable

/**
 * Hook 安装的统一收口。
 *
 * ## 为什么需要它
 *
 * 「装 Hook」这一步本身就会抛。官方 API（`api-102.0.0` 的 `HookBuilder.intercept`）声明了两类：
 *  - `IllegalArgumentException` —— `origin` 是框架内部方法、`Constructor.newInstance`，或 hooker 非法；
 *  - `HookFailedError` —— 框架内部错误导致这次装上不去。
 *
 * 裸调用的话，一抛出去就是**同一个 `install()` 里排在它后面的 Hook 全装不上** —— 一个可选功能把
 * 整条链路带走（`ImeAdapter.install` 内部本来就不该整体中断）。这正是 AGENTS.md「五、降级纪律」
 * 第 1 条要求「每一步 Hook 都必须包住」的原因，也是这个函数存在的唯一理由。
 *
 * ## 它只包「装」，不包「用」
 *
 *  - 装失败 → 写一条说清「哪个类、哪个方法」的告警后返回 null，调用方的后续 Hook 照常继续；
 *  - 装成功 → hooker 内部抛出的异常依旧交给框架按 `exceptionMode` 处理（框架默认 protective），
 *    本函数**不**吞运行期异常，也不改变 `chain.proceed()` 的语义。
 *
 * 调用点因此不必再各自套 `try/catch`，也不会因为漏包一层就退化成整段安装中断。
 * `origin` 收成可空是刻意的：调用方查类/查方法普遍返回可空类型，收口这一层兜住 null
 * 比让每个调用点都写一遍判空更省事，也保证「查不到」永远只是一条日志。
 */
fun XposedModule.hookGuarded(
    origin: Executable?,
    hooker: XposedInterface.Hooker,
): XposedInterface.HookHandle? {
    if (origin == null) {
        XLog.w("Hook 目标为 null，本次跳过（调用方应已打过更具体的原因）")
        return null
    }
    return try {
        hook(origin).intercept(hooker)
    } catch (e: Throwable) {
        // Throwable 而非 Exception：框架侧失败有可能是 Error 系，一并吞掉只降级不扩散
        XLog.w("装 Hook 失败（已跳过，后续 Hook 继续）：${origin.declaringClass.name}#${origin.name}", e)
        null
    }
}
