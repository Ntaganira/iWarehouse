package com.ntaganira.heritier.iWarehouse.service;

import com.ntaganira.heritier.iWarehouse.dto.AccountDto;
import com.ntaganira.heritier.iWarehouse.entity.Account;
import com.ntaganira.heritier.iWarehouse.entity.JournalEntry;
import com.ntaganira.heritier.iWarehouse.entity.JournalLine;
import com.ntaganira.heritier.iWarehouse.entity.Product;
import com.ntaganira.heritier.iWarehouse.enums.AccountKey;
import com.ntaganira.heritier.iWarehouse.enums.AccountType;
import com.ntaganira.heritier.iWarehouse.enums.DocumentType;
import com.ntaganira.heritier.iWarehouse.enums.JournalSource;
import com.ntaganira.heritier.iWarehouse.exception.BusinessException;
import com.ntaganira.heritier.iWarehouse.repository.*;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.*;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/** The chart of accounts (ACC-03), saving journals (ACC-04) and the inventory account check (AT-10). */
class AccountingServicesTest {

    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-10-09T08:00:00Z"), ZoneId.of("Africa/Kigali"));

    // ---------------------------------------------------------------- chart of accounts

    @Test
    void codesAreUniqueAndTheTypeIsFixedOnceUsedOrOnSystemAccounts() {
        AccountRepository repo = mock(AccountRepository.class);
        JournalLineRepository lineRepo = mock(JournalLineRepository.class);
        AccountService service = new AccountService(repo, lineRepo);
        when(repo.save(any())).thenAnswer(a -> a.getArgument(0));
        when(repo.existsByCode("1200")).thenReturn(true);

        assertThatThrownBy(() -> service.create(dto("1200", "Inventory 2", AccountType.ASSET)))
                .isInstanceOfSatisfying(BusinessException.class, e -> {
                    assertThat(e.getField()).isEqualTo("code");
                    assertThat(e.getMessageKey()).isEqualTo("account.code.taken");
                });
        Account rent = service.create(dto("5100", " Rent ", AccountType.EXPENSE));
        assertThat(rent.getName()).isEqualTo("Rent");

        Account used = account("5110", AccountType.EXPENSE, null);
        when(repo.findById(used.getId())).thenReturn(Optional.of(used));
        when(lineRepo.existsByAccount_Id(used.getId())).thenReturn(true);
        assertThatThrownBy(() -> service.update(used.getId(), dto("5110", "Fuel", AccountType.ASSET)))
                .isInstanceOfSatisfying(BusinessException.class, e -> assertThat(e.getMessageKey()).isEqualTo("account.type.used"));
        assertThat(service.update(used.getId(), dto("5111", "Fuel", AccountType.EXPENSE)).getCode()).isEqualTo("5111");

        Account inventory = account("1200", AccountType.ASSET, AccountKey.INVENTORY);
        when(repo.findById(inventory.getId())).thenReturn(Optional.of(inventory));
        assertThatThrownBy(() -> service.update(inventory.getId(), dto("1200", "Stock", AccountType.EXPENSE)))
                .isInstanceOfSatisfying(BusinessException.class, e -> assertThat(e.getMessageKey()).isEqualTo("account.type.system"));
        assertThat(service.update(inventory.getId(), dto("1210", "Stock - Glass", AccountType.ASSET)).getName())
                .isEqualTo("Stock - Glass");                                     // renamed and renumbered
    }

    @Test
    void accountsThePostingRulesUseStayActive() {
        AccountRepository repo = mock(AccountRepository.class);
        AccountService service = new AccountService(repo, mock(JournalLineRepository.class));
        Account bank = account("1030", AccountType.ASSET, AccountKey.BANK);
        Account rent = account("5100", AccountType.EXPENSE, null);
        when(repo.findById(bank.getId())).thenReturn(Optional.of(bank));
        when(repo.findById(rent.getId())).thenReturn(Optional.of(rent));

        assertThatThrownBy(() -> service.setEnabled(bank.getId(), false))
                .isInstanceOfSatisfying(BusinessException.class, e -> assertThat(e.getMessageKey()).isEqualTo("account.system.active"));
        assertThat(service.setEnabled(rent.getId(), false).isEnabled()).isFalse();
    }

    // ---------------------------------------------------------------- journals

    @Test
    void aJournalIsSavedWithItsNumberLinesAndForeignAmounts() {
        Fixture f = new Fixture();
        UUID clear = UUID.randomUUID();
        UUID supplier = UUID.randomUUID();
        Journal journal = Journal.of(JournalSource.GOODS_RECEIPT, UUID.randomUUID(), "GRN-WH-2026-000010", LocalDate.of(2026, 10, 9), "Goods receipt")
                .add(AccountKey.INVENTORY, new BigDecimal("941966.40"), clear, null, null, null)
                .add(AccountKey.GRNI, new BigDecimal("-941966.40"), null, supplier, "GRN-WH-2026-000010",
                        new Journal.Fx("USD", new BigDecimal("650.03"), new BigDecimal("1449.123456")));

        JournalEntry entry = f.service.post(journal);

        assertThat(entry.getNumber()).isEqualTo("JV-WH-2026-000001");
        assertThat(entry.getTotal()).isEqualByComparingTo("941966.40");
        assertThat(entry.getUsername()).isEqualTo("system");
        assertThat(entry.getPostedAt()).isEqualTo(LocalDate.of(2026, 10, 9).atTime(10, 0));
        ArgumentCaptor<JournalLine> lines = ArgumentCaptor.forClass(JournalLine.class);
        verify(f.lineRepo, times(2)).save(lines.capture());
        assertThat(lines.getAllValues()).extracting(JournalLine::getLineNo).containsExactly(1, 2);
        JournalLine stock = lines.getAllValues().get(0);
        assertThat(stock.getAccount().getSystemKey()).isEqualTo(AccountKey.INVENTORY);
        assertThat(stock.getDebit()).isEqualByComparingTo("941966.40");
        assertThat(stock.getCredit()).isEqualByComparingTo("0");
        JournalLine grni = lines.getAllValues().get(1);
        assertThat(grni.getCredit()).isEqualByComparingTo("941966.40");
        assertThat(grni.getCurrencyCode()).isEqualTo("USD");
        assertThat(grni.getFxAmount()).isEqualByComparingTo("650.03");
        assertThat(grni.getRate()).isEqualByComparingTo("1449.123456");
        verify(f.numbers).next(DocumentType.JOURNAL);
    }

    @Test
    void anEmptyJournalIsNotSavedAndOneThatDoesNotBalanceIsRefused() {
        Fixture f = new Fixture();
        Journal empty = Journal.of(JournalSource.CUTTING_JOB, null, "CUT-1", LocalDate.of(2026, 10, 9), "Cut");
        assertThat(f.service.post(empty)).isNull();

        Journal off = Journal.of(JournalSource.CUTTING_JOB, null, "CUT-2", LocalDate.of(2026, 10, 9), "Cut")
                .debit(AccountKey.SPOILAGE, BigDecimal.TEN).credit(AccountKey.INVENTORY, BigDecimal.ONE);
        assertThatThrownBy(() -> f.service.post(off)).isInstanceOf(IllegalStateException.class);
        verify(f.entryRepo, never()).save(any());
    }

    @Test
    void theInventoryCheckListsEachGlassWhoseAccountDiffersFromItsValue() {
        Fixture f = new Fixture();
        Product clear = product("CLR-6");
        Product mirror = product("MIR-4");
        when(f.lineRepo.balanceByProduct(f.accounts.get(AccountKey.INVENTORY).getId())).thenReturn(List.of(
                new Object[]{clear.getId(), new BigDecimal("1000.00")},
                new Object[]{mirror.getId(), new BigDecimal("250.00")}));
        when(f.summary.valueByProduct()).thenReturn(Map.of(clear.getId(), new BigDecimal("1000.00"), mirror.getId(), new BigDecimal("300.00")));
        when(f.productRepo.findAllById(any())).thenReturn(List.of(clear, mirror));

        JournalService.InventoryCheck check = f.service.inventoryCheck();

        assertThat(check.ledger()).isEqualByComparingTo("1250.00");
        assertThat(check.valuation()).isEqualByComparingTo("1300.00");
        assertThat(check.isMatching()).isFalse();
        assertThat(check.differences()).singleElement().satisfies(d -> {
            assertThat(d.product()).isEqualTo(mirror);
            assertThat(d.getDifference()).isEqualByComparingTo("-50.00");
        });
    }

    // ---------------------------------------------------------------- helpers

    private static final class Fixture {
        final JournalEntryRepository entryRepo = mock(JournalEntryRepository.class);
        final JournalLineRepository lineRepo = mock(JournalLineRepository.class);
        final AccountRepository accountRepo = mock(AccountRepository.class);
        final ProductRepository productRepo = mock(ProductRepository.class);
        final StockSummaryService summary = mock(StockSummaryService.class);
        final DocumentNumberService numbers = mock(DocumentNumberService.class);
        final Map<AccountKey, Account> accounts = new EnumMap<>(AccountKey.class);
        final JournalService service;

        Fixture() {
            for (AccountKey key : AccountKey.values()) {
                accounts.put(key, account(key.name(), AccountType.ASSET, key));
            }
            when(accountRepo.findBySystemKeyIsNotNull()).thenReturn(new ArrayList<>(accounts.values()));
            when(accountRepo.findBySystemKey(any())).thenAnswer(a -> Optional.of(accounts.get((AccountKey) a.getArgument(0))));
            when(entryRepo.save(any())).thenAnswer(a -> a.getArgument(0));
            when(lineRepo.save(any())).thenAnswer(a -> a.getArgument(0));
            when(numbers.next(DocumentType.JOURNAL)).thenReturn("JV-WH-2026-000001");
            service = new JournalService(entryRepo, lineRepo, accountRepo, productRepo, mock(SupplierRepository.class),
                    mock(CustomerRepository.class), mock(DriverRepository.class), summary, numbers, mock(PeriodLock.class), CLOCK);
        }
    }

    private static Account account(String code, AccountType type, AccountKey key) {
        Account a = new Account();
        a.setId(UUID.randomUUID());
        a.setCode(code);
        a.setName(code);
        a.setType(type);
        a.setSystemKey(key);
        return a;
    }

    private static AccountDto dto(String code, String name, AccountType type) {
        AccountDto dto = new AccountDto();
        dto.setCode(code);
        dto.setName(name);
        dto.setType(type);
        return dto;
    }

    private static Product product(String code) {
        Product p = new Product();
        p.setId(UUID.randomUUID());
        p.setCode(code);
        return p;
    }
}
