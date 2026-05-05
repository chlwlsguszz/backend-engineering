"use client";

import Image from "next/image";
import Link from "next/link";

import { CATEGORY_IMAGE_TEXT, CATEGORY_LABEL } from "../../shared/constants";
import type { Product } from "../../shared/types";

type Props = {
  products: Product[];
};

export function ProductGrid({ products }: Props) {
  return (
    <div className="mt-5 grid gap-4 sm:grid-cols-2 lg:grid-cols-3">
      {products.map((product) => (
        <Link key={product.id} href={`/products/${product.id}`} className="block">
          <article className="rounded-2xl border border-slate-200 bg-white p-4 text-left transition hover:border-slate-300">
            <Image
              src={`https://placehold.co/600x400/png?text=${CATEGORY_IMAGE_TEXT[product.category]}&font=inter`}
              alt={product.name}
              width={600}
              height={400}
              className="h-36 w-full rounded-xl border border-slate-200 object-cover"
            />
            <div className="flex items-center justify-between">
              <p className="mt-3 text-xs font-semibold text-slate-500">{product.brand}</p>
              <span className="rounded-full bg-slate-100 px-2 py-0.5 text-xs text-slate-700">
                {CATEGORY_LABEL[product.category]}
              </span>
            </div>
            <h3 className="mt-2 line-clamp-1 text-base font-semibold text-slate-900">{product.name}</h3>
            <p className="mt-3 text-lg font-bold text-slate-900">{Number(product.priceAmount).toLocaleString()}원</p>
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
  );
}
