package com.ntaganira.heritier.iWarehouse.dto;

import com.ntaganira.heritier.iWarehouse.enums.RateSource;
import jakarta.validation.constraints.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.springframework.format.annotation.DateTimeFormat;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Locale;
import java.util.UUID;

/**
 * <pre>
 * - Project   : iWarehouse - Glass Warehouse &amp; Mobile POS (ERP-Lite)
 * - Package   : com.ntaganira.heritier.iWarehouse.dto
 * - File      : ExchangeRateDto.java
 * - Date      : 2026. 10. 07.
 * - User      : Hntaganira
 * - Desc      : Record or correct an exchange rate (ACC-02). Currency, date and source are fixed once
 *               recorded; a correction changes the rate or note and needs a reason. "confirmed" lets a
 *               rate more than 10% away from the previous one through, after the user checked it.
 * </pre>
 */
@Getter
@Setter
@NoArgsConstructor
public class ExchangeRateDto {

    private UUID id;

    @NotBlank(message = "{rate.currency.required}")
    private String currencyCode;

    @NotNull(message = "{rate.date.required}")
    @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
    private LocalDate rateDate;

    @NotNull(message = "{rate.source.required}")
    private RateSource source;

    /** RWF for 1 unit of the currency. */
    @NotNull(message = "{rate.rate.required}")
    @DecimalMin(value = "0.000001", message = "{rate.rate.positive}")
    @Digits(integer = 12, fraction = 6, message = "{rate.rate.digits}")
    private BigDecimal rate;

    @Size(max = 255, message = "{rate.note.size}")
    private String note;

    /** Why a recorded rate is corrected; required for corrections, kept in the change log. */
    @Size(max = 255, message = "{rate.reason.size}")
    private String reason;

    private boolean confirmed;

    public void setCurrencyCode(String currencyCode) {
        this.currencyCode = currencyCode == null ? null : currencyCode.trim().toUpperCase(Locale.ROOT);
    }
}
