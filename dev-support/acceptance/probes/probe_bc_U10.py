# -*- coding: utf-8 -*-
"""U10 Kotlin 主动入口降级——构造行/选择区专项 + 普通行负例 + Java 正对照。

**验收口径来源**
- 审计表 U10 行：`mybatis-plus-sql-visualizer-dev/docs/验收文档/20261016-T-DevFullUI-合流后全功能沙盒验收记录.md`
  L35「待构造行与选择区专项」；增量记录 L200（ktQuery 链 Alt+Shift+Q → 「暂不能可靠预览」通知、
  无插件 gutter、不生成猜测 SQL）。
- 文案权威：`MpSqlVisualizerBundle_zh.properties` L37/L38（标题「Kotlin Wrapper 暂不能可靠预览」、
  正文「已识别到 Kotlin Wrapper 链……没有生成猜测 SQL……」）；L36 负例「未在光标处找到可预览的 SQL 源」。
- 夹具：`mybatis-plus-kotlin-demo/src/main/kotlin/cn/song/kotlindemo/scenario/KotlinWrapperScenarios.kt`
  L46 ktQuery 链（审计记录引用 :47 为空行偏移，以链行 46 为准）；正对照
  `mybatis-plus-demo/.../UserServiceImpl.java:36`（LambdaQueryWrapper 构造行）。

**前置条件**：沙盒 IDE 起且演示工程开；robot-server 可选（gutter 计数无 robot 时降级为截图）。

**预计时长**：5~7 分钟。
"""
import os
import sys
import time

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
sys.path.insert(0, os.path.dirname(os.path.dirname(os.path.abspath(__file__))))  # 工具层在 acceptance/

import bc_common as bc
from bc_common import Reporter, front, shot, ide, esc, diff_score, fingerprint
import pyautogui


def select_current_line():
    """选中光标所在行（Home→Shift+End），供「选择区」入口。"""
    pyautogui.keyDown("home"); pyautogui.keyUp("home"); time.sleep(0.2)
    pyautogui.keyDown("shift"); pyautogui.keyDown("end"); pyautogui.keyUp("end"); pyautogui.keyUp("shift")
    time.sleep(0.3)


def assert_kotlin_denied(rep, tag, note):
    """统一断言面：通知出现（气泡区变化）+ 无插件 gutter + 无预览浮窗。"""
    _pid, _hwnd, irect = ide()
    g = bc.Guard(region=bc.balloon_region(irect))
    g.mark("balloon")
    bc.nav_mod.trigger_preview(wait=3.0)
    time.sleep(0.8)
    changed, score = g.changed("balloon", wait=0.4)
    rep.shot_check(changed, "bc-U10-%s-balloon" % tag,
                   "%s：气泡区出现变化（diff=%.1f）——通知已弹（文案人工核对：标题=Kotlin Wrapper 暂不能可靠预览）" % (note, score),
                   "%s：气泡区无变化——主动入口未给限制说明？" % note)
    icons = bc.gutter_icons_in_editor()
    rep.check(not icons, "%s：编辑器无插件 gutter 图标（count=0）" % note,
              "%s：检出 %d 个疑似 gutter 图标——Kotlin 链不该出插件 gutter（截图人工排误检）" % (note, len(icons)))
    rep.check(bc.preview_rect() is None, "%s：无预览浮窗（未生成猜测 SQL 的面板面）" % note,
              "%s：预览浮窗在场——与「不生成猜测 SQL」冲突" % note)
    shot("bc-U10-%s-editor" % tag)
    esc(2)


def java_positive_control(rep):
    """正对照：Java Wrapper 构造行 Alt+Shift+Q 出预览 + 该行有插件 gutter。"""
    bc.open_file(bc.P_USER_SERVICE, rep=rep)
    bc.caret(line=36, col=9, rep=rep)          # LambdaQueryWrapper<User> 构造行首 token
    icons_before = bc.gutter_icons_in_editor()
    rep.shot_check(bool(icons_before), "bc-U10-java-gutter",
                   "Java 正对照：检出 %d 个插件 gutter 图标" % len(icons_before),
                   "Java 正对照：未检出 gutter 图标（正对照失败，先查条带定位再谈 U10）")
    rect = bc.trigger_preview(rep)
    rep.check(rect is not None, "Java 构造行 Alt+Shift+Q 出预览浮窗",
              "Java 构造行未出预览——正对照不成立，本轮 Kotlin 断言全部存疑")
    shot("bc-U10-java-preview", region=rect)
    bc.close_preview()


def main():
    front()
    esc(2)
    rep = Reporter("U10")

    rep.step("1 打开 KotlinWrapperScenarios.kt，光标落 ktQuery 链行（构造行入口）")
    bc.open_file(bc.P_KOTLIN_SCENARIOS, rep=rep)
    bc.caret(text=".isNotNull(KUser::email)", rep=rep)   # L46 链内唯一片段，光标落 token
    rep.step("2 构造行 Alt+Shift+Q → 限制说明通知 + 无插件 gutter + 无预览浮窗")
    assert_kotlin_denied(rep, "ctor-line", "构造行")

    rep.step("3 选择区入口：选中整行链文本 → Alt+Shift+Q → 同一降级面")
    select_current_line()
    shot("bc-U10-selection")
    assert_kotlin_denied(rep, "selection", "选择区")

    rep.step("4 普通行负例：光标落 @Service 注解行 → 只弹「未在光标处找到可预览的 SQL 源」类提示")
    bc.caret(text="@Service", rep=rep)
    _pid, _hwnd, irect = ide()
    g = bc.Guard(region=bc.balloon_region(irect))
    g.mark("balloon")
    bc.nav_mod.trigger_preview(wait=2.5)
    changed, score = g.changed("balloon", wait=0.5)
    rep.shot_check(changed, "bc-U10-negative-balloon",
                   "普通行有提示反馈（气泡区变化 diff=%.1f；文案人工核对=未找到 SQL 源，非 Kotlin 专属文案）" % score,
                   "普通行气泡区无变化——负例无反馈（也可能被前置气泡吞焦，截图判读）")
    rep.check(bc.preview_rect() is None, "普通行无预览浮窗（不误报）",
              "普通行出了预览浮窗——误报，登记")

    rep.step("5 Java 正对照（UserServiceImpl.java:36 构造行）")
    java_positive_control(rep)
    return rep.finish()


if __name__ == "__main__":
    sys.exit(main())
