"use client";

import Image from "next/image";
import { useMemo, useState } from "react";

type Product = {
  id: number;
  name: string;
  priceAmount: number;
  stockQuantity: number;
  category: "TOP" | "BOTTOM" | "OUTER" | "SHOES" | "GLASSES" | "HAT";
  brand: string;
  color: string;
  gender: string;
  popularityScore: number;
  createdAt: string;
};

type ApiEnvelope<T> = {
  success: boolean;
  data: T;
  error?: {
    code: string;
    message: string;
  };
};

export default function Home() {
  const [products, setProducts] = useState<Product[]>([]);
  const [searchInput, setSearchInput] = useState("");
  const [keyword, setKeyword] = useState("");
  const [selectedCategory, setSelectedCategory] = useState("ALL");
  const [isFilterOpen, setIsFilterOpen] = useState(false);
  const [sortBy, setSortBy] = useState<"VIEW" | "POPULARITY">("VIEW");
  const [selectedGender, setSelectedGender] = useState("ALL");
  const [selectedColor, setSelectedColor] = useState("ALL");
  const [minPrice, setMinPrice] = useState("");
  const [maxPrice, setMaxPrice] = useState("");
  const [currentPage, setCurrentPage] = useState(1);
  const [loadingProducts, setLoadingProducts] = useState(false);
  const [resultMessage, setResultMessage] = useState("");

  const pageSize = 12;

  const categories = useMemo(
    () => ["ALL", "TOP", "BOTTOM", "OUTER", "SHOES", "GLASSES", "HAT"] as const,
    [],
  );

  const genders = useMemo(
    () => ["ALL", ...Array.from(new Set(products.map((product) => product.gender)))],
    [products],
  );

  const colors = useMemo(
    () => ["ALL", ...Array.from(new Set(products.map((product) => product.color)))],
    [products],
  );

  const categoryLabel: Record<(typeof categories)[number], string> = {
    ALL: "전체",
    TOP: "상의",
    BOTTOM: "하의",
    OUTER: "아우터",
    SHOES: "신발",
    GLASSES: "안경",
    HAT: "모자",
  };

  const searchedProducts = useMemo(() => {
    const normalized = keyword.trim().toLowerCase();
    const min = minPrice.trim() === "" ? null : Number(minPrice);
    const max = maxPrice.trim() === "" ? null : Number(maxPrice);

    return products.filter((product) => {
      const matchedKeyword =
        normalized.length === 0 ||
        product.name.toLowerCase().includes(normalized) ||
        product.brand.toLowerCase().includes(normalized);
      const matchedCategory =
        selectedCategory === "ALL" || product.category === selectedCategory;
      const matchedGender = selectedGender === "ALL" || product.gender === selectedGender;
      const matchedColor = selectedColor === "ALL" || product.color === selectedColor;
      const matchedMin = min === null || Number(product.priceAmount) >= min;
      const matchedMax = max === null || Number(product.priceAmount) <= max;
      return (
        matchedKeyword &&
        matchedCategory &&
        matchedGender &&
        matchedColor &&
        matchedMin &&
        matchedMax
      );
    });
  }, [products, keyword, selectedCategory, selectedGender, selectedColor, minPrice, maxPrice]);

  const filteredProducts = useMemo(() => {
    const cloned = [...searchedProducts];
    if (sortBy === "POPULARITY") {
      cloned.sort((a, b) => b.popularityScore - a.popularityScore || b.id - a.id);
      return cloned;
    }
    cloned.sort(
      (a, b) =>
        new Date(b.createdAt).getTime() - new Date(a.createdAt).getTime() || b.id - a.id,
    );
    return cloned;
  }, [searchedProducts, sortBy]);

  function applyKeywordSearch() {
    setKeyword(searchInput);
    setCurrentPage(1);
    if (products.length === 0) {
      void fetchProducts();
    }
  }

  const totalPages = Math.max(1, Math.ceil(filteredProducts.length / pageSize));

  const pagedProducts = useMemo(() => {
    const safePage = Math.min(currentPage, totalPages);
    const start = (safePage - 1) * pageSize;
    return filteredProducts.slice(start, start + pageSize);
  }, [filteredProducts, currentPage, totalPages]);

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
      setCurrentPage(1);
    } catch {
      setResultMessage("백엔드 연결 실패: 서버가 실행 중인지 확인하세요.");
    } finally {
      setLoadingProducts(false);
    }
  }

  return (
    <main className="min-h-screen bg-gradient-to-b from-white via-slate-50 to-white">
      <div className="mx-auto flex w-full max-w-6xl flex-col gap-8 px-6 py-10">
        <header className="py-2">
          <h1 className="text-center text-4xl font-bold tracking-wide text-slate-900">MARKET ENGINE</h1>
        </header>

        <section className="rounded-2xl border border-slate-200 bg-white p-5 shadow-sm">
          <div className="flex flex-wrap items-center gap-2">
            {categories.map((category) => (
              <button
                key={category}
                type="button"
                onClick={() => {
                  setSelectedCategory(category);
                  setCurrentPage(1);
                }}
                className={`rounded-full border px-3 py-1.5 text-xs font-medium transition ${
                  selectedCategory === category
                    ? "border-slate-900 bg-slate-900 text-white"
                    : "border-slate-300 bg-white text-slate-700 hover:border-slate-500"
                }`}
              >
                {categoryLabel[category]}
              </button>
            ))}
          </div>

          <div className="mt-3 flex flex-wrap items-center gap-3">
            <input
              value={searchInput}
              onChange={(event) => {
                setSearchInput(event.target.value);
              }}
              onKeyDown={(event) => {
                if (event.key === "Enter") {
                  event.preventDefault();
                  applyKeywordSearch();
                }
              }}
              placeholder="검색어를 입력하세요"
              className="w-72 rounded-xl border border-slate-300 bg-white px-4 py-2 text-sm text-black placeholder:text-slate-500 outline-none ring-slate-200 transition focus:ring"
            />
            <button
              onClick={() => applyKeywordSearch()}
              className="rounded-xl bg-slate-900 px-4 py-2 text-sm font-medium text-white transition hover:bg-slate-700"
              type="button"
            >
              검색
            </button>
            <button
              type="button"
              onClick={() => setIsFilterOpen((prev) => !prev)}
              className="rounded-xl border border-slate-300 bg-white px-4 py-2 text-sm text-slate-700 transition hover:bg-slate-50"
            >
              필터 {isFilterOpen ? "접기" : "펼치기"}
            </button>
            {loadingProducts && <span className="text-sm text-slate-500">불러오는 중...</span>}
          </div>

          {isFilterOpen && (
            <div className="mt-4 grid gap-3 rounded-xl border border-slate-200 bg-slate-50 p-4 md:grid-cols-5">
              <input
                value={minPrice}
                onChange={(event) => {
                  setMinPrice(event.target.value);
                  setCurrentPage(1);
                }}
                placeholder="최소 금액"
                className="rounded-lg border border-slate-300 bg-white px-3 py-2 text-sm text-black placeholder:text-slate-500"
              />
              <input
                value={maxPrice}
                onChange={(event) => {
                  setMaxPrice(event.target.value);
                  setCurrentPage(1);
                }}
                placeholder="최대 금액"
                className="rounded-lg border border-slate-300 bg-white px-3 py-2 text-sm text-black placeholder:text-slate-500"
              />
              <select
                value={sortBy}
                onChange={(event) => {
                  setSortBy(event.target.value as "VIEW" | "POPULARITY");
                  setCurrentPage(1);
                }}
                className="rounded-lg border border-slate-300 bg-white px-3 py-2 text-sm text-black"
              >
                <option value="VIEW">조회순</option>
                <option value="POPULARITY">인기순</option>
              </select>
              <select
                value={selectedGender}
                onChange={(event) => {
                  setSelectedGender(event.target.value);
                  setCurrentPage(1);
                }}
                className="rounded-lg border border-slate-300 bg-white px-3 py-2 text-sm text-black"
              >
                {genders.map((gender) => (
                  <option key={gender} value={gender}>
                    {gender === "ALL" ? "성별 전체" : gender}
                  </option>
                ))}
              </select>
              <select
                value={selectedColor}
                onChange={(event) => {
                  setSelectedColor(event.target.value);
                  setCurrentPage(1);
                }}
                className="rounded-lg border border-slate-300 bg-white px-3 py-2 text-sm text-black"
              >
                {colors.map((color) => (
                  <option key={color} value={color}>
                    {color === "ALL" ? "색상 전체" : color}
                  </option>
                ))}
              </select>
            </div>
          )}

          <div className="mt-5 grid gap-4 sm:grid-cols-2 lg:grid-cols-3">
            {pagedProducts.map((product) => (
              <article
                key={product.id}
                className="rounded-2xl border border-slate-200 bg-white p-4 text-left transition hover:border-slate-300"
              >
                <Image
                  src={`https://placehold.co/600x400/f8fafc/334155?text=${encodeURIComponent(product.brand)}`}
                  alt={product.name}
                  width={600}
                  height={400}
                  className="h-36 w-full rounded-xl border border-slate-200 object-cover"
                />
                <div className="flex items-center justify-between">
                  <p className="mt-3 text-xs font-semibold text-slate-500">{product.brand}</p>
                  <span className="rounded-full bg-slate-100 px-2 py-0.5 text-xs text-slate-700">
                    {categoryLabel[product.category]}
                  </span>
                </div>
                <h3 className="mt-2 line-clamp-1 text-base font-semibold text-slate-900">
                  {product.name}
                </h3>
                <p className="mt-3 text-lg font-bold text-slate-900">
                  {Number(product.priceAmount).toLocaleString()}원
                </p>
                <div className="mt-3 flex items-center justify-between text-xs text-slate-500">
                  <span>재고 {product.stockQuantity}</span>
                  <span>인기도 {product.popularityScore}</span>
                </div>
                <div className="mt-2 flex items-center gap-2 text-xs">
                  <span className="rounded-full bg-white px-2 py-0.5 text-slate-600 ring-1 ring-slate-200">
                    {product.color}
                  </span>
                  <span className="rounded-full bg-white px-2 py-0.5 text-slate-600 ring-1 ring-slate-200">
                    {product.gender}
                  </span>
                </div>
              </article>
            ))}

            {pagedProducts.length === 0 && (
              <div className="rounded-xl border border-dashed border-slate-300 bg-slate-50 p-6 text-sm text-slate-500 sm:col-span-2 lg:col-span-3">
                검색 결과가 없습니다.
              </div>
            )}
          </div>
        </section>

        <section className="rounded-2xl border border-slate-200 bg-white p-5 shadow-sm">
          <div className="flex flex-wrap items-center justify-between gap-3">
            <div className="text-sm text-slate-600">
              총 <span className="font-semibold text-slate-900">{filteredProducts.length}</span>개 상품
            </div>
            <div className="flex items-center gap-2">
              <button
                type="button"
                onClick={() => setCurrentPage((prev) => Math.max(1, prev - 1))}
                className="rounded-lg border border-slate-300 px-3 py-1.5 text-sm text-slate-700 disabled:opacity-40"
                disabled={currentPage <= 1}
              >
                이전
              </button>

              <select
                value={String(Math.min(currentPage, totalPages))}
                onChange={(event) => setCurrentPage(Number(event.target.value))}
                className="rounded-lg border border-slate-300 px-3 py-1.5 text-sm"
              >
                {Array.from({ length: totalPages }, (_, index) => index + 1).map((pageNumber) => (
                  <option key={pageNumber} value={pageNumber}>
                    {pageNumber} / {totalPages}
                  </option>
                ))}
              </select>

              <button
                type="button"
                onClick={() => setCurrentPage((prev) => Math.min(totalPages, prev + 1))}
                className="rounded-lg border border-slate-300 px-3 py-1.5 text-sm text-slate-700 disabled:opacity-40"
                disabled={currentPage >= totalPages}
              >
                다음
              </button>
            </div>
          </div>
        </section>

        {resultMessage && (
          <section className="rounded-2xl border border-slate-200 bg-white p-4 text-sm text-slate-700 shadow-sm">
            {resultMessage}
          </section>
        )}
      </div>
    </main>
  );
}
