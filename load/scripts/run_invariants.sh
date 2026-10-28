#!/usr/bin/env bash
# =============================================================================
# run_invariants.sh — Wrapper chạy file SQL kiểm tra bất biến DB
# =============================================================================
# Sử dụng:
#   bash load/scripts/run_invariants.sh
#   hoặc với tham số tường minh:
#   bash load/scripts/run_invariants.sh --db-user dkhp_app --db-name dkhp
#
# Đọc cấu hình từ file .env ở thư mục gốc project (nếu tồn tại),
# sau đó có thể ghi đè bằng tham số dòng lệnh hoặc biến môi trường.
# =============================================================================

set -euo pipefail

# ---------------------------------------------------------------------------
# Màu sắc
# ---------------------------------------------------------------------------
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
CYAN='\033[0;36m'
BOLD='\033[1m'
NC='\033[0m'

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_ROOT="$(cd "${SCRIPT_DIR}/../.." && pwd)"
INVARIANTS_SQL="${PROJECT_ROOT}/docker/verify/invariants.sql"
ENV_FILE="${PROJECT_ROOT}/.env"

if [[ -f "$ENV_FILE" ]]; then
    set -o allexport
    source <(grep -E '^(POSTGRES_USER|POSTGRES_DB|POSTGRES_PASSWORD)=' "$ENV_FILE" 2>/dev/null || true)
    set +o allexport
fi

POSTGRES_USER="${POSTGRES_USER:-dkhp_app}"
POSTGRES_DB="${POSTGRES_DB:-dkhp}"

while [[ $# -gt 0 ]]; do
    case "$1" in
        --db-user)  POSTGRES_USER="$2"; shift 2 ;;
        --db-name)  POSTGRES_DB="$2";   shift 2 ;;
        --sql-file) INVARIANTS_SQL="$2"; shift 2 ;;
        --help|-h)
            echo "Cách dùng: $0 [--db-user USER] [--db-name DB] [--sql-file FILE]"
            exit 0 ;;
        *) echo "Tham số không hợp lệ: $1" >&2; exit 1 ;;
    esac
done

if [[ ! -f "$INVARIANTS_SQL" ]]; then
    echo -e "${RED}Lỗi: Không tìm thấy file invariants.sql tại:${NC}"
    echo "  $INVARIANTS_SQL"
    echo ""
    echo "Đảm bảo người phụ trách CSDL đã tạo file docker/verify/invariants.sql"
    echo "hoặc chỉ định đường dẫn khác: --sql-file /path/to/invariants.sql"
    exit 1
fi

echo -e "${CYAN}${BOLD}════════════════════════════════════════════════${NC}"
echo -e "${CYAN}${BOLD}  Chạy kiểm tra bất biến DB (invariants.sql)${NC}"
echo -e "${CYAN}${BOLD}════════════════════════════════════════════════${NC}"
echo ""
echo -e "  DB user : ${BOLD}${POSTGRES_USER}${NC}"
echo -e "  DB name : ${BOLD}${POSTGRES_DB}${NC}"
echo -e "  SQL file: ${BOLD}${INVARIANTS_SQL}${NC}"
echo ""

if ! docker compose -f "${PROJECT_ROOT}/docker-compose.yml" ps postgres 2>/dev/null | grep -q "running\|Up"; then
    echo -e "${YELLOW}Cảnh báo: Container postgres có thể chưa chạy.${NC}"
    echo "Chạy: docker compose up -d postgres"
    echo ""
fi

echo -e "${YELLOW}Đang chạy invariants.sql...${NC}"
echo ""

if docker compose \
    -f "${PROJECT_ROOT}/docker-compose.yml" \
    exec -T postgres \
    psql \
        -U "${POSTGRES_USER}" \
        -d "${POSTGRES_DB}" \
        -f /dev/stdin \
    < "${INVARIANTS_SQL}"; then
    echo ""
    echo -e "${GREEN}${BOLD}✔ Kiểm tra bất biến hoàn tất thành công${NC}"
else
    EXIT_CODE=$?
    echo ""
    echo -e "${RED}${BOLD}✘ Kiểm tra bất biến THẤT BẠI (exit code: $EXIT_CODE)${NC}"
    echo -e "${RED}Có ít nhất một bất biến bị vi phạm — kiểm tra output bên trên.${NC}"
    exit $EXIT_CODE
fi
