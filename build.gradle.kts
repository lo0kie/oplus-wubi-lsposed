plugins {
    id("com.android.application") version "8.13.1" apply false
    id("org.jetbrains.kotlin.android") version "2.2.20" apply false
    // Compose 编译器：Kotlin 2.x 起它是独立插件，版本必须跟 Kotlin 一致
    id("org.jetbrains.kotlin.plugin.compose") version "2.2.20" apply false
}
