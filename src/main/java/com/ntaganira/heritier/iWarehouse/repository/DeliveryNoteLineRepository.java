package com.ntaganira.heritier.iWarehouse.repository;

import com.ntaganira.heritier.iWarehouse.entity.DeliveryNoteLine;
import com.ntaganira.heritier.iWarehouse.enums.DeliveryNoteStatus;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;
import java.util.UUID;

/**
 * <pre>
 * - Project   : iWarehouse - Glass Warehouse &amp; Mobile POS (ERP-Lite)
 * - Package   : com.ntaganira.heritier.iWarehouse.repository
 * - File      : DeliveryNoteLineRepository.java
 * - Date      : 2026. 10. 15.
 * - User      : NTAGANIRA Heritier
 * - Email     : ntaganira71@gmail.com
 * - Phone     : +250 788 533 669
 * - Desc      : What delivery notes release. Append-only: save and finders only.
 * </pre>
 */
public interface DeliveryNoteLineRepository extends Repository<DeliveryNoteLine, UUID> {

    DeliveryNoteLine save(DeliveryNoteLine line);

    List<DeliveryNoteLine> findByDeliveryNoteIdOrderByLineNo(UUID deliveryNoteId);

    List<DeliveryNoteLine> findByDeliveryNoteIdIn(Collection<UUID> deliveryNoteIds);

    /** The lines of an invoice's notes in these states (a unit leaves on one note only). */
    @Query("select l from DeliveryNoteLine l, DeliveryNote n where n.id = l.deliveryNoteId and n.invoice.id = :invoiceId"
            + " and n.status in :statuses")
    List<DeliveryNoteLine> ofInvoice(@Param("invoiceId") UUID invoiceId, @Param("statuses") Collection<DeliveryNoteStatus> statuses);
}
