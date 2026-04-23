"use client";

import { FormEvent, useMemo, useState } from "react";

type Product = {
  id: number;
  name: string;
  priceAmount: number;
  stockQuantity: number;
};

type ApiEnvelope<T> = {
  success: boolean;
  data: T;
  error?: {
    code: string;
    message: string;
  };
};

const INITIAL_ORDER_FORM = {
  memberId: "1",
  productId: "",
  quantity: "1",
};

export default function Home() {
  const [products, setProducts] = useState<Product[]>([]);
  const [keyword, setKeyword] = useState("");
  const [loadingProducts, setLoadingProducts] = useState(false);
  const [orderForm, setOrderForm] = useState(INITIAL_ORDER_FORM);
  const [resultMessage, setResultMessage] = useState("");

  const filteredProducts = useMemo(() => {
    const normalized = keyword.trim().toLowerCase();
    if (!normalized) return products;
    return products.filter((product) =>
      product.name.toLowerCase().includes(normalized),
    );
  }, [products, keyword]);

  async function fetchProducts() {
    setLoadingProducts(true);
    setResultMessage("");
    try {
      const response = await fetch("/backend/api/products");
      const body: ApiEnvelope<Product[]> = await response.json();
      if (!response.ok || !body.success) {
        setResultMessage(body.error?.message ?? "상품 조회 실패");
        return;
      }
      setProducts(body.data);
      if (body.data.length > 0 && !orderForm.productId) {
        setOrderForm((prev) => ({ ...prev, productId: String(body.data[0].id) }));
      }
    } catch {
      setResultMessage("백엔드 연결 실패: 서버가 실행 중인지 확인하세요.");
    } finally {
      setLoadingProducts(false);
    }
  }

  async function handleCreateOrder(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    setResultMessage("");
    try {
      const response = await fetch("/backend/api/orders", {
        method: "POST",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify({
          memberId: Number(orderForm.memberId),
          productId: Number(orderForm.productId),
          quantity: Number(orderForm.quantity),
        }),
      });
      const body: ApiEnvelope<{
        id: number;
        totalAmount: number;
        status: string;
      }> = await response.json();

      if (!response.ok || !body.success) {
        setResultMessage(`주문 실패: ${body.error?.message ?? "알 수 없는 오류"}`);
        return;
      }

      setResultMessage(
        `주문 성공 - orderId=${body.data.id}, total=${body.data.totalAmount}, status=${body.data.status}`,
      );
    } catch {
      setResultMessage("주문 요청 실패: 백엔드 상태를 확인하세요.");
    }
  }

  return (
    <main className="mx-auto flex min-h-screen w-full max-w-5xl flex-col gap-8 px-6 py-10">
      <header>
        <h1 className="text-2xl font-bold">Market Engine Frontend (Minimal)</h1>
        <p className="mt-2 text-sm text-gray-600">
          상품 조회/검색 및 주문 API 호출을 위한 최소 검증 화면
        </p>
      </header>

      <section className="rounded-lg border p-4">
        <div className="flex flex-wrap items-center gap-3">
          <button
            onClick={() => void fetchProducts()}
            className="rounded bg-black px-4 py-2 text-sm text-white"
            type="button"
          >
            상품 목록 새로고침
          </button>
          <input
            value={keyword}
            onChange={(event) => setKeyword(event.target.value)}
            placeholder="상품명 검색"
            className="w-60 rounded border px-3 py-2 text-sm"
          />
          {loadingProducts && <span className="text-sm text-gray-500">로딩 중...</span>}
        </div>

        <div className="mt-4 overflow-x-auto">
          <table className="w-full text-left text-sm">
            <thead>
              <tr className="border-b">
                <th className="px-2 py-2">ID</th>
                <th className="px-2 py-2">상품명</th>
                <th className="px-2 py-2">가격</th>
                <th className="px-2 py-2">재고</th>
              </tr>
            </thead>
            <tbody>
              {filteredProducts.map((product) => (
                <tr key={product.id} className="border-b">
                  <td className="px-2 py-2">{product.id}</td>
                  <td className="px-2 py-2">{product.name}</td>
                  <td className="px-2 py-2">{product.priceAmount}</td>
                  <td className="px-2 py-2">{product.stockQuantity}</td>
                </tr>
              ))}
              {filteredProducts.length === 0 && (
                <tr>
                  <td className="px-2 py-4 text-gray-500" colSpan={4}>
                    조회된 상품이 없습니다.
                  </td>
                </tr>
              )}
            </tbody>
          </table>
        </div>
      </section>

      <section className="rounded-lg border p-4">
        <h2 className="mb-3 text-lg font-semibold">주문 생성</h2>
        <form className="grid gap-3 md:grid-cols-4" onSubmit={handleCreateOrder}>
          <input
            className="rounded border px-3 py-2 text-sm"
            value={orderForm.memberId}
            onChange={(event) =>
              setOrderForm((prev) => ({ ...prev, memberId: event.target.value }))
            }
            placeholder="memberId"
          />
          <input
            className="rounded border px-3 py-2 text-sm"
            value={orderForm.productId}
            onChange={(event) =>
              setOrderForm((prev) => ({ ...prev, productId: event.target.value }))
            }
            placeholder="productId"
          />
          <input
            className="rounded border px-3 py-2 text-sm"
            value={orderForm.quantity}
            onChange={(event) =>
              setOrderForm((prev) => ({ ...prev, quantity: event.target.value }))
            }
            placeholder="quantity"
          />
          <button className="rounded bg-blue-600 px-4 py-2 text-sm text-white" type="submit">
            주문 생성
          </button>
        </form>
      </section>

      {resultMessage && (
        <section className="rounded-lg border border-gray-300 bg-gray-50 p-3 text-sm">
          {resultMessage}
        </section>
      )}
    </main>
  );
}
