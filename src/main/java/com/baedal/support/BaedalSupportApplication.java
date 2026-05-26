package com.baedal.support;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication(scanBasePackages = "com.baedal")
public class BaedalSupportApplication {

    public static void main(String[] args) {
        SpringApplication.run(BaedalSupportApplication.class, args);
    }
}
