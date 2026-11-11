package com.payrail.payment.merchant;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;
import java.util.UUID;

public interface ApiKeyRepository extends JpaRepository<ApiKey, UUID> {

    Optional<ApiKey> findByKeyPrefix(String keyPrefix);

    /** Loads an active (not revoked) key and its merchant in a single query. */
    @Query("""
            select k from ApiKey k
            join fetch k.merchant
            where k.keyPrefix = :prefix and k.revokedAt is null
            """)
    Optional<ApiKey> findActiveByPrefixWithMerchant(@Param("prefix") String prefix);
}