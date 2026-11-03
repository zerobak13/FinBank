package com.finbank.backend.loan.harness;

import com.finbank.backend.loan.domain.RepaymentType;
import org.junit.jupiter.api.Test;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * 케이스 파일 형식 검사 — LLM이 만든 행에 대한 가드레일.
 *
 * <p>검증 에이전트가 추가한 행이 형식을 어기면 판정 이전에 여기서 걸린다.
 * 열 개수, ID 규칙·중복, 상환방식, 숫자·날짜 형식, expect·source 값, 근거(note) 필수.
 * 형식 오류를 '구현 버그'와 섞지 않고 '케이스 오류'로 따로 보기 위해 분리했다.</p>
 */
class CaseFileFormatTest {

    static final String HEADER = "id,type,principal,rate,term,executedAt,expect,source,note";
    static final String HUMAN_FILE = "/harness/schedule-cases.csv";
    static final String LLM_FILE = "/harness/llm-cases.csv";

    private static final int COLUMNS = 9;
    private static final Pattern HUMAN_ID = Pattern.compile("[HR]-\\d{3}");
    private static final Pattern LLM_ID = Pattern.compile("L-\\d{3,}");
    private static final Set<String> TYPES = java.util.Arrays.stream(RepaymentType.values())
            .map(Enum::name).collect(Collectors.toSet());

    @Test
    void humanCaseFile() {
        report(HUMAN_FILE, checkFile(HUMAN_FILE, Set.of("manual", "regression"), HUMAN_ID));
    }

    @Test
    void llmCaseFile() {
        report(LLM_FILE, checkFile(LLM_FILE, Set.of("llm"), LLM_ID));
    }

    @Test
    void idsAreUniqueAcrossFiles() {
        List<String> problems = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (String resource : List.of(HUMAN_FILE, LLM_FILE)) {
            List<String> lines = readAll(resource);
            for (String line : lines.subList(Math.min(1, lines.size()), lines.size())) {
                if (line.isBlank()) {
                    continue;
                }
                String id = line.split(",", -1)[0];
                if (!seen.add(id)) {
                    problems.add(resource + ": 중복 ID " + id);
                }
            }
        }
        report("ID 중복", problems);
    }

    /** 가드레일 자체의 테스트 — 잘못된 행을 실제로 잡아내는지. */
    @Test
    void guardrailCatchesMalformedRows() {
        Set<String> llm = Set.of("llm");
        assertEquals(0, checkRow("", "L-001,BULLET,1000,0.05,12,2026-01-31,ok,llm,말일 실행", llm, LLM_ID).size());

        List<String> malformed = List.of(
                "L-002,BULLET,1000,0.05,12,2026-01-31,ok,llm,근거에, 쉼표",    // 열 개수
                "L-003,BULLET,1000,0.05,12,2026-01-31,ok,llm,",               // 근거 없음
                "L-004,BALLOON,1000,0.05,12,2026-01-31,ok,llm,없는 방식",       // 상환방식
                "X-005,BULLET,1000,0.05,12,2026-01-31,ok,llm,ID 규칙",          // ID
                "L-006,BULLET,1000,0.05,12,2026-01-31,ok,manual,출처 위조",     // source
                "L-007,BULLET,-1000,0.05,12,2026-01-31,ok,llm,음수 원금",       // 정상 케이스 원금
                "L-008,BULLET,1000.5,0.05,12,2026-01-31,ok,llm,원 미만 원금",   // 정상 케이스 원금
                "L-009,BULLET,1000,0.05,12,2026-02-30,ok,llm,없는 날짜",        // 날짜
                "L-010,BULLET,1000,0.05,12,2026-01-31,maybe,llm,기대값 오류",   // expect
                "L-012,BULLET,1000,0.25,12,2026-01-31,ok,llm,상한 초과 금리",   // INP-02
                "L-013,BULLET,1000,0.05,601,2026-01-31,ok,llm,상한 초과 기간",  // INP-03
                "L-011,BULLET,1000,0.05,12,2026-01-31,ok,llm,\"따옴표\"");      // 따옴표
        for (String row : malformed) {
            assertTrue(!checkRow("", row, llm, LLM_ID).isEmpty(), "잡아내야 하는 행: " + row);
        }
    }

    // ── 검사 로직 ────────────────────────────────────────────────

    static List<String> checkFile(String resource, Set<String> sources, Pattern idPattern) {
        List<String> problems = new ArrayList<>();
        List<String> lines = readAll(resource);
        if (lines.isEmpty() || !HEADER.equals(lines.get(0))) {
            problems.add("헤더가 다르다 — 기대: " + HEADER);
            return problems;
        }
        for (int i = 1; i < lines.size(); i++) {
            if (!lines.get(i).isBlank()) {
                problems.addAll(checkRow(resource + ":" + (i + 1) + " ", lines.get(i), sources, idPattern));
            }
        }
        return problems;
    }

    static List<String> checkRow(String at, String line, Set<String> sources, Pattern idPattern) {
        List<String> p = new ArrayList<>();
        if (line.contains("\"")) {
            p.add(at + "큰따옴표 금지");
            return p;
        }
        String[] c = line.split(",", -1);
        if (c.length != COLUMNS) {
            p.add(at + "열 " + c.length + "개 (기대 " + COLUMNS + "개 — note에 쉼표 금지)");
            return p;
        }
        String id = c[0], type = c[1], expect = c[6], source = c[7], note = c[8];

        if (!idPattern.matcher(id).matches()) {
            p.add(at + "ID 형식 " + id + " (기대: " + idPattern + ")");
        }
        if (!TYPES.contains(type)) {
            p.add(at + "상환방식 " + type + " (기대: " + TYPES + ")");
        }
        BigDecimal principal = decimal(at + "원금 ", c[2], p);
        BigDecimal rate = decimal(at + "금리 ", c[3], p);
        Integer term = integer(at + "기간 ", c[4], p);
        LocalDate executedAt = null;
        try {
            executedAt = LocalDate.parse(c[5]);
        } catch (DateTimeParseException e) {
            p.add(at + "실행일 형식 " + c[5] + " (yyyy-MM-dd, 실제 있는 날짜)");
        }
        if (!Set.of("ok", "reject").contains(expect)) {
            p.add(at + "expect는 ok 또는 reject — " + expect);
        }
        if (!sources.contains(source)) {
            p.add(at + "source는 " + sources + " 중 하나 — " + source);
        }
        if (note.isBlank()) {
            p.add(at + "근거(note)가 비어 있다");
        }
        // 정상(ok) 케이스는 입력 계약 안에 있어야 한다 — 계약 밖이면 '거부되어야 할 입력'이므로 케이스 오류
        if ("ok".equals(expect) && principal != null && rate != null && term != null && executedAt != null) {
            for (LoanInvariant rule : InputContract.violations(principal, rate, term, executedAt)) {
                p.add(at + "expect=ok인데 입력 계약 위반 — " + rule.id() + " " + rule.description());
            }
        }
        return p;
    }

    private static BigDecimal decimal(String at, String value, List<String> problems) {
        try {
            return new BigDecimal(value);
        } catch (NumberFormatException e) {
            problems.add(at + "숫자 형식 " + value);
            return null;
        }
    }

    private static Integer integer(String at, String value, List<String> problems) {
        try {
            return Integer.valueOf(value);
        } catch (NumberFormatException e) {
            problems.add(at + "정수 형식 " + value);
            return null;
        }
    }

    static List<String> readAll(String resource) {
        InputStream in = CaseFileFormatTest.class.getResourceAsStream(resource);
        if (in == null) {
            fail("케이스 파일이 없다: " + resource);
        }
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
            return reader.lines().collect(Collectors.toList());
        } catch (IOException e) {
            throw new IllegalStateException("케이스 파일을 읽을 수 없다: " + resource, e);
        }
    }

    private static void report(String name, List<String> problems) {
        if (!problems.isEmpty()) {
            fail(name + " 형식 오류 " + problems.size() + "건\n  - " + String.join("\n  - ", problems));
        }
    }
}
