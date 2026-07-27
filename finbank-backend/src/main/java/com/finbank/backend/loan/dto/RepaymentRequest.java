package com.finbank.backend.loan.dto;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * 회차 상환 요청.
 *
 * <p>installmentNo는 선택이다. 생략하면 서버가 "가장 빠른 미납 회차"를 상환한다.
 * 값을 보내면 그 회차가 정말 가장 빠른 미납 회차인지 검증한다(순서대로만 상환).
 * 클라이언트가 화면에서 본 회차와 서버가 처리할 회차가 어긋나는 것을 막기 위한 확인용 파라미터다.</p>
 */
public record RepaymentRequest(
        @Schema(description = "상환할 회차 번호(선택). 생략 시 가장 빠른 미납 회차", example = "1")
        Integer installmentNo
) {
}
