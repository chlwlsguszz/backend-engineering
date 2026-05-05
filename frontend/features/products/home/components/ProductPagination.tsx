"use client";

type Props = {
  currentPage: number;
  totalPages: number;
  totalElements: number;
  pageSize: number;
  setCurrentPage: (update: number | ((prev: number) => number)) => void;
};

export function ProductPagination({
  currentPage,
  totalPages,
  totalElements,
  pageSize,
  setCurrentPage,
}: Props) {
  return (
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
  );
}
