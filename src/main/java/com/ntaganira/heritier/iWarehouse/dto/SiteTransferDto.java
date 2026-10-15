package com.ntaganira.heritier.iWarehouse.dto;

import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.springframework.format.annotation.DateTimeFormat;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * <pre>
 * - Project   : iWarehouse - Glass Warehouse &amp; Mobile POS (ERP-Lite)
 * - Package   : com.ntaganira.heritier.iWarehouse.dto
 * - File      : SiteTransferDto.java
 * - Date      : 2026. 10. 15.
 * - User      : Hntaganira
 * - Desc      : The transfer request form (IMP 11.1, TRF-007): from and to which site, why, the day it should leave and the
 *               glass asked (rows; empty rows are dropped). The service checks everything.
 * </pre>
 */
@Getter
@Setter
@NoArgsConstructor
public class SiteTransferDto {

    private UUID fromSiteId;

    private UUID toSiteId;

    private String reason;

    @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
    private LocalDate expectedDispatch;

    private List<Line> lines = new ArrayList<>();

    @Getter
    @Setter
    @NoArgsConstructor
    public static class Line {
        private UUID productId;
        private Integer widthMm;
        private Integer heightMm;
        private Integer quantity;

        public boolean isBlank() {
            return productId == null && widthMm == null && heightMm == null && quantity == null;
        }
    }
}
