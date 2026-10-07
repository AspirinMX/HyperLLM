package app.pagefault.hyperllm.common.page;

import java.util.List;

/**
 * Standard paginated response payload matching the REST API contract.
 */
public record PageResponse<T>(
        List<T> items,
        int page,
        int pageSize,
        long total
) {
    public PageResponse {
        items = List.copyOf(items);
    }
}
