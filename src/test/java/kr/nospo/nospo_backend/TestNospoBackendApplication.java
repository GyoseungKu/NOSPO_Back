package kr.nospo.nospo_backend;

import org.springframework.boot.SpringApplication;

public class TestNospoBackendApplication {

	public static void main(String[] args) {
		SpringApplication.from(NospoBackendApplication::main).with(TestcontainersConfiguration.class).run(args);
	}

}
