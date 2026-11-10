package com.marketengine.backend.order.application;

import java.util.List;

import org.postgresql.util.PSQLException;
import org.postgresql.util.PSQLState;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import com.marketengine.backend.common.exception.BusinessException;
import com.marketengine.backend.common.exception.ErrorCode;
import com.marketengine.backend.order.api.OrderDtos.CreateOrderRequest;
import com.marketengine.backend.order.api.OrderDtos.OrderResponse;
import com.marketengine.backend.order.api.OrderDtos.UpdateOrderRequest;
import com.marketengine.backend.order.domain.Order;
import com.marketengine.backend.order.domain.OrderRepository;

import lombok.RequiredArgsConstructor;

@Service
@RequiredArgsConstructor
public class OrderService {

    private final OrderRepository orderRepository;
    private final OrderCreateExecutor orderCreateExecutor;

    // Recovery must run after the writer has rolled back, even with a transactional caller.
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public OrderResponse create(CreateOrderRequest request) {
        try {
            return orderCreateExecutor.execute(request);
        } catch (BusinessException ex) {
            if (ex.errorCode() != ErrorCode.INSUFFICIENT_STOCK) {
                throw ex;
            }
            // A concurrent request with the same key may have consumed the last unit.
            return orderCreateExecutor.findExistingResponse(request).orElseThrow(() -> ex);
        } catch (DataIntegrityViolationException ex) {
            if (!isIdempotencyKeyViolation(ex)) {
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
    public List<OrderResponse> list() {
        return orderRepository.findAll().stream().map(OrderResponse::from).toList();
    }

    @Transactional
    public OrderResponse update(Long orderId, UpdateOrderRequest request) {
        Order order = findOrder(orderId);
        order.changeStatus(request.status());
        order.changeQuantity(request.quantity());
        return OrderResponse.from(order);
    }

    @Transactional
    public void delete(Long orderId) {
        Order order = findOrder(orderId);
        orderRepository.delete(order);
    }

    private boolean isIdempotencyKeyViolation(DataIntegrityViolationException ex) {
        for (Throwable cause = ex.getCause(); cause != null; cause = cause.getCause()) {
            if (cause instanceof PSQLException pg) {
                var error = pg.getServerErrorMessage();
                return PSQLState.UNIQUE_VIOLATION.getState().equals(pg.getSQLState())
                        && error != null
                        && "orders".equals(error.getTable())
                        && Order.IDEMPOTENCY_CONSTRAINT.equals(error.getConstraint());
            }
        }
        return false;
    }

    private Order findOrder(Long orderId) {
        return orderRepository.findById(orderId)
                .orElseThrow(() -> new BusinessException(ErrorCode.RESOURCE_NOT_FOUND, "Order not found"));
    }
}
