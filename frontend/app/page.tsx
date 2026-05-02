"use client";

import Image from "next/image";
import Link from "next/link";
import { useEffect, useLayoutEffect, useMemo, useState } from "react";

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

type ProductPageResponse = {
  items: Product[];
  page: number;
  size: number;
  totalElements: number;
  totalPages: number;
  hasNext: boolean;
};

export default function Home() {
  const [products, setProducts] = useState<Product[]>([]);
  const [searchInput, setSearchInput] = useState("");
  const [selectedCategory, setSelectedCategory] = useState("ALL");
  const [isFilterOpen, setIsFilterOpen] = useState(false);
  const [sortBy, setSortBy] = useState<"LATEST" | "POPULARITY">("LATEST");
  const [selectedBrand, setSelectedBrand] = useState("ALL");
  const [selectedGender, setSelectedGender] = useState("ALL");
  const [selectedColor, setSelectedColor] = useState("ALL");
  const [minPrice, setMinPrice] = useState("");
  const [maxPrice, setMaxPrice] = useState("");
  const [keyword, setKeyword] = useState("");
  const [currentPage, setCurrentPage] = useState(0);
  const [totalPages, setTotalPages] = useState(1);
  const [totalElements, setTotalElements] = useState(0);
  const [loadingProducts, setLoadingProducts] = useState(false);
  const [resultMessage, setResultMessage] = useState("");

  const pageSize = 12;

  const categories = useMemo(
    () => ["ALL", "TOP", "BOTTOM", "OUTER", "SHOES", "GLASSES", "HAT"] as const,
    [],
  );

  const genders = useMemo(() => ["ALL", "MEN", "WOMEN", "UNISEX"], []);
  const brands = useMemo(
    () => [
      "ALL",
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
    ],
    [],
  );
  const colors = useMemo(
    () => ["ALL", "BLACK", "WHITE", "NAVY", "GRAY", "BEIGE", "RED", "BLUE", "GREEN"],
    [],
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

  const categoryImageText: Record<(typeof categories)[number], string> = {
    ALL: "APPAREL",
    TOP: "TOP",
    BOTTOM: "BOTTOM",
    OUTER: "OUTER",
    SHOES: "SHOES",
    GLASSES: "GLASSES",
    HAT: "CAP",
  };

  function applyKeywordSearch() {
    setKeyword(searchInput);
    setCurrentPage(0);
  }

  async function fetchProducts() {
    setLoadingProducts(true);
    setResultMessage("");
    const perf =
      typeof performance !== "undefined"
        ? { t0: performance.now(), tNetwork: 0, tJson: 0, n: 0 }
        : null;
    try {
      const params = new URLSearchParams({
        page: String(currentPage),
        size: String(pageSize),
        sortBy,
      });
      if (keyword.trim() !== "") params.set("keyword", keyword.trim());
      if (selectedCategory !== "ALL") params.set("category", selectedCategory);
      if (selectedBrand !== "ALL") params.set("brand", selectedBrand);
      if (selectedGender !== "ALL") params.set("gender", selectedGender);
      if (selectedColor !== "ALL") params.set("color", selectedColor);
      if (minPrice.trim() !== "") params.set("minPrice", minPrice.trim());
      if (maxPrice.trim() !== "") params.set("maxPrice", maxPrice.trim());

      const response = await fetch(`/backend/api/products?${params.toString()}`);
      if (perf) perf.tNetwork = performance.now();
      const body: ApiEnvelope<ProductPageResponse> = await response.json();
      if (perf) {
        perf.tJson = performance.now();
        perf.n = body.success && body.data?.items ? body.data.items.length : 0;
      }
      if (!response.ok || !body.success) {
        setResultMessage(body.error?.message ?? "상품 조회 실패");
        setProducts([]);
        setTotalPages(1);
        setTotalElements(0);
        return;
      }
      setProducts(body.data.items);
      setTotalPages(Math.max(1, body.data.totalPages));
      setTotalElements(body.data.totalElements);
      if (perf) {
        const networkMs = (perf.tNetwork - perf.t0).toFixed(1);
        const jsonMs = (perf.tJson - perf.tNetwork).toFixed(1);
        console.info(
          `[perf] API network ${networkMs}ms · JSON parse ${jsonMs}ms · items ${perf.n} (React paint logged after commit)`,
        );
      }
    } catch {
      setProducts([]);
      setTotalPages(1);
      setTotalElements(0);
      setResultMessage("백엔드 연결 실패: 서버가 실행 중인지 확인하세요.");
    } finally {
      setLoadingProducts(false);
    }
  }

  useEffect(() => {
    void fetchProducts();
  }, [currentPage, keyword, selectedCategory, selectedBrand, selectedGender, selectedColor, minPrice, maxPrice, sortBy]);

  useLayoutEffect(() => {
    if (products.length === 0) return;
    const start = performance.now();
    requestAnimationFrame(() => {
      console.info(`[perf] after list commit → rAF ${(performance.now() - start).toFixed(1)}ms (DOM+paint queue)`);
    });
  }, [products]);

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
                  setCurrentPage(0);
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
            <div className="mt-4 grid gap-3 rounded-xl border border-slate-200 bg-slate-50 p-4 md:grid-cols-6">
              <input
                value={minPrice}
                onChange={(event) => {
                  setMinPrice(event.target.value);
                  setCurrentPage(0);
                }}
                placeholder="최소 금액"
                className="rounded-lg border border-slate-300 bg-white px-3 py-2 text-sm text-black placeholder:text-slate-500"
              />
              <input
                value={maxPrice}
                onChange={(event) => {
                  setMaxPrice(event.target.value);
                  setCurrentPage(0);
                }}
                placeholder="최대 금액"
                className="rounded-lg border border-slate-300 bg-white px-3 py-2 text-sm text-black placeholder:text-slate-500"
              />
              <select
                value={sortBy}
                onChange={(event) => {
                  setSortBy(event.target.value as "LATEST" | "POPULARITY");
                  setCurrentPage(0);
                }}
                className="rounded-lg border border-slate-300 bg-white px-3 py-2 text-sm text-black"
              >
                <option value="LATEST">최신순</option>
                <option value="POPULARITY">인기순</option>
              </select>
              <select
                value={selectedBrand}
                onChange={(event) => {
                  setSelectedBrand(event.target.value);
                  setCurrentPage(0);
                }}
                className="rounded-lg border border-slate-300 bg-white px-3 py-2 text-sm text-black"
              >
                {brands.map((brand) => (
                  <option key={brand} value={brand}>
                    {brand === "ALL" ? "브랜드 전체" : brand}
                  </option>
                ))}
              </select>
              <select
                value={selectedGender}
                onChange={(event) => {
                  setSelectedGender(event.target.value);
                  setCurrentPage(0);
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
                  setCurrentPage(0);
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
            {products.map((product) => (
              <Link key={product.id} href={`/products/${product.id}`} className="block">
                <article className="rounded-2xl border border-slate-200 bg-white p-4 text-left transition hover:border-slate-300">
                <Image
                  src={`https://placehold.co/600x400/png?text=${categoryImageText[product.category]}&font=inter`}
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
              </Link>
            ))}

            {products.length === 0 && (
              <div className="rounded-xl border border-dashed border-slate-300 bg-slate-50 p-6 text-sm text-slate-500 sm:col-span-2 lg:col-span-3">
                검색 결과가 없습니다.
              </div>
            )}
          </div>
        </section>

        <section className="rounded-2xl border border-slate-200 bg-white p-5 shadow-sm">
          <div className="flex flex-wrap items-center justify-between gap-3">
            <div className="text-sm text-slate-600">
              총 <span className="font-semibold text-slate-900">{totalElements}</span>개 상품
            </div>
            <div className="flex items-center gap-2">
              <button
                type="button"
                onClick={() => setCurrentPage((prev) => Math.max(0, prev - 1))}
                className="rounded-lg border border-slate-300 px-3 py-1.5 text-sm text-slate-700 disabled:opacity-40"
                disabled={currentPage <= 0}
              >
                이전
              </button>

              <span className="min-w-[8rem] rounded-lg border border-slate-300 bg-slate-50 px-3 py-1.5 text-center text-sm text-slate-900 tabular-nums">
                {Math.min(currentPage + 1, totalPages)} / {totalPages}
              </span>

              <button
                type="button"
                onClick={() => setCurrentPage((prev) => Math.min(totalPages - 1, prev + 1))}
                className="rounded-lg border border-slate-300 px-3 py-1.5 text-sm text-slate-700 disabled:opacity-40"
                disabled={currentPage + 1 >= totalPages}
              >
                다음
              </button>
            </div>
          </div>
          <p className="mt-2 text-xs text-slate-500">현재 페이지 크기: {pageSize}</p>
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
