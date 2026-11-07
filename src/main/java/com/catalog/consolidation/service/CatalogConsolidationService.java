package com.catalog.consolidation.service;

import com.catalog.consolidation.matcher.ProductMatcher;
import com.catalog.consolidation.model.Product;
import com.catalog.consolidation.model.ProductEntry;
import com.catalog.consolidation.model.ProductKey;
import com.catalog.consolidation.repository.CatalogRepository;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

public class CatalogConsolidationService {

    private final DataSource dataSource;
    private final CatalogRepository catalogRepository;
    private final ProductMatcher productMatcher;

    public CatalogConsolidationService(
            DataSource dataSource,
            CatalogRepository catalogRepository,
            ProductMatcher productMatcher) {
        this.dataSource = Objects.requireNonNull(dataSource, "dataSource is required");
        this.catalogRepository = Objects.requireNonNull(catalogRepository, "catalogRepository is required");
        this.productMatcher = Objects.requireNonNull(productMatcher, "productMatcher is required");
    }

    /**
     * Consolidates seller entries into the canonical catalog in a single
     * transaction.
     * <p>
     * Existing seller associations are reused when the incoming product
     * matches the associated canonical product. Conflicting associations
     * cause the entire batch to be rolled back.
     */
    public void consolidate(List<ProductEntry> entries) throws SQLException {
        Objects.requireNonNull(entries, "entries are required");
        if (entries.isEmpty()) {
            return;
        }

        try (Connection connection = dataSource.getConnection()) {
            enableForeignKeys(connection);
            connection.setAutoCommit(false);

            try {
                Map<ProductKey, Product> catalog = new HashMap<>();
                for (Product product : catalogRepository.findAllProducts(connection)) {
                    catalog.putIfAbsent(productMatcher.keyFor(product), product);
                }
                for (ProductEntry entry : entries) {
                    validate(entry);
                    consolidateEntry(connection, entry, catalog);
                }
                connection.commit();
            } catch (SQLException | RuntimeException | Error failure) {
                rollback(connection, failure);
                throw failure;
            }
        }
    }

    private void consolidateEntry(
            Connection connection,
            ProductEntry entry,
            Map<ProductKey, Product> catalog
    ) throws SQLException {
        Optional<Product> existingSellerProduct =
                catalogRepository.findSellerProduct(connection, entry.sellerName(), entry.id());
        if (existingSellerProduct.isPresent()) {
            Product associatedProduct = existingSellerProduct.get();
            if (!productMatcher.matches(associatedProduct, entry)) {
                throw new IllegalStateException(
                        "Seller product ID " + entry.id() + " for seller " + entry.sellerName()
                                + " is already associated with product " + describe(associatedProduct)
                                + ", but the incoming product is " + describe(entry)
                );
            }
            return;
        }

        ProductKey productKey = productMatcher.keyFor(entry);
        Product product = catalog.get(productKey);
        if (product == null) {
            long productId = catalogRepository.insertProduct(connection, entry);
            product = new Product(productId, entry.name(), entry.brand(), entry.category());
            catalog.put(productKey, product);
        }

        catalogRepository.insertSellerProduct(connection, entry.sellerName(), entry.id(), product.id());
    }

    private static String describe(Product product) {
        return "{id=" + product.id()
                + ", name='" + product.name()
                + "', brand='" + product.brand()
                + "', category='" + product.category() + "'}";
    }

    private static String describe(ProductEntry entry) {
        return "{name='" + entry.name()
                + "', brand='" + entry.brand()
                + "', category='" + entry.category() + "'}";
    }

    private static void validate(ProductEntry entry) {
        Objects.requireNonNull(entry, "Catalog entry is required");
        if (entry.id() == null || entry.id().isBlank()) {
            throw new IllegalArgumentException("Seller product ID is required");
        }
        if (entry.sellerName() == null || entry.sellerName().isBlank()) {
            throw new IllegalArgumentException("Seller name is required");
        }
        if (entry.name() == null || entry.name().isBlank()) {
            throw new IllegalArgumentException("Product name is required");
        }
    }

    private static void enableForeignKeys(Connection connection) throws SQLException {
        if (!connection.getAutoCommit()) {
            throw new SQLException("A newly acquired connection must be in auto-commit mode");
        }
        try (Statement statement = connection.createStatement()) {
            statement.execute("PRAGMA foreign_keys = ON");
        }
    }

    private static void rollback(Connection connection, Throwable failure) {
        try {
            connection.rollback();
        } catch (SQLException rollbackFailure) {
            failure.addSuppressed(rollbackFailure);
        }
    }
}
