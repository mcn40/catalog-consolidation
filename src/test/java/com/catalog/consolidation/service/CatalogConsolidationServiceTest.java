package com.catalog.consolidation.service;

import com.catalog.consolidation.matcher.ProductMatcher;
import com.catalog.consolidation.model.Product;
import com.catalog.consolidation.model.ProductEntry;
import com.catalog.consolidation.repository.JdbcCatalogRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.sqlite.SQLiteDataSource;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.List;
import java.util.UUID;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

class CatalogConsolidationServiceTest {

    @TempDir
    Path tempDir;

    private SQLiteDataSource dataSource;
    private CatalogConsolidationService service;

    @BeforeEach
    void setUp() throws Exception {
        Path databasePath = tempDir.resolve("catalog.db");
        dataSource = new SQLiteDataSource();
        dataSource.setUrl("jdbc:sqlite:" + databasePath);
        service = new CatalogConsolidationService(
                dataSource,
                new JdbcCatalogRepository(),
                new ProductMatcher()
        );

        try (Connection connection = DriverManager.getConnection("jdbc:sqlite:" + databasePath);
             Statement statement = connection.createStatement()) {
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

    @Test
    void consolidatesSequentiallyReusesProductsAndSkipsExistingAssociations() throws Exception {
        long existingProductId = insertProduct("iPhone 15", "Apple", "Phones");
        String repeatedId = UUID.randomUUID().toString();
        ProductEntry firstSeller = entry("Seller A", repeatedId, "iPhone 15", "Apple", "Phones");
        ProductEntry repeatedAssociation = entry("Seller A", repeatedId, "IPHONE   15", "Apple", "Electronics");
        ProductEntry secondSeller = entry("Seller B", UUID.randomUUID().toString(), "IPHONE 15", "Apple", "Electronics");
        ProductEntry newProduct = entry("Seller C", UUID.randomUUID().toString(), "Tablet", "Example", "Devices");

        service.consolidate(List.of(firstSeller, repeatedAssociation, secondSeller, newProduct));

        assertEquals(List.of(
                new Product(existingProductId, "iPhone 15", "Apple", "Phones"),
                new Product(existingProductId + 1, "Tablet", "Example", "Devices")
        ), findAllProducts());
        assertEquals(3, countSellerProducts());
        assertEquals(existingProductId, associatedProductId("Seller A", repeatedId));
        assertEquals(existingProductId, associatedProductId("Seller B", secondSeller.id()));
    }

    @Test
    void reusesProductInsertedEarlierInSameBatch() throws Exception {
        String firstSellerProductId = UUID.randomUUID().toString();
        String secondSellerProductId = UUID.randomUUID().toString();
        ProductEntry firstEntry = entry(
                "Seller A",
                firstSellerProductId,
                "Wireless Mouse",
                "Logitech",
                "Accessories"
        );
        ProductEntry secondEntry = entry(
                "Seller B",
                secondSellerProductId,
                "  wireless   mouse ",
                "LOGITECH",
                "Computer accessories"
        );

        service.consolidate(List.of(firstEntry, secondEntry));

        List<Product> products = findAllProducts();
        assertEquals(1, products.size());
        assertEquals("Wireless Mouse", products.getFirst().name());
        assertEquals("Logitech", products.getFirst().brand());
        assertEquals(2, countSellerProducts());
        assertEquals(products.getFirst().id(), associatedProductId("Seller A", firstSellerProductId));
        assertEquals(products.getFirst().id(), associatedProductId("Seller B", secondSellerProductId));
    }

    @Test
    void rollsBackEntireBatchWhenLaterEntryIsInvalid() throws Exception {
        ProductEntry validEntry = entry(
                "Seller A",
                UUID.randomUUID().toString(),
                "New product",
                "Example",
                "Devices"
        );
        ProductEntry invalidEntry = entry(
                " ",
                UUID.randomUUID().toString(),
                "Another product",
                "Example",
                "Devices"
        );

        assertThrows(IllegalArgumentException.class,
                () -> service.consolidate(List.of(validEntry, invalidEntry)));

        assertEquals(List.of(), findAllProducts());
        assertEquals(0, countSellerProducts());
    }

    @Test
    void reimportOfEquivalentSellerProductSkipsRegardlessOfCategoryOrFormatting() throws Exception {
        long productId = insertProduct("iPhone 15", "Apple", "Phones");
        String sellerProductId = UUID.randomUUID().toString();
        insertSellerProduct("Seller A", productId, sellerProductId);

        service.consolidate(List.of(
                entry("Seller A", sellerProductId, "iPhone 15", "Apple", "Electronics"),
                entry("Seller A", sellerProductId, "  IPHONE   15 ", "APPLE", "Mobile devices")
        ));

        assertEquals(List.of(new Product(productId, "iPhone 15", "Apple", "Phones")), findAllProducts());
        assertEquals(1, countSellerProducts());
        assertEquals(productId, associatedProductId("Seller A", sellerProductId));
    }

    @Test
    void importingSameBatchTwiceDoesNotCreateDuplicates() throws Exception {
        ProductEntry entry = entry(
                "Seller A",
                UUID.randomUUID().toString(),
                "Wireless Mouse",
                "Logitech",
                "Accessories"
        );

        service.consolidate(List.of(entry));
        service.consolidate(List.of(entry));

        assertEquals(1, findAllProducts().size());
        assertEquals(1, countSellerProducts());
    }

    @Test
    void importsAndReimportsNonUuidSellerIdentifierIdempotently() throws Exception {
        String sellerProductId = "seller-item/42?variant=blue";
        ProductEntry entry = entry(
                "Seller A",
                sellerProductId,
                "Wireless Mouse",
                "Logitech",
                "Accessories"
        );

        service.consolidate(List.of(entry));
        service.consolidate(List.of(entry));

        assertEquals(1, findAllProducts().size());
        assertEquals(1, countSellerProducts());
        assertEquals(1, countSellerProductId(sellerProductId));
        assertEquals(findAllProducts().getFirst().id(), associatedProductId("Seller A", sellerProductId));
    }

    @Test
    void rejectsNullOrBlankSellerProductIds() throws Exception {
        for (String sellerProductId : java.util.Arrays.asList(null, " \t")) {
            ProductEntry entry = entry(
                    "Seller A",
                    sellerProductId,
                    "Wireless Mouse",
                    "Logitech",
                    "Accessories"
            );

            assertThrows(IllegalArgumentException.class,
                    () -> service.consolidate(List.of(entry)));
        }

        assertEquals(List.of(), findAllProducts());
        assertEquals(0, countSellerProducts());
    }

    @Test
    void conflictingDuplicateEntriesInSameBatchRollBackBothTables() throws Exception {
        String sellerProductId = UUID.randomUUID().toString();
        ProductEntry firstEntry = entry(
                "Seller A",
                sellerProductId,
                "Wireless Mouse",
                "Logitech",
                "Accessories"
        );
        ProductEntry conflictingEntry = entry(
                "Seller A",
                sellerProductId,
                "Keyboard",
                "Logitech",
                "Accessories"
        );

        assertThrows(IllegalStateException.class,
                () -> service.consolidate(List.of(firstEntry, conflictingEntry)));

        assertEquals(List.of(), findAllProducts());
        assertEquals(0, countSellerProducts());
    }

    @Test
    void conflictingSellerProductRollsBackEntireBatch() throws Exception {
        String sellerProductId = UUID.randomUUID().toString();

        ProductEntry original = entry(
                "Seller A", sellerProductId,
                "iPhone 15", "Apple", "Phones"
        );

        service.consolidate(List.of(original));

        ProductEntry newProduct = entry(
                "Seller B", UUID.randomUUID().toString(),
                "Wireless Mouse", "Logitech", "Accessories"
        );

        ProductEntry conflict = entry(
                "Seller A", sellerProductId,
                "Galaxy S23", "Samsung", "Phones"
        );

        assertThrows(IllegalStateException.class,
                () -> service.consolidate(List.of(newProduct, conflict)));

        assertEquals(1, findAllProducts().size());
        assertEquals(1, countSellerProducts());
    }

    @Test
    void safelyPersistsSqlInjectionLikeBrand() throws Exception {
        ProductEntry entry = new ProductEntry(
                "security-test-id",
                "TestSeller",
                "Security Test Product",
                "TestBrand'; SELECT 1; --",
                "Electronics"
        );

        service.consolidate(List.of(entry));

        Product product = findAllProducts().getFirst();

        assertEquals("TestBrand'; SELECT 1; --", product.brand());
        assertEquals(1, countSellerProducts());
    }

    @ParameterizedTest
    @MethodSource("conflictingReimports")
    void reimportWithDifferentProductIdentityRejectsAndRollsBackBatch(
            String name,
            String brand
    ) throws Exception {
        long existingProductId = insertProduct("iPhone 15", "Apple", "Phones");
        String sellerProductId = UUID.randomUUID().toString();
        insertSellerProduct("Seller A", existingProductId, sellerProductId);
        ProductEntry newProduct = entry(
                "Seller B",
                UUID.randomUUID().toString(),
                "New product",
                "Example",
                "Devices"
        );
        ProductEntry conflictingReimport =
                entry("Seller A", sellerProductId, name, brand, "Phones");

        IllegalStateException exception = assertThrows(IllegalStateException.class,
                () -> service.consolidate(List.of(newProduct, conflictingReimport)));

        assertTrue(exception.getMessage().contains("id=" + existingProductId));
        assertTrue(exception.getMessage().contains("name='iPhone 15'"));
        assertTrue(exception.getMessage().contains("brand='Apple'"));
        assertTrue(exception.getMessage().contains("name='" + name + "'"));
        assertTrue(exception.getMessage().contains("brand='" + brand + "'"));
        assertEquals(List.of(new Product(existingProductId, "iPhone 15", "Apple", "Phones")),
                findAllProducts());
        assertEquals(1, countSellerProducts());
        assertEquals(existingProductId, associatedProductId("Seller A", sellerProductId));
    }

    private static Stream<Arguments> conflictingReimports() {
        return Stream.of(
                Arguments.of("iPhone 16", "Apple"),
                Arguments.of("iPhone 15", "Samsung")
        );
    }

    private long insertProduct(String name, String brand, String category) throws Exception {
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(
                     "INSERT INTO Product (Name, Brand, Category) VALUES (?, ?, ?) RETURNING Id")) {
            statement.setString(1, name);
            statement.setString(2, brand);
            statement.setString(3, category);
            try (ResultSet resultSet = statement.executeQuery()) {
                resultSet.next();
                return resultSet.getLong(1);
            }
        }
    }

    private void insertSellerProduct(String sellerName, long productId, String sellerProductId) throws Exception {
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement("""
                     INSERT INTO SellerProduct (SellerName, ProductId, SellerProductId)
                     VALUES (?, ?, ?)
                     """)) {
            statement.setString(1, sellerName);
            statement.setLong(2, productId);
            statement.setString(3, sellerProductId);
            statement.executeUpdate();
        }
    }

    private List<Product> findAllProducts() throws Exception {
        try (Connection connection = dataSource.getConnection();
             Statement statement = connection.createStatement();
             ResultSet resultSet = statement.executeQuery(
                     "SELECT Id, Name, Brand, Category FROM Product ORDER BY Id")) {
            java.util.ArrayList<Product> products = new java.util.ArrayList<>();
            while (resultSet.next()) {
                products.add(new Product(
                        resultSet.getLong("Id"),
                        resultSet.getString("Name"),
                        resultSet.getString("Brand"),
                        resultSet.getString("Category")
                ));
            }
            return products;
        }
    }

    private int countSellerProducts() throws Exception {
        try (Connection connection = dataSource.getConnection();
             Statement statement = connection.createStatement();
             ResultSet resultSet = statement.executeQuery("SELECT COUNT(*) FROM SellerProduct")) {
            resultSet.next();
            return resultSet.getInt(1);
        }
    }

    private int countSellerProductId(String sellerProductId) throws Exception {
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(
                     "SELECT COUNT(*) FROM SellerProduct WHERE SellerProductId = ?")) {
            statement.setString(1, sellerProductId);
            try (ResultSet resultSet = statement.executeQuery()) {
                resultSet.next();
                return resultSet.getInt(1);
            }
        }
    }

    private long associatedProductId(String sellerName, String sellerProductId) throws Exception {
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement("""
                     SELECT ProductId FROM SellerProduct
                     WHERE SellerName = ? AND SellerProductId = ?
                     """)) {
            statement.setString(1, sellerName);
            statement.setString(2, sellerProductId);
            try (ResultSet resultSet = statement.executeQuery()) {
                resultSet.next();
                return resultSet.getLong(1);
            }
        }
    }

    private static ProductEntry entry(
            String sellerName,
            String id,
            String name,
            String brand,
            String category
    ) {
        return new ProductEntry(id, sellerName, name, brand, category);
    }
}
