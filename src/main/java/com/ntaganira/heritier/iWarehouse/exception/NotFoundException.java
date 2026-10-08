package com.ntaganira.heritier.iWarehouse.exception;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ResponseStatus;

/**
 * <pre>
 * - Project   : iWarehouse - Glass Warehouse &amp; Mobile POS (ERP-Lite)
 * - Package   : com.ntaganira.heritier.iWarehouse.exception
 * - File      : NotFoundException.java
 * - Date      : 2026. 10. 07.
 * - User      : Hntaganira
 * - Desc      : The requested record does not exist; answered with the 404 page
 * </pre>
 */
@ResponseStatus(HttpStatus.NOT_FOUND)
public class NotFoundException extends RuntimeException {

    public NotFoundException(String entity, Object id) {
        super(entity + " not found: " + id);
    }
}
