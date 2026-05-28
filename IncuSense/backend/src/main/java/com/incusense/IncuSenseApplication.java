package com.incusense;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;

@SpringBootApplication
@EnableJpaRepositories(basePackages = "com.incusense.repository", considerNestedRepositories = true)
public class IncuSenseApplication {

    public static void main(String[] args) {
        SpringApplication.run(IncuSenseApplication.class, args);
    }
}
