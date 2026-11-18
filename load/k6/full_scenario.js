import http from 'k6/http';
import { check, sleep } from 'k6';
import exec from 'k6/execution';
import { Trend, Counter } from 'k6/metrics';
import { BASE_URL, login, send } from './auth.js';

const enrollDuration = new Trend('enroll_duration_ms');
const cancelDuration = new Trend('cancel_duration_ms');
const enrollTimeTo201 = new Trend('enroll_time_to_201_ms');
const idempotentReplays = new Counter('idempotent_replays');
const replayMismatch = new Counter('replay_mismatch');

const TARGET_VUS = parseInt(__ENV.VUS || '1000', 10);
const OFFSET = parseInt(__ENV.ACCOUNT_OFFSET || '0', 10);
const LOGIN_WINDOW_S = parseInt(__ENV.LOGIN_WINDOW_S || '30', 10);
const SECTION_COUNT = parseInt(__ENV.SECTION_COUNT || '36', 10);

export const options = {
  scenarios: {
    dkhp_full: {
      executor: 'ramping-vus',
      startVUs: 0,
      stages: [
        { duration: `${LOGIN_WINDOW_S}s`, target: TARGET_VUS }, // login window
        { duration: '60s', target: TARGET_VUS },
        { duration: '30s', target: 0 },
      ],
      gracefulRampDown: '10s',
    },
  },
  thresholds: {
    'http_req_duration{phase:rush}': ['p(95)<3000', 'p(99)<5000'],
    'http_req_failed{phase:rush}': ['rate<0.01'],
    login_time_to_token_ms: ['p(95)<2000'],
    enroll_time_to_201_ms: ['p(95)<5000'],
    replay_mismatch: ['count==0'],
  },
};

let token = null; // module scope is per VU: one login per VU

export default function () {
  // One account per VU, so the seed needs students >= ACCOUNT_OFFSET + VUS.
  const code = 'SV' + String(OFFSET + __VU).padStart(5, '0');
  if (!token) {
    token = login(code);
    if (!token) {
      sleep(1);
      return;
    }
    // All VUs start the rush together when the login window closes.
    sleep(Math.max(0, exec.scenario.startTime + LOGIN_WINDOW_S * 1000 - Date.now()) / 1000);
  }

  const headers = { 'Content-Type': 'application/json', Authorization: `Bearer ${token}` };
  const params = { headers, tags: { phase: 'rush' } };

  // 1. Tra cứu đợt mở
  send(() => http.get(`${BASE_URL}/registration`, params), 'read');

  // 2. Tra cứu danh sách lớp; hết chỗ thì chọn ngẫu nhiên để không dồn mọi VU vào một dòng
  const sectionsRes = send(() => http.get(`${BASE_URL}/sections?hasSeats=true&limit=10`, params), 'read');
  const items = sectionsRes.status === 200 ? sectionsRes.json('items') : null;
  const sectionId = items && items.length > 0
    ? items[Math.floor(Math.random() * items.length)].id
    : 1 + Math.floor(Math.random() * SECTION_COUNT);

  // 3. Đăng ký học phần; lần thử lại sau 429 giữ nguyên Idempotency-Key
  const body = JSON.stringify({ sectionIds: [sectionId] });
  const enrollParams = {
    headers: Object.assign({ 'Idempotency-Key': `k6-${code}-${Date.now()}-${Math.floor(Math.random() * 100000)}` }, headers),
    tags: { phase: 'rush' },
  };
  const enroll = () => http.post(`${BASE_URL}/enrollments`, body, enrollParams);
  const enrollStart = Date.now();
  const enrollRes = send(enroll, 'write');
  enrollDuration.add(enrollRes.timings.duration);
  if (enrollRes.status === 201) enrollTimeTo201.add(Date.now() - enrollStart);

  // 4. Gửi lại 10 % số lượt đã có kết quả nghiệp vụ; 429 / 5xx ở lần hai chưa tới service nên bỏ qua
  if ([201, 409, 422].includes(enrollRes.status) && Math.random() < 0.1) {
    const again = send(enroll, 'write');
    if (again.status !== 429 && again.status < 500) {
      const replayed = again.headers['X-Idempotent-Replay'] === 'true';
      if (replayed) idempotentReplays.add(1);
      if (!replayed || again.status !== enrollRes.status || again.body !== enrollRes.body) replayMismatch.add(1);
    }
  }

  // 5. Hủy đăng ký nếu đăng ký thành công để hoàn trả chỗ
  const enrollmentId = enrollRes.status === 201 ? enrollRes.json('items.0.enrollmentId') : null;
  if (enrollmentId) {
    sleep(1);
    const cancelRes = send(() => http.del(`${BASE_URL}/enrollments/${enrollmentId}`, null, params), 'write');
    cancelDuration.add(cancelRes.timings.duration);
    check(cancelRes, { 'cancel status is 204': (r) => r.status === 204 });
  }

  sleep(1);
}
