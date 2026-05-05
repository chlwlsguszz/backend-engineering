"use client";

import { BRANDS, CATEGORIES, CATEGORY_LABEL, COLORS, GENDERS } from "../../shared/constants";
import type { ProductFilterState } from "../../shared/types";

type Props = {
  filters: ProductFilterState;
  searchInput: string;
  isFilterOpen: boolean;
  loadingProducts: boolean;
  setSearchInput: (value: string) => void;
  setIsFilterOpen: (value: boolean | ((prev: boolean) => boolean)) => void;
  patchFilters: (patch: Partial<ProductFilterState>) => void;
  applyKeywordSearch: () => void;
};

export function ProductToolbar({
  filters,
  searchInput,
  isFilterOpen,
  loadingProducts,
  setSearchInput,
  setIsFilterOpen,
  patchFilters,
  applyKeywordSearch,
}: Props) {
  return (
    <section className="rounded-2xl border border-slate-200 bg-white p-5 shadow-sm">
      <div className="flex flex-wrap items-center gap-2">
        {CATEGORIES.map((category) => (
          <button
            key={category}
            type="button"
            onClick={() => patchFilters({ category })}
            className={`rounded-full border px-3 py-1.5 text-xs font-medium transition ${
              filters.category === category
                ? "border-slate-900 bg-slate-900 text-white"
                : "border-slate-300 bg-white text-slate-700 hover:border-slate-500"
            }`}
          >
            {CATEGORY_LABEL[category]}
          </button>
        ))}
      </div>

      <div className="mt-3 flex flex-wrap items-center gap-3">
        <input
          value={searchInput}
          onChange={(event) => setSearchInput(event.target.value)}
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
          onClick={applyKeywordSearch}
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
            value={filters.minPrice}
            onChange={(event) => patchFilters({ minPrice: event.target.value })}
            placeholder="최소 금액"
            className="rounded-lg border border-slate-300 bg-white px-3 py-2 text-sm text-black placeholder:text-slate-500"
          />
          <input
            value={filters.maxPrice}
            onChange={(event) => patchFilters({ maxPrice: event.target.value })}
            placeholder="최대 금액"
            className="rounded-lg border border-slate-300 bg-white px-3 py-2 text-sm text-black placeholder:text-slate-500"
          />
          <select
            value={filters.sortBy}
            onChange={(event) => patchFilters({ sortBy: event.target.value as ProductFilterState["sortBy"] })}
            className="rounded-lg border border-slate-300 bg-white px-3 py-2 text-sm text-black"
          >
            <option value="LATEST">최신순</option>
            <option value="POPULARITY">인기순</option>
          </select>
          <select
            value={filters.brand}
            onChange={(event) => patchFilters({ brand: event.target.value })}
            className="rounded-lg border border-slate-300 bg-white px-3 py-2 text-sm text-black"
          >
            {BRANDS.map((brand) => (
              <option key={brand} value={brand}>
                {brand === "ALL" ? "브랜드 전체" : brand}
              </option>
            ))}
          </select>
          <select
            value={filters.gender}
            onChange={(event) => patchFilters({ gender: event.target.value })}
            className="rounded-lg border border-slate-300 bg-white px-3 py-2 text-sm text-black"
          >
            {GENDERS.map((gender) => (
              <option key={gender} value={gender}>
                {gender === "ALL" ? "성별 전체" : gender}
              </option>
            ))}
          </select>
          <select
            value={filters.color}
            onChange={(event) => patchFilters({ color: event.target.value })}
            className="rounded-lg border border-slate-300 bg-white px-3 py-2 text-sm text-black"
          >
            {COLORS.map((color) => (
              <option key={color} value={color}>
                {color === "ALL" ? "색상 전체" : color}
              </option>
            ))}
          </select>
        </div>
      )}
    </section>
  );
}
