# FINDINGS-DETAIL — mybatis-plus-sql-visualizer（run-1）

本次运行产出 **零条 confirmed 发现**。按审计契约，confirmed 严重级别要求「跨所述边界的、有界本地观测结果」，而本机无法提供该条件（无 OS 级沙箱用于执行目标受控代码）。因此全部 25 条经独立复核的线索均保留为 `needs_validation` 记录，附精确阻塞点与解决方案——见 NEEDS-VALIDATION.md。当某条线索的本地检查落地后，从该文档升级为 confirmed；本文与 NEEDS-VALIDATION.md 中的任何条目都不应被视为已确认漏洞。
