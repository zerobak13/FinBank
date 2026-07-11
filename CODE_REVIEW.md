# FinBank 코드 리뷰 (main 브랜치)

> 대상: 다우기술 / 우리FIS / 뱅크웨어글로벌 등 금융 IT 면접 관점
> 기준 커밋: `01ea329` (비관적 락 + JWT/Refresh Token 단계)
> 리뷰 축: ① 금융 도메인 이해도 ② 기술 깊이 ③ 코드 퀄리티

---

## 🔴 심각도 높음 — 면접에서 바로 지적당할 부분

### 1. 입금/출금에 동시성 제어가 없다 (프로젝트 정체성과 충돌)

이 프로젝트의 핵심 셀링포인트는 "동시성 제어"인데, 정작 **입금·출금은 락 없이** 처리된다.

```java
// deposit / withdraw
Account account = accountRepository.findById(accountId)   // 락 없음
account.deposit(amount);   // read-modify-write
accountRepository.save(account);
```

같은 계좌에 동시 출금 2건이 들어오면 lost update로 잔액이 틀어질 수 있다.
이체만 `findWithLockingById`로 막고 입출금은 안 막은 건 논리적 비일관성이다.
면접관이 "이체는 락 걸었는데 출금은 왜 안 걸었나요?"라고 물으면 방어가 어렵다.

**해결**: deposit/withdraw도 `findWithLockingById` 사용, 혹은 `@Version` 낙관적 락.
→ 이걸 "발견 → 수정 → JMeter로 전후 비교"하면 그대로 **트러블슈팅 1개**가 된다.

### 2. 금액 타입이 `Long` (원 단위 정수)

`balance`, `amount` 모두 `Long`. 수신(입출금)만 할 땐 버틸 수 있지만,
**여신 모듈의 이자 계산에는 치명적**이다. 이자는 소수점·반올림 정책(HALF_UP, 절사)이 핵심인데
`Long`으로는 표현 불가. 면접에서 "이자 계산 시 반올림 어떻게 처리했나요?"가 반드시 나온다.

**해결**: 최소한 여신 모듈은 `BigDecimal` + 명시적 `RoundingMode` + `scale` 고정.
수신 쪽도 장기적으로 `BigDecimal`(scale 0) 통일 검토. DB는 `DECIMAL(19,4)`.

### 3. HTTP 상태코드 의미 불일치 (권한 오류가 400)

본인 계좌가 아닐 때 `BusinessException` → GlobalExceptionHandler가 **일괄 400**으로 매핑.
그런데 Swagger 문서에는 "403 본인 계좌 아님"이라고 적혀 있다. **문서와 실제 동작이 다르다.**

```java
if (!account.getMember().getId().equals(member.getId())) {
    throw new BusinessException("본인 계좌만 조회할 수 있습니다."); // → 400 (실제로는 403이어야)
}
```

**해결**: `ForbiddenException`(403) 분리, 잔액부족 등 순수 비즈니스 규칙 위반만 400.
금융권은 에러 응답 규격에 민감하다 — 상태코드/에러코드 체계를 정리하면 코드 퀄리티 점수가 오른다.

---

## 🟠 중간 — 개선 권장

### 4. 인증 필터가 매 요청마다 DB 조회

`JwtAuthenticationFilter`가 요청마다 `memberRepository.findByEmail()`을 호출한다.
JWT는 이미 subject(email)를 담고 있으므로 검증에 DB 조회가 꼭 필요하진 않다.
트래픽이 늘면 인증이 병목이 된다.

**해결**: 토큰 검증만으로 인증 컨텍스트 구성(DB 조회 생략), 회원 엔티티가 필요한 지점에서만 조회.
또는 회원 존재 여부를 Redis로 캐싱.

### 5. 계좌번호 생성이 충돌 위험 + `Random`

```java
Random random = new Random();          // 보안/유니크성 약함
for (int i = 0; i < 12; i++) sb.append(random.nextInt(10));
```

- `Random`은 예측 가능. 계좌번호 생성엔 `SecureRandom` 권장.
- **중복 시 재시도 로직이 없다.** DB 유니크 제약에 걸리면 그대로 500 에러.

**해결**: `SecureRandom` + 유니크 충돌 시 재생성 루프(최대 N회) 또는 시퀀스 기반 발급.

### 6. `getAccountDetail`의 무한 조회 (`Pageable.unpaged()`)

```java
transactionLogRepository.findByAccountPerspective(..., Pageable.unpaged())
```

거래 전체를 한 번에 로딩한다. 거래가 수만 건이면 OOM/지연.
(현재 컨트롤러는 이 메서드를 안 쓰고 페이징 버전을 쓰므로 **사실상 데드코드**이기도 하다.)

**해결**: 미사용이면 삭제, 사용한다면 반드시 페이징. 죽은 코드 정리도 리뷰 포인트.

### 7. 예외 처리에서 `printStackTrace()`

```java
@ExceptionHandler(Exception.class)
public ResponseEntity<ApiError> handleException(Exception ex) {
    ex.printStackTrace();   // ❌ 로거를 써야 함
```

`printStackTrace()`는 실무에서 지양. 로그 레벨/포맷/추적ID 관리가 안 된다.

**해결**: `Logger.error("...", ex)`. 나아가 요청별 traceId(MDC)를 남기면 금융권 감사(audit) 관점에서 가점.

### 8. 검증(Validation)이 서비스 계층에 흩어져 있음

`deposit`/`withdraw`가 `@RequestParam Long amount`로 받고, 0 이하 검증을 서비스에서 if로 처리.
`@Valid` + `@Positive` 같은 선언적 검증을 못 쓰는 구조.

**해결**: 요청을 DTO(body)로 받고 `@Positive`, `@NotNull`로 컨트롤러 진입점에서 검증.
REST 관점에서도 금액 같은 상태 변경 값은 query param보다 body가 적절.

### 9. 잔액 검증 로직 중복

`withdraw`가 서비스에서 `balance < amount` 체크하고, `Account.withdraw()` 내부에서도
`IllegalStateException`을 던진다. 규칙이 두 군데. 도메인 규칙은 엔티티에 두고
서비스는 도메인 예외를 잡아 비즈니스 예외로 변환하는 식으로 일원화하는 게 깔끔하다.

---

## 🟡 낮음 — 디테일 / 클린업

- **주석 처리된 옛 transfer 코드**가 파일에 그대로 남아 있음 → 삭제(git 이력에 있음). 데드코드는 감점.
- **에러 메시지 언어 혼재**: 도메인 예외는 영어(`"Insufficient balance"`), 비즈니스 예외는 한글. 통일 필요.
- **Audit 필드 부족**: `createdAt`만 있고 `updatedAt`, `createdBy` 없음. 금융권은 변경 이력 추적을 중시.
- **Account 상태 관리 부재**: `locked`(boolean)만 존재. 실무는 정상/휴면/정지/해지 등 `status` enum이 일반적. (README의 향후 개선에도 언급됨)
- **`Setter` import만 있고 미사용** (Account, TransactionLog) → 불필요 import 정리.
- **권한(Role) 개념 없음**: 인증 authority가 `emptyList()`. 관리자 기능(배치 수동 실행 등) 붙일 때 `ROLE_ADMin` 체계가 필요해진다.
- **`createAccount` 응답이 200**: 리소스 생성은 `201 Created` + Location 헤더가 정석.

---

## 🧭 여신 모듈 붙이기 전에 미리 잡아둘 것

계획대로 loan 모듈을 얹으려면 지금 구조에서 아래를 선반영하면 재작업이 준다.

1. **금액 → `BigDecimal` 통일** (이자 계산의 전제). 지금 `Long`인 채로 여신을 붙이면 타입이 갈라진다.
2. **에러 코드 체계 정립** (403/409/422 분리). 여신은 한도초과·중복실행 등 상태코드가 다양해진다.
3. **동시성 전략 문서화**: 현재 "이체=비관적 락, ID 오름차순". 여신 한도 차감에도 같은 원칙을 재사용한다고 명시 → 스토리 일관성.
4. **멱등성 훅 자리 마련**: 대출 실행 API는 멱등키가 필수. 지금 컨트롤러 구조가 body 기반이면 `Idempotency-Key` 헤더 붙이기 쉬움.
5. **패키지 분리 준비**: 현재 `domain/service/repository`가 계좌 중심 평면 구조. `account`, `loan`, `member` 도메인 패키지로 나눌 계획이면 지금부터 경계 의식.

---

## ✅ 잘 되어 있는 점 (면접에서 강조할 것)

- **이체 데드락 방지**: 두 계좌를 ID 오름차순으로 락 획득 → lock ordering. 이론을 코드로 증명함.
- **비관적 락 재조회 패턴**: 락 없이 검증 → 락 걸고 재조회 → 재매핑. 흐름이 정확.
- **거래 원장(ledger) 설계**: 입출금/이체를 단일 `transaction_logs`로 통합 + `balance_after` 기록 → 시점 잔액 추적 가능.
- **Refresh Token을 DB 불투명 토큰으로**: "JWT는 탈취 시 못 막지만 DB 토큰은 즉시 폐기 가능" → 근거 있는 기술 선택. 면접에서 좋은 답변 소재.
- **Flyway 마이그레이션 + `ddl-auto: validate`**: 스키마를 코드로 관리. 실무 감각 있음.
- **동시성 테스트 존재**: `CountDownLatch` 기반 "동시 이체 시 하나만 성공" 검증.

---

## 📌 우선순위 정리 (권장 착수 순서)

| 순위 | 항목 | 이유 |
|---|---|---|
| 1 | 입출금 동시성 제어 추가 | 프로젝트 정체성. 트러블슈팅 소재화 가능 |
| 2 | 금액 `BigDecimal` 전환 | 여신 이자 계산의 전제 |
| 3 | 에러 상태코드 체계 정리(403 분리) | 문서-동작 불일치 해소, 코드 퀄리티 |
| 4 | 인증 필터 DB 조회 제거 | 성능. JMeter 수치로 증명 가능 |
| 5 | 데드코드/주석/import 정리 | 리뷰 첫인상 |
