package com.ntaganira.heritier.iWarehouse.ebm;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.math.BigDecimal;
import java.util.List;

/**
 * <pre>
 * - Project   : iWarehouse - Glass Warehouse &amp; Mobile POS (ERP-Lite)
 * - Package   : com.ntaganira.heritier.iWarehouse.ebm
 * - File      : Vsdc.java
 * - Date      : 2026. 10. 10.
 * - User      : Hntaganira
 * - Desc      : The messages of RRA's VSDC API (RRA_VSDC_DOC v1.0.5), field for field as the VSDC reads them:
 *               device initialisation (/initializer/selectInitInfo), item registration (/items/saveItems) and a
 *               sale or refund with its receipt (/trnsSales/saveSales). Every call is a JSON POST answered by an
 *               envelope {resultCd, resultMsg, resultDt, data}; "000" is success. Amounts have at most 2 decimals,
 *               dates are yyyyMMddHHmmss (yyyyMMdd for a plain date). Prices and taxable amounts include VAT.
 * </pre>
 */
public final class Vsdc {

    public static final String INIT = "/initializer/selectInitInfo";
    public static final String SAVE_ITEM = "/items/saveItems";
    public static final String SAVE_SALE = "/trnsSales/saveSales";

    private Vsdc() {
    }

    /** What every call answers. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Envelope<T>(String resultCd, String resultMsg, String resultDt, T data) {
    }

    /** A call as sent and answered: both texts are kept with the receipt. */
    public record Reply<T>(String requestJson, String responseJson, Envelope<T> envelope) {

        public String code() {
            return envelope.resultCd();
        }

        public String message() {
            return envelope.resultMsg();
        }

        public T data() {
            return envelope.data();
        }
    }

    // ------------------------------------------------------------------ device initialisation

    public record InitRequest(String tin, String bhfId, String dvcSrlNo) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record InitData(InitInfo info) {
    }

    /** The device as RRA registered it (its keys are not read: the VSDC keeps them). */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record InitInfo(String tin, String taxprNm, String bhfId, String bhfNm, String dvcId, String sdcId, String mrcNo,
                           Long lastInvcNo, Long lastSaleRcptNo, Long lastSaleInvcNo) {
    }

    // ------------------------------------------------------------------ item registration

    public record ItemRequest(String tin, String bhfId, String itemCd, String itemClsCd, String itemTyCd, String itemNm,
                              String itemStdNm, String orgnNatCd, String pkgUnitCd, String qtyUnitCd, String taxTyCd,
                              String btchNo, String bcd, BigDecimal dftPrc, BigDecimal grpPrcL1, BigDecimal grpPrcL2,
                              BigDecimal grpPrcL3, BigDecimal grpPrcL4, BigDecimal grpPrcL5, String addInfo, BigDecimal sftyQty,
                              String isrcAplcbYn, String useYn, String regrNm, String regrId, String modrNm, String modrId) {
    }

    // ------------------------------------------------------------------ sale or refund

    public record SaleRequest(String tin, String bhfId, long invcNo, long orgInvcNo, String custTin, String prcOrdCd,
                              String custNm, String salesTyCd, String rcptTyCd, String pmtTyCd, String salesSttsCd,
                              String cfmDt, String salesDt, String stockRlsDt, String cnclReqDt, String cnclDt, String rfdDt,
                              String rfdRsnCd, int totItemCnt,
                              BigDecimal taxblAmtA, BigDecimal taxblAmtB, BigDecimal taxblAmtC, BigDecimal taxblAmtD,
                              BigDecimal taxRtA, BigDecimal taxRtB, BigDecimal taxRtC, BigDecimal taxRtD,
                              BigDecimal taxAmtA, BigDecimal taxAmtB, BigDecimal taxAmtC, BigDecimal taxAmtD,
                              BigDecimal totTaxblAmt, BigDecimal totTaxAmt, BigDecimal totAmt, String prchrAcptcYn,
                              String remark, String regrId, String regrNm, String modrId, String modrNm,
                              SaleReceipt receipt, List<SaleItem> itemList) {
    }

    public record SaleReceipt(String custTin, String custMblNo, long rptNo, String trdeNm, String adrs, String topMsg,
                              String btmMsg, String prchrAcptcYn) {
    }

    public record SaleItem(int itemSeq, String itemCd, String itemClsCd, String itemNm, String bcd, String pkgUnitCd,
                           BigDecimal pkg, String qtyUnitCd, BigDecimal qty, BigDecimal prc, BigDecimal splyAmt, BigDecimal dcRt,
                           BigDecimal dcAmt, String isrccCd, String isrccNm, BigDecimal isrcRt, BigDecimal isrcAmt,
                           String taxTyCd, BigDecimal taxblAmt, BigDecimal taxAmt, BigDecimal totAmt) {
    }

    /** What the VSDC returns for a signed receipt. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Signature(Long rcptNo, String intrlData, String rcptSign, Long totRcptNo, String vsdcRcptPbctDate,
                            String sdcId, String mrcNo) {

        public boolean isComplete() {
            return rcptNo != null && totRcptNo != null && notBlank(intrlData) && notBlank(rcptSign) && notBlank(sdcId) && notBlank(mrcNo);
        }

        private static boolean notBlank(String s) {
            return s != null && !s.isBlank();
        }
    }
}
