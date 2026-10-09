package com.ntaganira.heritier.iWarehouse.repository;

import com.ntaganira.heritier.iWarehouse.entity.JournalLine;
import com.ntaganira.heritier.iWarehouse.enums.JournalSource;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Collection;
import java.util.List;
import java.util.UUID;

/**
 * <pre>
 * - Project   : iWarehouse - Glass Warehouse &amp; Mobile POS (ERP-Lite)
 * - Package   : com.ntaganira.heritier.iWarehouse.repository
 * - File      : JournalLineRepository.java
 * - Date      : 2026. 10. 09.
 * - User      : Hntaganira
 * - Desc      : Journal lines (ACC-01): a journal's lines, an account's ledger, balances for the trial balance
 *               (ACC-11) and the inventory account per glass (AT-10). Append-only: save and finders only.
 * </pre>
 */
public interface JournalLineRepository extends Repository<JournalLine, UUID> {

    JournalLine save(JournalLine line);

    @EntityGraph(attributePaths = {"account", "product", "supplier", "customer"})
    List<JournalLine> findByEntry_IdOrderByLineNo(UUID entryId);

    /** An account's lines, newest journal first (its ledger). */
    @EntityGraph(attributePaths = {"entry", "product", "supplier", "customer"})
    @Query(value = "select l from JournalLine l where l.account.id = :accountId",
            countQuery = "select count(l) from JournalLine l where l.account.id = :accountId")
    Page<JournalLine> findLedger(@Param("accountId") UUID accountId, Pageable pageable);

    boolean existsByAccount_Id(UUID accountId);

    /** Rows of (account id, debits, credits) of the journals dated up to a day. */
    @Query("select l.account.id, coalesce(sum(l.debit), 0), coalesce(sum(l.credit), 0) from JournalLine l"
            + " where l.entry.entryDate <= :asOf group by l.account.id")
    List<Object[]> balancesAsOf(@Param("asOf") LocalDate asOf);

    /** Rows of (account id, debits, credits) of all journals. */
    @Query("select l.account.id, coalesce(sum(l.debit), 0), coalesce(sum(l.credit), 0) from JournalLine l group by l.account.id")
    List<Object[]> balances();

    /** Debits less credits of an account on one customer's lines (what they owe on the receivable account). */
    @Query("select coalesce(sum(l.debit), 0) - coalesce(sum(l.credit), 0) from JournalLine l"
            + " where l.account.id = :accountId and l.customer.id = :customerId")
    BigDecimal balanceOfCustomer(@Param("accountId") UUID accountId, @Param("customerId") UUID customerId);

    /** A customer's lines on an account (their statement, ACC-09), in the order they were posted. */
    @EntityGraph(attributePaths = {"entry"})
    @Query("select l from JournalLine l where l.account.id = :accountId and l.customer.id = :customerId"
            + " order by l.entry.entryDate, l.entry.postedAt, l.entry.number, l.lineNo")
    List<JournalLine> findCustomerLines(@Param("accountId") UUID accountId, @Param("customerId") UUID customerId);

    /** Rows of (customer id, date, debit, credit) of an account's lines that name a customer: the receivables ageing. */
    @Query("select l.customer.id, l.entry.entryDate, l.debit, l.credit from JournalLine l"
            + " where l.account.id = :accountId and l.customer is not null")
    List<Object[]> customerEntries(@Param("accountId") UUID accountId);

    /** A supplier's lines on an account (their statement, ACC-09), in the order they were posted. */
    @EntityGraph(attributePaths = {"entry"})
    @Query("select l from JournalLine l where l.account.id = :accountId and l.supplier.id = :supplierId"
            + " order by l.entry.entryDate, l.entry.postedAt, l.entry.number, l.lineNo")
    List<JournalLine> findSupplierLines(@Param("accountId") UUID accountId, @Param("supplierId") UUID supplierId);

    /** An account's lines that name a supplier (the payables report), in the order they were posted. */
    @EntityGraph(attributePaths = {"entry", "supplier"})
    @Query("select l from JournalLine l where l.account.id = :accountId and l.supplier is not null"
            + " order by l.entry.entryDate, l.entry.postedAt, l.entry.number, l.lineNo")
    List<JournalLine> findSupplierLines(@Param("accountId") UUID accountId);

    /** The lines on an account of the journals of some documents of a kind: a goods receipt's line on GRNI. */
    @EntityGraph(attributePaths = {"entry"})
    @Query("select l from JournalLine l where l.account.id = :accountId and l.entry.sourceType = :type and l.entry.sourceId in :sourceIds")
    List<JournalLine> findOfSources(@Param("accountId") UUID accountId, @Param("type") JournalSource type,
                                    @Param("sourceIds") Collection<UUID> sourceIds);

    /** Rows of (product id, debits less credits) of an account: the inventory account per glass (AT-10). */
    @Query("select l.product.id, coalesce(sum(l.debit), 0) - coalesce(sum(l.credit), 0) from JournalLine l"
            + " where l.account.id = :accountId group by l.product.id")
    List<Object[]> balanceByProduct(@Param("accountId") UUID accountId);
}
