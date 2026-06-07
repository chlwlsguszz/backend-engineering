package com.marketengine.backend.order.application;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import com.marketengine.backend.common.exception.BusinessException;
import com.marketengine.backend.common.exception.ErrorCode;
import com.marketengine.backend.member.domain.Member;
import com.marketengine.backend.order.api.OrderDtos.CreateOrderRequest;
import com.marketengine.backend.order.api.OrderDtos.OrderResponse;
import com.marketengine.backend.order.domain.Order;
import com.marketengine.backend.order.domain.OrderRepository;
import com.marketengine.backend.order.domain.OrderStatus;
import com.marketengine.backend.product.domain.Product;
import com.marketengine.backend.product.domain.ProductRepository;

import lombok.RequiredArgsConstructor;

@Service
@RequiredArgsConstructor
public class OrderCreateExecutor {

    private final OrderRepository orderRepository;
    private final ProductRepository productRepository;

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public OrderResponse execute(CreateOrderRequest request, Member member) {
        Product product = productRepository.findByIdForUpdate(request.productId())
                .orElseThrow(() -> new BusinessException(ErrorCode.RESOURCE_NOT_FOUND, "Product not found"));

        product.decreaseStock(request.quantity());

        Order saved = orderRepository.save(
                new Order(
                        member,
                        product,
                        request.quantity(),
                        product.getPriceAmount(),
                        OrderStatus.CREATED,
                        request.idempotencyKey()
                )
        );
        return OrderResponse.from(saved);
    }
}
