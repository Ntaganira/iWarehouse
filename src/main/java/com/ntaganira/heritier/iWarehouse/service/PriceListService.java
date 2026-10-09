package com.ntaganira.heritier.iWarehouse.service;

import com.ntaganira.heritier.iWarehouse.dto.PriceListDto;
import com.ntaganira.heritier.iWarehouse.dto.ProcessingServiceDto;
import com.ntaganira.heritier.iWarehouse.entity.*;
import com.ntaganira.heritier.iWarehouse.enums.SettingKey;
import com.ntaganira.heritier.iWarehouse.exception.BusinessException;
import com.ntaganira.heritier.iWarehouse.exception.NotFoundException;
import com.ntaganira.heritier.iWarehouse.repository.*;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.math.BigDecimal;
import java.util.*;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * <pre>
 * - Project   : iWarehouse - Glass Warehouse &amp; Mobile POS (ERP-Lite)
 * - Package   : com.ntaganira.heritier.iWarehouse.service
 * - File      : PriceListService.java
 * - Date      : 2026. 10. 08.
 * - User      : Hntaganira
 * - Desc      : Price lists and processing services (MD-06). One list is the default: it prices customers
 *               without a list and fills what a customer's list leaves unpriced (priceFor). The default
 *               list stays active, and a list with active customers cannot be deactivated. Prices are
 *               saved row by row through JPA, so every change is in the change log and the list's
 *               price history.
 * </pre>
 */
@Service
@Transactional(readOnly = true)
public class PriceListService {

    /** Entity types shown in a list's price history. */
    public static final List<String> HISTORY_TYPES = List.of("PriceList", "PriceListItem", "ServicePrice");

    private final PriceListRepository listRepo;
    private final PriceListItemRepository itemRepo;
    private final ProcessingServiceRepository serviceRepo;
    private final ServicePriceRepository servicePriceRepo;
    private final ProductRepository productRepo;
    private final CustomerRepository customerRepo;
    private final SettingService settingService;
    private final DataChangeService dataChangeService;

    public PriceListService(PriceListRepository listRepo, PriceListItemRepository itemRepo,
                            ProcessingServiceRepository serviceRepo, ServicePriceRepository servicePriceRepo,
                            ProductRepository productRepo, CustomerRepository customerRepo,
                            SettingService settingService, DataChangeService dataChangeService) {
        this.listRepo = listRepo;
        this.itemRepo = itemRepo;
        this.serviceRepo = serviceRepo;
        this.servicePriceRepo = servicePriceRepo;
        this.productRepo = productRepo;
        this.customerRepo = customerRepo;
        this.settingService = settingService;
        this.dataChangeService = dataChangeService;
    }

    // ---------------------------------------------------------------- reading

    /** A list with its counts, for the index page. */
    public record ListSummary(PriceList list, long pricedProducts, long pricedServices, long customers) {
    }

    /** A product's price on a list and on the default list (what applies when the list has none). */
    public record ProductPriceRow(Product product, BigDecimal price, BigDecimal defaultPrice) {
    }

    public record ServicePriceRow(ProcessingService service, BigDecimal price, BigDecimal defaultPrice) {
    }

    /** What a customer pays per m² of a product, and which list that price comes from. */
    /** A processing service's price per its charge unit, and the list it comes from (MD-06). */
    public record ServicePriceFor(BigDecimal price, PriceList list) {
    }

    public record UnitPrice(BigDecimal pricePerM2, PriceList list, boolean fromDefaultList) {

        public boolean includesVat() {
            return list.isPricesIncludeVat();
        }
    }

    public record PriceUpdate(int added, int changed, int cleared) {

        public int total() {
            return added + changed + cleared;
        }
    }

    public List<ListSummary> summaries() {
        return listRepo.findAllByOrderByDefaultListDescEnabledDescCodeAsc().stream()
                .map(l -> new ListSummary(l, itemRepo.countByPriceList_IdAndPricePerM2IsNotNull(l.getId()),
                        servicePriceRepo.countByPriceList_IdAndPriceIsNotNull(l.getId()),
                        customerRepo.countByPriceList_IdAndEnabledTrue(l.getId())))
                .toList();
    }

    public long activeProductCount() {
        return productRepo.count((root, query, cb) -> cb.isTrue(root.get("enabled")));
    }

    public PriceList findById(UUID id) {
        return listRepo.findById(id).orElseThrow(() -> new NotFoundException("PriceList", id));
    }

    public PriceList defaultList() {
        return listRepo.findByDefaultListTrue().orElseThrow(() -> new IllegalStateException("No default price list (V9)"));
    }

    /** Smallest area charged per piece on this list: its own minimum, else Settings (0.25 m²). */
    public BigDecimal minChargeableArea(PriceList list) {
        return list.getMinChargeableM2() != null ? list.getMinChargeableM2()
                : settingService.getDecimal(SettingKey.MIN_CHARGEABLE_AREA);
    }

    public BigDecimal settingMinChargeableArea() {
        return settingService.getDecimal(SettingKey.MIN_CHARGEABLE_AREA);
    }

    /** Every active product with its price on the list and on the default list. */
    public List<ProductPriceRow> productRows(PriceList list) {
        Map<UUID, BigDecimal> own = productPrices(list.getId());
        Map<UUID, BigDecimal> fallback = list.isDefaultList() ? Map.of() : productPrices(defaultList().getId());
        Sort order = Sort.by("glassType").and(Sort.by("variant")).and(Sort.by("thicknessMm"));
        return productRepo.findAll(order).stream()
                .filter(Product::isEnabled)
                .map(p -> new ProductPriceRow(p, own.get(p.getId()), fallback.get(p.getId())))
                .toList();
    }

    public List<ServicePriceRow> serviceRows(PriceList list) {
        Map<UUID, BigDecimal> own = servicePrices(list.getId());
        Map<UUID, BigDecimal> fallback = list.isDefaultList() ? Map.of() : servicePrices(defaultList().getId());
        return serviceRepo.findByEnabledTrueOrderByCodeAsc().stream()
                .map(s -> new ServicePriceRow(s, own.get(s.getId()), fallback.get(s.getId())))
                .toList();
    }

    public List<Customer> customers(UUID listId) {
        return customerRepo.findByPriceList_IdOrderByNameAsc(listId);
    }

    /** The customer's list first, then the default list; empty when neither prices the product (MD-06). */
    public Optional<UnitPrice> priceFor(Customer customer, Product product) {
        PriceList defaultList = defaultList();
        PriceList own = customer == null || customer.getPriceList() == null ? defaultList : customer.getPriceList();
        BigDecimal ownPrice = itemRepo.findByPriceList_IdAndProduct_Id(own.getId(), product.getId())
                .map(PriceListItem::getPricePerM2).orElse(null);
        BigDecimal defaultPrice = own.getId().equals(defaultList.getId()) ? null
                : itemRepo.findByPriceList_IdAndProduct_Id(defaultList.getId(), product.getId())
                        .map(PriceListItem::getPricePerM2).orElse(null);
        return Pricing.resolve(ownPrice, defaultPrice)
                .map(r -> new UnitPrice(r.price(), r.fromDefaultList() ? defaultList : own, r.fromDefaultList()));
    }

    /** A service's price for the customer: their list first, then the default list; empty when neither prices it. */
    public Optional<ServicePriceFor> servicePriceFor(Customer customer, ProcessingService service) {
        PriceList defaultList = defaultList();
        PriceList own = customer == null || customer.getPriceList() == null ? defaultList : customer.getPriceList();
        BigDecimal ownPrice = servicePrices(own.getId()).get(service.getId());
        if (ownPrice != null) {
            return Optional.of(new ServicePriceFor(ownPrice, own));
        }
        BigDecimal defaultPrice = own.getId().equals(defaultList.getId()) ? null : servicePrices(defaultList.getId()).get(service.getId());
        return defaultPrice == null ? Optional.empty() : Optional.of(new ServicePriceFor(defaultPrice, defaultList));
    }

    /** Changes to the list and its prices, newest first. */
    public Page<DataChangeLog> history(PriceList list, int page, int size) {
        List<String> ids = new ArrayList<>();
        ids.add(list.getId().toString());
        itemRepo.findIdsByPriceListId(list.getId()).forEach(id -> ids.add(id.toString()));
        servicePriceRepo.findIdsByPriceListId(list.getId()).forEach(id -> ids.add(id.toString()));
        return dataChangeService.historyOf(HISTORY_TYPES, ids, page, size);
    }

    /** What each price row of the list is about, by row id: a product or a service (for the history). */
    public Map<String, Object> historySubjects(UUID listId) {
        Map<String, Object> subjects = new HashMap<>();
        itemRepo.findByPriceList_Id(listId).forEach(i -> subjects.put(i.getId().toString(), i.getProduct()));
        servicePriceRepo.findByPriceList_Id(listId).forEach(p -> subjects.put(p.getId().toString(), p.getService()));
        return subjects;
    }

    private Map<UUID, BigDecimal> productPrices(UUID listId) {
        Map<UUID, BigDecimal> prices = new HashMap<>();
        itemRepo.findByPriceList_Id(listId).forEach(i -> {
            if (i.getPricePerM2() != null) {
                prices.put(i.getProduct().getId(), i.getPricePerM2());
            }
        });
        return prices;
    }

    private Map<UUID, BigDecimal> servicePrices(UUID listId) {
        Map<UUID, BigDecimal> prices = new HashMap<>();
        servicePriceRepo.findByPriceList_Id(listId).forEach(s -> {
            if (s.getPrice() != null) {
                prices.put(s.getService().getId(), s.getPrice());
            }
        });
        return prices;
    }

    // ---------------------------------------------------------------- lists

    @Transactional
    public PriceList create(PriceListDto dto) {
        if (listRepo.existsByCode(dto.getCode())) {
            throw BusinessException.onField("code", "pricelist.code.taken", dto.getCode());
        }
        PriceList list = new PriceList();
        list.setCode(dto.getCode());
        apply(list, dto);
        return listRepo.save(list);
    }

    @Transactional
    public PriceList update(UUID id, PriceListDto dto) {
        PriceList list = findById(id);
        apply(list, dto);
        return list;
    }

    @Transactional
    public PriceList setEnabled(UUID id, boolean enabled) {
        PriceList list = findById(id);
        if (!enabled) {
            if (list.isDefaultList()) {
                throw BusinessException.of("pricelist.default.disable", list.getName());
            }
            long customers = customerRepo.countByPriceList_IdAndEnabledTrue(id);
            if (customers > 0) {
                throw BusinessException.of("pricelist.disable.customers", list.getName(), customers);
            }
        }
        list.setEnabled(enabled);
        return list;
    }

    /** Only one default at a time: the old one is cleared and flushed first (uk_price_lists_default). */
    @Transactional
    public PriceList makeDefault(UUID id) {
        PriceList list = findById(id);
        if (!list.isEnabled()) {
            throw BusinessException.of("pricelist.default.inactive", list.getName());
        }
        listRepo.findByDefaultListTrue()
                .filter(current -> !current.getId().equals(id))
                .ifPresent(current -> {
                    current.setDefaultList(false);
                    listRepo.saveAndFlush(current);
                });
        list.setDefaultList(true);
        return list;
    }

    private static void apply(PriceList list, PriceListDto dto) {
        list.setName(dto.getName().trim());
        list.setPricesIncludeVat(dto.isPricesIncludeVat());
        list.setMinChargeableM2(dto.getMinChargeableM2());
        list.setNotes(PartyRules.clean(dto.getNotes()));
    }

    // ---------------------------------------------------------------- prices

    /**
     * Saves the prices typed on the price page: a value adds or changes a price, null clears it.
     * Unchanged rows are not touched, so the change log only holds real changes.
     */
    @Transactional
    public PriceUpdate updatePrices(UUID listId, Map<UUID, BigDecimal> productPrices, Map<UUID, BigDecimal> servicePrices) {
        PriceList list = findById(listId);
        int[] counts = new int[3];

        Map<UUID, PriceListItem> items = itemRepo.findByPriceList_Id(listId).stream()
                .collect(Collectors.toMap(i -> i.getProduct().getId(), Function.identity()));
        productPrices.forEach((productId, price) -> {
            PriceListItem item = items.get(productId);
            BigDecimal old = item == null ? null : item.getPricePerM2();
            if (!count(old, price, counts)) {
                return;
            }
            if (item == null) {
                Product product = productRepo.findById(productId).orElseThrow(() -> new NotFoundException("Product", productId));
                item = new PriceListItem();
                item.setPriceList(list);
                item.setProduct(product);
                item.setPricePerM2(price);
                itemRepo.save(item);
            } else {
                item.setPricePerM2(price);
            }
        });

        Map<UUID, ServicePrice> rows = servicePriceRepo.findByPriceList_Id(listId).stream()
                .collect(Collectors.toMap(s -> s.getService().getId(), Function.identity()));
        servicePrices.forEach((serviceId, price) -> {
            ServicePrice row = rows.get(serviceId);
            BigDecimal old = row == null ? null : row.getPrice();
            if (!count(old, price, counts)) {
                return;
            }
            if (row == null) {
                ProcessingService service = serviceRepo.findById(serviceId)
                        .orElseThrow(() -> new NotFoundException("ProcessingService", serviceId));
                row = new ServicePrice();
                row.setPriceList(list);
                row.setService(service);
                row.setPrice(price);
                servicePriceRepo.save(row);
            } else {
                row.setPrice(price);
            }
        });
        return new PriceUpdate(counts[0], counts[1], counts[2]);
    }

    /** Counts the change (added, changed, cleared); false when the price stays the same. */
    private static boolean count(BigDecimal old, BigDecimal price, int[] counts) {
        if (old == null && price == null || old != null && price != null && old.compareTo(price) == 0) {
            return false;
        }
        counts[old == null ? 0 : price == null ? 2 : 1]++;
        return true;
    }

    // ---------------------------------------------------------------- processing services

    public List<ProcessingService> services() {
        return serviceRepo.findAllByOrderByEnabledDescCodeAsc();
    }

    public ProcessingService findService(UUID id) {
        return serviceRepo.findById(id).orElseThrow(() -> new NotFoundException("ProcessingService", id));
    }

    @Transactional
    public ProcessingService createService(ProcessingServiceDto dto) {
        if (serviceRepo.existsByCode(dto.getCode())) {
            throw BusinessException.onField("code", "service.code.taken", dto.getCode());
        }
        ProcessingService service = new ProcessingService();
        service.setCode(dto.getCode());
        service.setName(dto.getName().trim());
        service.setChargeUnit(dto.getChargeUnit());
        return serviceRepo.save(service);
    }

    /** The charge unit is fixed once a list prices the service: a price per hole must not become per m². */
    @Transactional
    public ProcessingService updateService(UUID id, ProcessingServiceDto dto) {
        ProcessingService service = findService(id);
        if (service.getChargeUnit() != dto.getChargeUnit() && isPriced(service)) {
            throw BusinessException.onField("chargeUnit", "service.unit.priced", service.getName());
        }
        service.setName(dto.getName().trim());
        service.setChargeUnit(dto.getChargeUnit());
        return service;
    }

    @Transactional
    public ProcessingService setServiceEnabled(UUID id, boolean enabled) {
        ProcessingService service = findService(id);
        service.setEnabled(enabled);
        return service;
    }

    public boolean isPriced(ProcessingService service) {
        return servicePriceRepo.existsByService_IdAndPriceIsNotNull(service.getId());
    }

    /** Text for the activity log: "3 added, 8 changed, 1 cleared". */
    public static String describe(PriceUpdate update) {
        List<String> parts = new ArrayList<>();
        if (update.added() > 0) {
            parts.add(update.added() + " added");
        }
        if (update.changed() > 0) {
            parts.add(update.changed() + " changed");
        }
        if (update.cleared() > 0) {
            parts.add(update.cleared() + " cleared");
        }
        return StringUtils.collectionToDelimitedString(parts, ", ");
    }
}
