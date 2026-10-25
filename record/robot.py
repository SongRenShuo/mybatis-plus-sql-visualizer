# -*- coding: utf-8 -*-
"""组件树定位客户端 —— 直连 IDE 内的 robot-server，按**组件身份**取屏幕坐标。

**为什么要有这个**：像素坐标点不准（弹窗高度随内容变、悬停才渲染、CJK 双宽、独立窗口遮挡）。
robot-server 是 JetBrains 官方 UI 测试框架的**服务端插件**，跑在 IDE 进程内，提供 HTTP/JSON 接口：
按 XPath/类名/文本找到组件；返回的 x/y 是**相对父组件**的，客户端需沿 XPath 祖先链累加
到屏幕物理坐标，再交给 pyautogui。定位靠组件身份，点击仍用现有管线。

**协议（从 remote-robot.jar / robot-server 插件 jar 反查核实，非猜测）**：
- `POST /xpath/components` 请求 `{"xpath": "..."}` → 响应 `{"status":"SUCCESS","elementList":[{id?,className,name,x,y,width,height},...]}`
- `POST /xpath/component` 仅限恰好一项；多项或零项会返回 `status: ERROR`，不可当作列表接口。
- `GET /screenshot` 返回 JSON 的 `bytes` 有符号整数数组（PNG），不是原始二进制。
- 端口由 IDE 系统属性 `robot-server.port` 决定，**默认 8580**，只监听本机。

**在录制沙盒安装（已验证三款插件共存）**：
1. `python record/bootstrap-robot-server.py` 下载并校验到被忽略的本地目录；
2. `./gradlew runIde -PgifRecord=true -PgifRobotServer=true --args=<demo工程路径>`：
   `build.gradle.kts` 仅在录制态且显式开关打开时声明 `localPlugin(...)`，
   交给 `prepareSandbox` 与主插件、AI Assistant 一起安装，正常录制和发行构建不受影响。
**严禁**设置 `-Didea.plugins.path=<robot目录>`：该属性**替换整个插件目录**，
会让主插件和 AI Assistant 消失。IPGP 的 `robotServerPlugin(...)` DSL 则只属于
`intellijPlatformTesting`，不接在普通 `runIde` 的依赖块里。

**用法**：
    python robot.py probe                 # 探活 + 打印根节点，确认端口/协议
    python robot.py find "//div[@class='...']"
    python robot.py click "<xpath>"
    # 脚本里：
    from robot import click_component, find_components
    click_component("(//div[@accessiblename='SQL 历史'])[1]")  # 必须唯一；多命中拒绝盲点
"""
import json
import os
import sys
import urllib.error
import urllib.request

DEFAULT_BASE = os.environ.get("ROBOT_SERVER", "http://127.0.0.1:8580")


class RobotServerError(RuntimeError):
    pass


def _request(path, body=None, base=DEFAULT_BASE, timeout=20, method=None):
    url = base.rstrip("/") + path
    data = None
    headers = {"Accept": "application/json"}
    if body is not None:
        data = json.dumps(body).encode("utf-8")
        headers["Content-Type"] = "application/json"
    req = urllib.request.Request(url, data=data, headers=headers,
                                 method=method or ("POST" if data is not None else "GET"))
    try:
        with urllib.request.urlopen(req, timeout=timeout) as resp:
            raw = resp.read()
    except urllib.error.HTTPError as e:
        detail = e.read().decode("utf-8", "replace")[:400]
        raise RobotServerError("HTTP %s %s -> %s: %s" % (e.code, path, e.reason, detail))
    except urllib.error.URLError as e:
        raise RobotServerError("连不上 robot-server（%s）：%s——沙盒起了吗？robot-server 装了吗？"
                               % (url, e.reason))
    if not raw:
        return None
    try:
        return json.loads(raw.decode("utf-8"))
    except Exception:
        return raw


def find_components(xpath, base=DEFAULT_BASE, timeout=20, **kw):
    """按 XPath 找组件，返回原始组件 dict 列表（字段见模块 docstring）。"""
    resp = _request("/xpath/components", {"xpath": xpath}, base=base, timeout=timeout, **kw)
    if not isinstance(resp, dict):
        raise RobotServerError("组件查询响应不是 JSON 对象：%r" % type(resp))
    if resp.get("status") != "SUCCESS":
        raise RobotServerError("组件查询失败：%s" % resp.get("message", resp.get("status")))
    return resp.get("elementList") or []


def find_one(xpath, base=DEFAULT_BASE, timeout=20, **kw):
    """要求恰好命中一个可见组件；零项返回 None，多项拒绝盲点。"""
    hits = find_components(xpath, base=base, timeout=timeout, **kw)
    visible = [c for c in hits if c.get("width", 0) > 0 and c.get("height", 0) > 0]
    if len(visible) > 1:
        raise RobotServerError("XPath 匹配 %d 个可见组件，请缩小范围：%s" % (len(visible), xpath))
    return visible[0] if visible else None


def component_center(comp, offset=(0, 0)):
    """只算父组件内的相对中心；不要把返回值直接交给 pyautogui。"""
    need = ("x", "y", "width", "height")
    miss = [k for k in need if comp.get(k) is None]
    if miss:
        raise RobotServerError("组件缺少坐标字段 %s：%s" % (miss, comp))
    if int(comp["width"]) <= 0 or int(comp["height"]) <= 0:
        raise RobotServerError("组件不可见或尺寸为零，拒绝点击：%s" % comp)
    return (int(comp["x"]) + int(comp["width"]) // 2 + offset[0],
            int(comp["y"]) + int(comp["height"]) // 2 + offset[1])


def component_screen_center(xpath, comp=None, base=DEFAULT_BASE, offset=(0, 0), timeout=20):
    """XPath 唯一组件的父链累加，得到可用于点击的屏幕物理像素坐标。"""
    comp = comp or find_one(xpath, base=base, timeout=timeout)
    if comp is None:
        return None
    local_x, local_y = component_center(comp, offset=offset)
    chain = find_components("(%s)/ancestor::div" % xpath, base=base, timeout=timeout)
    if not chain or not any(c.get("className", "").endswith("IdeFrameImpl") for c in chain):
        raise RobotServerError("没有 IDE 主窗口到组件的完整祖先链，拒绝猜坐标：%s" % xpath)
    window_roots = [i for i, parent in enumerate(chain)
                    if parent.get("className", "").endswith(("HeavyWeightWindow", "$MyDialog", "$FloatDialog"))]
    if window_roots:
        chain = chain[window_roots[-1]:]
    screen_x = local_x + sum(int(parent["x"]) for parent in chain)
    screen_y = local_y + sum(int(parent["y"]) for parent in chain)
    return screen_x, screen_y


def click_component(xpath, base=DEFAULT_BASE, offset=(0, 0), button="left",
                    move_first=True, timeout=20):
    """找到组件即点其中心，返回 True；**没找到返回 False（不猜坐标）**。"""
    comp = find_one(xpath, base=base, timeout=timeout)
    if not comp:
        return False
    x, y = component_screen_center(xpath, comp=comp, base=base, offset=offset, timeout=timeout)
    import pyautogui
    if move_first:
        pyautogui.moveTo(x, y, duration=0.25)
    pyautogui.click(x, y, button=button)
    return True


def require_component(xpath, base=DEFAULT_BASE, offset=(0, 0), retries=3, interval=0.6, **kw):
    """开录前的闸门：找不到就抛错（与 verify.Guard.require / anchor.require_anchor 同性质）。"""
    import time
    for _ in range(max(1, retries)):
        comp = find_one(xpath, base=base, **kw)
        if comp:
            return comp
        time.sleep(interval)
    raise RobotServerError("组件未找到: %s——XPath 写错了？界面还没出现？" % xpath)


def menu_items(base=DEFAULT_BASE, timeout=20, screen=(1920, 1080)):
    """当前弹出菜单的全部菜单项（屏幕坐标，按 y 从上到下），无菜单时返回 []。

    2026-10-26 实测：ActionMenuItem **在主窗组件树里**——此前「robot 查不到弹出菜单」
    的结论是 XPath 写错了：菜单项没有 name/text 字段，只有 className + 几何。
    返回 [{x, y, width, height}]，x/y 为该行的左缘/上缘（屏幕坐标）。

    **落在屏幕外的项会被丢弃**：菜单关闭后组件树里可能残留上一轮的坐标（实测会返回
    y=1012/1795 这类超过窗口高度的旧值），按屏幕边界过滤可避免把残留当成当前菜单。
    """
    xp = "//div[contains(@class,'ActionMenuItem')]"
    comps = find_components(xp, base=base, timeout=timeout)
    out = []
    for c in comps:
        try:
            sx, sy = component_screen_center(xp, comp=c, base=base, timeout=timeout)
        except Exception:
            continue
        w, h = int(c.get("width", 0)), int(c.get("height", 0))
        if not (0 <= sx < screen[0] and 0 <= sy < screen[1] and w > 0 and h > 0):
            continue                        # 屏幕外 = 残留缓存，不是当前菜单
        out.append({"x": sx, "y": sy, "width": w, "height": h})
    out.sort(key=lambda r: r["y"])          # 「第 N 项」= 从上往下数
    return out


def click_menu_item(index, base=DEFAULT_BASE, timeout=20, retries=3, interval=0.6,
                    x_ratio=0.5):
    """点弹出菜单的第 index 项（0 起，从上往下；负数从末尾数，-1 = 最后一项）。

    替代「像素扫描找末项 + 两段式量测子菜单」那套（a15 曾为此返工五轮）：菜单项几何
    由 IDE 组件树直接给出，项数随上下文变化也不影响。x_ratio 控制点在该行的横向位置
    （0.5 = 行中心；想避开文字可给 0.9）。
    返回被点的矩形；菜单没弹出则重试，最终失败抛错。
    """
    import time
    for _ in range(max(1, retries)):
        items = menu_items(base=base, timeout=timeout)
        if items:
            try:
                rect = items[index]
            except IndexError:
                raise RobotServerError("菜单只有 %d 项，取不到第 %s 项" % (len(items), index))
            x = int(rect["x"] + rect["width"] * x_ratio)
            y = rect["y"] + rect["height"] // 2
            import pyautogui
            pyautogui.moveTo(x, y, duration=0.25)
            pyautogui.click(x, y)
            return rect
        time.sleep(interval)
    raise RobotServerError("菜单没弹出来（0 个 ActionMenuItem）")


def has_menu(base=DEFAULT_BASE, timeout=10):
    """当前是否有弹出菜单在显示（无需等满 timeout 的快速判据）。"""
    try:
        return bool(find_components("//div[contains(@class,'ActionMenuItem')]",
                                    base=base, timeout=timeout))
    except Exception:
        return False


def screenshot(base=DEFAULT_BASE, out=None, timeout=30):
    """取 IDE 侧截图（返回 bytes；给了 out 就落盘）。"""
    resp = _request("/screenshot", base=base, timeout=timeout)
    if not isinstance(resp, dict) or resp.get("status") != "SUCCESS":
        raise RobotServerError("截图接口失败：%s" % (resp.get("message") if isinstance(resp, dict) else type(resp)))
    values = resp.get("bytes")
    if not isinstance(values, list):
        raise RobotServerError("截图缺少 bytes 数组：%s" % list(resp))
    raw = bytes(value & 0xFF for value in values)
    if not raw.startswith(b"\x89PNG\r\n\x1a\n"):
        raise RobotServerError("截图并非 PNG")
    if out:
        with open(out, "wb") as f:
            f.write(raw)
    return raw


def _cli(argv):
    base = os.environ.get("ROBOT_SERVER", DEFAULT_BASE)
    if len(argv) < 2:
        print(__doc__)
        return 2
    cmd = argv[1]
    try:
        if cmd == "probe":
            print("探活 %s ..." % base)
            root = find_components("//div")
            print("根节点数:", len(root))
            if root:
                print("首个:", json.dumps(root[0], ensure_ascii=False)[:300])
            return 0
        if cmd == "find" and len(argv) >= 3:
            hits = find_components(argv[2], base=base)
            print("命中 %d 个" % len(hits))
            for h in hits[:5]:
                print("  ", json.dumps(h, ensure_ascii=False)[:220])
            return 0 if hits else 1
        if cmd == "click" and len(argv) >= 3:
            ok = click_component(argv[2], base=base)
            print("已点击" if ok else "未找到组件（未点击）")
            return 0 if ok else 1
        if cmd == "shot":
            out = argv[2] if len(argv) >= 3 else "robot_shot.png"
            screenshot(base=base, out=out)
            print("已存", out)
            return 0
    except RobotServerError as e:
        print("错误:", e)
        return 1
    print(__doc__)
    return 2


if __name__ == "__main__":
    sys.exit(_cli(sys.argv))
