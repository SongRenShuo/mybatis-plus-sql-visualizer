package cn.song.mybatisplus.visualizer.log

import com.intellij.psi.PsiClassOwner
import com.intellij.psi.PsiManager
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue

/**
 * 项目类型检测行为测试（T-TestGuard-07 零覆盖补测：log/ProjectTypeDetector 此前零覆盖）。
 *
 * F-44-7：SqlSourceInterceptorAutoInjector 依赖的项目类型识别。检测体全量 PSI 探测
 * （T-PreviewDocLock2 F2 起包 ReadAction），本测试同时验证 detect() 在平台测试环境
 * 可安全直调（不抛 read access 断言）。
 */
class ProjectTypeDetectorTest : BasePlatformTestCase() {
    fun `test空项目探测不抛异常_返回null或UNSUPPORTED兜底`() {
        // 无 Spring 特征、无源码根的空 fixture 项目：有源码根时兜底 UNSUPPORTED、无则 null
        val result = ProjectTypeDetector.detect(project)
        assertTrue("兜底契约破坏：$result", result == null || result.type == ProjectType.UNSUPPORTED)
    }
    fun `test有源码根与java文件_基础包从首个java文件推断`() {
        myFixture.addFileToProject(
            "src/main/java/com/demo/App.java",
            """
            package com.demo;

            public class App {
            }
            """.trimIndent(),
        )
        val root = myFixture.findFileInTempDir("src/main/java")
        com.intellij.testFramework.PsiTestUtil.addSourceRoot(myFixture.module, root)

        val result = ProjectTypeDetector.detect(project)
        assertNotNull("有 src/main/java 源码根应产出结构（无特征时 UNSUPPORTED）", result)

        // 测试 classpath 是否有 mybatis（MP core 传递依赖）决定命中 MYBATIS_NATIVE 还是 UNSUPPORTED
        val mybatisPresent = com.intellij.psi.JavaPsiFacade.getInstance(project)
            .findClass("org.apache.ibatis.session.SqlSessionFactory", GlobalSearchScope.allScope(project)) != null
        if (mybatisPresent) {
            assertEquals("classpath 有 SqlSessionFactory 应识别为 MyBatis 原生项目", ProjectType.MYBATIS_NATIVE, result!!.type)
            assertEquals("基础包应从首个 java 文件的 package 推断", "com.demo", result!!.basePackage)
        } else {
            assertEquals("无任何特征应兜底 UNSUPPORTED", ProjectType.UNSUPPORTED, result!!.type)
        }
    }
}
