import http from 'k6/http';
import { sleep } from 'k6';
import { Counter, Trend } from 'k6/metrics';

export const BASE_URL = __ENV.BASE_URL || 'http://localhost:8080/api/v1';
const PASSWORD = __ENV.STUDENT_PASSWORD || 'password123';

const rejected = new Counter('rejected');
const loginTimeToToken = new Trend('login_time_to_token_ms');
const loginDuration = new Trend('login_duration_ms'); // still read by the current notebook sweep

// 409/422 are business answers and 429 is shedding (counted in `rejected`), so none of them is a failure.
http.setResponseCallback(http.expectedStatuses({ min: 200, max: 399 }, 409, 422, 429));

// Calls fn; on 429 waits Retry-After plus up to 1 s of jitter and tries again, at most 5 times.
export function send(fn, group) {
  for (let retry = 0; ; retry++) {
    const res = fn();
    if (res.status !== 429) return res;
    rejected.add(1, { group });
    if (retry === 5) return res;
    sleep((parseInt(res.headers['Retry-After'], 10) || 1) + Math.random());
  }
}

// Returns the access token, or null when the login did not end in 200.
export function login(code) {
  const start = Date.now();
  const res = send(() => http.post(`${BASE_URL}/auth/login`, JSON.stringify({ code, password: PASSWORD }), {
    headers: { 'Content-Type': 'application/json' },
    tags: { phase: 'login' },
  }), 'login');
  loginDuration.add(res.timings.duration);
  if (res.status !== 200) return null;
  loginTimeToToken.add(Date.now() - start);
  return res.json('accessToken') || null;
}
