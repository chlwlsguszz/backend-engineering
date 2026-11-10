package com.marketengine.backend.order.api;

import org.springframework.web.bind.annotation.RequestParam;
import com.marketengine.backend.common.response.CursorPage;
import io.swagger.v3.oas.annotations.Operation;

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
import io.swagger.v3.oas.annotations.Operation;
import lombok.RequiredArgsConstructor;

@RestController
@RequestMapping("/api/orders")
@RequiredArgsConstructor
public class OrderController {

    private final OrderService orderService;

    @PostMapping
    public ApiResponse<OrderResponse> create(@Valid @RequestBody CreateOrderRequest request) {
        return ApiResponse.ok(orderService.create(request));
    }

    @GetMapping("/{orderId}")
    public ApiResponse<OrderResponse> get(@PathVariable Long orderId) {
        return ApiResponse.ok(orderService.get(orderId));
    }

    @GetMapping
    @Operation(summary = "List by descending ID", description = "Pass nextCursor as beforeId. Page size is 1 to 100.")
    public ApiResponse<CursorPage<OrderResponse>> list(@RequestParam(required = false) Long beforeId,
            @RequestParam(defaultValue = "20") int size) {
        return ApiResponse.ok(orderService.list(beforeId, size));
    }

    @PutMapping("/{orderId}")
    @Operation(summary = "Change reserved quantity or status at the original unit price",
            description = "CREATED and CONFIRMED reserve stock. CONFIRMED cannot revert to CREATED. "
                    + "Cancellation preserves quantity and is final.")
    public ApiResponse<OrderResponse> update(
            @PathVariable Long orderId,
            @Valid @RequestBody UpdateOrderRequest request
    ) {
        return ApiResponse.ok(orderService.update(orderId, request));
    }

    @DeleteMapping("/{orderId}")
    @Operation(summary = "Cancel an order and release its reserved stock once",
            description = "Repeated cancellation succeeds. The order remains queryable as CANCELLED.")
    public ApiResponse<Void> delete(@PathVariable Long orderId) {
        orderService.delete(orderId);
        return ApiResponse.ok();
    }
}
