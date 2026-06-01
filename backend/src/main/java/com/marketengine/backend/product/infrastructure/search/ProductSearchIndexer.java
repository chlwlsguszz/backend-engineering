package com.marketengine.backend.product.infrastructure.search;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

import com.marketengine.backend.product.domain.Product;
import com.marketengine.backend.product.domain.ProductRepository;

/**
 * Step 4: keep Elasticsearch {@link ProductDocument} in sync with PostgreSQL on CUD.
 * List/search API still uses PostgreSQL until step 5.
 */
@Component
public class ProductSearchIndexer {

    private final ObjectProvider<ProductSearchRepository> productSearchRepository;
    private final ProductRepository productRepository;

    public ProductSearchIndexer(
            ObjectProvider<ProductSearchRepository> productSearchRepository,
            ProductRepository productRepository
    ) {
        this.productSearchRepository = productSearchRepository;
        this.productRepository = productRepository;
    }

    public void index(Product product) {
        ProductSearchRepository repository = productSearchRepository.getIfAvailable();
        if (repository == null) {
            return;
        }
        Product source = resolveProductForIndex(product);
        repository.save(ProductDocument.from(source));
    }

    public void delete(Long productId) {
        ProductSearchRepository repository = productSearchRepository.getIfAvailable();
        if (repository == null) {
            return;
        }
        repository.deleteById(productId);
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
