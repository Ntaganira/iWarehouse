package com.ntaganira.heritier.iWarehouse.ebm;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ntaganira.heritier.iWarehouse.repository.EbmReceiptRepository;
import org.springframework.stereotype.Component;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.time.Clock;
import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * <pre>
 * - Project   : iWarehouse - Glass Warehouse &amp; Mobile POS (ERP-Lite)
 * - Package   : com.ntaganira.heritier.iWarehouse.ebm
 * - File      : SimulatedVsdc.java
 * - Date      : 2026. 10. 10.
 * - User      : Hntaganira
 * - Desc      : A VSDC for development and tests (Settings, ebm.mode = SIMULATOR). It checks a request as RRA's server
 *               does (formats, item count, at most 2 decimals, an item class on every item, the letters' amounts adding
 *               up, a refund naming its sale and a reason) and answers 910 with the field when it is wrong. A good sale
 *               gets receipt numbers that go on from the last simulated receipt, and a signature and internal data made
 *               from the receipt (HMAC, Base32): they look like RRA's but are not fiscal, and every page prints that.
 *               An outage can be switched on (the EBM page) to show a sale completing while EBM is down (AT-09).
 * </pre>
 */
@Component
public class SimulatedVsdc implements VsdcClient {

    public static final String SDC_ID = "SDCSIM000001";
    public static final String MRC_NO = "SIM00000001";
    private static final byte[] KEY = "iWarehouse VSDC simulator".getBytes(StandardCharsets.UTF_8);

    private final EbmReceiptRepository receipts;
    private final ObjectMapper mapper;
    private final Clock clock;
    private final AtomicBoolean down = new AtomicBoolean();
    private final Set<Long> signed = new HashSet<>();
    private long lastReceipt = -1;

    public SimulatedVsdc(EbmReceiptRepository receipts, ObjectMapper mapper, Clock clock) {
        this.receipts = receipts;
        this.mapper = mapper;
        this.clock = clock;
    }

    public boolean isDown() {
        return down.get();
    }

    public void setDown(boolean value) {
        down.set(value);
    }

    @Override
    public boolean simulated() {
        return true;
    }

    @Override
    public Vsdc.Reply<Vsdc.InitData> init(Vsdc.InitRequest r) {
        String json = write(r);
        checkUp(json);
        List<String> errors = new ArrayList<>();
        tin(errors, "tin", r.tin());
        branch(errors, r.bhfId());
        if (blank(r.dvcSrlNo()) || r.dvcSrlNo().length() > 100) {
            errors.add("dvcSrlNo : required, at most 100");
        }
        if (!errors.isEmpty()) {
            return refused(json, errors);
        }
        Long lastInvcNo = receipts.maxSimulatedInvcNo();
        Vsdc.InitInfo info = new Vsdc.InitInfo(r.tin(), "SIMULATED TAXPAYER", r.bhfId(), "Simulated branch",
                "SIM" + String.format("%013d", Math.abs((long) r.dvcSrlNo().hashCode())), SDC_ID, MRC_NO,
                lastInvcNo == null ? 0 : lastInvcNo, receiptsSoFar(), receiptsSoFar());
        return answer(json, new Vsdc.InitData(info));
    }

    @Override
    public Vsdc.Reply<JsonNode> saveItem(Vsdc.ItemRequest r) {
        String json = write(r);
        checkUp(json);
        List<String> errors = new ArrayList<>();
        tin(errors, "tin", r.tin());
        branch(errors, r.bhfId());
        length(errors, "itemCd", r.itemCd(), 20);
        length(errors, "itemClsCd", r.itemClsCd(), 10);
        if (!Set.of("1", "2", "3").contains(String.valueOf(r.itemTyCd()))) {
            errors.add("itemTyCd : Code value error");
        }
        length(errors, "itemNm", r.itemNm(), 200);
        if (r.orgnNatCd() == null || !r.orgnNatCd().matches("[A-Z]{2}")) {
            errors.add("orgnNatCd : Code value error");
        }
        length(errors, "pkgUnitCd", r.pkgUnitCd(), 5);
        length(errors, "qtyUnitCd", r.qtyUnitCd(), 5);
        letter(errors, "taxTyCd", r.taxTyCd());
        amount(errors, "dftPrc", r.dftPrc());
        if (!errors.isEmpty()) {
            return refused(json, errors);
        }
        return answer(json, null);
    }

    @Override
    public Vsdc.Reply<Vsdc.Signature> saveSale(Vsdc.SaleRequest r) {
        String json = write(r);
        checkUp(json);
        List<String> errors = check(r);
        if (!errors.isEmpty()) {
            return refused(json, errors);
        }
        synchronized (this) {
            if (signed.contains(r.invcNo())) {
                return reply(json, "924", "Invoice number already exists.", null);
            }
            long number = receiptsSoFar() + 1;
            lastReceipt = number;
            signed.add(r.invcNo());
            byte[] mac = mac(r.tin() + "|" + r.bhfId() + "|" + r.invcNo() + "|" + r.rcptTyCd() + "|" + r.totAmt().toPlainString()
                    + "|" + r.cfmDt() + "|" + number);
            Vsdc.Signature signature = new Vsdc.Signature(number, EbmCodes.base32(Arrays.copyOfRange(mac, 10, 26)),
                    EbmCodes.base32(Arrays.copyOfRange(mac, 0, 10)), number, EbmCodes.dateTime(LocalDateTime.now(clock)), SDC_ID, MRC_NO);
            return answer(json, signature);
        }
    }

    /** What RRA's server refuses in a sale (910 and the field). */
    static List<String> check(Vsdc.SaleRequest r) {
        List<String> errors = new ArrayList<>();
        tin(errors, "tin", r.tin());
        branch(errors, r.bhfId());
        if (r.invcNo() < 1) {
            errors.add("invcNo : must be positive");
        }
        boolean refund = EbmCodes.REFUND.equals(r.rcptTyCd());
        if (!refund && !EbmCodes.SALE.equals(r.rcptTyCd())) {
            errors.add("rcptTyCd : Code value error");
        }
        if (refund ? r.orgInvcNo() < 1 : r.orgInvcNo() != 0) {
            errors.add("orgInvcNo : " + (refund ? "a refund names its sale" : "must be 0 for a sale"));
        }
        if (refund && (r.rfdRsnCd() == null || !EbmCodes.REFUND_REASONS.contains(r.rfdRsnCd()))) {
            errors.add("rfdRsnCd : Code value error");
        }
        if (!EbmCodes.NORMAL.equals(r.salesTyCd())) {
            errors.add("salesTyCd : Code value error");
        }
        if (!(refund ? EbmCodes.REFUNDED : EbmCodes.APPROVED).equals(r.salesSttsCd())) {
            errors.add("salesSttsCd : Code value error");
        }
        if (r.cfmDt() == null || !r.cfmDt().matches("\\d{14}")) {
            errors.add("cfmDt : Date format error");
        }
        if (r.salesDt() == null || !r.salesDt().matches("\\d{8}")) {
            errors.add("salesDt : Date format error");
        }
        if (r.custTin() != null) {
            tin(errors, "custTin", r.custTin());
        }
        if (r.prcOrdCd() != null && r.prcOrdCd().length() > 6) {
            errors.add("prcOrdCd : at most 6");
        }
        if (r.pmtTyCd() == null || !r.pmtTyCd().matches("0[1-7]")) {
            errors.add("pmtTyCd : Code value error");
        }
        if (r.itemList() == null || r.itemList().isEmpty() || r.totItemCnt() != r.itemList().size()) {
            errors.add("totItemCnt : Item Count error");
            return errors;
        }
        Map<String, BigDecimal> taxable = new HashMap<>();
        Map<String, BigDecimal> tax = new HashMap<>();
        for (int i = 0; i < r.itemList().size(); i++) {
            Vsdc.SaleItem item = r.itemList().get(i);
            String at = "itemList[" + i + "].";
            if (item.itemSeq() != i + 1) {
                errors.add(at + "itemSeq : out of order");
            }
            length(errors, at + "itemClsCd", item.itemClsCd(), 10);
            length(errors, at + "itemNm", item.itemNm(), 200);
            length(errors, at + "qtyUnitCd", item.qtyUnitCd(), 5);
            letter(errors, at + "taxTyCd", item.taxTyCd());
            amount(errors, at + "qty", item.qty());
            amount(errors, at + "prc", item.prc());
            amount(errors, at + "splyAmt", item.splyAmt());
            amount(errors, at + "taxblAmt", item.taxblAmt());
            amount(errors, at + "taxAmt", item.taxAmt());
            amount(errors, at + "totAmt", item.totAmt());
            if (item.qty() != null && item.qty().signum() <= 0) {
                errors.add(at + "qty : must be positive");
            }
            if (item.splyAmt() != null && item.dcAmt() != null && item.taxblAmt() != null
                    && item.splyAmt().subtract(item.dcAmt()).compareTo(item.taxblAmt()) != 0) {
                errors.add(at + "taxblAmt : supply less discount");
            }
            if (item.taxblAmt() != null && item.totAmt() != null && item.taxblAmt().compareTo(item.totAmt()) != 0) {
                errors.add(at + "totAmt : the taxable amount");
            }
            if (item.taxTyCd() != null && item.taxblAmt() != null && item.taxAmt() != null) {
                taxable.merge(item.taxTyCd(), item.taxblAmt(), BigDecimal::add);
                tax.merge(item.taxTyCd(), item.taxAmt(), BigDecimal::add);
            }
        }
        if (!errors.isEmpty()) {
            return errors;
        }
        sum(errors, "taxblAmtA", r.taxblAmtA(), taxable.get("A"));
        sum(errors, "taxblAmtB", r.taxblAmtB(), taxable.get("B"));
        sum(errors, "taxblAmtC", r.taxblAmtC(), taxable.get("C"));
        sum(errors, "taxblAmtD", r.taxblAmtD(), taxable.get("D"));
        sum(errors, "taxAmtA", r.taxAmtA(), tax.get("A"));
        sum(errors, "taxAmtB", r.taxAmtB(), tax.get("B"));
        sum(errors, "taxAmtC", r.taxAmtC(), tax.get("C"));
        sum(errors, "taxAmtD", r.taxAmtD(), tax.get("D"));
        BigDecimal totalTaxable = taxable.values().stream().reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal totalTax = tax.values().stream().reduce(BigDecimal.ZERO, BigDecimal::add);
        sum(errors, "totTaxblAmt", r.totTaxblAmt(), totalTaxable);
        sum(errors, "totTaxAmt", r.totTaxAmt(), totalTax);
        sum(errors, "totAmt", r.totAmt(), totalTaxable);
        return errors;
    }

    private long receiptsSoFar() {
        synchronized (this) {
            if (lastReceipt < 0) {
                Long max = receipts.maxSimulatedReceiptNo();
                lastReceipt = max == null ? 0 : max;
            }
            return lastReceipt;
        }
    }

    private void checkUp(String json) {
        if (down.get()) {
            throw new VsdcUnavailableException("The simulated VSDC is down (outage switched on)", json);
        }
    }

    private <T> Vsdc.Reply<T> answer(String json, T data) {
        return reply(json, VsdcResults.OK, "It is succeeded", data);
    }

    private <T> Vsdc.Reply<T> refused(String json, List<String> errors) {
        return reply(json, "910", "Request parameter error [" + String.join("; ", errors) + "]", null);
    }

    private <T> Vsdc.Reply<T> reply(String json, String code, String message, T data) {
        Vsdc.Envelope<T> envelope = new Vsdc.Envelope<>(code, message, EbmCodes.dateTime(LocalDateTime.now(clock)), data);
        return new Vsdc.Reply<>(json, write(envelope), envelope);
    }

    private String write(Object value) {
        try {
            return mapper.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Cannot write a simulated VSDC message", e);
        }
    }

    private static byte[] mac(String data) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(KEY, "HmacSHA256"));
            return mac.doFinal(data.getBytes(StandardCharsets.UTF_8));
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("HmacSHA256 is not available", e);
        }
    }

    private static void tin(List<String> errors, String field, String value) {
        if (value == null || !value.matches("\\d{9}")) {
            errors.add(field + " : 9 digits");
        }
    }

    private static void branch(List<String> errors, String value) {
        if (value == null || !value.matches("\\d{2}")) {
            errors.add("bhfId : 2 digits");
        }
    }

    private static void length(List<String> errors, String field, String value, int max) {
        if (blank(value) || value.length() > max) {
            errors.add(field + " : required, at most " + max);
        }
    }

    private static void letter(List<String> errors, String field, String value) {
        if (value == null || !EbmRequests.LETTERS.contains(value)) {
            errors.add(field + " : Code value error");
        }
    }

    private static void amount(List<String> errors, String field, BigDecimal value) {
        if (value == null || value.scale() > 2 || value.signum() < 0) {
            errors.add(field + " : numeric(16,2) error");
        }
    }

    private static void sum(List<String> errors, String field, BigDecimal value, BigDecimal expected) {
        BigDecimal e = expected == null ? BigDecimal.ZERO : expected;
        if (value == null || value.compareTo(e) != 0) {
            errors.add(field + " : " + value + " is not the items' " + e);
        }
    }

    private static boolean blank(String s) {
        return s == null || s.isBlank();
    }
}
