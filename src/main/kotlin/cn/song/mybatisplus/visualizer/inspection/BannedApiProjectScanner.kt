package cn.song.mybatisplus.visualizer.inspection

import com.intellij.ide.highlighter.JavaFileType
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.project.Project
import com.intellij.psi.JavaRecursiveElementWalkingVisitor
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiJavaFile
import com.intellij.psi.PsiManager
import com.intellij.psi.PsiMethodCallExpression
import com.intellij.psi.search.FileTypeIndex
import com.intellij.psi.search.GlobalSearchScope

/**
 * 禁用 API 存量扫描结果。
 *
 * @param scannedFiles 实际扫描的 Java 文件数
 * @param totalHits    命中总数（不受 [hits] 截断影响）
 * @param hits         明细（已按 [BannedApiScanReport.MAX_DETAIL_ENTRIES] 截断）
 * @param canceled     是否被用户取消（取消时明细不完整）
 */
data class BannedApiScanResult(
    val scannedFiles: Int,
    val totalHits: Int,
    val hits: List<BannedApiScanHit>,
    val canceled: Boolean,
)

/**
 * 禁用 API 全项目存量扫描器（T-BannedApiCheck S4）——只读 PSI 遍历，不触碰文件内容。
 *
 * ## 线程与索引纪律（调用方职责）
 *
 *  - **必须在 ReadAction 内调用**（本类直接读 PSI 与 Document；扫描动作在后台线程
 *    `ApplicationManager.getApplication().runReadAction {}` 里调，不在 EDT）；
 *  - **调用方须先判 `DumbService.isDumb`**：索引构建期 resolve 会抛异常/返回空结果，
 *    本类不做等待（等着比不跑更糟：全项目扫描在索引期跑完也没有意义）；
 *  - [ProgressIndicator] 只用于进度与取消：每文件更新 fraction/text2，逐调用点检查 `isCanceled`。
 *
 * ## 上限与降级
 *
 *  - 明细存储条数上限 = [BannedApiScanReport.MAX_DETAIL_ENTRIES]（500），超出后**继续计数**但
 *    不再保存明细——大项目也不会把几百 MB 的命中列表堆进内存；
 *  - 单个文件解析失败（非 Java PSI / 文档不可得）静默跳过，不中断整轮扫描：存量统计是
 *    「尽力而为」的报告，不是构建门禁。
 */
object BannedApiProjectScanner {

    /**
     * 扫描项目内全部 Java 文件。
     *
     * @param rules     预编译规则表（扫描期间固定，不受规则变更影响）
     * @param indicator 进度/取消载体
     */
    fun scan(project: Project, rules: CompiledRules, indicator: ProgressIndicator): BannedApiScanResult {
        val files = FileTypeIndex.getFiles(JavaFileType.INSTANCE, GlobalSearchScope.projectScope(project))
        val total = files.size
        val hits = ArrayList<BannedApiScanHit>()
        var totalHits = 0
        var scanned = 0
        var canceled = false

        indicator.isIndeterminate = false
        val psiManager = PsiManager.getInstance(project)
        val documentManager = PsiDocumentManager.getInstance(project)

        for ((index, virtualFile) in files.withIndex()) {
            if (indicator.isCanceled) {
                canceled = true
                break
            }
            indicator.fraction = if (total == 0) 1.0 else index.toDouble() / total
            indicator.text2 = virtualFile.name

            // 解析失败（非 Java PSI / 文件失效）→ 跳过该文件，扫描继续
            val psiFile = try {
                psiManager.findFile(virtualFile) as? PsiJavaFile
            } catch (e: Exception) {
                null
            } ?: continue
            scanned++

            val document = try {
                documentManager.getDocument(psiFile)
            } catch (e: Exception) {
                null
            }

            psiFile.accept(
                object : JavaRecursiveElementWalkingVisitor() {
                    override fun visitMethodCallExpression(expression: PsiMethodCallExpression) {
                        super.visitMethodCallExpression(expression)
                        if (indicator.isCanceled) {
                            stopWalking()
                            return
                        }
                        val methodName = expression.methodExpression.referenceName ?: return
                        if (!rules.mayMatchMethodName(methodName)) return
                        val method = expression.resolveMethod() ?: return
                        val classFqn = method.containingClass?.qualifiedName ?: return
                        val rule = rules.match(classFqn, method.name) ?: return

                        totalHits++
                        if (hits.size < BannedApiScanReport.MAX_DETAIL_ENTRIES) {
                            val offset = expression.methodExpression.textOffset
                            val line = try {
                                document?.getLineNumber(offset)?.plus(1) ?: 0
                            } catch (e: Exception) {
                                0
                            }
                            hits += BannedApiScanHit(
                                classFqn = classFqn,
                                methodName = method.name,
                                level = rule.level,
                                description = rule.description,
                                filePath = displayPath(project, virtualFile.path),
                                line = line,
                            )
                        }
                    }
                },
            )

            if (indicator.isCanceled) {
                canceled = true
                break
            }
        }

        indicator.fraction = 1.0
        return BannedApiScanResult(
            scannedFiles = scanned,
            totalHits = totalHits,
            hits = hits,
            canceled = canceled,
        )
    }

    /** 展示路径：项目根内用相对路径（报告更短更好读），项目外退回绝对路径。 */
    private fun displayPath(project: Project, absolutePath: String): String {
        val base = project.basePath ?: return absolutePath
        val normalizedBase = base.replace('\\', '/').trimEnd('/')
        val normalized = absolutePath.replace('\\', '/')
        return if (normalized.startsWith("$normalizedBase/")) {
            normalized.substring(normalizedBase.length + 1)
        } else {
            normalized
        }
    }
}
