package ru.mtuci.appeals;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

@SpringBootApplication
@ConfigurationPropertiesScan
public class InsuranceAppealsApplication {

    public static void main(String[] args) {
        SpringApplication.run(InsuranceAppealsApplication.class, args);
    }
}
