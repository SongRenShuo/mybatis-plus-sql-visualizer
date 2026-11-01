---
name: pitfall-sandbox-jdbc-driver-needs-installed-state
description: 沙盒里配 JDBC 驱动要两份文件——config/jdbc-drivers/（目录清单+j ar）只决定"有哪些版本"，config/options/databaseDrivers.xml（已安装状态）才决定"哪个 driver id 用哪个 artifact 版本"；只拷前者必 ClassNotFoundException
metadata:
  node_type: memory
  type: project
  originSessionId: sess_da4753b3-ef7f-4a1f-b5d2-a46a94883c80
---

# 沙盒 JDBC 驱动要两份文件，缺一必 CNFE（2026-11-01 实锤）

给 runIde 沙盒配数据源时，`ClassNotFoundException: com.mysql.cj.jdbc.Driver` 排查了三轮才定位。沙盒里驱动相关有**两份互不替代**的文件：

| 文件 | 作用 | 只有它会怎样 |
|---|---|---|
| `config/jdbc-drivers/jdbc-drivers.xml` + 各驱动目录（Maven 布局的 jar） | **目录清单**：声明"存在哪些 artifact / 哪些版本 / 每个版本的 maven 坐标与 license" | IDE 知道有这个版本，但不知道**你的 driver id 该绑哪个版本** → 仍报「No driver files configured」 |
| `config/options/databaseDrivers.xml` | **已安装状态**：`<driver id="mysql.8"><artifact id="MySQL ConnectorJ" version="9.7.0"/></driver>` | 缺它 → `driver-ref` 解析不到 artifact → 远程 JDBC 进程 classpath 空 → **CNFE** |

**正确做法**：把宿主 IDE 的 `config/options/databaseDrivers.xml` 整份拷进沙盒（它同时绑定了 mysql.8 / postgresql / redis 等），并确认 `config/jdbc-drivers/` 里对应版本的 jar 实体在场。拷完**必须重启 IDE**（两份都在启动期读）。

**判据**：日志出现 `RemoteProcessSupport … ClassNotFoundException: <驱动类>` 就是这两份没配齐，**不是插件缺陷**。别去怀疑数据源 URL、凭据、方言——那几样只会让连接失败，不会让类加载失败。

**踩过的弯路（别重复）**：
1. 先改数据源 `<driver-ref>` 为 `MySQL ConnectorJ/9.7.0` → IDE 加载时**回写成 `mysql.8`**（driver-ref 只认 driver id，不认 `artifact/version` 写法）。
2. 再往目录清单里补 `<version version="9.7.0">` → 无效，因为缺的是"已安装状态"那份。
3. 最后整拷 `databaseDrivers.xml` → CNFE 立刻消失（时间戳可证：拷贝前 16:48 有 4 次，拷贝后 16:53 起零次）。

**反面参照别误用**：另一个沙盒（`MPSV-RealMachine`）的 `databaseDrivers.xml` 同样只有 DM/GaussDB，但那一轮 MySQL 能连通——因为那次是**经 GUI 点了「Download missing driver files」**，平台自己把状态写进去了。文件拷贝复刻不了这一步的副作用，所以不能拿"别人的文件也是空的"当反证。

相关：[[pitfall-sandbox-license-needs-ideakey]]（同类"文件就位 ≠ 生效"）、[[pitfall-sandbox-jar-staleness-and-window-enum]]。
