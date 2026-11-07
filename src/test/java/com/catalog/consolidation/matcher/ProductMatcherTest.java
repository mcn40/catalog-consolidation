package com.catalog.consolidation.matcher;

import com.catalog.consolidation.model.Product;
import com.catalog.consolidation.model.ProductEntry;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.util.UUID;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ProductMatcherTest {

    private final ProductMatcher matcher = new ProductMatcher();

    @ParameterizedTest(name = "{0}")
    @MethodSource("matchingScenarios")
    void matchesExpectedScenarios(
            String scenario,
            Product existing,
            ProductEntry incoming,
            boolean expected
    ) {
        assertEquals(expected, matcher.matches(existing, incoming), scenario);
    }

    @ParameterizedTest
    @CsvSource({
            "'CÃ¢mera Canon EOS R6', 'Camera Canon EOS R6'",
            "'Câmera Canon EOS R6', 'Camera Canon EOS R6'",
            "'CafÃ©', 'Cafe'",
            "'Café', 'Cafe'"
    })
    void matchesEncodingAndAccentVariations(String existingName, String incomingName) {
        assertTrue(matcher.matches(
                product(existingName, "Canon", null),
                entry(incomingName, "Canon", null)
        ));
    }

    private static Stream<Arguments> matchingScenarios() {
        return Stream.of(
                scenario("Same name and brand",
                        product("iPhone 15", "Apple", "Phones"),
                        entry("iPhone 15", "Apple", "Phones"),
                        true),
                scenario("Extra whitespace in name",
                        product("  iPhone   15  ", "Apple", "Phones"),
                        entry("iPhone 15", "Apple", "Phones"),
                        true),
                scenario("Different name capitalization",
                        product("IPHONE 15", "Apple", "Phones"),
                        entry("iPhone 15", "Apple", "Phones"),
                        true),
                scenario("iPad 12.9 double quote versus two apostrophes",
                        product("Tablet iPad Pro 12.9\"", "Apple", "Tablets"),
                        entry("Tablet iPad Pro 12.9''", "Apple", "Tablets"),
                        true),
                scenario("iPad with missing inch symbol",
                        product("Tablet iPad Pro 12.9\"", "Apple", "Tablets"),
                        entry("Tablet iPad Pro 12.9", "Apple", "Tablets"),
                        true),
                scenario("Same name with different brands",
                        product("iPhone 15", "Apple", "Phones"),
                        entry("iPhone 15", "Samsung", "Phones"),
                        false),
                scenario("Same name and brand with different categories",
                        product("iPhone 15", "Apple", "Phones"),
                        entry("iPhone 15", "Apple", "Electronics"),
                        true),
                scenario("Both brands are null",
                        product("Phone", null, "Phones"),
                        entry("Phone", null, "Phones"),
                        true),
                scenario("Only one brand is null",
                        product("Phone", null, "Phones"),
                        entry("Phone", "Example", "Phones"),
                        false),
                scenario("iPhone 15 versus iPhone 15 Pro",
                        product("iPhone 15", "Apple", "Phones"),
                        entry("iPhone 15 Pro", "Apple", "Phones"),
                        false),
                scenario("RTX 4080 with extra whitespace",
                        product("Graphics Card RTX  4080", "NVIDIA", "Components"),
                        entry("Graphics Card RTX 4080", "NVIDIA", "Components"),
                        true),
                scenario("Levi's matches Levis",
                        product("Jeans 501", "Levi's", "Clothing"),
                        entry("Jeans 501", "Levis", "Apparel"),
                        true),
                scenario("Levi's matches typographic apostrophe brand",
                        product("Jeans 501", "Levi's", "Clothing"),
                        entry("Jeans 501", "Levi’s", "Apparel"),
                        true),
                scenario("Portuguese processador alias matches processor",
                        product("Processador AMD Ryzen 9 7950X", "AMD", "Components"),
                        entry("Processor AMD Ryzen 9 7950X", "AMD", "Hardware"),
                        true),
                scenario("Portuguese roteador alias matches router",
                        product("Roteador WiFi 6 TP-Link", "TP-Link", "Networking"),
                        entry("Router WiFi 6 TP-Link", "TP-Link", "Network"),
                        true),
                scenario("Aliases only apply to whole name terms",
                        product("Microprocessador AMD", "AMD", "Components"),
                        entry("Microprocessor AMD", "AMD", "Components"),
                        false)
        );
    }

    private static Arguments scenario(
            String name,
            Product existing,
            ProductEntry incoming,
            boolean expected
    ) {
        return Arguments.of(name, existing, incoming, expected);
    }

    private static Product product(String name, String brand, String category) {
        return new Product(1, name, brand, category);
    }

    private static ProductEntry entry(String name, String brand, String category) {
        return new ProductEntry(UUID.randomUUID().toString(), "test-seller", name, brand, category);
    }
}
