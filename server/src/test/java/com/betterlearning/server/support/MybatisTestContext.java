package com.betterlearning.server.support;

import javax.sql.DataSource;

import com.baomidou.mybatisplus.autoconfigure.MybatisPlusAutoConfiguration;
import com.betterlearning.server.config.MybatisConfiguration;
import com.betterlearning.server.dao.UserDao;
import com.betterlearning.server.dao.impl.MybatisUserDao;
import com.betterlearning.server.dao.mapper.UserMapper;

import org.springframework.context.annotation.AnnotationConfigApplicationContext;

/**
 * 集成测试的最小 MyBatis 装配。
 *
 * <p>复用生产 {@link MybatisConfiguration} 与官方 {@link MybatisPlusAutoConfiguration}，
 * 并注入测试已有的 {@link DataSource}；不复制 MyBatis 配置。调用方负责在测试结束时关闭。</p>
 *
 * @author BetterLearning
 * @since 2026-10-22
 */
public final class MybatisTestContext implements AutoCloseable {

    private final AnnotationConfigApplicationContext context;

    private MybatisTestContext(AnnotationConfigApplicationContext context) {
        this.context = context;
    }

    /**
     * 创建测试上下文。
     *
     * @param dataSource 测试数据源
     * @return 可关闭的测试上下文
     */
    public static MybatisTestContext create(DataSource dataSource) {
        AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext();
        context.registerBean(DataSource.class, () -> dataSource);
        context.register(MybatisConfiguration.class, MybatisPlusAutoConfiguration.class);
        context.register(MybatisUserDao.class);
        context.refresh();
        return new MybatisTestContext(context);
    }

    /**
     * 返回真实的用户 DAO。
     *
     * @return 用户 DAO
     */
    public UserDao userDao() {
        return context.getBean(UserDao.class);
    }

    /**
     * 返回扫描注册的用户 Mapper。
     *
     * @return 用户 Mapper
     */
    public UserMapper userMapper() {
        return context.getBean(UserMapper.class);
    }

    @Override
    public void close() {
        context.close();
    }
}
