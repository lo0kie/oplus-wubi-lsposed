package com.lookie.opluswubi.ime.oplus

import android.content.res.Resources
import com.lookie.opluswubi.Reflect
import com.lookie.opluswubi.XLog
import com.lookie.opluswubi.hookGuarded
import com.lookie.opluswubi.ime.ImeEnv
import com.lookie.opluswubi.table.ModuleConfig
import io.github.libxposed.api.XposedModule

/**
 * 解除剪贴板历史条数上限（小布输入法）。
 *
 * ## 上限在哪
 *
 * 小布的剪贴板是 Room 表 `tab_clipboard_data`，入库路径
 * `com.oplus.keyboard.db.dao.k`（jadx 里的 `C0829k`，Room 生成的合成 lambda）的 case 2 里写着：
 *
 * ```java
 * long id = clipboardData.clipboard_id;
 * b existing = queryById(id);                                  // 这条是不是已经在库里
 * if (count() == 500 && existing == null && (oldest = oldestOne()) != null) {
 *     delete(oldest);                                          // ← 满了就删最旧一条
 * }
 * insertOrUpdate(clipboardData);
 * ```
 *
 * 也就是**最多留 500 条**（`db/dao/C0829k.java:44`）。
 *
 * ## 为什么改「取最旧一条」而不是改那个 500
 *
 * - 改计数（`db/dao/j` 的 case 1）要伪造 `SELECT COUNT(*)` 的结果，风险是别的调用方也读它；
 * - 改删除分支（`C0829k` 的 case 1）**会连用户手动删除一起堵掉** ——
 *   `ClipboardManager$deleteClipboardData$1` 删单条走的也是同一个 case 1；
 * - 而「取最旧一条」那个查询（真实类名 `com.oplus.keyboard.db.dao.m`，case 1）
 *   **全仓库只有这一处调用**（`C0829k.java:44`），让它返回 null，裁剪条件 `oldest != null`
 *   自然不成立 —— 不动计数、不动手动删除，副作用最小。
 *
 * ## 为什么可以不走 `chain.proceed()`
 *
 * 那条查询的结果只被这一处 `!= null` 用；事务的 begin/end 由调用方
 * `androidx.room.util.a.k(...)` 负责，跳过 lambda 体不会破坏事务。
 */
internal object OplusClipboardHook {

    /** 「取最旧一条」的查询 lambda（jadx 名 `C0831m`，真实类名 `m`）。 */
    private const val CLS_OLDEST_LAMBDA = "com.oplus.keyboard.db.dao.m"

    /** 合成 lambda 的 case 选择字段（jadx 名 `f11989a`，真实名 `a`）。 */
    private const val FIELD_CASE = "a"

    /** `C0831m` 里 case 1 = `ORDER BY clipboard_time ASC LIMIT 1`（取最旧）。 */
    private const val CASE_OLDEST = 1

    /** 剪贴板面板上「条数上限」的显示值（写死在 `ClipBoardView` 的 `tvCount` 里）。 */
    private const val DISPLAYED_LIMIT = "500"

    /** 替换成实际上限，与另外三个输入法保持一致。 */
    private const val REAL_LIMIT = "100000"

    @Volatile
    private var logged = false

    @Volatile
    private var textLogged = false

    fun install(module: XposedModule, loader: ClassLoader) {
        val cls = Reflect.findClass(loader, CLS_OLDEST_LAMBDA) ?: run {
            XLog.w("找不到 $CLS_OLDEST_LAMBDA，剪贴板条数上限保持原样（不影响其它功能）")
            return
        }
        val caseField = Reflect.field(cls, FIELD_CASE) ?: run {
            XLog.w("找不到 $CLS_OLDEST_LAMBDA.$FIELD_CASE，剪贴板条数上限保持原样")
            return
        }
        val invoke = Reflect.method(cls, "invoke", Any::class.java) ?: run {
            XLog.w("找不到 $CLS_OLDEST_LAMBDA.invoke，剪贴板条数上限保持原样")
            return
        }
        module.hookGuarded(invoke) { chain ->
            val case = runCatching { caseField.getInt(chain.getThisObject()) }.getOrDefault(-1)
            if (case == CASE_OLDEST && clipboardUnlimited()) {
                if (!logged) {
                    logged = true
                    XLog.i("已解除剪贴板条数上限：跳过「取最旧一条」查询（原本 500 条就删最旧）")
                }
                // 返回 null：调用处 `oldest != null` 不成立 → 不触发裁剪；结果只被这一处使用
                return@hookGuarded null
            }
            chain.proceed()
        }
        XLog.i("已 Hook $CLS_OLDEST_LAMBDA.invoke（受「解除剪贴板条数上限」开关控制）")

        runCatching { hookLimitText(module) }
            .onFailure { XLog.w("剪贴板面板上限文案改写不可用", it) }
    }

    /** 读模块设置里的开关；拿不到 Context 时按「开」处理（维持原行为，别无声地把功能关掉）。 */
    private fun clipboardUnlimited(): Boolean {
        val ctx = ImeEnv.context() ?: return true
        return ModuleConfig.clipboardUnlimited(ctx)
    }

    /**
     * 面板上的上限文案。
     *
     * `ClipBoardView` 的 `tvCount` 直接写死 `getString(R.string.clip_length, 当前条数, 500)`，
     * 这个 500 和 DB 层那个裁剪阈值是**两份互不相干**的常量，所以掐掉裁剪后文案不会自己变。
     *
     * 拿不到 `R.string.clip_length` 的资源 id（类名被混淆、R 字段被内联），所以在**渲染结果**上认：
     * 只重写含 `/500` 的文案，其余原样放行。`Resources.getString(int, Object…)` 是带格式参数的
     * 重载，调用频率远低于无参的 `getString(int)`，代价可以接受。
     */
    private fun hookLimitText(module: XposedModule) {
        val target = Reflect.method(
            Resources::class.java,
            "getString",
            Integer.TYPE,
            Array<Any>::class.java,
        ) ?: run {
            XLog.w("找不到 Resources.getString(int, Object[])，面板上限文案保持原样")
            return
        }
        module.hookGuarded(target) { chain ->
            val result = chain.proceed() as? String
            if (result == null || !result.contains("/$DISPLAYED_LIMIT")) return@hookGuarded result
            // 开关关掉 → 文案保持原样（还是 /500）
            if (!clipboardUnlimited()) return@hookGuarded result
            val replaced = result.replace("/$DISPLAYED_LIMIT", "/$REAL_LIMIT")
            if (!textLogged) {
                textLogged = true
                XLog.i("剪贴板面板上限文案：'$result' -> '$replaced'")
            }
            replaced
        }
        XLog.i("已 Hook Resources.getString(int, Object[])（面板上限文案改为 $REAL_LIMIT）")
    }
}
