export type ProductCategory = "TOP" | "BOTTOM" | "OUTER" | "SHOES" | "GLASSES" | "HAT";

export type SortBy = "LATEST" | "POPULARITY";

export type Product = {
  id: number;
  name: string;
  priceAmount: number;
  stockQuantity: number;
  category: ProductCategory;
  brand: string;
  color: string;
  gender: string;
  popularityScore: number;
  createdAt: string;
};

export type ProductDetail = Product & {
  description: string;
  status: string;
  updatedAt: string;
};

export type ApiEnvelope<T> = {
  success: boolean;
  data: T;
  error?: {
    code: string;
    message: string;
  };
};

export type ProductPageResponse = {
  items: Product[];
  page: number;
  size: number;
  hasNext: boolean;
};

export type ProductFilterState = {
  keyword: string;
  category: "ALL" | ProductCategory;
  brand: string;
  gender: string;
  color: string;
  minPrice: string;
  maxPrice: string;
  sortBy: SortBy;
};

export type OrderResponse = {
  id: number;
  memberId: number;
  productId: number;
  quantity: number;
  unitPrice: number;
  status: string;
  totalAmount: number;
  createdAt: string;
};
