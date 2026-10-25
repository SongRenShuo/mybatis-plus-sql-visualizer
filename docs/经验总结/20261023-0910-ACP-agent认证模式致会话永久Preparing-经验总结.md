# ACP agent 认证模式导致会话永久 Preparing 的排查与修复（2026-10-23）

> 任务背景：T-DocsRefresh1023 文档站动图重录，R10 `mcp-agent.gif` 需要 AI Assistant 面板里的 ACP agent（Codex / Claude）真实调用 MCP 工具并入镜。
> 结果：**已定位并修复**，agent 可正常使用本地 key（第三方 Anthropic 兼容端点）。

---

## 一、现象

在录制沙盒（`build/MPSV-GifFresh`，IU-2026.2.1 / 262 平台）里，右侧 AI 面板选择任一 ACP agent（Codex、Claude Agent）后发送任务：

- 面板永久停在 `Preparing...`，输入框显示 `Waiting...`
- 面板中部固定显示 **「Choose authentication method」**，只有 `JetBrains` 与 `Anthropic Console` 两个选项
- 点击这两个选项均无任何反应（不报错，也无日志）

同一台机器上，**日常 IDE（`%APPDATA%\JetBrains\IntelliJIdea2026.2`）用同一个 Claude agent 可以直接跑**，走的是 `~/.claude/settings.json` 里配置的第三方端点（`ANTHROPIC_BASE_URL` + `ANTHROPIC_AUTH_TOKEN`，DeepSeek 的 Anthropic 兼容接口）。

## 二、排除法（都证伪过，别再走这些弯路）

| 怀疑点 | 实测结论 |
|---|---|
| 许可 / 订阅失效 | 无关。同沙盒内 Database、EXPLAIN、SQL 预览全部正常；标题栏无 Start Free Trial |
| 本地 key 不被认 | 无关。用 adapter 自带的原生 CLI 手动跑 `claude.exe -p "..."`，经 settings.json 端点**正常返回** |
| adapter 包坏了 | 无关。升级后的 `claude-acp@0.94.0` 手动发起 ACP 握手（`initialize` → `session/new`，带 SSE mcpServer、IDE 同款 env）**2 秒全通**，返回 sessionId |
| adapter 入口搞错 | 踩过一次：`dist/acp-agent.js` 是库模块，直接跑会静默 `exit 0`；正确入口是 `dist/index.js`（见 package.json 的 `bin`） |
| 进程残留 | 杀干净整棵进程树 + New Chat 后仍卡（`Reusing existing process handler` 那句是干扰项，不是根因） |
| 升级中断 | 装包在 130s 内完成，`AcpPackageInstaller - Installed package ...` 有正常收尾日志 |

**决定性证据**：IDE 的 `session/new` 请求**从未到达 adapter**——进程树里 adapter node 活着，但它下面**没有任何 `claude.exe` / codex 子进程**；日志停在

```
AcpAuthenticationService - acp.registry.claude-acp: get auth EXPLICIT_SELECTION
```

之后无任何后续。这说明卡在 IDE 侧的认证决策，而不是 agent 侧。

## 三、根因

`options/acpAgents.xml` 里的 `AcpAgentSettings.agent_auth` 决定「IDE 是否强制每次会话显式选认证」：

| 值 | 所属枚举 | 语义 |
|---|---|---|
| `MANAGED_EXTERNALLY` | `AcpAgentAuthentication$AGENT`（唯一值） | 认证交给 agent 自己管——直接读 agent 自己的配置（`~/.claude/settings.json` 的 base_url / token），IDE 不介入 |
| `AUTO_SELECTION` | `AcpAgentAuthentication$REQUIRED` | IDE 自动挑一种认证 |
| `EXPLICIT_SELECTION` | `AcpAgentAuthentication$REQUIRED` | **每次会话都必须由用户在面板显式选一次** |
| `JETBRAINS_AI` / `API_KEY` / `VERTEX_KEY` / `VERTEX_ADC` | `AcpAgentAuthentication$IDE` | IDE 侧代付/供 key |

沙盒里是 `EXPLICIT_SELECTION`，于是每次会话都要求用户选择；而由于 adapter 启动参数带有 `--hide-claude-auth`（见 `hide-claude-auth.js` 的 `shouldHideClaudeAuth()`，参数由 IDE 传入），**「agent 自管」这个选项被隐藏**，面板只剩 JetBrains / Anthropic Console 两项；这两项又需要 IDE 侧凭据（JetBrains 账号 / 官方 Anthropic key），与本地 DeepSeek token 都不匹配，于是选择永远完不成，`session/new` 永远不发。

日常 IDE 该值是 `MANAGED_EXTERNALLY`，所以「用本地 key 直接跑」是正常状态，不是特殊配置。

## 四、修复

**只能改文件**（设置页没有这个开关，见下节）。文件路径：

- 日常：`%APPDATA%\JetBrains\IntelliJIdea2026.2\options\acpAgents.xml`
- 沙盒：`<sandboxContainer>/config/options/acpAgents.xml`，本例为
  `build/MPSV-GifFresh/mybatis-plus-sql-visualizer/IU-2026.2.1/config/options/acpAgents.xml`

```xml
<application>
  <component name="AcpAgentSettings">
    <option name="agent_auth">
      <map>
        <entry key="acp.registry.claude-acp" value="MANAGED_EXTERNALLY" />
        <entry key="acp.registry.codex-acp" value="MANAGED_EXTERNALLY" />
        <entry key="acp.registry.opencode" value="MANAGED_EXTERNALLY" />
      </map>
    </option>
    <option name="agent_config_options">
      <map>
        <entry key="acp.registry.claude-acp">
          <value>
            <AgentConfigOptionsState>
              <option name="values">
                <map>
                  <entry key="mode" value="auto" />
                </map>
              </option>
            </AgentConfigOptionsState>
          </value>
        </entry>
      </map>
    </option>
  </component>
</application>
```

**顺序硬约束：先完全退出 IDE → 再改文件 → 再启动。** IDE 退出时会把内存态回写覆盖这个文件，运行中改必被冲掉。

## 五、为什么设置页改不了

反编译 `plugins/ml-llm/lib/modules/intellij.ml.llm.agents.acp.jar` 后确认：

- 写入口只有 `AcpAuthenticationService.setSelectedAgentAuth(AcpAgentId, AcpAgentAuthentication)`，它由面板的 `AcpAuthenticationChooser`（即「Choose authentication method」选择器）调用
- `settings/` 包下的类（`AcpModelManager` / `AcpModeManager` / `AcpConfigOptionsManager` 等）都是管理 model/mode/configOptions，**没有任何 configurable 暴露 `agent_auth`**
- 因此在 `Settings → Tools → AI Assistant → Agents` 里找不到「认证模式」这一项

**面板上唯一能写这个值的动作就是完成一次认证选择**——问题在于沙盒里那次选择永远完不成（选项被 `--hide-claude-auth` 裁掉了）。所以对于「想用 agent 自带 key」这个目标，界面路径是死路，必须改文件。

## 六、验证

改完启动后看日志：

```bash
grep "get auth" <sandbox>/log/idea.log | tail -3
```

期望从原来的

```
acp.registry.claude-acp: get auth EXPLICIT_SELECTION
```

变为

```
acp.registry.claude-acp: get auth MANAGED_EXTERNALLY
```

实测输出（2026-10-23 09:02）：

```
2026-10-23 08:52:19 INFO - AcpAuthenticationService - acp.registry.claude-acp: set auth EXPLICIT_SELECTION   ← 改前
2026-10-23 09:02:37 INFO - AcpAuthenticationService - acp.registry.claude-acp: get auth MANAGED_EXTERNALLY   ← 改后
2026-10-23 09:03:18 INFO - AcpAuthenticationService - acp.registry.claude-acp: get auth MANAGED_EXTERNALLY
```

改后用户在面板中直接发任务即可，不再出现认证选择，agent 走 `~/.claude/settings.json` 的端点。

## 七、可复用的排查口诀

> **「日常 IDE 能用、沙盒不能用」的第一反应应该是 diff 两边的 `config/options/*.xml`，而不是怀疑许可、网络或 token。**

一条命令定位：

```bash
diff "%APPDATA%/JetBrains/IntelliJIdea2026.2/options/acpAgents.xml" \
     "<sandbox>/config/options/acpAgents.xml"
```

本例两边只差 `agent_auth` 的三条 entry 和一段 `agent_config_options`，其余 AI 相关文件（`llm.for.code.xml` 等）完全一致——差异面越小，越说明根因就在那几条里。

## 八、遗留

- 沙盒的 `agent_config_options` 目前只写了 `mode=auto`（日常还有 `model=opus`、`fast=off`；日常的 model 值是在 DeepSeek 端点下的本地映射，照抄到沙盒未必等价，故未带）
- 该修复只影响 ACP 会话建立，与插件自身功能无关；R10 录制可继续
