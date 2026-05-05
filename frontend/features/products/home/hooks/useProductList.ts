import { useEffect, useMemo, useRef, useState } from "react";

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
  const [filters, setFilters] = useState<ProductFilterState>(INITIAL_FILTERS);
  const [searchInput, setSearchInput] = useState("");
  const [isFilterOpen, setIsFilterOpen] = useState(false);
  const [currentPage, setCurrentPage] = useState(0);
  const [hasNext, setHasNext] = useState(false);
  const [loadingProducts, setLoadingProducts] = useState(false);
  const [resultMessage, setResultMessage] = useState("");
  const lastFetchMetricRef = useRef<{
    mode: "initial" | "loadMore";
    startedAt: number;
    responseAt: number;
    itemCount: number;
    page: number;
  } | null>(null);

  const hasProducts = useMemo(() => products.length > 0, [products]);

  useEffect(() => {
    async function load() {
      setLoadingProducts(true);
      setResultMessage("");
      const startedAt = performance.now();
      const mode = currentPage === 0 ? "initial" : "loadMore";

      const result = await fetchProductPage(currentPage, filters);
      const responseAt = performance.now();

      if (!result.data) {
        if (currentPage === 0) {
          setProducts([]);
          setHasNext(false);
        }
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
        page: currentPage,
      };

      if (currentPage === 0) {
        setProducts(data.items);
      } else {
        setProducts((prev) => [...prev, ...data.items]);
      }
      setHasNext(data.hasNext);
      setLoadingProducts(false);
    }

    void load();
  }, [currentPage, filters]);

  function applyKeywordSearch() {
    setFilters((prev) => ({ ...prev, keyword: searchInput }));
    setCurrentPage(0);
  }

  function patchFilters(patch: Partial<ProductFilterState>) {
    setFilters((prev) => ({ ...prev, ...patch }));
    setCurrentPage(0);
  }

  function loadMore() {
    if (loadingProducts || !hasNext) {
      return;
    }
    setCurrentPage((prev) => prev + 1);
  }

  return {
    products,
    filters,
    searchInput,
    isFilterOpen,
    hasNext,
    loadingProducts,
    loadingInitial: loadingProducts && currentPage === 0,
    loadingMore: loadingProducts && currentPage > 0,
    resultMessage,
    hasProducts,
    pageSize: PAGE_SIZE,
    setSearchInput,
    setIsFilterOpen,
    patchFilters,
    applyKeywordSearch,
    loadMore,
    lastFetchMetricRef,
  };
}
