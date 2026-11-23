---
name: pitfall-java-linkage-visible-at-link-time
description: Java 类在链接期就要求全部引用类型可见（含 StackMapTable 帧与父接口），与调用哪个方法无关——「只删 implements 或只不动那个方法」都躲不掉依赖；另附常量池死条目会让字符串 grep 误判重定位
metadata:
  node_type: memory
  type: project
  originSessionId: sess_86e18fbd-6049c1be8e5643c39c0d40c1
---

# 类链接期就要全可见：删 implements / 不调那个方法都躲不掉

20261022 T-JRebelPremainCrash 期间实测（demo 仓 `agent-premain-hostile-loader-repro` 的 `linkage` 与 `hypothesis` 两格，Corretto 21.0.12.1）。

## 三条实测结论

1. **最小对照**：一个类里 `untouched(String)` 与某不可见类型毫无引用关系，但**只调 `untouched` 仍然抛 `NoClassDefFoundError`**（cause 为对应 CNFE）。⇒ 类的链接期就要求**全部**引用类型可见，包括方法体、描述符、**StackMapTable 帧**里的类型；与被调用的是哪个方法无关。
2. **删 `implements` 无效**：造出 `interfaces_count=0` 的 `StatementWeaver` 副本后重跑，**仍在同一行 `DriverRegistry.isStatementWeavable:61` 抛**，只是被拒的 ASM 类名从 `Opcodes` 换成 `tree/AbstractInsnNode`（另一次运行换成 `ClassVisitor`，取决于 JVM 解析顺序）。原因：该类方法体有 6 处 `getstatic Opcodes.LONG/DOUBLE/TOP:Ljava/lang/Integer;`——**`Integer` 装箱静态字段，不是可被 javac 内联的 `int` 常量**。对比 `ConnectWeaver`：`Opcodes` 引用**只有类头一处**（int 常量全被内联），故对它删 implements 是有效的。
3. **判「删接口有没有用」不能只看 `int` 常量是否内联**：要用 `javap -p -c -v` 数**活引用**（方法体 `getstatic/getfield`、描述符、帧），只看类头 `implements` 会误判。

## 常量池假象（会让 grep 得出反向结论）

`StatementWeaver.class` 常量池里有 **12 个 `org/objectweb/asm/*` Class 项，全部落在引用闭包之外**（`ClassWriter(ClassReader,flags)` 拷贝常量池留下的死条目）。**用字符串搜索 class 文件会误判「ASM 没被重定位」**；核验重定位/依赖必须按活引用判定，或直接 `javap -p -c` 看方法体。

## 兜底类型的推论

同一失败点在另一种加载器形态（`-Djava.system.class.loader`）下抛的是 **`ClassCircularityError`** 而不是 NCDFE——二者只有共同父类 `Throwable`。⇒ 给类初始化/类解析路径做兜底时，**`catch (Throwable)` 是唯一安全选择**，只捕 `LinkageError`/`NoClassDefFoundError` 会漏。

**Why:** 「删掉那行 implements 就不依赖了」「只要不调那个方法就不会炸」都是直觉误判；JVM 的可见性要求发生在链接期，比调用期早得多。

**How to apply:** 想让某个「查表/查名字」的轻量入口摆脱重依赖包时，正解是**把数据搬到零依赖的独立类**（本仓 = `WeaveSpecs`），而不是删接口或延迟调用。参见 [[project-jrebel-premain-crash]]、[[pitfall-java-port-db-plugin-classloading]]。
