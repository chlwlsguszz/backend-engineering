import http from "k6/http";
import { check } from "k6";

/**
 * Duplicate submit test — same idempotencyKey twice should create one order.
 *
 *   k6 run scripts/k6/order_duplicate_submit.js
 *
 * Verify:
 *   .\scripts\sql\run-check-hot-sku.ps1
 */

const BASE_URL = __ENV.BASE_URL || "http://localhost:8080";
const MEMBER_ID = Number(__ENV.MEMBER_ID || 1);
const PRODUCT_ID = Number(__ENV.PRODUCT_ID || 10000501);
const QTY = Number(__ENV.QTY || 1);
const IDEMPOTENCY_KEY = __ENV.IDEMPOTENCY_KEY || "dup-submit-demo";

export const options = {
  vus: 1,
  iterations: 1,
};

export default function () {
  const url = `${BASE_URL}/api/orders`;
  const payload = JSON.stringify({
    memberId: MEMBER_ID,
    productId: PRODUCT_ID,
    quantity: QTY,
    idempotencyKey: IDEMPOTENCY_KEY,
  });
  const params = {
    headers: { "Content-Type": "application/json" },
    tags: { name: "POST /api/orders" },
  };

  const first = http.post(url, payload, params);
  const second = http.post(url, payload, params);

  check(first, { "first status 200": (r) => r.status === 200 });
  check(second, { "second status 200": (r) => r.status === 200 });

  const firstId = first.json("data.id");
  const secondId = second.json("data.id");
  check(null, {
    "same order id returned": () => firstId === secondId,
  });
}
