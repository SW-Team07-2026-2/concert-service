package com.indie.concert.global.common.response;

import org.springframework.data.domain.Slice;

import java.util.List;
import java.util.function.Function;

/**
 * 목록 페이지 응답 — { items, page, size, hasNext }. 공연 · 공연장 목록은 page (0부터) · size 입니다 (08 §0.6).
 * 피드처럼 계속 쌓이는 목록이 아니라 OFFSET 으로 충분합니다. 내 예매 목록만 커서를 씁니다.
 */
public record PageResult<T>(List<T> items, int page, int size, boolean hasNext) {

    public static <E, T> PageResult<T> of(Slice<E> slice, Function<List<E>, List<T>> mapper) {
        return new PageResult<>(mapper.apply(slice.getContent()), slice.getNumber(), slice.getSize(), slice.hasNext());
    }
}
