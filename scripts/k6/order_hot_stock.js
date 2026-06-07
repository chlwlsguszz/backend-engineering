import http from "k6/http";
import { check, sleep } from "k6";
import { Counter } from "k6/metrics";

/**
 * Order hot-stock load test — POST /api/orders against ONE hot productId.
 * Load profile: ramping-arrival-rate 0 → TARGET_RPS, hold, ramp down.
 *
 * 기본 (0→10000 RPS in 20s, hold 20s, ramp-down 10s):
 *   k6 run scripts/k6/order_hot_stock.js
 *
 * 커스텀:
 *   k6 run -e TARGET_RPS=5000 -e RAMP_UP=20s -e HOLD=20s scripts/k6/order_hot_stock.js
 *   k6 run -e PRODUCT_ID=10000501 -e MEMBER_ID_MIN=1 -e MEMBER_ID_MAX=50 -e QTY=1 scripts/k6/order_hot_stock.js
 *
 * 참고:
 * - 재고 seed: scripts/sql/seed_order_products_1000.sql
 * - dropped_iterations ↑ → PREALLOCATED_VUS / MAX_VUS 올리기
 */

const BASE_URL = __ENV.BASE_URL || "http://localhost:8080";
const PRODUCT_ID = Number(__ENV.PRODUCT_ID || 10000501);
const MEMBER_ID_MIN = Number(__ENV.MEMBER_ID_MIN || 1);
const MEMBER_ID_MAX = Number(__ENV.MEMBER_ID_MAX || 50);
const QTY = Number(__ENV.QTY || 1);

const TARGET_RPS = Number(__ENV.TARGET_RPS || 10000);
const RAMP_UP = __ENV.RAMP_UP || __ENV.WARM_UP || "20s";
const HOLD = __ENV.HOLD || __ENV.DURATION || "20s";
const RAMP_DOWN = __ENV.RAMP_DOWN || "10s";
const PREALLOCATED_VUS = Number(__ENV.PREALLOCATED_VUS || 800);
const MAX_VUS = Number(__ENV.MAX_VUS || 3000);

const THINK_MIN_MS = Number(__ENV.THINK_MIN_MS ?? 0);
const THINK_MAX_MS = Number(__ENV.THINK_MAX_MS ?? 10);

const created = new Counter("orders_created");
const insufficient = new Counter("orders_insufficient_stock");
const unexpected = new Counter("orders_unexpected");

const ENFORCE_THRESHOLDS = __ENV.ENFORCE_THRESHOLDS === "1";

export const options = {
  scenarios: {
    hot: {
      executor: "ramping-arrival-rate",
      startRate: 0,
      timeUnit: "1s",
      preAllocatedVUs: PREALLOCATED_VUS,
      maxVUs: MAX_VUS,
      stages: [
        { duration: RAMP_UP, target: TARGET_RPS },
        { duration: HOLD, target: TARGET_RPS },
        { duration: RAMP_DOWN, target: 0 },
      ],
    },
  },
  thresholds: ENFORCE_THRESHOLDS ? { http_req_failed: ["rate<0.05"] } : {},
};

export function setup() {
  console.log(
    `[k6 order] BASE_URL=${BASE_URL} productId=${PRODUCT_ID} qty=${QTY} | members ${MEMBER_ID_MIN}..${MEMBER_ID_MAX}`,
  );
  console.log(
    `[k6 order] TARGET_RPS=${TARGET_RPS} | ramp ${RAMP_UP}, hold ${HOLD}, ramp-down ${RAMP_DOWN} | VUs ${PREALLOCATED_VUS}/${MAX_VUS} | think ${THINK_MIN_MS}~${THINK_MAX_MS}ms`,
  );
}

export default function () {
  const memberRange = Math.max(1, MEMBER_ID_MAX - MEMBER_ID_MIN + 1);
  const memberId = MEMBER_ID_MIN + ((__ITER + (__VU - 1)) % memberRange);

  const url = `${BASE_URL}/api/orders`;
  const payload = JSON.stringify({
    memberId,
    productId: PRODUCT_ID,
    quantity: QTY,
    idempotencyKey: `${memberId}-${PRODUCT_ID}-${__VU}-${__ITER}`,
  });

  const res = http.post(url, payload, {
    headers: { "Content-Type": "application/json" },
    tags: { name: "POST /api/orders" },
  });

  const ok = check(res, {
    "status is 200 or 409": (r) => r.status === 200 || r.status === 409,
  });

  if (!ok) {
    unexpected.add(1);
  } else if (res.status === 200) {
    created.add(1);
  } else if (res.status === 409) {
    insufficient.add(1);
  }

  if (THINK_MAX_MS > 0) {
    const ms =
      THINK_MIN_MS +
      Math.floor(Math.random() * (Math.max(0, THINK_MAX_MS - THINK_MIN_MS) + 1));
    sleep(ms / 1000);
  }
}
