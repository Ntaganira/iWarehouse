package com.ntaganira.heritier.iWarehouse.controller;

import com.ntaganira.heritier.iWarehouse.audit.AuditContext;
import com.ntaganira.heritier.iWarehouse.config.Messages;
import com.ntaganira.heritier.iWarehouse.config.NumberFormats;
import com.ntaganira.heritier.iWarehouse.config.Paging;
import com.ntaganira.heritier.iWarehouse.dto.TripDto;
import com.ntaganira.heritier.iWarehouse.dto.TripFuelDto;
import com.ntaganira.heritier.iWarehouse.entity.Driver;
import com.ntaganira.heritier.iWarehouse.entity.StockUnit;
import com.ntaganira.heritier.iWarehouse.service.SyncConflictService;
import com.ntaganira.heritier.iWarehouse.service.MobileTripService;
import com.ntaganira.heritier.iWarehouse.service.MobileSaleService;
import com.ntaganira.heritier.iWarehouse.entity.TripInvoiceNumber;
import com.ntaganira.heritier.iWarehouse.entity.SalesInvoice;
import com.ntaganira.heritier.iWarehouse.entity.Trip;
import com.ntaganira.heritier.iWarehouse.entity.TripFuel;
import com.ntaganira.heritier.iWarehouse.entity.TripLine;
import com.ntaganira.heritier.iWarehouse.entity.Vehicle;
import com.ntaganira.heritier.iWarehouse.enums.ActivityStatus;
import com.ntaganira.heritier.iWarehouse.enums.TripStatus;
import com.ntaganira.heritier.iWarehouse.exception.BusinessException;
import com.ntaganira.heritier.iWarehouse.security.AppUserPrincipal;
import com.ntaganira.heritier.iWarehouse.service.ActivityLogService;
import com.ntaganira.heritier.iWarehouse.service.DataChangeService;
import com.ntaganira.heritier.iWarehouse.service.DriverService;
import com.ntaganira.heritier.iWarehouse.service.FleetPapers;
import com.ntaganira.heritier.iWarehouse.service.StockService;
import com.ntaganira.heritier.iWarehouse.service.TripLoading;
import com.ntaganira.heritier.iWarehouse.service.TripService;
import com.ntaganira.heritier.iWarehouse.service.VehicleService;
import jakarta.validation.Valid;
import org.springframework.data.domain.Page;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.util.StringUtils;
import org.springframework.validation.BindingResult;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDate;
import java.util.*;
import java.util.stream.Collectors;

/**
 * <pre>
 * - Project   : iWarehouse - Glass Warehouse &amp; Mobile POS (ERP-Lite)
 * - Package   : com.ntaganira.heritier.iWarehouse.controller
 * - File      : TripController.java
 * - Date      : 2026. 10. 10.
 * - User      : Hntaganira
 * - Desc      : Moving-shop trips (FLT-04..07, FLT-12): list, plan and edit (PERM_PLAN_TRIP), build the manifest by scanning
 *               or typing labels (a rack label adds its available units), scan-load (PERM_LOAD_TRIP), confirm the departure
 *               (PERM_DEPART_TRIP: AT-03, a load over the vehicle's limits is refused), cancel with a reason, record the
 *               odometer and fuel (PERM_RECORD_TRIP_READINGS). PAGE_FLEET; without PERM_VIEW_TRIP a driver sees and loads
 *               only their own trips.
 * </pre>
 */
@Controller
@RequestMapping("/trips")
public class TripController {

    static final String MODULE = "Fleet";
    private static final int REASON_MAX = 255;

    private final TripService tripService;
    private final VehicleService vehicleService;
    private final DriverService driverService;
    private final StockService stockService;
    private final MobileSaleService mobileSaleService;
    private final MobileTripService mobileTripService;
    private final SyncConflictService syncConflictService;
    private final DataChangeService dataChangeService;
    private final ActivityLogService activityLogService;
    private final NumberFormats num;
    private final Messages messages;
    private final Clock clock;

    public TripController(TripService tripService, VehicleService vehicleService, DriverService driverService, StockService stockService,
                          MobileSaleService mobileSaleService, MobileTripService mobileTripService, SyncConflictService syncConflictService,
                          DataChangeService dataChangeService, ActivityLogService activityLogService, NumberFormats num, Messages messages,
                          Clock clock) {
        this.tripService = tripService;
        this.vehicleService = vehicleService;
        this.driverService = driverService;
        this.stockService = stockService;
        this.mobileSaleService = mobileSaleService;
        this.mobileTripService = mobileTripService;
        this.syncConflictService = syncConflictService;
        this.dataChangeService = dataChangeService;
        this.activityLogService = activityLogService;
        this.num = num;
        this.messages = messages;
        this.clock = clock;
    }

    /** A manifest line with its unit (none if the unit cannot be read). */
    public record Row(TripLine line, StockUnit unit) {
    }

    // ---------------------------------------------------------------- list and view

    @GetMapping
    @PreAuthorize("hasAuthority('PAGE_FLEET')")
    public String list(@RequestParam(required = false) String search, @RequestParam(required = false) TripStatus status,
                       @RequestParam(required = false) UUID vehicle, @RequestParam(defaultValue = "0") int page, Model model) {
        boolean all = AppUserPrincipal.currentHas("PERM_VIEW_TRIP");
        Long me = all ? null : AppUserPrincipal.current().map(AppUserPrincipal::getId).orElse(-1L);
        Page<Trip> trips = tripService.findPage(new TripService.Filter(search, status, vehicle, me), Paging.page(page), Paging.SIZE);
        model.addAttribute("trips", trips);
        model.addAttribute("counts", tripService.counts(trips.getContent()));
        model.addAttribute("allTrips", all);
        model.addAttribute("statuses", TripStatus.values());
        model.addAttribute("vehicles", all ? vehicleService.active() : List.of());
        model.addAttribute("planned", tripService.countByStatus(TripStatus.PLANNED));
        model.addAttribute("onTheRoad", tripService.countByStatus(TripStatus.DEPARTED));
        model.addAttribute("search", search);
        model.addAttribute("status", status);
        model.addAttribute("vehicle", vehicle);
        model.addAttribute("paginationQuery", QueryString.of("search", search, "status", status, "vehicle", vehicle));
        return "trips/list";
    }

    @GetMapping("/{id}")
    @PreAuthorize("hasAuthority('PAGE_FLEET')")
    public String view(@PathVariable UUID id, @RequestParam(required = false) String tab, @RequestParam(defaultValue = "0") int page,
                       Model model) {
        Trip trip = visible(id);
        String open = tab != null && List.of("manifest", "sales", "fuel", "history").contains(tab) ? tab : "manifest";
        Map<UUID, StockUnit> units = tripService.units(trip);
        List<Row> rows = TripService.sortedLines(trip).stream().map(l -> new Row(l, units.get(l.getStockUnitId()))).toList();
        List<TripFuel> fuel = trip.getFuel().stream()
                .sorted(Comparator.comparing(TripFuel::getFilledOn).reversed().thenComparing(TripFuel::getCreatedAt, Comparator.reverseOrder()))
                .toList();
        LocalDate today = LocalDate.now(clock);
        int days = vehicleService.alertDays();
        Map<String, FleetPapers.Stage> papers = new LinkedHashMap<>();
        papers.put("LICENCE", FleetPapers.stage(trip.getDriver().getLicenceExpiry(), today, days));
        papers.put("INSURANCE", FleetPapers.stage(trip.getVehicle().getInsuranceExpiry(), today, days));
        papers.put("INSPECTION", FleetPapers.stage(trip.getVehicle().getInspectionExpiry(), today, days));
        model.addAttribute("trip", trip);
        model.addAttribute("rows", Paging.of(rows, Paging.pageOf("manifest", open, page)));
        model.addAttribute("planned", TripService.check(trip, units, false));
        model.addAttribute("loaded", TripService.check(trip, units, true));
        model.addAttribute("loadedCount", trip.getLines().stream().filter(TripLine::isLoaded).count());
        model.addAttribute("fuel", Paging.of(fuel, Paging.pageOf("fuel", open, page)));
        model.addAttribute("fuelTotal", TripService.fuelTotal(trip));
        model.addAttribute("litresTotal", TripService.litresTotal(trip));
        model.addAttribute("papers", papers);
        model.addAttribute("locations", stockService.locationsById());
        model.addAttribute("isDriver", isDriver(trip));
        if (!model.containsAttribute("fuelDto")) {
            TripFuelDto fuelDto = new TripFuelDto();
            fuelDto.setFilledOn(today);
            model.addAttribute("fuelDto", fuelDto);
        }
        model.addAttribute("today", today);
        // Sales from the vehicle on the mobile POS (MPOS), the numbers its phones hold, the sales in conflict (SYNC-05)
        List<SalesInvoice> sales = mobileSaleService.salesOf(id);
        List<TripInvoiceNumber> numbers = mobileTripService.numbersOf(id);
        model.addAttribute("sales", Paging.of(sales, Paging.pageOf("sales", open, page)));
        model.addAttribute("salesTotal", sales.stream().map(SalesInvoice::getTotalAmount).reduce(BigDecimal.ZERO, BigDecimal::add));
        model.addAttribute("numbersGiven", numbers.size());
        model.addAttribute("numbersUsed", numbers.stream().filter(TripInvoiceNumber::isUsed).count());
        model.addAttribute("openConflicts", syncConflictService.openOfTrip(id));
        model.addAttribute("history", dataChangeService.historyWithChildren("Trip", id.toString(), List.of("TripLine", "TripFuel"), "trip",
                Paging.pageOf("history", open, page), Paging.SIZE));
        model.addAttribute("tab", open);
        return "trips/view";
    }

    // ---------------------------------------------------------------- planning

    @GetMapping("/new")
    @PreAuthorize("hasAuthority('PAGE_FLEET') and hasAuthority('PERM_PLAN_TRIP')")
    public String createForm(@RequestParam(required = false) UUID vehicle, @RequestParam(required = false) UUID driver, Model model) {
        TripDto dto = new TripDto();
        dto.setVehicleId(vehicle);
        dto.setDriverId(driver);
        dto.setTripDate(LocalDate.now(clock));
        if (vehicle == null && driver != null) {
            driverService.active().stream().filter(d -> d.getId().equals(driver) && d.getDefaultVehicle() != null).findFirst()
                    .ifPresent(d -> dto.setVehicleId(d.getDefaultVehicle().getId()));
        }
        return form(model, dto);
    }

    @PostMapping("/new")
    @PreAuthorize("hasAuthority('PAGE_FLEET') and hasAuthority('PERM_PLAN_TRIP')")
    public String create(@Valid @ModelAttribute("tripDto") TripDto dto, BindingResult result, Model model, RedirectAttributes redirect) {
        if (result.hasErrors()) {
            return invalid(model, dto, result);
        }
        try {
            Trip trip = tripService.create(dto);
            activityLogService.record(MODULE, "CREATE_TRIP", "Planned trip " + trip.getNumber() + ": " + trip.getVehicle().getPlate()
                    + " with " + trip.getDriver().getUsername() + " on " + trip.getTripDate() + " to " + trip.getArea(), ActivityStatus.SUCCESS);
            redirect.addFlashAttribute("flashSuccess", messages.get("trip.created", trip.getNumber()));
            return "redirect:/trips/" + trip.getId();
        } catch (BusinessException e) {
            activityLogService.record(MODULE, "CREATE_TRIP", "Failed to plan a trip: " + messages.get(e.getMessageKey(), e.getArgs()),
                    ActivityStatus.FAILED);
            return rejected(model, dto, result, e);
        }
    }

    @GetMapping("/{id}/edit")
    @PreAuthorize("hasAuthority('PAGE_FLEET') and hasAuthority('PERM_PLAN_TRIP')")
    public String editForm(@PathVariable UUID id, Model model, RedirectAttributes redirect) {
        Trip trip = tripService.findDetailed(id);
        if (!trip.isPlanned()) {
            redirect.addFlashAttribute("flashError", messages.get("trip.notAllowed", trip.getNumber(),
                    messages.get("trip.status." + trip.getStatus())));
            return "redirect:/trips/" + id;
        }
        TripDto dto = new TripDto();
        dto.setId(id);
        dto.setVehicleId(trip.getVehicle().getId());
        dto.setDriverId(trip.getDriver().getId());
        dto.setTripDate(trip.getTripDate());
        dto.setArea(trip.getArea());
        dto.setNote(trip.getNote());
        return form(model, dto);
    }

    @PostMapping("/{id}/edit")
    @PreAuthorize("hasAuthority('PAGE_FLEET') and hasAuthority('PERM_PLAN_TRIP')")
    public String update(@PathVariable UUID id, @Valid @ModelAttribute("tripDto") TripDto dto, BindingResult result, Model model,
                         RedirectAttributes redirect) {
        dto.setId(id);
        if (result.hasErrors()) {
            return invalid(model, dto, result);
        }
        try {
            Trip trip = tripService.update(id, dto);
            activityLogService.record(MODULE, "UPDATE_TRIP", "Updated trip " + trip.getNumber() + ": " + trip.getVehicle().getPlate()
                    + " with " + trip.getDriver().getUsername() + " on " + trip.getTripDate(), ActivityStatus.SUCCESS);
            redirect.addFlashAttribute("flashSuccess", messages.get("trip.updated", trip.getNumber()));
            return "redirect:/trips/" + id;
        } catch (BusinessException e) {
            activityLogService.record(MODULE, "UPDATE_TRIP", "Failed to update trip " + numberOf(id) + ": "
                    + messages.get(e.getMessageKey(), e.getArgs()), ActivityStatus.FAILED);
            return rejected(model, dto, result, e);
        }
    }

    @PostMapping("/{id}/cancel")
    @PreAuthorize("hasAuthority('PAGE_FLEET') and hasAuthority('PERM_PLAN_TRIP')")
    public String cancel(@PathVariable UUID id, @RequestParam(required = false) String reason, RedirectAttributes redirect) {
        if (!StringUtils.hasText(reason) || reason.trim().length() > REASON_MAX) {
            redirect.addFlashAttribute("flashError", messages.get("po.reason.required"));
            return "redirect:/trips/" + id;
        }
        try {
            Trip trip = AuditContext.withReason(reason.trim(), () -> tripService.cancel(id, reason));
            activityLogService.record(MODULE, "CANCEL_TRIP", "Cancelled trip " + trip.getNumber() + ": " + reason.trim()
                    + "; " + trip.getLines().size() + " unit(s) free again", ActivityStatus.SUCCESS);
            redirect.addFlashAttribute("flashSuccess", messages.get("trip.cancelledMsg", trip.getNumber()));
        } catch (BusinessException e) {
            String error = messages.get(e.getMessageKey(), e.getArgs());
            activityLogService.record(MODULE, "CANCEL_TRIP", "Failed to cancel trip " + numberOf(id) + ": " + error, ActivityStatus.FAILED);
            redirect.addFlashAttribute("flashError", error);
        }
        return "redirect:/trips/" + id;
    }

    // ---------------------------------------------------------------- the manifest and loading

    @PostMapping("/{id}/units")
    @PreAuthorize("hasAuthority('PAGE_FLEET') and hasAuthority('PERM_PLAN_TRIP')")
    public String addUnits(@PathVariable UUID id, @RequestParam(required = false) String codes, RedirectAttributes redirect) {
        try {
            TripService.AddResult r = tripService.addUnits(id, codes);
            Trip trip = r.trip();
            if (!r.added().isEmpty()) {
                activityLogService.record(MODULE, "UPDATE_TRIP", "Planned " + describe(r.added()) + " on trip " + trip.getNumber()
                        + " (" + trip.getLines().size() + " on the manifest)", ActivityStatus.SUCCESS);
                redirect.addFlashAttribute("flashSuccess", messages.get("trip.units.added", r.added().size(), trip.getNumber()));
            } else {
                redirect.addFlashAttribute("flashSuccess", messages.get("trip.units.nothingNew", trip.getNumber()));
            }
            List<String> notes = new ArrayList<>();
            if (!r.already().isEmpty()) {
                notes.add(messages.get("trip.units.already", String.join(", ", r.already())));
            }
            if (r.skipped() > 0) {
                notes.add(messages.get("trip.units.skipped", r.skipped()));
            }
            String over = overLimits(r.planned(), trip.getVehicle(), "trip.plan.over");
            if (over != null) {
                notes.add(over);
            }
            if (!notes.isEmpty()) {
                redirect.addFlashAttribute("flashWarning", String.join(" ", notes));
            }
        } catch (BusinessException e) {
            String error = messages.get(e.getMessageKey(), e.getArgs());
            activityLogService.record(MODULE, "UPDATE_TRIP", "Failed to plan units on trip " + numberOf(id) + ": " + error, ActivityStatus.FAILED);
            redirect.addFlashAttribute("flashError", error);
            redirect.addFlashAttribute("codesBack", codes);
        }
        return "redirect:/trips/" + id + "#manifest-add";
    }

    @PostMapping("/{id}/lines/{lineId}/remove")
    @PreAuthorize("hasAuthority('PAGE_FLEET') and hasAuthority('PERM_PLAN_TRIP')")
    public String removeLine(@PathVariable UUID id, @PathVariable UUID lineId, RedirectAttributes redirect) {
        try {
            TripLine line = tripService.removeLine(id, lineId);
            activityLogService.record(MODULE, "UPDATE_TRIP", "Took " + line.getUnitCode() + " off the manifest of trip " + numberOf(id),
                    ActivityStatus.SUCCESS);
            redirect.addFlashAttribute("flashSuccess", messages.get("trip.units.removed", line.getUnitCode()));
        } catch (BusinessException e) {
            String error = messages.get(e.getMessageKey(), e.getArgs());
            activityLogService.record(MODULE, "UPDATE_TRIP", "Failed to take a unit off trip " + numberOf(id) + ": " + error, ActivityStatus.FAILED);
            redirect.addFlashAttribute("flashError", error);
        }
        return "redirect:/trips/" + id;
    }

    /** Scan-load (FLT-06): each planned unit scanned is confirmed on the vehicle; a label not on the manifest is refused. */
    @PostMapping("/{id}/scan")
    @PreAuthorize("hasAuthority('PAGE_FLEET') and hasAuthority('PERM_LOAD_TRIP')")
    public String scan(@PathVariable UUID id, @RequestParam(required = false) String codes, RedirectAttributes redirect) {
        visible(id);
        String back = "redirect:/trips/" + id + "#scan";
        try {
            TripService.ScanResult r = tripService.scan(id, codes);
            Trip trip = r.trip();
            List<TripService.Scanned> refused = r.refused();
            List<TripService.Scanned> loaded = r.loadedNow();
            if (!loaded.isEmpty()) {
                activityLogService.record(MODULE, "LOAD_TRIP", "Scanned " + describe(loaded.stream().map(TripService.Scanned::code).toList())
                        + " onto " + trip.getVehicle().getPlate() + " for trip " + trip.getNumber(), ActivityStatus.SUCCESS);
            }
            if (!refused.isEmpty()) {
                // FLT-06: a unit not on the manifest is refused, and the attempt is logged
                activityLogService.record(MODULE, "LOAD_TRIP", "Refused " + refused.stream().map(TripService.Scanned::code)
                        .collect(Collectors.joining(", ")) + " on trip " + trip.getNumber() + ": not on its manifest", ActivityStatus.FAILED);
                String unknown = refused.stream().filter(s -> !s.known()).map(TripService.Scanned::code).collect(Collectors.joining(", "));
                String elsewhere = refused.stream().filter(TripService.Scanned::known).map(TripService.Scanned::code).collect(Collectors.joining(", "));
                List<String> parts = new ArrayList<>();
                if (!elsewhere.isEmpty()) {
                    parts.add(messages.get("trip.scan.notOnManifest", elsewhere, trip.getNumber()));
                }
                if (!unknown.isEmpty()) {
                    parts.add(messages.get("trip.scan.unknown", unknown));
                }
                redirect.addFlashAttribute("flashError", String.join(" ", parts));
            }
            long done = trip.getLines().stream().filter(TripLine::isLoaded).count();
            if (!loaded.isEmpty()) {
                redirect.addFlashAttribute("flashSuccess", loaded.size() == 1
                        ? messages.get("trip.scan.loaded", loaded.get(0).code(), done, trip.getLines().size())
                        : messages.get("trip.scan.loadedSeveral", loaded.size(), done, trip.getLines().size()));
            } else if (refused.isEmpty()) {
                redirect.addFlashAttribute("flashSuccess", messages.get("trip.scan.again", r.scans().get(0).code(), done, trip.getLines().size()));
            }
            String over = overLimits(r.loaded(), trip.getVehicle(), "trip.load.over");
            if (over != null) {
                redirect.addFlashAttribute("flashWarning", over);
            }
        } catch (BusinessException e) {
            String error = messages.get(e.getMessageKey(), e.getArgs());
            activityLogService.record(MODULE, "LOAD_TRIP", "Failed to scan on trip " + numberOf(id) + ": " + error, ActivityStatus.FAILED);
            redirect.addFlashAttribute("flashError", error);
        }
        return back;
    }

    /** The supervisor approves the loading (FLT-06, FLT-07, AT-03). */
    @PostMapping("/{id}/depart")
    @PreAuthorize("hasAuthority('PAGE_FLEET') and hasAuthority('PERM_DEPART_TRIP')")
    public String depart(@PathVariable UUID id, @RequestParam(required = false) Integer odometerStart, RedirectAttributes redirect) {
        try {
            Trip trip = tripService.depart(id, odometerStart);
            activityLogService.record(MODULE, "APPROVE_TRIP", "Confirmed the departure of trip " + trip.getNumber() + ": "
                    + trip.getLoadedPieces() + " unit(s), " + trip.getLoadedKg().toPlainString() + " kg on " + trip.getVehicle().getPlate()
                    + " in the charge of " + trip.getDriver().getUsername()
                    + (odometerStart == null ? "" : ", odometer " + odometerStart + " km"), ActivityStatus.SUCCESS);
            redirect.addFlashAttribute("flashSuccess", messages.get("trip.departed", trip.getNumber(), trip.getLoadedPieces(),
                    trip.getVehicle().getPlate(), trip.getDriver().getUser().getFullName()));
        } catch (BusinessException e) {
            String error = messages.get(e.getMessageKey(), e.getArgs());
            activityLogService.record(MODULE, "APPROVE_TRIP", "Refused the departure of trip " + numberOf(id) + ": " + error, ActivityStatus.FAILED);
            redirect.addFlashAttribute("flashError", error);
        }
        return "redirect:/trips/" + id;
    }

    // ---------------------------------------------------------------- readings and fuel (FLT-12)

    @PostMapping("/{id}/readings")
    @PreAuthorize("hasAuthority('PAGE_FLEET') and hasAuthority('PERM_RECORD_TRIP_READINGS')")
    public String readings(@PathVariable UUID id, @RequestParam(required = false) Integer odometerStart,
                           @RequestParam(required = false) Integer odometerEnd, RedirectAttributes redirect) {
        visible(id);
        try {
            Trip trip = tripService.recordReadings(id, odometerStart, odometerEnd);
            activityLogService.record(MODULE, "UPDATE_TRIP", "Recorded the odometer of trip " + trip.getNumber() + ": "
                    + trip.getOdometerStart() + (trip.getOdometerEnd() == null ? "" : " to " + trip.getOdometerEnd()) + " km", ActivityStatus.SUCCESS);
            redirect.addFlashAttribute("flashSuccess", messages.get("trip.readings.saved", trip.getNumber()));
        } catch (BusinessException e) {
            String error = messages.get(e.getMessageKey(), e.getArgs());
            activityLogService.record(MODULE, "UPDATE_TRIP", "Failed to record the odometer of trip " + numberOf(id) + ": " + error,
                    ActivityStatus.FAILED);
            redirect.addFlashAttribute("flashError", error);
        }
        return "redirect:/trips/" + id;
    }

    @PostMapping("/{id}/fuel")
    @PreAuthorize("hasAuthority('PAGE_FLEET') and hasAuthority('PERM_RECORD_TRIP_READINGS')")
    public String addFuel(@PathVariable UUID id, @Valid @ModelAttribute("fuelDto") TripFuelDto dto, BindingResult result,
                          RedirectAttributes redirect) {
        visible(id);
        String back = "redirect:/trips/" + id + "?tab=fuel";
        if (result.hasErrors()) {
            redirect.addFlashAttribute("org.springframework.validation.BindingResult.fuelDto", result);
            redirect.addFlashAttribute("fuelDto", dto);
            return back;
        }
        try {
            TripFuel fuel = tripService.addFuel(id, dto);
            activityLogService.record(MODULE, "UPDATE_TRIP", "Recorded fuel on trip " + numberOf(id) + ": " + fuel.getLitres().toPlainString()
                    + " l, " + fuel.getAmount().toPlainString() + " RWF on " + fuel.getFilledOn(), ActivityStatus.SUCCESS);
            redirect.addFlashAttribute("flashSuccess", messages.get("tripFuel.added", num.litres(fuel.getLitres()), num.money(fuel.getAmount())));
        } catch (BusinessException e) {
            String error = messages.get(e.getMessageKey(), e.getArgs());
            activityLogService.record(MODULE, "UPDATE_TRIP", "Failed to record fuel on trip " + numberOf(id) + ": " + error, ActivityStatus.FAILED);
            if (e.getField() != null) {
                result.rejectValue(e.getField(), e.getMessageKey(), e.getArgs(), error);
                redirect.addFlashAttribute("org.springframework.validation.BindingResult.fuelDto", result);
                redirect.addFlashAttribute("fuelDto", dto);
            } else {
                redirect.addFlashAttribute("flashError", error);
            }
        }
        return back;
    }

    @PostMapping("/{id}/fuel/{fuelId}/remove")
    @PreAuthorize("hasAuthority('PAGE_FLEET') and hasAuthority('PERM_RECORD_TRIP_READINGS')")
    public String removeFuel(@PathVariable UUID id, @PathVariable UUID fuelId, @RequestParam(required = false) String reason,
                             RedirectAttributes redirect) {
        visible(id);
        String back = "redirect:/trips/" + id + "?tab=fuel";
        if (!StringUtils.hasText(reason) || reason.trim().length() > REASON_MAX) {
            redirect.addFlashAttribute("flashError", messages.get("po.reason.required"));
            return back;
        }
        try {
            TripFuel fuel = AuditContext.withReason(reason.trim(), () -> tripService.removeFuel(id, fuelId));
            activityLogService.record(MODULE, "UPDATE_TRIP", "Took off the fuel of " + fuel.getFilledOn() + " (" + fuel.getAmount().toPlainString()
                    + " RWF) from trip " + numberOf(id) + ": " + reason.trim(), ActivityStatus.SUCCESS);
            redirect.addFlashAttribute("flashSuccess", messages.get("tripFuel.removed"));
        } catch (BusinessException e) {
            String error = messages.get(e.getMessageKey(), e.getArgs());
            activityLogService.record(MODULE, "UPDATE_TRIP", "Failed to take fuel off trip " + numberOf(id) + ": " + error, ActivityStatus.FAILED);
            redirect.addFlashAttribute("flashError", error);
        }
        return back;
    }

    // ---------------------------------------------------------------- helpers

    /** The trip, if the user may see it (PERM_VIEW_TRIP or their own); otherwise 403. */
    private Trip visible(UUID id) {
        Trip trip = tripService.findDetailed(id);
        if (!TripService.canSee(trip)) {
            throw new AccessDeniedException("Not your trip");
        }
        return trip;
    }

    private static boolean isDriver(Trip trip) {
        Long me = AppUserPrincipal.current().map(AppUserPrincipal::getId).orElse(null);
        return me != null && me.equals(trip.getDriver().getUser().getId());
    }

    /** "The manifest has 40 pieces: RAC123A carries 35" when a load is over a limit; null when within. */
    private String overLimits(TripLoading.Check check, Vehicle vehicle, String keyPrefix) {
        if (check.overPieces()) {
            return messages.get(keyPrefix + "Pieces", check.load().pieces(), vehicle.getPlate(), vehicle.getMaxPieces());
        }
        if (check.overKg()) {
            return messages.get(keyPrefix + "Kg", num.kg(check.load().kg()), vehicle.getPlate(), num.kg(BigDecimal.valueOf(vehicle.getMaxLoadKg())));
        }
        return null;
    }

    /** "U-WH-000041" or "12 units (U-WH-000041, ...)" for the activity log. */
    private static String describe(List<String> codes) {
        if (codes.size() == 1) {
            return codes.get(0);
        }
        String shown = codes.size() <= 10 ? String.join(", ", codes) : String.join(", ", codes.subList(0, 10)) + ", ...";
        return codes.size() + " units (" + shown + ")";
    }

    private String numberOf(UUID id) {
        try {
            return tripService.findDetailed(id).getNumber();
        } catch (RuntimeException e) {
            return id.toString();
        }
    }

    private String form(Model model, TripDto dto) {
        Trip current = dto.getId() == null ? null : tripService.findDetailed(dto.getId());
        List<Vehicle> vehicles = new ArrayList<>(vehicleService.active());
        List<Driver> drivers = new ArrayList<>(driverService.active());
        model.addAttribute("tripDto", dto);
        model.addAttribute("trip", current);
        model.addAttribute("vehicles", vehicles);
        model.addAttribute("drivers", drivers);
        model.addAttribute("today", LocalDate.now(clock));
        return "trips/form";
    }

    private String invalid(Model model, TripDto dto, BindingResult result) {
        model.addAttribute("formErrors", result.getFieldErrors());
        return form(model, dto);
    }

    private String rejected(Model model, TripDto dto, BindingResult result, BusinessException e) {
        String error = messages.get(e.getMessageKey(), e.getArgs());
        if (e.getField() != null) {
            result.rejectValue(e.getField(), e.getMessageKey(), e.getArgs(), error);
        } else {
            model.addAttribute("flashError", error);
        }
        return invalid(model, dto, result);
    }
}
