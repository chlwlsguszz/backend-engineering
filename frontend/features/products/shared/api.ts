import { PAGE_SIZE } from "./constants";
import type {
  ApiEnvelope,
  OrderResponse,
  ProductDetail,
  ProductFilterState,
  ProductPageResponse,
} from "./types";

type ApiResult<T> = { data: T | null; errorMessage: string };

async function requestBackend<T>(url: string, init?: RequestInit): Promise<ApiResult<T>> {
  try {
    const response = await fetch(url, init);
    const body: ApiEnvelope<T> = await response.json();

    if (!response.ok || !body.success) {
      return { data: null, errorMessage: body.error?.message ?? "요청 처리 실패" };
    }

    return { data: body.data, errorMessage: "" };
  } catch {
    return { data: null, errorMessage: "백엔드 연결 실패: 서버가 실행 중인지 확인하세요." };
  }
}

export async function fetchProductPage(
  page: number,
  filters: ProductFilterState,
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

  return requestBackend<ProductPageResponse>(`/backend/api/products?${params.toString()}`);
}

export async function fetchProductDetail(productId: string): Promise<ApiResult<ProductDetail>> {
  return requestBackend<ProductDetail>(`/backend/api/products/${productId}`);
}

export async function createOrder(payload: {
  memberId: number;
  productId: number;
  quantity: number;
}): Promise<ApiResult<OrderResponse>> {
  return requestBackend<OrderResponse>("/backend/api/orders", {
    method: "POST",
    headers: {
      "Content-Type": "application/json",
    },
    body: JSON.stringify(payload),
  });
}
