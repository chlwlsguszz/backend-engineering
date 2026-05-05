"use client";

import { ProductGrid } from "./ProductGrid";
import { ProductPagination } from "./ProductPagination";
import { ProductToolbar } from "./ProductToolbar";
import { useProductList } from "../hooks/useProductList";

export function HomePageClient() {
  const {
    products,
    filters,
    searchInput,
    isFilterOpen,
    currentPage,
    totalPages,
    totalElements,
    loadingProducts,
    resultMessage,
    pageSize,
    setSearchInput,
    setIsFilterOpen,
    setCurrentPage,
    patchFilters,
    applyKeywordSearch,
  } = useProductList();

  return (
    <main className="min-h-screen bg-gradient-to-b from-white via-slate-50 to-white">
      <div className="mx-auto flex w-full max-w-6xl flex-col gap-8 px-6 py-10">
        <header className="py-2">
          <h1 className="text-center text-4xl font-bold tracking-wide text-slate-900">MARKET ENGINE</h1>
        </header>

        <ProductToolbar
          filters={filters}
          searchInput={searchInput}
          isFilterOpen={isFilterOpen}
          loadingProducts={loadingProducts}
          setSearchInput={setSearchInput}
          setIsFilterOpen={setIsFilterOpen}
          patchFilters={patchFilters}
          applyKeywordSearch={applyKeywordSearch}
        />

        <section className="rounded-2xl border border-slate-200 bg-white p-5 shadow-sm">
          <ProductGrid products={products} />
        </section>

        <ProductPagination
          currentPage={currentPage}
          totalPages={totalPages}
          totalElements={totalElements}
          pageSize={pageSize}
          setCurrentPage={setCurrentPage}
        />

        {resultMessage && (
          <section className="rounded-2xl border border-slate-200 bg-white p-4 text-sm text-slate-700 shadow-sm">
            {resultMessage}
          </section>
        )}
      </div>
    </main>
  );
}
