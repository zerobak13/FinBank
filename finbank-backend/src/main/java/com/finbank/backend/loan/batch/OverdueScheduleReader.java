package com.finbank.backend.loan.batch;

import com.finbank.backend.loan.domain.RepaymentSchedule;
import com.finbank.backend.loan.repository.RepaymentScheduleRepository;
import org.springframework.batch.item.ItemReader;
import org.springframework.data.domain.PageRequest;

import java.time.LocalDate;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;

/**
 * 연체 후보(납기 지난 PENDING 회차) 커서 기반 Reader.
 *
 * <p><b>왜 JpaPagingItemReader를 쓰지 않는가</b><br>
 * 이 배치는 읽은 행의 상태를 PENDING → OVERDUE로 바꾼다. 즉 <b>읽는 조건을 스스로 무너뜨린다</b>.
 * 페이지 번호로 읽으면 1페이지(0~99)를 처리해 100건이 조건에서 빠진 뒤 2페이지(offset 100)를 읽으므로,
 * 원래 100~199번이던 행들이 0~99로 당겨져 <b>통째로 건너뛴다</b>. 10만 건 중 절반만 처리되고도
 * 배치는 "정상 종료"로 끝난다 — 조용해서 더 위험한 종류의 버그다.</p>
 *
 * <p><b>커서 방식</b>은 "마지막으로 읽은 id 다음부터"를 읽으므로 앞쪽 행이 조건에서 빠져도 밀리지 않는다.
 * offset이 커질수록 느려지는 문제도 없다.</p>
 *
 * <p>스텝 스코프로 매 실행마다 새로 만들어지며, 청크 단위로 버퍼를 채운다.</p>
 */
public class OverdueScheduleReader implements ItemReader<RepaymentSchedule> {

    private final RepaymentScheduleRepository repository;
    private final LocalDate baseDate;
    private final int chunkSize;

    private final Deque<RepaymentSchedule> buffer = new ArrayDeque<>();
    private long lastId = 0L;

    public OverdueScheduleReader(RepaymentScheduleRepository repository, LocalDate baseDate, int chunkSize) {
        this.repository = repository;
        this.baseDate = baseDate;
        this.chunkSize = chunkSize;
    }

    @Override
    public RepaymentSchedule read() {
        if (buffer.isEmpty()) {
            fill();
        }
        return buffer.poll(); // 비어 있으면 null → 스텝 종료
    }

    private void fill() {
        List<RepaymentSchedule> next = repository.findOverdueCandidates(
                baseDate, lastId, PageRequest.of(0, chunkSize));
        if (next.isEmpty()) {
            return;
        }
        buffer.addAll(next);
        lastId = next.get(next.size() - 1).getId();
    }
}
