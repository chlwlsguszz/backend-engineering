package com.marketengine.backend.order.application;

import org.postgresql.util.PSQLState;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import com.marketengine.backend.common.exception.BusinessException;
import com.marketengine.backend.common.exception.ErrorCode;
import com.marketengine.backend.common.exception.PostgresConstraint;
import com.marketengine.backend.common.response.CursorPage;
import com.marketengine.backend.order.api.OrderDtos.CreateOrderRequest;
import com.marketengine.backend.order.api.OrderDtos.OrderResponse;
import com.marketengine.backend.order.api.OrderDtos.UpdateOrderRequest;
import com.marketengine.backend.order.domain.Order;
import com.marketengine.backend.order.domain.OrderRepository;
import com.marketengine.backend.order.domain.OrderStatus;
import com.marketengine.backend.product.infrastructure.ProductInventory;
import com.marketengine.backend.product.infrastructure.search.ProductSearchOutbox;

import lombok.RequiredArgsConstructor;

@Service
@RequiredArgsConstructor
public class OrderService {

    private final OrderRepository orderRepository;
    private final OrderCreateExecutor orderCreateExecutor;
    private final ProductInventory inventory;
    private final ProductSearchOutbox productSearchOutbox;

    // Recovery must run after the writer has rolled back, even with a transactional caller.
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public OrderResponse create(CreateOrderRequest request) {
        try {
            return orderCreateExecutor.execute(request);
        } catch (BusinessException ex) {
            if (ex.errorCode() != ErrorCode.INSUFFICIENT_STOCK && ex.errorCode() != ErrorCode.PRODUCT_NOT_FOR_SALE) {
                throw ex;
            }
            // The winning order can commit before the product sells out or is withdrawn.
            return orderCreateExecutor.findExistingResponse(request).orElseThrow(() -> ex);
        } catch (DataIntegrityViolationException ex) {
            if (!PostgresConstraint.matches(ex, PSQLState.UNIQUE_VIOLATION, "orders", Order.IDEMPOTENCY_CONSTRAINT)) {
                throw ex;
            }
            return orderCreateExecutor.findExistingResponse(request)
                    .orElseThrow(() -> new BusinessException(
                            ErrorCode.CONFLICT,
                            "Duplicate order request is still being processed"
                    ));
        }
    }

    @Transactional(readOnly = true)
    public OrderResponse get(Long orderId) {
        return OrderResponse.from(findOrder(orderId));
    }

    @Transactional(readOnly = true)
    public CursorPage<OrderResponse> list(Long beforeId, int size) {
        return CursorPage.read(beforeId, size, orderRepository::listThroughId, OrderResponse::id);
    }

    @Transactional
    public OrderResponse update(Long orderId, UpdateOrderRequest request) {
        Order order = findOrderForUpdate(orderId);
        revise(order, request.status(), request.quantity());
        return OrderResponse.from(order);
    }

    @Transactional
    public void delete(Long orderId) {
        Order order = findOrderForUpdate(orderId);
        revise(order, OrderStatus.CANCELLED, order.getQuantity());
    }

    private Order findOrderForUpdate(Long orderId) {
        return orderRepository.findByIdForUpdate(orderId)
                .orElseThrow(() -> new BusinessException(ErrorCode.RESOURCE_NOT_FOUND, "Order not found"));
    }

    private void revise(Order order, OrderStatus status, int quantity) {
        int additionalUnits = order.revise(status, quantity);
        if (additionalUnits == 0) {
            return;
        }
        long productId = order.getProduct().getId();
        if (additionalUnits > 0) {
            inventory.reserve(productId, additionalUnits);
        } else {
            inventory.release(productId, -additionalUnits);
        }
        productSearchOutbox.enqueue(productId);
    }

    private Order findOrder(Long orderId) {
        return orderRepository.findById(orderId)
                .orElseThrow(() -> new BusinessException(ErrorCode.RESOURCE_NOT_FOUND, "Order not found"));
    }
}
