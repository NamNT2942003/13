package com.example.demo;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

@SpringBootTest
@ActiveProfiles("test")
public class DemoApplicationTests {

    @Test
    void contextLoads() {
        System.out.println(">>> Spring Boot context loaded successfully with PostgreSQL service on GitLab CI!");
    }
}
