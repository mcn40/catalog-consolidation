package com.catalog.consolidation.matcher;

import com.catalog.consolidation.model.Product;
import com.catalog.consolidation.model.ProductEntry;
import com.catalog.consolidation.model.ProductKey;

import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.text.Normalizer;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

/**
 * Matches products using a deterministic key composed of normalized
 * product name and brand.
 * <p>
 * The strategy favors avoiding false positive matches over detecting
 * every possible equivalent product. Category is intentionally excluded
 * because sellers may classify the same product differently.
 */
public class ProductMatcher {

    private static final Map<String, String> NAME_ALIASES = Map.of(
            "processador", "processor",
            "roteador", "router"
    );

    public boolean matches(Product existing, ProductEntry incoming) {
        return keyFor(existing).equals(keyFor(incoming));
    }

    public ProductKey keyFor(Product product) {
        Objects.requireNonNull(product, "Product is required");
        Objects.requireNonNull(product.name(), "Product name is required");

        return new ProductKey(
                normalizeName(product.name()),
                normalizeBrand(product.brand())
        );
    }

    public ProductKey keyFor(ProductEntry entry) {
        Objects.requireNonNull(entry, "Product entry is required");
        Objects.requireNonNull(entry.name(), "Product entry name is required");

        return new ProductKey(
                normalizeName(entry.name()),
                normalizeBrand(entry.brand())
        );
    }

    private String normalizeName(String value) {
        String normalized = normalize(value);
        String[] terms = normalized.split(" ");

        for (int i = 0; i < terms.length; i++) {
            terms[i] = NAME_ALIASES.getOrDefault(terms[i], terms[i]);
        }

        return String.join(" ", terms);
    }

    private String normalizeBrand(String value) {
        String normalized = normalize(value);
        return normalized == null
                ? null
                : normalized.replace("'", "").replace("’", "");
    }

    private String normalize(String value) {
        if (value == null) {
            return null;
        }

        String repaired = repairMojibake(value);

        String normalized = Normalizer.normalize(repaired, Normalizer.Form.NFD)
                .replaceAll("\\p{M}+", "");

        return normalized.trim()
                .toLowerCase(Locale.ROOT)
                .replaceAll("\\s+", " ")
                .replaceAll("(?<=\\d)(?:''|\")", "");
    }

    /**
     * Attempts to repair UTF-8 text incorrectly decoded as ISO-8859-1.
     * Returns the original value if the conversion cannot be performed safely.
     */
    private static String repairMojibake(String value) {
        if (!value.contains("Ã") && !value.contains("Â")) {
            return value;
        }

        try {
            ByteBuffer bytes = StandardCharsets.ISO_8859_1
                    .newEncoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .encode(java.nio.CharBuffer.wrap(value));

            return StandardCharsets.UTF_8
                    .newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(bytes)
                    .toString();

        } catch (CharacterCodingException e) {
            return value;
        }
    }
}
