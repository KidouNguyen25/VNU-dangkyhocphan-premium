import http from 'k6/http';
import { check, sleep } from 'k6';
import { Trend, Rate } from 'k6/metrics';
import { SharedArray } from 'k6/data';

const loginDuration = new Trend('login_duration_ms');
const errorRate = new Rate('error_rate');

const accounts = new SharedArray('accounts', function () {
  return JSON.parse(open('./accounts.json'));
});

const BASE_URL = __ENV.BASE_URL || 'http://localhost:8080/api/v1';
const PASSWORD = __ENV.STUDENT_PASSWORD || 'password123';
const TARGET_VUS = parseInt(__ENV.VUS || '100', 10);
const TOTAL_REQUESTS = parseInt(__ENV.TOTAL_REQUESTS || '1000', 10);

export const options = {
  scenarios: {
    login_ramp: {
      executor: 'ramping-vus',
      startVUs: 0,
      stages: [
        { duration: '15s', target: TARGET_VUS },
        { duration: '30s', target: TARGET_VUS },
        { duration: '15s', target: 0 },
      ],
      gracefulRampDown: '5s',
    },
  },
  thresholds: {
    login_duration_ms: ['p(95)<2000'],
    error_rate: ['rate<0.05'],
  },
};

export default function () {
  const account = accounts[__VU % accounts.length];
  const payload = JSON.stringify({
    code: account.code,
    password: PASSWORD,
  });

  const params = {
    headers: {
      'Content-Type': 'application/json',
    },
  };

  const start = Date.now();
  const res = http.post(`${BASE_URL}/auth/login`, payload, params);
  loginDuration.add(Date.now() - start);

  const success = check(res, {
    'status is 200': (r) => r.status === 200,
    'has accessToken': (r) => {
      try {
        const body = JSON.parse(r.body);
        return body && body.accessToken !== undefined;
      } catch (e) {
        return false;
      }
    },
  });

  errorRate.add(!success);
  sleep(1);
}
