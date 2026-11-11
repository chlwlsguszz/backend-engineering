"use client";

import Image from "next/image";
import Link from "next/link";
import { useEffect, useRef, useState } from "react";
import { useParams } from "next/navigation";
import { createOrder, fetchProductDetail } from "@/features/products/shared/api";
import type { ProductDetail } from "@/features/products/shared/types";
import { CATEGORY_LABEL } from "@/features/products/shared/constants";

const DEMO_MEMBER_ID = 1;

type OrderIntent = { quantity: number; idempotencyKey: string };

function readIntent(storageKey: string): OrderIntent | null {
  try {
    const stored = sessionStorage.getItem(storageKey);
    if (!stored) return null;
    const value = JSON.parse(stored);
    if (!Number.isSafeInteger(value?.quantity) || value.quantity < 1 ||
        typeof value.idempotencyKey !== "string" || value.idempotencyKey.length !== 36) return null;
    return { quantity: value.quantity, idempotencyKey: value.idempotencyKey };
  } catch {
    return null;
  }
}

export default function ProductDetailPage() {
  const { id } = useParams<{ id: string }>();
  return <ProductDetailView key={id} productId={id} />;
}

function ProductDetailView({ productId }: { productId: string }) {
  const storageKey = `marketengine:order-intent:${DEMO_MEMBER_ID}:${productId}`;
  const [product, setProduct] = useState<ProductDetail | null>(null);
  const [loading, setLoading] = useState(true);
  const [message, setMessage] = useState("");
  const [orderQuantity, setOrderQuantity] = useState(1);
  const [ordering, setOrdering] = useState(false);
  const [orderMessage, setOrderMessage] = useState("");
  const [pendingIntent, setPendingIntent] = useState<OrderIntent | null>(null);
  const submitting = useRef(false);

  useEffect(() => {
    const controller = new AbortController();
    async function fetchDetail() {
      const result = await fetchProductDetail(productId, controller.signal);
      if (controller.signal.aborted) return;
      if (!result.data) {
        setMessage(result.errorMessage || "상품 상세 조회 실패");
        setProduct(null);
        setLoading(false);
        return;
      }
      setProduct(result.data);
      const intent = readIntent(storageKey);
      if (intent) {
        setPendingIntent(intent);
        setOrderQuantity(intent.quantity);
        setOrderMessage("처리 결과를 확인할 주문이 있습니다. 같은 주문을 다시 확인하세요.");
      }
      setLoading(false);
    }

    if (productId) {
      void fetchDetail();
    }
    return () => controller.abort();
  }, [productId, storageKey]);

  function finishIntent(intent: OrderIntent) {
    if (readIntent(storageKey)?.idempotencyKey === intent.idempotencyKey) {
      sessionStorage.removeItem(storageKey);
    }
    setPendingIntent(null);
  }

  async function refreshStock() {
    const result = await fetchProductDetail(productId);
    if (result.data) setProduct(result.data);
    else setMessage("최신 재고를 확인하려면 페이지를 새로 고쳐 주세요.");
  }

  async function orderProduct() {
    if (!product || submitting.current) {
      return;
    }
    submitting.current = true;
    setOrdering(true);
    setMessage("");
    setOrderMessage("");
    try {
      const intent = pendingIntent ?? { quantity: orderQuantity, idempotencyKey: crypto.randomUUID() };
      // Persist before sending: a reload after a lost response must replay the committed order.
      sessionStorage.setItem(storageKey, JSON.stringify(intent));
      setPendingIntent(intent);
      const result = await createOrder({
        memberId: DEMO_MEMBER_ID,
        productId: product.id,
        ...intent,
      });
      if (result.data) {
        finishIntent(intent);
        setOrderMessage(`주문 완료! 주문번호: ${result.data.id}`);
        await refreshStock();
      } else if (result.retryable) {
        setOrderMessage("주문 결과를 확인하지 못했습니다. 같은 주문을 다시 확인하세요.");
      } else {
        finishIntent(intent);
        if (result.errorCode === "INSUFFICIENT_STOCK" || result.errorCode === "PRODUCT_NOT_FOR_SALE") {
          setOrderMessage(result.errorCode === "INSUFFICIENT_STOCK"
            ? "주문 가능한 재고가 부족합니다."
            : "판매가 중단된 상품입니다.");
          await refreshStock();
        } else {
          setOrderMessage(result.errorMessage);
        }
      }
    } catch {
      setOrderMessage("브라우저에서 주문 키를 저장하지 못했습니다. 이 사이트의 저장소 설정을 확인하세요.");
    } finally {
      submitting.current = false;
      setOrdering(false);
    }
  }

  return (
    <main className="min-h-screen bg-gradient-to-b from-white via-slate-50 to-white">
      <div className="mx-auto flex w-full max-w-5xl flex-col gap-6 px-6 py-10">
        <header className="flex items-center justify-between gap-3">
          <h1 className="text-2xl font-bold text-slate-900">상품 상세</h1>
          <Link
            href="/"
            className="rounded-lg border border-slate-300 bg-white px-3 py-1.5 text-sm text-slate-700 transition hover:bg-slate-50"
          >
            목록으로
          </Link>
        </header>

        {loading && (
          <section className="rounded-2xl border border-slate-200 bg-white p-6 text-sm text-slate-600 shadow-sm">
            불러오는 중...
          </section>
        )}

        {!loading && message && (
          <section className="rounded-2xl border border-slate-200 bg-white p-6 text-sm text-slate-700 shadow-sm">
            {message}
          </section>
        )}

        {!loading && product && (
          <section className="grid gap-6 rounded-2xl border border-slate-200 bg-white p-6 shadow-sm md:grid-cols-2">
            <Image
              src={`https://placehold.co/900x600/png?text=${product.category}&font=inter`}
              alt={product.name}
              width={900}
              height={600}
              className="h-full min-h-72 w-full rounded-xl border border-slate-200 object-cover"
            />

            <div className="flex flex-col gap-4">
              <div>
                <p className="text-sm font-semibold text-slate-500">{product.brand}</p>
                <h2 className="mt-1 text-2xl font-bold text-slate-900">{product.name}</h2>
                <p className="mt-3 text-2xl font-bold text-slate-900">
                  {Number(product.priceAmount).toLocaleString()}원
                </p>
              </div>

              <p className="rounded-xl bg-slate-50 p-4 text-sm leading-relaxed text-slate-700">
                {product.description}
              </p>

              <div className="grid grid-cols-2 gap-3 text-sm">
                <Info label="카테고리" value={CATEGORY_LABEL[product.category]} />
                <Info label="성별" value={product.gender} />
                <Info label="색상" value={product.color} />
                <Info label="상태" value={product.status} />
                <Info label="재고" value={`${product.stockQuantity}`} />
                <Info label="인기도" value={`${product.popularityScore}`} />
              </div>

              <div className="rounded-xl border border-slate-200 bg-slate-50 p-4">
                <div className="flex flex-wrap items-center gap-3">
                  <label className="text-sm font-medium text-slate-700" htmlFor="order-quantity">
                    수량
                  </label>
                  <input
                    id="order-quantity"
                    type="number"
                    disabled={ordering}
                    min={1}
                    max={Math.max(1, product.stockQuantity)}
                    value={orderQuantity}
                    onChange={(event) => {
                      const value = Number(event.target.value);
                      if (Number.isNaN(value)) {
                        return;
                      }
                      const bounded = Math.min(Math.max(1, Math.trunc(value)), Math.max(1, product.stockQuantity));
                      if (bounded !== orderQuantity && pendingIntent) finishIntent(pendingIntent);
                      setOrderQuantity(bounded);
                    }}
                    className="w-24 rounded-lg border border-slate-300 bg-white px-3 py-2 text-sm text-black"
                  />
                  <button
                    type="button"
                    onClick={() => void orderProduct()}
                    disabled={ordering || (!pendingIntent && (product.stockQuantity <= 0 || product.status !== "ACTIVE" || orderQuantity > product.stockQuantity))}
                    className="rounded-xl bg-slate-900 px-4 py-2 text-sm font-medium text-white transition hover:bg-slate-700 disabled:cursor-not-allowed disabled:opacity-50"
                  >
                    {ordering ? "주문 처리 중..." : pendingIntent ? "같은 주문 다시 확인" : "주문하기"}
                  </button>
                </div>
                <p className="mt-2 text-xs text-slate-500">데모용 주문은 memberId=1 기준으로 생성됩니다.</p>
                {orderMessage && <p className="mt-3 text-sm text-slate-700">{orderMessage}</p>}
              </div>
            </div>
          </section>
        )}
      </div>
    </main>
  );
}

function Info({ label, value }: { label: string; value: string }) {
  return (
    <div className="rounded-lg border border-slate-200 bg-white p-3">
      <p className="text-xs text-slate-500">{label}</p>
      <p className="mt-1 font-medium text-slate-900">{value}</p>
    </div>
  );
}
