import { check } from "k6";
import http from "k6/http";

const mode = __ENV.CASE || "replay";
const baseUrl = (__ENV.BASE_URL || "http://localhost:8080").replace(/\/$/, "");
const memberId = Number(__ENV.MEMBER_ID || 1);
const burstSize = Number(__ENV.BURST_SIZE || 8);
const manifest = JSON.parse(open(__ENV.TARGETS_FILE || "./order-targets.json"));

if (!["replay", "burst", "last-unit"].includes(mode)) {
  throw new Error("CASE must be replay, burst, or last-unit.");
}
if (!Number.isInteger(burstSize) || burstSize < 2 || burstSize > 32) {
  throw new Error("BURST_SIZE must be an integer from 2 to 32.");
}
if (manifest.schemaVersion !== 2 || !Array.isArray(manifest.targets) ||
    !Number.isSafeInteger(manifest.memberCount) || manifest.memberCount < 1) {
  throw new Error("Export a current v2 order-targets.json before running this scenario.");
}
if (!Number.isSafeInteger(memberId) || memberId < 1 || memberId > manifest.memberCount) {
  throw new Error("MEMBER_ID must be in the exported B9 member range.");
}

export const options = {
  vus: 1,
  iterations: 1,
  batch: burstSize,
  batchPerHost: burstSize,
  tags: { case: mode },
  systemTags: ["name", "method", "status", "scenario", "check", "expected_response", "error_code"],
  thresholds: { checks: ["rate==1"], http_req_failed: ["rate==0"] },
};

function parseBody(response) {
  try {
    return response.json();
  } catch {
    return null;
  }
}

function getStock(productId) {
  const response = http.get(`${baseUrl}/api/products/${productId}`, {
    tags: { name: "GET /api/products/{id}" },
  });
  const body = parseBody(response);
  if (response.status !== 200 || !body || !body.success || !body.data ||
      body.data.id !== productId || !Number.isSafeInteger(body.data.stockQuantity)) {
    return null;
  }
  return body.data.stockQuantity;
}

export function setup() {
  // Use production-sized IDs so boxed Long reference comparisons are exercised.
  const requiredStock = mode === "burst" ? burstSize : 2;
  const target = manifest.targets.find((item) =>
    Number.isSafeInteger(item.productId) && item.productId > 127 &&
    (!__ENV.PRODUCT_ID || item.productId === Number(__ENV.PRODUCT_ID)) &&
    Number.isSafeInteger(item.initialStock) &&
    (mode === "last-unit" ? item.initialStock === 1 : item.initialStock >= requiredStock));
  if (!target) {
    throw new Error(`No eligible SKU in the export for CASE=${mode}. Refresh or enlarge the sample.`);
  }
  const stockBefore = getStock(target.productId);
  if (stockBefore !== target.initialStock) {
    throw new Error("The live SKU no longer matches the export. Check BASE_URL and export the sample again.");
  }
  const key = `dup-${mode}-${Date.now().toString(36)}-${Math.random().toString(36).slice(2, 12)}`;
  console.log("After k6, run from the repository root against the same Compose project:");
  console.log("Get-Content -Raw scripts/sql/check_order_idempotency.sql | " +
    `docker compose exec -T postgres psql -X -U marketengine -d marketengine -v ON_ERROR_STOP=1 ` +
    `-v member=${memberId} -v product=${target.productId} -v key=${key} -v before=${stockBefore}`);
  return { productId: target.productId, stockBefore, key };
}

export default function ({ productId, stockBefore, key }) {
  const url = `${baseUrl}/api/orders`;
  const payload = JSON.stringify({ memberId, productId, quantity: 1, idempotencyKey: key });
  const params = { headers: { "Content-Type": "application/json" }, tags: { name: "POST /api/orders" } };
  const responses = mode === "replay"
    ? [http.post(url, payload, params), http.post(url, payload, params)]
    : http.batch(Array.from({ length: burstSize }, () => ["POST", url, payload, params]));

  const ids = [];
  for (const response of responses) {
    const body = parseBody(response);
    const order = body && body.data;
    const valid = check(response, {
      "request returns the submitted order": (r) =>
        r.status === 200 && body && body.success && order && Number.isSafeInteger(order.id) && order.id > 0 &&
        order.memberId === memberId && order.productId === productId && order.quantity === 1 &&
        order.idempotencyKey === key,
    });
    if (valid) {
      ids.push(order.id);
    } else {
      console.warn(`Unexpected order response: HTTP ${response.status}, code=${body && body.error && body.error.code}`);
    }
  }
  check(ids, {
    "every response has the same order ID": (values) =>
      values.length === responses.length && new Set(values).size === 1,
  });
  check(getStock(productId), {
    "only one unit was reserved": (stockAfter) => stockAfter === stockBefore - 1,
  });
}
