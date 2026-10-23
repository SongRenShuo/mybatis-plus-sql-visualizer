package cn.song.mybatisplus.visualizer.config

import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.util.JDOMUtil
import com.intellij.util.xmlb.XmlSerializer
import org.jdom.Document
import org.jdom.Element
import java.io.ByteArrayInputStream
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.NoSuchFileException
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

/**
 * 状态文件「上一代好副本」防护（T-MagpieGems W3，机制取自 magpie `internal/lastgood`）。
 *
 * 平台对插件 options 文件的坏文件处理是：`FileBasedStorage.loadLocalData` 解析失败 → 返回 null →
 * 组件保持出厂默认 State，且该类文件不在 `blockSaving` 名单 → 下一次保存用默认值整文件覆写坏文件
 * （「一次坏读 + 一次好写 = 数据永久丢失」）。本类把纪律补回来：**文件存在但不合法 = 未知，绝不是空**。
 *
 * 三件套：
 *  1. 写前 `.bak`——主文件可解析时，与 `.bak` 内容不同则原子刷新副本（[refreshBackup]）；
 *  2. 读时回落——主文件解析失败 → 坏文件先存证 `.bad-<时间戳>`（**绝不删除、绝不覆盖原文件**），
 *     再读 `.bak` 反序列化并回调 [checkAndRecover] 的 apply（恢复内存态，平台下次保存即自愈主文件）；
 *  3. 「文件不存在」**不**触发回落——rename 原子性保证消失的一定是人为删除，回落反而会把删除变成复活。
 *
 * 纯函数形态（入参全部是 Path/Class/lambda，无平台服务依赖），headless 可直接单测；
 * 触发时机与通知见 [SettingsLastGoodStartup]。项目级文件（.idea 下）二期跟进，本轮只挂应用级两份。
 */
object StateFileLastGood {

    private val LOG = Logger.getInstance(StateFileLastGood::class.java)

    /** 存证文件后缀：`<主文件名>.bad-<yyyyMMdd-HHmmss>` */
    const val BAD_INFIX = ".bad-"
    const val BAK_SUFFIX = ".bak"

    enum class Status {
        /** 主文件可解析（此时已顺带刷新 .bak）。 */
        HEALTHY,
        /** 主文件损坏，已从 .bak 恢复内存态；detail=存证文件名（可能为 null）。 */
        RECOVERED,
        /** 主文件损坏且 .bak 缺失/不可解析；detail=存证文件名（可能为 null）。 */
        CORRUPT_NO_BACKUP,
        /** 主文件不存在——按纪律不回落、不存证、不动 .bak。 */
        NO_FILE,
    }

    data class Outcome(val status: Status, val detail: String?)

    /**
     * 校验字节是否可解析为 XML 文档；解析失败返回 null（不抛）。
     * 注意这是「结构合法」检查，不校验 State 字段——字段级容错由各组件 loadState 自己负责。
     */
    fun parseDocument(bytes: ByteArray): Document? = try {
        JDOMUtil.loadDocument(ByteArrayInputStream(bytes))
    } catch (e: Exception) {
        null
    }

    /**
     * 刷新 `.bak`：主文件可解析且与现存副本内容不同时，走临时文件 + ATOMIC_MOVE 落盘
     * （平台自身的截断式直写正是 #1505 形态事故的根因，副本写入不能再复制它）。
     *
     * @return true=副本已就绪且与主文件一致；false=主文件缺失/不可解析或副本写入失败。
     */
    fun refreshBackup(mainFile: Path, bakFile: Path): Boolean {
        val bytes = try {
            Files.readAllBytes(mainFile)
        } catch (e: NoSuchFileException) {
            return false
        } catch (e: Exception) {
            LOG.warn("StateFileLastGood.refreshBackup: 主文件读取失败（${mainFile.fileName}）：$e")
            return false
        }
        if (parseDocument(bytes) == null) return false
        if (Files.exists(bakFile)) {
            val old = try {
                Files.readAllBytes(bakFile)
            } catch (e: Exception) {
                null
            }
            if (old != null && old.contentEquals(bytes)) return true
        }
        return try {
            val tmp = bakFile.resolveSibling(bakFile.fileName.toString() + ".tmp")
            Files.write(tmp, bytes)
            try {
                Files.move(tmp, bakFile, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
            } catch (e: AtomicMoveNotSupportedException) {
                Files.move(tmp, bakFile, StandardCopyOption.REPLACE_EXISTING)
            }
            true
        } catch (e: Exception) {
            LOG.warn("StateFileLastGood.refreshBackup: 副本写入失败（${bakFile.fileName}）：$e")
            false
        }
    }

    /**
     * 三件套主入口：健康即备份、损坏即存证回落恢复。
     *
     * @param componentName options XML 里 `<component name="...">` 的名字（即组件 `@State.name`），
     *   State 类本身不带该注解，须由调用方显式传入。
     * @param apply 恢复回调（把反序列化出的 State 交给组件 loadState）；只在 [Status.RECOVERED] 时调用一次。
     *   是否需要 EDT 归位由调用方决定——本函数只做文件 IO 与反序列化，可在任意线程运行。
     */
    fun <T : Any> checkAndRecover(
        mainFile: Path,
        bakFile: Path,
        componentName: String,
        stateClass: Class<T>,
        apply: (T) -> Unit,
    ): Outcome {
        if (!Files.exists(mainFile)) return Outcome(Status.NO_FILE, null)
        val mainBytes = try {
            Files.readAllBytes(mainFile)
        } catch (e: Exception) {
            // 读不出（锁/权限）≠ 文件坏：按 NO_FILE 处置，不存证不回落，等下轮维护再查
            LOG.warn("StateFileLastGood: 主文件读取失败，本轮跳过（${mainFile.fileName}）：$e")
            return Outcome(Status.NO_FILE, null)
        }
        if (parseDocument(mainBytes) != null) {
            refreshBackup(mainFile, bakFile)
            return Outcome(Status.HEALTHY, null)
        }

        // 坏文件存证：同名同轮只存一份（周期维护会反复进这里，防证据文件刷屏）
        val evidenceName = try {
            val existing = existingEvidence(mainFile)
            if (existing != null) {
                existing.fileName.toString()
            } else {
                val created = newEvidencePath(mainFile)
                Files.copy(mainFile, created, StandardCopyOption.REPLACE_EXISTING)
                created.fileName.toString()
            }
        } catch (e: Exception) {
            LOG.warn("StateFileLastGood: 坏文件存证失败（${mainFile.fileName}）：$e")
            null
        }

        if (!Files.exists(bakFile)) return Outcome(Status.CORRUPT_NO_BACKUP, evidenceName)
        val bakBytes = try {
            Files.readAllBytes(bakFile)
        } catch (e: Exception) {
            return Outcome(Status.CORRUPT_NO_BACKUP, evidenceName)
        }
        val bakDoc = parseDocument(bakBytes) ?: return Outcome(Status.CORRUPT_NO_BACKUP, evidenceName)
        val component = componentElementOf(bakDoc, componentName)
            ?: return Outcome(Status.CORRUPT_NO_BACKUP, evidenceName)
        val restored = try {
            val bean = stateClass.getDeclaredConstructor().newInstance()
            XmlSerializer.deserializeInto(bean, component)
            bean
        } catch (e: Exception) {
            LOG.warn("StateFileLastGood: .bak 反序列化失败（${bakFile.fileName}）：$e")
            return Outcome(Status.CORRUPT_NO_BACKUP, evidenceName)
        }
        try {
            apply(restored)
        } catch (e: Exception) {
            LOG.warn("StateFileLastGood: 恢复回调失败（${mainFile.fileName}）：$e")
            return Outcome(Status.CORRUPT_NO_BACKUP, evidenceName)
        }
        return Outcome(Status.RECOVERED, evidenceName)
    }

    /** options XML 结构：`<application><component name="...">状态字段…</component></application>`。 */
    private fun componentElementOf(doc: Document, componentName: String): Element? =
        doc.rootElement.getChildren("component").firstOrNull { it.getAttributeValue("name") == componentName }

    private fun existingEvidence(mainFile: Path): Path? {
        val dir = mainFile.parent ?: return null
        val prefix = "${mainFile.fileName}$BAD_INFIX"
        return try {
            Files.list(dir).use { stream ->
                stream.filter { it.fileName.toString().startsWith(prefix) }.findFirst().orElse(null)
            }
        } catch (e: Exception) {
            null
        }
    }

    private fun newEvidencePath(mainFile: Path): Path {
        val stamp = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss").format(LocalDateTime.now())
        return mainFile.resolveSibling("${mainFile.fileName}$BAD_INFIX$stamp")
    }
}
