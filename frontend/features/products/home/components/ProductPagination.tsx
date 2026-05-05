"use client";

type Props = {
  hasNext: boolean;
  loadingMore: boolean;
  loadedCount: number;
  pageSize: number;
  loadMore: () => void;
};

export function ProductPagination({
  hasNext,
  loadingMore,
  loadedCount,
  pageSize,
  loadMore,
}: Props) {
  return (
    <section className="rounded-2xl border border-slate-200 bg-white p-5 shadow-sm">
      <div className="flex flex-wrap items-center justify-between gap-3">
        <div className="text-sm text-slate-600">
          현재 <span className="font-semibold text-slate-900">{loadedCount}</span>개 상품 표시 중
        </div>
        <div>
          <button
            type="button"
            onClick={loadMore}
            className="rounded-lg border border-slate-300 px-3 py-1.5 text-sm text-slate-700 disabled:opacity-40"
            disabled={!hasNext || loadingMore}
          >
            {loadingMore ? "불러오는 중..." : "더 보기"}
          </button>
        </div>
      </div>
      <p className="mt-2 text-xs text-slate-500">현재 페이지 크기: {pageSize}</p>
    </section>
  );
}
