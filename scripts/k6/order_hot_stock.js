import http from "k6/http";
import { check, sleep } from "k6";
import { Counter } from "k6/metrics";

/**
 * Order hot-stock concurrency test
 *
 * Goal: many concurrent POST /api/orders against ONE hot productId.
 *
 * 기본:
 *   k6 run scripts/k6/order_hot_stock.js
 *
 * 커스텀:
 *   k6 run -e BASE_URL=http://localhost:8080 -e VUS=200 -e DURATION=30s scripts/k6/order_hot_stock.js
 *
 * Hot SKU / 멤버 / 수량:
 *   k6 run -e PRODUCT_ID=10000501 -e MEMBER_ID_MIN=1 -e MEMBER_ID_MAX=50 -e QTY=1 scripts/k6/order_hot_stock.js
 *
 * 참고:
 * - 성공(200) / 재고부족(409) / 기타 에러 비율을 카운트합니다.
 * - 재고는 사전에 DB seed로 맞춰두는 것을 권장합니다 (scripts/sql/seed_order_products_100.sql).
 */

const BASE_URL = __ENV.BASE_URL || "http://localhost:8080";
const PRODUCT_ID = Number(__ENV.PRODUCT_ID || 10000501);
const MEMBER_ID_MIN = Number(__ENV.MEMBER_ID_MIN || 1);
const MEMBER_ID_MAX = Number(__ENV.MEMBER_ID_MAX || 50);
const QTY = Number(__ENV.QTY || 1);

const VUS = Number(__ENV.VUS || 200);
const DURATION = __ENV.DURATION || "30s";

const THINK_MIN_MS = Number(__ENV.THINK_MIN_MS ?? 0);
const THINK_MAX_MS = Number(__ENV.THINK_MAX_MS ?? 10);

const created = new Counter("orders_created");
const insufficient = new Counter("orders_insufficient_stock");
const unexpected = new Counter("orders_unexpected");

const ENFORCE_THRESHOLDS = __ENV.ENFORCE_THRESHOLDS === "1";

export const options = {
  scenarios: {
    hot: {
      executor: "constant-vus",
      vus: VUS,
      duration: DURATION,
    },
  },
  thresholds: ENFORCE_THRESHOLDS ? { http_req_failed: ["rate<0.05"] } : {},
};

export function setup() {
  console.log(
    `[k6 order] BASE_URL=${BASE_URL} productId=${PRODUCT_ID} qty=${QTY} | members ${MEMBER_ID_MIN}..${MEMBER_ID_MAX} | VUS=${VUS} duration=${DURATION} | think ${THINK_MIN_MS}~${THINK_MAX_MS}ms`,
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

