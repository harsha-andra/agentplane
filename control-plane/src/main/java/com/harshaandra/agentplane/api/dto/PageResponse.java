package com.harshaandra.agentplane.api.dto;

import java.util.List;
import org.springframework.data.domain.Page;

/**
 * Deliberately narrow page envelope: {@code {content, totalElements, totalPages, number, size}}
 * only - matching what the React console expects, rather than Spring's default {@code Page}
 * JSON (which also serializes {@code pageable}, {@code sort}, {@code first}, {@code last} etc).
 */
public record PageResponse<T>(List<T> content, long totalElements, int totalPages, int number, int size) {

    public static <T> PageResponse<T> of(Page<T> page) {
        return new PageResponse<>(
                page.getContent(),
                page.getTotalElements(),
                page.getTotalPages(),
                page.getNumber(),
                page.getSize());
    }
}
