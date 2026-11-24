package com.finbank.backend.common;

import com.finbank.backend.exception.BusinessException;
import com.finbank.backend.exception.ErrorCode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.PageRequest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** 페이지 규칙 단위 테스트 — 잘못된 값이 500(IllegalArgumentException)이 아니라 400으로 나가는지 */
class PagingPolicyTest {

    @Test
    @DisplayName("번호 0 이상, 크기 1~100이면 그대로 PageRequest")
    void validPage() {
        PageRequest page = PagingPolicy.of(2, PagingPolicy.MAX_SIZE);

        assertThat(page.getPageNumber()).isEqualTo(2);
        assertThat(page.getPageSize()).isEqualTo(100);
    }

    @Test
    @DisplayName("크기 0·101, 번호 -1은 400 INVALID_PAGE_REQUEST")
    void invalidPage_rejected() {
        int[][] invalid = {{0, 0}, {0, 101}, {-1, 20}};
        for (int[] pageAndSize : invalid) {
            assertThatThrownBy(() -> PagingPolicy.of(pageAndSize[0], pageAndSize[1]))
                    .isInstanceOf(BusinessException.class)
                    .extracting(e -> ((BusinessException) e).getErrorCode())
                    .isEqualTo(ErrorCode.INVALID_PAGE_REQUEST);
        }
    }
}
