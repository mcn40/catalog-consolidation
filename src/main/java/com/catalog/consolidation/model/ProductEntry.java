package com.catalog.consolidation.model;

import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;

@JsonNaming(PropertyNamingStrategies.UpperCamelCaseStrategy.class)
public record ProductEntry(
        String id,
        String sellerName,
        String name,
        String brand,
        String category
) {}