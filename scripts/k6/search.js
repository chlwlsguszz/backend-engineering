import http from "k6/http";
import { check, sleep } from "k6";

/**
 *
 * - /api/products 에 대해 EXPLAIN 시나리오(S1~S6)와 유사한 요청을 섞어서 보냅니다.
 * - Grafana 템플릿을 쓴다면 `--tag testid=...` 를 권장합니다.
 *
 * 실행 예시:
 *   k6 run scripts/k6/search.js
 *   k6 run -e VUS=10 -e DURATION=1m scripts/k6/search.js
 *   k6 run --tag testid=exp-1 -o experimental-prometheus-rw scripts/k6/search.js
 */

const BASE_URL = __ENV.BASE_URL || "http://localhost:8080";
const SIZE = Number(__ENV.SIZE || 12);
const THINK_MIN_MS = Number(__ENV.THINK_MIN_MS || 300);
const THINK_MAX_MS = Number(__ENV.THINK_MAX_MS || 1000);

// 계단식 stage (10s ramp + 10s hold): 2000 → 4000 → 6000
export const options = {
  scenarios: {
    default: {
      executor: "ramping-vus",
      startVUs: 0,
      stages: [
        { duration: "10s", target: 2000 },
        { duration: "10s", target: 2000 },
        { duration: "10s", target: 4000 },
        { duration: "10s", target: 4000 },
        { duration: "10s", target: 6000 },
        { duration: "10s", target: 6000 },
      ],
      gracefulRampDown: "30s",
    },
  },
};

function q(params) {
  const parts = [];
  for (const [k, v] of Object.entries(params)) {
    if (v === undefined || v === null || v === "") continue;
    parts.push(`${encodeURIComponent(k)}=${encodeURIComponent(String(v))}`);
  }
  return parts.length ? `?${parts.join("&")}` : "";
}

function ms(min, max) {
  return Math.floor(Math.random() * (max - min + 1)) + min;
}

function getProducts(params, nameTag) {
  const url = `${BASE_URL}/api/products${q(params)}`;
  const res = http.get(url, { tags: { name: nameTag } });

  check(res, {
    [`${nameTag} status 200`]: (r) => r.status === 200,
    [`${nameTag} success`]: (r) => r.json("success") === true,
    [`${nameTag} items array`]: (r) => Array.isArray(r.json("data.items")),
  });

  return res;
}

// S1: list - no filters, LATEST, page 0
function S1() {
  return getProducts({ page: 0, size: SIZE, sortBy: "LATEST" }, "S1-list");
}

// S2: list - category SHOES
function S2() {
  return getProducts({ category: "SHOES", page: 0, size: SIZE, sortBy: "LATEST" }, "S2-category");
}

// S3: keyword search (name OR brand contains)
function S3() {
  return getProducts({ keyword: "Nike", page: 0, size: SIZE, sortBy: "LATEST" }, "S3-keyword");
}

// S4: complex filter (same as search_scenarios.sql)
function S4() {
  return getProducts(
    {
      category: "SHOES",
      brand: "Nike",
      gender: "MEN",
      color: "BLACK",
      minPrice: 40000,
      maxPrice: 180000,
      page: 0,
      size: SIZE,
      sortBy: "LATEST",
    },
    "S4-complex",
  );
}

// S5: deep page (offset stress)
function S5() {
  return getProducts({ page: 833, size: SIZE, sortBy: "LATEST" }, "S5-deep");
}

// S6: popularity sort
function S6() {
  return getProducts({ page: 0, size: SIZE, sortBy: "POPULARITY" }, "S6-popularity");
}

function runMixed() {
  const r = Math.random();
  if (r < 0.45) return S1();
  if (r < 0.60) return S2();
  if (r < 0.72) return S3();
  if (r < 0.92) return S4();
  if (r < 0.97) return S5();
  return S6();
}

export default function () {
  runMixed();

  sleep(ms(THINK_MIN_MS, THINK_MAX_MS) / 1000);
}