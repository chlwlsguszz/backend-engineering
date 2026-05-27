package com.marketengine.backend.product.infrastructure.search;

import org.springframework.stereotype.Component;

import com.marketengine.backend.product.domain.Product;
import com.marketengine.backend.product.domain.ProductRepository;

import lombok.RequiredArgsConstructor;

/**
 * Step 4: keep Elasticsearch {@link ProductDocument} in sync with PostgreSQL on CUD.
 * List/search API still uses PostgreSQL until step 5.
 */
@Component
@RequiredArgsConstructor
public class ProductSearchIndexer {

    private final ProductSearchRepository productSearchRepository;
    private final ProductRepository productRepository;

    public void index(Product product) {
        Product source = resolveProductForIndex(product);
        productSearchRepository.save(ProductDocument.from(source));
    }

    public void delete(Long productId) {
        productSearchRepository.deleteById(productId);
    }

    /**
     * {@code created_at} is DB-generated ({@code insertable = false}); reload once after insert if needed.
     */
    private Product resolveProductForIndex(Product product) {
        if (product.getCreatedAt() != null || product.getId() == null) {
            return product;
        }
        return productRepository.findById(product.getId()).orElse(product);
    }
}
