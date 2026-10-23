package com.example.ittechnology;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

// 请求日志已接入数据库持久化（默认 H2 文件模式 ./data/reqlog，可切 MySQL profile），
// 数据源由 spring-boot 自动配置，无需排除 DataSourceAutoConfiguration。
@SpringBootApplication
public class ItTechnologyApplication {

    public static void main(String[] args) {
        SpringApplication.run(ItTechnologyApplication.class, args);
    }

}
