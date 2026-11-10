package com.marketengine.backend.order.domain;

import java.util.Optional;
import java.util.List;
import org.springframework.data.domain.Limit;
import com.marketengine.backend.order.api.OrderDtos.OrderResponse;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.repository.query.Param;
import jakarta.persistence.LockModeType;

public interface OrderRepository extends JpaRepository<Order, Long> {
    @Query("""
            select new com.marketengine.backend.order.api.OrderDtos$OrderResponse(
                o.id, o.member.id, o.product.id, o.quantity, o.unitPrice, o.status, o.totalAmount,
                o.idempotencyKey, o.createdAt)
            from Order o where o.id <= :upperBound order by o.id desc
            """)
    List<OrderResponse> listThroughId(@Param("upperBound") long upperBound, Limit limit);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select o from Order o where o.id = :id")
    Optional<Order> findByIdForUpdate(@Param("id") Long id);


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
