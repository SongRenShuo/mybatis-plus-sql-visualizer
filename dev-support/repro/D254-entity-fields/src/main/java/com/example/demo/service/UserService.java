package com.example.demo.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.example.demo.entity.UserEntity;
import com.example.demo.mapper.UserMapper;

import java.util.List;

/**
 * 复现夹具触发点（D254 T-EntityFieldStaticHidden）。
 *
 * 在 IDE 里把光标放进下面任一方法、用插件的预览入口（gutter 图标 / Alt+Enter / 右键菜单）看生成的 SQL。
 * 预期见 dev-support/repro/D254-entity-fields/README.md。
 */
public class UserService {

    private UserMapper userMapper;

    /**
     * 触发 1：SELECT 全列展开。
     * 新版：id, username, nickname, created_at（create_time 只以子类列名出现一次），
     *       且不含 serial_version_uid / table_name / log_tag / version_tag。
     */
    public List<UserEntity> listAll() {
        return userMapper.selectList(null);
    }

    /**
     * 触发 2：INSERT 列清单（批量面板同源）。
     * 新版：无静态列；created_at 只出现一次；version / deleted 仍是业务列（子类那份没有注解）。
     */
    public int create(UserEntity user) {
        return userMapper.insert(user);
    }

    /**
     * 触发 3：Wrapper 预览。
     * 新版：username = ? AND deleted 不作为逻辑删除条件（子类重写字段不带 @TableLogic）；
     *       旧版会额外追加 AND deleted = 0。
     */
    public UserEntity findByUsername(String username) {
        LambdaQueryWrapper<UserEntity> wrapper = Wrappers.<UserEntity>lambdaQuery()
                .eq(UserEntity::getUsername, username)
                .orderByDesc(UserEntity::getId);
        return userMapper.selectOne(wrapper);
    }

    /**
     * 触发 4：XML 语句预览（resultMap / foreach）。
     * 补全点：resultMap 的 column 与 property、foreach 的 item 属性候选里不应出现静态字段。
     */
    public List<UserEntity> listByXml(List<UserEntity> users) {
        return userMapper.queryByXml(users);
    }

    /** 触发 5：UPDATE 预览（验证子类重写 version 后不再追加乐观锁条件）。 */
    public int update(UserEntity user) {
        return userMapper.updateById(user);
    }

    public void setUserMapper(UserMapper userMapper) {
        this.userMapper = userMapper;
    }
}
