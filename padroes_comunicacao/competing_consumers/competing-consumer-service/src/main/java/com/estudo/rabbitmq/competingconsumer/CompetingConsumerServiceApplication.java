package com.estudo.rabbitmq.competingconsumer;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication
public class CompetingConsumerServiceApplication {
    public static void main(String[] args) {
        SpringApplication.run(CompetingConsumerServiceApplication.class, args);
    }
}
