# -*- coding: utf-8 -*-
"""写录制所需的环境状态（必须在 IDE 完全退出后跑）。

为什么单独一个脚本（20261027 反复踩）：沙盒 IDE **退出时会把内存态回写 config**，
把外部写入的 `dialect` 键冲掉；运行中改也会被内存态覆盖。所以固定顺序是：
    ① 杀掉沙盒 IDE（等进程真的退干净）
    ② 跑本脚本写状态
    ③ runIde 启动

用法：
    python prep_env.py [--dialect MySQL|PostgreSQL] [--demo-app]

做的事：
  · 沙盒 config/options/mp-sql-visualizer.xml：dialectAuto=false + dialect=<目标>
  · demo 仓 .idea/sqldialects.xml：PROJECT 级映射 = <目标>
  · （--demo-app）保持 demo 应用由 IDE 内启动，这里只提示
"""
import os
import re
import sys

SANDBOX_XML = ("F:/code/Java/Gitee/SongRenShuo/mybatis-plus-sql-visualizer/build/"
               "MPSV-GifFresh/mybatis-plus-sql-visualizer/IU-2026.2.1/config/options/"
               "mp-sql-visualizer.xml")
DEMO_SQLDIALECTS = ("F:/code/Java/Gitee/SongRenShuo/mybatis-plus-sql-visualizer-demo/"
                    ".idea/sqldialects.xml")


def main():
    dialect = "MySQL"
    if "--dialect" in sys.argv:
        dialect = sys.argv[sys.argv.index("--dialect") + 1]

    # 1) 沙盒全局
    if not os.path.exists(SANDBOX_XML):
        print(f"[FAIL] 沙盒 config 不存在：{SANDBOX_XML}（先跑一次 runIde 建沙盒）")
        return 1
    s = open(SANDBOX_XML, encoding="utf-8").read()
    if 'name="dialectAuto" value="false"' not in s:
        s = re.sub(r'<option name="dialectAuto" value="[^"]*" />',
                   '<option name="dialectAuto" value="false" />', s)
        if 'name="dialectAuto"' not in s:
            s = s.replace('<component name="MpSqlVisualizerSettings">',
                          '<component name="MpSqlVisualizerSettings">\n    '
                          '<option name="dialectAuto" value="false" />')
    if 'name="dialect" ' in s:
        s = re.sub(r'<option name="dialect" value="[^"]*" />',
                   f'<option name="dialect" value="{dialect}" />', s)
    else:
        s = s.replace('<option name="dialectAuto" value="false" />',
                      f'<option name="dialectAuto" value="false" />\n    '
                      f'<option name="dialect" value="{dialect}" />')
    open(SANDBOX_XML, "w", encoding="utf-8", newline="").write(s)
    print(f"[OK] 沙盒全局 dialect={dialect}（dialectAuto=false）")

    # 2) 项目级 SQL 方言映射
    if os.path.exists(DEMO_SQLDIALECTS):
        t = open(DEMO_SQLDIALECTS, encoding="utf-8").read()
        if re.search(r'<file url="PROJECT" dialect="[^"]*" />', t):
            t = re.sub(r'<file url="PROJECT" dialect="[^"]*" />',
                       f'<file url="PROJECT" dialect="{dialect}" />', t)
        else:
            t = t.replace("</component>",
                          f'    <file url="PROJECT" dialect="{dialect}" />\n  </component>')
        open(DEMO_SQLDIALECTS, "w", encoding="utf-8", newline="").write(t)
        print(f"[OK] 项目级 PROJECT dialect={dialect}")

    print("\n下一步：runIde 启动沙盒，然后跑 preflight 复核")
    return 0


if __name__ == "__main__":
    sys.exit(main())
