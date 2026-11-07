package com.catalog.consolidation;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;

import static org.junit.jupiter.api.Assertions.assertEquals;

class MainTest {

    @TempDir
    Path tempDir;

    @Test
    void runReadsJsonAndConsolidatesIntoConfiguredDatabase() throws Exception {
        Path databasePath = tempDir.resolve("catalog.db");
        createSchema(databasePath);
        Path inputPath = tempDir.resolve("ProductEntry.json");
        Files.writeString(inputPath, """
                [
                  {
                    "Id": "a1b2c3d4-e5f6-4a5b-8c9d-0e1f2a3b4c5d",
                    "SellerName": "Seller A",
                    "Name": "Wireless Mouse",
                    "Brand": "Logitech",
                    "Category": "Accessories"
                  },
                  {
                    "Id": "b1b2c3d4-e5f6-4a5b-8c9d-0e1f2a3b4c5d",
                    "SellerName": "Seller B",
                    "Name": " wireless   mouse ",
                    "Brand": "LOGITECH",
                    "Category": "Computer accessories"
                  }
                ]
                """);

        Main.run(inputPath, databasePath);

        try (Connection connection = DriverManager.getConnection("jdbc:sqlite:" + databasePath);
             Statement statement = connection.createStatement()) {
            assertEquals(1, count(statement, "Product"));
            assertEquals(2, count(statement, "SellerProduct"));
        }
    }

    @Test
    void runImportsAndReimportsMalformedUuidAsOpaqueSellerId() throws Exception {
        Path databasePath = tempDir.resolve("catalog.db");
        createSchema(databasePath);
        Path inputPath = tempDir.resolve("ProductEntry.json");
        String sellerProductId = "uddd0000-eeee-4111-ffff-aaaa22223333";
        Files.writeString(inputPath, """
                [
                  {
                    "Id": "%s",
                    "SellerName": "SmartHomeStore",
                    "Name": "Smart TV Samsung 55",
                    "Brand": "Samsung",
                    "Category": "Electronics"
                  }
                ]
                """.formatted(sellerProductId));

        Main.run(inputPath, databasePath);
        Main.run(inputPath, databasePath);

        try (Connection connection = DriverManager.getConnection("jdbc:sqlite:" + databasePath);
             Statement statement = connection.createStatement()) {
            assertEquals(1, count(statement, "Product"));
            assertEquals(1, count(statement, "SellerProduct"));
        }
        try (Connection connection = DriverManager.getConnection("jdbc:sqlite:" + databasePath);
             PreparedStatement statement = connection.prepareStatement(
                     "SELECT SellerProductId FROM SellerProduct WHERE SellerName = ?")) {
            statement.setString(1, "SmartHomeStore");
            try (ResultSet resultSet = statement.executeQuery()) {
                resultSet.next();
                assertEquals(sellerProductId, resultSet.getString("SellerProductId"));
            }
        }
    }

    @Test
    void runPropagatesInvalidEntryFailure() throws Exception {
        Path databasePath = tempDir.resolve("catalog.db");
        createSchema(databasePath);
        Path inputPath = tempDir.resolve("ProductEntry.json");
        Files.writeString(inputPath, """
                [
                  {
                    "Id": "a1b2c3d4-e5f6-4a5b-8c9d-0e1f2a3b4c5d",
                    "SellerName": "Seller A",
                    "Name": " ",
                    "Brand": "Logitech",
                    "Category": "Accessories"
                  }
                ]
                """);

        org.junit.jupiter.api.Assertions.assertThrows(
                IllegalArgumentException.class,
                () -> Main.run(inputPath, databasePath)
        );

        try (Connection connection = DriverManager.getConnection("jdbc:sqlite:" + databasePath);
             Statement statement = connection.createStatement()) {
            assertEquals(0, count(statement, "Product"));
            assertEquals(0, count(statement, "SellerProduct"));
        }
    }

    private static void createSchema(Path databasePath) throws Exception {
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

    private static int count(Statement statement, String table) throws Exception {
        try (ResultSet resultSet = statement.executeQuery("SELECT COUNT(*) FROM " + table)) {
            resultSet.next();
            return resultSet.getInt(1);
        }
    }
}
