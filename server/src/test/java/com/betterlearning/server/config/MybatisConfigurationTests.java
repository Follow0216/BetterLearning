package com.betterlearning.server.config;

import javax.sql.DataSource;

import com.baomidou.mybatisplus.autoconfigure.MybatisPlusAutoConfiguration;
import com.baomidou.mybatisplus.autoconfigure.MybatisPlusProperties;
import com.betterlearning.server.dao.mapper.UserMapper;

import org.apache.ibatis.logging.nologging.NoLoggingImpl;
import org.apache.ibatis.mapping.SqlCommandType;
import org.apache.ibatis.session.ExecutorType;
import org.apache.ibatis.session.LocalCacheScope;
import org.apache.ibatis.session.SqlSessionFactory;
import org.junit.jupiter.api.Test;
import org.mybatis.spring.SqlSessionTemplate;
import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 验证 MyBatis-Plus 固定策略与 Mapper 扫描范围。
 *
 * <p>不连接数据库、不依赖容器；M2 已注册真实 Mapper 与 XML statement，SQL 执行仍由后续容器
 * 集成测试验证。</p>
 *
 * @author BetterLearning
 * @since 2026-10-22
 */
class MybatisConfigurationTests {

    private static final String USER_MAPPER =
            "com.betterlearning.server.dao.mapper.UserMapper.";

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(MybatisPlusAutoConfiguration.class))
            .withUserConfiguration(MybatisConfiguration.class)
            .withBean(DataSource.class, DriverManagerDataSource::new);

    @Test
    void shouldBindFixedConfigurationAndMapperLocations() {
        MybatisPlusProperties properties = new MybatisPlusProperties();

        new MybatisConfiguration().mybatisPlusPropertiesCustomizer().customize(properties);

        assertThat(properties.getMapperLocations())
                .containsExactly(MybatisConfiguration.MAPPER_LOCATIONS);
        assertThat(properties.getExecutorType()).isEqualTo(ExecutorType.SIMPLE);
        assertThat(properties.getConfiguration().getCacheEnabled()).isFalse();
        assertThat(properties.getConfiguration().getLocalCacheScope())
                .isEqualTo(LocalCacheScope.STATEMENT);
        assertThat(properties.getConfiguration().getDefaultExecutorType())
                .isEqualTo(ExecutorType.SIMPLE);
        assertThat(properties.getConfiguration().getMapUnderscoreToCamelCase()).isTrue();
        assertThat(properties.getConfiguration().getLogImpl()).isEqualTo(NoLoggingImpl.class);
    }

    @Test
    void shouldApplyFixedConfigurationToAutoConfiguredSession() {
        contextRunner.run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context).hasSingleBean(SqlSessionFactory.class);

            org.apache.ibatis.session.Configuration configuration =
                    context.getBean(SqlSessionFactory.class).getConfiguration();
            assertThat(configuration.isCacheEnabled()).isFalse();
            assertThat(configuration.getLocalCacheScope()).isEqualTo(LocalCacheScope.STATEMENT);
            assertThat(configuration.getDefaultExecutorType()).isEqualTo(ExecutorType.SIMPLE);
            assertThat(configuration.isMapUnderscoreToCamelCase()).isTrue();
            assertThat(configuration.getLogImpl()).isEqualTo(NoLoggingImpl.class);
            assertThat(context).hasSingleBean(SqlSessionTemplate.class);
            assertThat(context.getBean(SqlSessionTemplate.class).getExecutorType())
                    .isEqualTo(ExecutorType.SIMPLE);
        });
    }

    @Test
    void shouldRegisterUserMapperAndXmlStatements() {
        contextRunner.run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context).hasSingleBean(UserMapper.class);

            org.apache.ibatis.session.Configuration configuration =
                    context.getBean(SqlSessionFactory.class).getConfiguration();
            assertThat(configuration.hasStatement(USER_MAPPER + "insertReturningAccountId"))
                    .isTrue();
            assertThat(configuration.hasStatement(USER_MAPPER + "updateContactInfo")).isTrue();
            assertThat(configuration.hasStatement(USER_MAPPER + "updatePassword")).isTrue();
            assertThat(configuration.getMappedStatement(
                    USER_MAPPER + "insertReturningAccountId").getSqlCommandType())
                    .isEqualTo(SqlCommandType.SELECT);
            assertThat(configuration.getMappedStatement(
                    USER_MAPPER + "updateContactInfo").getSqlCommandType())
                    .isEqualTo(SqlCommandType.UPDATE);
            assertThat(configuration.getMappedStatement(
                    USER_MAPPER + "updatePassword").getSqlCommandType())
                    .isEqualTo(SqlCommandType.UPDATE);
        });
    }

    @Test
    void shouldScanOnlyDaoMapperPackage() {
        MapperScan mapperScan = MybatisConfiguration.class.getAnnotation(MapperScan.class);

        assertThat(mapperScan).isNotNull();
        assertThat(mapperScan.value())
                .containsExactly("com.betterlearning.server.dao.mapper");
    }
}
