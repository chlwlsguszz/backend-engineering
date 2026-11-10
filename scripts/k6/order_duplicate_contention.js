import { randomSeed } from "k6";
import exec from "k6/execution";
import http from "k6/http";
import { Counter, Rate, Trend } from "k6/metrics";

function integer(name, fallback, min = 1, max = Number.MAX_SAFE_INTEGER) {
  const value = Number(__ENV[name] ?? fallback);
  if (!Number.isSafeInteger(value) || value < min || value > max) throw new Error(`Invalid ${name}.`);
  return value;
}

const BASE_URL = (__ENV.BASE_URL || "http://localhost:8080").replace(/\/$/, "");
const PROBE_RPS = integer("PROBE_RPS", 4);
const PAIR_RPS = integer("PAIR_RPS", 8);
const SECONDS = integer("SECONDS", 20);
const HOT_COUNT = integer("HOT_COUNT", 10, 1, 1000);
const DUPLICATE_PAIR_PCT = integer("DUPLICATE_PAIR_PCT", 0, 0, 100);
const PREALLOCATED_VUS = integer("PREALLOCATED_VUS", 10);
const MAX_VUS = integer("MAX_VUS", 100, PREALLOCATED_VUS);

const catalog = JSON.parse(open(__ENV.TARGETS_FILE || "./order-targets.json"));
if (catalog.schemaVersion !== 2 || !Number.isSafeInteger(catalog.seed) || !Array.isArray(catalog.targets)
    || !Number.isSafeInteger(catalog.memberCount) || catalog.memberCount < 1) {
  throw new Error("Export a v2 order-targets.json with scripts/sql/run-seed-order-products.ps1.");
}
const ids = new Set();
for (const { productId, initialStock, popularityScore } of catalog.targets) {
  if (!Number.isSafeInteger(productId) || productId < 1 || ids.has(productId)
      || !Number.isSafeInteger(initialStock) || initialStock < 0 || !Number.isSafeInteger(popularityScore)) {
    throw new Error("Invalid SKU or duplicate product ID in order-targets.json.");
  }
  ids.add(productId);
}

const probeCount = PROBE_RPS * SECONDS;
const pairCount = PAIR_RPS * SECONDS;
const hotProbeCount = Math.ceil(probeCount / 2);
const normalProbeCount = Math.floor(probeCount / 2);
// Pin an A/B sample with enough stock for two runs even if every pair places two distinct orders.
const hotUnitsPerRun = 2 * Math.ceil(pairCount / HOT_COUNT) + Math.ceil(hotProbeCount / HOT_COUNT);
const requiredHotStock = 2 * hotUnitsPerRun;
const hot = catalog.targets.filter((sku) => sku.initialStock >= requiredHotStock)
  .sort((a, b) => b.popularityScore - a.popularityScore || a.productId - b.productId).slice(0, HOT_COUNT);
if (hot.length !== HOT_COUNT) {
  throw new Error(`Need ${HOT_COUNT} hot SKUs with >= ${requiredHotStock} units. Enlarge the sample, shorten the run or spread the load over more SKUs.`);
}
const hotIds = new Set(hot.map((sku) => sku.productId));
const normal = catalog.targets.filter((sku) => sku.initialStock >= 2 && !hotIds.has(sku.productId));
if (!normal.length || !normalProbeCount) throw new Error("Require a disjoint normal SKU and at least two scheduled probes.");
if (normal.some((sku, slot) => sku.initialStock < 2 * visits(normalProbeCount, slot, normal.length))) {
  throw new Error("The normal sample cannot supply both A/B runs. Enlarge the sample or shorten both runs.");
}

const invalidResponse = new Rate("contention_invalid_response");
const pairMismatch = new Rate("contention_pair_mismatch");
const stockouts = new Counter("contention_stockouts");
const duplicatePairs = new Counter("contention_duplicate_pairs");
const distinctPairs = new Counter("contention_distinct_pairs");
const probeLatency = new Trend("contention_probe_duration", true);
const hotProbeLatency = new Trend("contention_hot_probe_duration", true);
const normalProbeLatency = new Trend("contention_normal_probe_duration", true);
const duplicateLatency = new Trend("contention_duplicate_pair_duration", true);
const distinctLatency = new Trend("contention_distinct_pair_duration", true);

function arrivalStream(execName, rate) {
  return {
    executor: "constant-arrival-rate", exec: execName, rate, timeUnit: "1s", duration: `${SECONDS}s`,
    preAllocatedVUs: PREALLOCATED_VUS, maxVUs: MAX_VUS,
  };
}

export const options = {
  scenarios: { probe: arrivalStream("probeOrder", PROBE_RPS), pairs: arrivalStream("pairOrders", PAIR_RPS) },
  systemTags: ["name", "method", "status", "scenario", "expected_response"],
  summaryTrendStats: ["med", "p(95)", "p(99)", "max"],
  thresholds: {
    dropped_iterations: ["count==0"],
    "http_reqs{scenario:probe}": [`count==${probeCount}`],
    "http_reqs{scenario:pairs}": [`count==${2 * pairCount}`],
    contention_invalid_response: ["rate==0"],
    contention_pair_mismatch: ["rate==0"],
  },
};

function visits(total, slot, poolSize) {
  return Math.max(0, Math.ceil((total - slot) / poolSize));
}

export function setup() {
  // Round-robin selection gives an exact worst-case per-SKU cost with all pairs using distinct keys.
  const hotPlan = hot.map(({ productId }, i) => ({
    productId, units: visits(hotProbeCount, i, hot.length) + 2 * visits(pairCount, i, hot.length),
  }));
  const normalPlan = normal.map(({ productId }, i) => ({ productId, units: visits(normalProbeCount, i, normal.length) }));
  const scheduled = hotPlan.concat(normalPlan).filter(({ units }) => units > 0);
  for (let i = 0; i < scheduled.length; i += 20) {
    const group = scheduled.slice(i, i + 20);
    const results = http.batch(group.map(({ productId }) => ["GET", `${BASE_URL}/api/products/${productId}`, null,
      { tags: { name: "GET /api/products/{id}" } }]));
    const observations = [];
    for (let j = 0; j < group.length; j++) {
      const { productId, units } = group[j];
      const response = results[j];
      const product = response.status === 200 ? response.json()?.data : null;
      if (product?.id !== productId || !Number.isSafeInteger(product.stockQuantity)) {
        throw new Error(`Unable to read live stock for SKU ${productId}. Verify BASE_URL and the pinned sample.`);
      }
      if (product.stockQuantity < units) {
        throw new Error(`SKU ${productId} needs ${units} units, but only ${product.stockQuantity} remain. Restore inventory or resize both runs.`);
      }
      observations.push(`${productId}:${product.stockQuantity}:${units}`);
    }
    console.log(`opening_inventory[sku:live:required]=${observations.join(",")}`);
  }
  const runId = `${Date.now().toString(36)}-${Math.random().toString(36).slice(2, 12)}`;
  console.log(`run=${runId} offered_post_rps=${PROBE_RPS + 2 * PAIR_RPS} duplicate_pair_pct=${DUPLICATE_PAIR_PCT} hot_skus=${hot.map((sku) => sku.productId)}`);
  return { runId };
}

function orderRequest(sku, runId, iteration) {
  randomSeed((catalog.seed + iteration + (exec.scenario.name === "pairs" ? 0x40000000 : 0)) >>> 0);
  return {
    memberId: 1 + Math.floor(Math.random() * catalog.memberCount), productId: sku.productId, quantity: 1,
    idempotencyKey: `contention-${runId}-${exec.scenario.name}-${iteration}`,
  };
}

const params = { headers: { "Content-Type": "application/json" }, tags: { name: "POST /api/orders" } };
const url = `${BASE_URL}/api/orders`;

function recordResponse(response, request, latency) {
  latency.add(response.timings.duration);
  let envelope;
  try {
    envelope = response.json();
  } catch {
    envelope = null;
  }
  const order = envelope?.data;
  const valid = response.status === 200 && envelope?.success === true && Number.isSafeInteger(order?.id)
    && order.id > 0 && order.memberId === request.memberId && order.productId === request.productId
    && order.quantity === 1 && order.idempotencyKey === request.idempotencyKey;
  stockouts.add(envelope?.error?.code === "INSUFFICIENT_STOCK" ? 1 : 0);
  invalidResponse.add(!valid);
  return valid ? order.id : null;
}

export function probeOrder({ runId }) {
  const i = exec.scenario.iterationInTest;
  // An arrival at the duration boundary may start one more iteration.
  if (i >= probeCount) return;
  if (i === 0) console.log(`contention_window_start_ms=${exec.scenario.startTime}`);
  const isHot = i % 2 === 0;
  const products = isHot ? hot : normal;
  const request = orderRequest(products[Math.floor(i / 2) % products.length], runId, i);
  const response = http.post(url, JSON.stringify(request), params);
  probeLatency.add(response.timings.duration);
  recordResponse(response, request, isHot ? hotProbeLatency : normalProbeLatency);
}

export function pairOrders({ runId }) {
  const i = exec.scenario.iterationInTest;
  if (i >= pairCount) return;
  const slot = i % HOT_COUNT;
  // Spread duplicate pairs across time AND across hot SKUs even at e.g. 50% with 10 hot SKUs.
  const phase = Math.floor(slot * 100 / HOT_COUNT);
  const duplicate = (Math.floor(i / HOT_COUNT) * DUPLICATE_PAIR_PCT + phase) % 100 + DUPLICATE_PAIR_PCT >= 100;
  const first = orderRequest(hot[slot], runId, i);
  const second = duplicate ? first : { ...first, idempotencyKey: `${first.idempotencyKey}-b` };
  const responses = http.batch([first, second].map((request) => ["POST", url, JSON.stringify(request), params]));
  const latency = duplicate ? duplicateLatency : distinctLatency;
  const firstId = recordResponse(responses[0], first, latency);
  const secondId = recordResponse(responses[1], second, latency);
  pairMismatch.add(firstId === null || secondId === null || (firstId === secondId) !== duplicate);
  duplicatePairs.add(duplicate ? 1 : 0);
  distinctPairs.add(duplicate ? 0 : 1);
}
