import { useEffect, useMemo, useState } from "react";

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
  const [totalPages, setTotalPages] = useState(1);
  const [totalElements, setTotalElements] = useState(0);
  const [loadingProducts, setLoadingProducts] = useState(false);
  const [resultMessage, setResultMessage] = useState("");

  const hasProducts = useMemo(() => products.length > 0, [products]);

  useEffect(() => {
    async function load() {
      setLoadingProducts(true);
      setResultMessage("");

      const result = await fetchProductPage(currentPage, filters);

      if (!result.data) {
        setProducts([]);
        setTotalPages(1);
        setTotalElements(0);
        setResultMessage(result.errorMessage);
        setLoadingProducts(false);
        return;
      }

      setProducts(result.data.items);
      setTotalPages(Math.max(1, result.data.totalPages));
      setTotalElements(result.data.totalElements);
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

  return {
    products,
    filters,
    searchInput,
    isFilterOpen,
    currentPage,
    totalPages,
    totalElements,
    loadingProducts,
    resultMessage,
    hasProducts,
    pageSize: PAGE_SIZE,
    setSearchInput,
    setIsFilterOpen,
    setCurrentPage,
    patchFilters,
    applyKeywordSearch,
  };
}
