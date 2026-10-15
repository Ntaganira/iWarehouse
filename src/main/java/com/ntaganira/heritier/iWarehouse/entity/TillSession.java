package com.ntaganira.heritier.iWarehouse.entity;

import com.ntaganira.heritier.iWarehouse.audit.AuditedEntity;
import com.ntaganira.heritier.iWarehouse.enums.TillStatus;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.UUID;

/**
 * <pre>
 * - Project   : iWarehouse - Glass Warehouse &amp; Mobile POS (ERP-Lite)
 * - Package   : com.ntaganira.heritier.iWarehouse.entity
 * - File      : TillSession.java
 * - Date      : 2026. 10. 09.
 * - User      : Hntaganira
 * - Desc      : A cashier's till session (POS-10): opened with a float, closed with the cash counted. The
 *               cash expected is the float plus the cash kept from sales; the difference (negative when
 *               short) is recorded with a note. One open session per cashier.
 * </pre>
 */
@Entity
@Table(name = "till_sessions")
@AuditedEntity(ref = "number")
@Getter
@Setter
@NoArgsConstructor
public class TillSession extends BaseEntity {

    @Column(nullable = false, unique = true, length = 30)
    private String number;

    @Column(name = "cashier_id", nullable = false)
    private Long cashierId;

    @Column(name = "cashier_username", nullable = false, length = 50)
    private String cashierUsername;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 10)
    private TillStatus status = TillStatus.OPEN;

    @Column(name = "opened_at", nullable = false)
    private LocalDateTime openedAt;

    @Column(name = "opening_float", nullable = false, precision = 18, scale = 2)
    private BigDecimal openingFloat;

    @Column(name = "closed_at")
    private LocalDateTime closedAt;

    @Column(name = "cash_sales", precision = 18, scale = 2)
    private BigDecimal cashSales;

    @Column(name = "expected_cash", precision = 18, scale = 2)
    private BigDecimal expectedCash;

    /** Cash refunded from the till on credit notes (POS-09): it left the drawer. Set when the till closes. */
    @Column(name = "cash_refunds", precision = 18, scale = 2)
    private BigDecimal cashRefunds;

    /** Cash taken on customer accounts (ACC-09): it is in the drawer. Set when the till closes. */
    @Column(name = "cash_account_payments", precision = 18, scale = 2)
    private BigDecimal cashAccountPayments;

    @Column(name = "counted_cash", precision = 18, scale = 2)
    private BigDecimal countedCash;

    @Column(precision = 18, scale = 2)
    private BigDecimal difference;

    @Column(name = "close_note", length = 255)
    private String closeNote;

    /** The cash counted note by note (F-13): "5000:12,1000:3", largest first (CashCount). */
    @Column(length = 400)
    private String denominations;

    /** Treasury's verification of the count (F-13): never the cashier. */
    @Column(name = "verified_by", length = 50)
    private String verifiedBy;

    @Column(name = "verified_by_id")
    private Long verifiedById;

    @Column(name = "verified_at")
    private LocalDateTime verifiedAt;

    @Column(name = "verify_note", length = 255)
    private String verifyNote;

    /** The deposit that banked its cash (F-13, SDP 10). */
    @Column(name = "deposit_id")
    private UUID depositId;

    public boolean isOpen() {
        return status == TillStatus.OPEN;
    }

    public boolean isVerified() {
        return verifiedAt != null;
    }

    public boolean isBanked() {
        return depositId != null;
    }

    /** What is banked from it: the cash counted less the float, which stays in the vault (never less than nothing). */
    public BigDecimal getCashToBank() {
        if (countedCash == null) {
            return BigDecimal.ZERO;
        }
        BigDecimal taken = countedCash.subtract(openingFloat);
        return taken.signum() < 0 ? BigDecimal.ZERO.setScale(2) : taken;
    }
}
