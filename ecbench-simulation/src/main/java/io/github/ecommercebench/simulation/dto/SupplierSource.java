package io.github.ecommercebench.simulation.dto;

/** 某 SKU 的供应商交付占比。 */
public record SupplierSource(String supplier, int unitsDelivered, double share) {}
