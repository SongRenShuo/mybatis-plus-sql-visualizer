---
name: pitfall-tomcat-url-factory-jvm-once-after-swap
description: 插件热替换后内嵌 Tomcat 起不来（A child container failed during
  start）——URL.setURLStreamHandlerFactory 是 JVM 级一次性调用，旧 loader 的 Tomcat 已注册，新
  loader 再注册抛 factory already defined；用 Tomcat 自带 disable() 预跳过
metadata:
  node_type: memory
  type: feedback
  originSessionId: sess_cddb53d3-1842-447e-a693-e38c5fbee3e5
---

# 热替换后内嵌 Tomcat 起不来：JVM 级 URL 工厂只能注册一次

## 症状

更新完成后 MCP 设置页「服务状态：未运行」，最近错误 `A child container failed during start`；日志：

```
SEVERE org.apache.catalina.core.ContainerBase - A child container failed during start
Caused by: java.lang.Error: factory already defined
  at java.base/java.net.URL.setURLStreamHandlerFactory(URL.java:1321)
  at org.apache.catalina.webresources.TomcatURLStreamHandlerFactory.<init>(...:121)
  at ...getInstanceInternal(...:52)  at ...register(...:73)
  at org.apache.catalina.webresources.StandardRoot.registerURLStreamHandlerFactory(...:698)
  → dev.coolrequest.mcp.CoolRequestMCPServer.startMCPServer
```

## 根因

`java.net.URL.setURLStreamHandlerFactory` 是 **JVM 级一次性**。旧 classloader 的 Tomcat 在首次启动时注册过；热替换后新 classloader 的 Tomcat 类静态状态是全新的（`registered` 标志不共享），于是又去注册 → `Error: factory already defined`。

## 修法（tomcat-embed-core 11.0.18 字节码实证）

`TomcatURLStreamHandlerFactory.disable()` 走 `getInstanceInternal(false)`：**实例照建并占住静态 instance**（`getInstance()` 仍返回可用工厂，`war:`/`classpath:` 资源处理不受影响），但构造时**不调用** JVM 级注册；因 instance 已非空，之后任何 `register()` 都直接复用，不会二次注册。

落地：
1. **JVM 级标记**（`System.getProperty`，跨 classloader 存活）在首次成功启动后写下；
2. 热替换后的新实例看到标记 → 启动前先 `disable()`，**一次成功、无 SEVERE 噪音**；
3. 标记缺失但仍冲突（老版本没写标记）→ 丢弃半启动容器、`disable()`、**重试一次**；
4. 所有内嵌 Tomcat（MCP 服务器、静态资源服务器）都要接这道闸。

## 探针

- 冲突识别用例：把 `Error("factory already defined")` 包三层（ExecutionException→LifecycleException→Error）仍要识别；
- `disable()` 可用性用例：随包 Tomcat 必须提供 `public static void disable()`（`javap -c` 已证）；
- 真机判据：热更新后 MCP 页「服务状态」为运行中、端口 8019 监听、无 `factory already defined`。

相关：[[pitfall-self-update-callback-must-be-service-free]]、[[project-coolrequest-vip-update-channel-port]]
