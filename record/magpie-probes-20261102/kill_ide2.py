import subprocess,time
import ctypes
u32=ctypes.windll.user32
import pygetwindow as pg
wins=[w for w in pg.getAllWindows() if w.title and "mybatis-plus-sql-visualizer-demo" in w.title and w.visible]
for w in wins:
    subprocess.run(["taskkill","/F","/PID",str(w._hWnd)])
print("windows",len(wins))
