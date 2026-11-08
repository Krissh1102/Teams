// k6 load test: 100 concurrent users hammering the live board. Target: p95 < 200 ms, <1% errors.
//   1. seed:  psql "$DATABASE_URL" -f loadtest/seed.sql
//   2. run:   k6 run -e BASE_URL=http://localhost:8080 loadtest/board.js
// Writes loadtest/report.json (machine readable) and prints a summary.
import http from 'k6/http'
import { check } from 'k6'
import { textSummary } from 'https://jslib.k6.io/k6-summary/0.0.4/index.js'

const BASE = __ENV.BASE_URL || 'http://localhost:8080'
const HACKATHON = '00000000-0000-0000-0000-0000000000a1'

export const options = {
  scenarios: { board: { executor: 'constant-vus', vus: 100, duration: __ENV.DURATION || '60s' } },
  thresholds: {
    http_req_duration: ['p(95)<200'],
    http_req_failed: ['rate<0.01'],
    checks: ['rate>0.99'],
  },
}

export function setup() {
  const r = http.post(`${BASE}/api/auth/login`,
    JSON.stringify({ email: 'user1@seed.test', password: 'password123' }),
    { headers: { 'Content-Type': 'application/json' } })
  if (r.status !== 200) throw new Error(`login failed: ${r.status} ${r.body}`)
  return { token: r.json('token') }
}

export default function (data) {
  const page = Math.floor(Math.random() * 15)
  const r = http.get(`${BASE}/api/hackathons/${HACKATHON}/board?page=${page}&size=20`,
    { headers: { Authorization: `Bearer ${data.token}` }, tags: { name: 'GET board' } })
  check(r, { 'status 200': (x) => x.status === 200, 'has items': (x) => x.json('items').length > 0 })
}

export function handleSummary(data) {
  return {
    'loadtest/report.json': JSON.stringify(data, null, 2),
    stdout: textSummary(data, { indent: ' ', enableColors: false }),
  }
}
