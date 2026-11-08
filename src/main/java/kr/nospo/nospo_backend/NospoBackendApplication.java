package kr.nospo.nospo_backend;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication(scanBasePackages = "kr.nospo")
public class NospoBackendApplication {

    public static void main(String[] args) {
        SpringApplication.run(NospoBackendApplication.class, args);
    }
}
