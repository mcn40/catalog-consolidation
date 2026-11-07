package com.catalog.consolidation.repository;

import com.catalog.consolidation.model.Product;
import com.catalog.consolidation.model.ProductEntry;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.List;
import java.util.Optional;

public interface CatalogRepository {
    List<Product> findAllProducts(Connection connection)
            throws SQLException;

    long insertProduct(Connection connection, ProductEntry entry)
            throws SQLException;

    Optional<Product> findSellerProduct(
            Connection connection,
            String sellerName,
            String sellerProductId
    ) throws SQLException;

    void insertSellerProduct(
            Connection connection,
            String sellerName,
            String sellerProductId,
            long productId
    ) throws SQLException;
}