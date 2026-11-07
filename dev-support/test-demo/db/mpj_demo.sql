-- =============================================================
-- mybatis-plus-join-demo 初始化脚本（MPJ 多表联查用例数据）
-- 数据库：MySQL 5.7+ / 8.0
-- 使用前先创建库：CREATE DATABASE IF NOT EXISTS mpj_demo DEFAULT CHARSET utf8mb4;
-- 执行：mysql -uroot -p mpj_demo < schema.sql
--
-- 来源：mybatis-plus-sql-visualizer-demo @74cc257
--       mybatis-plus-join-demo/src/main/resources/db/schema.sql
-- 许可：MIT（Copyright (c) 2026 SongRenShuo）
-- 同步方式：见 dev-support/test-demo/README.md「更新 demo 快照」一节
-- =============================================================

USE mpj_demo;

DROP TABLE IF EXISTS car_device_info;
DROP TABLE IF EXISTS station_info;
DROP TABLE IF EXISTS user_address;
DROP TABLE IF EXISTS area;
DROP TABLE IF EXISTS `user`;
DROP TABLE IF EXISTS `user_2026`;

-- 用户表（MPJ 主表）
CREATE TABLE `user` (
    `id`       INT         NOT NULL AUTO_INCREMENT COMMENT '主键',
    `name`     VARCHAR(64) NOT NULL COMMENT '姓名',
    `age`      INT         DEFAULT NULL COMMENT '年龄',
    `sex`      TINYINT     DEFAULT 1 COMMENT '性别（1男 2女）',
    `head_img` VARCHAR(255) DEFAULT NULL COMMENT '头像',
    PRIMARY KEY (`id`)
) ENGINE = InnoDB COMMENT = '用户表';

-- 用户表 2026（setTableName 动态表名演示：/mpj/user/page 使用 setTableName(name -> name + "_2026")）
CREATE TABLE `user_2026` (
    `id`       INT         NOT NULL AUTO_INCREMENT COMMENT '主键',
    `name`     VARCHAR(64) NOT NULL COMMENT '姓名',
    `age`      INT         DEFAULT NULL COMMENT '年龄',
    `sex`      TINYINT     DEFAULT 1 COMMENT '性别（1男 2女）',
    `head_img` VARCHAR(255) DEFAULT NULL COMMENT '头像',
    PRIMARY KEY (`id`)
) ENGINE = InnoDB COMMENT = '用户表2026';

-- 用户地址表（MPJ 关联表 user_address）
CREATE TABLE `user_address` (
    `id`      INT         NOT NULL AUTO_INCREMENT COMMENT '主键',
    `user_id` INT         NOT NULL COMMENT '用户ID',
    `tel`     VARCHAR(32) DEFAULT NULL COMMENT '电话',
    `address` VARCHAR(255) DEFAULT NULL COMMENT '详细地址',
    `area_id` INT         DEFAULT NULL COMMENT '地区ID',
    `status`  TINYINT     DEFAULT 1 COMMENT '状态（0禁用 1启用）',
    PRIMARY KEY (`id`),
    KEY `idx_user_id` (`user_id`),
    KEY `idx_area_id` (`area_id`)
) ENGINE = InnoDB COMMENT = '用户地址表';

-- 地区表（MPJ 三级关联表）
CREATE TABLE `area` (
    `id`       INT         NOT NULL AUTO_INCREMENT COMMENT '主键',
    `province` VARCHAR(32) DEFAULT NULL COMMENT '省份',
    `city`     VARCHAR(32) DEFAULT NULL COMMENT '城市',
    PRIMARY KEY (`id`)
) ENGINE = InnoDB COMMENT = '地区表';

-- 设备信息表（对齐插件测试 car_device_info）
CREATE TABLE `car_device_info` (
    `id`              INT         NOT NULL AUTO_INCREMENT COMMENT '主键',
    `station_id`      INT         DEFAULT NULL COMMENT '站点ID',
    `share_scheme_id` INT         DEFAULT NULL COMMENT '共享方案ID',
    `device_name`     VARCHAR(64) DEFAULT NULL COMMENT '设备名称',
    PRIMARY KEY (`id`),
    KEY `idx_station_id` (`station_id`)
) ENGINE = InnoDB COMMENT = '设备信息表';

-- 站点信息表（对齐插件测试 station_info）
CREATE TABLE `station_info` (
    `id`           INT         NOT NULL AUTO_INCREMENT COMMENT '主键',
    `station_id`   INT         DEFAULT NULL COMMENT '站点编码ID',
    `station_name` VARCHAR(64) DEFAULT NULL COMMENT '站点名称',
    `merchant_id`  INT         DEFAULT NULL COMMENT '商户ID',
    PRIMARY KEY (`id`),
    KEY `idx_station_id` (`station_id`)
) ENGINE = InnoDB COMMENT = '站点信息表';

-- 初始数据
INSERT INTO `user` (`id`, `name`, `age`, `sex`, `head_img`) VALUES
(1, '张三', 20, 1, 'img/zhangsan.png'),
(2, '李四', 25, 1, 'img/lisi.png'),
(3, '王五', 30, 2, 'img/wangwu.png');

INSERT INTO `user_2026` (`id`, `name`, `age`, `sex`, `head_img`) VALUES
(1, '张三', 20, 1, 'img/zhangsan.png'),
(2, '李四', 25, 1, 'img/lisi.png'),
(3, '王五', 30, 2, 'img/wangwu.png');

INSERT INTO `area` (`id`, `province`, `city`) VALUES
(1, '浙江省', '杭州市'),
(2, '浙江省', '宁波市'),
(3, '江苏省', '南京市');

INSERT INTO `user_address` (`id`, `user_id`, `tel`, `address`, `area_id`, `status`) VALUES
(1, 1, '13800000001', '西湖区文一西路100号', 1, 1),
(2, 1, '13800000002', '滨江区江南大道200号', 1, 1),
(3, 2, '13900000003', '鄞州区天童南路300号', 2, 1),
(4, 3, '13700000004', '鼓楼区中山北路400号', 3, 0);

INSERT INTO `station_info` (`id`, `station_id`, `station_name`, `merchant_id`) VALUES
(1, 101, '中心站', 1001),
(2, 102, '东站', 1002),
(3, 103, '西站', 1001);

INSERT INTO `car_device_info` (`id`, `station_id`, `share_scheme_id`, `device_name`) VALUES
(1, 101, 1, '充电桩A-01'),
(2, 101, 1, '充电桩A-02'),
(3, 102, 2, '换电柜B-01'),
(4, 103, 3, '充电桩C-01');
