import { useEffect, useRef, useState } from "react";

import { fetchProductPage } from "../../shared/api";
import { PAGE_SIZE } from "../../shared/constants";
import type { Product, ProductFilterState } from "../../shared/types";

const INITIAL_FILTERS: ProductFilterState = {
  keyword: "",
  category: "ALL",
  brand: "ALL",
  gender: "ALL",
  color: "ALL",
  minPrice: "",
  maxPrice: "",
  sortBy: "LATEST",
};

export function useProductList() {
  const [products, setProducts] = useState<Product[]>([]);
  const [request, setRequest] = useState({ filters: INITIAL_FILTERS, page: 0, after: null as string | null });
  const nextCursor = useRef<string | null>(null);
  const [searchInput, setSearchInput] = useState("");
  const [isFilterOpen, setIsFilterOpen] = useState(false);
  const [loadedPage, setLoadedPage] = useState(-1);
  const [hasNext, setHasNext] = useState(false);
  const [loadingProducts, setLoadingProducts] = useState(true);
  const [resultMessage, setResultMessage] = useState("");
  const lastFetchMetricRef = useRef<{
    mode: "initial" | "loadMore";
    startedAt: number;
    responseAt: number;
    itemCount: number;
    page: number;
  } | null>(null);

  const activeRequest = useRef<AbortController | null>(null);

  useEffect(() => {
    const controller = new AbortController();
    activeRequest.current = controller;
    const { page, filters, after } = request;
    async function load() {
      const startedAt = performance.now();
      const mode = page === 0 ? "initial" : "loadMore";

      const result = await fetchProductPage(page, filters, controller.signal, after);
      if (controller.signal.aborted) return;
      const responseAt = performance.now();

      if (!result.data) {
        setResultMessage(result.errorMessage);
        setLoadingProducts(false);
        return;
      }
      const data = result.data;
      lastFetchMetricRef.current = {
        mode,
        startedAt,
        responseAt,
        itemCount: data.items.length,
        page,
      };

      if (page === 0) {
        setProducts(data.items);
      } else {
        setProducts((prev) => [...prev, ...data.items]);
      }
      setLoadedPage(page);
      nextCursor.current = data.nextCursor;
      setHasNext(data.hasNext);
      setLoadingProducts(false);
    }

    void load();
    return () => controller.abort();
  }, [request]);

  function applyKeywordSearch() {
    patchFilters({ keyword: searchInput });
  }

  function patchFilters(patch: Partial<ProductFilterState>) {
    activeRequest.current?.abort();
    setProducts([]);
    setHasNext(false);
    setLoadedPage(-1);
    nextCursor.current = null;
    setLoadingProducts(true);
    setResultMessage("");
    setRequest((prev) => ({ filters: { ...prev.filters, ...patch }, page: 0, after: null }));
  }

  function loadMore() {
    if (loadingProducts || !hasNext) {
      return;
    }
    setLoadingProducts(true);
    setResultMessage("");
    setRequest((prev) => ({ filters: prev.filters, page: loadedPage + 1, after: nextCursor.current }));
  }

  return {
    products,
    filters: request.filters,
    searchInput,
    isFilterOpen,
    hasNext,
    loadingInitial: loadingProducts && request.page === 0,
    loadingMore: loadingProducts && request.page > 0,
    resultMessage,
    pageSize: PAGE_SIZE,
    setSearchInput,
    setIsFilterOpen,
    patchFilters,
    applyKeywordSearch,
    loadMore,
    lastFetchMetricRef,
  };
}
