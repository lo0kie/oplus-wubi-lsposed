package com.lookie.opluswubi.ime.baidu

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.preference.CheckBoxPreference
import android.preference.Preference
import android.preference.PreferenceGroup
import com.lookie.opluswubi.Reflect
import com.lookie.opluswubi.XLog
import com.lookie.opluswubi.hookGuarded
import com.lookie.opluswubi.ime.ImeEnv
import com.lookie.opluswubi.table.ModuleConfig
import io.github.libxposed.api.XposedModule

/**
 * 设置入口注入（百度输入法）。
 *
 * ## 注入成什么样子
 *
 * 在五笔设置页的「五笔方案」条目**后面**追加一条开关「修复五笔方案」：
 * 打开（默认）时模块会在每次下发核心配置前把方案对齐并重下发；关掉就完全不碰百度的方案。
 * 从关切到开的那一刻，顺带立刻执行一次 [BaiduWubiSchemeFix.applyNow]。
 *
 * 开关用的是**百度自己的** `com.baidu.input.pref.ImeCheckBoxPreference`
 * （`extends android.preference.CheckBoxPreference`），勾选框、字体、行样式与原生条目一致。
 *
 * ## 百度设置页的模型（反编译 13.3.16.2）
 *
 * 百度用的是 **framework 的 `android.preference`**（不是 androidx），条目都继承
 * `com.baidu.input.pref.ImePreference`，构造期会跑 `ImePreference.a()`：
 *
 * ```
 * ImePreference.<init>(Context…)
 *   └─ ImePreference.a()                            // PUBLIC FINAL，构造期跑
 *        ├─ key = getKey()
 *        ├─ 在 SettingsComponent.f.b() 里找 b(key) == true 的 IPrefCustomInterceptorFactory
 *        ├─ this.a = factory.a()                    // 本页「五笔方案」命中的是 WbPrefFactory
 *        └─ this.a.f(this)                          // ← WbPref.f(Preference)
 * ```
 *
 * 所以 **`WbPref.f(Preference)` 只在「五笔方案」条目构造时被调一次**，是最准的锚点：
 * 不用猜 key、也不受子类重写影响（`a()` 由基类构造函数调用）。
 *
 * ## 实现要点
 *
 * 1. `WbPref.f()` 触发时条目还在构造中、`getParent()` 还是 null，所以先记下锚点，
 *    丢到主线程等页面装配完再取父分组（拿不到就重试若干次）。
 * 2. 百度资源里**没有任何自定义的设置行布局**（`res/layout` 里搜不到 preference/item 行布局），
 *    它那些带勾选的条目就是 `ImeCheckBoxPreference` 的默认样子；所以这里不设 `layoutResource`，
 *    用框架默认行布局，勾选框才会出来。
 * 3. 开关值不写进百度的偏好体系（`setPersistent(false)`），存模块自己的 `ModuleConfig`
 *    （与搜狗「支持简词」同一套做法），避免污染百度的设置项。
 *
 * ## ⚠️ 铁律：不许直接引用目标 App 的任何类
 *
 * `WbPref` / `ImeCheckBoxPreference` 一律从 `ImeEnv.classLoader` 解析后反射；
 * `android.preference.*` 是**系统框架**类（不是百度自带的库），可以直接用。
 */
internal object BaiduSettingsHook {

    /** 五笔方案条目的拦截器，只在五笔设置页被调。 */
    private const val CLS_WB_PREF = "com.baidu.input.pref.WbPref"

    /** 百度自己的开关条目（继承 framework 的 `android.preference.CheckBoxPreference`）。 */
    private const val CLS_IME_CHECKBOX = "com.baidu.input.pref.ImeCheckBoxPreference"

    /** 兜底：百度自己的普通条目基类（继承 framework 的 `android.preference.Preference`）。 */
    private const val CLS_IME_PREFERENCE = "com.baidu.input.pref.ImePreference"

    /** 锚点：「五笔方案」条目的 key（`WbPrefFactory.b(key)` 命中的就是它）。 */
    private const val KEY_ANCHOR = "pref_key_wb_mode_new"

    /** 我们注入的条目 key。 */
    private const val KEY_FIX = "opluswubi_baidu_fix_wb_scheme"

    private const val TITLE_FIX = "修复五笔方案"
    private const val SUMMARY_FIX = "修「切过来后方案不生效」的问题"

    /** 锚点挂进分组前最多等几次（每次 200ms）。 */
    private const val MAX_ATTEMPTS = 10

    private val main = Handler(Looper.getMainLooper())

    /** 上一次注入的条目与它当时所在的分组（用于幂等，避免页面上出现两条）。 */
    @Volatile
    private var injected: Preference? = null

    @Volatile
    private var injectedParent: PreferenceGroup? = null

    fun install(module: XposedModule, loader: ClassLoader) {
        val cls = Reflect.findClass(loader, CLS_WB_PREF)
        if (cls == null) {
            XLog.e("百度里找不到 $CLS_WB_PREF，跳过设置入口注入")
            return
        }
        val target = Reflect.method(cls, "f", Preference::class.java)
        if (target == null) {
            XLog.e("找不到 $CLS_WB_PREF.f(Preference)，跳过设置入口注入")
            return
        }
        module.hookGuarded(target) { chain ->
            val result = chain.proceed()
            runCatching { onAnchorConfigured(chain.getArg(0)) }
                .onFailure { XLog.w("准备注入「$TITLE_FIX」失败", it) }
            result
        }
        XLog.i("已 Hook WbPref.f()（五笔设置页注入「$TITLE_FIX」入口）")

        runCatching { hookAddPreference(module) }
            .onFailure { XLog.w("addPreference 注入点不可用", it) }
    }

    // ------------------------------------------------------------ 注入点 1（主）

    /**
     * 主注入点：`PreferenceGroup.addPreference(Preference)`。
     *
     * 百度设置页的条目是**逐条 addPreference 进分组**的，所以「五笔方案」被加进去的那一刻，
     * 父分组和 Context 都齐了，可以**同步**把我们的条目塞进同一个分组。
     *
     * 这比 [onAnchorConfigured] 那条（构造期触发 → 只能延迟轮询父分组）早得多：
     * 此刻页面还在装配、ListView 还没绑定，所以条目是**跟页面一起出现**的，
     * 不会出现「页面加载完才补上一条」的观感。
     */
    private fun hookAddPreference(module: XposedModule) {
        val target = Reflect.method(
            PreferenceGroup::class.java,
            "addPreference",
            Preference::class.java,
        ) ?: run {
            XLog.w("找不到 PreferenceGroup.addPreference，无法在装配期注入")
            return
        }
        module.hookGuarded(target) { chain ->
            val result = chain.proceed()
            runCatching {
                val child = chain.getArg(0) as? Preference
                val group = chain.getThisObject() as? PreferenceGroup
                if (child?.key == KEY_ANCHOR && group != null) {
                    injectInto(group)
                }
            }
            result
        }
        XLog.i("已 Hook PreferenceGroup.addPreference（装配期同步注入「$TITLE_FIX」）")
    }

    // ------------------------------------------------------------ 锚点 → 父分组

    private fun onAnchorConfigured(anchor: Any?) {
        if (anchor !is Preference) {
            XLog.w("WbPref.f() 的参数不是 android.preference.Preference，跳过注入「$TITLE_FIX」")
            return
        }
        // 此刻条目还在构造中，等页面装配完再取父分组
        main.postDelayed({ injectIntoParent(anchor, 0) }, 200)
    }

    private fun injectIntoParent(anchor: Preference, attempt: Int) {
        val parent = runCatching { anchor.parent }.getOrNull()
        if (parent == null) {
            if (attempt < MAX_ATTEMPTS) {
                main.postDelayed({ injectIntoParent(anchor, attempt + 1) }, 200)
            } else {
                XLog.w("五笔方案条目一直没挂到分组上，本次不注入「$TITLE_FIX」")
            }
            return
        }
        runCatching { injectInto(parent) }
            .onFailure { XLog.w("注入「$TITLE_FIX」失败", it) }
    }

    // ------------------------------------------------------------ 注入

    private fun injectInto(group: PreferenceGroup) {
        val context = runCatching { group.context }.getOrNull() ?: run {
            XLog.w("拿不到 Context，跳过注入「$TITLE_FIX」")
            return
        }

        // 幂等：已经在同一个分组里了就直接返回（页面重绑定时会再触发一次）
        val existing = runCatching { group.findPreference(KEY_FIX) }.getOrNull()
        if (existing != null && existing === injected) return

        // 上一轮注入的条目可能挂在别的分组里，按记录删掉
        val old = injected
        val oldParent = injectedParent
        if (old != null && oldParent != null) {
            runCatching { oldParent.removePreference(old) }
        }
        if (existing != null) {
            runCatching { group.removePreference(existing) }
        }
        injected = null
        injectedParent = null

        val pref = createPreference(context) ?: run {
            XLog.w("造百度设置条目失败，跳过注入「$TITLE_FIX」")
            return
        }
        val enabled = ModuleConfig.baiduFixEnabled(context)
        pref.key = KEY_FIX
        // 只是个开关入口，不往百度的偏好体系里写值
        pref.isPersistent = false
        pref.title = TITLE_FIX
        pref.summary = SUMMARY_FIX
        if (pref is CheckBoxPreference) pref.isChecked = enabled
        pref.setOnPreferenceClickListener { _ ->
            // 不依赖「监听器是在翻转勾选之前还是之后被调用」——直接翻转模块自己存的值，与时序无关。
            // （早先按 `!isChecked()` 推新值，真机上翻转已经发生过，于是勾着点一下还提示「已重新应用」。）
            val next = !ModuleConfig.baiduFixEnabled(context)
            runCatching { ModuleConfig.setBaiduFixEnabled(context, next) }
                .onFailure { XLog.w("写入「$TITLE_FIX」开关失败", it) }
            val message = if (!next) {
                "已关闭：模块不再干预百度的五笔方案"
            } else {
                runCatching { BaiduWubiSchemeFix.applyNow() }
                    .getOrElse {
                        XLog.w("修复五笔方案失败", it)
                        "修复失败：${it.javaClass.simpleName}"
                    }
            }
            XLog.i("「$TITLE_FIX」→ $next：$message")
            true
        }
        runCatching { group.addPreference(pref) }
            .onFailure {
                XLog.w("addPreference 失败，注入「$TITLE_FIX」未生效", it)
                return
            }
        injected = pref
        injectedParent = group

        XLog.i(
            "已注入「$TITLE_FIX」开关（进程=${ImeEnv.processName}，当前=$enabled，" +
                "分组=${group.javaClass.simpleName}，控件=${pref.javaClass.simpleName}）",
        )
    }

    /**
     * 克隆百度自己的开关条目；拿不到就退化成普通条目（至少有标题，不会崩）。
     *
     * 构造期 `a()` 会拿 `getKey()` 去匹配拦截器工厂 —— 这时还没 setKey，
     * key 是 null，匹配不上任何工厂，所以不会误挂上百度的拦截器。
     */
    private fun createPreference(context: Context): Preference? {
        val loader = ImeEnv.classLoader ?: return null
        for (name in listOf(CLS_IME_CHECKBOX, CLS_IME_PREFERENCE)) {
            val cls = Reflect.findClass(loader, name) ?: continue
            val instance = runCatching {
                cls.getConstructor(Context::class.java).newInstance(context)
            }.onFailure { XLog.w("构造 $name 失败", it) }.getOrNull()
            if (instance is Preference) {
                if (instance !is CheckBoxPreference) {
                    XLog.w("$CLS_IME_CHECKBOX 不可用，退化成普通条目（不会有勾选框）")
                }
                return instance
            }
        }
        return null
    }
}
