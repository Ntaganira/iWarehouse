package com.ntaganira.heritier.iWarehouse.service;

import java.math.BigDecimal;
import java.util.*;

/**
 * <pre>
 * - Project   : iWarehouse - Glass Warehouse &amp; Mobile POS (ERP-Lite)
 * - Package   : com.ntaganira.heritier.iWarehouse.service
 * - File      : VatReport.java
 * - Date      : 2026. 10. 09.
 * - User      : Hntaganira
 * - Desc      : Output VAT of a month (TAX-05): each invoice's and credit note's VAT per tax letter, worked out on the
 *               document as it was issued (Vat.totals: rounded per letter on the document), then added up per letter
 *               over the month: sales less credit notes, VAT included, the VAT in them and the rest. Pure,
 *               unit-tested.
 * </pre>
 */
public final class VatReport {

    private VatReport() {
    }

    /** A document line as VAT sees it: its document, tax letter, rate and amount VAT included. */
    public record DocLine(UUID documentId, String taxCode, BigDecimal rate, BigDecimal amount) {
    }

    /** One tax letter over the month: sales and credit notes, VAT included, and the VAT in them. */
    public record Letter(String taxCode, BigDecimal rate, BigDecimal salesGross, BigDecimal salesVat, BigDecimal creditGross,
                         BigDecimal creditVat) {

        public BigDecimal getSalesNet() {
            return salesGross.subtract(salesVat);
        }

        public BigDecimal getCreditNet() {
            return creditGross.subtract(creditVat);
        }

        /** Sales less credit notes, VAT excluded: the taxable amount declared. */
        public BigDecimal getNet() {
            return getSalesNet().subtract(getCreditNet());
        }

        /** The output VAT of the letter: on sales less on credit notes. */
        public BigDecimal getVat() {
            return salesVat.subtract(creditVat);
        }
    }

    /** The letters (alphabetical) and how many invoices and credit notes they come from. */
    public record Result(List<Letter> letters, int invoices, int creditNotes) {

        public BigDecimal getNet() {
            return letters.stream().map(Letter::getNet).reduce(BigDecimal.ZERO, BigDecimal::add);
        }

        public BigDecimal getVat() {
            return letters.stream().map(Letter::getVat).reduce(BigDecimal.ZERO, BigDecimal::add);
        }

        public BigDecimal getSalesGross() {
            return letters.stream().map(Letter::salesGross).reduce(BigDecimal.ZERO, BigDecimal::add);
        }

        public BigDecimal getCreditGross() {
            return letters.stream().map(Letter::creditGross).reduce(BigDecimal.ZERO, BigDecimal::add);
        }
    }

    public static Result of(List<DocLine> sales, List<DocLine> credits) {
        Map<String, BigDecimal[]> byLetter = new TreeMap<>();
        Map<String, BigDecimal> rates = new HashMap<>();
        int invoices = add(sales, byLetter, rates, 0);
        int creditNotes = add(credits, byLetter, rates, 2);
        List<Letter> letters = new ArrayList<>();
        byLetter.forEach((code, v) -> letters.add(new Letter(code, rates.get(code), v[0], v[1], v[2], v[3])));
        return new Result(letters, invoices, creditNotes);
    }

    /** Adds each document's VAT groups at {@code offset} (0 sales, 2 credit notes); returns how many documents. */
    private static int add(List<DocLine> lines, Map<String, BigDecimal[]> byLetter, Map<String, BigDecimal> rates, int offset) {
        Map<UUID, List<Vat.Line>> byDocument = new LinkedHashMap<>();
        for (DocLine l : lines) {
            byDocument.computeIfAbsent(l.documentId(), k -> new ArrayList<>()).add(new Vat.Line(l.taxCode(), l.rate(), l.amount()));
        }
        for (List<Vat.Line> doc : byDocument.values()) {
            for (Vat.Group g : Vat.totals(doc).groups()) {
                BigDecimal[] v = byLetter.computeIfAbsent(g.taxCode(),
                        k -> new BigDecimal[]{BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO});
                v[offset] = v[offset].add(g.gross());
                v[offset + 1] = v[offset + 1].add(g.vat());
                rates.putIfAbsent(g.taxCode(), g.rate());
            }
        }
        return byDocument.size();
    }
}
