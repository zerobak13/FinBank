-- MySQL 컨테이너 최초 기동 시 1회 실행된다.
--
-- 스키마를 둘로 나누는 이유: 테스트는 DatabaseCleaner로 전 테이블을 비우므로
-- 개발 데이터와 같은 스키마를 쓰면 테스트를 돌릴 때마다 개발 데이터가 사라진다.
CREATE DATABASE IF NOT EXISTS finbank
    DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;

CREATE DATABASE IF NOT EXISTS finbank_test
    DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;

-- 애플리케이션 계정에 두 스키마 권한 부여
GRANT ALL PRIVILEGES ON finbank.* TO 'finuser'@'%';
GRANT ALL PRIVILEGES ON finbank_test.* TO 'finuser'@'%';
FLUSH PRIVILEGES;
