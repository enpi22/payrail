package com.payrail.payment.order;

import com.payrail.payment.TestcontainersConfiguration;
import com.payrail.payment.common.ApiException;
import com.payrail.payment.merchant.CreateMerchantRequest;
import com.payrail.payment.merchant.MerchantService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpStatus;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@Import(TestcontainersConfiguration.class)
@SpringBootTest
class OrderServiceTest {

    @Autowired
    private OrderService orderService;

    @Autowired
    private MerchantService merchantService;

    private UUID newMerchant() {
        return merchantService.createMerchant(new CreateMerchantRequest("Test Store", null)).merchantId();
    }

    @Test
    void createsOrderInCreatedStatusWithDefaultCurrency() {
        OrderResponse order = orderService.createOrder(newMerchant(),
                new CreateOrderRequest(100_000L, null, "SHOE-1"));

        assertThat(order.id()).isNotNull();
        assertThat(order.status()).isEqualTo(OrderStatus.CREATED);
        assertThat(order.currency()).isEqualTo("INR");
        assertThat(order.amount()).isEqualTo(100_000L);
    }

    @Test
    void duplicateReceiptForSameMerchantIsAConflict() {
        UUID merchant = newMerchant();
        orderService.createOrder(merchant, new CreateOrderRequest(100_000L, "INR", "SHOE-1"));

        assertThatThrownBy(() -> orderService.createOrder(merchant, new CreateOrderRequest(50_000L, "INR", "SHOE-1")))
                .isInstanceOfSatisfying(ApiException.class, ex -> {
                    assertThat(ex.getStatus()).isEqualTo(HttpStatus.CONFLICT);
                    assertThat(ex.getCode()).isEqualTo("duplicate_receipt");
                });
    }

    @Test
    void sameReceiptForDifferentMerchantsIsAllowed() {
        orderService.createOrder(newMerchant(), new CreateOrderRequest(100_000L, "INR", "SHOE-1"));
        OrderResponse second = orderService.createOrder(newMerchant(), new CreateOrderRequest(100_000L, "INR", "SHOE-1"));

        assertThat(second.id()).isNotNull();
    }

    @Test
    void merchantCanFetchItsOwnOrder() {
        UUID merchant = newMerchant();
        OrderResponse created = orderService.createOrder(merchant, new CreateOrderRequest(100_000L, "INR", "SHOE-1"));

        assertThat(orderService.getOrder(merchant, created.id()).receipt()).isEqualTo("SHOE-1");
    }

    @Test
    void otherMerchantsOrderLooksExactlyLikeAMissingOne() {
        OrderResponse created = orderService.createOrder(newMerchant(), new CreateOrderRequest(100_000L, "INR", "SHOE-1"));
        UUID otherMerchant = newMerchant();

        assertThatThrownBy(() -> orderService.getOrder(otherMerchant, created.id()))
                .isInstanceOfSatisfying(ApiException.class,
                        ex -> assertThat(ex.getStatus()).isEqualTo(HttpStatus.NOT_FOUND));
        assertThatThrownBy(() -> orderService.getOrder(otherMerchant, UUID.randomUUID()))
                .isInstanceOfSatisfying(ApiException.class,
                        ex -> assertThat(ex.getStatus()).isEqualTo(HttpStatus.NOT_FOUND));
    }
}