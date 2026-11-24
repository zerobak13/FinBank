package com.finbank.backend.common;

import com.finbank.backend.exception.BusinessException;
import com.finbank.backend.exception.ErrorCode;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;

/**
 * 목록 조회의 페이지 규칙 — 번호는 0부터, 크기는 1~{@value #MAX_SIZE}.
 *
 * <p>검증 없이 {@code PageRequest.of}에 넘기면 size=0이나 page=-1에서 IllegalArgumentException이 나 500으로
 * 나가고, 크기를 크게 주면 한 번에 수만 건을 읽는다. 잘못된 값은 400으로 알린다.</p>
 */
public final class PagingPolicy {

    public static final int MAX_SIZE = 100;

    private PagingPolicy() {
    }

    public static PageRequest of(int page, int size, Sort sort) {
        if (page < 0 || size < 1 || size > MAX_SIZE) {
            throw new BusinessException(ErrorCode.INVALID_PAGE_REQUEST,
                    "페이지 번호는 0 이상, 크기는 1~" + MAX_SIZE + "이어야 합니다. (page=" + page + ", size=" + size + ")");
        }
        return PageRequest.of(page, size, sort);
    }

    public static PageRequest of(int page, int size) {
        return of(page, size, Sort.unsorted());
    }
}
