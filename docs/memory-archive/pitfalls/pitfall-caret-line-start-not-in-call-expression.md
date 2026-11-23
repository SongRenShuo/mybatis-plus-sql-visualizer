---
name: pitfall-caret-line-start-not-in-call-expression
description: 光标落在 `return client.queryForX(...)` 的 return
  关键字上时，调用表达式是兄弟节点不是祖先——getParentOfType(PsiMethodCallExpression) 返回
  null，按「文件+行号」定位的 MCP/快捷键退化成错源提示；须回落「包含调用的语句」再扫
metadata:
  node_type: memory
  type: project
  originSessionId: sess_2e6c34a8-130e-43a0-a784-b9d4d57a5188
---

# 行首关键字不在调用表达式里（20261111 实测）

## 形态

- `renderAtLine` / 快捷键默认把定位锚放在行内**首个非空白字符**。`return client.queryForObject("User.selectById", id);` 的锚是 `return` token，它挂在 `PsiReturnStatement` 下，**调用表达式是与它并列的兄弟节点**。
- `PsiTreeUtil.getParentOfType(element, PsiMethodCallExpression::class.java, false)` 因此返回 null。

## 后果

- `IbatisCallTargets.resolve` 返回 null → 链路落到 JDBC 分支 `toOutcome(null)` → MCP 返回 `[SOURCE_NOT_FOUND] the source at the given location resolved to no SQL (unsupported shape)`，即错源提示；实际要的 iBatis 语句就在同一行。
- 同族提醒：任何「按光标元素上爬找调用」的定位器在行首锚上都有同一缺口，只是此前靠别的分支兜住，别只修自己那一条。

## 修法（两跳）

1. 先 `getParentOfType(element, PsiMethodCallExpression::class.java, false)`——覆盖光标在方法名/实参/限定符内。
2. 为空则 `getParentOfType(element, PsiStatement::class.java, false)`，在该语句子树 `collectElementsOfType` 找首个形状命中的调用（取 `minByOrNull { textRange.startOffset }`）。
3. 语句也为空才返回 null（方法签名等落点交给方法级回落）。

## 回归锚

`test光标落在return关键字_仍定位到调用`：用 `file.findElementAt(text.indexOf("return client.queryForObject(\"User.selectById\""))` 直接驱动；MCP 用例按行号 5（调用行）/4（方法签名）分档，分别覆盖调用级与方法级回落。