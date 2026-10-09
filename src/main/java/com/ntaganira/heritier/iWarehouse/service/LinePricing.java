package com.ntaganira.heritier.iWarehouse.service;

import com.ntaganira.heritier.iWarehouse.entity.Currency;
import com.ntaganira.heritier.iWarehouse.entity.Customer;
import com.ntaganira.heritier.iWarehouse.entity.PriceList;
import com.ntaganira.heritier.iWarehouse.entity.ProcessingService;
import com.ntaganira.heritier.iWarehouse.entity.Product;
import com.ntaganira.heritier.iWarehouse.entity.TaxCategory;
import com.ntaganira.heritier.iWarehouse.repository.CurrencyRepository;
import com.ntaganira.heritier.iWarehouse.repository.TaxCategoryRepository;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.Optional;

/**
 * <pre>
 * - Project   : iWarehouse - Glass Warehouse &amp; Mobile POS (ERP-Lite)
 * - Package   : com.ntaganira.heritier.iWarehouse.service
 * - File      : LinePricing.java
 * - Date      : 2026. 10. 09.
 * - User      : Hntaganira
 * - Desc      : How a sale or quotation line is priced (MD-06, TAX-01), in one place: glass from the customer's
 *               list (or the default one) per m² over the chargeable area, with the glass's tax letter and rate;
 *               processing per its unit (m², metre of edge, piece, hole) at the standard rate; the amount whole
 *               RWF with VAT included (Vat.lineAmount). The counter (SalesService) and quotations use it.
 * </pre>
 */
@Component
public class LinePricing {

    private final PriceListService priceListService;
    private final TaxCategoryRepository taxRepo;
    private final CurrencyRepository currencyRepo;

    public LinePricing(PriceListService priceListService, TaxCategoryRepository taxRepo, CurrencyRepository currencyRepo) {
        this.priceListService = priceListService;
        this.taxRepo = taxRepo;
        this.currencyRepo = currencyRepo;
    }

    /** Glass for a customer: the list, its VAT flag, the price per m², the chargeable area of one piece, the tax. */
    public record Glass(PriceList list, boolean pricesIncludeVat, BigDecimal pricePerM2, BigDecimal chargeableArea,
                        String taxCode, BigDecimal vatRate) {
    }

    /** Processing for a customer: the list, its VAT flag, the price per unit, the units the pieces need, the tax. */
    public record Service(PriceList list, boolean pricesIncludeVat, BigDecimal unitPrice, BigDecimal quantity,
                          String taxCode, BigDecimal vatRate) {
    }

    /** The glass's price for the customer and a piece's chargeable area; empty when no list prices it. */
    public Optional<Glass> glass(Customer customer, Product product, int widthMm, int heightMm) {
        return priceListService.priceFor(customer, product).map(p -> {
            TaxCategory tax = product.getTaxCategory();
            BigDecimal area = Pricing.chargeableArea(widthMm, heightMm, priceListService.minChargeableArea(p.list()));
            return new Glass(p.list(), p.list().isPricesIncludeVat(), p.pricePerM2(), area, tax.getEbmCode(), tax.getRate());
        });
    }

    /** The processing's price for the customer and what the pieces need of it; empty when no list prices it. */
    public Optional<Service> service(Customer customer, ProcessingService service, int widthMm, int heightMm, int quantity,
                                     Integer holes) {
        return priceListService.servicePriceFor(customer, service).map(p -> {
            TaxCategory tax = taxRepo.findByDefaultCategoryTrue().orElseThrow(() -> new IllegalStateException("No default tax category"));
            BigDecimal units = Pricing.serviceQuantity(service.getChargeUnit(), widthMm, heightMm, quantity, holes);
            return new Service(p.list(), p.list().isPricesIncludeVat(), p.price(), units, tax.getEbmCode(), tax.getRate());
        });
    }

    /** Price x basis (m² of a piece, or the processing's units) x quantity, VAT added when the list excludes it, whole RWF. */
    public BigDecimal amount(BigDecimal price, BigDecimal basis, int quantity, boolean pricesIncludeVat, BigDecimal vatRate) {
        return Vat.lineAmount(price, basis, quantity, pricesIncludeVat, vatRate, baseDecimals());
    }

    public int baseDecimals() {
        return currencyRepo.findByBaseCurrencyTrue().map(Currency::getDecimals).orElse(0);
    }
}
