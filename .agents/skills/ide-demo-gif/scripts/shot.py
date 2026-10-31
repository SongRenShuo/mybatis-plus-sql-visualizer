# -*- coding: utf-8 -*-
"""标定辅助：抓帧/点击/移动 单步工具。

用法：
  python shot.py L T W H out.png      抓区域帧存 PNG
  python shot.py click X Y            移动并单击 (X,Y)
  python shot.py move X Y             仅移动
  python shot.py pos                  打印当前鼠标位置
  python shot.py key a ctrl+shift+p   发按键/组合键(+连接)
"""
import sys

from gifkit import click, keys, move, pyautogui, shot


def main():
    a = sys.argv[1:]
    if a[0] == "click":
        click(int(a[1]), int(a[2]))
    elif a[0] == "move":
        move(int(a[1]), int(a[2]))
    elif a[0] == "pos":
        print(pyautogui.position())
    elif a[0] == "key":
        for combo in a[1:]:
            keys(*combo.split("+"))
    elif a[0] == "shot":
        shot((int(a[1]), int(a[2]), int(a[3]), int(a[4])), a[5])
        print("saved", a[5])
    else:
        print(__doc__)


if __name__ == "__main__":
    main()
