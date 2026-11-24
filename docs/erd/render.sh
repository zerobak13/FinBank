#!/usr/bin/env bash
# ERD 그림을 다시 만든다. Mermaid CLI가 필요하다: npm install -g @mermaid-js/mermaid-cli
#   finbank_erd.mmd      → 저장소 루트 finbank_erd.png   (README, 전체 컬럼)
#   finbank_erd_keys.mmd → docs/erd/finbank_erd_keys.png (가이드 3-3, 키만)
# 스키마(Flyway 마이그레이션)가 바뀌면 두 .mmd를 같이 고친 뒤 이 스크립트를 돌린다.
set -euo pipefail
cd "$(dirname "$0")"
mmdc -i finbank_erd.mmd      -o ../../finbank_erd.png -s 2 -b white
mmdc -i finbank_erd_keys.mmd -o finbank_erd_keys.png  -s 2 -b white
