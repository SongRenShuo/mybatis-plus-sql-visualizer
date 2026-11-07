-- =============================================================
-- mybatis-plus-demo 初始化脚本
-- 数据库：MySQL 5.7+ / 8.0
-- 使用前先创建库：CREATE DATABASE IF NOT EXISTS mp_demo DEFAULT CHARSET utf8mb4;
-- 执行：mysql -uroot -p mp_demo < schema.sql
--
-- 来源：mybatis-plus-sql-visualizer-demo @74cc257
--       mybatis-plus-demo/src/main/resources/db/schema.sql
-- 许可：MIT（Copyright (c) 2026 SongRenShuo）
-- 同步方式：见 dev-support/test-demo/README.md「更新 demo 快照」一节
-- =============================================================

USE mp_demo;

DROP TABLE IF EXISTS t_order;
DROP TABLE IF EXISTS user;
DROP TABLE IF EXISTS department;
DROP TABLE IF EXISTS role;
DROP TABLE IF EXISTS sys_config;
DROP TABLE IF EXISTS user_role;
DROP TABLE IF EXISTS wkfl_proc;
DROP TABLE IF EXISTS wkfl_task;
DROP TABLE IF EXISTS wkfl_solicit;
DROP TABLE IF EXISTS core_apply_duties;

-- 用户表
CREATE TABLE `user` (
    `id`          INT          NOT NULL AUTO_INCREMENT COMMENT '主键',
    `username`    VARCHAR(64)  NOT NULL COMMENT '姓名',
    `password`    VARCHAR(128) DEFAULT NULL COMMENT '密码',
    `age`         INT          DEFAULT NULL COMMENT '年龄',
    `email`       VARCHAR(128) DEFAULT NULL COMMENT '邮箱',
    `role_id`     INT          DEFAULT NULL COMMENT '角色ID',
    `dept_id`     INT          DEFAULT NULL COMMENT '部门ID',
    `status`      TINYINT      DEFAULT 1 COMMENT '状态（0禁用 1启用）',
    `remark`      VARCHAR(255) DEFAULT NULL COMMENT '备注（Wrapper set/notLike 演示列）',
    `create_time` DATETIME     DEFAULT NULL COMMENT '创建时间',
    `update_time` DATETIME     DEFAULT NULL COMMENT '更新时间',
    `deleted`     TINYINT      DEFAULT 0 COMMENT '逻辑删除（0未删除 1已删除）',
    `version`     INT          DEFAULT 0 COMMENT '乐观锁版本号',
    PRIMARY KEY (`id`),
    KEY `idx_username` (`username`),
    KEY `idx_role_id` (`role_id`),
    KEY `idx_dept_id` (`dept_id`)
) ENGINE = InnoDB COMMENT = '用户表';

-- 角色表
CREATE TABLE `role` (
    `id`          INT         NOT NULL AUTO_INCREMENT COMMENT '主键',
    `name`        VARCHAR(64) NOT NULL COMMENT '角色名称',
    `code`        VARCHAR(32) DEFAULT NULL COMMENT '角色编码',
    `description` VARCHAR(255) DEFAULT NULL COMMENT '角色描述',
    PRIMARY KEY (`id`)
) ENGINE = InnoDB COMMENT = '角色表';

-- 部门表
CREATE TABLE `department` (
    `id`        INT         NOT NULL AUTO_INCREMENT COMMENT '主键',
    `name`      VARCHAR(64) NOT NULL COMMENT '部门名称',
    `code`      VARCHAR(32) DEFAULT NULL COMMENT '部门编码',
    `parent_id` INT         DEFAULT NULL COMMENT '上级部门ID',
    `sort`      INT         DEFAULT 0 COMMENT '排序号',
    PRIMARY KEY (`id`)
) ENGINE = InnoDB COMMENT = '部门表';

-- 订单表
CREATE TABLE `t_order` (
    `id`          INT           NOT NULL AUTO_INCREMENT COMMENT '主键',
    `order_no`    VARCHAR(64)   NOT NULL COMMENT '订单编号',
    `user_id`     INT           DEFAULT NULL COMMENT '用户ID',
    `amount`      DECIMAL(10, 2) DEFAULT NULL COMMENT '订单金额',
    `status`      TINYINT       DEFAULT 0 COMMENT '状态（0待支付 1已支付 2已取消）',
    `create_time` DATETIME      DEFAULT NULL COMMENT '创建时间',
    PRIMARY KEY (`id`),
    KEY `idx_user_id` (`user_id`)
) ENGINE = InnoDB COMMENT = '订单表';

-- 系统配置表
CREATE TABLE `sys_config` (
    `id`           INT          NOT NULL AUTO_INCREMENT COMMENT '主键',
    `config_key`   VARCHAR(128) NOT NULL COMMENT '配置键',
    `config_value` VARCHAR(255) DEFAULT NULL COMMENT '配置值',
    `config_type`  INT          DEFAULT NULL COMMENT '配置类型（1系统配置 2业务配置 3安全配置，对应 ConfigTypeEnum @EnumValue code）',
    `remark`       VARCHAR(255) DEFAULT NULL COMMENT '备注',
    `sort_no`      INT          DEFAULT 0 COMMENT '排序号（@OrderBy 默认倒序）',
    PRIMARY KEY (`id`)
) ENGINE = InnoDB COMMENT = '系统配置表';

-- 用户角色关系表（WrapperExtController#listByApplyAndExists 的 notExists 子查询）
CREATE TABLE `user_role` (
    `id`      INT NOT NULL AUTO_INCREMENT COMMENT '主键',
    `user_id` INT DEFAULT NULL COMMENT '用户ID',
    `role_id` INT DEFAULT NULL COMMENT '角色ID',
    PRIMARY KEY (`id`),
    KEY `idx_ur_user_id` (`user_id`)
) ENGINE = InnoDB COMMENT = '用户角色关系表';

-- =============================================================
-- 工作流演示表（StringSqlDaoDemoServiceImpl 自研框架字符串 SQL 场景）
-- 表名列名列对齐真实项目：大写书写 + 下划线命名
-- =============================================================

-- 流程定义表
CREATE TABLE `wkfl_proc` (
    `proc_id`      VARCHAR(64) NOT NULL COMMENT '流程实例ID',
    `proc_name`    VARCHAR(128) DEFAULT NULL COMMENT '流程名称',
    `proc_def_key` VARCHAR(128) DEFAULT NULL COMMENT '流程定义标识',
    `object_id`    VARCHAR(64) DEFAULT NULL COMMENT '业务对象ID',
    PRIMARY KEY (`proc_id`),
    KEY `idx_proc_def_key` (`proc_def_key`)
) ENGINE = InnoDB COMMENT = '工作流流程定义表';

-- 流程任务表
CREATE TABLE `wkfl_task` (
    `task_id`     VARCHAR(64) NOT NULL COMMENT '任务ID',
    `proc_id`     VARCHAR(64) DEFAULT NULL COMMENT '流程实例ID',
    `owner`       VARCHAR(64) DEFAULT NULL COMMENT '办理人',
    `finish_type` VARCHAR(32) DEFAULT NULL COMMENT '完成方式（NULL未办理）',
    PRIMARY KEY (`task_id`),
    KEY `idx_task_proc_id` (`proc_id`),
    KEY `idx_task_owner` (`owner`)
) ENGINE = InnoDB COMMENT = '工作流任务表';

-- 流程征询表
CREATE TABLE `wkfl_solicit` (
    `solicit_id` VARCHAR(64) NOT NULL COMMENT '征询ID',
    `proc_id`    VARCHAR(64) DEFAULT NULL COMMENT '流程实例ID',
    PRIMARY KEY (`solicit_id`),
    KEY `idx_solicit_proc_id` (`proc_id`)
) ENGINE = InnoDB COMMENT = '工作流征询表';

-- 担保函申请业务表
CREATE TABLE `core_apply_duties` (
    `apply_id`    VARCHAR(64) NOT NULL COMMENT '申请ID',
    `policy_name` VARCHAR(128) DEFAULT NULL COMMENT '政策名称',
    PRIMARY KEY (`apply_id`)
) ENGINE = InnoDB COMMENT = '担保函申请业务表';

-- 初始数据
INSERT INTO `role` (`id`, `name`, `code`, `description`) VALUES
(1, '管理员', 'ADMIN', '系统管理员'),
(2, '普通用户', 'USER', '普通用户'),
(3, '访客', 'GUEST', '游客');

INSERT INTO `department` (`id`, `name`, `code`, `parent_id`, `sort`) VALUES
(1, '技术部', 'TECH', NULL, 1),
(2, '产品部', 'PROD', NULL, 2),
(3, '前端组', 'FE', 1, 1);

INSERT INTO `user` (`id`, `username`, `password`, `age`, `email`, `role_id`, `dept_id`, `status`, `create_time`, `update_time`, `deleted`, `version`) VALUES
(1, 'admin', '123456', 30, 'admin@example.com', 1, 1, 1, NOW(), NOW(), 0, 0),
(2, 'zhangsan', '123456', 22, 'zhangsan@qq.com', 2, 1, 1, NOW(), NOW(), 0, 0),
(3, 'lisi', '123456', 25, 'lisi@163.com', 2, 2, 1, NOW(), NOW(), 0, 0),
(4, 'wangwu', '123456', 18, NULL, 3, 3, 0, NOW(), NOW(), 0, 0);

INSERT INTO `t_order` (`id`, `order_no`, `user_id`, `amount`, `status`, `create_time`) VALUES
(1, 'NO20260101001', 1, 199.90, 1, NOW()),
(2, 'NO20260101002', 1, 59.00, 0, NOW()),
(3, 'NO20260102001', 2, 299.00, 1, NOW()),
(4, 'NO20260102002', 3, 120.50, 2, NOW());

INSERT INTO `sys_config` (`id`, `config_key`, `config_value`, `config_type`, `remark`, `sort_no`) VALUES
(1, 'site.name', 'mp-demo', 1, '站点名称', 3),
(2, 'order.payTimeout', '30', 2, '订单支付超时时间（分钟）', 2),
(3, 'password.minLength', '6', 3, '密码最小长度', 1);

INSERT INTO `user_role` (`id`, `user_id`, `role_id`) VALUES
(1, 1, 1);

INSERT INTO `wkfl_proc` (`proc_id`, `proc_name`, `proc_def_key`, `object_id`) VALUES
('P001', '担保函申请流程', 'CoreApplyDuties', 'A001'),
('P002', '创业担担保函流程', 'CoreApplyDutiesEntrep', 'A002'),
('P003', '普通审批流程', 'NormalAudit', NULL);

INSERT INTO `wkfl_task` (`task_id`, `proc_id`, `owner`, `finish_type`) VALUES
('T001', 'P001', '1', NULL),
('T002', 'P001', '1', NULL),
('T003', 'P002', '1', NULL),
('T004', 'P003', '2', NULL),
('T005', 'P003', '2', 'AGREE');

INSERT INTO `wkfl_solicit` (`solicit_id`, `proc_id`) VALUES
('S001', 'P001');

INSERT INTO `core_apply_duties` (`apply_id`, `policy_name`) VALUES
('A001', '非创业担保政策'),
('A002', '创业担保贷款政策');

-- 混合注解实体（T-Embedded G3 语料）：值对象列展平（theme/notify_email/max_retry），无 setting 伪列
DROP TABLE IF EXISTS `t_mixed_profile`;
CREATE TABLE `t_mixed_profile` (
    `id`           BIGINT       NOT NULL AUTO_INCREMENT COMMENT '主键',
    `name`         VARCHAR(64)  DEFAULT NULL COMMENT '名称',
    `theme`        VARCHAR(32)  DEFAULT NULL COMMENT '界面主题（嵌入值对象展平列）',
    `notify_email` VARCHAR(128) DEFAULT NULL COMMENT '通知邮箱（嵌入值对象展平列）',
    `max_retry`    INT          DEFAULT NULL COMMENT '最大重试次数（嵌入值对象展平列）',
    `remark`       VARCHAR(255) DEFAULT NULL COMMENT '备注',
    PRIMARY KEY (`id`)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '混合注解实体（静态预览语料）';
