package com.catalog.consolidation.repository;

import com.catalog.consolidation.model.Product;
import com.catalog.consolidation.model.ProductEntry;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

public class JdbcCatalogRepository implements CatalogRepository {

    private static final String FIND_ALL_PRODUCTS_SQL =
            "SELECT Id, Name, Brand, Category FROM Product ORDER BY Id";
    private static final String INSERT_PRODUCT_SQL =
            "INSERT INTO Product (Name, Brand, Category) VALUES (?, ?, ?) RETURNING Id";
    private static final String FIND_SELLER_PRODUCT_SQL = """
            SELECT Product.Id AS Id, Product.Name AS Name,
                   Product.Brand AS Brand, Product.Category AS Category
            FROM SellerProduct
            JOIN Product ON Product.Id = SellerProduct.ProductId
            WHERE SellerProduct.SellerName = ? AND SellerProduct.SellerProductId = ?
            """;
    private static final String INSERT_SELLER_PRODUCT_SQL =
            "INSERT INTO SellerProduct (SellerName, ProductId, SellerProductId) VALUES (?, ?, ?)";

    @Override
    public List<Product> findAllProducts(Connection connection) throws SQLException {
        Objects.requireNonNull(connection, "connection is required");

        List<Product> products = new ArrayList<>();

        try (PreparedStatement statement = connection.prepareStatement(FIND_ALL_PRODUCTS_SQL);
             ResultSet resultSet = statement.executeQuery()) {
            while (resultSet.next()) {
                products.add(mapProduct(resultSet));
            }
        }

        return products;
    }

    @Override
    public long insertProduct(Connection connection, ProductEntry entry) throws SQLException {
        Objects.requireNonNull(connection, "connection is required");
        Objects.requireNonNull(entry, "entry is required");
        Objects.requireNonNull(entry.name(), "Product name is required");

        try (PreparedStatement statement = connection.prepareStatement(INSERT_PRODUCT_SQL)) {
            statement.setString(1, entry.name());
            statement.setString(2, entry.brand());
            statement.setString(3, entry.category());

            try (ResultSet resultSet = statement.executeQuery()) {
                if (!resultSet.next()) {
                    throw new SQLException("Inserting product did not return a generated Id");
                }
                return resultSet.getLong("Id");
            }
        }
    }

    @Override
    public Optional<Product> findSellerProduct(
            Connection connection,
            String sellerName,
            String sellerProductId
    ) throws SQLException {
        Objects.requireNonNull(connection, "connection is required");
        Objects.requireNonNull(sellerName, "sellerName is required");
        requireSellerProductId(sellerProductId);

        try (PreparedStatement statement = connection.prepareStatement(FIND_SELLER_PRODUCT_SQL)) {
            statement.setString(1, sellerName);
            statement.setString(2, sellerProductId);

            try (ResultSet resultSet = statement.executeQuery()) {
                return resultSet.next()
                        ? Optional.of(mapProduct(resultSet))
                        : Optional.empty();
            }
        }
    }

    @Override
    public void insertSellerProduct(
            Connection connection,
            String sellerName,
            String sellerProductId,
            long productId
    ) throws SQLException {
        Objects.requireNonNull(connection, "connection is required");
        Objects.requireNonNull(sellerName, "sellerName is required");
        requireSellerProductId(sellerProductId);

        try (PreparedStatement statement = connection.prepareStatement(INSERT_SELLER_PRODUCT_SQL)) {
            statement.setString(1, sellerName);
            statement.setLong(2, productId);
            statement.setString(3, sellerProductId);
            statement.executeUpdate();
        }
    }

    private static Product mapProduct(ResultSet resultSet) throws SQLException {
        return new Product(
                resultSet.getLong("Id"),
                resultSet.getString("Name"),
                resultSet.getString("Brand"),
                resultSet.getString("Category")
        );
    }

    private static void requireSellerProductId(String sellerProductId) {
        if (sellerProductId == null || sellerProductId.isBlank()) {
            throw new IllegalArgumentException("Seller product ID is required");
        }
    }
}
