import { check, sleep } from 'k6';
import { SharedArray } from 'k6/data';
import { login } from './auth.js';

const accounts = new SharedArray('accounts', function () {
  return JSON.parse(open('./accounts.json'));
});

const TARGET_VUS = parseInt(__ENV.VUS || '100', 10);

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
    login_time_to_token_ms: ['p(95)<2000'],
    http_req_failed: ['rate<0.05'],
  },
};

export default function () {
  check(login(accounts[__VU % accounts.length].code), { 'has accessToken': (t) => t !== null });
  sleep(1);
}
