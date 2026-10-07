package app.pagefault.hyperllm.common.page;

/**
 * Immutable pagination parameters (1-based page index).
 */
public record PageQuery(int page, int pageSize) {

    public static final int DEFAULT_PAGE_SIZE = 20;
    public static final int MAX_PAGE_SIZE = 200;

    public PageQuery {
        if (page < 1) {
            throw new IllegalArgumentException("page must be >= 1");
        }
        if (pageSize < 1 || pageSize > MAX_PAGE_SIZE) {
            throw new IllegalArgumentException("pageSize must be between 1 and " + MAX_PAGE_SIZE);
        }
    }

    public static PageQuery of(int page, int pageSize) {
        return new PageQuery(page, pageSize);
    }

    public int offset() {
        return (page - 1) * pageSize;
    }
}
