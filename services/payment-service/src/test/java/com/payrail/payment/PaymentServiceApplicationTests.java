package com.payrail.payment;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;

@Import(TestcontainersConfiguration.class)
@SpringBootTest
class PaymentServiceApplicationTests {

    @Test
    void contextLoads() {
        // Passes if the app starts and Flyway applies all migrations to a fresh database.
    }
}
