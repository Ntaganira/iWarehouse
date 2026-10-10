package com.ntaganira.heritier.iWarehouse.entity;

import com.ntaganira.heritier.iWarehouse.audit.AuditIgnore;
import com.ntaganira.heritier.iWarehouse.audit.AuditedEntity;
import com.ntaganira.heritier.iWarehouse.ebm.EbmCodes;
import com.ntaganira.heritier.iWarehouse.enums.EbmReceiptStatus;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * <pre>
 * - Project   : iWarehouse - Glass Warehouse &amp; Mobile POS (ERP-Lite)
 * - Package   : com.ntaganira.heritier.iWarehouse.entity
 * - File      : EbmReceipt.java
 * - Date      : 2026. 10. 10.
 * - User      : Hntaganira
 * - Desc      : An invoice (sale, S) or credit note (refund, R) sent to EBM under its own invoice number (invcNo,
 *               TAX-02, TAX-03): queued when the document is issued, then signed by the VSDC (its receipt numbers,
 *               internal data, signature, SDC ID and MRC are kept and never change: trg_ebm_receipts_signed) or refused.
 *               The attempts, the next one and the last request and answer are kept for the EBM page but not audited
 *               (they change on every retry). The first print is the original, later prints are copies.
 * </pre>
 */
@Entity
@Table(name = "ebm_receipts")
@AuditedEntity(ref = "documentNumber")
@Getter
@Setter
@NoArgsConstructor
public class EbmReceipt extends BaseEntity {

    @Column(name = "invc_no", nullable = false, updatable = false)
    private long invcNo;

    @Column(name = "receipt_type", nullable = false, length = 1, updatable = false)
    private String receiptType;

    @Column(name = "invoice_id", nullable = false, updatable = false)
    private UUID invoiceId;

    @Column(name = "credit_note_id", updatable = false)
    private UUID creditNoteId;

    @Column(name = "document_number", nullable = false, length = 30, updatable = false)
    private String documentNumber;

    @Column(name = "org_invc_no", updatable = false)
    private Long orgInvcNo;

    @Column(name = "purchase_code", length = 6)
    private String purchaseCode;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 10)
    private EbmReceiptStatus status = EbmReceiptStatus.QUEUED;

    @AuditIgnore
    @Column(nullable = false)
    private int attempts;

    @AuditIgnore
    @Column(name = "next_attempt_at")
    private LocalDateTime nextAttemptAt;

    @AuditIgnore
    @Column(name = "last_attempt_at")
    private LocalDateTime lastAttemptAt;

    /** The VSDC's result code, or CFG (settings missing), NET (not reached), WAIT (its sale not signed yet), ERR. */
    @AuditIgnore
    @Column(name = "result_code", length = 5)
    private String resultCode;

    @AuditIgnore
    @Column(name = "last_error", length = 500)
    private String lastError;

    @AuditIgnore
    @Column(name = "request_json", columnDefinition = "TEXT")
    private String requestJson;

    @AuditIgnore
    @Column(name = "response_json", columnDefinition = "TEXT")
    private String responseJson;

    @Column(nullable = false)
    private boolean simulated;

    @Column(name = "rcpt_no")
    private Long rcptNo;

    @Column(name = "tot_rcpt_no")
    private Long totRcptNo;

    @Column(name = "intrl_data", length = 40)
    private String intrlData;

    @Column(name = "rcpt_sign", length = 40)
    private String rcptSign;

    @Column(name = "sdc_id", length = 20)
    private String sdcId;

    @Column(name = "mrc_no", length = 20)
    private String mrcNo;

    @Column(name = "vsdc_date")
    private LocalDateTime vsdcDate;

    @Column(name = "signed_at")
    private LocalDateTime signedAt;

    /** When the original was printed; later prints are copies. */
    @Column(name = "printed_at")
    private LocalDateTime printedAt;

    @Column(nullable = false)
    private int copies;

    public boolean isRefund() {
        return EbmCodes.REFUND.equals(receiptType);
    }

    public boolean isSigned() {
        return status == EbmReceiptStatus.SIGNED;
    }

    public boolean isFailed() {
        return status == EbmReceiptStatus.FAILED;
    }

    public boolean isQueued() {
        return status == EbmReceiptStatus.QUEUED;
    }

    /** 27/32 NS, or the copy's 27/32 CS; null until signed. */
    public String receiptLabel(boolean copy) {
        return isSigned() ? EbmCodes.receiptLabel(rcptNo, totRcptNo, copy, receiptType) : null;
    }

    public String getReceiptLabel() {
        return receiptLabel(false);
    }

    public String getIntrlDataGroups() {
        return EbmCodes.groups(intrlData);
    }

    public String getRcptSignGroups() {
        return EbmCodes.groups(rcptSign);
    }
}
