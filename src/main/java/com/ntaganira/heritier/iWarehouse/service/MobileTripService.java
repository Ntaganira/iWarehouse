package com.ntaganira.heritier.iWarehouse.service;

import com.ntaganira.heritier.iWarehouse.entity.*;
import com.ntaganira.heritier.iWarehouse.enums.DocumentType;
import com.ntaganira.heritier.iWarehouse.enums.EbmMode;
import com.ntaganira.heritier.iWarehouse.enums.SettingKey;
import com.ntaganira.heritier.iWarehouse.enums.StockStatus;
import com.ntaganira.heritier.iWarehouse.enums.TripStatus;
import com.ntaganira.heritier.iWarehouse.exception.BusinessException;
import com.ntaganira.heritier.iWarehouse.exception.NotFoundException;
import com.ntaganira.heritier.iWarehouse.repository.*;
import com.ntaganira.heritier.iWarehouse.security.AppUserPrincipal;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDateTime;
import java.util.*;
import java.util.stream.Collectors;

/**
 * <pre>
 * - Project   : iWarehouse - Glass Warehouse &amp; Mobile POS (ERP-Lite)
 * - Package   : com.ntaganira.heritier.iWarehouse.service
 * - File      : MobileTripService.java
 * - Date      : 2026. 10. 10.
 * - User      : Hntaganira
 * - Desc      : What a driver's phone downloads at trip start (SYNC-01): their trip on the road, the units on the vehicle
 *               (MPOS-02), the active customers, the prices frozen for the trip and a block of invoice numbers for the
 *               phone. The prices are taken once, at the trip's first download, for the default list and every list a
 *               customer has, for the glass on board (MPOS-04); every phone and the server price the trip's sales with
 *               them. The phone holds one number per unit still on board (a sale takes at least one unit), topped up at
 *               each download. Locks the trip, so two phones never take the same prices or numbers.
 * </pre>
 */
@Service
@Transactional(readOnly = true)
public class MobileTripService {

    private final TripRepository tripRepo;
    private final DriverRepository driverRepo;
    private final StockUnitRepository unitRepo;
    private final CustomerRepository customerRepo;
    private final UserRepository userRepo;
    private final TripPriceListRepository priceListSnapshotRepo;
    private final TripPriceRepository priceSnapshotRepo;
    private final TripInvoiceNumberRepository numberRepo;
    private final PriceListItemRepository itemRepo;
    private final PriceListService priceListService;
    private final DocumentNumberService numbers;
    private final SettingService settings;
    private final LinePricing pricing;
    private final Clock clock;

    public MobileTripService(TripRepository tripRepo, DriverRepository driverRepo, StockUnitRepository unitRepo,
                             CustomerRepository customerRepo, UserRepository userRepo, TripPriceListRepository priceListSnapshotRepo,
                             TripPriceRepository priceSnapshotRepo, TripInvoiceNumberRepository numberRepo, PriceListItemRepository itemRepo,
                             PriceListService priceListService, DocumentNumberService numbers, SettingService settings, LinePricing pricing,
                             Clock clock) {
        this.tripRepo = tripRepo;
        this.driverRepo = driverRepo;
        this.unitRepo = unitRepo;
        this.customerRepo = customerRepo;
        this.userRepo = userRepo;
        this.priceListSnapshotRepo = priceListSnapshotRepo;
        this.priceSnapshotRepo = priceSnapshotRepo;
        this.numberRepo = numberRepo;
        this.itemRepo = itemRepo;
        this.priceListService = priceListService;
        this.numbers = numbers;
        this.settings = settings;
        this.pricing = pricing;
        this.clock = clock;
    }

    /** The company as receipts print it. */
    public record Company(String name, String tin, String address, String phone) {
    }

    /** Everything a phone needs to sell offline for a trip. */
    public record Bundle(Trip trip, List<StockUnit> units, List<TripPriceList> lists, List<TripPrice> prices, List<Customer> customers,
                         List<String> numbers, BigDecimal discountLimit, int decimals, Company company, EbmMode ebmMode,
                         LocalDateTime downloadedAt) {
    }

    /** The driver's trip on the road, or why there is none. */
    public Trip currentTrip(Long userId) {
        Driver driver = driverRepo.findByUser_Id(userId).orElseThrow(() -> BusinessException.of("mobile.trip.notDriver"));
        Trip trip = tripRepo.findFirstByDriver_IdAndStatus(driver.getId(), TripStatus.DEPARTED)
                .orElseThrow(() -> BusinessException.of("mobile.trip.none"));
        return tripRepo.findDetailedById(trip.getId()).orElseThrow(() -> new NotFoundException("Trip", trip.getId()));
    }

    @Transactional
    public Bundle download(AppUserPrincipal user, UUID deviceId) {
        Trip trip = currentTrip(user.getId());
        tripRepo.lockById(trip.getId());
        LocalDateTime now = LocalDateTime.now(clock);
        List<StockUnit> units = unitsOnBoard(trip);
        if (!priceListSnapshotRepo.existsByTripId(trip.getId())) {
            freezePrices(trip, units, now);
        }
        // One number per unit on board: a sale takes at least one, so the phone never runs out
        List<String> unused = numberRepo.findByTripIdAndDeviceIdOrderByNumber(trip.getId(), deviceId).stream()
                .filter(n -> !n.isUsed()).map(TripInvoiceNumber::getNumber).collect(Collectors.toCollection(ArrayList::new));
        for (int i = unused.size(); i < units.size(); i++) {
            TripInvoiceNumber n = new TripInvoiceNumber();
            n.setTripId(trip.getId());
            n.setDeviceId(deviceId);
            n.setNumber(numbers.next(DocumentType.MOBILE_INVOICE));
            n.setIssuedAt(now);
            numberRepo.save(n);
            unused.add(n.getNumber());
        }
        Company company = new Company(settings.get(SettingKey.COMPANY_NAME), settings.get(SettingKey.COMPANY_TIN),
                settings.get(SettingKey.COMPANY_ADDRESS), settings.get(SettingKey.COMPANY_PHONE));
        return new Bundle(trip, units, priceListSnapshotRepo.findByTripId(trip.getId()), priceSnapshotRepo.findByTripId(trip.getId()),
                customerRepo.findByEnabledTrueOrderByNameAsc(), unused, discountLimit(user.getId()), pricing.baseDecimals(), company,
                EbmMode.valueOf(settings.get(SettingKey.EBM_MODE)), now);
    }

    /** The units on the trip's vehicle, by code. */
    public List<StockUnit> unitsOnBoard(Trip trip) {
        return unitRepo.findByLocation_IdAndStatusOrderByCodeAsc(trip.getVehicle().getLocation().getId(), StockStatus.ON_VEHICLE);
    }

    /** The trip's frozen prices as the sale check reads them. */
    public MobileSales.Snapshot snapshot(UUID tripId) {
        List<TripPriceList> lists = priceListSnapshotRepo.findByTripId(tripId);
        UUID defaultList = lists.stream().filter(TripPriceList::isDefaultList).map(TripPriceList::getPriceListId).findFirst().orElse(null);
        Map<UUID, MobileSales.ListTerms> terms = new HashMap<>();
        lists.forEach(l -> terms.put(l.getPriceListId(), new MobileSales.ListTerms(l.getPriceListId(), l.isPricesIncludeVat(), l.getMinChargeableM2())));
        Map<UUID, Map<UUID, BigDecimal>> prices = new HashMap<>();
        priceSnapshotRepo.findByTripId(tripId).forEach(p ->
                prices.computeIfAbsent(p.getPriceListId(), k -> new HashMap<>()).put(p.getProductId(), p.getPricePerM2()));
        return new MobileSales.Snapshot(defaultList, terms, prices);
    }

    /** The trip's invoice numbers, given and used, for its page. */
    public List<TripInvoiceNumber> numbersOf(UUID tripId) {
        return numberRepo.findByTripIdOrderByNumber(tripId);
    }

    /** The largest discount the driver may give, % (POS-06 rules: their roles' limits, else Settings). */
    public BigDecimal discountLimit(Long userId) {
        List<BigDecimal> limits = userRepo.findById(userId)
                .map(u -> u.getRoles().stream().filter(Role::isEnabled).map(Role::getDiscountLimitPercent).toList())
                .orElse(List.of());
        return Discounts.limitOf(limits, settings.getDecimal(SettingKey.DISCOUNT_APPROVAL_PERCENT));
    }

    /** Freezes the default list and every list a customer has, for the glass on board (MPOS-04). */
    private void freezePrices(Trip trip, List<StockUnit> units, LocalDateTime now) {
        PriceList defaultList = priceListService.defaultList();
        Map<UUID, PriceList> lists = new LinkedHashMap<>();
        lists.put(defaultList.getId(), defaultList);
        for (Customer c : customerRepo.findByEnabledTrueOrderByNameAsc()) {
            if (c.getPriceList() != null && c.getPriceList().isEnabled()) {
                lists.putIfAbsent(c.getPriceList().getId(), c.getPriceList());
            }
        }
        Set<UUID> products = units.stream().map(u -> u.getProduct().getId()).collect(Collectors.toSet());
        for (PriceList list : lists.values()) {
            TripPriceList snapshot = new TripPriceList();
            snapshot.setTripId(trip.getId());
            snapshot.setPriceListId(list.getId());
            snapshot.setCode(list.getCode());
            snapshot.setName(list.getName());
            snapshot.setDefaultList(list.getId().equals(defaultList.getId()));
            snapshot.setPricesIncludeVat(list.isPricesIncludeVat());
            snapshot.setMinChargeableM2(priceListService.minChargeableArea(list));
            snapshot.setTakenAt(now);
            priceListSnapshotRepo.save(snapshot);
            for (UUID productId : products) {
                itemRepo.findByPriceList_IdAndProduct_Id(list.getId(), productId)
                        .filter(item -> item.getPricePerM2() != null)
                        .ifPresent(item -> {
                            TripPrice price = new TripPrice();
                            price.setTripId(trip.getId());
                            price.setPriceListId(list.getId());
                            price.setProductId(productId);
                            price.setPricePerM2(item.getPricePerM2());
                            priceSnapshotRepo.save(price);
                        });
            }
        }
    }
}
