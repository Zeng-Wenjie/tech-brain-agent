package com.agent.config;
import com.baomidou.mybatisplus.annotation.DbType;
import com.baomidou.mybatisplus.extension.plugins.MybatisPlusInterceptor;
import com.baomidou.mybatisplus.extension.plugins.inner.PaginationInnerInterceptor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
/*
 * 配置mybatisPlus分页插件
 * Configure the MyBatis-Plus pagination plugin.
 */
@Configuration
public class MybatisConfig {

    @Bean
    public MybatisPlusInterceptor mybatisPlusInterceptor() {
        //初始化核心插件
        // Initialize the core plugin.
        MybatisPlusInterceptor interceptor = new MybatisPlusInterceptor();
        //添加分页插件
        // Add the pagination interceptor.
        PaginationInnerInterceptor pagInterceptor = new PaginationInnerInterceptor(DbType.MYSQL);
        //设置分页上限
        // Set the maximum page size.
        pagInterceptor.setMaxLimit(500L);
        interceptor.addInnerInterceptor(pagInterceptor);
        return interceptor;
    }
}
