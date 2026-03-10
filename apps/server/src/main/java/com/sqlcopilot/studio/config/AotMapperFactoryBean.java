package com.sqlcopilot.studio.config;

import org.mybatis.spring.mapper.MapperFactoryBean;

/**
 * AOT 友好的 MapperFactoryBean，仅保留无参构造，
 * 避免 Spring AOT 生成 Class<?> 构造参数注入路径。
 */
public class AotMapperFactoryBean<T> extends MapperFactoryBean<T> {

    public AotMapperFactoryBean() {
        super();
    }
}
