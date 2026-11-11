import { expect, test, type APIRequestContext } from "@playwright/test";

async function createSku(request: APIRequestContext, stockQuantity: number) {
  const response = await request.post("/backend/api/products", {
    data: {
      name: `Browser test ${crypto.randomUUID()}`, priceAmount: 12000, description: "Browser smoke test",
      stockQuantity, category: "TOP", brand: "Test", gender: "UNISEX", color: "BLACK", status: "ACTIVE",
      popularityScore: 1,
    },
  });
  expect(response.ok(), await response.text()).toBeTruthy();
  return (await response.json()).data;
}

test.beforeEach(async ({ page }) => {
  // Keep decorative external image services out of checkout and paging tests.
  await page.route("**/_next/image?**", route => route.abort());
});

test("a lost response can be replayed after reload even when the last unit was sold", async ({ page, request }) => {
  const sku = await createSku(request, 1);
  await page.goto(`/products/${sku.id}`);
  let committedId: number | undefined;
  let originalKey: string | undefined;

  await page.route("**/backend/api/orders", async route => {
    originalKey = route.request().postDataJSON().idempotencyKey;
    const committed = await route.fetch();
    expect(committed.ok(), await committed.text()).toBeTruthy();
    committedId = (await committed.json()).data.id;
    await route.abort("failed");
  }, { times: 1 });

  await page.getByRole("button", { name: "주문하기" }).click();
  await expect(page.getByText("주문 결과를 확인하지 못했습니다.", { exact: false })).toBeVisible();
  await page.reload();
  const retry = page.waitForRequest(req => req.url().endsWith("/backend/api/orders") && req.method() === "POST");
  await page.getByRole("button", { name: "같은 주문 다시 확인" }).click();
  expect((await retry).postDataJSON().idempotencyKey).toBe(originalKey);
  await expect(page.getByText(`주문 완료! 주문번호: ${committedId}`)).toBeVisible();
  await expect(page.getByRole("button", { name: "주문하기" })).toBeDisabled();
  const detail = await request.get(`/backend/api/products/${sku.id}`);
  expect((await detail.json()).data.stockQuantity).toBe(0);
});

test("a confirmed purchase refreshes stock and a new purchase gets a new key", async ({ page, request }) => {
  const sku = await createSku(request, 2);
  await page.goto(`/products/${sku.id}`);
  const orders = [];
  for (const remaining of [1, 0]) {
    const reply = page.waitForResponse(r => r.url().endsWith("/backend/api/orders") && r.request().method() === "POST");
    await page.getByRole("button", { name: "주문하기" }).click();
    const order = (await (await reply).json()).data;
    await expect(page.getByText(`주문 완료! 주문번호: ${order.id}`)).toBeVisible();
    await expect(page.getByText("재고", { exact: true }).locator("..")).toContainText(String(remaining));
    orders.push(order);
  }
  expect(orders[0].id).not.toBe(orders[1].id);
  expect(orders[0].idempotencyKey).not.toBe(orders[1].idempotencyKey);
});

test("stock exhaustion shows the business error and refreshes the product", async ({ page, request }) => {
  const sku = await createSku(request, 1);
  await page.goto(`/products/${sku.id}`);
  const buy = page.getByRole("button", { name: "주문하기" });
  await expect(buy).toBeEnabled();
  const purchase = await request.post("/backend/api/orders", { data: {
    productId: sku.id, memberId: 1, quantity: 1, idempotencyKey: crypto.randomUUID(),
  } });
  expect(purchase.ok(), await purchase.text()).toBeTruthy();
  await buy.click();
  await expect(page.getByText("주문 가능한 재고가 부족합니다.")).toBeVisible();
  await expect(buy).toBeDisabled();
  await expect(page.getByText("재고", { exact: true }).locator("..")).toContainText("0");
});

test("an item withdrawn after opening the page refreshes its sale status", async ({ page, request }) => {
  const sku = await createSku(request, 2);
  await page.goto(`/products/${sku.id}`);
  const buy = page.getByRole("button", { name: "주문하기" });
  await expect(buy).toBeEnabled();
  const withdrawal = await request.put(`/backend/api/products/${sku.id}`, { data: {
    ...sku, status: "DISCONTINUED",
  } });
  expect(withdrawal.ok(), await withdrawal.text()).toBeTruthy();
  await buy.click();
  await expect(page.getByText("판매가 중단된 상품입니다.")).toBeVisible();
  await expect(buy).toBeDisabled();
  await expect(page.getByText("DISCONTINUED", { exact: true })).toBeVisible();
});

test("newer filters win and a failed next page retries the same cursor", async ({ page }) => {
  let unblockOld!: () => void;
  const oldHeld = new Promise<void>(resolve => { unblockOld = resolve; });
  let oldRequested!: () => void;
  const oldStarted = new Promise<void>(resolve => { oldRequested = resolve; });
  let failedOnce = false;
  const nextPages: string[] = [];
  await page.route("**/backend/api/products?**", async route => {
    const params = new URL(route.request().url()).searchParams;
    const keyword = params.get("keyword") ?? "initial";
    const cursor = params.get("after");
    const pageNumber = cursor ? "1" : params.get("page")!;
    if (keyword === "slow") {
      oldRequested();
      await oldHeld;
    }
    if (keyword === "latest" && pageNumber !== "0") {
      nextPages.push(cursor ?? "missing-cursor");
      if (!failedOnce) {
        failedOnce = true;
        await route.fulfill({ status: 503, json: { success: false, error: { message: "테스트 재시도" } } });
        return;
      }
    }
    const item = {
      id: Number(pageNumber) + 1, name: `${keyword}-p${pageNumber}`, priceAmount: 100,
      available: true, category: "TOP", brand: "Test", color: "BLACK", gender: "UNISEX",
      popularityScore: 1, createdAt: "2026-11-01T00:00:00Z",
    };
    await route.fulfill({ json: { success: true, data: {
      items: [item], page: 0, size: 12, hasNext: pageNumber === "0",
      nextCursor: pageNumber === "0" ? `${keyword}-cursor` : null,
    } } });
  });
  await page.goto("/");
  await expect(page.getByRole("heading", { name: "initial-p0" })).toBeVisible();
  const search = page.getByPlaceholder("검색어를 입력하세요");
  await search.fill("slow");
  await page.getByRole("button", { name: "검색", exact: true }).click();
  await oldStarted;
  await search.fill("latest");
  await page.getByRole("button", { name: "검색", exact: true }).click();
  await expect(page.getByRole("heading", { name: "latest-p0" })).toBeVisible();
  unblockOld();
  await page.getByRole("button", { name: "더 보기" }).click();
  await expect(page.getByText("테스트 재시도")).toBeVisible();
  await page.getByRole("button", { name: "더 보기" }).click();
  await expect(page.getByRole("heading", { name: "latest-p1" })).toBeVisible();
  await expect(page.getByRole("heading", { name: "latest-p0" })).toBeVisible();
  await expect(page.getByRole("heading", { name: "slow-p0" })).toHaveCount(0);
  expect(nextPages).toEqual(["latest-cursor", "latest-cursor"]);
});
