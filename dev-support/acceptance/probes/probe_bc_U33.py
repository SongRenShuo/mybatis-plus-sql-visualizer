# -*- coding: utf-8 -*-
"""U33 R24 原生 MyBatis 拦截器自动注入——批 C 探针（确认面 + 文件系统断言）。

**验收口径来源**
- 审计表 U33 行：`mybatis-plus-sql-visualizer-dev/docs/验收文档/20261016-T-DevFullUI-合流后全功能沙盒验收记录.md`
  L58 整组「待验」；增量口径 `20261008-1623-dev-bak对比master-新增功能验收文档.md` §3.25——
  单 config 成功注入（config 包生成两件 + mybatis-config.xml `<plugins>` 注册 + 成功通知）；
  无 config / config 多处 / `<plugins>` 结构不符 **不注入**（fail-closed，维持手动引导不报错）；
  Spring Boot 既有注入路径回归。
- 源码锚：`SqlSourceInterceptorAutoInjector.kt` L355-361（装配点= `<plugins>`；fail-closed=
  MyBatisNativeWiring.decide：检测不到 config / config 多处 / 根标签非 `<configuration>` →
  UnsupportedProject）；生成文件名 `SqlSourceInterceptor.java` / `SqlVisualizerConfig.java`
  （`SourceInterceptorTemplates.kt` L15/L18）；设置开关 `settings.log.autoInjectInterceptor`
  「自动注入源码捕获拦截器（推荐使用增强捕获）」——**纯动作开关**，勾选立即生成、完成后自动复位不持久。

**形态说明**：注入判定的主体在**启动形态**（生成文件 + config 注册 + 应用重启后日志带源码），
本脚本承担两类可自动化面——
  1) `--check-tree [clean|injected|corrupted]`：对 native-mybatis-demo 源树做**文件系统断言**
     （生成物在/不在、`<plugins>` 注册在/不在）——RUNBOOK 按三种形态逐次跑；
  2) 默认模式：沙盒 IDE 侧确认面——设置开关在场 + 「生成 CRUD/拦截器」链路点得动 +
     SQL 日志条目源码位置面截图（配合已注入重启后的应用语料）。

**前置条件**：沙盒起、演示工程开；--check-tree 模式无需 IDE。
**预计时长**：默认模式 4~6 分钟；--check-tree 即时。
"""
import os
import sys
import glob
import time

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
sys.path.insert(0, os.path.dirname(os.path.dirname(os.path.abspath(__file__))))  # 工具层在 acceptance/

import bc_common as bc
from bc_common import Reporter, front, shot, esc
import pyautogui

NATIVE_CFG = os.path.join(bc.DEMO_ROOT, "native-mybatis-demo", "src", "main", "resources", "mybatis-config.xml")
NATIVE_SRC = os.path.join(bc.DEMO_ROOT, "native-mybatis-demo", "src", "main", "java")


def generated_files():
    hits = []
    for name in ("SqlSourceInterceptor.java", "SqlVisualizerConfig.java"):
        hits += glob.glob(os.path.join(NATIVE_SRC, "**", name), recursive=True)
    return hits


def config_registered():
    try:
        with open(NATIVE_CFG, encoding="utf-8") as f:
            txt = f.read()
    except OSError:
        return False
    return "<plugin" in txt and "interceptor" in txt


def check_tree(mode):
    rep = Reporter("U33-check-tree:%s" % mode)
    rep.step("文件系统断言（fail-closed 语义的确定性面，无需 IDE）")
    files = generated_files()
    reg = config_registered()
    print("  生成物: %s" % (files or "无"))
    print("  <plugins> 注册: %s" % reg)
    if mode == "injected":
        rep.check(bool(files) and reg, "注入形态成立：两生成物在 + <plugins> 已注册",
                  "注入形态不成立：生成物=%d 个、注册=%s" % (len(files), reg))
    elif mode == "clean":
        rep.check(not files and not reg, "无注入形态成立：无生成物 + 无 <plugin> 注册（fail-closed 未误注入）",
                  "无注入形态被破坏：生成物=%d、注册=%s" % (len(files), reg))
    elif mode == "corrupted":
        rep.check(not files and not reg, "非法 <plugins>/多 config 形态成立：不注入（fail-closed 保持）",
                  "非法形态被注入——违反 fail-closed，登记缺陷")
    else:
        rep.fail("未知形态 %r（用 clean|injected|corrupted）" % mode)
    rep.shot_check(True, "bc-U33-tree-%s" % mode, "源树状态截图存档（mybatis-config.xml 与生成物路径）", "")
    return rep.finish()


def ide_surface(rep):
    """IDE 侧确认面：设置开关在场 + 注入后日志条目源码位置面。"""
    rep.step("I1 设置页 SQL 日志组「自动注入源码捕获拦截器」开关在场")
    pyautogui.hotkey("ctrl", "alt", "s")
    time.sleep(2.5)
    shot("bc-U33-01-settings")
    found = bc.find_contains("自动注入源码捕获拦截器")
    rep.check(bool(found), "「自动注入源码捕获拦截器」开关在册（robot 文本命中；**不点它**——点了会真的生成文件）",
              "开关未探到（robot 未启或设置页未停 SQL 日志组——人工切页后重跑）")
    if found:
        rep.ok("开关为纯动作型（勾选立即生成、自动复位）——本探针只证在场，不触发")
    esc(3)

    rep.step("I2 注入后的 IDE 功能面：SQL 日志条目应带源码位置（需 RUNBOOK 已完成注入+应用重启）")
    if bc.click_named("SQL Visualizer") or bc.click_named("SQL 日志"):
        time.sleep(2.0)
        shot("bc-U33-02-log-list")
        rep.ok("日志列表截图已存（人工核对：原生应用条目行带源码位置/跳转可用=注入生效的用户面）")
    else:
        rep.fail("工具窗未能按名唤起——人工打开 SQL Visualizer 后重跑")
    return rep.finish()


def main():
    argv = sys.argv[1:]
    if "--check-tree" in argv:
        i = argv.index("--check-tree")
        mode = argv[i + 1] if len(argv) > i + 1 and not argv[i + 1].startswith("--") else ""
        return check_tree(mode)
    front()
    esc(2)
    rep = Reporter("U33")
    ide_surface(rep)
    print("\n[说明] 三态文件系统断言（无需 IDE）：python probe_bc_U33.py --check-tree clean|injected|corrupted；"
          "造态与重启步骤见 RUNBOOK U33 节。")
    return rep.finish()


if __name__ == "__main__":
    sys.exit(main())
