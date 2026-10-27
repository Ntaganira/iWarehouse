package com.ntaganira.heritier.iWarehouse.repository;

import com.ntaganira.heritier.iWarehouse.entity.PaymentConfirmation;
import org.springframework.data.repository.Repository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * <pre>
 * - Project   : iWarehouse - Glass Warehouse &amp; Mobile POS (ERP-Lite)
 * - Package   : com.ntaganira.heritier.iWarehouse.repository
 * - File      : PaymentConfirmationRepository.java
 * - Date      : 2026. 10. 15.
 * - User      : NTAGANIRA Heritier
 * - Email     : ntaganira71@gmail.com
 * - Phone     : +250 788 533 669
 * - Desc      : Transfers confirmed by Finance (SDP 14). Append-only: save and finders only.
 * </pre>
 */
public interface PaymentConfirmationRepository extends Repository<PaymentConfirmation, UUID> {

    PaymentConfirmation save(PaymentConfirmation confirmation);

    Optional<PaymentConfirmation> findBySalesPaymentId(UUID salesPaymentId);

    List<PaymentConfirmation> findBySalesPaymentIdIn(Collection<UUID> salesPaymentIds);

    List<PaymentConfirmation> findByInvoiceId(UUID invoiceId);
}
