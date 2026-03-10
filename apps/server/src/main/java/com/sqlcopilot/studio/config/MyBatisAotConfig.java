package com.sqlcopilot.studio.config;

import org.mybatis.spring.mapper.MapperScannerConfigurer;
import org.springframework.beans.factory.aot.BeanFactoryInitializationAotProcessor;
import org.springframework.beans.factory.config.BeanFactoryPostProcessor;
import org.springframework.beans.factory.support.BeanDefinitionRegistry;
import org.springframework.beans.factory.support.RootBeanDefinition;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.ResolvableType;

/**
 * MyBatis 在 Spring AOT/native 下的兼容修复：
 * 1) 排除 MapperScannerConfigurer 的运行时 AOT 注册，避免 mapper 重复扫描导致冲突。
 */
@Configuration(proxyBeanMethods = false)
public class MyBatisAotConfig {

    @Bean
    static BeanFactoryInitializationAotProcessor mapperScannerConfigurerAotProcessor() {
        return beanFactory -> {
            if (beanFactory instanceof BeanDefinitionRegistry registry) {
                for (String beanName : beanFactory.getBeanNamesForType(MapperScannerConfigurer.class, false, false)) {
                    String resolvedBeanName = beanName.startsWith("&") ? beanName.substring(1) : beanName;
                    if (registry.containsBeanDefinition(resolvedBeanName)) {
                        registry.removeBeanDefinition(resolvedBeanName);
                    }
                }
            }
            return null;
        };
    }

    @Bean
    static BeanFactoryPostProcessor mapperFactoryBeanTargetTypePostProcessor() {
        return beanFactory -> {
            for (String beanName : beanFactory.getBeanDefinitionNames()) {
                if (!(beanFactory.getBeanDefinition(beanName) instanceof RootBeanDefinition beanDefinition)) {
                    continue;
                }
                if (!isAotMapperFactoryBean(beanDefinition)) {
                    continue;
                }
                Object mapperInterface = beanDefinition.getPropertyValues().get("mapperInterface");
                if (mapperInterface instanceof Class<?> mapperInterfaceClass) {
                    beanDefinition.setTargetType(
                        ResolvableType.forClassWithGenerics(AotMapperFactoryBean.class, mapperInterfaceClass)
                    );
                }
            }
        };
    }

    private static boolean isAotMapperFactoryBean(RootBeanDefinition beanDefinition) {
        String beanClassName = beanDefinition.getBeanClassName();
        return AotMapperFactoryBean.class.getName().equals(beanClassName);
    }

}
