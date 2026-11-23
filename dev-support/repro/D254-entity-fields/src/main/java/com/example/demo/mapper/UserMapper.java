package com.example.demo.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.example.demo.entity.UserEntity;

import java.util.List;

/**
 * 复现夹具入口：BaseMapper 的内置方法（selectList / insert / selectOne）是插件
 * 「SELECT 全列展开 / INSERT 列清单 / 批量面板」的触发点。
 */
public interface UserMapper extends BaseMapper<UserEntity> {

    /** XML 语句（src/main/resources/mapper/UserMapper.xml）：resultMap 属性补全与 foreach 的触发点。 */
    List<UserEntity> queryByXml(List<UserEntity> users);
}
