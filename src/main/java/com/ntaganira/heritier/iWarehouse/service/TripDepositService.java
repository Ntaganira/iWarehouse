package com.ntaganira.heritier.iWarehouse.service;

import com.ntaganira.heritier.iWarehouse.entity.Trip;
import com.ntaganira.heritier.iWarehouse.entity.TripDeposit;
import com.ntaganira.heritier.iWarehouse.enums.DocumentType;
import com.ntaganira.heritier.iWarehouse.enums.PaymentMethod;
import com.ntaganira.heritier.iWarehouse.exception.BusinessException;
import com.ntaganira.heritier.iWarehouse.exception.NotFoundException;
import com.ntaganira.heritier.iWarehouse.repository.FloatClearanceRepository;
import com.ntaganira.heritier.iWarehouse.repository.TripDepositRepository;
import com.ntaganira.heritier.iWarehouse.repository.TripRepository;
import com.ntaganira.heritier.iWarehouse.security.AppUserPrincipal;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;

/**
 * <pre>
 * - Project   : iWarehouse - Glass Warehouse &amp; Mobile POS (ERP-Lite)
 * - Package   : com.ntaganira.heritier.iWarehouse.service
 * - File      : TripDepositService.java
 * - Date      : 2026. 10. 15.
 * - User      : NTAGANIRA Heritier
 * - Email     : ntaganira71@gmail.com
 * - Phone     : +250 788 533 669
 * - Desc      : Collections banked or sent by mobile money during a trip, not on its return (BP 7.3 rule 4, F-10 C,
 *               SDP 10; N.07): recorded from the driver's phone (its clientId: sent twice, taken once) or at the office,
 *               on a trip on the road or back and not cleared, with the slip's or the transfer's reference, dated from the
 *               departure to today, at most what is left on the float. Posted when recorded (Dr Bank or Mobile Money /
 *               Cr Driver Float naming the driver), so the float to clear is the cash sales less the costs paid from them
 *               less what was banked.
 * </pre>
 */
@Service
@Transactional(readOnly = true)
public class TripDepositService {

    private final TripDepositRepository repo;
    private final TripRepository tripRepo;
    private final FloatClearanceRepository clearanceRepo;
    private final FloatService floatService;
    private final PostingService postingService;
    private final DocumentNumberService numbers;
    private final Clock clock;

    public TripDepositService(TripDepositRepository repo, TripRepository tripRepo, FloatClearanceRepository clearanceRepo,
                              FloatService floatService, PostingService postingService, DocumentNumberService numbers, Clock clock) {
        this.repo = repo;
        this.tripRepo = tripRepo;
        this.clearanceRepo = clearanceRepo;
        this.floatService = floatService;
        this.postingService = postingService;
        this.numbers = numbers;
        this.clock = clock;
    }

    /** What a deposit says: bank or mobile money, the amount, the slip's or the transfer's reference, the day (none: today). */
    public record Form(PaymentMethod method, BigDecimal amount, String reference, LocalDate depositedOn) {
    }

    public List<TripDeposit> ofTrip(UUID tripId) {
        return repo.findByTripIdOrderByDepositedOnAscCreatedAtAsc(tripId);
    }

    public Optional<TripDeposit> byClientId(UUID clientId) {
        return clientId == null ? Optional.empty() : repo.findByClientId(clientId);
    }

    public LocalDate today() {
        return LocalDate.now(clock);
    }

    /**
     * Records a deposit on a trip (via PHONE or OFFICE). The same clientId sent again gets the deposit already taken. The
     * trip is locked first, so the float is read and spent one deposit at a time.
     */
    @Transactional
    public TripDeposit record(UUID tripId, Form form, UUID clientId, String via) {
        Optional<TripDeposit> already = byClientId(clientId);
        if (already.isPresent()) {
            return already.get();
        }
        tripRepo.lockById(tripId).orElseThrow(() -> new NotFoundException("Trip", tripId));
        Trip trip = tripRepo.findDetailedById(tripId).orElseThrow(() -> new NotFoundException("Trip", tripId));
        if (!trip.isDeparted() && !trip.isReturned()) {
            throw BusinessException.of("tripDeposit.notAway", trip.getNumber());
        }
        if (clearanceRepo.existsByTrip_Id(tripId)) {
            throw BusinessException.of("tripDeposit.cleared", trip.getNumber());
        }
        if (form.method() != PaymentMethod.BANK_TRANSFER && form.method() != PaymentMethod.MOBILE_MONEY) {
            throw BusinessException.onField("method", "tripDeposit.method");
        }
        if (form.amount() == null || form.amount().signum() <= 0 || form.amount().stripTrailingZeros().scale() > 2) {
            throw BusinessException.onField("amount", "tripDeposit.amount");
        }
        String reference = PartyRules.clean(form.reference());
        if (reference == null || reference.length() > 60) {
            throw BusinessException.onField("reference", "tripDeposit.reference");
        }
        LocalDate today = today();
        LocalDate day = form.depositedOn() == null ? today : form.depositedOn();
        if (day.isAfter(today) || (trip.getDepartedAt() != null && day.isBefore(trip.getDepartedAt().toLocalDate()))) {
            throw BusinessException.onField("depositedOn", "tripDeposit.date", trip.getNumber());
        }
        BigDecimal left = floatService.expectedOf(tripId);
        if (form.amount().compareTo(left) > 0) {
            throw BusinessException.onField("amount", "tripDeposit.tooMuch", form.amount().toPlainString(), left.toPlainString(), trip.getNumber());
        }
        Optional<AppUserPrincipal> user = AppUserPrincipal.current();
        TripDeposit d = new TripDeposit();
        d.setNumber(numbers.next(DocumentType.TRIP_DEPOSIT));
        d.setClientId(clientId);
        d.setTripId(tripId);
        d.setDriverId(trip.getDriver().getId());
        d.setMethod(form.method());
        d.setAmount(form.amount().setScale(2));
        d.setReference(reference.toUpperCase(Locale.ROOT));
        d.setDepositedOn(day);
        d.setRecordedVia(via);
        d.setCreatedAt(LocalDateTime.now(clock));
        d.setUserId(user.map(AppUserPrincipal::getId).orElse(null));
        d.setUsername(user.map(AppUserPrincipal::getUsername).orElse("system"));
        repo.save(d);
        postingService.tripDeposit(d, trip);
        return d;
    }
}
