"use client";

import { useEffect } from "react";

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
    hasNext,
    loadingInitial,
    loadingMore,
    resultMessage,
    pageSize,
    setSearchInput,
    setIsFilterOpen,
    patchFilters,
    applyKeywordSearch,
    loadMore,
    lastFetchMetricRef,
  } = useProductList();

  useEffect(() => {
    const metric = lastFetchMetricRef.current;
    if (!metric) {
      return;
    }

    const afterCommitAt = performance.now();
    requestAnimationFrame(() => {
      const afterPaintAt = performance.now();
      const networkAndJsonMs = metric.responseAt - metric.startedAt;
      const reactCommitMs = afterCommitAt - metric.responseAt;
      const paintQueueMs = afterPaintAt - afterCommitAt;
      const totalMs = afterPaintAt - metric.startedAt;
      console.info(
        `[perf][products:${metric.mode}] page=${metric.page} items=${metric.itemCount} total=${totalMs.toFixed(1)}ms (net+json=${networkAndJsonMs.toFixed(1)}ms, react=${reactCommitMs.toFixed(1)}ms, paint=${paintQueueMs.toFixed(1)}ms)`,
      );
    });
    lastFetchMetricRef.current = null;
  }, [products, lastFetchMetricRef]);

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
          loadingProducts={loadingInitial}
          setSearchInput={setSearchInput}
          setIsFilterOpen={setIsFilterOpen}
          patchFilters={patchFilters}
          applyKeywordSearch={applyKeywordSearch}
        />

        <section className="rounded-2xl border border-slate-200 bg-white p-5 shadow-sm">
          <ProductGrid products={products} />
        </section>

        <ProductPagination
          hasNext={hasNext}
          loadingMore={loadingMore}
          loadedCount={products.length}
          pageSize={pageSize}
          loadMore={loadMore}
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
