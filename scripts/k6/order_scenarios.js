import { randomSeed } from "k6";
import exec from "k6/execution";
import http from "k6/http";
import { Counter, Rate, Trend } from "k6/metrics";

// Each iteration creates one order for one unit. The probe stream is identical in all cases.
const CASE = __ENV.CASE || "normal";
if (!["normal", "mixed", "control"].includes(CASE)) {
  throw new Error("CASE must be normal, mixed or control.");
}

function positiveInteger(name, fallback) {
  const value = Number(__ENV[name] ?? fallback);
  if (!Number.isSafeInteger(value) || value < 1) throw new Error(`${name} must be a positive integer.`);
  return value;
}

const BASE_URL = (__ENV.BASE_URL || "http://localhost:8080").replace(/\/$/, "");
const NORMAL_RPS = positiveInteger("NORMAL_RPS", 3);
const EXTRA_RPS = positiveInteger("EXTRA_RPS", 7);
const SECONDS = positiveInteger("SECONDS", 10);
const HOT_COUNT = positiveInteger("HOT_COUNT", 10);
const MIN_STOCK = positiveInteger("MIN_STOCK", 20);
const PREALLOCATED_VUS = positiveInteger("PREALLOCATED_VUS", 10);
const MAX_VUS = positiveInteger("MAX_VUS", 100);
if (PREALLOCATED_VUS > MAX_VUS) throw new Error("PREALLOCATED_VUS exceeds MAX_VUS.");

const catalog = JSON.parse(open(__ENV.TARGETS_FILE || "./order-targets.json"));
if (catalog.schemaVersion !== 2 || !Number.isSafeInteger(catalog.memberCount) || catalog.memberCount < 1
    || !Number.isSafeInteger(catalog.seed) || !Array.isArray(catalog.targets)) {
  throw new Error("Export a v2 order-targets.json using scripts/sql/run-seed-order-products.ps1.");
}
const seen = new Set();
for (const sku of catalog.targets) {
  if (!Number.isSafeInteger(sku.productId) || sku.productId < 1 || seen.has(sku.productId)
      || !Number.isSafeInteger(sku.initialStock) || sku.initialStock < 0
      || !Number.isSafeInteger(sku.popularityScore) || sku.popularityScore < 0) {
    throw new Error("Invalid or duplicate product in the order target export.");
  }
  seen.add(sku.productId);
}
const ranked = catalog.targets.filter((sku) => sku.initialStock >= MIN_STOCK)
  .sort((a, b) => b.popularityScore - a.popularityScore || a.productId - b.productId);
const hot = ranked.slice(0, HOT_COUNT);
const normal = ranked.slice(HOT_COUNT);
if (!normal.length) throw new Error("Export more in-stock products or lower MIN_STOCK or HOT_COUNT.");

function stream(execName, rate, tier, role) {
  return {
    executor: "constant-arrival-rate",
    exec: execName,
    rate,
    timeUnit: "1s",
    duration: `${SECONDS}s`,
    preAllocatedVUs: PREALLOCATED_VUS,
    maxVUs: MAX_VUS,
    tags: { tier, stream: role },
  };
}

const scenarios = { probe: stream("probeOrder", NORMAL_RPS, "normal", "probe") };
if (CASE !== "normal") {
  scenarios.extra = stream("extraOrder", EXTRA_RPS, CASE === "mixed" ? "hot" : "normal", "extra");
}
export const options = {
  scenarios,
  tags: { workload: CASE },
  systemTags: ["name", "method", "status", "scenario", "expected_response"],
  summaryTrendStats: ["med", "p(95)", "p(99)", "max"],
  thresholds: {
    dropped_iterations: ["count==0"],
    order_stockout_rate: ["rate==0"],
    order_unexpected_rate: ["rate==0"],
  },
};

const outcomeCounts = {};
for (const outcome of ["created", "stockout", "key_conflict", "conflict", "invalid_response", "unexpected"]) {
  outcomeCounts[outcome] = new Counter(`orders_${outcome}`);
}
const stockouts = new Rate("order_stockout_rate");
const unexpected = new Rate("order_unexpected_rate");
const probeDuration = new Trend("order_probe_duration", true);

function checkBudget(products, requests, tier) {
  const available = products.reduce((sum, sku) => sum + sku.initialStock, 0);
  if (requests > available) {
    throw new Error(`${tier}: the run schedules ${requests} units but the export has only ${available}. Shorten the run or change the sample.`);
  }
}

export function setup() {
  const normalRate = NORMAL_RPS + (CASE === "control" ? EXTRA_RPS : 0);
  checkBudget(normal, normalRate * SECONDS, "normal");
  if (CASE === "mixed") checkBudget(hot, EXTRA_RPS * SECONDS, "hot");
  const runId = `${Date.now().toString(36)}-${Math.random().toString(36).slice(2, 12)}`;
  console.log(`run=${runId} case=${CASE} seed=${catalog.seed} members=${catalog.memberCount} hot=${hot.length} normal=${normal.length}`);
  return { runId };
}

function classify(response, request) {
  let envelope;
  try {
    envelope = response.json();
  } catch {
    return "invalid_response";
  }
  const order = envelope?.data;
  if (response.status === 200 && envelope?.success === true && Number.isSafeInteger(order?.id) && order.id > 0
      && order.productId === request.productId && order.memberId === request.memberId
      && order.quantity === 1 && order.idempotencyKey === request.idempotencyKey) return "created";
  if (response.status === 409) {
    switch (envelope?.error?.code) {
      case "INSUFFICIENT_STOCK": return "stockout";
      case "IDEMPOTENCY_KEY_CONFLICT": return "key_conflict";
      case "CONFLICT": return "conflict";
    }
  }
  return "unexpected";
}

function placeOrder(products, isProbe, runId) {
  const iteration = exec.scenario.iterationInTest;
  // Repeat the same normal probes even when k6 allocates different VUs across runs.
  randomSeed((catalog.seed + iteration + (isProbe ? 0 : 0x40000000)) >>> 0);
  const productId = products[Math.floor(Math.random() * products.length)].productId;
  const memberId = 1 + Math.floor(Math.random() * catalog.memberCount);
  const request = {
    memberId,
    productId,
    quantity: 1,
    idempotencyKey: `order-${runId}-${exec.scenario.name}-${iteration}`,
  };
  const response = http.post(`${BASE_URL}/api/orders`, JSON.stringify(request), {
    headers: { "Content-Type": "application/json" },
    tags: { name: "POST /api/orders" },
  });
  const outcome = classify(response, request);
  outcomeCounts[outcome].add(1);
  stockouts.add(outcome === "stockout");
  unexpected.add(outcome !== "stockout" && outcome !== "created");
  if (isProbe) probeDuration.add(response.timings.duration);
}

export function probeOrder(data) {
  placeOrder(normal, true, data.runId);
}

export function extraOrder(data) {
  placeOrder(CASE === "mixed" ? hot : normal, false, data.runId);
}
