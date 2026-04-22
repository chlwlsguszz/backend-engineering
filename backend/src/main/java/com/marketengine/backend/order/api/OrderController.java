package com.marketengine.backend.order.api;

import java.util.List;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.marketengine.backend.common.response.ApiResponse;
import com.marketengine.backend.order.api.OrderDtos.CreateOrderRequest;
import com.marketengine.backend.order.api.OrderDtos.OrderResponse;
import com.marketengine.backend.order.api.OrderDtos.UpdateOrderRequest;
import com.marketengine.backend.order.application.OrderService;

import jakarta.validation.Valid;

@RestController
@RequestMapping("/api/orders")
public class OrderController {

    private final OrderService orderService;

    public OrderController(OrderService orderService) {
        this.orderService = orderService;
    }

    @PostMapping
    public ResponseEntity<ApiResponse<OrderResponse>> create(@Valid @RequestBody CreateOrderRequest request) {
        return ResponseEntity.ok(ApiResponse.ok(orderService.create(request)));
    }

    @GetMapping("/{orderId}")
    public ResponseEntity<ApiResponse<OrderResponse>> get(@PathVariable Long orderId) {
        return ResponseEntity.ok(ApiResponse.ok(orderService.get(orderId)));
    }

    @GetMapping
    public ResponseEntity<ApiResponse<List<OrderResponse>>> list() {
        return ResponseEntity.ok(ApiResponse.ok(orderService.list()));
    }

    @PutMapping("/{orderId}")
    public ResponseEntity<ApiResponse<OrderResponse>> update(
            @PathVariable Long orderId,
            @Valid @RequestBody UpdateOrderRequest request
    ) {
        return ResponseEntity.ok(ApiResponse.ok(orderService.update(orderId, request)));
    }

    @DeleteMapping("/{orderId}")
    public ResponseEntity<ApiResponse<Void>> delete(@PathVariable Long orderId) {
        orderService.delete(orderId);
        return ResponseEntity.ok(ApiResponse.ok());
    }
}