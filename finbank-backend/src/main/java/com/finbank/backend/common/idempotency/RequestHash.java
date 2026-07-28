package com.finbank.backend.common.idempotency;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * 요청 내용 지문(SHA-256).
 *
 * <p>같은 멱등키로 "다른 내용"을 보내는 것은 클라이언트 버그이거나 키 재사용 사고다.
 * 요청을 통째로 저장하는 대신 해시만 비교해 탐지한다(저장 비용 ↓, 개인정보 노출 ↓).</p>
 */
public final class RequestHash {

    private RequestHash() {
    }

    /** 엔드포인트 식별자 + 파라미터들을 구분자로 이어 붙여 해시한다. */
    public static String of(Object... parts) {
        String raw = Stream.of(parts)
                .map(String::valueOf)
                .collect(Collectors.joining("|"));
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(raw.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256을 사용할 수 없습니다.", e);
        }
    }
}
