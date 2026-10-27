package com.ntaganira.heritier.iWarehouse.service;

import com.ntaganira.heritier.iWarehouse.entity.Customer;
import com.ntaganira.heritier.iWarehouse.enums.SettingKey;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.Collection;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * <pre>
 * - Project   : iWarehouse - Glass Warehouse &amp; Mobile POS (ERP-Lite)
 * - Package   : com.ntaganira.heritier.iWarehouse.service
 * - File      : FieldCreditService.java
 * - Date      : 2026. 10. 15.
 * - User      : NTAGANIRA Heritier
 * - Email     : ntaganira71@gmail.com
 * - Phone     : +250 788 533 669
 * - Desc      : What customers may take on credit on the road (decision D8, FieldCredit): their limit less what they owe
 *               on the receivable account, nothing while an invoice is overdue past the grace when the Settings block it.
 *               Frozen into the trip's download for the phone, worked out again at sync.
 * </pre>
 */
@Service
@Transactional(readOnly = true)
public class FieldCreditService {

    private final JournalService journalService;
    private final CustomerAgeing ageing;
    private final SettingService settingService;

    public FieldCreditService(JournalService journalService, CustomerAgeing ageing, SettingService settingService) {
        this.journalService = journalService;
        this.ageing = ageing;
        this.settingService = settingService;
    }

    /** The phone sells on credit (the Settings' mobile.field-credit). */
    public boolean enabled() {
        return settingService.getBoolean(SettingKey.MOBILE_FIELD_CREDIT);
    }

    /** What a customer may still take on credit now; null without credit. */
    public BigDecimal leftOf(Customer customer) {
        if (!customer.getType().isCreditAllowed() || customer.isDefaultCustomer() || customer.getCreditLimit().signum() <= 0) {
            return null;
        }
        return FieldCredit.left(true, customer.getCreditLimit(), journalService.receivable(customer.getId()), blocked(customer));
    }

    /** What each customer with credit may still take (those without are left out): the trip's download. */
    public Map<UUID, BigDecimal> leftOf(Collection<Customer> customers) {
        Map<UUID, BigDecimal> left = new HashMap<>();
        if (!enabled()) {
            return left;
        }
        for (Customer c : customers) {
            BigDecimal l = leftOf(c);
            if (l != null) {
                left.put(c.getId(), l);
            }
        }
        return left;
    }

    private boolean blocked(Customer customer) {
        return settingService.getBoolean(SettingKey.CREDIT_BLOCK_OVERDUE)
                && ageing.overdue(customer, settingService.getInt(SettingKey.CREDIT_OVERDUE_GRACE_DAYS)).any();
    }
}
