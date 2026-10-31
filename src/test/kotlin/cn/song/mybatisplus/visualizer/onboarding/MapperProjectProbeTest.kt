package cn.song.mybatisplus.visualizer.onboarding

import com.intellij.openapi.application.ReadAction
import com.intellij.testFramework.fixtures.BasePlatformTestCase

/**
 * 工程级 mapper 探测的行为测试（S2 首用引导判据的前半截）：真实 fixture 文件 → 索引 → 名称判定。
 *
 * 为什么必须有这一例：`MapperProjectProbe.isMapperXmlFileName` 的纯判定矩阵只能证「规则能判对名字」，
 * 证不了「`FileTypeIndex` 在 fixture 里真的看得见那个文件」（扫描面为空时守卫/引导都是空绿）。
 * 这里两种布局各造一次：**有 `*Mapper.xml`** → true；**只有普通 XML** → false（后者是「不该打扰
 * 非 MyBatis 工程」的反向证据）。
 *
 * headless 下不跑 `MapperProjectPrimerActivity`（`isUnitTestMode` 短路），故本类只测探测内核。
 */
class MapperProjectProbeTest : BasePlatformTestCase() {

    fun `test存在MapperXml的工程被判定为mapper工程`() {
        myFixture.addFileToProject(
            "src/main/resources/mapper/UserMapper.xml",
            """<?xml version="1.0" encoding="UTF-8"?><mapper namespace="com.example.UserMapper"><select id="x">select 1</select></mapper>""",
        )
        val found = ReadAction.compute<Boolean, RuntimeException> { MapperProjectProbe.hasMapperXml(project) }
        assertTrue("fixture 中的 UserMapper.xml 必须被探测命中", found)
    }

    fun `test只有普通Xml的工程不被误判为mapper工程`() {
        myFixture.addFileToProject(
            "src/main/resources/applicationContext.xml",
            """<?xml version="1.0" encoding="UTF-8"?><beans/>""",
        )
        val found = ReadAction.compute<Boolean, RuntimeException> { MapperProjectProbe.hasMapperXml(project) }
        assertFalse("普通 XML 不得触发首用引导（防打扰非 MyBatis 工程）", found)
    }
}
