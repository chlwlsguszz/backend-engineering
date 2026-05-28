import http from "k6/http";
import { check, sleep } from "k6";

/**
 * /api/products 부하 — simple : complex : keyword = 3 : 1 : 1 (병렬, ramping)
 *
 *   k6 run scripts/k6/search.js
 *   k6 run -e TOTAL_VUS=1000 scripts/k6/search.js
 *   k6 run -e RAMP_UP=2m -e HOLD=10m scripts/k6/search.js
 *
 * Slow request log (default >= 1000 ms), one line: tag | duration | status | url
 *   k6 run scripts/k6/search.js 2> scripts/k6/slow-requests.log
 *   Select-String slow-requests.log '\[SLOW\]'
 *   k6 run -e LOG_SLOW=0 scripts/k6/search.js   # disable
 */

const BASE_URL = __ENV.BASE_URL || "http://localhost:8080";
const SLOW_MS = Number(__ENV.SLOW_MS || 100);
const LOG_SLOW = __ENV.LOG_SLOW !== "0";
const SIZE = Number(__ENV.SIZE || 12);
const RAMP_UP = __ENV.RAMP_UP || "30s";
const HOLD = __ENV.HOLD || __ENV.DURATION || "30s";
const THINK_MIN_MS = Number(__ENV.THINK_MIN_MS || 200);
const THINK_MAX_MS = Number(__ENV.THINK_MAX_MS || 900);

const TOTAL_VUS = Number(__ENV.TOTAL_VUS || 20);
const SIMPLE_VUS = Math.floor((TOTAL_VUS * 3) / 5);
const COMPLEX_VUS = Math.floor(TOTAL_VUS / 5);
const KEYWORD_VUS = TOTAL_VUS - SIMPLE_VUS - COMPLEX_VUS;

export function setup() {
  if (__ENV.TOTAL_VUS) {
    console.log(
      `[k6] TOTAL_VUS=${TOTAL_VUS} → simple=${SIMPLE_VUS}, complex=${COMPLEX_VUS}, keyword=${KEYWORD_VUS} (3:1:1, ramp ${RAMP_UP} + hold ${HOLD})`,
    );
  }
}

const CATEGORIES = ["TOP", "BOTTOM", "OUTER", "SHOES", "GLASSES", "HAT"];
const BRANDS = [
  "Nike", "Adidas", "Puma", "New Balance", "Under Armour", "Converse", "Reebok", "Fila",
  "Asics", "Lululemon", "Jordan", "Vans", "Skechers", "Champion", "Levis", "Patagonia",
  "The North Face", "Columbia", "Oakley", "Carhartt",
];
const COLORS = ["BLACK", "WHITE", "NAVY", "GRAY", "BEIGE", "RED", "BLUE", "GREEN"];
const GENDERS = ["MEN", "WOMEN", "UNISEX"];
const KEYWORDS = [
  "Nike", "Adidas", "Hoodie", "T-Shirt", "SHOES", "MEN", "WOMEN", "Carhartt",
  "North Face", "Oakley", "Jacket", "Running",
];
const KEYWORD_TYPOS = ["Nikke", "Nkie", "Adidass", "Pumma", "Hoodi", "Sneeker", "Jaket"];
const KEYWORD_COMBOS = [
  "Nike Hoodie", "Adidas MEN", "Nike Running", "Puma Sneakers", "North Face Jacket",
  "Under Armour Shirt", "New Balance Shoes", "Converse WOMEN", "Reebok T-Shirt", "Columbia OUTER",
];

function rampScenario(target, exec, scenario) {
  return {
    executor: "ramping-vus",
    startVUs: 0,
    stages: [
      { duration: RAMP_UP, target },
      { duration: HOLD, target },
    ],
    gracefulRampDown: "30s",
    exec,
    tags: { scenario },
  };
}

export const options = {
  scenarios: {
    simple: rampScenario(SIMPLE_VUS, "simpleBrowse", "simple"),
    complex_filters: rampScenario(COMPLEX_VUS, "complexFiltersRandom", "complex"),
    keyword_search: rampScenario(KEYWORD_VUS, "keywordSearchRandom", "keyword"),
  },
};

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

function priceBand() {
  const r = Math.random();
  if (r < 0.55) return { minPrice: 40000, maxPrice: 180000 };
  if (r < 0.8) return { minPrice: 10000, maxPrice: 50000 };
  if (r < 0.93) return { minPrice: 150000, maxPrice: 350000 };
  return { minPrice: 20000, maxPrice: 400000 };
}

function getProducts(params, name) {
  const path = `/api/products${query(params)}`;
  const res = http.get(`${BASE_URL}${path}`, { tags: { name } });
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

function think() {
  const ms = THINK_MIN_MS + Math.floor(Math.random() * (THINK_MAX_MS - THINK_MIN_MS + 1));
  sleep(ms / 1000);
}

export function simpleBrowse() {
  const r = Math.random();
  if (r < 0.68) getProducts({ page: 0, size: SIZE, sortBy: "LATEST" }, "simple-latest");
  else if (r < 0.88) getProducts({ page: 0, size: SIZE, sortBy: "LATEST", category: pick(CATEGORIES) }, "simple-category");
  else if (r < 0.96) getProducts({ page: 0, size: SIZE, sortBy: "POPULARITY" }, "simple-popularity");
  else getProducts({ page: 0, size: SIZE, sortBy: "POPULARITY", category: pick(CATEGORIES) }, "simple-cat-pop");
  think();
}

const COMPLEX_PROFILES = [
  { w: 0.2, tag: "complex-kw-only", extra: () => ({ keyword: pick(KEYWORDS) }) },
  { w: 0.14, tag: "complex-cat-only", extra: () => ({ category: pick(CATEGORIES) }) },
  { w: 0.12, tag: "complex-cat-brand", extra: () => ({ category: pick(CATEGORIES), brand: pick(BRANDS) }) },
  {
    w: 0.1,
    tag: "complex-cat-gender-color",
    extra: () => ({ category: pick(CATEGORIES), gender: pick(GENDERS), color: pick(COLORS) }),
  },
  { w: 0.08, tag: "complex-price", extra: priceBand },
  { w: 0.08, tag: "complex-kw-cat", extra: () => ({ keyword: pick(KEYWORDS), category: pick(CATEGORIES) }) },
  { w: 0.08, tag: "complex-brand-gender", extra: () => ({ brand: pick(BRANDS), gender: pick(GENDERS) }) },
  {
    w: 0.08,
    tag: "complex-stack",
    extra: () => ({
      category: pick(CATEGORIES),
      brand: pick(BRANDS),
      gender: pick(GENDERS),
      color: pick(COLORS),
      ...priceBand(),
    }),
  },
  { w: 0.06, tag: "complex-kw-price", extra: () => ({ keyword: pick(KEYWORDS), ...priceBand() }) },
  {
    w: 0.06,
    tag: "complex-full",
    extra: () => ({
      keyword: pick(KEYWORDS),
      category: pick(CATEGORIES),
      brand: pick(BRANDS),
      gender: pick(GENDERS),
      color: pick(COLORS),
      ...priceBand(),
    }),
  },
];

export function complexFiltersRandom() {
  const base = { page: 0, size: SIZE, sortBy: Math.random() < 0.88 ? "LATEST" : "POPULARITY" };
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
  const base = { size: SIZE, sortBy: Math.random() < 0.85 ? "LATEST" : "POPULARITY" };
  const u = Math.random();
  if (u < 0.4) getProducts({ ...base, keyword: pick(KEYWORD_TYPOS) }, "kw-typo");
  else if (u < 0.8) getProducts({ ...base, keyword: pick(KEYWORD_COMBOS) }, "kw-combo");
  else getProducts({ ...base, keyword: pick(KEYWORDS) }, "kw-single");
  think();
}
