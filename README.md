# FinBank

금융 거래의 **무결성과 동시성 문제**를 중심으로 설계한  
백엔드 중심의 금융 서비스 포트폴리오 프로젝트입니다.

실제 배포 환경에서 동작하는 API 서버와 데이터베이스를 기반으로  
인증, 트랜잭션, 동시성 제어를 구현하고 검증하는 데 초점을 두었습니다.

---

## Live Demo

- **Frontend**: https://finbank-frontend.vercel.app
- **Backend API**: https://finbank-backend.onrender.com

> ⚠️ Render 무료 티어 특성상 첫 요청 시 서버 기동에 약 1분 정도 소요될 수 있습니다.

---

##  Tech Stack

### Backend
- Java 17
- Spring Boot 3
- Spring Security
- JWT Authentication
- Spring Data JPA
- MySQL (Aiven)

### Frontend
- React
- Vite
- TailwindCSS

### Infrastructure
- Vercel (Frontend)
- Render (Backend)
- Aiven MySQL (Database)

---

##  System Architecture

![FinBankSystemArchitecture](./FinBankSystemArchitecture.drawio.png)

본 프로젝트는 프론트엔드, 백엔드, 데이터베이스를 분리하여  
각 계층의 책임을 명확히 하도록 설계했습니다.

- 사용자는 프론트엔드를 통해 요청을 전달합니다.
- 프론트엔드는 REST API(JSON)를 통해 백엔드와 통신합니다.
- 인증, 트랜잭션, 동시성 제어는 백엔드의 책임으로 관리합니다.
- 데이터베이스 접근은 백엔드에서만 이루어집니다.
- 금융 거래의 무결성을 위해 트랜잭션과 **비관적 락(PESSIMISTIC_WRITE)**을 적용했습니다.

---

##  ERD (Entity Relationship Diagram)

![FinBank ERD](./finbank_erd.png)

계좌(Account)를 중심으로 거래 로그(Transaction Log)를 분리하여  
금융 거래 이력 관리와 검증이 가능하도록 모델링했습니다.

### 주요 설계 포인트
- 회원(Member) 1 : N 계좌(Account)
- 계좌 간 거래는 `TRANSACTION_LOGS` 테이블에서 관리
- 입금 / 출금 / 이체를 하나의 로그 테이블로 통합
- 거래 이후의 잔액(`balance_after`)을 기록하여 이력 추적 가능

---

##  Authentication & Security

- **JWT 기반 인증**을 적용하여 Stateless한 인증 구조를 구성했습니다.
- Spring Security를 통해 인증/인가를 처리합니다.
- CORS 설정을 통해 허용된 프론트엔드 도메인에서만 API 접근을 허용했습니다.

---

##  Transaction & Concurrency Control

금융 서비스에서 가장 중요한 **동시성 문제와 잔액 무결성**을 중점적으로 고려했습니다.

### 트랜잭션 처리
- 모든 금액 변경 로직은 `@Transactional` 범위 내에서 처리합니다.
- 이체 로직은 내부적으로 **Transfer Out / Transfer In** 단계로 분리하여 처리합니다.

### 동시성 제어
- 계좌 조회 시 `PESSIMISTIC_WRITE` 비관적 락을 적용했습니다.
- 동시에 여러 거래가 발생하더라도 잔액 불일치가 발생하지 않도록 설계했습니다.

### 동시성 검증 결과 (벤치마크)

잔액 **200,000원** 계좌에 **50개 스레드가 동시에 10,000원씩 출금**을 시도한 벤치마크입니다.
정상적으로 처리되면 정확히 **20건만 성공하고 잔액은 0**이 되어야 합니다.
"락 없이 read-modify-write" vs "비관적 락(현재 구현)"을 비교한 대표 실행 결과:

| 방식 | 성공(WITHDRAW 로그) | 최종 잔액 | 잔액 오차 (lost update) | 처리 시간 |
|------|:---:|:---:|:---:|:---:|
| 락 없음 (read-modify-write) | 10 | 150,000원 | **50,000원 증발** | 69ms |
| **비관적 락 (현재 구현)** | **20** | **0원** | **0원 (정합)** | 60ms |

- **정합성**: 락 없이 처리하면 로그상 10만원이 출금됐는데 잔액은 5만원만 줄어 **5만원이 증발**(lost update)했습니다. 비관적 락은 정확히 20건만 성공하고 잔액이 0으로, 한도만큼만 정확히 인출됩니다.
- **안정성**: 락 없는 경쟁에서는 `Deadlock found (SQLState 40001)`이 대량 발생해 상당수 요청이 롤백됐습니다.
- **속도**: 이 시나리오에서는 오히려 비관적 락이 더 빨랐습니다 — 락 없는 쪽은 데드락 → 롤백 → 재시도로 지연됩니다.
- **재현**: `./gradlew test --tests "com.finbank.backend.service.ConcurrencyBenchmarkTest"` (`ConcurrencyBenchmarkTest`)

> 숫자는 타이밍/데드락에 따라 실행마다 다소 달라지지만, "락 없음 = lost update·데드락 / 비관적 락 = 정합·안정"이라는 결론은 항상 동일합니다.

---

##  Testing

모든 기능을 포괄적으로 테스트하기보다는,  
금융 서비스에서 가장 위험도가 높은 시나리오에 집중하여 테스트를 작성했습니다.

### 테스트 전략
- 동시 이체 상황에서의 잔액 무결성 검증
- 여러 스레드에서 동시에 접근하는 경우에도 데이터 정합성 유지 확인
- CountDownLatch를 활용한 동시성 테스트

> 테스트는 “개수”보다 **위험도가 높은 영역을 정확히 검증하는 것**을 목표로 했습니다.

---

##  금액 처리 정책 (BigDecimal)

여신(대출) 모듈의 이자 계산을 앞두고 금액 타입을 `Long`(원 단위 정수)에서 **`BigDecimal`(DB `DECIMAL(19,4)`)**로 전환했습니다.

- **전환 시점**: 이자·수수료처럼 소수 연산이 필요한 도메인이 들어오기 "직전"에 전환 — 타입이 갈라진 채 모듈이 늘어나면 재작업 비용이 커지기 때문입니다.
- **정책의 단일 출처**: 스케일·반올림 규칙을 `MoneyPolicy` 클래스 하나에 고정했습니다. (중간 계산 scale 10 / 확정 금액은 원 미만 절사 / 일할 계산 연 365일 고정)
- **점진 이관(expand-contract)**: 기존 `BusinessException(String)` 생성자를 유지한 채 `ErrorCode` 기반 생성자를 추가해, 기존 코드 무수정으로 409/422 상태코드 구분이 가능한 예외 체계를 도입했습니다.

---

##  트러블슈팅

### 1. BigDecimal 전환 후 테스트 전멸 — `equals`는 스케일까지 비교한다

- **문제**: 금액 타입 전환 후 기존 테스트의 잔액 단언이 전부 실패. 값은 분명 맞는데 `assertThat(balance).isEqualTo(30_000L)`이 깨짐.
- **원인**: DB 컬럼이 `DECIMAL(19,4)`라서 조회된 값의 스케일이 4(`30000.0000`)인데, `BigDecimal.equals`는 **값뿐 아니라 스케일까지 비교**한다. `30000 != 30000.0000`으로 판정.
- **해결**: 값 비교는 전부 `compareTo` 기반으로 교체 — 테스트는 `isEqualByComparingTo`, 프로덕션 코드의 잔액 비교는 `compareTo()`/`signum()`. API 응답은 `MoneyPolicy.toWon()`으로 스케일 0으로 정리해 JSON 형태(`1000`)를 기존과 동일하게 유지(프론트 무수정).
- **교훈**: BigDecimal의 동등성은 `equals`가 아니라 `compareTo`. 금액이 지나가는 모든 비교 지점이 잠재적 버그 포인트다.

### 2. Flyway 체크섬 불일치 → 유령 마이그레이션 — 로컬 DB의 과거 브랜치 잔재

- **문제**: 새 마이그레이션(V3, BIGINT→DECIMAL) 추가 후 모든 통합 테스트가 `Migration checksum mismatch for version 3`으로 컨텍스트 기동 실패. `repair`로 체크섬을 맞추자 이번엔 `Schema-validation: wrong column type ... found [bigint]` — **마이그레이션이 적용된 걸로 기록만 되고 실제 ALTER는 실행되지 않은 상태**.
- **원인**: 로컬 DB에 과거 실험 브랜치에서 적용했던 다른 내용의 V3·V4 기록이 `flyway_schema_history`에 남아 있었다. Flyway는 "버전 3은 이미 적용됨"으로 판단해 새 V3를 건너뛰었고, 히스토리의 체크섬은 옛 파일 기준이라 불일치가 났다.
- **해결**: 로컬 개발 DB이므로 스키마를 `flyway.clean()`으로 비우고 V1부터 재마이그레이션. (임시 `FlywayMigrationStrategy` 빈으로 1회 실행 후 즉시 삭제 — 남겨두면 실제 파일 변조를 못 잡는 구멍이 된다.) 재실행 후 16개 테스트 전부 통과.
- **교훈**: 적용된 마이그레이션 파일은 절대 수정하지 않는다. 브랜치마다 마이그레이션을 실험할 땐 DB도 브랜치별로 분리하거나 초기화한다. 공유 DB였다면 clean이 아니라 전진 마이그레이션(forward-fix)으로 풀어야 한다.

---

##  프로젝트를 통해 고민한 점

- 금융 거래에서 **어디까지를 데이터 모델로 표현하고**,  
  **어디부터를 비즈니스 로직으로 처리할 것인지**
- 트랜잭션과 락의 적용 범위
- 실제 배포 환경에서의 동작 안정성
- 설계(ERD)와 구현 간의 일관성 유지

---

##  향후 개선 방향

- 거래 로그 조회 성능 개선 (인덱스 / 조회 패턴 최적화)
- 계좌 상태 관리 확장 (휴면, 정지 등)
- 감사(Audit) 관점의 로그 분리
- 대용량 트래픽 상황을 가정한 확장 구조 검토

---

##  마무리

본 프로젝트는 단순한 CRUD 구현이 아닌,  
**금융 도메인에서 중요한 트랜잭션 무결성과 동시성 문제를 직접 설계하고 검증하는 경험**을 목표로 했습니다.

실제 배포 환경에서 동작하는 구조를 통해  
백엔드 설계와 데이터 모델링에 대한 이해를 정리할 수 있었습니다.
