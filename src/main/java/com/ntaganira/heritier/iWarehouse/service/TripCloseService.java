package com.ntaganira.heritier.iWarehouse.service;

import com.ntaganira.heritier.iWarehouse.enums.SettingKey;
import com.ntaganira.heritier.iWarehouse.enums.NotificationKind;
import com.ntaganira.heritier.iWarehouse.entity.AuditCase;
import com.ntaganira.heritier.iWarehouse.entity.FloatClearance;
import com.ntaganira.heritier.iWarehouse.entity.Trip;
import com.ntaganira.heritier.iWarehouse.enums.TripStatus;
import com.ntaganira.heritier.iWarehouse.exception.BusinessException;
import com.ntaganira.heritier.iWarehouse.exception.NotFoundException;
import com.ntaganira.heritier.iWarehouse.repository.AuditCaseRepository;
import com.ntaganira.heritier.iWarehouse.repository.TripRepository;
import com.ntaganira.heritier.iWarehouse.security.AppUserPrincipal;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDateTime;
import java.util.UUID;

/**
 * <pre>
 * - Project   : iWarehouse - Glass Warehouse &amp; Mobile POS (ERP-Lite)
 * - Package   : com.ntaganira.heritier.iWarehouse.service
 * - File      : TripCloseService.java
 * - Date      : 2026. 10. 11.
 * - User      : NTAGANIRA Heritier
 * - Email     : ntaganira71@gmail.com
 * - Phone     : +250 788 533 669
 * - Desc      : The end of a trip (MPOS-07, FLT-10, SRS 5.1). The driver declares the cash they hold, on the phone or at
 *               the warehouse (once, on the road or back). Stock and cash are settled separately: the trip closes once it is
 *               reconciled, its audit case (if any) resolved or escalated, its cash declared and, when its sales took cash,
 *               its float cleared by the accountant.
 * </pre>
 */
@Service
@Transactional(readOnly = true)
public class TripCloseService {

    public static final String VIA_PHONE = "PHONE";
    public static final String VIA_OFFICE = "OFFICE";

    private final TripRepository tripRepo;
    private final AuditCaseRepository caseRepo;
    private final FloatService floatService;
    private final LinePricing pricing;
    private final Clock clock;
    private final SettingService settings;
    private final Notifier notifier;

    public TripCloseService(TripRepository tripRepo, AuditCaseRepository caseRepo, FloatService floatService, LinePricing pricing, Clock clock,
                            SettingService settings, Notifier notifier) {
        this.settings = settings;
        this.notifier = notifier;
        this.tripRepo = tripRepo;
        this.caseRepo = caseRepo;
        this.floatService = floatService;
        this.pricing = pricing;
        this.clock = clock;
    }

    /** Where a trip stands on each condition of its closing. */
    public record Settlement(boolean back, AuditCase auditCase, boolean declared, BigDecimal cash, FloatClearance clearance) {

        public boolean caseSettled() {
            return auditCase == null || auditCase.isSettledForTrip();
        }

        /** Its sales took cash: the accountant clears it. */
        public boolean floatNeeded() {
            return cash.signum() > 0;
        }

        public boolean floatCleared() {
            return !floatNeeded() || clearance != null;
        }

        public boolean canClose() {
            return back && caseSettled() && declared && floatCleared();
        }
    }

    public Settlement settlement(Trip trip) {
        boolean back = trip.getStatus() == TripStatus.RETURNED || trip.getStatus() == TripStatus.CLOSED;
        return new Settlement(back, caseRepo.findByTrip_Id(trip.getId()).orElse(null), trip.isCashDeclared(), floatService.cashOf(trip.getId()),
                floatService.ofTrip(trip.getId()).orElse(null));
    }

    /**
     * The cash the driver holds (MPOS-07), declared once while the trip is on the road or back: by the driver on their phone,
     * or recorded at the warehouse as they declare it.
     */
    @Transactional
    public Trip declareCash(UUID tripId, BigDecimal amount, String via) {
        Trip trip = lock(tripId);
        if (!trip.isOpenForPhone()) {
            throw TripService.notState(trip);
        }
        if (VIA_PHONE.equals(via)) {
            Long me = AppUserPrincipal.current().map(AppUserPrincipal::getId).orElse(null);
            if (me == null || !me.equals(trip.getDriver().getUser().getId())) {
                throw BusinessException.of("trip.cash.notYourTrip", trip.getNumber());
            }
        }
        if (trip.isCashDeclared()) {
            throw BusinessException.of("trip.cash.declared", trip.getNumber());
        }
        if (amount == null || amount.signum() < 0) {
            throw BusinessException.onField("amount", "trip.cash.amount.invalid");
        }
        int decimals = pricing.baseDecimals();
        if (amount.stripTrailingZeros().scale() > decimals) {
            throw BusinessException.onField("amount", "trip.cash.decimals", decimals);
        }
        trip.setCashDeclaredVia(VIA_PHONE.equals(via) ? VIA_PHONE : VIA_OFFICE);
        trip.setCashDeclaredBy(AppUserPrincipal.currentUsername());
        trip.setCashDeclaredAt(LocalDateTime.now(clock));
        trip.setCashDeclared(amount);
        return trip;
    }

    /** Closes a trip whose stock and cash are settled (SRS 5.1); the refusal says what is left. */
    @Transactional
    public Trip close(UUID tripId) {
        Trip trip = lock(tripId);
        if (countersignRequired()) {
            throw BusinessException.of("trip.close.byClearance", trip.getNumber());
        }
        requireSettled(trip);
        trip.setClosedBy(AppUserPrincipal.currentUsername());
        trip.setClosedAt(LocalDateTime.now(clock));
        trip.setStatus(TripStatus.CLOSED);
        return trip;
    }

    /** The clearance is countersigned by the Director of Finance, which closes the trip (F-11). */
    public boolean countersignRequired() {
        return settings.getBoolean(SettingKey.FLEET_CLEARANCE_COUNTERSIGN);
    }

    /**
     * Clears a returned trip (F-11; the Director of Commercial): everything its closing needs settled. The Director of
     * Finance's countersignature then closes it (another person).
     */
    @Transactional
    public Trip clear(UUID tripId) {
        Trip trip = lock(tripId);
        if (trip.isCleared()) {
            throw BusinessException.of("trip.clearance.cleared", trip.getNumber(), trip.getClearedBy());
        }
        requireSettled(trip);
        AppUserPrincipal user = AppUserPrincipal.current().orElseThrow(() -> BusinessException.of("till.noUser"));
        trip.setClearedBy(user.getUsername());
        trip.setClearedById(user.getId());
        trip.setClearedAt(LocalDateTime.now(clock));
        notifier.holders("COUNTERSIGN_TRIP_CLEARANCE", user.getId(), NotificationKind.EOD, "notify.trip.cleared", "notify.trip.clearedText",
                "/trips/" + trip.getId(), trip.getNumber(), user.getUsername());
        return trip;
    }

    /** Countersigns a cleared trip's clearance, which closes it: never by who cleared it. */
    @Transactional
    public Trip countersign(UUID tripId) {
        Trip trip = lock(tripId);
        if (!trip.isCleared()) {
            throw BusinessException.of("trip.clearance.notCleared", trip.getNumber());
        }
        AppUserPrincipal user = AppUserPrincipal.current().orElseThrow(() -> BusinessException.of("till.noUser"));
        if (user.getId().equals(trip.getClearedById())) {
            throw BusinessException.of("trip.clearance.own");
        }
        requireSettled(trip);
        trip.setClosedBy(user.getUsername());
        trip.setClosedAt(LocalDateTime.now(clock));
        trip.setStatus(TripStatus.CLOSED);
        return trip;
    }

    private void requireSettled(Trip trip) {
        if (!trip.isReturned()) {
            throw TripService.notState(trip);
        }
        Settlement s = settlement(trip);
        if (!s.caseSettled()) {
            throw BusinessException.of("trip.close.caseOpen", trip.getNumber(), s.auditCase().getNumber());
        }
        if (!s.declared()) {
            throw BusinessException.of("trip.close.notDeclared", trip.getNumber(), trip.getDriver().getUser().getFullName());
        }
        if (!s.floatCleared()) {
            throw BusinessException.of("trip.close.floatNotCleared", trip.getNumber());
        }
    }

    private Trip lock(UUID id) {
        tripRepo.lockById(id).orElseThrow(() -> new NotFoundException("Trip", id));
        return tripRepo.findDetailedById(id).orElseThrow(() -> new NotFoundException("Trip", id));
    }
}
