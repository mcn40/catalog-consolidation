# Catalog Consolidation

A Java application that consolidates seller product listings into a shared product catalog, using SQLite for persistence.

The application reads product entries from a JSON file, identifies equivalent products using deterministic matching rules, and associates each seller listing with a canonical product.

## Requirements

- Java 21
- Maven
- SQLite database provided with the challenge

## Project Structure

The application separates responsibilities into four main components:

- **Main:** Reads the input JSON and initializes the application.
- **CatalogConsolidationService:** Coordinates matching, product creation, seller associations, and transactions.
- **ProductMatcher:** Normalizes product attributes and determines product equivalence.
- **JdbcCatalogRepository:** Handles SQLite operations using JDBC and prepared statements.

The implementation uses plain Java and JDBC, without an application framework.

## Setup

Place the input files in the `data/` directory:

```text
data/
├── catalog.db
└── ProductEntry.json
```

The application expects the SQLite database to contain the `Product` and `SellerProduct` tables.

### Database Migration

The original database defines `SellerProductId` as an integer. However, the input contains string identifiers, including values that do not conform to the UUID format. The application treats these identifiers as opaque strings and preserves them as received.

The database schema therefore needs to be migrated before running the application:

- Change `SellerProduct.SellerProductId` to `TEXT`.
- Add a unique constraint on `(SellerName, SellerProductId)`.
- Preserve the foreign key relationship to `Product`.

The `data/catalog.db` included in this repository has already been migrated. **Do not apply the migration to this database again.**

For reference, the migration script is available at `data/migrations/001_seller_product_text.sql`. It should only be applied to a separate copy of the original, unmigrated database:

```bash
sqlite3 path/to/original-catalog.db < data/migrations/001_seller_product_text.sql
```

The migration rebuilds only `SellerProduct`; it does not alter the `Product` table. Apply it only when the database still has the original `INTEGER` definition for `SellerProductId`. The SQLite `foreign_keys` setting is per connection, so the application enables foreign key enforcement on its import connection.

### Database State
The included database has already been migrated and processed using the provided input file.

The consolidation process is idempotent: running the application again with the same input does not create additional products or seller associations.

To reproduce the initial consolidation, use a fresh copy of the original database, apply the migration, and then run the application.

## Running the Application

From a terminal at the project root, run:

```bash
mvn exec:java -Dexec.mainClass=com.catalog.consolidation.Main
```

Alternatively, open the Maven project in IntelliJ IDEA and run:

```text
com.catalog.consolidation.Main
```

By default, the application reads:

- Input: `data/ProductEntry.json`
- Database: `data/catalog.db`

The database location can be overridden using the `CATALOG_DB_PATH` environment variable or the `catalog.db.path` Java system property. The system property takes precedence.

The application prints the number of input entries processed after successful consolidation. On failure it reports an error to stderr and exits with a nonzero status.

## Running Tests

Run the test suite with:

```bash
mvn clean test
```

The tests cover:

- Product normalization and matching
- JDBC persistence and database constraints
- Seller-specific identifiers
- Product reuse and duplicate handling
- Idempotent imports
- Transaction rollback on failure
- End-to-end JSON loading and consolidation

## Matching Strategy

Products are matched using a normalized key composed of:

```text
(normalized product name, normalized brand)
```

The same normalization rules are applied to existing catalog products and incoming seller entries.

Normalization includes:

- Case-insensitive comparison
- Leading, trailing, and repeated whitespace normalization
- Accent removal
- Normalization of inch notation
- Selected brand punctuation normalization (`Levi's`, `Levis`, and `Levi’s` compare equally)
- A small set of explicit Portuguese-to-English product-name aliases (`processador`/`processor` and `roteador`/`router`)
- Best-effort repair of certain malformed text encodings

For example:

```text
Processador AMD Ryzen 9 7950X
Processor AMD Ryzen 9 7950X
```

These entries are considered equivalent when their normalized brands also match.

Category is not part of the matching key because equivalent products may have different seller-defined categories.

### Why Deterministic Matching?

The implementation favors predictable and explainable matches over fuzzy similarity.

Fuzzy matching could identify unrelated products as equivalent and silently merge distinct catalog entries. The explicit normalization rules and aliases make each equivalence decision reproducible and reviewable.

### Matching Limitations

The matching strategy intentionally prioritizes precision over recall. Products are considered equivalent only when their normalized names and brands match exactly.

This approach may produce false negatives for equivalent products with reordered words, abbreviations, or substantially different descriptions. However, it reduces the risk of incorrectly merging distinct models or variants.

The normalization rules and translation aliases were selected based on observed input variations rather than attempting to provide general-purpose semantic product matching.

For a production catalog with more heterogeneous data, a possible extension would be a two-stage matching process: candidate generation followed by similarity scoring using structured attributes such as manufacturer, model number, and product identifiers. Ambiguous matches could be flagged for review instead of automatically merged.
