package com.example.demo.entity;

import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;

import java.time.LocalDateTime;

/**
 * 复现夹具（D254 T-EntityFieldStaticHidden）：子类。
 *
 * 三处「与父类同名字段」是核心复现点（Java 字段隐藏，不是重写）：
 *  - createTime：父类带 @TableField(fill = INSERT)，子类换成 @TableField("created_at")；
 *    MyBatis-Plus 真机按子类这份判定 —— 列名 created_at、且不再是自动填充列。
 *  - deleted：父类带 @TableLogic，子类这份没有 —— 真机不再是逻辑删除列
 *    （UPDATE / DELETE 不再自动追加 AND deleted = 0）。
 *  - version：父类带 @Version，子类这份没有 —— 真机不再是乐观锁列
 *    （UPDATE 不再追加 version = version + 1 与 AND version = ?）。
 *
 * 旧版（D254 之前）现象：
 *  - SELECT 全列里 create_time 与 created_at 各出现一次（两份都留）；
 *  - 逻辑删除 / 乐观锁按父类那份识别（即上面两条「真机已不是」的语义反而生效）；
 *  - serial_version_uid / table_name / log_tag / version_tag 这些静态字段被当成列。
 */
@TableName("t_user")
public class UserEntity extends BaseEntity {

    /** 子类 static 常量：旧版会产出 version_tag 列。 */
    public static final String VERSION_TAG = "v1";

    @TableField("created_at")
    private LocalDateTime createTime;

    private Integer version;

    private Integer deleted;

    private String username;

    private String nickname;

    @Override
    public LocalDateTime getCreateTime() {
        return createTime;
    }

    @Override
    public void setCreateTime(LocalDateTime createTime) {
        this.createTime = createTime;
    }

    @Override
    public Integer getVersion() {
        return version;
    }

    @Override
    public void setVersion(Integer version) {
        this.version = version;
    }

    @Override
    public Integer getDeleted() {
        return deleted;
    }

    @Override
    public void setDeleted(Integer deleted) {
        this.deleted = deleted;
    }

    public String getUsername() {
        return username;
    }

    public void setUsername(String username) {
        this.username = username;
    }

    public String getNickname() {
        return nickname;
    }

    public void setNickname(String nickname) {
        this.nickname = nickname;
    }
}
