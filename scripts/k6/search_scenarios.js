import { check, sleep } from "k6";
import exec from "k6/execution";
import http from "k6/http";
import { Counter, Gauge, Trend } from "k6/metrics";

// RATE counts new browsing sessions per second. A session can request several pages.
const CASE = __ENV.CASE || "feed";
const isSweep = CASE === "feed-sweep";
const isFeedWarmup = CASE === "feed" && __ENV.FEED_WARMUP === "1";
const feedQueries = {
  home_latest: {},
  top_latest: { category: "TOP" },
  home_popular: { sortBy: "POPULARITY" },
};
const warmupResponses = isFeedWarmup ? new Counter("warmup_valid_responses") : null;
const BASE_URL = (__ENV.BASE_URL || "http://localhost:8080").replace(/\/$/, "");
const PAGE_SIZE = 12;
const RATE = integerOption("RATE", isSweep ? 10000 : 5, 1, 15000);
const RAMP_SECONDS = integerOption("RAMP_SECONDS", isSweep ? 200 : 0, isSweep ? 1 : 0, 1800);
const BAND_RPS = isSweep ? integerOption("BAND_RPS", 1000, 1, RATE) : 0;
const MAX_PAGE = integerOption("MAX_PAGE", 8, 3, 20);
const MODEL_COUNT = integerOption("MODEL_COUNT", 2500000, 1, 2500000);

function integerOption(name, fallback, min, max) {
  const value = Number(__ENV[name] ?? fallback);
  if (!Number.isInteger(value) || value < min || value > max) {
    throw new Error(`${name} must be an integer between ${min} and ${max}`);
  }
  return value;
}

if (!["feed", "feed-sweep", "discovery", "browse", "pagination"].includes(CASE)) {
  throw new Error("CASE must be feed, feed-sweep, discovery, browse or pagination");
}
if (RAMP_SECONDS > 0 && CASE !== "feed" && !isSweep) {
  throw new Error("RAMP_SECONDS is supported for feed scenarios only.");
}
if (isSweep && (RATE % BAND_RPS !== 0 || RATE / BAND_RPS > 25)) {
  throw new Error("RATE must divide evenly into at most 25 bands of BAND_RPS.");
}

const maxVUs = integerOption("MAX_VUS", isSweep ? 2000 : 100, 10, 5000);
const allocatedVUs = integerOption("PREALLOCATED_VUS", isSweep ? Math.min(100, maxVUs) : 10, 1, maxVUs);
// Windows PowerShell 5.1 writes a UTF-8 BOM in settings.json.
const sweepSettings = isSweep && __ENV.SWEEP_SETTINGS
  ? JSON.parse(open(__ENV.SWEEP_SETTINGS).replace(/^\uFEFF/, ""))
  : null;
const holdDuration = sweepSettings?.HoldSeconds != null ? `${sweepSettings.HoldSeconds}s` : (__ENV.DURATION || "30s");
const hasHold = !isSweep || holdDuration !== "0s";

function loadRampPoints() {
  const rampEndMs = RAMP_SECONDS * 1000;
  if (!sweepSettings) return [{ EndMs: rampEndMs, TargetRps: RATE }];

  const points = sweepSettings.RampStages;
  if (!Array.isArray(points) || !points.length || sweepSettings.BandRps !== BAND_RPS) {
    throw new Error("SWEEP_SETTINGS must contain RampStages and the configured BandRps.");
  }
  let previousMs = 0;
  let previousRps = 0;
  for (const point of points) {
    if (!point || !Number.isSafeInteger(point.EndMs) || point.EndMs <= previousMs ||
        !Number.isSafeInteger(point.TargetRps) || point.TargetRps <= previousRps) {
      throw new Error("Each ramp stage must increase both EndMs and TargetRps.");
    }
    previousMs = point.EndMs;
    previousRps = point.TargetRps;
  }
  if (previousMs !== rampEndMs || previousRps !== RATE) {
    throw new Error("The final ramp stage must match RAMP_SECONDS and RATE.");
  }
  return points;
}

const rampPoints = loadRampPoints();
const schedule = RAMP_SECONDS > 0
  ? {
      executor: "ramping-arrival-rate",
      startRate: isSweep ? 0 : Math.min(50, RATE),
      stages: [
        ...rampPoints.map((point, index) => ({
          duration: `${point.EndMs - (index === 0 ? 0 : rampPoints[index - 1].EndMs)}ms`,
          target: point.TargetRps,
        })),
        ...(hasHold ? [{ duration: holdDuration, target: RATE }] : []),
      ],
    }
  : { executor: "constant-arrival-rate", rate: RATE, duration: holdDuration };
const load = {
  ...schedule,
  timeUnit: "1s",
  preAllocatedVUs: allocatedVUs,
  maxVUs,
};

const thresholds = isSweep ? {} : { checks: ["rate==1"] };
if (isSweep) {
  // Empty threshold lists register submetrics in the k6 summary. The sweep report
  // evaluates them after the full ramp, including later, potentially saturated bands.
  const metrics = [
    "http_reqs", "http_req_duration", "http_req_failed", "checks", "sweep_initialized_vus", "sweep_active_vus",
  ];
  const bands = Array.from({ length: RATE / BAND_RPS }, (_, i) => `b${i}`);
  if (hasHold) bands.push("hold");
  for (const band of bands) {
    for (const metric of metrics) thresholds[`${metric}{band:${band}}`] = [];
  }
  // A dropped iteration never enters feed() to receive a band tag.
  thresholds.dropped_iterations = [];
} else if (isFeedWarmup) {
  thresholds.dropped_iterations = [];
  for (const variant of Object.keys(feedQueries)) {
    thresholds[`warmup_valid_responses{feed:${variant}}`] = ["count>0"];
  }
} else if (CASE !== "pagination") {
  // k6 records drops in the executor, before a VU can add a phase tag.
  thresholds.dropped_iterations = ["count==0"];
  if (RAMP_SECONDS > 0) thresholds["http_reqs{phase:hold}"] = ["count>0"];
  if (__ENV.P99_MS) {
    const metric = RAMP_SECONDS > 0 ? "http_req_duration{phase:hold}" : "http_req_duration";
    thresholds[metric] = [`p(99)<=${integerOption("P99_MS", 200, 1, 60000)}`];
  }
}

export const options = {
  scenarios: {
    [isSweep ? "feed_sweep" : CASE]: CASE === "pagination"
      ? { executor: "shared-iterations", vus: 1, iterations: 1, exec: CASE }
      : { ...load, exec: isSweep ? "feed" : CASE },
  },
  systemTags: ["scenario", "name", "status", "method", "check", "expected_response", "error_code"],
  summaryTrendStats: ["avg", "p(95)", "p(99)", "max"],
  thresholds,
};

// Also expose these groups in the local terminal summary without a metrics backend.
const groupDurations = {};
const latencyGroups = {
  browse: ["first", "page_1_2", "page_3_plus"],
  discovery: ["brand", "collection", "model", "no_results"],
};
for (const group of latencyGroups[CASE] || []) {
  groupDurations[group] = new Trend(`search_${group}_duration`, true);
}

function readPage(filters, page, traffic) {
  const query = Object.entries({ sortBy: "LATEST", ...filters, size: PAGE_SIZE, page })
    .map(([key, value]) => `${key}=${encodeURIComponent(value)}`)
    .join("&");
  const depth = page === 0 ? "first" : page <= 2 ? "page_1_2" : "page_3_plus";
  const tags = { traffic, depth };
  const response = http.get(`${BASE_URL}/api/products?${query}`, {
    tags: { ...tags, name: "GET /api/products" },
  });
  const latency = groupDurations[CASE === "browse" ? depth : traffic];
  if (latency) latency.add(response.timings.duration);
  if (!check(response, { "HTTP 200": (r) => r.status === 200 }, tags)) return null;
  let body;
  try {
    body = response.json();
  } catch (_) {
    // A proxy can return HTML with status 200. Count it as a failed contract check.
  }
  const data = body && body.data;
  const valid = check(data, {
    "valid product slice": (d) =>
      body && body.success === true && d && d.page === page && d.size === PAGE_SIZE &&
      typeof d.hasNext === "boolean" && Array.isArray(d.items) && d.items.length <= PAGE_SIZE &&
      d.items.every((p) => p && Number.isSafeInteger(p.id) && p.id > 0),
  }, tags);
  return valid ? data : null;
}

const initializedVUs = isSweep ? new Gauge("sweep_initialized_vus") : null;
const activeVUs = isSweep ? new Gauge("sweep_active_vus") : null;
let lastSampledBand;

function sweepBandAt(elapsedMs) {
  let startMs = 0;
  let startRps = 0;
  for (const point of rampPoints) {
    if (elapsedMs < point.EndMs) {
      const currentRps = startRps + (elapsedMs - startMs) * (point.TargetRps - startRps) / (point.EndMs - startMs);
      return `b${Math.floor(currentRps / BAND_RPS)}`;
    }
    startMs = point.EndMs;
    startRps = point.TargetRps;
  }
  // An iteration dispatched just before shutdown can enter JS at the final boundary.
  return hasHold ? "hold" : `b${RATE / BAND_RPS - 1}`;
}

function markSweepBand(elapsedMs) {
  const band = sweepBandAt(elapsedMs);
  exec.vu.metrics.tags.band = band;
  // Sample on each VU's first iteration in a band and periodically thereafter.
  if (lastSampledBand !== band || exec.scenario.iterationInTest % 128 === 0) {
    initializedVUs.add(exec.instance.vusInitialized);
    activeVUs.add(exec.instance.vusActive);
    lastSampledBand = band;
  }
}

export function feed() {
  if (RAMP_SECONDS > 0) {
    const elapsedMs = Math.max(0, Date.now() - exec.scenario.startTime);
    if (isSweep) {
      markSweepBand(elapsedMs);
    } else {
      exec.vu.metrics.tags.phase = elapsedMs < RAMP_SECONDS * 1000 ? "ramp" : "hold";
    }
  }
  // Keep this sequence identical for cache-enabled and cache-disabled runs.
  const slot = exec.scenario.iterationInTest % 10;
  let variant = "home_latest";
  if (slot === 9) variant = "home_popular";
  else if (slot >= 6) variant = "top_latest";
  const data = readPage(feedQueries[variant], 0, "feed");
  if (!data) return;
  const valid = check(data, { "feed has another page": (d) => d.items.length === PAGE_SIZE && d.hasNext });
  if (warmupResponses && valid) warmupResponses.add(1, { feed: variant });
}

export function discovery() {
  const index = exec.scenario.iterationInTest;
  const slot = index % 10;
  let traffic, keyword;
  if (slot < 4) {
    traffic = "brand";
    keyword = ["Nike", "Adidas", "Puma", "Patagonia"][slot];
  } else if (slot === 4) {
    traffic = "collection";
    keyword = "Drop000001";
  } else if (slot < 9) {
    traffic = "model";
    // Spread short runs across the catalog. This stride is coprime to both B9 reference sizes.
    const ordinal = Math.floor(index / 10) * 4 + slot - 5;
    const model = 1 + ((ordinal % MODEL_COUNT) * 104729) % MODEL_COUNT;
    keyword = "ME" + String(model).padStart(7, "0");
  } else {
    traffic = "no_results";
    keyword = "ME0000000";
  }
  const data = readPage({ keyword }, 0, traffic);
  if (!data) return;
  if (traffic === "no_results") {
    check(data, { "absent model has zero hits": (d) => !d.hasNext && d.items.length === 0 });
  } else if (traffic === "model") {
    check(data, {
      "model has its own 1 to 7 variants": (d) =>
        !d.hasNext && d.items.length >= 1 && d.items.length <= 7 &&
        d.items.every((p) => typeof p.name === "string" && p.name.split(" ").includes(keyword)),
    });
  } else {
    check(data, { "broad query fills first page": (d) => d.items.length === PAGE_SIZE && d.hasNext });
  }
}

export function browse() {
  const index = exec.scenario.iterationInTest;
  const lastPage = [0, 0, 1, 1, 2, MAX_PAGE][index % 6];
  const filters = Math.floor(index / 6) % 2 === 0 ? { category: "TOP" } : { keyword: "Nike" };
  for (let page = 0; page <= lastPage; page++) {
    const data = readPage(filters, page, "browse");
    if (!data) return;
    if (!check(data, { "browse page has results": (d) => d.items.length > 0 })) return;
    if (!data.hasNext || page === lastPage) return;
    sleep(0.5);
  }
}

export function pagination() {
  for (const sortBy of ["LATEST", "POPULARITY"]) {
    const filters = { keyword: "StudioCapsule", sortBy };
    const first = readPage(filters, 0, "capsule");
    if (!first || !check(first, {
      "capsule first page has 12 and a next page": (d) => d.items.length === 12 && d.hasNext,
    }, { sort: sortBy })) continue;
    const last = readPage(filters, 1, "capsule");
    if (!last) continue;
    const ids = new Set([...first.items, ...last.items].map((p) => p.id));
    check(last, {
      "capsule last page has one and ends": (d) => d.items.length === 1 && !d.hasNext,
      "capsule returns exactly seeded IDs 1 through 13": () =>
        ids.size === 13 && [...ids].every((id) => id >= 1 && id <= 13),
    }, { sort: sortBy });
  }
}
