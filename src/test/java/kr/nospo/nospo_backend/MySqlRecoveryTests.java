package kr.nospo.nospo_backend;

import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.junit.jupiter.*;
import org.testcontainers.mysql.MySQLContainer;

@Testcontainers(disabledWithoutDocker=true)
class MySqlRecoveryTests extends RecoveryTests {
    @Container static final MySQLContainer MYSQL=new MySQLContainer("mysql:8.4.7");
    @DynamicPropertySource static void database(DynamicPropertyRegistry p) {
        p.add("spring.datasource.url",MYSQL::getJdbcUrl);p.add("spring.datasource.username",MYSQL::getUsername);
        p.add("spring.datasource.password",MYSQL::getPassword);p.add("spring.datasource.driver-class-name",()->"com.mysql.cj.jdbc.Driver");
    }
}
