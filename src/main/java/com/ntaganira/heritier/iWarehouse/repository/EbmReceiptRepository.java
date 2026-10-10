package com.ntaganira.heritier.iWarehouse.repository;

import com.ntaganira.heritier.iWarehouse.entity.EbmReceipt;
import com.ntaganira.heritier.iWarehouse.enums.EbmReceiptStatus;
import jakarta.persistence.LockModeType;
import jakarta.persistence.QueryHint;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.*;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * <pre>
 * - Project   : iWarehouse - Glass Warehouse &amp; Mobile POS (ERP-Lite)
 * - Package   : com.ntaganira.heritier.iWarehouse.repository
 * - File      : EbmReceiptRepository.java
 * - Date      : 2026. 10. 10.
 * - User      : Hntaganira
 * - Desc      : EBM receipts (TAX-02, TAX-03): the queue's due receipts, a document's receipt, the backlog.
 *               lockForSigning skips a receipt another thread is signing (SKIP LOCKED), so the worker and the
 *               attempt after a sale never send it twice at once.
 * </pre>
 */
public interface EbmReceiptRepository extends JpaRepository<EbmReceipt, UUID>, JpaSpecificationExecutor<EbmReceipt> {

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @QueryHints(@QueryHint(name = "jakarta.persistence.lock.timeout", value = "-2"))
    @Query("select r from EbmReceipt r where r.id = :id")
    Optional<EbmReceipt> lockForSigning(@Param("id") UUID id);

    /** Waits for a receipt being signed, then locks it (a print, a retry, a purchase code). */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select r from EbmReceipt r where r.id = :id")
    Optional<EbmReceipt> lockById(@Param("id") UUID id);

    @Query("select r.id from EbmReceipt r where r.status = com.ntaganira.heritier.iWarehouse.enums.EbmReceiptStatus.QUEUED"
            + " and r.nextAttemptAt <= :now order by r.invcNo")
    List<UUID> findDue(@Param("now") LocalDateTime now, Pageable page);

    @Query("select r from EbmReceipt r where r.invoiceId = :invoiceId and r.receiptType = 'S'")
    Optional<EbmReceipt> findSale(@Param("invoiceId") UUID invoiceId);

    Optional<EbmReceipt> findByCreditNoteId(UUID creditNoteId);

    long countByStatus(EbmReceiptStatus status);

    @Query("select r.id from EbmReceipt r where r.status <> com.ntaganira.heritier.iWarehouse.enums.EbmReceiptStatus.SIGNED"
            + " order by r.invcNo")
    List<UUID> findUnsignedIds();

    @Query("select min(r.createdAt) from EbmReceipt r where r.status <> com.ntaganira.heritier.iWarehouse.enums.EbmReceiptStatus.SIGNED")
    LocalDateTime oldestUnsigned();

    @Query("select count(r) from EbmReceipt r where r.status <> com.ntaganira.heritier.iWarehouse.enums.EbmReceiptStatus.SIGNED"
            + " and r.createdAt < :before")
    long countUnsignedBefore(@Param("before") LocalDateTime before);

    @Query("select min(r.createdAt) from EbmReceipt r")
    LocalDateTime firstReceiptAt();

    /** The simulator goes on from its own numbers. */
    @Query("select max(r.invcNo) from EbmReceipt r where r.simulated = true and r.status = com.ntaganira.heritier.iWarehouse.enums.EbmReceiptStatus.SIGNED")
    Long maxSimulatedInvcNo();

    @Query("select max(r.totRcptNo) from EbmReceipt r where r.simulated = true")
    Long maxSimulatedReceiptNo();
}
