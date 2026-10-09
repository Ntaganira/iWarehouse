package com.ntaganira.heritier.iWarehouse.service;

import com.ntaganira.heritier.iWarehouse.dto.AccountDto;
import com.ntaganira.heritier.iWarehouse.entity.Account;
import com.ntaganira.heritier.iWarehouse.enums.AccountType;
import com.ntaganira.heritier.iWarehouse.exception.BusinessException;
import com.ntaganira.heritier.iWarehouse.exception.NotFoundException;
import com.ntaganira.heritier.iWarehouse.repository.AccountRepository;
import com.ntaganira.heritier.iWarehouse.repository.JournalLineRepository;
import jakarta.persistence.criteria.Predicate;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.util.Locale;
import java.util.UUID;

/**
 * <pre>
 * - Project   : iWarehouse - Glass Warehouse &amp; Mobile POS (ERP-Lite)
 * - Package   : com.ntaganira.heritier.iWarehouse.service
 * - File      : AccountService.java
 * - Date      : 2026. 10. 09.
 * - User      : Hntaganira
 * - Desc      : The chart of accounts (ACC-03): list, add, rename or renumber, activate and deactivate. Codes
 *               are unique; the type is fixed once the account has lines, and on the accounts the posting
 *               rules use (system key), which also stay active.
 * </pre>
 */
@Service
@Transactional(readOnly = true)
public class AccountService {

    private final AccountRepository repo;
    private final JournalLineRepository lineRepo;

    public AccountService(AccountRepository repo, JournalLineRepository lineRepo) {
        this.repo = repo;
        this.lineRepo = lineRepo;
    }

    public Page<Account> findPage(String search, String type, boolean inactive, int page, int size) {
        Specification<Account> spec = (root, query, cb) -> {
            Predicate p = cb.conjunction();
            if (StringUtils.hasText(search)) {
                String term = "%" + search.trim().toLowerCase(Locale.ROOT) + "%";
                p = cb.and(p, cb.or(cb.like(cb.lower(root.get("code")), term), cb.like(cb.lower(root.get("name")), term)));
            }
            AccountType t = type(type);
            if (t != null) {
                p = cb.and(p, cb.equal(root.get("type"), t));
            }
            if (!inactive) {
                p = cb.and(p, cb.isTrue(root.get("enabled")));
            }
            return p;
        };
        return repo.findAll(spec, PageRequest.of(page, size, Sort.by("code")));
    }

    public Account findById(UUID id) {
        return repo.findById(id).orElseThrow(() -> new NotFoundException("Account", id));
    }

    /** Lines were posted to it: its type is fixed. */
    public boolean isUsed(UUID id) {
        return lineRepo.existsByAccount_Id(id);
    }

    @Transactional
    public Account create(AccountDto dto) {
        if (repo.existsByCode(dto.getCode())) {
            throw BusinessException.onField("code", "account.code.taken", dto.getCode());
        }
        Account account = new Account();
        account.setType(dto.getType());
        apply(account, dto);
        return repo.save(account);
    }

    @Transactional
    public Account update(UUID id, AccountDto dto) {
        Account account = findById(id);
        if (repo.existsByCodeAndIdNot(dto.getCode(), id)) {
            throw BusinessException.onField("code", "account.code.taken", dto.getCode());
        }
        if (dto.getType() != account.getType()) {
            if (account.isSystem()) {
                throw BusinessException.onField("type", "account.type.system", account.getCode());
            }
            if (isUsed(id)) {
                throw BusinessException.onField("type", "account.type.used", account.getCode());
            }
            account.setType(dto.getType());
        }
        apply(account, dto);
        return account;
    }

    @Transactional
    public Account setEnabled(UUID id, boolean enabled) {
        Account account = findById(id);
        if (!enabled && account.isSystem()) {
            throw BusinessException.of("account.system.active", account.getCode());
        }
        account.setEnabled(enabled);
        return account;
    }

    private static void apply(Account account, AccountDto dto) {
        account.setCode(dto.getCode());
        account.setName(dto.getName().trim());
        account.setDescription(StringUtils.hasText(dto.getDescription()) ? dto.getDescription().trim() : null);
    }

    private static AccountType type(String value) {
        if (!StringUtils.hasText(value)) {
            return null;
        }
        try {
            return AccountType.valueOf(value);
        } catch (IllegalArgumentException e) {
            return null; // unknown type: no filter
        }
    }
}
