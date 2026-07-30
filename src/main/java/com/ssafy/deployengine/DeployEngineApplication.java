package com.ssafy.deployengine;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
public class DeployEngineApplication {

    public static void main(String[] args) {
        SpringApplication.run(DeployEngineApplication.class, args);
    }
}
