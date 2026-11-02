import os,time,ctypes
from ctypes import wintypes
try: ctypes.windll.shcore.SetProcessDpiAwareness(2)
except Exception: pass
import pyautogui
pyautogui.FAILSAFE=False
u32=ctypes.windll.user32
TMP=os.path.join(os.path.dirname(os.path.abspath(__file__)),"tmp")
hits=[]
@ctypes.WINFUNCTYPE(ctypes.c_bool, wintypes.HWND, wintypes.LPARAM)
def cb(hwnd,lparam):
    if not u32.IsWindowVisible(hwnd): return True
    n=u32.GetWindowTextLengthW(hwnd)
    if n!=0: return True
    r=wintypes.RECT()
    u32.GetWindowRect(hwnd,ctypes.byref(r))
    wd=r.right-r.left; ht=r.bottom-r.top
    if 350<wd<700 and 100<ht<300:
        hits.append((hwnd,r.left,r.top,wd,ht))
    return True
u32.EnumWindows(cb,0)
print("HITS",hits)
if hits:
    hwnd,l,t,wd,ht=hits[0]
    u32.SetWindowPos(hwnd,-1,0,0,0,0,0x2|0x1)
    time.sleep(0.4)
    # checkbox at left+58, top+95 ; Trust button center left+217, top+125
    pyautogui.click(l+58,t+95)
    time.sleep(0.5)
    pyautogui.click(l+217,t+125)
    time.sleep(0.6)
    pyautogui.click(l+217,t+125)
    time.sleep(1.5)
pyautogui.screenshot().save(os.path.join(TMP,"d_dialog.png"))
print("DONE")
