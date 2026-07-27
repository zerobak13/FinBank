package com.finbank.backend.support;

import java.util.concurrent.atomic.AtomicLong;

/**
 * 테스트용 계좌번호 생성기 — 형식의 단일 출처.
 *
 * <p>기존 테스트들은 {@code "bench-" + System.nanoTime()}처럼 접두어 + 나노초로 계좌번호를 만들었다.
 * 그런데 {@code accounts.account_number}는 {@code VARCHAR(20)}이고 {@code System.nanoTime()}의
 * 자릿수는 머신 가동시간에 따라 <b>늘어난다</b>. 어제까지 20자에 딱 맞던 문자열이 오늘 21자가 되어
 * {@code Data too long for column 'account_number'}로 터진다 — 코드를 고치지 않았는데 깨지는 테스트.</p>
 *
 * <p>그래서 프로덕션과 같은 <b>12자리 숫자</b> 형식으로 고정한다.
 * (시각 하위 9자리 + 시퀀스 3자리 — 병렬 실행에서도 충돌하지 않는다.)</p>
 */
public final class TestAccountNumbers {

    private static final AtomicLong SEQUENCE = new AtomicLong();

    private TestAccountNumbers() {
    }

    public static String next() {
        long time = System.nanoTime() % 1_000_000_000L;   // 9자리
        long seq = SEQUENCE.incrementAndGet() % 1_000L;   // 3자리
        return String.format("%09d%03d", time, seq);
    }
}
