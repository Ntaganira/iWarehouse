package com.ntaganira.heritier.iWarehouse.service;

import com.ntaganira.heritier.iWarehouse.entity.*;
import com.ntaganira.heritier.iWarehouse.enums.AttachmentOwner;
import com.ntaganira.heritier.iWarehouse.enums.BreakageStatus;
import com.ntaganira.heritier.iWarehouse.enums.NotificationKind;
import com.ntaganira.heritier.iWarehouse.enums.StockStatus;
import com.ntaganira.heritier.iWarehouse.exception.BusinessException;
import com.ntaganira.heritier.iWarehouse.exception.NotFoundException;
import com.ntaganira.heritier.iWarehouse.repository.*;
import com.ntaganira.heritier.iWarehouse.security.AppUserPrincipal;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.time.Clock;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * <pre>
 * - Project   : iWarehouse - Glass Warehouse &amp; Mobile POS (ERP-Lite)
 * - Package   : com.ntaganira.heritier.iWarehouse.service
 * - File      : BreakageService.java
 * - Date      : 2026. 10. 11.
 * - User      : NTAGANIRA Heritier
 * - Email     : ntaganira71@gmail.com
 * - Phone     : +250 788 533 669
 * - Desc      : Breakage on the road (FLT-08, MPOS-06): a unit on the vehicle of a trip on the road, reported with a photo
 *               (kept among the trip's documents), the reason and the place, by the driver on the phone (a report sent
 *               again with the same id is kept once) or at the warehouse; the supervisors are told. Before the trip is
 *               reconciled the supervisor accepts it (the unit leaves stock as broken then) or rejects it with a note (the
 *               unit is expected back), never the person who reported it.
 * </pre>
 */
@Service
@Transactional(readOnly = true)
public class BreakageService {

    private static final int TEXT_MAX = 255;

    private final TripRepository tripRepo;
    private final TripBreakageRepository repo;
    private final StockUnitRepository unitRepo;
    private final DriverRepository driverRepo;
    private final ApiDeviceRepository deviceRepo;
    private final AttachmentService attachments;
    private final Notifier notifier;
    private final Clock clock;

    public BreakageService(TripRepository tripRepo, TripBreakageRepository repo, StockUnitRepository unitRepo, DriverRepository driverRepo,
                           ApiDeviceRepository deviceRepo, AttachmentService attachments, Notifier notifier, Clock clock) {
        this.tripRepo = tripRepo;
        this.repo = repo;
        this.unitRepo = unitRepo;
        this.driverRepo = driverRepo;
        this.deviceRepo = deviceRepo;
        this.attachments = attachments;
        this.notifier = notifier;
        this.clock = clock;
    }

    /** What a phone sent: its own id for the report, the trip and unit, why and where, when it happened (the phone's clock). */
    public record Report(UUID clientId, UUID tripId, UUID unitId, String reason, String place, OffsetDateTime happenedAt) {
    }

    public enum Outcome {
        /** Recorded now. */
        RECORDED,
        /** Recorded before: the same report sent again, or the unit already reported on the trip. */
        ALREADY
    }

    public record Result(Outcome outcome, TripBreakage breakage) {
    }

    /** A trip's reports, in the order they happened. */
    public List<TripBreakage> ofTrip(UUID tripId) {
        return repo.findByTrip_IdOrderByHappenedAtAscUnitCodeAsc(tripId);
    }

    public Optional<TripBreakage> findByClientId(UUID clientId) {
        return repo.findByClientId(clientId);
    }

    /** From the driver's phone (MPOS-06): a unit on the vehicle of their own trip on the road. */
    @Transactional
    public Result reportFromPhone(AppUserPrincipal user, UUID deviceId, Report report, String photoName, byte[] photo) {
        if (report.clientId() == null || report.tripId() == null || report.unitId() == null) {
            throw BusinessException.of("mobile.badRequest");
        }
        Optional<TripBreakage> sent = repo.findByClientId(report.clientId());
        if (sent.isPresent()) {
            return new Result(Outcome.ALREADY, sent.get());
        }
        Driver driver = driverRepo.findByUser_Id(user.getId()).orElseThrow(() -> BusinessException.of("mobile.trip.notDriver"));
        Trip trip = lock(report.tripId());
        if (!trip.getDriver().getId().equals(driver.getId())) {
            throw BusinessException.of("breakage.notYourTrip", trip.getNumber());
        }
        ApiDevice device = deviceRepo.findById(deviceId).orElseThrow(() -> new NotFoundException("ApiDevice", deviceId));
        return record(trip, report.unitId(), report.clientId(), device, report.reason(), report.place(), report.happenedAt(),
                user.getUsername(), photoName, photo, true);
    }

    /** At the warehouse (FLT-08): breakage found on a vehicle back from the road, recorded before the trip is reconciled. */
    @Transactional
    public TripBreakage reportAtWarehouse(UUID tripId, UUID unitId, String reason, String place, String photoName, byte[] photo) {
        Trip trip = lock(tripId);
        return record(trip, unitId, null, null, reason, place, null, AppUserPrincipal.currentUsername(), photoName, photo, false).breakage();
    }

    private Result record(Trip trip, UUID unitId, UUID clientId, ApiDevice device, String reason, String place, OffsetDateTime happenedAt,
                          String reporter, String photoName, byte[] photo, boolean fromPhone) {
        if (!trip.isDeparted()) {
            throw BusinessException.of("breakage.trip.notOnRoad", trip.getNumber());
        }
        TripLine line = trip.getLines().stream().filter(l -> l.getStockUnitId().equals(unitId)).findFirst()
                .orElseThrow(() -> BusinessException.onField("unitId", "breakage.unit.notOnTrip", trip.getNumber()));
        StockUnit unit = unitRepo.findByIdIn(List.of(unitId)).stream().findFirst().orElseThrow(() -> new NotFoundException("StockUnit", unitId));
        UUID vehicleLocation = trip.getVehicle().getLocation().getId();
        if (unit.getStatus() != StockStatus.ON_VEHICLE || unit.getLocation() == null || !unit.getLocation().getId().equals(vehicleLocation)) {
            throw BusinessException.onField("unitId", "breakage.unit.notOnVehicle", line.getUnitCode(), trip.getVehicle().getPlate());
        }
        Optional<TripBreakage> standing = repo.findStanding(trip.getId(), unitId);
        if (standing.isPresent()) {
            if (fromPhone) {
                return new Result(Outcome.ALREADY, standing.get());
            }
            throw BusinessException.onField("unitId", "breakage.already", line.getUnitCode());
        }
        String why = text(reason, "reason", "breakage.reason.required");
        String where = text(place, "place", "breakage.place.required");
        if (photo == null || photo.length == 0) {
            throw BusinessException.onField("file", "breakage.photo.required");
        }
        Attachment picture = attachments.add(AttachmentOwner.TRIP, trip.getId(), StringUtils.hasText(photoName) ? photoName : line.getUnitCode() + ".jpg",
                photo, line.getUnitCode() + ": " + why);

        LocalDateTime now = LocalDateTime.now(clock);
        LocalDateTime when = happenedAt == null ? now : happenedAt.atZoneSameInstant(clock.getZone()).toLocalDateTime();
        if (when.isAfter(now) || (trip.getDepartedAt() != null && when.isBefore(trip.getDepartedAt()))) {
            when = now;                                             // a phone clock off: when the server heard of it
        }
        TripBreakage b = new TripBreakage();
        b.setTrip(trip);
        b.setStockUnitId(unitId);
        b.setUnitCode(line.getUnitCode());
        b.setClientId(clientId);
        b.setDevice(device);
        b.setReason(why);
        b.setPlace(where);
        b.setHappenedAt(when);
        b.setReportedBy(reporter);
        b.setPhotoId(picture.getId());
        repo.save(b);
        notifier.holders("VALIDATE_BREAKAGE", AppUserPrincipal.current().map(AppUserPrincipal::getId).orElse(null), NotificationKind.EOD,
                "notify.eod.breakage.title", "notify.eod.breakage.message", "/trips/" + trip.getId() + "?tab=return",
                line.getUnitCode(), trip.getNumber(), trip.getDriver().getUser().getFullName(), why);
        return new Result(Outcome.RECORDED, b);
    }

    /** The supervisor's decision at the end of the day (FLT-08): accepted, or rejected with a note; never their own report. */
    @Transactional
    public TripBreakage decide(UUID tripId, UUID breakageId, boolean accept, String note) {
        tripRepo.lockById(tripId).orElseThrow(() -> new NotFoundException("Trip", tripId));
        TripBreakage b = repo.lockById(breakageId).filter(x -> x.getTrip().getId().equals(tripId))
                .orElseThrow(() -> new NotFoundException("TripBreakage", breakageId));
        if (!b.getTrip().isDeparted()) {
            throw TripService.notState(b.getTrip());
        }
        if (!b.isReported()) {
            throw BusinessException.of("breakage.decided", b.getUnitCode());
        }
        String user = AppUserPrincipal.currentUsername();
        if (user.equals(b.getReportedBy())) {
            throw BusinessException.of("breakage.ownReport", b.getUnitCode());
        }
        String why = StringUtils.hasText(note) ? note.trim() : null;
        if (!accept && why == null) {
            throw BusinessException.onField("reason", "breakage.note.required");
        }
        if (why != null && why.length() > TEXT_MAX) {
            throw BusinessException.onField("reason", "breakage.text.tooLong", TEXT_MAX);
        }
        b.setDecisionNote(why);
        b.setDecidedBy(user);
        b.setDecidedAt(LocalDateTime.now(clock));
        b.setStatus(accept ? BreakageStatus.ACCEPTED : BreakageStatus.REJECTED);
        return b;
    }

    private static String text(String value, String field, String requiredKey) {
        if (!StringUtils.hasText(value)) {
            throw BusinessException.onField(field, requiredKey);
        }
        String v = value.trim();
        if (v.length() > TEXT_MAX) {
            throw BusinessException.onField(field, "breakage.text.tooLong", TEXT_MAX);
        }
        return v;
    }

    private Trip lock(UUID id) {
        tripRepo.lockById(id).orElseThrow(() -> new NotFoundException("Trip", id));
        return tripRepo.findDetailedById(id).orElseThrow(() -> new NotFoundException("Trip", id));
    }
}
