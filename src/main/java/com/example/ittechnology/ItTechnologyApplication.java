package com.example.ittechnology;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration;

// 暂时排除数据库自动配置，当前项目作为 AI 代理后端不需要数据库
// 如后续需要接入 MySQL 存储对话历史，移除 exclude 并在 application.yaml 配置数据源即可
@SpringBootApplication(exclude = {DataSourceAutoConfiguration.class})
public class ItTechnologyApplication {

    public static void main(String[] args) {
        SpringApplication.run(ItTechnologyApplication.class, args);
    }

}
