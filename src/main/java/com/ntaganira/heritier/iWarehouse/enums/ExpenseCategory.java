package com.ntaganira.heritier.iWarehouse.enums;

/**
 * <pre>
 * - Project   : iWarehouse - Glass Warehouse &amp; Mobile POS (ERP-Lite)
 * - Package   : com.ntaganira.heritier.iWarehouse.enums
 * - File      : ExpenseCategory.java
 * - Date      : 2026. 10. 16.
 * - User      : NTAGANIRA Heritier
 * - Email     : ntaganira71@gmail.com
 * - Phone     : +250 788 533 669
 * - Desc      : What an expense bill line is for (CMP 01): each category posts to its account. Recurring bills
 *               (utilities, rent, telephone, bank charges, insurance, taxes, travel) need no requisition; purchases do (CMP 06).
 * </pre>
 */
public enum ExpenseCategory {

    UTILITIES(AccountKey.EXPENSE_UTILITIES, false),
    RENT(AccountKey.EXPENSE_RENT, false),
    COMMUNICATION(AccountKey.EXPENSE_COMMUNICATION, false),
    PROFESSIONAL_FEES(AccountKey.EXPENSE_PROFESSIONAL, true),
    REPAIRS(AccountKey.EXPENSE_REPAIRS, true),
    TRANSPORT(AccountKey.EXPENSE_TRANSPORT, true),
    TRAVEL(AccountKey.EXPENSE_TRAVEL, false),
    OFFICE(AccountKey.EXPENSE_OFFICE, true),
    SECURITY_CLEANING(AccountKey.EXPENSE_SECURITY, true),
    BANK_CHARGES(AccountKey.EXPENSE_BANK_CHARGES, false),
    MARKETING(AccountKey.EXPENSE_MARKETING, true),
    TRAINING(AccountKey.EXPENSE_TRAINING, true),
    INSURANCE(AccountKey.EXPENSE_INSURANCE, false),
    TAXES_LICENCES(AccountKey.EXPENSE_TAXES, false),
    OTHER(AccountKey.EXPENSE_OTHER, true),
    /** A capital item (equipment, furniture, a vehicle): capital expenditure. */
    CAPITAL_ITEM(AccountKey.FIXED_ASSETS, true);

    private final AccountKey account;
    private final boolean purchase;

    ExpenseCategory(AccountKey account, boolean purchase) {
        this.account = account;
        this.purchase = purchase;
    }

    /** The account its lines are posted to. */
    public AccountKey account() {
        return account;
    }

    /** A purchase a requisition comes before (CMP 06), not a recurring bill. */
    public boolean needsRequisition() {
        return purchase;
    }

    public boolean isCapital() {
        return this == CAPITAL_ITEM;
    }
}
