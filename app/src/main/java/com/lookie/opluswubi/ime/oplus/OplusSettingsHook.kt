package com.lookie.opluswubi.ime.oplus

import android.content.Context
import android.content.res.Configuration
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import com.lookie.opluswubi.Reflect
import com.lookie.opluswubi.XLog
import com.lookie.opluswubi.hookGuarded
import com.lookie.opluswubi.ime.ImeEnv
import com.lookie.opluswubi.table.TableStore
import com.lookie.opluswubi.ui.EntryRefresher
import com.lookie.opluswubi.ui.TableManager
import io.github.libxposed.api.XposedModule
import java.lang.ref.WeakReference
import java.lang.reflect.Method
import java.lang.reflect.Proxy
import java.util.Collections
import java.util.WeakHashMap

/**
 * 设置入口注入（小布输入法）。
 *
 * ## 注入成什么样子
 *
 * **五笔方案** 分组（`key_wubi_scheme`）里：**一个自定义方案一个条目**，与原生方案
 * （五笔86 / 98 / 新世纪）**同处一个单选列表**，底部常驻一条「添加方案…」：
 * ```
 * COUIPreferenceCategory  key_wubi_scheme
 * ├─ 五笔86 / 五笔98 / 五笔新世纪 …          （原生单选条目）
 * ├─ 虎码字词   171431 条                     ← 我们注入，同样的单选样式
 * ├─ 五笔新世纪   48213 条                     ← 我们注入
 * └─ 添加方案…  导入 txt 码表，内容相同会自动去重  ← 我们注入，永远在最后
 * ```
 * - **点条目 = 选中该方案**（radio 直接切换，和原生方案一致）
 * - **长按条目 = 二级菜单**（设为当前方案 / 重命名 / 删除 / 查看详情）
 * - **radio 互斥**：选中自定义方案后原生方案会被取消选中；选中原生方案则自动停用自定义方案
 *
 * **五笔设置** 分组（`key_wubi_setting`）里注入一条「自定义方案设置」，放模块自己的开关。
 *
 * ## 实现要点
 *
 * 1. 条目样式**克隆原生方案条目**：重建时先看分组里剩下的原生条目是哪个类，
 *    我们的条目就用同一个类（radio / 卡片样式天然一致），再反射 `setChecked`。
 * 2. **互斥自己兜**：COUI 的单选逻辑只在它自己的条目点击路径上跑，我们注入的条目
 *    点下去它管不到，所以每次重建后都显式把「非当前方案」的 radio 全部取消选中，
 *    并在原生条目每次绑定时兜一次（防止 App 自己又把原生方案勾上）。
 * 3. 长按靠两层保险：`Preference.setOnPreferenceLongClickListener`（绑定前设置）+
 *    Hook `Preference.onBindView(View)` 给条目视图直接挂 OnLongClickListener。
 *    视图是 RecyclerView 复用的，非我们的条目要把残留监听清掉。
 * 4. 三个注入点幂等：`onCreatePreferences` / `onViewCreated` / `PreferenceGroup.addPreference`
 *    （看到锚点分组被挂进偏好树时延后 200ms 重建，等 XML 子条目加完）。
 *
 * ## ⚠️ 铁律：不许直接引用目标 App 的任何类（含 androidx）
 *
 * 1. 目标 APK 混淆了 androidx 的**类名**（`PreferenceFragmentCompat` → `androidx.preference.B` …）；
 * 2. 模块自己的 ClassLoader 是 LSPosed 的 `InMemoryDexFile`，**看不到目标 App 的 dex**，
 *    直接写 `PreferenceGroup::class.java` 会在运行时抛
 *    `NoClassDefFoundError: Failed resolution of: Landroidx/preference/PreferenceGroup;`
 *    （2026-10-07 真机日志实测）。
 *
 * 因此：从目标 ClassLoader 解析出 `Class` 后**全程反射**。偏好方法从类名解析
 * （`androidx.preference.Preference` 反编译确认存在），分组方法按运行时对象的实际类去找。
 */
internal object OplusSettingsHook {

    private const val CLS_PREFERENCE = "androidx.preference.Preference"
    private const val CLS_PREFERENCE_GROUP = "androidx.preference.PreferenceGroup"

    private const val WUBI_FRAGMENT = "com.oplus.keyboard.settings.WubiInputFragment"
    private const val COUI_PREFERENCE = "com.coui.appcompat.preference.COUIPreference"

    /** 五笔方案分组：方案列表 + 「添加方案…」。 */
    private const val ANCHOR_SCHEME = "key_wubi_scheme"

    /** 五笔设置页里的「编码提示」开关：自定义方案不支持，生效时要禁掉。 */
    private const val CODE_TIP_KEY = "key_wubi_code_tip"

    /** 「四码上屏」开关的已知键名（不同版本可能不同，见 [isFourCodeKey]）。 */
    private val FOUR_CODE_KEYS = setOf("key_four_stroke")

    /** 输入设置页里「五笔输入」那条（右侧显示当前方案名）。 */
    private const val WUBI_IME_KEY = "key_wubi_ime"

    /** 上面那条的标题文本，用来确认视图没被 RecyclerView 复用。 */
    private const val WUBI_IME_TITLE = "五笔输入"

    /** 我们注入的条目的 key 前缀（也用于识别/清理上一轮的条目）。 */
    private const val KEY_PREFIX = "key_custom_wubi_"
    private const val KEY_ADD = "${KEY_PREFIX}add"
    private const val KEY_SETTINGS = "${KEY_PREFIX}settings"

    /**
     * 「自定义方案」独立分组的 key。
     *
     * 曾经把内置方案与自定义方案分成两块 panel，现已合成一块，这个 key 只用于**清掉旧版本
     * 残留在偏好树里的那个分组**（页面重建后本来也会消失，这里只是兜一层）。
     */
    private const val KEY_CUSTOM_PANEL = "${KEY_PREFIX}panel"

    private fun schemeKey(id: String) = "$KEY_PREFIX$id"

    /**
     * 从**目标 App 的 ClassLoader** 解析出来的 Preference 方法句柄。
     *
     * 参数类型都按精确签名取，避免 `Reflect.method` 的「按名字+参数个数」兜底
     * 撞上重载（例如 `setTitle(CharSequence)` 与 `setTitle(int)`）。
     */
    private class PrefApi(preference: Class<*>) {
        val preferenceClass: Class<*> = preference
        val getContext: Method? = Reflect.method(preference, "getContext")
        val getParent: Method? = Reflect.method(preference, "getParent")
        val getKey: Method? = Reflect.method(preference, "getKey")
        val getSummary: Method? = Reflect.method(preference, "getSummary")
        val setKey: Method? = Reflect.method(preference, "setKey", String::class.java)
        val setTitle: Method? = Reflect.method(preference, "setTitle", CharSequence::class.java)
        val setSummary: Method? = Reflect.method(preference, "setSummary", CharSequence::class.java)
        val setPersistent: Method? =
            Reflect.method(preference, "setPersistent", java.lang.Boolean.TYPE)
    }

    private val main = Handler(Looper.getMainLooper())

    @Volatile
    private var api: PrefApi? = null

    /** 上一轮注入的条目 key，重建前按它逐个删除。 */
    private val injectedKeys: MutableSet<String> = Collections.synchronizedSet(mutableSetOf())

    /** 最近一次注入的 Fragment，用于方案变化后重建列表。 */
    @Volatile
    private var fragmentRef: WeakReference<Any>? = null

    /** 原生方案条目的类与 key（用于克隆样式 + 判断「选中了原生方案」）。 */
    @Volatile
    private var nativeSchemeClass: Class<*>? = null

    @Volatile
    private var nativeSchemeKeys: Set<String> = emptySet()

    /** 已挂过长按监听的条目视图（RecyclerView 复用，需要知道哪些要清）。 */
    private val longClickWired: MutableMap<View, String> =
        Collections.synchronizedMap(WeakHashMap<View, String>())

    /** 「这个类没有某个监听 setter」的提醒只打一次。 */
    private val missingListenerLogged: MutableSet<String> = Collections.synchronizedSet(mutableSetOf())

    /** 诊断：已经打过绑定日志的 key。 */
    private val boundKeys: MutableSet<String> = Collections.synchronizedSet(mutableSetOf())

    /** 诊断：「五笔输入」条目文本日志打过几次。 */
    private var itemTextLogs = 0


    fun install(module: XposedModule, loader: ClassLoader) {
        val prefClass = Reflect.findClass(loader, CLS_PREFERENCE)
        if (prefClass == null) {
            XLog.e("目标 App 里找不到 $CLS_PREFERENCE，跳过设置入口注入")
            return
        }
        val resolved = PrefApi(prefClass)
        api = resolved

        val fragmentClass = Reflect.findClass(loader, WUBI_FRAGMENT)
        if (fragmentClass == null) {
            XLog.w("未找到 $WUBI_FRAGMENT，跳过 Fragment 注入点")
        } else {
            hookFragmentLifecycle(module, fragmentClass)
        }

        // 注入点 3：不依赖 Fragment 生命周期，作为最终兜底。
        hookAddPreference(module, Reflect.findClass(loader, CLS_PREFERENCE_GROUP), resolved)

        // 条目视图绑定：长按二级菜单 + 原生条目 radio 兜底互斥
        hookBindView(module, resolved)

        // 「五笔输入 → 新世纪五笔方案」这类内置方案名，直接拦 setSummary 换成自定义方案名
        hookSetSummary(module, resolved)

        // 选中原生方案时停用自定义方案
        hookNativeSchemeClick(module, resolved)

        // 文件选择：startActivityForResult + SettingsActivity.onActivityResult
        XLog.guard("小布文件选择 Hook") { OplusFilePicker.install(module, loader) }

        // 让 UI 层在方案/配置变化后重建列表（保持 ui 包不反向依赖 ime.oplus 包）
        EntryRefresher.bind { refreshEntries() }
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
                runCatching { injectFromFragment(chain.getThisObject(), "onCreatePreferences") }
                    .onFailure { XLog.w("注入设置入口失败(onCreatePreferences)", it) }
                result
            }
            XLog.i("已 Hook WubiInputFragment.onCreatePreferences")
        } else {
            XLog.w("未找到 onCreatePreferences，仅依赖其它注入点")
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
                runCatching { injectFromFragment(chain.getThisObject(), "onViewCreated") }
                    .onFailure { XLog.w("注入设置入口失败(onViewCreated)", it) }
                result
            }
            XLog.i("已 Hook WubiInputFragment.onViewCreated")
        } else {
            XLog.w("未找到 onViewCreated")
        }
    }

    // ------------------------------------------------------------ 注入点 3

    private fun hookAddPreference(module: XposedModule, groupClass: Class<*>?, a: PrefApi) {
        val target = groupClass?.let { Reflect.method(it, "addPreference", a.preferenceClass) }
        if (target == null) {
            XLog.w("未找到 PreferenceGroup.addPreference，跳过兜底注入点（另外两个注入点仍生效）")
            return
        }
        module.hookGuarded(target) { chain ->
            val result = chain.proceed()
            if (keyOf(chain.getArg(0)) == ANCHOR_SCHEME) {
                // XML 里的子条目是逐个 addPreference 进去的，等这一轮加完再重建，
                // 这样我们的条目才会稳定排在分组末尾（「添加方案…」在最后）
                main.postDelayed({
                    runCatching { refreshEntries() }.onFailure { XLog.w("兜底注入失败", it) }
                }, 200)
            }
            result
        }
        XLog.i("已 Hook PreferenceGroup.addPreference（兜底注入点）")
    }

    // ------------------------------------------------------------ 条目视图绑定

    private fun hookBindView(module: XposedModule, a: PrefApi) {
        // 目标 App 里 `Preference.onBindView` 拿不到（真机实测「未找到」），
        // 所以主路径用 public 的 `onBindViewHolder(PreferenceViewHolder)`：视图从 holder.itemView 取。
        val bound = hookOnBindViewHolder(module, a)
        if (bound) return
        val legacy = Reflect.method(a.preferenceClass, "onBindView", View::class.java)
        if (legacy == null) {
            XLog.w("既没有 onBindViewHolder 也没有 onBindView，长按二级菜单不可用")
            return
        }
        module.hookGuarded(legacy) { chain ->
            val result = chain.proceed()
            runCatching { onItemBound(chain.getThisObject(), chain.getArg(0) as? View) }
            result
        }
        XLog.i("已 Hook Preference.onBindView（长按二级菜单 + radio 互斥兜底）")
    }

    private fun hookOnBindViewHolder(module: XposedModule, a: PrefApi): Boolean {
        val target = Reflect.methodsOf(a.preferenceClass)
            .firstOrNull { it.name == "onBindViewHolder" && it.parameterTypes.size == 1 }
            ?: return false
        module.hookGuarded(target) { chain ->
            val result = chain.proceed()
            runCatching { onItemBound(chain.getThisObject(), itemViewOf(chain.getArg(0))) }
            result
        }
        XLog.i("已 Hook Preference.onBindViewHolder（长按二级菜单 + radio 互斥兜底）")
        return true
    }

    /** 从 PreferenceViewHolder 里取出条目视图（字段名可能被混淆，所以按类型找）。 */
    private fun itemViewOf(holder: Any?): View? {
        if (holder == null) return null
        if (holder is View) return holder
        var cur: Class<*>? = holder.javaClass
        while (cur != null && cur != Any::class.java) {
            val fields = runCatching { cur.declaredFields }.getOrDefault(emptyArray())
            for (field in fields) {
                if (!View::class.java.isAssignableFrom(field.type)) continue
                runCatching { field.isAccessible = true }
                (runCatching { field.get(holder) }.getOrNull() as? View)?.let { return it }
            }
            cur = cur.superclass
        }
        return null
    }

    private fun onItemBound(preference: Any?, itemView: View?) {
        if (preference == null || itemView == null) return
        val key = keyOf(preference) ?: return
        val mine = key.startsWith(KEY_PREFIX) && key != KEY_ADD
        if (!mine) {
            // 视图可能刚被我们的条目用过（RecyclerView 复用）：把点击监听还原成宿主自己的行为，
            // 否则点这条原生方案会跑去切我们那个方案
            if (longClickWired.containsKey(itemView)) restoreClick(itemView, preference)
            // 原生方案条目：自定义方案生效时不允许它同时是选中态
            if (key in nativeSchemeKeys && hasActiveCustomScheme(preference)) {
                setChecked(preference, false)
            }
            // 「五笔输入」这条右侧显示的是内置方案名（新世纪五笔方案），而且**不是 summary**
            // （实测 summary 为空，是 COUIJumpPreference 自己的 value 文本），所以只对这条
            // 精确改视图里的文本，别的一律不碰（之前无条件替换把内置方案名也改了）。
            // 自定义方案不支持编码提示：App 只认候选的 comment 字段，而 comment 非空时
            // 会顶掉候选文字，两者不可兼得 —— 所以生效期间把这个开关禁掉
            // ⚠️ 绑定过程中直接调 `setEnabled` 会抛 InvocationTargetException（视图还没就绪，
            // 真机日志：`设置条目 enabled=false 失败`），所以推到视图队列里延迟执行。
            // 另外 RecycerView 会复用条目，执行前再核对一次 key。
            if (key == CODE_TIP_KEY) {
                itemView.post {
                    if (keyOf(preference) == CODE_TIP_KEY) runCatching { disableCodeTipSwitch(preference) }
                }
            }
            // 自定义方案没有"四码唯一"这回事：模块不补，引擎也不该替它上屏 → 和编码提示一样禁掉
            if (isFourCodeKey(key)) {
                itemView.post {
                    if (isFourCodeKey(keyOf(preference).orEmpty())) {
                        runCatching { disableFourCodeSwitch(preference) }
                    }
                }
            }

            if (key == WUBI_IME_KEY) {
                val name = currentSchemeName(preference)
                if (name != null) {
                    // 实测这条的方案名是**绑定之后才异步设上去的**（绑定那一刻 TextView 还是空的），
                    // 所以即时试一次、再延迟试一次；两次都用标题「五笔输入」确认视图没被复用。
                    tryReplaceWubiImeText(itemView, name)
                    itemView.postDelayed(
                        { runCatching { tryReplaceWubiImeText(itemView, name) } },
                        350,
                    )
                }
                logItemTexts(itemView)
            }
            logBound(preference, key)
            clearLongClick(itemView)
            return
        }
        val id = key.removePrefix(KEY_PREFIX)
        if (id.isEmpty()) return
        val context = itemView.context
        // 点击 = 选中该方案。**视图层自己接管**：宿主的 COUIMarkPreference 点下去会把 radio
        // 直接 toggle（它那套单选逻辑只对自己的原生条目跑），于是点「已选中」的那条会变成
        // 取消选中。覆盖掉宿主的点击监听后，点已选中项什么都不做（原生条目也是这个行为）。
        itemView.setOnClickListener {
            runCatching { selectScheme(context, preference, id) }
                .onFailure { XLog.w("点击方案条目失败", it) }
        }
        // 长按 = 二级菜单（视图层直接挂，不依赖 Preference 自己有没有接长按）
        itemView.setOnLongClickListener {
            runCatching { TableManager.showSchemeActions(context, id) }
                .onFailure { XLog.w("打开方案二级菜单失败", it) }
            true
        }
        longClickWired[itemView] = id
    }

    /**
     * 点自定义方案条目：把 [id] 设为当前方案。
     *
     * 已经是当前方案时**什么都不做**（重建一遍等于把选中动画又播一次）；[preference] 传进来
     * 是给「Preference 层回调」那条兜底路用的 —— 万一点击还是走了宿主自己的回调（视图层监听
     * 没挂上），宿主已经把 radio toggle 掉了，这里把勾直接勾回来。
     */
    private fun selectScheme(context: Context, preference: Any, id: String) {
        if (id == TableStore.activeId(context)) {
            setChecked(preference, true)
            return
        }
        TableStore.setActive(context, id)
        XLog.i("切换自定义方案：$id")
        refreshEntries()
    }

    /**
     * 把复用来的视图上的点击监听还原成宿主自己的行为。
     *
     * 我们的条目接管过这个视图的点击，RecyclerView 把它复用给原生条目时得交回去 ——
     * `Preference` 自己就是 `View.OnClickListener`，转发给它等于原生点击。
     */
    private fun restoreClick(itemView: View, preference: Any) {
        val onClick = Reflect.method(preference.javaClass, "onClick", View::class.java)
        if (onClick == null) {
            XLog.w("视图复用：拿不到 ${preference.javaClass.simpleName}.onClick(View)，点击置空")
            itemView.setOnClickListener(null)
            return
        }
        itemView.setOnClickListener { v ->
            runCatching { Reflect.call(preference, onClick, v) }
                .onFailure { XLog.w("转发条目点击失败", it) }
        }
    }

    /** 诊断：把绑定时看到的偏好 key/摘要打一批（每个 key 一次），用来定位「五笔输入」那条。 */
    private fun logBound(preference: Any, key: String) {
        if (boundKeys.size >= 40 || !boundKeys.add(key)) return
        val summary = Reflect.call(preference, api?.getSummary)?.toString() ?: ""
        XLog.i("偏好绑定：key=$key summary='$summary' class=${preference.javaClass.simpleName}")
    }

    /**
     * 替换「五笔输入」条目右侧的内置方案名。
     *
     * 先用标题文本确认这个视图还是「五笔输入」那条（RecyclerView 会复用），
     * 再只在这个视图内部替换，避免误伤内置方案条目自己的名字。
     */
    private fun tryReplaceWubiImeText(itemView: View, schemeName: String) {
        if (!containsText(itemView, WUBI_IME_TITLE, 0)) return
        replaceTexts(itemView, schemeName, 0)
    }

    private fun containsText(view: View, text: String, depth: Int): Boolean {
        if (depth > 4) return false
        if (view is TextView) return view.text?.toString() == text
        if (view is ViewGroup) {
            for (i in 0 until view.childCount) {
                if (containsText(view.getChildAt(i), text, depth + 1)) return true
            }
        }
        return false
    }

    private fun replaceTexts(view: View, name: String, depth: Int) {
        if (depth > 4) return
        if (view is TextView) {
            val text = view.text?.toString() ?: return
            if (text != name && text.contains("五笔") && text.contains("方案")) {
                view.text = name
                XLog.i("内置方案文本「$text」→ 自定义方案名「$name」")
            }
            return
        }
        if (view is ViewGroup) {
            for (i in 0 until view.childCount) replaceTexts(view.getChildAt(i), name, depth + 1)
        }
    }

    /** 诊断：把「五笔输入」这条条目里所有 TextView 的文本打出来（前几次）。 */
    private fun logItemTexts(itemView: View) {
        if (itemTextLogs >= 3) return
        itemTextLogs++
        val texts = ArrayList<String>()
        collectTexts(itemView, texts, 0)
        XLog.i("五笔输入条目里的文本：$texts")
    }

    private fun collectTexts(view: View, out: MutableList<String>, depth: Int) {
        if (depth > 4) return
        if (view is TextView) {
            out.add("${view.id}='${view.text}'")
            return
        }
        if (view is ViewGroup) {
            for (i in 0 until view.childCount) collectTexts(view.getChildAt(i), out, depth + 1)
        }
    }

    /** 启用/禁用一个条目（禁用后置灰且点不动）。 */
    private fun setEnabled(preference: Any, enabled: Boolean) {
        val setter = Reflect.method(preference.javaClass, "setEnabled", java.lang.Boolean.TYPE)
            ?: return
        runCatching { setter.invoke(preference, enabled) }
            .onFailure { XLog.w("设置条目 enabled=$enabled 失败", it) }
    }

    /** 把「编码提示」开关禁掉、置为关，并说明原因（自定义方案生效期间）。 */
    private fun disableCodeTipSwitch(preference: Any) {
        val context = contextOf(preference) ?: return
        val custom = TableStore.activeId(context).isNotEmpty()
        // 自定义方案生效时禁用；停用后自动恢复可用（每次绑定都按当前状态设置）
        setEnabled(preference, !custom)
        if (!custom) return
        val key = keyOf(preference)
        val cleared = setChecked(preference, false) || forceBooleanPref(context, key, false)
        Reflect.call(preference, api?.setSummary, "自定义方案不支持")
        XLog.i("已禁用「编码提示」开关：key=$key（自定义方案不支持，置为关=$cleared）")
    }

    /**
     * 「四码上屏」开关：键名不同版本可能不一样，按关键字认（`four` + `stroke`/`commit`）。
     * 命中时会打日志带上真实 key，真机确认后可以收紧成固定键名。
     */
    private fun isFourCodeKey(key: String): Boolean {
        if (key in FOUR_CODE_KEYS) return true
        val k = key.lowercase()
        return k.contains("four") && (k.contains("stroke") || k.contains("commit"))
    }

    /**
     * 把「四码上屏」开关禁掉并落成关（自定义方案生效期间）。
     *
     * 自定义方案里没有"四码唯一"这回事：模块不补（见 `OplusCandidateHook.planAutoCommit`），
     * 引擎也不该替它上屏（真机：`aaaq` 在自定义方案里没有条目，引擎按内置方案上屏了「工区」）。
     * 置灰 + 说明原因与「编码提示」一致；额外把它置为关，好让引擎下次启动读到"不自动上屏"。
     */
    private fun disableFourCodeSwitch(preference: Any) {
        val context = contextOf(preference) ?: return
        val custom = TableStore.activeId(context).isNotEmpty()
        setEnabled(preference, !custom)
        if (!custom) return
        val key = keyOf(preference)
        // ⚠️ `setChecked` 会因视图复用/类不匹配而失败（真机日志里 true/false 交替），
        // 失败就直接落偏好 —— 引擎读的是这份偏好，落不下去它还照旧四码上屏。
        val cleared = setChecked(preference, false) || forceBooleanPref(context, key, false)
        Reflect.call(preference, api?.setSummary, "自定义方案不支持")
        XLog.i("已禁用「四码上屏」开关：key=$key（自定义方案不支持，置为关=$cleared）")
    }

    /**
     * 受管开关（「编码提示」「四码上屏」）在自定义方案生效时应显示的摘要。
     *
     * 不是受管条目、或自定义方案没生效时返回 null（原样放行）。用在 `setSummary` 的拦截里，
     * 保证文案不会被引擎后续的刷新刷掉。
     */
    private fun managedSwitchSummary(preference: Any?): String? {
        val key = keyOf(preference) ?: return null
        if (key != CODE_TIP_KEY && !isFourCodeKey(key)) return null
        val context = contextOf(preference) ?: return null
        if (TableStore.activeId(context).isEmpty()) return null
        return "自定义方案不支持"
    }

    /** 兜底：直接把偏好写成关（`setChecked` 不可用时用），失败返回 false。 */
    private fun forceBooleanPref(context: Context, key: String?, value: Boolean): Boolean {
        if (key.isNullOrEmpty()) return false
        return runCatching {
            context.getSharedPreferences(context.packageName + "_preferences", Context.MODE_PRIVATE)
                .edit()
                .putBoolean(key, value)
                .apply()
            true
        }.onFailure { XLog.w("写偏好 $key=$value 失败", it) }.getOrDefault(false)
    }

    /**
     * 刷新两个受管开关（「编码提示」「四码上屏」）的可用状态。
     *
     * 方案切换后界面上这两条不会重新绑定，而 `onItemBound` 里调 `setEnabled` 的时机太早
     * （真机日志：`设置条目 enabled=false 失败 | InvocationTargetException`），所以切换 / 刷新时
     * 按 key 找出来再设一次。
     */
    private fun refreshManagedSwitches(screen: Any) {
        val tip = findPreference(screen, CODE_TIP_KEY)
        if (tip != null) runCatching { disableCodeTipSwitch(tip) }
        for (key in FOUR_CODE_KEYS) {
            val pref = findPreference(screen, key) ?: continue
            runCatching { disableFourCodeSwitch(pref) }
        }
    }

    private fun clearLongClick(itemView: View) {
        if (longClickWired.remove(itemView) != null) {
            itemView.setOnLongClickListener(null)
        }
    }

    private fun hasActiveCustomScheme(preference: Any): Boolean {
        val context = contextOf(preference) ?: return false
        return TableStore.activeId(context).isNotEmpty()
    }

    /**
     * 把「xxx五笔方案」这种内置方案名换成当前自定义方案名。
     *
     * 拦 `Preference.setSummary(CharSequence)`：只要是「同时含五笔和方案」的摘要，
     * 就把入参换掉（`chain.proceed(arrayOf(name))`），这样不管这条偏好在哪个页面、
     * 谁先设置的，最终显示的都是自定义方案名。
     */
    private fun hookSetSummary(module: XposedModule, a: PrefApi) {
        val target = Reflect.method(a.preferenceClass, "setSummary", CharSequence::class.java)
        if (target == null) {
            XLog.w("未找到 Preference.setSummary，内置方案名无法替换")
            return
        }
        module.hookGuarded(target) { chain ->
            // 受管开关（编码提示 / 四码上屏）：自定义方案生效时摘要固定成「自定义方案不支持」，
            // 之后任何一方（引擎 / 框架 / 视图重建）设置的摘要都被换成它 —— 否则文案会被刷掉。
            val managed = managedSwitchSummary(chain.getThisObject())
            if (managed != null) return@hookGuarded chain.proceed(arrayOf<Any?>(managed))
            val original = chain.getArg(0)
            val name = currentSchemeName(chain.getThisObject())
            if (name != null && original is CharSequence && looksLikeBuiltinSchemeName(original)) {
                XLog.i("内置方案名「$original」→ 自定义方案名「$name」")
                return@hookGuarded chain.proceed(arrayOf<Any?>(name))
            }
            chain.proceed()
        }
        XLog.i("已 Hook Preference.setSummary（替换内置方案名）")
    }

    private fun looksLikeBuiltinSchemeName(text: CharSequence): Boolean {
        val value = text.toString()
        return value.contains("五笔") && value.contains("方案")
    }

    private fun currentSchemeName(preference: Any?): String? {
        val context = contextOf(preference) ?: return null
        return TableStore.activeMeta(context)?.name
    }

    // ------------------------------------------------------------ 原生方案互斥

    private fun hookNativeSchemeClick(module: XposedModule, a: PrefApi) {
        val target = Reflect.method(a.preferenceClass, "onClick")
        if (target == null) {
            XLog.w("未找到 Preference.onClick，无法在选中原生方案时自动停用自定义方案")
            return
        }
        module.hookGuarded(target) { chain ->
            val result = chain.proceed()
            runCatching { onPreferenceClicked(chain.getThisObject()) }
            result
        }
        XLog.i("已 Hook Preference.onClick（原生方案互斥）")
    }

    private fun onPreferenceClicked(preference: Any?) {
        if (preference == null) return
        val key = keyOf(preference) ?: return
        if (key.startsWith(KEY_PREFIX)) return
        // 只认「原生方案条目」：key 命中，或类与原生方案条目一致
        val nativeByClass = nativeSchemeClass != null && preference.javaClass == nativeSchemeClass
        if (key !in nativeSchemeKeys && !nativeByClass) return
        val context = contextOf(preference) ?: return
        if (TableStore.activeId(context).isEmpty()) return
        TableStore.setActive(context, "")
        XLog.i("选中原生方案（key=$key），已自动停用自定义方案")
        refreshEntries()
    }

    // ------------------------------------------------------------ 重建列表

    /** 方案/配置变化后重建（由 UI 层调用）。 */
    fun refreshEntries() {
        val fragment = fragmentRef?.get() ?: return
        injectFromFragment(fragment, "refresh")
    }

    private fun injectFromFragment(fragment: Any?, from: String) {
        if (fragment == null) return
        if (fragmentRef?.get() !== fragment) fragmentRef = WeakReference(fragment)

        val screen = Reflect.call(fragment, "getPreferenceScreen")
        if (screen == null) {
            XLog.w("[$from] getPreferenceScreen() 返回空，跳过本次注入")
            return
        }
        // 方案切换后这两条**不会重新绑定**，可用状态得自己刷一次（否则切到自定义方案后
        // 「编码提示」「四码上屏」还是可点）。
        refreshManagedSwitches(screen)

        val schemeGroup = resolveGroup(screen, ANCHOR_SCHEME)
        if (schemeGroup == null) {
            XLog.w("[$from] 没找到 $ANCHOR_SCHEME，跳过本次注入")
            return
        }

        // 0) 清掉过期版本留下的「自定义方案」独立分组（已改为与内置方案同组）
        findPreference(screen, KEY_CUSTOM_PANEL)?.let { legacy ->
            val parent = Reflect.call(legacy, api?.getParent) as? Any
            removePreference(parent ?: screen, legacy)
        }

        // 1) 先删掉上一轮注入的条目（按记录的 key）
        val stale = synchronized(injectedKeys) { injectedKeys.toList() }
        for (key in stale) {
            val old = findPreference(schemeGroup, key) ?: continue
            removePreference(schemeGroup, old)
        }
        injectedKeys.clear()

        // 2) 重建
        buildSchemeItems(schemeGroup, from)
    }

    /** 造自定义方案的条目（与内置方案同一个分组，排在它后面）。 */
    private fun buildSchemeItems(group: Any, from: String) {
        val context = contextOf(group) ?: run {
            XLog.w("[$from] 拿不到 Context，跳过方案列表注入")
            return
        }

        // 剩下的（不是我们注入的）就是原生方案条目：克隆它的样式，并记住它的 key
        val natives = nativeChildren(group)
        if (natives.isNotEmpty()) {
            nativeSchemeClass = natives.first().javaClass
            nativeSchemeKeys = natives.mapNotNull { keyOf(it) }.toSet()
            XLog.i("原生方案条目：class=${nativeSchemeClass?.name}，keys=$nativeSchemeKeys")
        }

        val schemes = TableStore.list(context)
        val activeId = TableStore.activeId(context)

        for (meta in schemes) {
            val key = schemeKey(meta.id)
            val pref = createPreference(context, nativeSchemeClass) ?: continue
            applyProps(pref, key, meta.name, "${meta.entryCount} 条")
            setChecked(pref, meta.id == activeId)
            attachClickListener(pref) {
                // 点条目 = 选中该方案。视图层那条监听是主路径（能拦住宿主的 toggle），
                // 这里兜「视图层没挂上」的情况（委托同一个实现）。
                runCatching { selectScheme(context, pref, meta.id) }
                    .onFailure { XLog.w("点击方案条目失败", it) }
            }
            attachLongClickListener(pref) { TableManager.showSchemeActions(context, meta.id) }
            addPreference(group, pref)
            injectedKeys.add(key)
        }

        val add = createPreference(context) ?: return
        val full = schemes.size >= TableManager.MAX_SCHEMES
        applyProps(
            add,
            KEY_ADD,
            "添加方案…",
            if (full) "已达上限 ${TableManager.MAX_SCHEMES} 个" else "导入 txt 码表",
        )
        attachClickListener(add) { TableManager.addScheme(context) }
        if (full) setEnabled(add, false)
        addPreference(group, add)
        injectedKeys.add(KEY_ADD)

        // 3) 互斥：当前方案之外的一律取消选中（含原生方案）
        enforceExclusive(group, if (activeId.isEmpty()) null else schemeKey(activeId))

        XLog.i("[$from] 已注入方案列表：${schemes.size} 个方案 + 添加方案（当前=${activeId.ifEmpty { "无" }}）")
    }

    /**
     * 单选互斥。
     *
     * [activeKey] 是当前自定义方案的 key：非 null 时把其余条目（含原生方案）全部取消选中；
     * 为 null（没有自定义方案生效）时只清掉我们自己的条目，原生方案的选中态交给 App 自己管。
     */
    private fun enforceExclusive(group: Any, activeKey: String?) {
        val count = (Reflect.call(group, "getPreferenceCount") as? Int) ?: return
        for (i in 0 until count) {
            val child = Reflect.call(group, "getPreference", i) ?: continue
            val key = keyOf(child) ?: continue
            val mine = key.startsWith(KEY_PREFIX)
            if (key == activeKey) {
                setChecked(child, true)
                continue
            }
            if (mine || activeKey != null) setChecked(child, false)
        }
    }

    /** 分组里现有的、不是我们注入的条目。 */
    private fun nativeChildren(group: Any): List<Any> {
        val count = (Reflect.call(group, "getPreferenceCount") as? Int) ?: return emptyList()
        val out = ArrayList<Any>(count)
        for (i in 0 until count) {
            val child = Reflect.call(group, "getPreference", i) ?: continue
            val key = keyOf(child)
            if (key != null && key.startsWith(KEY_PREFIX)) continue
            out.add(child)
        }
        return out
    }

    // ------------------------------------------------------------ 造条目

    private fun applyProps(pref: Any, key: String, title: String, summary: String) {
        val a = api ?: return
        Reflect.call(pref, a.setKey, key)
        Reflect.call(pref, a.setPersistent, false)
        Reflect.call(pref, a.setTitle, title)
        Reflect.call(pref, a.setSummary, summary)
    }

    /** 候选的「选中态」setter 名字（不同 COUI 版本可能不同，按顺序试）。 */
    private val CHECK_SETTER_NAMES = listOf(
        "setChecked",
        "setCheck",
        "setMark",
        "setMarked",
        "setSelected",
        "setSelect",
        "setValue",
        "setIsChecked",
        "setActive",
    )

    /** 每个类各自解析「选中态 setter」：有的类压根没有（例如普通 COUIPreference 条目）。 */
    private val checkedSetters: MutableMap<String, Method?> = Collections.synchronizedMap(HashMap())
    private val checkedSetterDumped: MutableSet<String> = Collections.synchronizedSet(mutableSetOf())

    /**
     * 设置条目选中态；返回是否成功。
     *
     * 原生方案条目是 `com.coui.appcompat.preference.COUIMarkPreference`，
     * `setChecked(boolean)` 声明在 `androidx.preference.TwoStatePreference` 上：
     * 拿非 TwoStatePreference 的实例去调会抛 `IllegalArgumentException`
     * （2026-10-07 真机踩过：「添加方案…」那种普通 COUIPreference 条目被互斥逻辑误调），
     * 所以这里先按**每个类**解析 setter，再判 `declaringClass.isInstance`。
     */
    private fun setChecked(pref: Any, checked: Boolean): Boolean {
        val setter = checkedSetters.getOrPut(pref.javaClass.name) { findCheckedSetter(pref.javaClass) }
        if (setter == null) {
            dumpSelectionSetters(pref.javaClass)
            return false
        }
        if (!setter.declaringClass.isInstance(pref)) return false
        return runCatching { setter.invoke(pref, checked) }
            .onFailure { XLog.w("设置选中态失败：${setter.name}", it) }
            .isSuccess
    }

    private fun findCheckedSetter(cls: Class<*>): Method? {
        val methods = Reflect.methodsOf(cls)
        for (name in CHECK_SETTER_NAMES) {
            methods.firstOrNull { m ->
                m.name == name && m.parameterTypes.size == 1 &&
                    m.parameterTypes[0] == java.lang.Boolean.TYPE
            }?.let { return rememberCheckedSetter(it, cls) }
        }
        // 放宽到「名字里带 check / mark / select，且只有一个 boolean 参数」的任意 setter
        methods.firstOrNull { m ->
            m.name.startsWith("set") && m.parameterTypes.size == 1 &&
                m.parameterTypes[0] == java.lang.Boolean.TYPE &&
                (m.name.contains("check", true) || m.name.contains("mark", true) ||
                    m.name.contains("select", true))
        }?.let { return rememberCheckedSetter(it, cls) }
        return null
    }

    private fun rememberCheckedSetter(setter: Method, cls: Class<*>): Method {
        runCatching { setter.isAccessible = true }
        XLog.i(
            "条目选中态：${cls.simpleName} 用 ${setter.declaringClass.simpleName}.${setter.name}(boolean)",
        )
        return setter
    }

    /** 一次性把「选中相关」的方法打进日志（每个类只打一次，方便定位正确方法）。 */
    private fun dumpSelectionSetters(cls: Class<*>) {
        if (!checkedSetterDumped.add(cls.name)) return
        val all = Reflect.methodsOf(cls)
        val related = all
            .filter {
                it.name.contains("check", true) || it.name.contains("mark", true) ||
                    it.name.contains("select", true)
            }
            .map { "${it.name}(${it.parameterTypes.joinToString { p -> p.simpleName }})" }
            .distinct()
        val booleanSetters = all
            .filter { it.name.startsWith("set") && it.parameterTypes.any { p -> p == java.lang.Boolean.TYPE } }
            .map { "${it.name}(${it.parameterTypes.joinToString { p -> p.simpleName }})" }
            .distinct()
        XLog.w(
            "${cls.simpleName} 没有可用的选中态 setter（普通条目，跳过即可）；" +
                "名字含 check/mark/select 的方法=$related；带 boolean 参数的 setter=$booleanSetters",
        )
    }

    /**
     * 造一个 Preference。默认优先 COUI 的 `COUIPreference`；
     * 传了 [kind]（原生方案条目的类）时就用它，这样单选样式与原生一致。
     */
    private fun createPreference(context: Context, kind: Class<*>? = null): Any? {
        val loader = ImeEnv.classLoader
        val cls = kind ?: loader?.let { Reflect.findClass(it, COUI_PREFERENCE) }
        if (cls != null) {
            val pref = runCatching {
                cls.getConstructor(Context::class.java).newInstance(context)
            }.onFailure { XLog.w("构造 ${cls.name} 失败", it) }.getOrNull()
            if (pref != null) return pref
        }
        if (kind != null) XLog.w("原生方案条目类不可用，回退到 COUIPreference")
        XLog.w("COUI Preference 不可用，回退到 androidx Preference")
        val prefClass = api?.preferenceClass ?: return null
        return runCatching { prefClass.getConstructor(Context::class.java).newInstance(context) }
            .onFailure { XLog.w("构造 androidx Preference 失败", it) }
            .getOrNull()
    }

    /**
     * 挂点击监听。
     *
     * `Preference.OnPreferenceClickListener` 在目标 APK 里被改名为 `androidx.preference.r`，
     * 无法在编译期实现；这里先按名字+参数个数拿到 setter，再用 Proxy 动态实现它的形参接口。
     */
    private fun attachClickListener(preference: Any, action: () -> Unit) {
        attachListener(preference, "setOnPreferenceClickListener", action)
    }

    /** 长按监听（`OnPreferenceLongClickListener` 同样被改名，走 Proxy）。 */
    private fun attachLongClickListener(preference: Any, action: () -> Unit) {
        attachListener(preference, "setOnPreferenceLongClickListener", action)
    }

    private fun attachListener(preference: Any, setterName: String, action: () -> Unit) {
        val setter = Reflect.methodByNameAndArity(preference.javaClass, setterName, 1)
        if (setter == null) {
            // 目标 App 的 COUIMarkPreference 就没有 setOnPreferenceLongClickListener，
            // 长按靠 onBindView 里挂的视图层监听兜住，所以这里每个类只提醒一次
            if (missingListenerLogged.add("$setterName@${preference.javaClass.simpleName}")) {
                XLog.w("${preference.javaClass.simpleName} 没有 $setterName（该功能走视图层监听兜底）")
            }
            return
        }
        val listenerType = setter.parameterTypes[0]
        val proxy = runCatching {
            Proxy.newProxyInstance(listenerType.classLoader, arrayOf(listenerType)) { _, method, _ ->
                // 只对接口自己的方法动作；toString/hashCode/equals 也会进这里，必须挡掉
                if (method.declaringClass == listenerType) {
                    runCatching { action() }.onFailure { XLog.w("条目 $setterName 处理异常", it) }
                }
                when (method.returnType) {
                    java.lang.Boolean.TYPE, java.lang.Boolean::class.java -> true
                    else -> null
                }
            }
        }.onFailure { XLog.w("创建 $setterName 监听失败：${listenerType.name}", it) }.getOrNull() ?: return
        runCatching { setter.invoke(preference, proxy) }
            .onFailure { XLog.w("挂载 $setterName 失败", it) }
    }

    // ------------------------------------------------------------ 反射小工具

    /**
     * 拿到锚点所在的分组：锚点本身是分组就直接用；否则用它的父分组
     * （某些版本里 `key_wubi_scheme` 可能是一条普通 Preference 而不是 Category）。
     */
    private fun resolveGroup(screen: Any, anchorKey: String): Any? {
        val anchor = findPreference(screen, anchorKey) ?: return null
        if (isGroup(anchor)) return anchor
        val parent = Reflect.call(anchor, api?.getParent)
        if (parent != null && isGroup(parent)) {
            XLog.w("$anchorKey 不是分组，改为注入它的父分组")
            return parent
        }
        XLog.w("$anchorKey 既不是分组也拿不到父分组")
        return null
    }

    private fun isGroup(obj: Any): Boolean {
        val prefClass = api?.preferenceClass ?: return false
        return Reflect.method(obj.javaClass, "addPreference", prefClass) != null
    }

    /** 分组方法按运行时对象的实际类去找（不依赖 PreferenceGroup 的类名）。 */
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
