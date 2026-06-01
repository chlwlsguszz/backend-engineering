import http from "k6/http";
import { check, sleep } from "k6";

/**
 * /api/products 고 RPS 부하 (v3) — 소수 VU, 극소 sleep, 요청 수 극대화
 *
 * v2 대비: VU↓, think time↓, ramping-arrival-rate로 RPS 웜업 후 목표 유지
 *
 * 기본: k6 run scripts/k6/search_v3.js  (2m 웜업 0→TARGET_RPS, 이후 HOLD 동안 유지)
 * RPS: k6 run -e TARGET_RPS=10000 -e WARM_UP=3m -e HOLD=5m scripts/k6/search_v3.js
 * VU: k6 run -e MAX_VUS=1200 -e PREALLOCATED_VUS=400 scripts/k6/search_v3.js
 */

const BASE_URL = __ENV.BASE_URL || "http://localhost:8080";
const SLOW_MS = Number(__ENV.SLOW_MS || 100);
const LOG_SLOW = __ENV.LOG_SLOW === "1";
const SIZE = Number(__ENV.SIZE || 12);

// 극소 think (0~20ms). 0으로 고정: THINK_MIN_MS=0 THINK_MAX_MS=0
const THINK_MIN_MS = Number(__ENV.THINK_MIN_MS ?? 0);
const THINK_MAX_MS = Number(__ENV.THINK_MAX_MS ?? 20);

// 초당 목표 요청 수 (simple:complex:keyword = 3:1:1). 기본 10000/s
const TARGET_RPS = Number(__ENV.TARGET_RPS || 10000);
const WARM_UP = __ENV.WARM_UP || "30s";
const HOLD = __ENV.HOLD || __ENV.DURATION || "30s";
// High-RPS preset: keep enough VUs to avoid dropped_iterations during hold.
const PREALLOCATED_VUS = Number(__ENV.PREALLOCATED_VUS || 400);
const MAX_VUS = Number(__ENV.MAX_VUS || 1200);

const SIMPLE_RATE = Math.max(1, Math.floor((TARGET_RPS * 3) / 5));
const COMPLEX_RATE = Math.max(1, Math.floor(TARGET_RPS / 5));
const KEYWORD_RATE = Math.max(1, TARGET_RPS - SIMPLE_RATE - COMPLEX_RATE);

export function setup() {
  console.log(
    `[k6 v3] TARGET_RPS=${TARGET_RPS} → simple=${SIMPLE_RATE}/s, complex=${COMPLEX_RATE}/s, keyword=${KEYWORD_RATE}/s`
  );
  console.log(
    `[k6 v3] warm-up ${WARM_UP} 0→TARGET_RPS, hold ${HOLD} | VUs ${PREALLOCATED_VUS}/${MAX_VUS} | think ${THINK_MIN_MS}~${THINK_MAX_MS}ms`
  );
}

// ----------------------------------------------------------------------
// 데이터셋 (v2와 동일)
// ----------------------------------------------------------------------
const GENDERS = ["MEN", "WOMEN", "UNISEX"];

const WEIGHTED_CATEGORIES = [
  { name: "TOP", weight: 0.40 },
  { name: "BOTTOM", weight: 0.30 },
  { name: "OUTER", weight: 0.15 },
  { name: "SHOES", weight: 0.10 },
  { name: "HAT", weight: 0.03 },
  { name: "GLASSES", weight: 0.02 },
];

const WEIGHTED_COLORS = [
  { name: "BLACK", weight: 0.40 },
  { name: "WHITE", weight: 0.25 },
  { name: "GRAY", weight: 0.15 },
  { name: "NAVY", weight: 0.10 },
  { name: "BEIGE", weight: 0.05 },
  { name: "BLUE", weight: 0.02 },
  { name: "RED", weight: 0.02 },
  { name: "GREEN", weight: 0.01 },
];

const WEIGHTED_BRANDS = [
  { name: "Nike", weight: 0.35 },
  { name: "Adidas", weight: 0.20 },
  { name: "The North Face", weight: 0.15 },
  { name: "New Balance", weight: 0.10 },
  { name: "Puma", weight: 0.05 },
  { name: "Under Armour", weight: 0.05 },
  { name: "Asics", weight: 0.05 },
  { name: "Patagonia", weight: 0.05 },
];

const WEIGHTED_KEYWORDS = [
  { name: "Nike", weight: 0.25 },
  { name: "Adidas", weight: 0.20 },
  { name: "Hoodie", weight: 0.15 },
  { name: "Sneakers", weight: 0.15 },
  { name: "Jacket", weight: 0.10 },
  { name: "T-Shirt", weight: 0.05 },
  { name: "Running", weight: 0.05 },
  { name: "Carhartt", weight: 0.05 },
];

const KEYWORD_TYPOS = ["Nikke", "Nkie", "Adidass", "Pumma", "Hoodi", "Sneeker", "Jaket"];
const KEYWORD_COMBOS = [
  "Nike Hoodie", "Adidas MEN", "Nike Running", "Puma Sneakers", "North Face Jacket",
  "Under Armour Shirt", "New Balance Shoes", "Converse WOMEN",
];

const USER_AGENTS = [
  "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/114.0.0.0 Safari/537.36",
  "Mozilla/5.0 (iPhone; CPU iPhone OS 16_5 like Mac OS X) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/16.5 Mobile/15E148 Safari/604.1",
  "Mozilla/5.0 (Linux; Android 13; SM-S918B) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/114.0.0.0 Mobile Safari/537.36",
];

// ----------------------------------------------------------------------
// 유틸
// ----------------------------------------------------------------------
function getHeaders() {
  return {
    "User-Agent": pick(USER_AGENTS),
    Accept: "application/json, text/plain, */*",
    "Cache-Control": "no-cache",
  };
}

function query(params) {
  const parts = [];
  for (const [k, v] of Object.entries(params)) {
    if (v != null && v !== "") parts.push(`${encodeURIComponent(k)}=${encodeURIComponent(v)}`);
  }
  return parts.length ? `?${parts.join("&")}` : "";
}

function pick(arr) {
  return arr[Math.floor(Math.random() * arr.length)];
}

function weightedPick(items) {
  const totalWeight = items.reduce((sum, item) => sum + item.weight, 0);
  let random = Math.random() * totalWeight;
  for (const item of items) {
    if (random < item.weight) return item.name;
    random -= item.weight;
  }
  return items[items.length - 1].name;
}

function randomPage() {
  const r = Math.random();
  if (r < 0.70) return 0;
  if (r < 0.90) return 1;
  // Cap deep pagination: 0~2 only (avoid ES offset paging tail explosion on page>=3)
  return 2;
}

function priceBand() {
  const r = Math.random();
  if (r < 0.55) return { minPrice: 40000, maxPrice: 180000 };
  if (r < 0.8) return { minPrice: 10000, maxPrice: 50000 };
  if (r < 0.93) return { minPrice: 150000, maxPrice: 350000 };
  return { minPrice: 20000, maxPrice: 400000 };
}

function think() {
  if (THINK_MAX_MS <= 0) return;
  const ms =
    THINK_MIN_MS + Math.floor(Math.random() * (THINK_MAX_MS - THINK_MIN_MS + 1));
  if (ms > 0) sleep(ms / 1000);
}

function getProducts(params, name) {
  const path = `/api/products${query(params)}`;
  const res = http.get(`${BASE_URL}${path}`, {
    headers: getHeaders(),
    tags: { name },
  });

  if (LOG_SLOW && res.timings.duration >= SLOW_MS) {
    const ms = Math.round(res.timings.duration);
    console.log(`[SLOW] ${name} | ${ms}ms | ${res.status} | ${path}`);
  }

  check(res, {
    [`${name} status 200`]: (r) => r.status === 200,
    [`${name} success`]: (r) => r.json("success") === true,
    [`${name} items array`]: (r) => Array.isArray(r.json("data.items")),
  });
}

function arrivalScenario(rate, exec, scenario) {
  return {
    executor: "ramping-arrival-rate",
    startRate: 0,
    timeUnit: "1s",
    preAllocatedVUs: PREALLOCATED_VUS,
    maxVUs: MAX_VUS,
    stages: [
      { duration: WARM_UP, target: rate },
      { duration: HOLD, target: rate },
    ],
    exec,
    tags: { scenario },
  };
}

export const options = {
  scenarios: {
    simple: arrivalScenario(SIMPLE_RATE, "simpleBrowse", "simple"),
    complex_filters: arrivalScenario(COMPLEX_RATE, "complexFiltersRandom", "complex"),
    keyword_search: arrivalScenario(KEYWORD_RATE, "keywordSearchRandom", "keyword"),
  },
};

// ----------------------------------------------------------------------
// 실행 함수 (v2 패턴 유지, think만 극소)
// ----------------------------------------------------------------------
export function simpleBrowse() {
  const page = randomPage();
  const r = Math.random();

  if (r < 0.68) getProducts({ page, size: SIZE, sortBy: "LATEST" }, "simple-latest");
  else if (r < 0.88)
    getProducts(
      { page, size: SIZE, sortBy: "LATEST", category: weightedPick(WEIGHTED_CATEGORIES) },
      "simple-category"
    );
  else if (r < 0.96) getProducts({ page, size: SIZE, sortBy: "POPULARITY" }, "simple-popularity");
  else
    getProducts(
      {
        page,
        size: SIZE,
        sortBy: "POPULARITY",
        category: weightedPick(WEIGHTED_CATEGORIES),
      },
      "simple-cat-pop"
    );

  think();
}

const COMPLEX_PROFILES = [
  { w: 0.2, tag: "complex-kw-only", extra: () => ({ keyword: weightedPick(WEIGHTED_KEYWORDS) }) },
  { w: 0.14, tag: "complex-cat-only", extra: () => ({ category: weightedPick(WEIGHTED_CATEGORIES) }) },
  {
    w: 0.12,
    tag: "complex-cat-brand",
    extra: () => ({
      category: weightedPick(WEIGHTED_CATEGORIES),
      brand: weightedPick(WEIGHTED_BRANDS),
    }),
  },
  {
    w: 0.1,
    tag: "complex-cat-gender-color",
    extra: () => ({
      category: weightedPick(WEIGHTED_CATEGORIES),
      gender: pick(GENDERS),
      color: weightedPick(WEIGHTED_COLORS),
    }),
  },
  { w: 0.08, tag: "complex-price", extra: priceBand },
  {
    w: 0.08,
    tag: "complex-kw-cat",
    extra: () => ({
      keyword: weightedPick(WEIGHTED_KEYWORDS),
      category: weightedPick(WEIGHTED_CATEGORIES),
    }),
  },
  {
    w: 0.08,
    tag: "complex-brand-gender",
    extra: () => ({ brand: weightedPick(WEIGHTED_BRANDS), gender: pick(GENDERS) }),
  },
  {
    w: 0.08,
    tag: "complex-stack",
    extra: () => ({
      category: weightedPick(WEIGHTED_CATEGORIES),
      brand: weightedPick(WEIGHTED_BRANDS),
      gender: pick(GENDERS),
      color: weightedPick(WEIGHTED_COLORS),
      ...priceBand(),
    }),
  },
  {
    w: 0.06,
    tag: "complex-kw-price",
    extra: () => ({ keyword: weightedPick(WEIGHTED_KEYWORDS), ...priceBand() }),
  },
  {
    w: 0.06,
    tag: "complex-full",
    extra: () => ({
      keyword: weightedPick(WEIGHTED_KEYWORDS),
      category: weightedPick(WEIGHTED_CATEGORIES),
      brand: weightedPick(WEIGHTED_BRANDS),
      gender: pick(GENDERS),
      color: weightedPick(WEIGHTED_COLORS),
      ...priceBand(),
    }),
  },
];

export function complexFiltersRandom() {
  const base = { page: randomPage(), size: SIZE, sortBy: Math.random() < 0.88 ? "LATEST" : "POPULARITY" };
  let u = Math.random();

  for (const p of COMPLEX_PROFILES) {
    if ((u -= p.w) < 0) {
      getProducts({ ...base, ...p.extra() }, p.tag);
      think();
      return;
    }
  }
  const last = COMPLEX_PROFILES[COMPLEX_PROFILES.length - 1];
  getProducts({ ...base, ...last.extra() }, last.tag);
  think();
}

export function keywordSearchRandom() {
  const base = { page: randomPage(), size: SIZE, sortBy: Math.random() < 0.85 ? "LATEST" : "POPULARITY" };
  const u = Math.random();

  if (u < 0.4) getProducts({ ...base, keyword: pick(KEYWORD_TYPOS) }, "kw-typo");
  else if (u < 0.8) getProducts({ ...base, keyword: pick(KEYWORD_COMBOS) }, "kw-combo");
  else getProducts({ ...base, keyword: weightedPick(WEIGHTED_KEYWORDS) }, "kw-single");

  think();
}
