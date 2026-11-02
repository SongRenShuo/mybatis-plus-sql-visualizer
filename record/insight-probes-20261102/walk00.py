import time
import walk_util as W

print("sandbox pid =", W.sandbox_pid())
pid, hwnd, title, rect = W.ide_window()
print("window:", title, rect)
W.front()
time.sleep(0.5)
print("shot:", W.shot("w00-startup"))
print("front ok:", W.assert_front() is not None)
print("---- 许可/横幅探测 ----")
for line in W.texts("试用"):
    print("TRIAL>", line)
for line in W.texts("授权"):
    print("LIC>", line)
