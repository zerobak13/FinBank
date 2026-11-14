# FinBank Backend

Spring Boot 3 · Java 17 · MySQL 8 · Flyway · Spring Batch · QueryDSL

설계·실행 방법·검증 결과는 저장소 루트의 [README](../README.md)와
[전체 가이드](../docs/FinBank_전체가이드.pdf)에 있습니다.

```bash
# 저장소 루트에서 — MySQL까지 한 번에
docker compose up

# 로컬 MySQL이 있을 때 (finbank / finbank_test 스키마 필요)
./gradlew test                  # 전체 테스트
./gradlew bootRun               # 개발 서버 :8080
../scripts/harness/fast-test.sh # 계산 엔진 + 불변식 하네스만 (DB 불필요)
```

DB 접속 정보와 JWT 시크릿은 gitignore된 `src/main/resources/application-local.yml`이 채웁니다
(CI·Docker는 환경변수).
