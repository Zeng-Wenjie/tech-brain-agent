//package com.agent.config;
//import com.baomidou.mybatisplus.annotation.DbType;
//import com.baomidou.mybatisplus.extension.plugins.MybatisPlusInterceptor;
//import org.springframework.context.annotation.Bean;
//import org.springframework.context.annotation.Configuration;
///*
//* 配置mybatisPlus分页插件
// */
//@Configuration
//public class MybatisConfig {
//
//    @Bean
//    public MybatisPlusInterceptor mybatisPlusInterceptor() {
//        //初始化核心插件
//        MybatisPlusInterceptor interceptor = new MybatisPlusInterceptor();
//        //添加分页插件
//        PaginationInnerInterceptor pagInterceptor = new PaginationInnerInterceptor(DbType.MYSQL);
//        pagInterceptor.setMaxLimit(500L);
//        interceptor.addInnerInterceptor(pagInterceptor);
//        return interceptor;
//    }
//}
