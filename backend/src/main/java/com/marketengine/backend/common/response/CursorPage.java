package com.marketengine.backend.common.response;

import java.util.List;
import java.util.function.BiFunction;
import java.util.function.ToLongFunction;

import org.springframework.data.domain.Limit;

import com.marketengine.backend.common.exception.BusinessException;
import com.marketengine.backend.common.exception.ErrorCode;

public record CursorPage<T>(List<T> items, Long nextCursor, boolean hasNext) {

    /** Fetch one extra row in descending ID order, without a count query. */
    public static <T> CursorPage<T> read(Long beforeId, int size,
            BiFunction<Long, Limit, List<T>> query, ToLongFunction<T> id) {
        if (size < 1 || size > 100 || (beforeId != null && beforeId < 1)) {
            throw new BusinessException(ErrorCode.INVALID_REQUEST, "size must be 1..100 and beforeId must be positive");
        }
        long upperBound = beforeId == null ? Long.MAX_VALUE : beforeId - 1;
        List<T> rows = query.apply(upperBound, Limit.of(size + 1));
        boolean more = rows.size() > size;
        List<T> items = List.copyOf(more ? rows.subList(0, size) : rows);
        return new CursorPage<>(items, more ? id.applyAsLong(items.getLast()) : null, more);
    }
}
