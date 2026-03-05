package com.baskette.dropship;

import com.baskette.dropship.config.DropshipProperties;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;

@SpringBootApplication
@EnableConfigurationProperties(DropshipProperties.class)
@org.springframework.scheduling.annotation.EnableAsync
public class DropshipApplication {

    public static void main(String[] args) {
        SpringApplication.run(DropshipApplication.class, args);
    }
}
