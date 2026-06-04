package com.securetransfer.api.web.dto;

import org.springframework.data.domain.Page;

import java.util.List;

/**
 * A small, stable pagination envelope — so we don't serialize Spring Data's
 * Page type directly (its JSON shape isn't a guaranteed-stable API).
 */
public record PagedResponse<T>(
        List<T> content,
        int page,
        int size,
        long totalElements,
        int totalPages,
        boolean hasNext
) {
    public static <T> PagedResponse<T> from(Page<T> page) {
        return new PagedResponse<>(
                page.getContent(),
                page.getNumber(),
                page.getSize(),
                page.getTotalElements(),
                page.getTotalPages(),
                page.hasNext());
    }
}
