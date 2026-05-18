import http from "k6/http";
import { check, sleep } from "k6";

/**
 * /api/products 부하 — 시나리오 3종 (병렬 실행, VU 비율로 빈도 조절)
 *
 * 1) simple        — 단순 조회(목록·카테고리·정렬만)
 * 2) complex       — 필터 조합 랜덤 + 프로필별 가중치(실사용에 가깝게)
 * 3) deep          — 아주 깊은 page(offset 스트레스), 일부는 필터+깊은 페이지
 *
 * 실행 예:
 *   k6 run scripts/k6/search.js
 *   k6 run -e DURATION=5m -e SIMPLE_VUS=20 -e COMPLEX_VUS=8 -e DEEP_VUS=4 scripts/k6/search.js
 *   k6 run -e DEEP_PAGE_MIN=10000 -e DEEP_PAGE_MAX=800000 scripts/k6/search.js
 */

const BASE_URL = __ENV.BASE_URL || "http://localhost:8080";
const SIZE = Number(__ENV.SIZE || 12);
const THINK_MIN_MS = Number(__ENV.THINK_MIN_MS || 200);
const THINK_MAX_MS = Number(__ENV.THINK_MAX_MS || 900);

const SIMPLE_VUS = Number(__ENV.SIMPLE_VUS || 12);
const COMPLEX_VUS = Number(__ENV.COMPLEX_VUS || 4);
const DEEP_VUS = Number(__ENV.DEEP_VUS || 2);
const DURATION = __ENV.DURATION || "3m";

/** 10M rows, size 12 → max page ≈ 833_332. 기본은 그보다 약간 여유 있게 상한 설정 */
const DEEP_PAGE_MIN = Number(__ENV.DEEP_PAGE_MIN || 8000);
const DEEP_PAGE_MAX = Number(__ENV.DEEP_PAGE_MAX || 820000);

const CATEGORIES = ["TOP", "BOTTOM", "OUTER", "SHOES", "GLASSES", "HAT"];
const BRANDS = [
  "Nike",
  "Adidas",
  "Puma",
  "New Balance",
  "Under Armour",
  "Converse",
  "Reebok",
  "Fila",
  "Asics",
  "Lululemon",
  "Jordan",
  "Vans",
  "Skechers",
  "Champion",
  "Levis",
  "Patagonia",
  "The North Face",
  "Columbia",
  "Oakley",
  "Carhartt",
];
const COLORS = ["BLACK", "WHITE", "NAVY", "GRAY", "BEIGE", "RED", "BLUE", "GREEN"];
const GENDERS = ["MEN", "WOMEN", "UNISEX"];
const KEYWORDS = [
  "Nike",
  "Adidas",
  "Hoodie",
  "T-Shirt",
  "SHOES",
  "MEN",
  "WOMEN",
  "Carhartt",
  "North Face",
  "Oakley",
  "Jacket",
  "Running",
];

export const options = {
  scenarios: {
    simple: {
      executor: "constant-vus",
      vus: SIMPLE_VUS,
      duration: DURATION,
      gracefulStop: "30s",
      exec: "simpleBrowse",
      tags: { scenario: "simple" },
    },
    complex_filters: {
      executor: "constant-vus",
      vus: COMPLEX_VUS,
      duration: DURATION,
      gracefulStop: "30s",
      exec: "complexFiltersRandom",
      tags: { scenario: "complex" },
    },
    deep_page: {
      executor: "constant-vus",
      vus: DEEP_VUS,
      duration: DURATION,
      gracefulStop: "30s",
      exec: "deepPaging",
      tags: { scenario: "deep" },
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

function pick(arr) {
  return arr[Math.floor(Math.random() * arr.length)];
}

function randInt(min, max) {
  return Math.floor(Math.random() * (max - min + 1)) + min;
}

/** 가격대: 시드(V4)에서 자주 쓰는 중가 + 가끔 저가/고가 */
function randomPriceBand() {
  const r = Math.random();
  if (r < 0.55) return { minPrice: 40000, maxPrice: 180000 };
  if (r < 0.8) return { minPrice: 10000, maxPrice: 50000 };
  if (r < 0.93) return { minPrice: 150000, maxPrice: 350000 };
  return { minPrice: 20000, maxPrice: 400000 };
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

function think() {
  sleep(ms(THINK_MIN_MS, THINK_MAX_MS) / 1000);
}

/** 1) 단순 조회 — 대부분 홈/목록, 가끔 카테고리·인기순 */
export function simpleBrowse() {
  const r = Math.random();
  if (r < 0.68) {
    getProducts({ page: 0, size: SIZE, sortBy: "LATEST" }, "simple-latest");
  } else if (r < 0.88) {
    getProducts({ page: 0, size: SIZE, sortBy: "LATEST", category: pick(CATEGORIES) }, "simple-category");
  } else if (r < 0.96) {
    getProducts({ page: 0, size: SIZE, sortBy: "POPULARITY" }, "simple-popularity");
  } else {
    getProducts({ page: 0, size: SIZE, sortBy: "POPULARITY", category: pick(CATEGORIES) }, "simple-cat-pop");
  }
  think();
}

/**
 * 2) 복잡한 필터 — 프로필별 가중치(자주 쓰는 조합은 더 자주).
 *    각 이터레이션에서 하나의 프로필만 선택.
 */
export function complexFiltersRandom() {
  const sortBy = Math.random() < 0.88 ? "LATEST" : "POPULARITY";
  const base = { page: 0, size: SIZE, sortBy };
  const u = Math.random();
  let tag = "complex";

  if (u < 0.2) {
    tag = "complex-kw-only";
    Object.assign(base, { keyword: pick(KEYWORDS) });
  } else if (u < 0.34) {
    tag = "complex-cat-only";
    Object.assign(base, { category: pick(CATEGORIES) });
  } else if (u < 0.46) {
    tag = "complex-cat-brand";
    Object.assign(base, { category: pick(CATEGORIES), brand: pick(BRANDS) });
  } else if (u < 0.56) {
    tag = "complex-cat-gender-color";
    Object.assign(base, {
      category: pick(CATEGORIES),
      gender: pick(GENDERS),
      color: pick(COLORS),
    });
  } else if (u < 0.64) {
    tag = "complex-price";
    Object.assign(base, randomPriceBand());
  } else if (u < 0.72) {
    tag = "complex-kw-cat";
    Object.assign(base, { keyword: pick(KEYWORDS), category: pick(CATEGORIES) });
  } else if (u < 0.8) {
    tag = "complex-brand-gender";
    Object.assign(base, { brand: pick(BRANDS), gender: pick(GENDERS) });
  } else if (u < 0.88) {
    tag = "complex-stack";
    Object.assign(base, {
      category: pick(CATEGORIES),
      brand: pick(BRANDS),
      gender: pick(GENDERS),
      color: pick(COLORS),
      ...randomPriceBand(),
    });
  } else if (u < 0.94) {
    tag = "complex-kw-price";
    Object.assign(base, { keyword: pick(KEYWORDS), ...randomPriceBand() });
  } else {
    tag = "complex-full";
    Object.assign(base, {
      keyword: pick(KEYWORDS),
      category: pick(CATEGORIES),
      brand: pick(BRANDS),
      gender: pick(GENDERS),
      color: pick(COLORS),
      ...randomPriceBand(),
    });
  }

  getProducts(base, tag);
  think();
}

/** 3) 깊은 페이지 — 기본은 무필터 깊은 offset, 일부는 필터+깊은 페이지 */
export function deepPaging() {
  const lo = Math.min(DEEP_PAGE_MIN, DEEP_PAGE_MAX);
  const hi = Math.max(DEEP_PAGE_MIN, DEEP_PAGE_MAX);
  const page = randInt(lo, hi);
  const sortBy = Math.random() < 0.82 ? "LATEST" : "POPULARITY";

  const u = Math.random();
  if (u < 0.12) {
    getProducts(
      {
        page,
        size: SIZE,
        sortBy,
        category: pick(CATEGORIES),
        brand: pick(BRANDS),
      },
      "deep-cat-brand",
    );
  } else if (u < 0.2) {
    getProducts(
      {
        page,
        size: SIZE,
        sortBy,
        keyword: pick(KEYWORDS),
      },
      "deep-keyword",
    );
  } else {
    getProducts({ page, size: SIZE, sortBy }, "deep-plain");
  }
  think();
}
