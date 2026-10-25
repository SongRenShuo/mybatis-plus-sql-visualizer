package cn.song.mybatisplus.visualizer.execute;

/**
 * T-ExplainPwdPrompt：执行计划失败分型（呈现层据此选出口，纯逻辑 headless 直测）。
 * Kotlin 顶层 enum → 顶级 Java 枚举（k2j 一文件一公开类惯例）。
 */
public enum ExplainFailureKind {
    /** 认证失败（数据源密码未存/错误）——附「补存密码」指引，不再无谓回退直送（同通道必同样失败）。 */
    AUTH,

    /**
     * T-ExplainFailUx W1（2026-10-24 用户沙盒实测）：<b>选错数据源</b>——预览引用的表不在当前源
     * （{@code Table 'mp_demo.wms_inventory_jar_demo' doesn't exist}）。这是最典型的「该换源」信号：
     * 不弹模态框，就地引导切换数据源（换源后自动重试一次，见 W3）。
     */
    WRONG_TARGET,

    /** 用户取消（原生密码框 Esc 等）——轻通知，绝不自动重试（重试会再弹一次框）。 */
    CANCELLED,

    /** 其余——维持既有回退直送链。 */
    OTHER,
}
