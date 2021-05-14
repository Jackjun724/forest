package com.retzero.forest.auto;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

import javax.annotation.PostConstruct;

@SpringBootApplication
public class StartApplication {

    @Autowired
    private AutoForest autoForest;

    public static void main(String[] args) {
        SpringApplication.run(StartApplication.class, args);
    }

    @PostConstruct
    public void init() {
        autoForest.start();
    }
}
