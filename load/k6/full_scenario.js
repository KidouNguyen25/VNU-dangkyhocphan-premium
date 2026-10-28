import http from 'k6/http';
import { check, sleep } from 'k6';
import { Trend, Rate, Counter } from 'k6/metrics';
import { SharedArray } from 'k6/data';

const loginDuration = new Trend('login_duration_ms');
const enrollDuration = new Trend('enroll_duration_ms');
const cancelDuration = new Trend('cancel_duration_ms');
const errorRate = new Rate('error_rate');
const idempotentReplays = new Counter('idempotent_replays');

const accounts = new SharedArray('accounts', function () {
  return JSON.parse(open('./accounts.json'));
});

const BASE_URL = __ENV.BASE_URL || 'http://localhost:8080/api/v1';
const PASSWORD = __ENV.STUDENT_PASSWORD || 'password123';
const TARGET_VUS = parseInt(__ENV.VUS || '1000', 10);
const OFFSET = parseInt(__ENV.ACCOUNT_OFFSET || '0', 10);
const LIMIT = parseInt(__ENV.ACCOUNT_LIMIT || '1000', 10);

export const options = {
  scenarios: {
    dkhp_full: {
      executor: 'ramping-vus',
      startVUs: 0,
      stages: [
        { duration: '30s', target: TARGET_VUS },
        { duration: '60s', target: TARGET_VUS },
        { duration: '30s', target: 0 },
      ],
      gracefulRampDown: '10s',
    },
  },
  thresholds: {
    http_req_duration: ['p(95)<3000', 'p(99)<5000'],
    error_rate: ['rate<0.10'],
  },
};

export default function () {
  const accountIndex = (OFFSET + (__VU - 1)) % LIMIT;
  const account = accounts[accountIndex] || accounts[0];

  // 1. Đăng nhập
  const loginPayload = JSON.stringify({
    code: account.code,
    password: PASSWORD,
  });

  const loginStart = Date.now();
  const loginRes = http.post(`${BASE_URL}/auth/login`, loginPayload, {
    headers: { 'Content-Type': 'application/json' },
  });
  loginDuration.add(Date.now() - loginStart);

  if (!check(loginRes, { 'login status is 200': (r) => r.status === 200 })) {
    errorRate.add(1);
    sleep(1);
    return;
  }

  let token = '';
  try {
    token = JSON.parse(loginRes.body).accessToken;
  } catch (e) {
    errorRate.add(1);
    return;
  }

  const authHeaders = {
    headers: {
      'Content-Type': 'application/json',
      Authorization: `Bearer ${token}`,
    },
  };

  // 2. Tra cứu đợt mở
  http.get(`${BASE_URL}/registration`, authHeaders);

  // 3. Tra cứu danh sách lớp
  const sectionsRes = http.get(`${BASE_URL}/sections?hasSeats=true&limit=10`, authHeaders);
  let sectionId = 1;
  try {
    const data = JSON.parse(sectionsRes.body);
    if (data.items && data.items.length > 0) {
      const idx = Math.floor(Math.random() * data.items.length);
      sectionId = data.items[idx].id;
    }
  } catch (e) {
    // fallback id
  }

  // 4. Đăng ký học phần (Idempotency-Key)
  const ikey = `k6-${account.code}-${Date.now()}-${Math.floor(Math.random() * 100000)}`;
  const enrollPayload = JSON.stringify({ sectionIds: [sectionId] });
  const enrollParams = {
    headers: {
      'Content-Type': 'application/json',
      Authorization: `Bearer ${token}`,
      'Idempotency-Key': ikey,
    },
  };

  const enrollStart = Date.now();
  const enrollRes = http.post(`${BASE_URL}/enrollments`, enrollPayload, enrollParams);
  enrollDuration.add(Date.now() - enrollStart);

  if (enrollRes.headers['X-Idempotent-Replay'] === 'true') {
    idempotentReplays.add(1);
  }

  let enrollmentId = null;
  if (enrollRes.status === 201) {
    try {
      const enrollData = JSON.parse(enrollRes.body);
      if (enrollData.items && enrollData.items.length > 0) {
        enrollmentId = enrollData.items[0].enrollmentId;
      }
    } catch (e) {}
  } else if (enrollRes.status !== 409 && enrollRes.status !== 422) {
    // 409 SECTION_FULL hoặc 422 xung đột là kết quả nghiệp vụ hợp lệ khi tranh chấp
    errorRate.add(1);
  }

  // 5. Hủy đăng ký nếu đăng ký thành công để hoàn trả chỗ
  if (enrollmentId) {
    sleep(1);
    const cancelStart = Date.now();
    const cancelRes = http.del(`${BASE_URL}/enrollments/${enrollmentId}`, null, authHeaders);
    cancelDuration.add(Date.now() - cancelStart);
    check(cancelRes, { 'cancel status is 204': (r) => r.status === 204 });
  }

  sleep(1);
}

