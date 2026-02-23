package com.baskette.dropship;

import com.baskette.dropship.config.DropshipProperties;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;

@SpringBootApplication
@EnableConfigurationProperties(DropshipProperties.class)
public class DropshipApplication {

    public static void main(String[] args) {
        SpringApplication.run(DropshipApplication.class, args);
    }
}
