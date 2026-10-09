package com.ntaganira.heritier.iWarehouse.dto;

import com.ntaganira.heritier.iWarehouse.enums.PaymentMethod;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * <pre>
 * - Project   : iWarehouse - Glass Warehouse &amp; Mobile POS (ERP-Lite)
 * - Package   : com.ntaganira.heritier.iWarehouse.dto
 * - File      : CreditNoteDto.java
 * - Date      : 2026. 10. 09.
 * - User      : Hntaganira
 * - Desc      : The return form (POS-09): the invoice, each unit the customer took (ticked if it comes back, and where
 *               it goes), the rack for glass back in stock, the reason and how the refund goes. The cancel form uses
 *               its sizes instead: the pieces of each size of an order given up. Checked by CreditNoteService.
 * </pre>
 */
@Getter
@Setter
@NoArgsConstructor
public class CreditNoteDto {

    private UUID invoiceId;

    private List<Item> items = new ArrayList<>();

    /** Cancelling an order: the pieces of each size given up. */
    private List<Size> sizes = new ArrayList<>();

    /** Where glass back in stock goes: a rack or slot. */
    private UUID locationId;

    private String reason;

    /** How the refund goes; CREDIT = to the customer's account. */
    private PaymentMethod refundMethod;

    private String refundReference;

    /** A unit the customer took on the invoice: ticked when it comes back; cullet when it cannot be sold again. */
    @Getter
    @Setter
    @NoArgsConstructor
    public static class Item {

        private UUID unitId;

        private boolean selected;

        private boolean cullet;
    }

    /** A size of an order: how many of its pieces not handed over are given up. */
    @Getter
    @Setter
    @NoArgsConstructor
    public static class Size {

        private UUID lineId;

        private Integer quantity;
    }
}
