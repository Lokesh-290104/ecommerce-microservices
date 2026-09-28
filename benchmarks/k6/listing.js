// Load test for the two listing endpoints (step 8, design D21/D26).
//   ENDPOINT=products : GET /api/products?categoryId=1..50&page=0..9&size=20 (public)
//   ENDPOINT=orders   : GET /api/orders?page=0..2&size=20 as users 1..1000 (own orders)
// Run through scripts/bench.sh, which uses the grafana/k6 image on the compose network.
import http from 'k6/http';
import { check } from 'k6';
import crypto from 'k6/crypto';
import encoding from 'k6/encoding';

const ENDPOINT = __ENV.ENDPOINT;
const PRODUCTS = __ENV.PRODUCTS_URL || 'http://product-service:8082';
const ORDERS = __ENV.ORDERS_URL || 'http://order-service:8083';
// The compose stack's local dev key: tokens for the seeded users are signed here, exactly as
// user-service would sign them after a login (logging in 1000 users would only measure BCrypt).
const SECRET = __ENV.JWT_SECRET || 'local-dev-jwt-secret-change-me-0123456789';

export const options = {
  scenarios: {
    load: { executor: 'constant-vus', vus: Number(__ENV.VUS || 20), duration: __ENV.DURATION || '2m' },
  },
  summaryTrendStats: ['avg', 'p(50)', 'p(95)', 'p(99)', 'max'],
  thresholds: { http_req_failed: ['rate<0.01'] },
};

function b64url(obj) {
  return encoding.b64encode(JSON.stringify(obj), 'rawurl');
}

function token(userId) {
  const now = Math.floor(Date.now() / 1000);
  const header = b64url({ alg: 'HS256' });
  const payload = b64url({ iss: 'shop-user-service', sub: String(userId), scope: 'user', iat: now, exp: now + 3600 });
  const signature = crypto.hmac('sha256', SECRET, `${header}.${payload}`, 'base64rawurl');
  return `${header}.${payload}.${signature}`;
}

const TOKENS = ENDPOINT === 'orders' ? Array.from({ length: 1000 }, (_, i) => token(i + 1)) : [];

function pick(max) {
  return Math.floor(Math.random() * max);
}

export default function () {
  let res;
  if (ENDPOINT === 'products') {
    res = http.get(`${PRODUCTS}/api/products?categoryId=${1 + pick(50)}&page=${pick(10)}&size=20`,
      { tags: { name: 'products' } });
  } else if (ENDPOINT === 'orders') {
    res = http.get(`${ORDERS}/api/orders?page=${pick(3)}&size=20`,
      { headers: { Authorization: `Bearer ${TOKENS[pick(1000)]}` }, tags: { name: 'orders' } });
  } else {
    throw new Error('Set ENDPOINT=products or ENDPOINT=orders');
  }
  check(res, { 'status 200': (r) => r.status === 200, 'not empty': (r) => r.json('content').length > 0 });
}
