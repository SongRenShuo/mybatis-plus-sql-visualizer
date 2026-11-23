package com.example.demo.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.Version;

import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * 复现夹具（D254 T-EntityFieldStaticHidden）：父类。
 *
 * 两个复现点都在这一层：
 *  1. 静态字段会被旧版当成表列 —— serialVersionUID（旧版已被 T-M42 特判）、
 *     TABLE_NAME（旧版必然产出 table_name 列）、LOG_TAG（产出 log_tag 列）；
 *  2. 这里的 createTime / version / deleted 会被子类同名重写，用于验证「注解随子类字段生效」
 *     （旧版因为父类字段排在前面，@TableField(fill)/@Version/@TableLogic 全都取父类那份）。
 */
public class BaseEntity implements Serializable {

    private static final long serialVersionUID = 1L;

    /** 静态常量：Java 字段名 TABLE_NAME 会被旧版按命名策略转成 table_name 列。 */
    public static final String TABLE_NAME = "t_user";

    private static final String LOG_TAG = "UserEntity";

    @TableId
    private Long id;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createTime;

    @Version
    private Integer version;

    @TableLogic
    private Integer deleted;

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public LocalDateTime getCreateTime() {
        return createTime;
    }

    public void setCreateTime(LocalDateTime createTime) {
        this.createTime = createTime;
    }

    public Integer getVersion() {
        return version;
    }

    public void setVersion(Integer version) {
        this.version = version;
    }

    public Integer getDeleted() {
        return deleted;
    }

    public void setDeleted(Integer deleted) {
        this.deleted = deleted;
    }
}
