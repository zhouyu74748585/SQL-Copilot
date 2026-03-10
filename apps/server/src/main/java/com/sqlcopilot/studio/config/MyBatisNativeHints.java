package com.sqlcopilot.studio.config;

import com.sqlcopilot.studio.entity.AiProviderConfigEntity;
import com.sqlcopilot.studio.entity.AuditLogEntity;
import com.sqlcopilot.studio.entity.ConnectionEntity;
import com.sqlcopilot.studio.entity.ErGraphSnapshotEntity;
import com.sqlcopilot.studio.entity.KnowledgeExampleSqlEntity;
import com.sqlcopilot.studio.entity.KnowledgeTermEntity;
import com.sqlcopilot.studio.entity.QueryHistoryEntity;
import com.sqlcopilot.studio.entity.RagEmbeddingConfigEntity;
import com.sqlcopilot.studio.entity.RagVectorizeStatusEntity;
import com.sqlcopilot.studio.entity.SavedQueryEntity;
import org.apache.ibatis.mapping.DatabaseIdProvider;
import org.apache.ibatis.logging.commons.JakartaCommonsLoggingImpl;
import org.apache.ibatis.logging.jdk14.Jdk14LoggingImpl;
import org.apache.ibatis.logging.log4j2.Log4j2Impl;
import org.apache.ibatis.logging.nologging.NoLoggingImpl;
import org.apache.ibatis.logging.slf4j.Slf4jImpl;
import org.apache.ibatis.logging.stdout.StdOutImpl;
import org.apache.ibatis.plugin.Interceptor;
import org.apache.ibatis.scripting.LanguageDriver;
import org.apache.ibatis.type.TypeHandler;
import org.springframework.aot.hint.TypeReference;
import org.springframework.aot.hint.MemberCategory;
import org.springframework.aot.hint.RuntimeHints;
import org.springframework.aot.hint.RuntimeHintsRegistrar;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;

/**
 * GraalVM native image 需要显式注册 MyBatis 日志实现构造器，
 * 否则 LogFactory 通过反射初始化 logger 时会在运行期失败。
 */
public class MyBatisNativeHints implements RuntimeHintsRegistrar {

    @Override
    public void registerHints(RuntimeHints hints, ClassLoader classLoader) {
        // Driver resolver and isolated loader both read these resources via ClassPathResource in native runtime.
        hints.resources().registerPattern("jdbc-drivers.yml");
        hints.resources().registerPattern("drivers/**");
        hints.reflection().registerType(Slf4jImpl.class, MemberCategory.INVOKE_PUBLIC_CONSTRUCTORS);
        hints.reflection().registerType(JakartaCommonsLoggingImpl.class, MemberCategory.INVOKE_PUBLIC_CONSTRUCTORS);
        hints.reflection().registerType(Log4j2Impl.class, MemberCategory.INVOKE_PUBLIC_CONSTRUCTORS);
        hints.reflection().registerType(Jdk14LoggingImpl.class, MemberCategory.INVOKE_PUBLIC_CONSTRUCTORS);
        hints.reflection().registerType(StdOutImpl.class, MemberCategory.INVOKE_PUBLIC_CONSTRUCTORS);
        hints.reflection().registerType(NoLoggingImpl.class, MemberCategory.INVOKE_PUBLIC_CONSTRUCTORS);
        // MyBatis auto-configuration constructor generic signatures depend on these SPI types in native runtime.
        hints.reflection().registerType(Interceptor.class, MemberCategory.INTROSPECT_PUBLIC_METHODS);
        hints.reflection().registerType(TypeHandler.class, MemberCategory.INTROSPECT_PUBLIC_METHODS);
        hints.reflection().registerType(LanguageDriver.class, MemberCategory.INTROSPECT_PUBLIC_METHODS);
        hints.reflection().registerType(DatabaseIdProvider.class, MemberCategory.INTROSPECT_PUBLIC_METHODS);
        hints.reflection().registerType(ArrayList.class, MemberCategory.INVOKE_DECLARED_CONSTRUCTORS);
        hints.reflection().registerType(HashMap.class, MemberCategory.INVOKE_DECLARED_CONSTRUCTORS);
        hints.reflection().registerType(LinkedHashMap.class, MemberCategory.INVOKE_DECLARED_CONSTRUCTORS);
        registerEntityType(hints, AiProviderConfigEntity.class);
        registerEntityType(hints, AuditLogEntity.class);
        registerEntityType(hints, ConnectionEntity.class);
        registerEntityType(hints, ErGraphSnapshotEntity.class);
        registerEntityType(hints, KnowledgeExampleSqlEntity.class);
        registerEntityType(hints, KnowledgeTermEntity.class);
        registerEntityType(hints, QueryHistoryEntity.class);
        registerEntityType(hints, RagEmbeddingConfigEntity.class);
        registerEntityType(hints, RagVectorizeStatusEntity.class);
        registerEntityType(hints, SavedQueryEntity.class);
        registerIfPresent(hints, classLoader, "org.apache.ibatis.executor.loader.javassist.JavassistProxyFactory");
        registerIfPresent(hints, classLoader, "org.apache.ibatis.executor.loader.javassist.JavassistSerialStateHolder");
        registerIfPresent(hints, classLoader, "org.apache.ibatis.javassist.util.proxy.ProxyFactory");
        registerIfPresent(hints, classLoader, "org.apache.ibatis.javassist.util.proxy.ProxyObject");
        registerIfPresent(hints, classLoader, "org.apache.ibatis.javassist.util.proxy.Proxy");
        registerIfPresent(hints, classLoader, "org.apache.ibatis.javassist.util.proxy.MethodHandler");
        registerIfPresent(hints, classLoader, "org.apache.ibatis.javassist.util.proxy.RuntimeSupport");
        registerIfPresent(hints, classLoader, "org.apache.ibatis.javassist.util.proxy.SecurityActions");
        registerIfPresent(hints, classLoader, "org.apache.ibatis.scripting.defaults.RawLanguageDriver");
        registerIfPresent(hints, classLoader, "org.apache.ibatis.scripting.xmltags.XMLLanguageDriver");
        registerIfPresent(hints, classLoader, "org.mybatis.spring.SqlSessionFactoryBean");
        registerIfPresent(hints, classLoader, "org.mybatis.spring.SqlSessionTemplate");
        registerIfPresent(hints, classLoader, "org.mybatis.spring.mapper.MapperFactoryBean");
        registerMapperProxyIfPresent(hints, classLoader, "com.sqlcopilot.studio.mapper.AiConfigMapper");
        registerMapperProxyIfPresent(hints, classLoader, "com.sqlcopilot.studio.mapper.AuditLogMapper");
        registerMapperProxyIfPresent(hints, classLoader, "com.sqlcopilot.studio.mapper.ConnectionMapper");
        registerMapperProxyIfPresent(hints, classLoader, "com.sqlcopilot.studio.mapper.ErGraphSnapshotMapper");
        registerMapperProxyIfPresent(hints, classLoader, "com.sqlcopilot.studio.mapper.KnowledgeExampleSqlMapper");
        registerMapperProxyIfPresent(hints, classLoader, "com.sqlcopilot.studio.mapper.KnowledgeTermMapper");
        registerMapperProxyIfPresent(hints, classLoader, "com.sqlcopilot.studio.mapper.QueryHistoryMapper");
        registerMapperProxyIfPresent(hints, classLoader, "com.sqlcopilot.studio.mapper.RagConfigMapper");
        registerMapperProxyIfPresent(hints, classLoader, "com.sqlcopilot.studio.mapper.RagVectorizeStatusMapper");
        registerMapperProxyIfPresent(hints, classLoader, "com.sqlcopilot.studio.mapper.SavedQueryMapper");
    }

    private static void registerIfPresent(RuntimeHints hints, ClassLoader classLoader, String className) {
        if (!isClassPresent(classLoader, className)) {
            return;
        }
        hints.reflection().registerType(
            TypeReference.of(className),
            MemberCategory.INVOKE_DECLARED_CONSTRUCTORS,
            MemberCategory.INVOKE_PUBLIC_CONSTRUCTORS,
            MemberCategory.INVOKE_PUBLIC_METHODS,
            MemberCategory.INVOKE_DECLARED_METHODS,
            MemberCategory.DECLARED_FIELDS,
            MemberCategory.PUBLIC_FIELDS
        );
    }

    private static boolean isClassPresent(ClassLoader classLoader, String className) {
        try {
            Class.forName(className, false, classLoader);
            return true;
        } catch (ClassNotFoundException ex) {
            return false;
        }
    }

    private static void registerMapperProxyIfPresent(RuntimeHints hints, ClassLoader classLoader, String interfaceName) {
        if (!isClassPresent(classLoader, interfaceName)) {
            return;
        }
        hints.proxies().registerJdkProxy(TypeReference.of(interfaceName));
    }

    /**
     * MyBatis 在 native 运行期会通过反射实例化实体并调用 getter/setter，
     * 这里统一注册 mapper 相关实体的构造器/方法/字段可见性。
     */
    private static void registerEntityType(RuntimeHints hints, Class<?> entityType) {
        hints.reflection().registerType(
            entityType,
            MemberCategory.INVOKE_DECLARED_CONSTRUCTORS,
            MemberCategory.INVOKE_PUBLIC_CONSTRUCTORS,
            MemberCategory.INVOKE_PUBLIC_METHODS,
            MemberCategory.INVOKE_DECLARED_METHODS,
            MemberCategory.DECLARED_FIELDS,
            MemberCategory.PUBLIC_FIELDS
        );
    }
}
