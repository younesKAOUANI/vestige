package dev.youneskaouani.vestige;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.scheduling.annotation.EnableScheduling;

/** Entry point. */
@SpringBootApplication
@ConfigurationPropertiesScan
@EnableScheduling
public class VestigeApplication {

    public static void main(String[] args) {
        SpringApplication.run(VestigeApplication.class, args);
    }
}
