# 代码编辑规范：grep 优先读取

> **用途**：修改大文件（>300 行）时，用 grep 定位行号 + 精确 Read，替代全文读取。
> **触发条件**：用户主动引用本文件，或修改涉及 >300 行的文件时。

---

## 核心原则

> **先 grep 定位行号，再 Read 精确范围，最后 Edit 修改。绝不 Read 整个大文件。**

## 分级操作

### Level 1：简单修改（改文案 / 改条件 / 改一行）

```bash
grep -n "目标内容" 文件.kt    # 定位行号
Read 行号-5 ~ 行号+5          # 只看上下文
```

token 消耗：<100。不需要看全文。

### Level 2：修改方法逻辑（改参数 / 加返回值 / 改条件分支）

```bash
grep -n "方法名" 文件.kt           # 找方法体范围
Read 方法体                        # 读完整方法
grep -n "涉及的字段名/类名" 文件.kt  # 找依赖的字段和类型
Read 字段声明区域                   # 确认类型和可空性
```

token 消耗：~400。两轮 grep + 两轮小范围 Read。

### Level 3：重构 / 拆分（大改）

```bash
grep -n "fun \|private \|class \|val \|var " 文件.kt  # 列出所有声明
Read 关键区间（字段区 + 要改的方法 + 相关方法）
```

token 消耗：~2K。先看清结构，再定向读取。

## 什么时候必须读全文

只有一种情况：**你不知道这个文件里有什么，也不知道要改哪里**。这时候需要探索。但探索完之后，后续修改都应该用 grep 定位。

## 常用 grep 命令

```bash
# 找某个方法在哪个文件
grep -rn "fun formatSql" src/main/kotlin/ --include="*.kt"

# 找某个方法被哪些地方调用
grep -rn "formatSql()" src/main/kotlin/ --include="*.kt"

# 找某个常量/配置在哪里定义
grep -rn "NOTIFICATION_GROUP_ID" src/ --include="*.kt"

# 列出一个文件的所有方法名
grep -n "fun " 文件.kt | grep -v "//"

# 找某类的所有测试
find src/test -name "*SqlPreview*Test*.kt"

# 找某个 import 对应的类定义
grep -rn "class SqlFormatter" src/main/kotlin/
```

## 实际操作模板

```
任务：修改 formatSql() 支持自定义格式化规则

1. grep -n "fun formatSql" 文件.kt → 行 516
2. Read 516-522 → 看到方法体
3. grep -n "private var editor" → 行 147 → 确认类型 Editor?
4. grep -n "class SqlFormatter" → 定位到哪个文件
5. 修改
```

5 步，总 token < 500。全文读取要 6000。

## 会遗漏的场景（必须注意）

修改方法时，grep 只看到方法体，可能**遗漏依赖**：

- **字段声明**：方法里用 `editor`，但字段声明在文件顶部（可空性、类型）
- **import**：方法里用 `WriteCommandAction`，但 import 在文件头
- **相关方法**：修改 `formatSql()` 可能需要理解 `formatClauseLine()` 的逻辑
- **内部类**：修改面板布局可能涉及 `ThemedSplitPane`

**应对**：每次修改前，用 grep 先确认涉及的字段和依赖，再定向 Read。不要跳过"找依赖"这一步。

## token 对比

| 场景 | 全文 Read | grep + 精确 Read | 节省 |
|------|-----------|-------------------|------|
| 改一行文案 | 6,200 | <100 | 98% |
| 改方法逻辑 | 6,200 | ~400 | 93% |
| 重构拆分 | 6,200 | ~2,000 | 68% |

## 注意事项

- grep/find 命令跑在 shell 里，不消耗 token（是工具调用，不是 LLM 读取）
- 只有 Read 的内容才计入 token
- grep 输出的行号是 1-based
- Edit 的 old_string 必须与文件内容**逐字符匹配**，grep 定位后用 Read 确认精确内容再 Edit
