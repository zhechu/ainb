package cn.enlev.ainb.framework.mybatis.config;

import cn.hutool.core.collection.CollUtil;
import cn.hutool.core.util.StrUtil;
import cn.enlev.ainb.framework.common.util.json.JsonUtils;
import cn.enlev.ainb.framework.mybatis.core.handler.DefaultDBFieldHandler;
import com.baomidou.mybatisplus.annotation.DbType;
import com.baomidou.mybatisplus.autoconfigure.MybatisPlusAutoConfiguration;
import com.baomidou.mybatisplus.core.handlers.IJsonTypeHandler;
import com.baomidou.mybatisplus.core.handlers.MetaObjectHandler;
import com.baomidou.mybatisplus.core.incrementer.IKeyGenerator;
import com.baomidou.mybatisplus.extension.handlers.JacksonTypeHandler;
import com.baomidou.mybatisplus.extension.incrementer.*;
import com.baomidou.mybatisplus.extension.parser.JsqlParserGlobal;
import com.baomidou.mybatisplus.extension.parser.cache.JdkSerialCaffeineJsqlParseCache;
import com.baomidou.mybatisplus.extension.plugins.MybatisPlusInterceptor;
import com.baomidou.mybatisplus.extension.plugins.inner.OptimisticLockerInnerInterceptor;
import com.baomidou.mybatisplus.extension.plugins.inner.PaginationInnerInterceptor;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.ibatis.annotations.Mapper;
import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.core.env.ConfigurableEnvironment;

import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * MyBaits 配置类
 *
 * @author 芋道源码
 */
@AutoConfiguration(before = MybatisPlusAutoConfiguration.class) // 目的：先于 MyBatis Plus 自动配置，避免 @MapperScan 可能扫描不到 Mapper 打印 warn 日志
@MapperScan(value = "${enlev.info.base-package}", annotationClass = Mapper.class,
        lazyInitialization = "${mybatis.lazy-initialization:false}") // Mapper 懒加载，目前仅用于单元测试
public class EnlevMybatisAutoConfiguration {

    static {
        // 动态 SQL 智能优化支持本地缓存加速解析，更完善的租户复杂 XML 动态 SQL 支持，静态注入缓存
        JsqlParserGlobal.setJsqlParseCache(new JdkSerialCaffeineJsqlParseCache(
                (cache) -> cache.maximumSize(1024)
                        .expireAfterWrite(5, TimeUnit.SECONDS))
        );
    }

    @Bean
    public MybatisPlusInterceptor mybatisPlusInterceptor() {
        MybatisPlusInterceptor mybatisPlusInterceptor = new MybatisPlusInterceptor();
        mybatisPlusInterceptor.addInnerInterceptor(new PaginationInnerInterceptor()); // 分页插件
        // 乐观锁插件：让 DO 上的 @Version 生效（更新时把旧版本作为 WHERE 条件、并把版本自增）。
        //
        // 🔴 顺序按 MyBatis-Plus 官方建议放在分页之后。就本仓而言顺序其实无关：
        //    分页插件只改 SELECT，乐观锁插件只改 UPDATE（见其 beforeUpdate），作用在不相交的语句类型上。
        //
        // 🔴 影响面：【只作用于打了 @Version 的 DO】，没打的一个字节都不会变。
        //    全仓此前没有任何 DO 打过 @Version，故本次注册对既有模块是纯空操作；
        //    开启它的只有 infra_data_model 与 infra_form_design 两个 DO。
        //
        // ⚠️ 三件事它【不会】替你做，别以为注册完就安全了：
        //    1. 版本冲突时它【不抛异常】，只是让 UPDATE 命中 0 行 —— 调用方必须自己检查
        //       影响行数并抛错，否则「并发覆盖」的表现是接口返回成功、先提交的改动被静默丢弃。
        //    2. 更新对象的 version 为 null 时，它【静默跳过】、根本不加版本条件
        //       （3.5.16 的 doOptimisticLocker：取到 null 就直接 return）。
        //       它提供了 setException 把这种情况变成显式失败，但那是单例级、跨全仓的开关，
        //       且共享的异常实例堆栈指向 bean 构造处、日志会把人引到错的地方 ——
        //       所以刻意不开，改由使用方在自己的 Service 里显式判 version 非空、用各自的错误码报。
        //    3. 它够不着两类写法：手写 @Update 的原生 SQL、以及 update(null, wrapper)
        //       （参数里没有 entity，无从读版本）。这两种要手写版本条件。
        mybatisPlusInterceptor.addInnerInterceptor(new OptimisticLockerInnerInterceptor()); // 乐观锁插件
        // ↓↓↓ 按需开启，可能会影响到 updateBatch 的地方：例如说文件配置管理 ↓↓↓
        // mybatisPlusInterceptor.addInnerInterceptor(new BlockAttackInnerInterceptor()); // 拦截没有指定条件的 update 和 delete 语句
        return mybatisPlusInterceptor;
    }

    @Bean
    public MetaObjectHandler defaultMetaObjectHandler() {
        return new DefaultDBFieldHandler(); // 自动填充参数类
    }

    @Bean
    @ConditionalOnProperty(prefix = "mybatis-plus.global-config.db-config", name = "id-type", havingValue = "INPUT")
    public IKeyGenerator keyGenerator(ConfigurableEnvironment environment) {
        DbType dbType = IdTypeEnvironmentPostProcessor.getDbType(environment);
        if (dbType != null) {
            switch (dbType) {
                case POSTGRE_SQL:
                    return new PostgreKeyGenerator();
                case ORACLE:
                case ORACLE_12C:
                    return new OracleKeyGenerator();
                case H2:
                    return new H2KeyGenerator();
                case KINGBASE_ES:
                    return new KingbaseKeyGenerator();
                case DM:
                    return new DmKeyGenerator();
            }
        }
        // 找不到合适的 IKeyGenerator 实现类
        throw new IllegalArgumentException(StrUtil.format("DbType{} 找不到合适的 IKeyGenerator 实现类", dbType));
    }

    @Bean // 特殊：返回结果使用 Object 而不用 JacksonTypeHandler 的原因，避免因为 JacksonTypeHandler 被 mybatis 全局使用！
    public Object jacksonTypeHandler(List<ObjectMapper> objectMappers) {
        // 特殊：设置 JacksonTypeHandler 的 ObjectMapper！
        ObjectMapper objectMapper = CollUtil.getFirst(objectMappers);
        if (objectMapper == null) {
            objectMapper = JsonUtils.getObjectMapper();
        }
        JacksonTypeHandler.setObjectMapper(objectMapper);
        return new JacksonTypeHandler(Object.class);
    }

}
