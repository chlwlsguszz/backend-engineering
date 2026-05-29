import http from "k6/http";
import { check, sleep } from "k6";

/**
 * /api/products 부하 — simple : complex : keyword = 3 : 1 : 1 (병렬, ramping & spike)
 *
 * 기본 실행 (스파이크 패턴 포함): k6 run scripts/k6/search.js
 * 커스텀 실행: k6 run -e TOTAL_VUS=1000 -e RAMP_UP=1m -e HOLD=5m scripts/k6/search.js
 */

const BASE_URL = __ENV.BASE_URL || "http://localhost:8080";
const SLOW_MS = Number(__ENV.SLOW_MS || 100);
const LOG_SLOW = __ENV.LOG_SLOW !== "0";
const SIZE = Number(__ENV.SIZE || 12);

// Think Time: 사람의 실제 인지 및 탐색 시간 (2초 ~ 8초)
const THINK_MIN_MS = Number(__ENV.THINK_MIN_MS || 2000);
const THINK_MAX_MS = Number(__ENV.THINK_MAX_MS || 8000);

const TOTAL_VUS = Number(__ENV.TOTAL_VUS || 50);
const SIMPLE_VUS = Math.floor((TOTAL_VUS * 3) / 5);
const COMPLEX_VUS = Math.floor(TOTAL_VUS / 5);
const KEYWORD_VUS = TOTAL_VUS - SIMPLE_VUS - COMPLEX_VUS;

export function setup() {
  if (__ENV.TOTAL_VUS || true) {
    console.log(
      `[k6] TOTAL_VUS=${TOTAL_VUS} → simple=${SIMPLE_VUS}, complex=${COMPLEX_VUS}, keyword=${KEYWORD_VUS}`
    );
    console.log(`[k6] Think Time: ${THINK_MIN_MS}ms ~ ${THINK_MAX_MS}ms`);
  }
}

// ----------------------------------------------------------------------
// 데이터셋 및 가중치 설정 (모든 주요 필터에 멱함수 분포 적용)
// ----------------------------------------------------------------------
const GENDERS = ["MEN", "WOMEN", "UNISEX"];

// 카테고리 가중치 (상/하의 등 주요 품목 집중)
const WEIGHTED_CATEGORIES = [
  { name: "TOP", weight: 0.40 },
  { name: "BOTTOM", weight: 0.30 },
  { name: "OUTER", weight: 0.15 },
  { name: "SHOES", weight: 0.10 },
  { name: "HAT", weight: 0.03 },
  { name: "GLASSES", weight: 0.02 },
];

// 색상 가중치 (블랙, 화이트 및 무채색 계열 집중)
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

// 브랜드 가중치
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

// 키워드 가중치 (영문 전용)
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

// 영문 기반 오타 및 조합어
const KEYWORD_TYPOS = ["Nikke", "Nkie", "Adidass", "Pumma", "Hoodi", "Sneeker", "Jaket"];
const KEYWORD_COMBOS = [
  "Nike Hoodie", "Adidas MEN", "Nike Running", "Puma Sneakers", "North Face Jacket",
  "Under Armour Shirt", "New Balance Shoes", "Converse WOMEN"
];

// 브라우저/디바이스 환경 모사 헤더
const USER_AGENTS = [
  "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/114.0.0.0 Safari/537.36", // PC
  "Mozilla/5.0 (iPhone; CPU iPhone OS 16_5 like Mac OS X) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/16.5 Mobile/15E148 Safari/604.1", // iOS
  "Mozilla/5.0 (Linux; Android 13; SM-S918B) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/114.0.0.0 Mobile Safari/537.36", // Android
];

// ----------------------------------------------------------------------
// 유틸리티 함수
// ----------------------------------------------------------------------
function getHeaders() {
  return {
    "User-Agent": pick(USER_AGENTS),
    "Accept": "application/json, text/plain, */*",
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

// 가중치 기반 랜덤 선택 함수
function weightedPick(items) {
  const totalWeight = items.reduce((sum, item) => sum + item.weight, 0);
  let random = Math.random() * totalWeight;
  for (const item of items) {
    if (random < item.weight) return item.name;
    random -= item.weight;
  }
  return items[items.length - 1].name;
}

// 페이징 부하 유발: 70% 첫 페이지, 나머지 딥 다이브
function randomPage() {
  const r = Math.random();
  if (r < 0.70) return 0;
  if (r < 0.90) return 1;
  return Math.floor(Math.random() * 4) + 2; 
}

function priceBand() {
  const r = Math.random();
  if (r < 0.55) return { minPrice: 40000, maxPrice: 180000 };
  if (r < 0.8) return { minPrice: 10000, maxPrice: 50000 };
  if (r < 0.93) return { minPrice: 150000, maxPrice: 350000 };
  return { minPrice: 20000, maxPrice: 400000 };
}

function think() {
  const ms = THINK_MIN_MS + Math.floor(Math.random() * (THINK_MAX_MS - THINK_MIN_MS + 1));
  sleep(ms / 1000);
}

function getProducts(params, name) {
  const path = `/api/products${query(params)}`;
  const res = http.get(`${BASE_URL}${path}`, { 
    headers: getHeaders(), 
    tags: { name } 
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

// ----------------------------------------------------------------------
// 시나리오 & 스테이지 설정
// ----------------------------------------------------------------------
function getStages(target) {
  if (__ENV.RAMP_UP || __ENV.HOLD || __ENV.DURATION) {
    const rampUp = __ENV.RAMP_UP || "30s";
    const hold = __ENV.HOLD || __ENV.DURATION || "30s";
    return [
      { duration: rampUp, target },
      { duration: hold, target },
    ];
  }
  
  // 스파이크 트래픽 모사
  return [
    { duration: "30s", target: Math.floor(target * 0.5) }, // 웜업
    { duration: "1m", target: target },                    // 평시 트래픽
    { duration: "30s", target: target * 2 },               // 스파이크 폭주
    { duration: "1m", target: target },                    // 회복
  ];
}

function rampScenario(target, exec, scenario) {
  return {
    executor: "ramping-vus",
    startVUs: 0,
    stages: getStages(target),
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

// ----------------------------------------------------------------------
// VUs 실행 함수 (유저 행동 패턴)
// ----------------------------------------------------------------------
export function simpleBrowse() {
  const page = randomPage();
  const r = Math.random();
  
  if (r < 0.68) getProducts({ page, size: SIZE, sortBy: "LATEST" }, "simple-latest");
  else if (r < 0.88) getProducts({ page, size: SIZE, sortBy: "LATEST", category: weightedPick(WEIGHTED_CATEGORIES) }, "simple-category");
  else if (r < 0.96) getProducts({ page, size: SIZE, sortBy: "POPULARITY" }, "simple-popularity");
  else getProducts({ page, size: SIZE, sortBy: "POPULARITY", category: weightedPick(WEIGHTED_CATEGORIES) }, "simple-cat-pop");
  
  think();
}

const COMPLEX_PROFILES = [
  { w: 0.2, tag: "complex-kw-only", extra: () => ({ keyword: weightedPick(WEIGHTED_KEYWORDS) }) },
  { w: 0.14, tag: "complex-cat-only", extra: () => ({ category: weightedPick(WEIGHTED_CATEGORIES) }) },
  { w: 0.12, tag: "complex-cat-brand", extra: () => ({ category: weightedPick(WEIGHTED_CATEGORIES), brand: weightedPick(WEIGHTED_BRANDS) }) },
  { w: 0.1, tag: "complex-cat-gender-color", extra: () => ({ category: weightedPick(WEIGHTED_CATEGORIES), gender: pick(GENDERS), color: weightedPick(WEIGHTED_COLORS) }) },
  { w: 0.08, tag: "complex-price", extra: priceBand },
  { w: 0.08, tag: "complex-kw-cat", extra: () => ({ keyword: weightedPick(WEIGHTED_KEYWORDS), category: weightedPick(WEIGHTED_CATEGORIES) }) },
  { w: 0.08, tag: "complex-brand-gender", extra: () => ({ brand: weightedPick(WEIGHTED_BRANDS), gender: pick(GENDERS) }) },
  { w: 0.08, tag: "complex-stack", extra: () => ({ category: weightedPick(WEIGHTED_CATEGORIES), brand: weightedPick(WEIGHTED_BRANDS), gender: pick(GENDERS), color: weightedPick(WEIGHTED_COLORS), ...priceBand() }) },
  { w: 0.06, tag: "complex-kw-price", extra: () => ({ keyword: weightedPick(WEIGHTED_KEYWORDS), ...priceBand() }) },
  { w: 0.06, tag: "complex-full", extra: () => ({ keyword: weightedPick(WEIGHTED_KEYWORDS), category: weightedPick(WEIGHTED_CATEGORIES), brand: weightedPick(WEIGHTED_BRANDS), gender: pick(GENDERS), color: weightedPick(WEIGHTED_COLORS), ...priceBand() }) },
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