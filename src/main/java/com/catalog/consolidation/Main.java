package com.catalog.consolidation;

import com.catalog.consolidation.matcher.ProductMatcher;
import com.catalog.consolidation.model.ProductEntry;
import com.catalog.consolidation.repository.JdbcCatalogRepository;
import com.catalog.consolidation.service.CatalogConsolidationService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.sqlite.SQLiteDataSource;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;

public class Main {

    private static final Path INPUT_PATH = Path.of("data", "ProductEntry.json");
    private static final String DEFAULT_DATABASE_PATH = "data/catalog.db";
    private static final String DATABASE_PATH_PROPERTY = "catalog.db.path";
    private static final String DATABASE_PATH_ENV = "CATALOG_DB_PATH";

    public static void main(String[] args) {
        try {
            run(INPUT_PATH, configuredDatabasePath());
        } catch (Exception exception) {
            System.err.println("Catalog consolidation failed: " + exception.getMessage());
            System.exit(1);
        }
    }

    static void run(Path inputPath, Path databasePath) throws Exception {
        if (!Files.isRegularFile(databasePath)) {
            throw new IllegalArgumentException(
                    "Database file not found: " + databasePath.toAbsolutePath()
            );
        }

        ProductEntry[] entries = new ObjectMapper().readValue(inputPath.toFile(), ProductEntry[].class);

        SQLiteDataSource dataSource = new SQLiteDataSource();
        dataSource.setUrl("jdbc:sqlite:" + databasePath.toAbsolutePath());

        CatalogConsolidationService service = new CatalogConsolidationService(
                dataSource,
                new JdbcCatalogRepository(),
                new ProductMatcher()
        );
        service.consolidate(Arrays.asList(entries));
        System.out.println("Catalog consolidation succeeded: processed " + entries.length + " entries.");
    }

    private static Path configuredDatabasePath() {
        String path = System.getProperty(DATABASE_PATH_PROPERTY);
        if (path == null || path.isBlank()) {
            path = System.getenv(DATABASE_PATH_ENV);
        }
        if (path == null || path.isBlank()) {
            path = DEFAULT_DATABASE_PATH;
        }
        return Path.of(path);
    }
}
