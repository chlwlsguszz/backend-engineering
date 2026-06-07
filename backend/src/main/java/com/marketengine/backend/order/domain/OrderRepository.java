package com.marketengine.backend.order.domain;

import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface OrderRepository extends JpaRepository<Order, Long> {

    @Query("""
            SELECT o FROM Order o
            JOIN FETCH o.member
            JOIN FETCH o.product
            WHERE o.member.id = :memberId AND o.idempotencyKey = :idempotencyKey
            """)
    Optional<Order> findByMemberIdAndIdempotencyKey(
            @Param("memberId") Long memberId,
            @Param("idempotencyKey") String idempotencyKey
    );
}
