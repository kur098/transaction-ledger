package com.example.ledger.common;

/** Validated page/size pair shared by paginated endpoints. */
public record PageRequestParams(int page, int size) {

    public static final int MAX_SIZE = 500;

    public static PageRequestParams of(int page, int size) {
        if (page < 0) {
            throw new ValidationException("INVALID_PAGE", "page must be >= 0.");
        }
        if (size < 1 || size > MAX_SIZE) {
            throw new ValidationException("INVALID_PAGE_SIZE", "size must be between 1 and " + MAX_SIZE + ".");
        }
        return new PageRequestParams(page, size);
    }

    public long offset() {
        return (long) page * size;
    }
}
