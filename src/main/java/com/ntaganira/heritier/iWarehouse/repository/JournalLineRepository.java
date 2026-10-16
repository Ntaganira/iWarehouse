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

    /** Rows of (account id, debits, credits) of the journals dated between two days, both included (ACC-11). */
    @Query("select l.account.id, coalesce(sum(l.debit), 0), coalesce(sum(l.credit), 0) from JournalLine l"
            + " where l.entry.entryDate between :from and :to group by l.account.id")
    List<Object[]> movements(@Param("from") LocalDate from, @Param("to") LocalDate to);

    /** An account's lines dated between two days, in the order they were posted (its general ledger, ACC-11). */
    @EntityGraph(attributePaths = {"entry", "product", "supplier", "customer"})
    @Query("select l from JournalLine l where l.account.id = :accountId and l.entry.entryDate between :from and :to"
            + " order by l.entry.entryDate, l.entry.postedAt, l.entry.number, l.lineNo")
    List<JournalLine> findAccountLines(@Param("accountId") UUID accountId, @Param("from") LocalDate from, @Param("to") LocalDate to);

    /** An account's lines dated up to a day, in the order they were posted (what a reconciliation may clear, ACC-12). */
    @EntityGraph(attributePaths = {"entry"})
    @Query("select l from JournalLine l where l.account.id = :accountId and l.entry.entryDate <= :to"
            + " order by l.entry.entryDate, l.entry.postedAt, l.entry.number, l.lineNo")
    List<JournalLine> findAccountLinesUpTo(@Param("accountId") UUID accountId, @Param("to") LocalDate to);

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

    /**
     * Rows of (customer id, date, debit, credit, source document id) of an account's lines that name a customer: the
     * receivables ageing (an invoice with extended terms is found by its id).
     */
    @Query("select l.customer.id, l.entry.entryDate, l.debit, l.credit, l.entry.sourceId from JournalLine l"
            + " where l.account.id = :accountId and l.customer is not null")
    List<Object[]> customerEntries(@Param("accountId") UUID accountId);

    /** Rows of (customer id, date, source, debit, credit) of an account's lines naming a customer up to a day (the movement of receivables). */
    @Query("select l.customer.id, l.entry.entryDate, l.entry.sourceType, l.debit, l.credit from JournalLine l"
            + " where l.account.id = :accountId and l.customer is not null and l.entry.entryDate <= :to")
    List<Object[]> customerMovements(@Param("accountId") UUID accountId, @Param("to") LocalDate to);

    /** Debits less credits of an account on one customer's lines dated up to a day (a balance confirmation). */
    @Query("select coalesce(sum(l.debit), 0) - coalesce(sum(l.credit), 0) from JournalLine l"
            + " where l.account.id = :accountId and l.customer.id = :customerId and l.entry.entryDate <= :asOf")
    BigDecimal balanceOfCustomerAt(@Param("accountId") UUID accountId, @Param("customerId") UUID customerId, @Param("asOf") LocalDate asOf);

    /** The date of an account's first line (none: null). */
    @Query("select min(l.entry.entryDate) from JournalLine l where l.account.id = :accountId")
    LocalDate firstDateOfAccount(@Param("accountId") UUID accountId);

    /** Rows of (debits, credits) of an account's lines dated in a period. */
    @Query("select coalesce(sum(l.debit), 0), coalesce(sum(l.credit), 0) from JournalLine l"
            + " where l.account.id = :accountId and l.entry.entryDate between :from and :to")
    List<Object[]> movement(@Param("accountId") UUID accountId, @Param("from") LocalDate from, @Param("to") LocalDate to);

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

    /**
     * Rows of (account id, supplier id, currency, owed in it, owed in RWF) of the foreign-currency lines on some accounts
     * dated up to a day (ACC-08): owed is credits less debits; a line's foreign amount counts on its side.
     */
    @Query("select l.account.id, s.id, l.currencyCode,"
            + " sum(case when l.credit > 0 then abs(l.fxAmount) else -abs(l.fxAmount) end), sum(l.credit) - sum(l.debit)"
            + " from JournalLine l left join l.supplier s"
            + " where l.account.id in :accountIds and l.currencyCode is not null and l.entry.entryDate <= :asOf"
            + " group by l.account.id, s.id, l.currencyCode")
    List<Object[]> foreignBalances(@Param("accountIds") Collection<UUID> accountIds, @Param("asOf") LocalDate asOf);

    /** Rows of (product id, debits less credits) of an account: the inventory account per glass (AT-10). */
    @Query("select l.product.id, coalesce(sum(l.debit), 0) - coalesce(sum(l.credit), 0) from JournalLine l"
            + " where l.account.id = :accountId group by l.product.id")
    List<Object[]> balanceByProduct(@Param("accountId") UUID accountId);

    /** Rows of (driver id, debits less credits) of an account: each driver's float or shortage (ACC-06, ACC-07). */
    @Query("select l.driver.id, coalesce(sum(l.debit), 0) - coalesce(sum(l.credit), 0) from JournalLine l"
            + " where l.account.id = :accountId and l.driver is not null group by l.driver.id")
    List<Object[]> balanceByDriver(@Param("accountId") UUID accountId);

    /**
     * Cost of Goods Sold and Inventory posted for documents, as (source, source id, account key, product id, debits less credits),
     * from journals dated on or after a day: what each sale, hand-over and credit note cost, and the stock value of each glass
     * it moved (RPT-05).
     */
    @Query("select e.sourceType, e.sourceId, a.systemKey, p.id, sum(l.debit) - sum(l.credit) from JournalLine l join l.entry e join l.account a"
            + " left join l.product p where a.systemKey in (com.ntaganira.heritier.iWarehouse.enums.AccountKey.COGS,"
            + " com.ntaganira.heritier.iWarehouse.enums.AccountKey.INVENTORY)"
            + " and e.sourceType in :sources and e.entryDate >= :since group by e.sourceType, e.sourceId, a.systemKey, p.id")
    List<Object[]> costOfSales(@Param("sources") Collection<com.ntaganira.heritier.iWarehouse.enums.JournalSource> sources,
                               @Param("since") java.time.LocalDate since);

    /** An account's balance (debits less credits) up to and including a day. */
    @Query("select coalesce(sum(l.debit - l.credit), 0) from JournalLine l where l.account.systemKey = :key and l.entry.entryDate <= :day")
    java.math.BigDecimal balanceAt(@Param("key") com.ntaganira.heritier.iWarehouse.enums.AccountKey key, @Param("day") java.time.LocalDate day);

    /** An account's movement (debits less credits) over a period, both days included (turnover: the cost of sales). */
    @Query("select coalesce(sum(l.debit - l.credit), 0) from JournalLine l where l.account.systemKey = :key"
            + " and l.entry.entryDate between :from and :to")
    java.math.BigDecimal movementBetween(@Param("key") com.ntaganira.heritier.iWarehouse.enums.AccountKey key,
                                         @Param("from") java.time.LocalDate from, @Param("to") java.time.LocalDate to);

    /**
     * Rows of (customer id, date, source, source document id, debit, credit) of an account's lines naming a customer up to a
     * day, in posting order: collection performance (Q.05) finds each charge's invoice by its source document.
     */
    @Query("select l.customer.id, l.entry.entryDate, l.entry.sourceType, l.entry.sourceId, l.debit, l.credit from JournalLine l"
            + " where l.account.id = :accountId and l.customer is not null and l.entry.entryDate <= :to"
            + " order by l.entry.entryDate, l.entry.postedAt, l.entry.number, l.lineNo")
    List<Object[]> customerLinesUntil(@Param("accountId") UUID accountId, @Param("to") LocalDate to);

    /**
     * Expense lines of a period as (account code, account name, account key, cost centre id, location id, source, debits less
     * credits), on the expense accounts and Fixed Assets: the expenditure report (T.05, T.06).
     */
    @Query("select a.code, a.name, a.systemKey, l.costCentreId, l.locationId, e.sourceType, sum(l.debit) - sum(l.credit) from JournalLine l"
            + " join l.entry e join l.account a where (a.type = com.ntaganira.heritier.iWarehouse.enums.AccountType.EXPENSE"
            + " or a.systemKey = com.ntaganira.heritier.iWarehouse.enums.AccountKey.FIXED_ASSETS) and e.entryDate between :from and :to"
            + " group by a.code, a.name, a.systemKey, l.costCentreId, l.locationId, e.sourceType")
    List<Object[]> expenseLines(@Param("from") LocalDate from, @Param("to") LocalDate to);
}
