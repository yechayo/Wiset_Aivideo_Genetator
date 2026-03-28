package com.comic.dto.response;

import lombok.Data;

import java.util.List;

/**
 * 通用分页响应包装
 */
@Data
public class PaginatedResponse<T> {
    private List<T> items;
    private long total;
    private int page;
    private int size;
    private int totalPages;

    public static <T> PaginatedResponse<T> of(List<T> items, long total, int page, int size) {
        PaginatedResponse<T> response = new PaginatedResponse<>();
        response.setItems(items);
        response.setTotal(total);
        response.setPage(page);
        response.setSize(size);
        response.setTotalPages((int) Math.ceil((double) total / size));
        return response;
    }
}