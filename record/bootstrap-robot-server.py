# -*- coding: utf-8 -*-
"""下载录制专用 robot-server 到本地缓存（不入库、不覆盖沙盒插件目录）。

首次运行：python record/bootstrap-robot-server.py
然后用标准 Gradle 命令启动录制沙盒，并加 -PgifRobotServer=true。
"""
import hashlib
import os
from pathlib import Path
import subprocess
import tempfile
import zipfile

VERSION = "0.11.23"
SHA256 = "65658b502c6bee6b3649298b7bfe887fd3ae5ca4d1482914f41da4f0acaf8f23"
URL = (
    "https://packages.jetbrains.team/maven/p/ij/intellij-dependencies/"
    f"com/intellij/remoterobot/robot-server-plugin/{VERSION}/"
    f"robot-server-plugin-{VERSION}.zip"
)
ROOT = Path(__file__).resolve().parent
TARGET = ROOT / "robot-plugins" / "robot-server-plugin"
PLUGIN_JAR = TARGET / "lib" / f"instrumented-robot-server-plugin-{VERSION}.jar"


def installed():
    if not PLUGIN_JAR.is_file():
        return False
    with zipfile.ZipFile(PLUGIN_JAR) as jar:
        descriptor = jar.read("META-INF/plugin.xml")
    return b"com.jetbrains.test.robot-server-plugin" in descriptor


def main():
    if TARGET.exists():
        if not installed():
            raise RuntimeError(f"本地目录已存在但不是预期插件，请人工检查：{TARGET}")
        print(f"robot-server {VERSION} 已就位：{TARGET}")
        return

    TARGET.parent.mkdir(parents=True, exist_ok=True)
    with tempfile.TemporaryDirectory(prefix="robot-download-", dir=TARGET.parent) as folder:
        archive = Path(folder) / "robot-server.zip"
        proxy = os.environ.get("MPSV_PROXY", "http://127.0.0.1:7890")
        subprocess.run(
            ["curl", "--fail", "--location", "--retry", "2", "--max-time", "120",
             "--proxy", proxy, "--output", str(archive), URL],
            check=True,
        )
        digest = hashlib.sha256(archive.read_bytes()).hexdigest()
        if digest != SHA256:
            raise RuntimeError(f"robot-server 下载包摘要不符：{digest}")
        with zipfile.ZipFile(archive) as bundle:
            for item in bundle.infolist():
                parts = Path(item.filename).parts
                if not parts or parts[0] != "robot-server-plugin" or ".." in parts:
                    raise RuntimeError(f"插件包路径非法：{item.filename}")
            bundle.extractall(folder)
        unpacked = Path(folder) / "robot-server-plugin"
        expected = unpacked / "lib" / f"instrumented-robot-server-plugin-{VERSION}.jar"
        if not expected.is_file():
            raise RuntimeError(f"插件包缺少主 jar：{expected}")
        unpacked.rename(TARGET)
    if not installed():
        raise RuntimeError(f"robot-server 插件描述校验失败：{TARGET}")
    print(f"robot-server {VERSION} 已下载并校验：{TARGET}")


if __name__ == "__main__":
    main()
