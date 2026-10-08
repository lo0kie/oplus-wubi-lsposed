package com.lookie.opluswubi

import android.content.Context
import android.content.pm.ApplicationInfo
import com.lookie.opluswubi.ime.ImeEnv
import com.lookie.opluswubi.ime.ImeRegistry
import io.github.libxposed.api.XposedModule
import io.github.libxposed.api.XposedModuleInterface.ModuleLoadedParam
import io.github.libxposed.api.XposedModuleInterface.PackageReadyParam

/**
 * 模块入口（现代 libxposed API）。
 *
 * 与旧 API 的差别（见 LSPosed wiki "Develop Xposed Modules Using Modern Xposed API"）：
 *  - 入口由 `META-INF/xposed/java_init.list` 声明，不再用 `assets/xposed_init`；
 *  - 模块名/描述取自 `android:label` / `android:description`，作用域取自
 *    `META-INF/xposed/scope.list`，版本约束取自 `META-INF/xposed/module.prop`；
 *  - 入口继承 [XposedModule]，框架会自动 `attachFramework()`，
 *    模块不应在 [onModuleLoaded] 之前做初始化；
 *  - Hook 是 OkHttp 风格的拦截器链：`hook(executable).intercept { chain -> ... }`；
 *    本模块统一走 [hookGuarded]（装失败只降级、不中断同一 `install()` 里后面的 Hook）。
 *
 * 编译期依赖最新的 `io.github.libxposed:api:102.0.0`，但只用到 API 101 就有的能力
 * （`onModuleLoaded` / `onPackageReady` / interceptor chain），
 * 所以 `module.prop` 里声明 `minApiVersion=101` / `targetApiVersion=101`
 * —— LSPosed 2.0（lsposed-it-7598 起）已不再加载声明为 API 100 的模块。
 * 框架自身的版本号在日志里用反射读，避免调用到老版本可能没有的 getter。
 */
class WubiModule : XposedModule() {

    override fun onModuleLoaded(param: ModuleLoadedParam) {
        XLog.bind(this)
        // 进程名要在装 Hook 之前记下来：输入法往往有多个进程
        // （设置页在主进程、输入法服务在 :xxx 子进程），
        // 适配器靠它决定「这份 Hook 该装在哪个进程」。
        ImeEnv.bindProcess(param.getProcessName())
        XLog.i(
            "模块已加载 | 进程=${param.getProcessName()} | systemServer=${param.isSystemServer()} " +
                "| framework=${frameworkInfo()} | build=$BUILD_ID",
        )
    }

    /**
     * 构建标识：**每改一版就手动 +1**。
     *
     * 2026-10-07 连续三轮都在猜「用户到底装的哪一版」—— 日志里缺某行就怀疑装错包，
     * 反复来回。有一行自证的版本号，这类问题一眼就能定。
     */
    private companion object {
        const val BUILD_ID = "2026-10-08-1429"
    }

    /**
     * 包加载完成（AppComponentFactory 已建好 ClassLoader、Application 尚未创建）。
     * 这是安装 Hook 的标准时机：类都还没被初始化，能保证 Hook 到所有调用。
     */
    override fun onPackageReady(param: PackageReadyParam) {
        val packageName = param.packageName
        val info = param.applicationInfo
        val (versionName, versionCode) = readVersion(info)

        // 多输入法隔离的第一道闸：不在作用域内的包直接不碰
        val adapter = ImeRegistry.find(packageName, versionName, versionCode) ?: return

        val loader = param.classLoader
        ImeEnv.bind(this, packageName, loader, versionName, versionCode)
        XLog.i("命中输入法适配器：${adapter.displayName}（$packageName $versionName/$versionCode）")

        // 尽早把宿主 Context 拿到手：界面上的「已生效」是**当场问出来的**（PING/PONG），
        // 而应答器注册在 `ImeEnv.bindContext` 里 —— 各适配器绑定 Context 的时机不一样
        // （小布在 Application.onCreate、搜狗在候选 Hook、百度要等键盘弹出来），
        // 光靠适配器的话百度那种就会一直显示未生效。
        // 这里统一钩一次宿主的 `Application.onCreate`：那是每个进程里最早能拿到 Context 的地方。
        // 类名取自 applicationInfo、按名字从宿主 ClassLoader 解析，**不直接引用**（红线 1）。
        // 与适配器里已有的同类 Hook 重复也无所谓：`bindContext` 是幂等的。
        XLog.guard("Hook Application.onCreate（捕获宿主 Context）") {
            val appClass = Reflect.findClass(loader, info.className)
            val onCreate = appClass?.let { Reflect.method(it, "onCreate") }
            if (onCreate == null) {
                XLog.w("没找到 ${info.className}.onCreate，Context 捕获降级为按需反射")
            } else {
                hookGuarded(onCreate) { chain ->
                    val result = chain.proceed()
                    runCatching { (chain.getThisObject() as? Context)?.let { ImeEnv.bindContext(it) } }
                    result
                }
            }
        }

        // ⚠️ 必须 `catch (Throwable)`，不能用 `runCatching`（2026-10-07 百度输入法崩溃事故）：
        // 适配器安装过程中会读宿主的静态字段，可能触发类初始化，抛的是
        // `ExceptionInInitializerError` / `NoClassDefFoundError` —— 它们是 **`Error` 不是 `Exception`**。
        // `runCatching` 同样只包 `Exception`，这类 `Error` 会直接穿过 `onPackageReady` 抛回框架，
        // 结果就是**输入法一启动就崩**（dropbox 栈：
        // `onPackageReady → BaiduClipboardHook.install → syncPref → Reflect.staticField`）。
        //
        // 模块的定位是「锦上添花」，绝不能因为自己出错就把用户的输入法弄崩。
        try {
            adapter.install(this, loader, versionName, versionCode)
        } catch (e: Throwable) {
            XLog.e("适配器安装失败（已忽略，不影响宿主启动）：${adapter.id}", e)
        }
    }

    /**
     * 读目标包的版本号。
     *
     * 注意：`ApplicationInfo.versionName / versionCode` 在 API 35 的编译期 stub 里已经被移除
     * （编译会直接报 unresolved），但设备运行时字段依然存在，所以这里用反射读，
     * 既保证能编译，又保证真机拿到真实版本。
     */
    private fun readVersion(info: ApplicationInfo): Pair<String, Long> {
        val name = Reflect.fieldValue(info, info.javaClass, "versionName") as? String ?: ""
        val code = (Reflect.fieldValue(info, info.javaClass, "longVersionCode") as? Number)?.toLong()
            ?: (Reflect.fieldValue(info, info.javaClass, "versionCode") as? Number)?.toLong()
            ?: 0L
        return name to code
    }

    /**
     * 框架名与版本。
     *
     * 这三个都是官方 API 101 就有的 getter，`XposedModule` 继承自 `XposedInterfaceWrapper`
     * 时已经实现，直接调用即可 —— `module.prop` 里的 `minApiVersion=101` 就是版本闸门，
     * 框架低于 101 根本不会加载本模块，不存在「getter 可能没有」的版本区间。
     */
    private fun frameworkInfo(): String =
        "${getFrameworkName()} ${getFrameworkVersion()} (api=${getApiVersion()})"
}
