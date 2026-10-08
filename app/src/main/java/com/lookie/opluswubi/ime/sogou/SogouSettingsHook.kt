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
 * 五笔设置页（`WubiSettingFragment`，资源 `r/ai/aw.xml`）原生结构：
 * ```
 * PreferenceScreen
 * ├─ SogouCategory「基本设置」
 * │   ├─ SogouSwitchPreference  wubi_hybird_input_enabled        五笔拼音混输
 * │   └─ SogouPreference        wubi_setting_plan_manager        管理五笔方案
 * ├─ SogouDividerPreference
 * ├─ SogouCategory「候选排序」
 * │   ├─ wubi_setting_user_dict / wubi_setting_smart_make_word / wubi_setting_dynamic_fm
 * ├─ SogouDividerPreference
 * ├─ SogouCategory「特殊习惯」
 * │   ├─ wubi_show_code_enabled            编码逐键提示
 * │   ├─ wubi_input_pinyin_show_code       拼音提示五笔编码
 * │   ├─ wubi_input_four_code_commit       四码唯一时自动上屏
 * │   ├─ wubi_input_five_code_commit_first 第五码将首选上屏
 * │   ├─ wubi_setting_z_wildcard           Z键作为五笔通配按键
 * │   └─ ★ 支持简词（本模块注入）★          ← 追加在「特殊习惯」末尾
 * ```
 *
 * ## 实现要点
 *
 * 1. 搜狗**没有混淆 androidx**，所以 `PreferenceFragmentCompat` / `PreferenceGroup` /
 *    `Preference` / `TwoStatePreference` 全是真名，直接按名字反射即可
 *    （小布那边是 `androidx.preference.B` 这种混淆名，只能瞎摸）。
 * 2. 开关**克隆搜狗自己的** `com.sogou.lib.preference.SogouSwitchPreference`，
 *    样式/动画与原生条目完全一致。
 * 3. 开关值不写进搜狗的偏好体系（`setPersistent(false)`），改存模块自己的
 *    `SharedPreferences`（同进程、同私有目录），避免污染搜狗的设置项、也避免被
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

    /** 五笔设置页。 */
    private const val WUBI_FRAGMENT = "com.sogou.imskit.feature.settings.preference.WubiSettingFragment"

    /** 搜狗自己的开关控件（继承 SwitchPreferenceCompat）。 */
    private const val SOGOU_SWITCH = "com.sogou.lib.preference.SogouSwitchPreference"

    /** 锚点：五笔设置页「特殊习惯」分组里的最后一条（Z键作为五笔通配按键）。 */
    private const val ANCHOR_Z_WILDCARD = "wubi_setting_z_wildcard"

    /** 兜底锚点：五笔设置页「基本设置」分组里的方案管理。 */
    private const val ANCHOR_PLAN_MANAGER = "wubi_setting_plan_manager"

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
                if (isWubiFragment(fragment)) {
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
                if (isWubiFragment(fragment)) {
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
            // 只认「特殊习惯」的最后一条：早于它触发时锚点还没加载完，
            // 会退到别的分组，导致条目先出现在错误的位置再被搬走（会闪）。
            if (key == ANCHOR_Z_WILDCARD) {
                // XML 里的子条目是逐个 addPreference 进去的，等这一轮加完再注入，
                // 这样我们的开关才会稳定排在分组末尾
                val parent = Reflect.call(child, api?.getParent)
                main.postDelayed({
                    if (parent != null && isGroup(parent)) {
                        runCatching { injectInto(parent, "addPreference") }
                            .onFailure { e -> XLog.w("兜底注入失败", e) }
                    }
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
        if (fragmentRef?.get() !== fragment) fragmentRef = WeakReference(fragment)

        val screen = Reflect.call(fragment, "getPreferenceScreen") ?: run {
            XLog.w("[$from] getPreferenceScreen() 返回空，跳过本次注入")
            return
        }
        val group = anchorGroup(screen) ?: run {
            XLog.w("[$from] 没找到锚点分组，跳过本次注入")
            return
        }
        injectInto(group, from)
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

        // 幂等：上一轮注入的那条可能挂在**别的分组**里（XML 是逐条 addPreference 进去的，
        // 早先触发时锚点还没加载完，会退到「基本设置」；后来再注入就挂到「特殊习惯」了，
        // 结果页面上出现两条）。所以这里记住「条目 + 它当时所在的分组」，按记录删。
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

    /**
     * 锚点所在的分组。
     *
     * 优先「特殊习惯」（Z键通配是它的最后一条，追加进去正好在末尾）；
     * 该条目在部分版本/机型上可能被隐藏，退到「基本设置」；再退到整个 PreferenceScreen。
     */
    private fun anchorGroup(screen: Any): Any? {
        for (key in listOf(ANCHOR_Z_WILDCARD, ANCHOR_PLAN_MANAGER)) {
            val anchor = findPreference(screen, key) ?: continue
            val parent = Reflect.call(anchor, api?.getParent)
            if (parent != null && isGroup(parent)) return parent
        }
        return if (isGroup(screen)) screen else null
    }

    private fun isGroup(obj: Any): Boolean {
        val prefClass = api?.preferenceClass ?: return false
        return Reflect.method(obj.javaClass, "addPreference", prefClass) != null
    }

    private fun isWubiFragment(obj: Any?): Boolean {
        var cur: Class<*>? = obj?.javaClass
        while (cur != null && cur != Any::class.java) {
            if (cur.name == WUBI_FRAGMENT) return true
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
