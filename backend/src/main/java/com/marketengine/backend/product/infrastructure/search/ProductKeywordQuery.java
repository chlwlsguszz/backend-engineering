package com.marketengine.backend.product.infrastructure.search;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

import com.marketengine.backend.product.domain.ProductSearchKeywords;

import co.elastic.clients.elasticsearch._types.query_dsl.BoolQuery;
import co.elastic.clients.elasticsearch._types.query_dsl.Operator;
import co.elastic.clients.elasticsearch._types.query_dsl.Query;

/** Query-time catalogue vocabulary; works with the existing text and keyword mappings. */
final class ProductKeywordQuery {
    private static final int MAX_ALIAS_WORDS = 3;
    private static final Pattern TYPO_CANDIDATE = Pattern.compile("[a-z]{6,}");
    private static final Map<String, Alias> ALIASES = catalogueAliases();

    private ProductKeywordQuery() {
    }

    static void appendTo(BoolQuery.Builder query, String keyword) {
        List<String> tokens = ProductSearchKeywords.tokens(keyword);
        for (int offset = 0; offset < tokens.size();) {
            AliasMatch match = findAlias(tokens, offset);
            if (match != null) {
                query.must(aliasQuery(match));
                offset += match.words();
            } else {
                String token = tokens.get(offset++);
                query.must(nameMatch(token, TYPO_CANDIDATE.matcher(token).matches()));
            }
        }
    }

    private static AliasMatch findAlias(List<String> tokens, int offset) {
        // Consume whole brand/item phrases before interpreting individual words.
        for (int words = Math.min(MAX_ALIAS_WORDS, tokens.size() - offset); words > 0; words--) {
            String phrase = String.join(" ", tokens.subList(offset, offset + words));
            Alias alias = ALIASES.get(phrase);
            if (alias != null) return new AliasMatch(phrase, words, alias);
        }
        return null;
    }

    private static Query aliasQuery(AliasMatch match) {
        Alias alias = match.alias();
        // Keep literal names searchable too, including products whose actual names are Korean.
        // The outer MUST still requires every independent user term and every explicit facet filter.
        Query literal = match.words() > 1 || match.phrase().contains("-")
                ? Query.of(q -> q.matchPhrase(p -> p.field("name").query(match.phrase())))
                : nameMatch(match.phrase(), false);
        BoolQuery.Builder alternatives = new BoolQuery.Builder().should(literal).minimumShouldMatch("1");
        for (String value : alias.values()) {
            alternatives.should("name".equals(alias.field())
                    ? Query.of(q -> q.matchPhrase(p -> p.field("name").query(value)))
                    : Query.of(q -> q.term(t -> t.field(alias.field()).value(value).caseInsensitive(true))));
        }
        return Query.of(q -> q.bool(alternatives.build()));
    }

    private static Query nameMatch(String text, boolean tolerateTypo) {
        return Query.of(q -> q.match(m -> {
            m.field("name").query(text).operator(Operator.And);
            if (tolerateTypo) {
                // Do not broaden short words (shirt/short), model codes, or punctuation into fuzzy terms.
                m.fuzziness("1").prefixLength(2).maxExpansions(20).fuzzyTranspositions(true);
            }
            return m;
        }));
    }

    private static Map<String, Alias> catalogueAliases() {
        Map<String, Alias> aliases = new LinkedHashMap<>();
        addBrands(aliases);
        addFacets(aliases);
        addItems(aliases);
        return Map.copyOf(aliases);
    }

    private static void addBrands(Map<String, Alias> aliases) {
        register(aliases, "brand", "Nike", "나이키");
        register(aliases, "brand", "Adidas", "아디다스");
        register(aliases, "brand", "Puma", "푸마");
        register(aliases, "brand", "New Balance", "뉴발란스", "뉴 발란스", "newbalance");
        register(aliases, "brand", "Under Armour", "언더아머", "언더 아머", "underarmour");
        register(aliases, "brand", "Converse", "컨버스");
        register(aliases, "brand", "Reebok", "리복");
        register(aliases, "brand", "Fila", "휠라", "필라");
        register(aliases, "brand", "Asics", "아식스");
        register(aliases, "brand", "Lululemon", "룰루레몬");
        register(aliases, "brand", "Jordan", "조던");
        register(aliases, "brand", "Vans", "반스");
        register(aliases, "brand", "Skechers", "스케쳐스", "스케처스");
        register(aliases, "brand", "Champion", "챔피온", "챔피언");
        register(aliases, "brand", "Levis", "리바이스");
        register(aliases, "brand", "Patagonia", "파타고니아");
        register(aliases, "brand", "The North Face", "노스페이스", "노스 페이스", "north face", "thenorthface");
        register(aliases, "brand", "Columbia", "컬럼비아", "콜롬비아");
        register(aliases, "brand", "Oakley", "오클리");
        register(aliases, "brand", "Carhartt", "칼하트");
    }

    private static void addFacets(Map<String, Alias> aliases) {
        register(aliases, "category", "TOP", "상의");
        register(aliases, "category", "BOTTOM", "하의", "바지");
        register(aliases, "category", "OUTER", "아우터", "외투");
        register(aliases, "category", "SHOES", "신발");
        register(aliases, "category", "GLASSES", "안경");
        register(aliases, "category", "HAT", "모자");

        register(aliases, "color", "BLACK", "검정", "검정색", "검은색", "블랙");
        register(aliases, "color", "WHITE", "흰색", "하얀색", "화이트");
        register(aliases, "color", "NAVY", "남색", "네이비");
        register(aliases, "color", "GRAY", "회색", "그레이", "grey");
        register(aliases, "color", "BEIGE", "베이지");
        register(aliases, "color", "RED", "빨강", "빨간색", "빨강색", "레드");
        register(aliases, "color", "BLUE", "파랑", "파란색", "파랑색", "블루");
        register(aliases, "color", "GREEN", "초록", "초록색", "녹색", "그린");

        register(aliases, "gender", "MEN", "남성", "남자", "남성용");
        register(aliases, "gender", "WOMEN", "여성", "여자", "여성용");
        register(aliases, "gender", "UNISEX", "남녀공용", "공용", "유니섹스");
    }

    private static void addItems(Map<String, Alias> aliases) {
        register(aliases, "name", "Hoodie", "후드", "후드티", "후디");
        register(aliases, "name", "T-Shirt", "티셔츠", "티셔트", "tshirt", "tee");
        register(aliases, "name", "Sweatshirt", "맨투맨", "스웨트셔츠");
        register(aliases, "name", "Long Sleeve", "긴팔", "롱슬리브", "longsleeve");
        register(aliases, "name", "Jeans", "청바지", "데님");
        register(aliases, "name", "Slacks", "슬랙스");
        register(aliases, "name", "Jogger Pants", "조거", "조거팬츠");
        register(aliases, "name", "Cargo Pants", "카고", "카고팬츠");
        register(aliases, "name", "Jacket", "자켓", "재킷");
        register(aliases, "name", "Coat", "코트");
        register(aliases, "name", "Windbreaker", "바람막이", "윈드브레이커");
        register(aliases, "name", "Cardigan", "가디건");
        register(aliases, "name", "Sneakers", "스니커즈");
        register(aliases, "name", "Running Shoes", "러닝화", "런닝화");
        aliases.put("운동화", new Alias("name", List.of("Sneakers", "Running Shoes")));
        register(aliases, "name", "Boots", "부츠");
        register(aliases, "name", "Loafers", "로퍼");
        register(aliases, "name", "Baseball Cap", "야구모자", "야구 모자");
        register(aliases, "name", "Bucket Hat", "버킷햇", "벙거지");
        register(aliases, "name", "Beanie", "비니");
        register(aliases, "name", "Visor", "바이저");
        register(aliases, "name", "Round Glasses", "둥근안경", "둥근 안경");
        register(aliases, "name", "Metal Frame", "금속테", "메탈프레임");
        register(aliases, "name", "Sunglasses", "선글라스");
        register(aliases, "name", "Square Glasses", "사각안경", "사각 안경");
    }

    private static void register(Map<String, Alias> aliases, String field, String canonical, String... alternatives) {
        Alias alias = new Alias(field, List.of(canonical));
        aliases.put(canonical.toLowerCase(Locale.ROOT), alias);
        for (String alternative : alternatives) aliases.put(alternative, alias);
    }

    private record Alias(String field, List<String> values) {}
    private record AliasMatch(String phrase, int words, Alias alias) {}
}
