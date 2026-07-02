package com.example.shoptetfeed;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.WebApplicationType;

@SpringBootApplication
public class ShoptetFeedApplication {

    public static void main(String[] args) {
        SpringApplication app = new SpringApplication(ShoptetFeedApplication.class);
        app.setWebApplicationType(WebApplicationType.NONE); // CLI, no embedded server
        app.run(args);
    }
}
