import type { ProductCategory } from "./types";

export const PAGE_SIZE = 12;

export const CATEGORIES = ["ALL", "TOP", "BOTTOM", "OUTER", "SHOES", "GLASSES", "HAT"] as const;
export const GENDERS = ["ALL", "MEN", "WOMEN", "UNISEX"] as const;
export const COLORS = ["ALL", "BLACK", "WHITE", "NAVY", "GRAY", "BEIGE", "RED", "BLUE", "GREEN"] as const;

export const BRANDS = [
  "ALL",
  "Nike",
  "Adidas",
  "Puma",
  "New Balance",
  "Under Armour",
  "Converse",
  "Reebok",
  "Fila",
  "Asics",
  "Lululemon",
  "Jordan",
  "Vans",
  "Skechers",
  "Champion",
  "Levis",
  "Patagonia",
  "The North Face",
  "Columbia",
  "Oakley",
  "Carhartt",
] as const;

export const CATEGORY_LABEL: Record<(typeof CATEGORIES)[number], string> = {
  ALL: "전체",
  TOP: "상의",
  BOTTOM: "하의",
  OUTER: "아우터",
  SHOES: "신발",
  GLASSES: "안경",
  HAT: "모자",
};

export const CATEGORY_IMAGE_TEXT: Record<"ALL" | ProductCategory, string> = {
  ALL: "APPAREL",
  TOP: "TOP",
  BOTTOM: "BOTTOM",
  OUTER: "OUTER",
  SHOES: "SHOES",
  GLASSES: "GLASSES",
  HAT: "CAP",
};
