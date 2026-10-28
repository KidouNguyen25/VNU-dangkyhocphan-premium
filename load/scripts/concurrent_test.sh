#!/usr/bin/env bash
# concurrent_test.sh — Kiểm thử đồng thời và idempotency cho API đăng ký học phần
# Sử dụng:
#   chmod +x concurrent_test.sh
#   ./concurrent_test.sh \
#       --base-url  http://localhost:8080/api/v1 \
#       --admin-token <JWT_admin> \
#       --student-password <mat_khau_sinh_vien>
# Yêu cầu: curl, bash >= 4, GNU parallel (tuỳ chọn — fallback về background jobs)

set -euo pipefail

RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
CYAN='\033[0;36m'
BOLD='\033[1m'
NC='\033[0m' # No Color

BASE_URL="${BASE_URL:-http://localhost:8080/api/v1}"
ADMIN_TOKEN="${ADMIN_TOKEN:-}"
STUDENT_PASSWORD="${STUDENT_PASSWORD:-}"

while [[ $# -gt 0 ]]; do
    case "$1" in
        --base-url)        BASE_URL="$2";        shift 2 ;;
        --admin-token)     ADMIN_TOKEN="$2";     shift 2 ;;
        --student-password) STUDENT_PASSWORD="$2"; shift 2 ;;
        *) echo "Tham số không hợp lệ: $1" >&2; exit 1 ;;
    esac
done

if [[ -z "$STUDENT_PASSWORD" ]]; then
    echo -e "${RED}Lỗi: Thiếu --student-password${NC}" >&2
    exit 1
fi

scenario_header() {
    echo ""
    echo -e "${CYAN}${BOLD}━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━${NC}"
    echo -e "${CYAN}${BOLD}  Kịch bản $1: $2${NC}"
    echo -e "${CYAN}${BOLD}━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━${NC}"
}

pass() { echo -e "  ${GREEN}✔ PASS${NC}: $1"; }
fail() { echo -e "  ${RED}✘ FAIL${NC}: $1"; OVERALL_FAIL=1; }
info() { echo -e "  ${YELLOW}ℹ${NC} $1"; }

remind_invariants() {
    echo ""
    echo -e "  ${YELLOW}▶ Chạy invariants.sql để kiểm tra tính nhất quán:${NC}"
    echo -e "    ${BOLD}bash load/scripts/run_invariants.sh${NC}"
    echo ""
}

login() {
    local code="$1"
    local password="$2"
    local resp
    resp=$(curl -s -X POST "${BASE_URL}/auth/login" \
        -H "Content-Type: application/json" \
        -d "{\"code\":\"${code}\",\"password\":\"${password}\"}")
    echo "$resp" | grep -o '"token":"[^"]*"' | head -1 | cut -d'"' -f4
}

HAS_PARALLEL=0
if command -v parallel &>/dev/null; then
    HAS_PARALLEL=1
fi

OVERALL_FAIL=0

TMP_DIR=$(mktemp -d)
trap 'rm -rf "$TMP_DIR"' EXIT

scenario_header "a" "50 yêu cầu đồng thời vào lớp chỉ còn 1 chỗ (section_id=11, capacity=2)"

info "Bước 1: Đăng nhập lấy JWT cho 50 sinh viên (SV00001 → SV00050)..."

TOKEN_DIR="${TMP_DIR}/tokens_a"
mkdir -p "$TOKEN_DIR"

login_batch() {
    local idx="$1"
    local code
    code=$(printf "SV%05d" "$idx")
    local tok
    tok=$(login "$code" "$STUDENT_PASSWORD")
    echo "$tok" > "${TOKEN_DIR}/${idx}.tok"
}
export -f login_batch login BASE_URL STUDENT_PASSWORD TOKEN_DIR

if [[ $HAS_PARALLEL -eq 1 ]]; then
    seq 1 50 | parallel -j10 login_batch
else
    for i in $(seq 1 50); do login_batch "$i" & done
    wait
fi

VALID_TOKENS=0
for i in $(seq 1 50); do
    tok=$(cat "${TOKEN_DIR}/${i}.tok" 2>/dev/null || true)
    [[ -n "$tok" ]] && VALID_TOKENS=$((VALID_TOKENS + 1))
done
info "Lấy được $VALID_TOKENS/50 JWT hợp lệ"

info "Bước 2: Gửi 50 POST /enrollments đồng thời (sectionIds=[11])..."
RESP_DIR_A="${TMP_DIR}/resp_a"
mkdir -p "$RESP_DIR_A"

send_enrollment_a() {
    local idx="$1"
    local tok
    tok=$(cat "${TOKEN_DIR}/${idx}.tok" 2>/dev/null || true)
    [[ -z "$tok" ]] && echo "NO_TOKEN" > "${RESP_DIR_A}/${idx}.status" && return
    local idem_key="idem-a-${idx}-$(date +%s%N)"
    local http_code
    http_code=$(curl -s -o "${RESP_DIR_A}/${idx}.body" -w "%{http_code}" \
        -X POST "${BASE_URL}/enrollments" \
        -H "Authorization: Bearer ${tok}" \
        -H "Content-Type: application/json" \
        -H "Idempotency-Key: ${idem_key}" \
        -d "{\"sectionIds\":[11]}")
    echo "$http_code" > "${RESP_DIR_A}/${idx}.status"
}
export -f send_enrollment_a BASE_URL TOKEN_DIR RESP_DIR_A

if [[ $HAS_PARALLEL -eq 1 ]]; then
    seq 1 50 | parallel -j50 send_enrollment_a
else
    for i in $(seq 1 50); do send_enrollment_a "$i" & done
    wait
fi

COUNT_201=0; COUNT_409=0; COUNT_OTHER=0
for i in $(seq 1 50); do
    sc=$(cat "${RESP_DIR_A}/${i}.status" 2>/dev/null || echo "ERR")
    case "$sc" in
        201) COUNT_201=$((COUNT_201 + 1)) ;;
        409) COUNT_409=$((COUNT_409 + 1)) ;;
        *)   COUNT_OTHER=$((COUNT_OTHER + 1)); info "  SV$(printf '%05d' $i) nhận status=$sc" ;;
    esac
done

info "Kết quả: 201=$COUNT_201, 409=$COUNT_409, khác=$COUNT_OTHER"

if [[ $COUNT_201 -eq 1 ]]; then
    pass "Đúng 1 yêu cầu thành công (201) — section_id=11 chỉ còn 1 chỗ"
else
    fail "Kỳ vọng đúng 1 response 201, nhận được $COUNT_201"
fi

if [[ $COUNT_409 -eq 49 ]]; then
    pass "49 yêu cầu còn lại nhận 409 (lớp đầy)"
else
    fail "Kỳ vọng 49 response 409, nhận được $COUNT_409"
fi

remind_invariants

scenario_header "b" "Cùng Idempotency-Key gửi 20 lần — phải trả cùng response"

# Đăng nhập 1 tài khoản cố định cho kịch bản này
FIXED_CODE="SV00100"
info "Đăng nhập tài khoản $FIXED_CODE..."
FIXED_TOKEN=$(login "$FIXED_CODE" "$STUDENT_PASSWORD")

if [[ -z "$FIXED_TOKEN" ]]; then
    fail "Không đăng nhập được $FIXED_CODE — bỏ qua kịch bản (b)"
else
    FIXED_KEY="idem-b-fixed-key-$(date +%s)"
    info "Idempotency-Key: $FIXED_KEY"
    info "Gửi 20 POST /enrollments đồng thời với cùng key (sectionIds=[3])..."

    RESP_DIR_B="${TMP_DIR}/resp_b"
    mkdir -p "$RESP_DIR_B"

    send_idempotent_b() {
        local idx="$1"
        local http_code
        http_code=$(curl -s -o "${RESP_DIR_B}/${idx}.body" -w "%{http_code}" \
            -X POST "${BASE_URL}/enrollments" \
            -H "Authorization: Bearer ${FIXED_TOKEN}" \
            -H "Content-Type: application/json" \
            -H "Idempotency-Key: ${FIXED_KEY}" \
            -d '{"sectionIds":[3]}')
        echo "$http_code" > "${RESP_DIR_B}/${idx}.status"
    }
    export -f send_idempotent_b BASE_URL FIXED_TOKEN FIXED_KEY RESP_DIR_B

    if [[ $HAS_PARALLEL -eq 1 ]]; then
        seq 1 20 | parallel -j20 send_idempotent_b
    else
        for i in $(seq 1 20); do send_idempotent_b "$i" & done
        wait
    fi

    FIRST_STATUS=$(cat "${RESP_DIR_B}/1.status" 2>/dev/null || echo "ERR")
    FIRST_BODY=$(cat "${RESP_DIR_B}/1.body" 2>/dev/null || echo "")
    ALL_SAME=1

    for i in $(seq 2 20); do
        sc=$(cat "${RESP_DIR_B}/${i}.status" 2>/dev/null || echo "ERR")
        body=$(cat "${RESP_DIR_B}/${i}.body" 2>/dev/null || echo "")
        if [[ "$sc" != "$FIRST_STATUS" || "$body" != "$FIRST_BODY" ]]; then
            ALL_SAME=0
            info "  Request $i khác: status=$sc (kỳ vọng $FIRST_STATUS)"
        fi
    done

    info "Status của lần 1: $FIRST_STATUS"

    if [[ $ALL_SAME -eq 1 ]]; then
        pass "Tất cả 20 response có cùng status code ($FIRST_STATUS) và body"
    else
        fail "Một số response khác nhau — idempotency bị vi phạm"
    fi

    if [[ "$FIRST_STATUS" == "201" ]]; then
        ENROLLMENT_ID=$(echo "$FIRST_BODY" | grep -o '"id":[0-9]*' | head -1 | cut -d: -f2)
        info "Enrollment ID được tạo: $ENROLLMENT_ID"
        pass "Kịch bản idempotency: 20 lần gửi cùng key → chỉ 1 enrollment được tạo"
    fi
fi

remind_invariants

scenario_header "c" "Cùng Idempotency-Key nhưng khác sectionIds → 409 IDEMPOTENCY_KEY_REUSED"

FIXED_CODE_C="SV00200"
info "Đăng nhập tài khoản $FIXED_CODE_C..."
TOKEN_C=$(login "$FIXED_CODE_C" "$STUDENT_PASSWORD")

if [[ -z "$TOKEN_C" ]]; then
    fail "Không đăng nhập được $FIXED_CODE_C — bỏ qua kịch bản (c)"
else
    KEY_C="idem-c-reuse-test-$(date +%s)"
    info "Idempotency-Key: $KEY_C"

    info "Lần 1: POST /enrollments sectionIds=[3]..."
    RESP1_C=$(curl -s -o /dev/null -w "%{http_code}" \
        -X POST "${BASE_URL}/enrollments" \
        -H "Authorization: Bearer ${TOKEN_C}" \
        -H "Content-Type: application/json" \
        -H "Idempotency-Key: ${KEY_C}" \
        -d '{"sectionIds":[3]}')
    info "Lần 1 → HTTP $RESP1_C"

    info "Lần 2: POST /enrollments sectionIds=[4] (cùng key, khác body)..."
    BODY2_C="${TMP_DIR}/resp_c_2.body"
    RESP2_C=$(curl -s -o "$BODY2_C" -w "%{http_code}" \
        -X POST "${BASE_URL}/enrollments" \
        -H "Authorization: Bearer ${TOKEN_C}" \
        -H "Content-Type: application/json" \
        -H "Idempotency-Key: ${KEY_C}" \
        -d '{"sectionIds":[4]}')
    info "Lần 2 → HTTP $RESP2_C"

    if [[ "$RESP2_C" == "409" ]]; then
        ERROR_CODE=$(cat "$BODY2_C" | grep -o '"code":"[^"]*"' | head -1 | cut -d'"' -f4)
        if [[ "$ERROR_CODE" == "IDEMPOTENCY_KEY_REUSED" ]]; then
            pass "Lần 2 nhận 409 IDEMPOTENCY_KEY_REUSED đúng như kỳ vọng"
        else
            fail "Nhận 409 nhưng error code là '$ERROR_CODE' (kỳ vọng IDEMPOTENCY_KEY_REUSED)"
        fi
    else
        fail "Kỳ vọng HTTP 409 cho lần 2, nhận được $RESP2_C"
    fi
fi

remind_invariants

scenario_header "d" "Hủy đăng ký hai lần — lần hai phải trả 404 ENROLLMENT_NOT_FOUND"

FIXED_CODE_D="SV00300"
info "Đăng nhập tài khoản $FIXED_CODE_D..."
TOKEN_D=$(login "$FIXED_CODE_D" "$STUDENT_PASSWORD")

if [[ -z "$TOKEN_D" ]]; then
    fail "Không đăng nhập được $FIXED_CODE_D — bỏ qua kịch bản (d)"
else
    KEY_D="idem-d-enroll-$(date +%s)"
    info "Đăng ký lớp section_id=5..."
    ENROLL_BODY="${TMP_DIR}/resp_d_enroll.body"
    ENROLL_STATUS=$(curl -s -o "$ENROLL_BODY" -w "%{http_code}" \
        -X POST "${BASE_URL}/enrollments" \
        -H "Authorization: Bearer ${TOKEN_D}" \
        -H "Content-Type: application/json" \
        -H "Idempotency-Key: ${KEY_D}" \
        -d '{"sectionIds":[5]}')
    info "Đăng ký → HTTP $ENROLL_STATUS"

    if [[ "$ENROLL_STATUS" != "201" ]]; then
        fail "Không thể đăng ký lớp (HTTP $ENROLL_STATUS) — bỏ qua kịch bản (d)"
    else
        ENROLL_ID=$(cat "$ENROLL_BODY" | grep -o '"id":[0-9]*' | head -1 | cut -d: -f2)
        if [[ -z "$ENROLL_ID" ]]; then
            ENROLL_ID=$(cat "$ENROLL_BODY" | grep -o '"id":[0-9]*' | sed -n '1p' | cut -d: -f2)
        fi
        info "Enrollment ID: $ENROLL_ID"

        info "Lấy enrolled_count của section_id=5 trước khi hủy..."

        info "Hủy lần 1: DELETE /enrollments/${ENROLL_ID}..."
        DEL1_STATUS=$(curl -s -o /dev/null -w "%{http_code}" \
            -X DELETE "${BASE_URL}/enrollments/${ENROLL_ID}" \
            -H "Authorization: Bearer ${TOKEN_D}")
        info "Hủy lần 1 → HTTP $DEL1_STATUS"

        if [[ "$DEL1_STATUS" == "204" ]]; then
            pass "Hủy lần 1 nhận 204 No Content"
        else
            fail "Kỳ vọng 204 cho hủy lần 1, nhận $DEL1_STATUS"
        fi

        info "Hủy lần 2: DELETE /enrollments/${ENROLL_ID} (đã bị hủy)..."
        DEL2_BODY="${TMP_DIR}/resp_d_del2.body"
        DEL2_STATUS=$(curl -s -o "$DEL2_BODY" -w "%{http_code}" \
            -X DELETE "${BASE_URL}/enrollments/${ENROLL_ID}" \
            -H "Authorization: Bearer ${TOKEN_D}")
        info "Hủy lần 2 → HTTP $DEL2_STATUS"

        if [[ "$DEL2_STATUS" == "404" ]]; then
            ERROR_CODE_D=$(cat "$DEL2_BODY" | grep -o '"code":"[^"]*"' | head -1 | cut -d'"' -f4)
            if [[ "$ERROR_CODE_D" == "ENROLLMENT_NOT_FOUND" ]]; then
                pass "Hủy lần 2 nhận 404 ENROLLMENT_NOT_FOUND đúng như kỳ vọng"
            else
                fail "Nhận 404 nhưng error code là '$ERROR_CODE_D' (kỳ vọng ENROLLMENT_NOT_FOUND)"
            fi
        else
            fail "Kỳ vọng 404 cho hủy lần 2, nhận $DEL2_STATUS"
        fi

        info "Kiểm tra enrolled_count: chạy invariants.sql để xác nhận enrolled_count giảm đúng 1"
    fi
fi

remind_invariants
Ư
echo ""
echo -e "${CYAN}${BOLD}━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━${NC}"
echo -e "${CYAN}${BOLD}  KẾT QUẢ TỔNG HỢP${NC}"
echo -e "${CYAN}${BOLD}━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━${NC}"
if [[ $OVERALL_FAIL -eq 0 ]]; then
    echo -e "  ${GREEN}${BOLD}✔ TẤT CẢ KỊCH BẢN PASS${NC}"
else
    echo -e "  ${RED}${BOLD}✘ CÓ KỊCH BẢN THẤT BẠI — Xem chi tiết bên trên${NC}"
fi
echo ""
echo -e "  ${YELLOW}Lưu ý cuối:${NC} Chạy ${BOLD}bash load/scripts/run_invariants.sh${NC}"
echo -e "  để xác nhận toàn bộ bất biến DB sau khi kết thúc kiểm thử."
echo ""

exit $OVERALL_FAIL
