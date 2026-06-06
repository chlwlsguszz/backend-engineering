import http from "k6/http";
import { check, sleep } from "k6";
import { Counter } from "k6/metrics";

/**
 * Order spread load test — POST /api/orders across fixture products (even distribution).
 *
 * Targets product IDs PRODUCT_ID_MIN .. PRODUCT_ID_MAX (default: seed block 10000501–10001500).
 * Each iteration picks the next product in round-robin so load is spread evenly.
 * Load profile: ramping-arrival-rate 0 → TARGET_RPS, hold, ramp down.
 *
 * 기본 (0→10000 RPS in 20s, hold 20s, ramp-down 10s):
 *   k6 run scripts/k6/order_spread_products.js
 *
 * Seed first:
 *   .\scripts\sql\run-seed-order-products.ps1
 *
 * 커스텀:
 *   k6 run -e TARGET_RPS=10000 -e RAMP_UP=20s -e HOLD=20s scripts/k6/order_spread_products.js
 *   k6 run -e PRODUCT_ID_MIN=10000501 -e PRODUCT_ID_MAX=10001500 scripts/k6/order_spread_products.js
 */

const BASE_URL = __ENV.BASE_URL || "http://localhost:8080";
const PRODUCT_ID_MIN = Number(__ENV.PRODUCT_ID_MIN || 10000501);
const PRODUCT_ID_MAX = Number(__ENV.PRODUCT_ID_MAX || 10001500);
const MEMBER_ID_MIN = Number(__ENV.MEMBER_ID_MIN || 1);
const MEMBER_ID_MAX = Number(__ENV.MEMBER_ID_MAX || 50);
const QTY = Number(__ENV.QTY || 1);

const TARGET_RPS = Number(__ENV.TARGET_RPS || 10000);
const RAMP_UP = __ENV.RAMP_UP || __ENV.WARM_UP || "20s";
const HOLD = __ENV.HOLD || __ENV.DURATION || "20s";
const RAMP_DOWN = __ENV.RAMP_DOWN || "10s";
const PREALLOCATED_VUS = Number(__ENV.PREALLOCATED_VUS || 800);
const MAX_VUS = Number(__ENV.MAX_VUS || 5000);

const THINK_MIN_MS = Number(__ENV.THINK_MIN_MS ?? 0);
const THINK_MAX_MS = Number(__ENV.THINK_MAX_MS ?? 10);

const PRODUCT_COUNT = Math.max(1, PRODUCT_ID_MAX - PRODUCT_ID_MIN + 1);

const created = new Counter("orders_created");
const insufficient = new Counter("orders_insufficient_stock");
const unexpected = new Counter("orders_unexpected");

const ENFORCE_THRESHOLDS = __ENV.ENFORCE_THRESHOLDS === "1";

export const options = {
  scenarios: {
    spread: {
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
  if (PRODUCT_ID_MIN > PRODUCT_ID_MAX) {
    throw new Error(`PRODUCT_ID_MIN (${PRODUCT_ID_MIN}) must be <= PRODUCT_ID_MAX (${PRODUCT_ID_MAX})`);
  }

  console.log(
    `[k6 order spread] BASE_URL=${BASE_URL} products ${PRODUCT_ID_MIN}..${PRODUCT_ID_MAX} (${PRODUCT_COUNT} SKUs) qty=${QTY}`,
  );
  console.log(
    `[k6 order spread] TARGET_RPS=${TARGET_RPS} | ramp ${RAMP_UP}, hold ${HOLD}, ramp-down ${RAMP_DOWN} | VUs ${PREALLOCATED_VUS}/${MAX_VUS} | members ${MEMBER_ID_MIN}..${MEMBER_ID_MAX}`,
  );
}

function pickProductId() {
  const offset = (__ITER + (__VU - 1)) % PRODUCT_COUNT;
  return PRODUCT_ID_MIN + offset;
}

function pickMemberId() {
  const memberRange = Math.max(1, MEMBER_ID_MAX - MEMBER_ID_MIN + 1);
  return MEMBER_ID_MIN + ((__ITER + (__VU - 1)) % memberRange);
}

export default function () {
  const productId = pickProductId();
  const memberId = pickMemberId();

  const url = `${BASE_URL}/api/orders`;
  const payload = JSON.stringify({
    memberId,
    productId,
    quantity: QTY,
  });

  const res = http.post(url, payload, {
    headers: { "Content-Type": "application/json" },
    tags: { name: "POST /api/orders", product_id: String(productId) },
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
