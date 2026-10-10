import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "com.lookie.opluswubi"
    compileSdk = 36
    buildToolsVersion = "36.0.0"

    defaultConfig {
        applicationId = "com.lookie.opluswubi"
        // 目标输入法自身 minSdk = 33，模块跟着对齐即可
        minSdk = 33
        targetSdk = 35
        versionCode = 2
        versionName = "1.1.0"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
        freeCompilerArgs += listOf("-Xno-param-assertions")
    }

    // 正式签名从**根目录的 `keystore.properties`** 读（该文件不入库，见 .gitignore）：
    //   storeFile=release.jks
    //   storePassword=…
    //   keyAlias=opluswubi
    //   keyPassword=…
    // 文件不存在就不创建这个 signingConfig，release 会自动退回 debug 签名。
    //
    // ⚠️ 这一段必须排在 `buildTypes` **之前**：下面 `signingConfig = signingConfigs.findByName(...)`
    // 是配置期立刻求值的，写在 `buildTypes` 后面就永远查不到，release 会静默退回 debug 签名。
    signingConfigs {
        val propsFile = rootProject.file("keystore.properties")
        if (propsFile.exists()) {
            val props = Properties()
            propsFile.inputStream().use { props.load(it) }
            create("release") {
                storeFile = rootProject.file(props.getProperty("storeFile"))
                storePassword = props.getProperty("storePassword")
                keyAlias = props.getProperty("keyAlias")
                keyPassword = props.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        debug {
            // debug 也用同一把正式签名（`keystore.properties` 在的时候）。
            // 否则本机 debug 包（Android Debug 签名）和 CI 出的 release 包（release.jks）
            // 签名不同，互相覆盖安装会 `INSTALL_FAILED_UPDATE_INCOMPATIBLE`，只能卸载重装
            // —— 2026-10-09 真机踩过。
            signingConfig = signingConfigs.findByName("release") ?: signingConfigs.getByName("debug")
        }
        release {
            // 正式版：混淆 + 资源收缩 + 非 debuggable。
            //
            // 「不含 debug 信息」靠三件事：
            //  1. `isDebuggable = false` —— manifest 里不写 android:debuggable；
            //  2. 不 keep `SourceFile` / `LineNumberTable`（见 proguard-rules.pro）—— 行号表不进包；
            //  3. `XLog.d` / `XLog.i` 用 `BuildConfig.DEBUG` 常量做条件（release 里恒 false），
            //     R8 会把整段调用连同那些日志字符串一起消掉 —— 详细日志的字面量根本不出现在包里。
            //     告警与错误（`XLog.w` / `XLog.e` / `XLog.guard`）保留：那是降级诊断，README 承诺过。
            isMinifyEnabled = true
            isShrinkResources = true
            isDebuggable = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
            signingConfig = if (signingConfigs.findByName("release") != null) {
                signingConfigs.getByName("release")
            } else {
                logger.warn(
                    "⚠️ 没找到 keystore.properties，release 包用 debug 签名 —— 只能本机测试，不可发布" +
                        "（建 key：keytool -genkeypair -keystore release.jks -alias opluswubi " +
                        "-keyalg RSA -keysize 2048 -validity 10000）",
                )
                signingConfigs.getByName("debug")
            }
        }
    }

    buildFeatures {
        // 不需要 BuildConfig：详细日志开关用的是按构建类型各放一份的 `const val`
        // （`src/debug|release/java/com/lookie/opluswubi/BuildFlags.kt`），编译期就能折掉。
        buildConfig = false
        // 模块自己的设置界面（MainActivity）用 Compose + Miuix。
        // ⚠️ 只有**模块自己进程**的界面用它；注入进宿主进程的代码一律不许碰 androidx
        //（见 AGENTS.md 绝对红线 1 与 ui/MainActivity.kt 的说明）。
        compose = true
    }

    // META-INF/xposed/* 必须原样打进 APK，不要被 resources 排除规则吃掉
    packaging {
        resources {
            pickFirsts += setOf(
                "META-INF/xposed/java_init.list",
                "META-INF/xposed/scope.list",
                "META-INF/xposed/module.prop",
            )
        }
    }

    lint {
        abortOnError = false
    }
}

dependencies {
    // ---- 现代 Xposed API (libxposed API 102) ----------------------------------
    // 运行时由 LSPosed 框架提供，因此 compileOnly，不打包进模块 APK。
    compileOnly("io.github.libxposed:api:102.0.0")

    // ---- libxposed service（**模块自己进程**用）--------------------------------
    // 界面靠它判断「模块在 LSPosed 里启用了没有」：模块启用时框架会通过
    // `io.github.libxposed.service.XposedProvider`（这个 AAR 自带，manifest 会自动合并）
    // 把一个 binder 递过来，`XposedServiceHelper` 再回调 onServiceBind / onServiceDied。
    // 这是官方 API，不需要 root，也不依赖任何输入法进程。
    // 它只依赖 `interface`，**不会**把 `api` 带进 APK（api 必须保持 compileOnly）。
    // 用 101.0.0：102.0.0 的 AAR 元数据要求 compileSdk 37，本项目是 36（AGP 8.13.1 上限），
    // 而 101.0.0 要求 36，API 完全一样（本模块本来就只用到 API 101 的能力）。
    implementation("io.github.libxposed:service:101.0.0")

    // ---- 模块自己的设置界面（只在模块进程里跑）---------------------------------
    // Miuix：小米 HyperOS 风格的 Compose 组件库（Maven Central）。
    // 只给 ui/MainActivity 这类**模块自己进程**的代码用；注入侧（ime/ 下所有代码）
    // 依然不许引用 androidx / Compose —— 那些类在宿主进程里没有对应实现。
    //
    // ⚠️ 版本要压住：0.8.0 起依赖 Compose 1.10 / kotlin-stdlib 2.3，而 0.9.x 更是
    // 拉进 androidx.compose 1.12 —— 那要求 AGP ≥ 9.1 且 compileSdk 37。本项目用
    // AGP 8.13 + Kotlin 2.2 + compileSdk 36，所以停在 0.7.2（Compose 1.9.3 / Kotlin 2.2.21）。
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("top.yukonga.miuix.kmp:miuix-android:0.7.2")
    // Miuix 把 Compose 声明成 runtime scope，编译期看不到，这里按它解析出来的版本显式补齐
    // （1.9.4 / material3 1.4.0 就是 `debugRuntimeClasspath` 里实际的版本，别乱升）。
    implementation("androidx.compose.runtime:runtime:1.9.4")
    implementation("androidx.compose.foundation:foundation:1.9.4")
    implementation("androidx.compose.ui:ui:1.9.4")
    implementation("androidx.compose.material3:material3:1.4.0")

    // ---- 目标 App 的库一律不声明依赖 -------------------------------------------
    // androidx.preference / androidx.fragment / androidx.activity 全部**故意不引入**：
    //  1) 目标 APK 混淆了它们的类名，编译期本来也引用不到几个；
    //  2) 更要命的是模块自己的 ClassLoader（LSPosed 的 InMemoryDexFile）看不到目标 App 的 dex，
    //     哪怕类名没被混淆，直接写 `PreferenceGroup::class.java` 也会在运行时抛
    //     NoClassDefFoundError（2026-10-07 真机日志实测）。
    // 所以这类类一律「从目标 ClassLoader 解析 Class 再反射调用」（见 Reflect / OplusSettingsHook）。
    // 不声明依赖，正好让误加的直接引用变成编译错误而不是运行时崩溃。
}
