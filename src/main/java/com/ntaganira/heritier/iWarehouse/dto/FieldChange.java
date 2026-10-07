package com.ntaganira.heritier.iWarehouse.dto;

/** One row of the before/after view: a field, its old and new value, and whether it changed. */
public record FieldChange(String field, Object before, Object after, boolean changed) {
}
