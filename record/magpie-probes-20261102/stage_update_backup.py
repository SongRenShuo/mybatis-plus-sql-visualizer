# -*- coding: utf-8 -*-
"""一次性探针（T-MagpieGems 验收 MG-08）：为沙盒预置 UpdateBackupStore 真备份。

用法：python stage_update_backup.py <sandboxRoot>
  sandboxRoot 形如 F:/code/Java/Gitee/SongRenShuo/mpsv-magpie-gems/build/MPSV-MagpieGems-UI243/IU-2024.3.7.1

复刻 UpdateBackupStore 的磁盘形态（UpdateBackupStore.kt 实测）：
  backupDir  = <sandbox>/system/plugins/update-backup/
  zip        = rollback-<pluginDirName>-<version>.zip（顶层 entry = 插件目录名）
  manifest   = JDK Properties：fromVersion / sha256 / createdAt / pluginDirName
"""
import hashlib
import io
import os
import sys
import time
import zipfile


def sha256_file(path):
    h = hashlib.sha256()
    with open(path, "rb") as f:
        for chunk in iter(lambda: f.read(65536), b""):
            h.update(chunk)
    return h.hexdigest()


def main():
    sandbox = sys.argv[1].replace("\\", "/").rstrip("/")
    plugins = os.path.join(sandbox, "plugins")
    if not os.path.isdir(plugins):
        print("NO_PLUGINS_DIR", plugins)
        return 2
    candidates = [d for d in os.listdir(plugins)
                  if d.startswith("mybatis-plus-sql-visualizer") and os.path.isdir(os.path.join(plugins, d))]
    if not candidates:
        print("NO_PLUGIN_DIR", os.listdir(plugins))
        return 2
    plugin_dir_name = candidates[0]
    plugin_dir = os.path.join(plugins, plugin_dir_name)

    # 版本：从插件 jar 的 plugin.xml 或目录名后缀取；沙盒目录名无版本时读 lib jar 内 META-INF/plugin.xml
    version = None
    lib = os.path.join(plugin_dir, "lib")
    for jar in sorted(os.listdir(lib)):
        if not jar.endswith(".jar"):
            continue
        try:
            with zipfile.ZipFile(os.path.join(lib, jar)) as z:
                for name in z.namelist():
                    if name.endswith("META-INF/plugin.xml"):
                        text = z.read(name).decode("utf-8", "replace")
                        for line in text.splitlines():
                            if "<version>" in line:
                                version = line.split("<version>")[1].split("</version>")[0].strip()
                                break
                        if version:
                            break
        except zipfile.BadZipFile:
            continue
        if version:
            break
    if not version:
        print("NO_VERSION")
        return 2

    backup_root = os.path.join(sandbox, "system", "plugins", "update-backup")
    os.makedirs(backup_root, exist_ok=True)
    zip_path = os.path.join(backup_root, "rollback-%s-%s.zip" % (plugin_dir_name, version))
    print("ZIPPING", plugin_dir, "->", zip_path)
    with zipfile.ZipFile(zip_path, "w", zipfile.ZIP_DEFLATED) as zos:
        for root, dirs, files in os.walk(plugin_dir):
            rel_root = os.path.relpath(root, os.path.dirname(plugin_dir)).replace("\\", "/")
            if root != plugin_dir:
                zos.writestr(zipfile.ZipInfo(rel_root + "/"), b"")
            for fn in sorted(files):
                full = os.path.join(root, fn)
                entry = os.path.relpath(full, os.path.dirname(plugin_dir)).replace("\\", "/")
                zos.write(full, entry)
    digest = sha256_file(zip_path)

    manifest = os.path.join(backup_root, "manifest.json")
    # JDK Properties.store 兼容形态（key=value；读取只认四键；注释行用 ASCII 防 locale 坑）
    with io.open(manifest, "w", encoding="iso-8859-1") as f:
        f.write("#mpsv update rollback backup (T-SelfUpdateRollback O-150)\n")
        f.write("fromVersion=%s\n" % version)
        f.write("sha256=%s\n" % digest)
        f.write("createdAt=%d\n" % int(time.time() * 1000))
        f.write("pluginDirName=%s\n" % plugin_dir_name)
    print("STAGED version=%s zip=%s sha256=%s manifest=%s" % (version, zip_path, digest[:12], manifest))
    return 0


if __name__ == "__main__":
    sys.exit(main())
