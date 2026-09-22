package com.ipovideo;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@EnableScheduling
@SpringBootApplication
@MapperScan("com.ipovideo.mapper")
public class IpoVideoApplication {

    public static void main(String[] args) {
        SpringApplication.run(IpoVideoApplication.class, args);
    }
}
