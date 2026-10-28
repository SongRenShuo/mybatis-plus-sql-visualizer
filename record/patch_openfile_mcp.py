# -*- coding: utf-8 -*-
"""批4 脚本补丁：把 Ctrl+Shift+N 开文件段统一换成 MCP 按路径精确打开。

动机（20261027 实测）：demo 仓里 UserMapper.xml / NativeUserMapper.xml 同名近名，
Ctrl+Shift+N + typewrite 会开错文件（a34 因此连废 3 个 take，LINE 落在错误文件里
弹出「选择要预览的 SQL」候选框）。MCP open_file 按相对路径精确打开，零歧义。

用法：python patch_openfile_mcp.py   # 幂等，已改过的会跳过
"""
import os
import re
import sys

HERE = os.path.dirname(os.path.abspath(__file__))

# (脚本, 目标文件相对路径, 该段起止特征)
TARGETS = [
    ("scene_a11_explain.py",
     "mybatis-plus-join-demo/src/main/java/cn/song/mpjdemo/service/impl/DeviceInfoServiceImpl.java"),
    ("scene_a13_execute_sql.py",
     "mybatis-plus-join-demo/src/main/java/cn/song/mpjdemo/service/impl/DeviceInfoServiceImpl.java"),
    ("scene_a25_xml_exec_guard.py",
     "mybatis-plus-demo/src/main/resources/mapper/OrderMapper.xml"),
    ("scene_a22_dialect.py",
     "mybatis-plus-demo/src/main/java/cn/song/mybatisplusdemo/service/impl/PageSimServiceImpl.java"),
]


def mcp_block(rel):
    return f'''    # 开文件改 MCP 按路径精确打开（20261027：Ctrl+Shift+N + typewrite 必撞同名文件）
    try:
        from mcpnav import open_file
        open_file("{rel}", settle=2.0)
    except Exception as e:
        print("MCP 打不开，中止:", e)
        raise'''


def patch(path, rel):
    if rel is None:
        return False
    p = os.path.join(HERE, path)
    s = open(p, encoding="utf-8").read()
    if "from mcpnav import open_file" in s:
        print(f"  {path}: 已是 MCP 版，跳过")
        return False
    # 把从 ctrl+shift+n 到 goto_text/goto_col 之前整段替换
    m = re.search(
        r'[ \t]*pyautogui\.hotkey\("ctrl", "shift", "n"\).*?(?=\n[ \t]*(?:goto_text|goto_col|trigger_preview|\w+\s*=))',
        s, re.S)
    if not m:
        print(f"  {path}: 未匹配到开文件段，需手工处理")
        return False
    s = s[:m.start()] + mcp_block(rel) + s[m.end():]
    open(p, "w", encoding="utf-8", newline="").write(s)
    print(f"  {path}: 已替换")
    return True


def main():
    print("补丁 Ctrl+Shift+N → MCP:")
    for path, rel in TARGETS:
        patch(path, rel)


if __name__ == "__main__":
    main()
