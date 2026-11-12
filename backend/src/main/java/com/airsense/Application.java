package com.airsense;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
public class Application {
  @org.springframework.context.annotation.Bean
  public java.time.Clock clock() {
    return java.time.Clock.systemUTC();
  }

  public static void main(String[] args) {
    SpringApplication.run(Application.class, args);
  }
}
