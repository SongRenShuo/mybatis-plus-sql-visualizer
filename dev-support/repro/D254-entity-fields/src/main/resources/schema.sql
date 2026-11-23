-- D254 复现夹具的最小表结构（可选：只做「预览」不连库也能验证）
-- 与 UserEntity 的子类语义对齐：列名 created_at、无逻辑删除专用列（deleted 只是普通列）

DROP TABLE IF EXISTS t_user;

CREATE TABLE t_user (
    id         BIGINT       NOT NULL PRIMARY KEY,
    created_at DATETIME     NULL,
    username   VARCHAR(64)  NULL,
    nickname   VARCHAR(64)  NULL,
    version    INT          NULL,
    deleted    INT          NULL
);

INSERT INTO t_user (id, created_at, username, nickname, version, deleted)
VALUES (1, NOW(), 'alice', 'Alice', 1, 0);
