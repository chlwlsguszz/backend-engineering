import { PAGE_SIZE } from "./constants";
import type {
  ApiEnvelope,
  OrderResponse,
  ProductDetail,
  ProductFilterState,
  ProductPageResponse,
} from "./types";

type ApiResult<T> =
  | { data: T; errorMessage: ""; errorCode?: undefined; retryable?: undefined }
  | { data: null; errorMessage: string; errorCode?: string; retryable: boolean };

async function requestBackend<T>(url: string, init?: RequestInit): Promise<ApiResult<T>> {
  try {
    const response = await fetch(url, init);
    const body: ApiEnvelope<T> = await response.json();

    if (!body.success || !response.ok) {
      const error = !body.success ? body.error : undefined;
      return {
        data: null,
        errorCode: error?.code,
        errorMessage: error?.message ?? "요청 처리 실패",
        retryable: response.status >= 500 || response.status === 408 || response.status === 429 || error?.code === "CONFLICT",
      };
    }

    return { data: body.data, errorMessage: "" };
  } catch {
    return { data: null, errorMessage: "서버 응답을 확인하지 못했습니다.", retryable: true };
  }
}

export async function fetchProductPage(
  page: number,
  filters: ProductFilterState,
  signal?: AbortSignal,
): Promise<ApiResult<ProductPageResponse>> {
  const params = new URLSearchParams({
    page: String(page),
    size: String(PAGE_SIZE),
    sortBy: filters.sortBy,
  });

  if (filters.keyword.trim() !== "") params.set("keyword", filters.keyword.trim());
  if (filters.category !== "ALL") params.set("category", filters.category);
  if (filters.brand !== "ALL") params.set("brand", filters.brand);
  if (filters.gender !== "ALL") params.set("gender", filters.gender);
  if (filters.color !== "ALL") params.set("color", filters.color);
  if (filters.minPrice.trim() !== "") params.set("minPrice", filters.minPrice.trim());
  if (filters.maxPrice.trim() !== "") params.set("maxPrice", filters.maxPrice.trim());

  return requestBackend<ProductPageResponse>(`/backend/api/products?${params.toString()}`, { signal });
}

export async function fetchProductDetail(productId: string, signal?: AbortSignal): Promise<ApiResult<ProductDetail>> {
  return requestBackend<ProductDetail>(`/backend/api/products/${productId}`, { signal, cache: "no-store" });
}

export async function createOrder(payload: {
  memberId: number;
  productId: number;
  quantity: number;
  idempotencyKey: string;
}): Promise<ApiResult<OrderResponse>> {
  return requestBackend<OrderResponse>("/backend/api/orders", {
    method: "POST",
    headers: {
      "Content-Type": "application/json",
    },
    body: JSON.stringify(payload),
  });
}
