package dev.youneskaouani.vestige.common.web;

import java.util.List;
import java.util.function.Function;
import org.springframework.data.domain.Page;

/**
 * A page of API-facing DTOs, deliberately not {@link org.springframework.data.domain.Page} itself.
 *
 * <p>Returning a Spring Data {@code Page} straight from a controller works, but it serialises
 * Hibernate's internal paging metadata (a {@code pageable} object, a {@code sort} object) as public
 * API shape, and Spring Boot 3.3 logs a warning recommending exactly this: a small, stable,
 * hand-written response type instead.
 */
public record PageResponse<T>(
        List<T> items, int page, int size, long totalElements, int totalPages) {

    public static <S, T> PageResponse<T> of(Page<S> page, Function<S, T> mapper) {
        return new PageResponse<>(
                page.getContent().stream().map(mapper).toList(),
                page.getNumber(),
                page.getSize(),
                page.getTotalElements(),
                page.getTotalPages());
    }
}
