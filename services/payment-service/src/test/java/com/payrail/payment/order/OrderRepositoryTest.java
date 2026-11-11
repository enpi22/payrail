package com.payrail.payment.order;

import com.payrail.payment.TestcontainersConfiguration;
import com.payrail.payment.merchant.Merchant;
import jakarta.persistence.PersistenceException;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.autoconfigure.orm.jpa.TestEntityManager;
import org.springframework.context.annotation.Import;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE) // use real Postgres, not H2
@Import(TestcontainersConfiguration.class)
class OrderRepositoryTest {

    @Autowired
    private OrderRepository orderRepository;

    @Autowired
    private TestEntityManager entityManager;

    private UUID newMerchantId() {
        return entityManager.persistAndFlush(new Merchant("Test Store", null, "whsec_test")).getId();
    }

    @Test
    void savedOrderGetsIdTimestampAndCreatedStatus() {
        Order order = entityManager.persistAndFlush(new Order(newMerchantId(), 100_000, "INR", "SHOE-1"));

        assertThat(order.getId()).isNotNull();
        assertThat(order.getCreatedAt()).isNotNull();
        assertThat(order.getStatus()).isEqualTo(OrderStatus.CREATED);
    }

    @Test
    void orderIsOnlyVisibleToItsOwnMerchant() {
        UUID merchantA = newMerchantId();
        UUID merchantB = newMerchantId();
        Order order = entityManager.persistAndFlush(new Order(merchantA, 100_000, "INR", "SHOE-1"));
        entityManager.clear(); // force real database reads below

        assertThat(orderRepository.findByIdAndMerchantId(order.getId(), merchantA)).isPresent();
        assertThat(orderRepository.findByIdAndMerchantId(order.getId(), merchantB)).isEmpty();
    }

    @Test
    void databaseRejectsDuplicateReceiptForSameMerchant() {
        UUID merchant = newMerchantId();
        entityManager.persistAndFlush(new Order(merchant, 100_000, "INR", "SHOE-1"));

        assertThatThrownBy(() -> entityManager.persistAndFlush(new Order(merchant, 200_000, "INR", "SHOE-1")))
                .isInstanceOf(PersistenceException.class);
    }

    @Test
    void sameReceiptIsAllowedForDifferentMerchants() {
        entityManager.persistAndFlush(new Order(newMerchantId(), 100_000, "INR", "SHOE-1"));
        Order second = entityManager.persistAndFlush(new Order(newMerchantId(), 100_000, "INR", "SHOE-1"));

        assertThat(second.getId()).isNotNull();
    }
}