package com.lookie.opluswubi.table

import android.content.Context
import com.lookie.opluswubi.XLog
import org.json.JSONObject
import java.io.File
import java.io.InputStream
import java.security.MessageDigest
import java.util.UUID

/** 一份已导入码表的元信息。 */
data class TableMeta(
    val id: String,
    val name: String,
    val entryCount: Int,
    val sourceName: String,
    val importedAt: Long,
    val sizeBytes: Long,
    /** 规范化内容的 MD5（每行 `编码\t词` 的字节流），用于「同一个方案不重复导入」。 */
    val md5: String = "",
) {
    fun toJson(): String = JSONObject().apply {
        put("id", id)
        put("name", name)
        put("entryCount", entryCount)
        put("sourceName", sourceName)
        put("importedAt", importedAt)
        put("sizeBytes", sizeBytes)
        put("md5", md5)
    }.toString()

    companion object {
        fun fromJson(text: String): TableMeta? = runCatching {
            val o = JSONObject(text)
            TableMeta(
                id = o.getString("id"),
                name = o.optString("name", o.getString("id")),
                entryCount = o.optInt("entryCount", 0),
                sourceName = o.optString("sourceName", ""),
                importedAt = o.optLong("importedAt", 0L),
                sizeBytes = o.optLong("sizeBytes", 0L),
                md5 = o.optString("md5", ""),
            )
        }.getOrNull()
    }
}

/** 导入结果：区分「新增成功」「内容完全一致（MD5 撞上已有方案）」「解析不出条目」「写入失败」。 */
sealed class ImportResult {
    data class Added(val meta: TableMeta) : ImportResult()
    data class Duplicate(val existing: TableMeta) : ImportResult()
    object Empty : ImportResult()
    object Failed : ImportResult()
}

/**
 * 码表仓库：负责落盘、枚举、删除、当前激活表，以及内存索引缓存。
 *
 * 所有文件都写在**目标输入法自己的私有目录**下（`filesDir/<storageDirName>`），
 * 因此不同输入法之间天然隔离，也不需要任何跨进程/跨应用权限。
 */
object TableStore {

    private const val META_SUFFIX = ".json"
    private const val DATA_SUFFIX = ".tbl"

    /** 由适配器注入的目录名；未设置时用一个通用名兜底。 */
    @Volatile
    var storageDirName: String = "custom_wubi"

    // ---------------------------------------------------------------- 路径

    fun root(context: Context): File =
        File(context.filesDir, storageDirName).apply { if (!exists()) mkdirs() }

    fun tablesDir(context: Context): File =
        File(root(context), "tables").apply { if (!exists()) mkdirs() }

    fun metaDir(context: Context): File =
        File(root(context), "meta").apply { if (!exists()) mkdirs() }

    fun tableFile(context: Context, id: String): File = File(tablesDir(context), id + DATA_SUFFIX)

    fun metaFile(context: Context, id: String): File = File(metaDir(context), id + META_SUFFIX)

    // ---------------------------------------------------------------- 枚举

    fun list(context: Context): List<TableMeta> =
        metaDir(context).listFiles { f -> f.isFile && f.name.endsWith(META_SUFFIX) }
            ?.mapNotNull { runCatching { TableMeta.fromJson(it.readText()) }.getOrNull() }
            ?.sortedBy { it.importedAt }
            ?: emptyList()

    fun meta(context: Context, id: String): TableMeta? {
        val f = metaFile(context, id)
        if (!f.isFile) return null
        return runCatching { TableMeta.fromJson(f.readText()) }.getOrNull()
    }

    /** 按内容 MD5 找已有方案（用于导入去重）。 */
    fun findByMd5(context: Context, md5: String): TableMeta? {
        if (md5.isEmpty()) return null
        return list(context).firstOrNull { it.md5.isNotEmpty() && it.md5 == md5 }
    }

    // ---------------------------------------------------------------- 导入 / 删除

    /**
     * 解析并导入一份码表。整个过程同步执行，调用方需要放到工作线程。
     *
     * 去重规则：把解析结果规范化成 `编码\t词` 后算 MD5，与已有方案比对；
     * 只要内容完全一致就直接丢弃，不再产生第二份（与方案名、文件名无关）。
     */
    fun import(context: Context, displayName: String, sourceName: String, input: InputStream): ImportResult {
        val parsed = CodeTable.parse(input)
        if (parsed.entries.isEmpty()) {
            XLog.w("导入失败：没有解析出任何有效条目（总行 ${parsed.totalLines}）")
            return ImportResult.Empty
        }
        val id = UUID.randomUUID().toString().replace("-", "").take(12)
        val dataFile = tableFile(context, id)
        val tmp = File(dataFile.parentFile, id + ".tmp")
        try {
            tmp.bufferedWriter(Charsets.UTF_8).use { w ->
                for ((code, word) in parsed.entries) {
                    w.append(code).append('\t').append(word).append('\n')
                }
            }
            if (!tmp.renameTo(dataFile)) {
                tmp.copyTo(dataFile, overwrite = true)
                tmp.delete()
            }
        } catch (t: Throwable) {
            XLog.e("写入码表失败", t)
            tmp.delete()
            return ImportResult.Failed
        }

        val md5 = runCatching { md5Of(dataFile) }.getOrElse {
            XLog.w("计算 MD5 失败", it)
            ""
        }
        val existing = findByMd5(context, md5)
        if (existing != null) {
            dataFile.delete()
            XLog.i("导入去重：内容与已有方案「${existing.name}」完全一致（md5=$md5），未重复导入")
            return ImportResult.Duplicate(existing)
        }

        val meta = TableMeta(
            id = id,
            name = displayName.ifBlank { sourceName.ifBlank { id } },
            entryCount = parsed.entries.size,
            sourceName = sourceName,
            importedAt = System.currentTimeMillis(),
            sizeBytes = dataFile.length(),
            md5 = md5,
        )
        runCatching { metaFile(context, id).writeText(meta.toJson()) }
            .onFailure { XLog.e("写入码表元信息失败", it) }

        invalidate()
        XLog.i("码表导入成功：${meta.name}（${meta.entryCount} 条，${meta.sizeBytes} 字节，md5=$md5）")
        return ImportResult.Added(meta)
    }

    fun delete(context: Context, id: String) {
        runCatching { tableFile(context, id).delete() }
        runCatching { metaFile(context, id).delete() }
        if (activeId(context) == id) setActive(context, "")
        invalidate()
    }

    fun rename(context: Context, id: String, newName: String) {
        val meta = meta(context, id) ?: return
        runCatching { metaFile(context, id).writeText(meta.copy(name = newName).toJson()) }
    }

    // ---------------------------------------------------------------- 激活状态

    /**
     * 当前生效的码表 id（空串 = 没有自定义方案生效，走输入法原生词库）。
     *
     * ⚠️ 存的是**文件**不是 SharedPreferences：有的输入法把设置页与输入法服务放在两个进程里，
     * SharedPreferences 的进程内缓存会让输入法进程永远读不到新值（详见 [ModuleConfig]）。
     */
    fun activeId(context: Context): String =
        ModuleConfig.getString(context, ModuleConfig.KEY_ACTIVE_TABLE)

    fun setActive(context: Context, id: String) {
        ModuleConfig.putString(context, ModuleConfig.KEY_ACTIVE_TABLE, id)
        invalidate()
    }

    fun activeMeta(context: Context): TableMeta? =
        activeId(context).takeIf { it.isNotEmpty() }?.let { meta(context, it) }

    // ---------------------------------------------------------------- 索引缓存

    @Volatile
    private var cachedId: String = ""

    @Volatile
    private var cachedLength: Long = -1

    @Volatile
    private var cachedModified: Long = -1

    @Volatile
    private var cachedTable: CodeTable? = null

    fun invalidate() {
        cachedTable = null
        cachedId = ""
        cachedLength = -1
        cachedModified = -1
    }

    /** 取当前激活码表的索引；无激活表或加载失败时返回 null。 */
    fun activeTable(context: Context): CodeTable? {
        val id = activeId(context)
        if (id.isEmpty()) return null
        val file = tableFile(context, id)
        if (!file.isFile) return null
        val table = cachedTable
        if (table != null && cachedId == id && cachedLength == file.length() && cachedModified == file.lastModified()) {
            return table
        }
        val loaded = CodeTable.load(file) ?: return null
        cachedId = id
        cachedLength = file.length()
        cachedModified = file.lastModified()
        cachedTable = loaded
        return loaded
    }

    // ---------------------------------------------------------------- 工具

    /** 文件内容的 MD5（小写十六进制）。 */
    private fun md5Of(file: File): String {
        val digest = MessageDigest.getInstance("MD5")
        file.inputStream().use { input ->
            val buf = ByteArray(1 shl 16)
            while (true) {
                val n = input.read(buf)
                if (n <= 0) break
                digest.update(buf, 0, n)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }
}
