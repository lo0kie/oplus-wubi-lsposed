package com.lookie.opluswubi.ime.sogou

import android.content.Context
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import com.lookie.opluswubi.Reflect
import com.lookie.opluswubi.XLog
import com.lookie.opluswubi.hookGuarded
import com.lookie.opluswubi.ime.ImeEnv
import com.lookie.opluswubi.table.ModuleConfig
import io.github.libxposed.api.XposedModule
import java.lang.ref.WeakReference
import java.lang.reflect.Method

/**
 * 设置入口注入（搜狗输入法）。
 *
 * ## 注入成什么样子
 *
 * 「支持简词」开关注入在**「自定义五笔方案」页**（`WubiPlanCustomFragment`，
 * 资源 `r/ai/au.xml`）的末尾。
 *
 * 迁移路径：五笔设置页「特殊习惯」分组 → 「管理五笔方案」页末尾 → **本页末尾**。
 * 一步步往「自定义方案」这个概念里收：简词是给自定义方案用的开关，
 * 就该长在自定义方案的设置页里。
 *
 * ## 不需要门槛，也不需要动态显隐
 *
 * 这一页**本身就是「自定义五笔方案」**——能被打开就说明用户已经在用它了
 * （管理页那条在 `d.g() == 0` 时根本点不进来）。所以：
 * - 不再判定「是否启用自定义方案」，进页面就注入；
 * - 也不再 Hook 方案切换 —— 本页切方案一/二/三**不改变「是不是自定义方案」**，
 *   开关本来就该一直在，没有需要显隐的时机。
 *
 * 页面结构（`WubiPlanCustomFragment`，资源 `r/ai/au.xml`）：
 * ```
 * PreferenceScreen
 * ├─ SogouTipRadioButtonPreference  wubi_input_custom_plan1        方案一
 * ├─ SogouTipRadioButtonPreference  wubi_input_custom_plan2        方案二（未导入时隐藏）
 * ├─ SogouTipRadioButtonPreference  wubi_input_custom_plan3        方案三（未导入时隐藏）
 * ├─ SogouClickLightPreference      wubi_input_add_custom_plan     导入自定义五笔方案
 * ├─ SogouDividerPreference
 * └─ SogouSwitchPreference          wubi_input_custom_with_system  兼用系统词库 ← 原生最后一条
 *     ★ 支持简词（本模块注入，追加在本页末尾）★
 * ```
 * 顶层就是 `PreferenceScreen`（不像管理页多包了一层 category），
 * 所以直接追加到 `getPreferenceScreen()` 末尾即可。
 *
 * 宿主：管理页点「自定义五笔方案」→ `WubiPlanManagerFragment$d.onPreferenceChange`
 * → `startActivityForResult(WubiPlanManagerSettings)` → `WubiPlanCustomSettings`
 * （Activity，`Z()` 里 new 出本 Fragment 并塞进参数，见其 `mI` 字段）。
 *
 * 相关字段（本页 `X()` / `n0()` / `m0()` 在用，我们**不**依赖）：
 * - `d.m()` → `pref_wubi_custom_dict_type`（单选勾哪个子方案，`m0()` 用）
 * - `d.g()` → `pref_wubi_custom_dict_has_imported_count`（导入数，`n0()` 用）
 *
 * ## 实现要点
 *
 * 1. 搜狗**没有混淆 androidx**，所以 `PreferenceFragmentCompat` / `PreferenceGroup` /
 *    `Preference` / `TwoStatePreference` 全是真名，直接按名字反射即可
 *    （小布那边是 `androidx.preference.B` 这种混淆名，只能瞎摸）。
 * 2. 开关**克隆搜狗自己的** `com.sogou.lib.preference.SogouSwitchPreference`，
 *    样式/动画与原生条目完全一致。
 * 3. 开关值不写进搜狗的偏好体系（`setPersistent(false)`），改存模块自己的
 *    `ModuleConfig`（同进程、同私有目录），避免污染搜狗的设置项、也避免被
 *    搜狗自己的 `PreferenceDataStore` 覆盖。
 * 4. 取值靠 Hook `TwoStatePreference.setChecked(boolean)`：用户点开关时 androidx
 *    一定会走到这里（`TwoStatePreference.onClick` / SwitchCompat 的
 *    OnCheckedChangeListener 两条路径都调它），我们只认自己的 key。
 *
 * ## ⚠️ 铁律（同小布）：不许直接引用目标 App 的任何类
 *
 * 模块自己的 ClassLoader 是 LSPosed 的 `InMemoryDexFile`，看不到搜狗的 dex，
 * 写 `Preference::class.java` 会在运行时抛 `NoClassDefFoundError`。
 * 所以一律「从 `param.classLoader` 解析 Class，再反射」。
 */
internal object SogouSettingsHook {

    private const val CLS_PREFERENCE = "androidx.preference.Preference"
    private const val CLS_PREFERENCE_GROUP = "androidx.preference.PreferenceGroup"
    private const val CLS_TWO_STATE = "androidx.preference.TwoStatePreference"

    /**
     * 搜狗所有设置页的基类。
     *
     * ⚠️ 搜狗**重写**了 `onCreatePreferences`（不是用 androidx 的原版），所以必须挂到这个类上：
     * ART 的方法 Hook 只拦截「解析到该类的这次调用」，子类重写后不会走父类的实现。
     * 反编译确认它的实现是 `W(rootKey)` → `X()`（`W` 由具体页面重写成
     * `addPreferencesFromResource(R.xml.xxx)`）。
     */
    private const val SOGOU_FRAGMENT_BASE = "com.sogou.lib.preference.base.AbstractSogouPreferenceFragment"

    /** 「自定义五笔方案」页 —— 「支持简词」注入在这里。 */
    private const val WUBI_PLAN_CUSTOM_FRAGMENT =
        "com.sogou.imskit.feature.settings.preference.WubiPlanCustomFragment"

    /** 搜狗自己的开关控件（继承 SwitchPreferenceCompat）。 */
    private const val SOGOU_SWITCH = "com.sogou.lib.preference.SogouSwitchPreference"

    /**
     * 自定义方案页最后一条的 key（`r/ai/au.xml`）—— 兜底注入点用它触发。
     *
     * 页面结构（`WubiPlanCustomFragment`，资源 `r/ai/au.xml`）：
     * ```
     * PreferenceScreen
     * ├─ SogouTipRadioButtonPreference  wubi_input_custom_plan1
     * ├─ SogouTipRadioButtonPreference  wubi_input_custom_plan2
     * ├─ SogouTipRadioButtonPreference  wubi_input_custom_plan3
     * ├─ SogouClickLightPreference      wubi_input_add_custom_plan
     * ├─ SogouDividerPreference         （无 key）
     * └─ SogouSwitchPreference          wubi_input_custom_with_system  ← 最后一条
     * ```
     * `wubi_input_custom_with_system`（「兼用系统词库」）是 XML 里最后一条带 key 的条目，
     * 用它当锚点能保证我们追加时页面已填完（不会中途 append 被后续条目顶到前面）。
     */
    private const val ANCHOR_LAST_KEY = "wubi_input_custom_with_system"

    /** 我们注入的开关 key。 */
    const val KEY_SIMPLE_WORD = "wubi_setting_simple_word"

    private const val TITLE_SIMPLE_WORD = "支持简词"
    private const val SUMMARY_SIMPLE_WORD = "打一码出简词（如 u → 的）"

    private val main = Handler(Looper.getMainLooper())

    private class PrefApi(preference: Class<*>) {
        val preferenceClass: Class<*> = preference
        val getKey: Method? = Reflect.method(preference, "getKey")
        val getContext: Method? = Reflect.method(preference, "getContext")
        val getParent: Method? = Reflect.method(preference, "getParent")
        val setKey: Method? = Reflect.method(preference, "setKey", String::class.java)
        val setTitle: Method? = Reflect.method(preference, "setTitle", CharSequence::class.java)
        val setSummary: Method? = Reflect.method(preference, "setSummary", CharSequence::class.java)
        val setPersistent: Method? =
            Reflect.method(preference, "setPersistent", java.lang.Boolean.TYPE)

        /**
         * 原生条目在 XML 里都写了 `app:iconSpaceReserved=false`（见 `r/ai/aw.xml`），
         * 也就是**不预留图标位**。我们是从代码 `new` 出来的，没有 AttributeSet，
         * 默认会预留图标位 → 比原生条目多缩进一截。所以运行时补一个 false。
         */
        val setIconSpaceReserved: Method? =
            Reflect.method(preference, "setIconSpaceReserved", java.lang.Boolean.TYPE)
    }

    @Volatile
    private var api: PrefApi? = null

    @Volatile
    private var fragmentRef: WeakReference<Any>? = null

    /** 上一次注入的条目与它当时所在的分组（用于幂等删除，避免页面上出现两条）。 */
    @Volatile
    private var injectedPref: Any? = null

    @Volatile
    private var injectedParent: Any? = null

    fun install(module: XposedModule, loader: ClassLoader) {
        val prefClass = Reflect.findClass(loader, CLS_PREFERENCE)
        if (prefClass == null) {
            XLog.e("搜狗里找不到 $CLS_PREFERENCE，跳过设置入口注入")
            return
        }
        api = PrefApi(prefClass)

        val fragmentClass = Reflect.findClass(loader, SOGOU_FRAGMENT_BASE)
        if (fragmentClass == null) {
            XLog.e("搜狗里找不到 $SOGOU_FRAGMENT_BASE，跳过设置入口注入")
            return
        }
        hookFragmentLifecycle(module, fragmentClass)

        // 兜底注入点：不依赖 Fragment 生命周期
        runCatching { hookAddPreference(module, loader, prefClass) }
            .onFailure { XLog.w("PreferenceGroup.addPreference 兜底注入点不可用", it) }

        // 开关取值
        runCatching { hookSetChecked(module, loader) }
            .onFailure { XLog.w("TwoStatePreference.setChecked 取值 Hook 不可用", it) }
    }

    // ------------------------------------------------------------ 注入点 1 / 2

    private fun hookFragmentLifecycle(module: XposedModule, fragmentClass: Class<*>) {
        val onCreatePreferences = Reflect.method(
            fragmentClass,
            "onCreatePreferences",
            Bundle::class.java,
            String::class.java,
        )
        if (onCreatePreferences != null) {
            module.hookGuarded(onCreatePreferences) { chain ->
                val result = chain.proceed()
                val fragment = chain.getThisObject()
                if (isCustomPlanFragment(fragment)) {
                    runCatching { inject(fragment, "onCreatePreferences") }
                        .onFailure { XLog.w("注入「$TITLE_SIMPLE_WORD」失败", it) }
                }
                result
            }
            XLog.i("已 Hook $SOGOU_FRAGMENT_BASE.onCreatePreferences")
        } else {
            XLog.w("未找到 $SOGOU_FRAGMENT_BASE.onCreatePreferences")
        }

        val onViewCreated = Reflect.method(
            fragmentClass,
            "onViewCreated",
            View::class.java,
            Bundle::class.java,
        )
        if (onViewCreated != null) {
            module.hookGuarded(onViewCreated) { chain ->
                val result = chain.proceed()
                val fragment = chain.getThisObject()
                if (isCustomPlanFragment(fragment)) {
                    runCatching { inject(fragment, "onViewCreated") }
                        .onFailure { XLog.w("注入「$TITLE_SIMPLE_WORD」失败", it) }
                }
                result
            }
            XLog.i("已 Hook $SOGOU_FRAGMENT_BASE.onViewCreated")
        } else {
            XLog.w("未找到 $SOGOU_FRAGMENT_BASE.onViewCreated")
        }
    }

    // ------------------------------------------------------------ 注入点 2（兜底）

    private fun hookAddPreference(module: XposedModule, loader: ClassLoader, prefClass: Class<*>) {
        val groupClass = Reflect.findClass(loader, CLS_PREFERENCE_GROUP) ?: return
        val target = Reflect.method(groupClass, "addPreference", prefClass) ?: return
        module.hookGuarded(target) { chain ->
            val result = chain.proceed()
            val child = chain.getArg(0)
            val key = keyOf(child)
            // 只认自定义方案页**最后一条**（`r/ai/au.xml` 里的
            // `wubi_input_custom_with_system`「兼用系统词库」）：更早触发时页面还没填完，
            // append 进去会被后续 addPreference 顶到前面去（位置会跳）。
            if (key == ANCHOR_LAST_KEY) {
                // ⚠️ 这里必须走 `inject()`（它统一做「是否自定义方案」门槛 + 取顶层
                // `getPreferenceScreen()` 追加），**不能**直接 `injectInto(child.getParent())`：
                //  - `child` 未必在顶层 `PreferenceScreen` 上，`getParent()` 拿到内层分组时
                //    插进去会落在那条分组里面而不是页面末尾；
                //  - 绕开 `inject()` 就绕开了门槛，内置方案下也会被注入（2026-10-10 真机踩过）。
                // Fragment 实例拿不到（`Preference.getFragment()` 只给类名字符串），但生命周期
                // 注入点已经把当前 Fragment 存进 `fragmentRef` 了 —— 本页 XML 加载完毕才会
                // 走到这里的 `ANCHOR_LAST_KEY`，所以那时 `fragmentRef` 一定是本页的 Fragment。
                main.postDelayed({
                    val fragment = fragmentRef?.get() ?: run {
                        XLog.w("[addPreference] fragmentRef 为空，跳过本次兜底注入")
                        return@postDelayed
                    }
                    runCatching { inject(fragment, "addPreference") }
                        .onFailure { e -> XLog.w("兜底注入失败", e) }
                }, 200)
            }
            result
        }
        XLog.i("已 Hook PreferenceGroup.addPreference（兜底注入点）")
    }

    // ------------------------------------------------------------ 取值

    private fun hookSetChecked(module: XposedModule, loader: ClassLoader) {
        val cls = Reflect.findClass(loader, CLS_TWO_STATE) ?: return
        val target = Reflect.method(cls, "setChecked", java.lang.Boolean.TYPE) ?: return
        module.hookGuarded(target) { chain ->
            val result = chain.proceed()
            val pref = chain.getThisObject()
            if (keyOf(pref) == KEY_SIMPLE_WORD) {
                val context = contextOf(pref)
                val value = chain.getArg(0) as? Boolean
                if (context != null && value != null) {
                    if (ModuleConfig.simpleWord(context) != value) {
                        ModuleConfig.setSimpleWord(context, value)
                        XLog.i("「$TITLE_SIMPLE_WORD」→ $value")
                    }
                }
            }
            result
        }
        XLog.i("已 Hook TwoStatePreference.setChecked（读「$TITLE_SIMPLE_WORD」开关值）")
    }

    // ------------------------------------------------------------ 注入

    private fun inject(fragment: Any?, from: String) {
        if (fragment == null) return
        // 兜底注入点可能被别的设置页触发，这里再确认一次页面类型
        if (!isCustomPlanFragment(fragment)) return

        if (fragmentRef?.get() !== fragment) fragmentRef = WeakReference(fragment)

        val screen = Reflect.call(fragment, "getPreferenceScreen") ?: run {
            XLog.w("[$from] getPreferenceScreen() 返回空，跳过本次注入")
            return
        }
        if (!isGroup(screen)) {
            XLog.w("[$from] PreferenceScreen 不是分组（${screen.javaClass.name}），跳过本次注入")
            return
        }
        // 追加到页面末尾：本页顶层就是 PreferenceScreen，直接加在它末尾即可
        injectInto(screen, from)
    }

    private fun injectInto(group: Any, from: String) {
        val context = contextOf(group) ?: run {
            XLog.w("[$from] 拿不到 Context，跳过本次注入")
            return
        }

        // 已经在同一个分组里了：只同步一下开关状态，不要拆了重建（会闪）
        val existing = findPreference(group, KEY_SIMPLE_WORD)
        if (existing != null && existing === injectedPref) {
            setChecked(existing, ModuleConfig.simpleWord(context))
            return
        }

        // 幂等：上一轮注入的那条可能挂在**别的分组**里（兜底注入点与生命周期注入点
        // 触发时机不同，parent 可能不一样）。这里记住「条目 + 它当时所在的分组」，按记录删。
        val old = injectedPref
        val oldParent = injectedParent
        if (old != null && oldParent != null) {
            removePreference(oldParent, old)
        }
        // 兜底：按 key 在当前分组里也清一遍
        existing?.let { removePreference(group, it) }
        injectedPref = null
        injectedParent = null

        val pref = createSwitch(context) ?: run {
            XLog.w("[$from] 造 $SOGOU_SWITCH 失败")
            return
        }
        val checked = ModuleConfig.simpleWord(context)
        applyProps(pref, KEY_SIMPLE_WORD, TITLE_SIMPLE_WORD, SUMMARY_SIMPLE_WORD)
        setChecked(pref, checked)
        addPreference(group, pref)
        injectedPref = pref
        injectedParent = group

        XLog.i("[$from] 已注入「$TITLE_SIMPLE_WORD」开关（当前=$checked，分组=${group.javaClass.simpleName}）")
    }

    private fun isGroup(obj: Any): Boolean {
        val prefClass = api?.preferenceClass ?: return false
        return Reflect.method(obj.javaClass, "addPreference", prefClass) != null
    }

    /** 是否是「自定义五笔方案」页（`WubiPlanCustomFragment`）—— 注入前必须过这一关。 */
    private fun isCustomPlanFragment(obj: Any?): Boolean {
        var cur: Class<*>? = obj?.javaClass
        while (cur != null && cur != Any::class.java) {
            if (cur.name == WUBI_PLAN_CUSTOM_FRAGMENT) return true
            cur = cur.superclass
        }
        return false
    }

    // ------------------------------------------------------------ 造条目

    private fun createSwitch(context: Context): Any? {
        val loader = ImeEnv.classLoader
        val cls = loader?.let { Reflect.findClass(it, SOGOU_SWITCH) }
        if (cls == null) {
            XLog.w("$SOGOU_SWITCH 不可用")
            return null
        }
        return runCatching { cls.getConstructor(Context::class.java).newInstance(context) }
            .onFailure { XLog.w("构造 $SOGOU_SWITCH 失败", it) }
            .getOrNull()
    }

    private fun applyProps(pref: Any, key: String, title: String, summary: String) {
        val a = api ?: return
        Reflect.call(pref, a.setKey, key)
        // 不写进搜狗的偏好体系，值由模块自己存
        Reflect.call(pref, a.setPersistent, false)
        Reflect.call(pref, a.setTitle, title)
        Reflect.call(pref, a.setSummary, summary)
        // 与原生条目对齐：不预留图标位，否则会多缩进一截
        Reflect.call(pref, a.setIconSpaceReserved, false)
    }

    private fun setChecked(pref: Any, checked: Boolean) {
        val setter = Reflect.method(pref.javaClass, "setChecked", java.lang.Boolean.TYPE) ?: return
        runCatching { setter.invoke(pref, checked) }
            .onFailure { XLog.w("设置开关初始状态失败", it) }
    }

    // ------------------------------------------------------------ 反射小工具

    private fun findPreference(group: Any, key: String): Any? {
        val method = Reflect.method(group.javaClass, "findPreference", CharSequence::class.java)
        if (method == null) XLog.w("未找到 ${group.javaClass.simpleName}.findPreference")
        return Reflect.call(group, method, key)
    }

    private fun addPreference(group: Any, child: Any) {
        val prefClass = api?.preferenceClass ?: return
        val method = Reflect.method(group.javaClass, "addPreference", prefClass)
        if (method == null) {
            XLog.w("未找到 ${group.javaClass.simpleName}.addPreference，注入失败")
            return
        }
        Reflect.call(group, method, child)
    }

    private fun removePreference(group: Any, child: Any) {
        val prefClass = api?.preferenceClass ?: return
        val method = Reflect.method(group.javaClass, "removePreference", prefClass)
        if (method == null) {
            XLog.w("未找到 ${group.javaClass.simpleName}.removePreference")
            return
        }
        Reflect.call(group, method, child)
    }

    private fun keyOf(preference: Any?): String? =
        Reflect.call(preference, api?.getKey) as? String

    private fun contextOf(preference: Any?): Context? =
        Reflect.call(preference, api?.getContext) as? Context
}
