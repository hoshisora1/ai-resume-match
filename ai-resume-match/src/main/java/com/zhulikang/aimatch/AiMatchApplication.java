package com.zhulikang.aimatch;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@EnableScheduling
@SpringBootApplication
public class AiMatchApplication {
    public static void main(String[] args) {
        SpringApplication.run(AiMatchApplication.class, args);
    }
}
