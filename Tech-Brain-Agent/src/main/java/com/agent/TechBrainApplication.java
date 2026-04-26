package com.agent;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;


@SpringBootApplication
@MapperScan("com.agent.mapper")//扫描mapper接口
public class TechBrainApplication {

    public static void main(String[] args) {
        SpringApplication.run(TechBrainApplication.class, args);
    }

}
