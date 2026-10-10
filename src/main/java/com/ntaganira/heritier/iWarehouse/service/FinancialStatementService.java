package com.ntaganira.heritier.iWarehouse.service;

import com.ntaganira.heritier.iWarehouse.entity.Account;
import com.ntaganira.heritier.iWarehouse.entity.JournalLine;
import com.ntaganira.heritier.iWarehouse.exception.NotFoundException;
import com.ntaganira.heritier.iWarehouse.repository.AccountRepository;
import com.ntaganira.heritier.iWarehouse.repository.JournalLineRepository;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * <pre>
 * - Project   : iWarehouse - Glass Warehouse &amp; Mobile POS (ERP-Lite)
 * - Package   : com.ntaganira.heritier.iWarehouse.service
 * - File      : FinancialStatementService.java
 * - Date      : 2026. 10. 09.
 * - User      : Hntaganira
 * - Desc      : Reads the journals for the financial statements (ACC-11): the income statement of a period, the
 *               balance sheet at a day, the general ledger of a period (every account, or one account's lines with
 *               the balance after each). The figures come from FinancialStatements (pure).
 * </pre>
 */
@Service
@Transactional(readOnly = true)
public class FinancialStatementService {

    private final AccountRepository accountRepo;
    private final JournalLineRepository lineRepo;
    private final Clock clock;

    public FinancialStatementService(AccountRepository accountRepo, JournalLineRepository lineRepo, Clock clock) {
        this.accountRepo = accountRepo;
        this.lineRepo = lineRepo;
        this.clock = clock;
    }

    /** One account's ledger over a period: its opening, debits, credits and closing, and its lines with the balance after each. */
    public record AccountLedger(FinancialStatements.LedgerRow row, List<FinancialStatements.LedgerLine> lines) {
    }

    public FinancialStatements.IncomeStatement incomeStatement(LocalDate from, LocalDate to) {
        return FinancialStatements.incomeStatement(from, to, accountRepo.findAll(), lineRepo.movements(from, to));
    }

    public FinancialStatements.BalanceSheet balanceSheet(LocalDate asOf) {
        return FinancialStatements.balanceSheet(asOf, accountRepo.findAll(), lineRepo.balancesAsOf(asOf),
                lineRepo.movements(asOf.withDayOfYear(1), asOf));
    }

    public List<FinancialStatements.LedgerRow> ledger(LocalDate from, LocalDate to) {
        return FinancialStatements.ledger(accountRepo.findAll(), lineRepo.balancesAsOf(from.minusDays(1)), lineRepo.movements(from, to));
    }

    public AccountLedger accountLedger(UUID accountId, LocalDate from, LocalDate to) {
        Account account = accountRepo.findById(accountId).orElseThrow(() -> new NotFoundException("Account", accountId));
        BigDecimal opening = BigDecimal.ZERO;
        for (Object[] r : lineRepo.balancesAsOf(from.minusDays(1))) {
            if (accountId.equals(r[0])) {
                opening = ((BigDecimal) r[1]).subtract((BigDecimal) r[2]);
            }
        }
        List<JournalLine> lines = lineRepo.findAccountLines(accountId, from, to);
        BigDecimal debits = lines.stream().map(JournalLine::getDebit).reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal credits = lines.stream().map(JournalLine::getCredit).reduce(BigDecimal.ZERO, BigDecimal::add);
        return new AccountLedger(new FinancialStatements.LedgerRow(account, opening, debits, credits), FinancialStatements.running(opening, lines));
    }

    /** Every account, by code (the general ledger's choice). */
    public List<Account> accounts() {
        return accountRepo.findAll(Sort.by("code"));
    }

    public LocalDate today() {
        return LocalDate.now(clock);
    }
}
