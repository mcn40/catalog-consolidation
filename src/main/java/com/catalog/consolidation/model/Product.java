package com.catalog.consolidation.model;

public record Product(
        long id,
        String name,
        String brand,
        String category
) {}
