package com.catalog.consolidation.repository;

import com.catalog.consolidation.model.Product;
import com.catalog.consolidation.model.ProductEntry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

class JdbcCatalogRepositoryTest {

    private Connection connection;
    private final JdbcCatalogRepository repository = new JdbcCatalogRepository();

    @BeforeEach
    void setUp() throws Exception {
        connection = DriverManager.getConnection("jdbc:sqlite::memory:");
        try (Statement statement = connection.createStatement()) {
            statement.execute("PRAGMA foreign_keys = ON");
            statement.execute("""
                    CREATE TABLE Product (
                        Id INTEGER PRIMARY KEY AUTOINCREMENT,
                        Name TEXT NOT NULL,
                        Brand TEXT,
                        Category TEXT
                    )
                    """);
            statement.execute("""
                    CREATE TABLE SellerProduct (
                        Id INTEGER PRIMARY KEY AUTOINCREMENT,
                        SellerName TEXT NOT NULL,
                        ProductId INTEGER NOT NULL,
                        SellerProductId TEXT NOT NULL,
                        CONSTRAINT FK_Product_Id FOREIGN KEY (ProductId) REFERENCES Product (Id),
                        CONSTRAINT UQ_SellerProduct_Seller_SellerProductId
                            UNIQUE (SellerName, SellerProductId)
                    )
                    """);
        }
    }

    @AfterEach
    void tearDown() throws Exception {
        connection.close();
    }

    @Test
    void findAllProductsReturnsRowsIncludingOptionalNullFields() throws Exception {
        try (Statement statement = connection.createStatement()) {
            statement.executeUpdate("""
                    INSERT INTO Product (Id, Name, Brand, Category)
                    VALUES (8, 'Wireless Mouse', 'Logitech', 'Accessories'),
                           (9, 'Unbranded item', NULL, NULL)
                    """);
        }

        assertEquals(List.of(
                new Product(8, "Wireless Mouse", "Logitech", "Accessories"),
                new Product(9, "Unbranded item", null, null)
        ), repository.findAllProducts(connection));
    }

    @Test
    void insertProductReturnsGeneratedIdAndPersistsProductFields() throws Exception {
        ProductEntry entry = new ProductEntry(
                UUID.randomUUID().toString(),
                "Example seller",
                "Wireless Mouse",
                "Logitech",
                "Accessories"
        );

        long id = repository.insertProduct(connection, entry);

        assertEquals(1, id);
        assertEquals(List.of(new Product(id, "Wireless Mouse", "Logitech", "Accessories")),
                repository.findAllProducts(connection));
    }

    @Test
    void insertProductPreservesNullOptionalFields() throws Exception {
        ProductEntry entry = new ProductEntry(
                UUID.randomUUID().toString(),
                "Example seller",
                "Unbranded item",
                null,
                null
        );

        long id = repository.insertProduct(connection, entry);
        Product product = repository.findAllProducts(connection).getFirst();

        assertEquals(id, product.id());
        assertEquals("Unbranded item", product.name());
        assertNull(product.brand());
        assertNull(product.category());
    }

    @Test
    void insertProductCanBeRolledBackByCaller() throws Exception {
        connection.setAutoCommit(false);
        ProductEntry entry = new ProductEntry(
                UUID.randomUUID().toString(),
                "Example seller",
                "Temporary product",
                "Example brand",
                "Example category"
        );

        long id = repository.insertProduct(connection, entry);

        assertEquals(List.of(new Product(
                id,
                "Temporary product",
                "Example brand",
                "Example category"
        )), repository.findAllProducts(connection));
        assertFalse(connection.getAutoCommit());

        connection.rollback();

        assertEquals(List.of(), repository.findAllProducts(connection));
        assertFalse(connection.getAutoCommit());
    }

    @Test
    void insertSellerProductStoresExternalIdAsTextAndCanBeFound() throws Exception {
        long productId = insertTestProduct();
        String sellerProductId = "seller-item/42?variant=blue";

        assertEquals(Optional.empty(),
                repository.findSellerProduct(connection, "Example seller", sellerProductId));

        repository.insertSellerProduct(connection, "Example seller", sellerProductId, productId);

        assertEquals(Optional.of(new Product(productId, "Test product", "Test brand", "Test category")),
                repository.findSellerProduct(connection, "Example seller", sellerProductId));
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT SellerName, ProductId, SellerProductId, typeof(SellerProductId) AS IdType " +
                        "FROM SellerProduct");
             ResultSet resultSet = statement.executeQuery()) {
            resultSet.next();
            assertEquals("Example seller", resultSet.getString("SellerName"));
            assertEquals(productId, resultSet.getLong("ProductId"));
            assertEquals(sellerProductId, resultSet.getString("SellerProductId"));
            assertEquals("text", resultSet.getString("IdType"));
        }
    }

    @Test
    void duplicateSellerProductForSameSellerIsRejected() throws Exception {
        long productId = insertTestProduct();
        String sellerProductId = UUID.randomUUID().toString();

        repository.insertSellerProduct(connection, "Example seller", sellerProductId, productId);

        assertThrows(SQLException.class,
                () -> repository.insertSellerProduct(
                        connection,
                        "Example seller",
                        sellerProductId,
                        productId
                ));
    }

    @Test
    void findSellerProductIsScopedToSeller() throws Exception {
        long productId = insertTestProduct();
        String sellerProductId = UUID.randomUUID().toString();
        repository.insertSellerProduct(connection, "Seller A", sellerProductId, productId);

        assertEquals(Optional.of(new Product(productId, "Test product", "Test brand", "Test category")),
                repository.findSellerProduct(connection, "Seller A", sellerProductId));
        assertEquals(Optional.empty(), repository.findSellerProduct(connection, "Seller B", sellerProductId));
    }

    @Test
    void insertSellerProductRejectsUnknownProduct() {
        String sellerProductId = UUID.randomUUID().toString();

        assertThrows(SQLException.class,
                () -> repository.insertSellerProduct(
                        connection,
                        "Example seller",
                        sellerProductId,
                        99999L
                ));
    }

    @Test
    void sellerProductMethodsRejectNullOrBlankExternalIds() {
        assertThrows(IllegalArgumentException.class,
                () -> repository.findSellerProduct(connection, "Example seller", null));
        assertThrows(IllegalArgumentException.class,
                () -> repository.findSellerProduct(connection, "Example seller", "  "));
        assertThrows(IllegalArgumentException.class,
                () -> repository.insertSellerProduct(connection, "Example seller", null, 1));
        assertThrows(IllegalArgumentException.class,
                () -> repository.insertSellerProduct(connection, "Example seller", "", 1));
    }

    private long insertTestProduct() throws Exception {
        return repository.insertProduct(connection, new ProductEntry(
                UUID.randomUUID().toString(),
                "Example seller",
                "Test product",
                "Test brand",
                "Test category"
        ));
    }
}
