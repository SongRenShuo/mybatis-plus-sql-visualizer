package cn.song.mybatisplus.visualizer.psi

import cn.song.mybatisplus.visualizer.psi.jpa.JpaEntityResolver
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.util.Computable
import com.intellij.openapi.vfs.VfsUtil
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiClass
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiManager
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.testFramework.HeavyPlatformTestCase
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull

/**
 * 实体解析缓存：父类文件改动失效（D254 T-EntityFieldStaticHidden M5，Heavy 沙盒）。
 *
 * 场景：父类与子类分处两个文件（真实项目形态）。改父类文件（加 @TableLogic / @Version）后，
 * 子类解析结果必须失效——只看子类自身文件戳的旧实现会继续返回旧列。
 *
 * Light 沙盒跨文件 resolve 不可用（子类 extends 父类解析不到），故走 Heavy（磁盘 + 索引）。
 *
 * k2j 移植注（互操作三形态）：
 *  - `WriteCommandAction.runWriteCommandAction(project, Computable { ... })`：函数式接口**变量/实参**
 *    赋值形态在 Kotlin 侧本就是 SAM 转换，Java 侧签名同为 `Computable<T>`，直取无需显式包装；
 *  - `module` / `project` 是 HeavyPlatformTestCase（JUnit3 基类）的 Java getter，
 *    Kotlin 属性语法直取；
 *  - `updateModel(module) { model -> ... }` 的 `return@updateModel` 标签保留（作用域函数语义不变，
 *    因为 `ModuleRootModificationUtil.updateModel` 是 Java 静态方法、lambda 仍是 SAM 转换）。
 */
class EntityResolverInheritanceCacheHeavyTest : HeavyPlatformTestCase() {

    /**
     * 项目源根（**只创建一次**）：`createTestProjectStructure()` 每次调用会新建结构，
     * 逐调用获取会把父类与子类放进不同根部，导致子类 `extends` 解析不到父类。
     */
    private var cachedSourceRoot: VirtualFile? = null

    private fun sourceRoot(): VirtualFile =
        cachedSourceRoot ?: createTestProjectStructure().also { cachedSourceRoot = it }

    /**
     * 写（或重写）源码文件：按 `package` 声明落到 `src/<pkg 路径>/<name>`。
     *
     * 目录与 package 必须一致——索引版 `findClass` 按 package 注册能命中，但引用解析（`extends`）
     * 走同包/目录查找，包目录不匹配时 `superClass` 恒 null（实施期诊断实证）。
     * 同名文件已存在则复用（重写触发 modificationStamp 变化）。
     */
    private var sourceRootRegistered = false

    /**
     * 把 `src` 注册为模块源根：Heavy 沙盒里 `createTestProjectStructure()` 建出的目录默认不在
     * source content 内——`findClass` 走类索引能命中，但 `extends` 引用解析不成立
     * （`superClass == null`，实施期诊断实证）。注册一次即可。
     */
    private fun ensureSourceRootRegistered(src: VirtualFile) {
        if (sourceRootRegistered) return
        sourceRootRegistered = true
        val module = com.intellij.openapi.module.ModuleManager.getInstance(project).modules.firstOrNull()
            ?: return
        com.intellij.openapi.roots.ModuleRootModificationUtil.updateModel(module) { model ->
            val entry = model.contentEntries.firstOrNull() ?: return@updateModel
            if (entry.sourceFolders.none { it.file == src }) {
                entry.addSourceFolder(src, false)
            }
        }
    }

    private fun addSourceFile(fileName: String, content: String): PsiFile =
        WriteCommandAction.runWriteCommandAction(project, Computable {
            var dir = sourceRoot().findChild("src") ?: sourceRoot().createChildDirectory(this, "src")
            ensureSourceRootRegistered(dir)
            val text = content.trimIndent()
            val pkg = Regex("""(?m)^\s*package\s+([\w.]+)\s*;""").find(text)?.groupValues?.get(1)
            if (pkg != null) {
                for (part in pkg.split('.')) {
                    if (part.isEmpty()) continue
                    dir = dir.findChild(part) ?: dir.createChildDirectory(this, part)
                }
            }
            val vf = dir.findChild(fileName) ?: dir.createChildData(this, fileName)
            // 重写走 Document（IDE 真实编辑路径）：saveText 直写 VFS 时 PSI 树与 modificationStamp
            // 可能不刷新，缓存失效判据读不到变化
            val document = com.intellij.openapi.fileEditor.FileDocumentManager.getInstance().getDocument(vf)
            if (document != null && vf.exists()) {
                document.setText(text)
                com.intellij.psi.PsiDocumentManager.getInstance(project).commitDocument(document)
            } else {
                VfsUtil.saveText(vf, text)
            }
            PsiManager.getInstance(project).findFile(vf)!!
        })

    private fun classByName(file: PsiFile, name: String): PsiClass =
        PsiTreeUtil.collectElementsOfType(file, PsiClass::class.java).first { it.name == name }

    fun testParentFileModificationInvalidatesMpEntityCache() {
        EntityResolver.clearCache()
        addSourceFile(
            "BaseEntity.java",
            """
            package com.example;

            public class BaseEntity {
                private Long id;
            }
            """,
        )
        val childFile = addSourceFile(
            "UserEntity.java",
            """
            package com.example;

            public class UserEntity extends BaseEntity {
                private String name;
            }
            """,
        )
        val child = classByName(childFile, "UserEntity")
        val first = EntityResolver().resolve(child)
        assertEquals(listOf("id", "name"), first.allColumnNames)

        // 改父类文件：新增 @TableLogic deleted（子类文件未动，只有父类戳变化）
        addSourceFile(
            "BaseEntity.java",
            """
            package com.example;

            public class BaseEntity {
                private Long id;

                @com.baomidou.mybatisplus.annotation.TableLogic
                private Integer deleted;
            }
            """,
        )
        val updated = EntityResolver().resolve(child)
        assertEquals("deleted", updated.logicDeleteColumn)
        assertEquals(listOf("id", "name", "deleted"), updated.allColumnNames)
    }

    fun testParentFileModificationInvalidatesJpaEntityCache() {
        JpaEntityResolver.clearCache()
        addSourceFile(
            "JpaBaseEntity.java",
            """
            package com.example;

            @jakarta.persistence.Entity
            public class JpaBaseEntity {
                @jakarta.persistence.Id
                private Long id;
            }
            """,
        )
        val childFile = addSourceFile(
            "JpaUserEntity.java",
            """
            package com.example;

            @jakarta.persistence.Entity
            public class JpaUserEntity extends JpaBaseEntity {
                private String name;
            }
            """,
        )
        val child = classByName(childFile, "JpaUserEntity")
        val first = JpaEntityResolver().resolve(child)
        assertEquals(listOf("id", "name"), first.allColumnNames)

        addSourceFile(
            "JpaBaseEntity.java",
            """
            package com.example;

            @jakarta.persistence.Entity
            public class JpaBaseEntity {
                @jakarta.persistence.Id
                private Long id;

                @jakarta.persistence.Version
                private Integer version;
            }
            """,
        )
        val updated = JpaEntityResolver().resolve(child)
        assertNotNull("父类新增 @Version 后必须重新解析出乐观锁列", updated.versionColumn)
        assertEquals("version", updated.versionColumn)
    }
}
