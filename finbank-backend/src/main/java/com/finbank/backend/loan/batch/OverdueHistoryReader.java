package com.finbank.backend.loan.batch;

import com.finbank.backend.loan.domain.OverdueHistory;
import com.finbank.backend.loan.repository.OverdueHistoryRepository;
import org.springframework.batch.item.ItemReader;
import org.springframework.data.domain.PageRequest;

import java.time.LocalDate;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;

/**
 * 연체이자 가산 대상(미해소 연체이력) 커서 기반 Reader.
 *
 * <p>{@link OverdueScheduleReader}와 같은 이유로 커서 방식이다 —
 * 이 배치도 읽은 행의 {@code lastAccruedAt}을 바꿔 읽기 조건에서 스스로 제외시킨다.</p>
 */
public class OverdueHistoryReader implements ItemReader<OverdueHistory> {

    private final OverdueHistoryRepository repository;
    private final LocalDate baseDate;
    private final int chunkSize;

    private final Deque<OverdueHistory> buffer = new ArrayDeque<>();
    private long lastId = 0L;

    public OverdueHistoryReader(OverdueHistoryRepository repository, LocalDate baseDate, int chunkSize) {
        this.repository = repository;
        this.baseDate = baseDate;
        this.chunkSize = chunkSize;
    }

    @Override
    public OverdueHistory read() {
        if (buffer.isEmpty()) {
            fill();
        }
        return buffer.poll();
    }

    private void fill() {
        List<OverdueHistory> next = repository.findAccrualTargets(
                baseDate, lastId, PageRequest.of(0, chunkSize));
        if (next.isEmpty()) {
            return;
        }
        buffer.addAll(next);
        lastId = next.get(next.size() - 1).getId();
    }
}
