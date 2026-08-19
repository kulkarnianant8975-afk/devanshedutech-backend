package com.devanshedutech.controller;

import com.devanshedutech.model.DutyShift;
import com.devanshedutech.model.Holiday;
import com.devanshedutech.model.WorkingHours;
import com.devanshedutech.repository.DutyShiftRepository;
import com.devanshedutech.repository.HolidayRepository;
import com.devanshedutech.repository.UserRepository;
import com.devanshedutech.service.DutyRosterService;
import lombok.Data;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import com.devanshedutech.repository.WorkingHoursRepository;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Opening hours, closures, and who is on live-enquiry duty.
 *
 * <p>All three used to be assumptions: the institute was implicitly open every minute of every
 * day, and a new enquiry belonged to nobody until someone happened to look at the list.</p>
 */
@RestController
@RequestMapping("/api/schedule")
public class ScheduleController {

    private final WorkingHoursRepository hours;
    private final HolidayRepository holidays;
    private final DutyShiftRepository shifts;
    private final UserRepository users;
    private final DutyRosterService roster;

    public ScheduleController(WorkingHoursRepository hours, HolidayRepository holidays,
                              DutyShiftRepository shifts, UserRepository users,
                              DutyRosterService roster) {
        this.hours = hours;
        this.holidays = holidays;
        this.shifts = shifts;
        this.users = users;
        this.roster = roster;
    }

    // ---------------- opening hours ----------------

    @GetMapping("/hours")
    @PreAuthorize("hasAuthority('PERM_LEAD_VIEW_OWN')")
    public List<WorkingHours> hours() {
        return hours.findAll().stream()
                .sorted(java.util.Comparator.comparing(WorkingHours::getDay))
                .toList();
    }

    @Data
    public static class HoursRequest {
        private String day;
        private String opensAt;
        private String closesAt;
        private boolean closed;
    }

    @PutMapping("/hours")
    @PreAuthorize("hasAuthority('PERM_SETTINGS_MANAGE')")
    @Transactional
    public List<WorkingHours> setHours(@RequestBody List<HoursRequest> week) {
        for (HoursRequest r : week) {
            DayOfWeek day = parseDay(r.getDay());
            LocalTime opens = parseTime(r.getOpensAt(), "opening time");
            LocalTime closes = parseTime(r.getClosesAt(), "closing time");

            // A day that closes before it opens would make every duration on that day zero, and
            // the resulting response-time figures would look excellent for no reason.
            if (!r.isClosed() && !closes.isAfter(opens)) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                        day.getDisplayName(java.time.format.TextStyle.FULL, java.util.Locale.ENGLISH)
                                + " closes at or before it opens. Set a closing time after "
                                + opens + ", or mark the day closed.");
            }
            hours.save(WorkingHours.builder()
                    .day(day).opensAt(opens).closesAt(closes).closed(r.isClosed()).build());
        }
        return hours();
    }

    // ---------------- holidays ----------------

    @GetMapping("/holidays")
    @PreAuthorize("hasAuthority('PERM_LEAD_VIEW_OWN')")
    public List<Holiday> upcomingHolidays() {
        return holidays.findByDayGreaterThanEqualOrderByDayAsc(LocalDate.now().minusMonths(1));
    }

    @Data
    public static class HolidayRequest {
        private String day;
        private String name;
    }

    @PostMapping("/holidays")
    @PreAuthorize("hasAuthority('PERM_SETTINGS_MANAGE')")
    @Transactional
    public Holiday addHoliday(@RequestBody HolidayRequest request) {
        LocalDate day;
        try {
            day = LocalDate.parse(request.getDay());
        } catch (Exception e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Pick a date for the closure.");
        }
        String name = request.getName() == null || request.getName().isBlank()
                ? "Closed" : request.getName().trim();
        return holidays.save(Holiday.builder().day(day).name(name).build());
    }

    @DeleteMapping("/holidays/{day}")
    @PreAuthorize("hasAuthority('PERM_SETTINGS_MANAGE')")
    @Transactional
    public Map<String, String> removeHoliday(@PathVariable String day) {
        holidays.deleteById(LocalDate.parse(day));
        return Map.of("status", "removed");
    }

    // ---------------- duty roster ----------------

    @GetMapping("/roster")
    @PreAuthorize("hasAuthority('PERM_LEAD_VIEW_OWN')")
    public List<DutyShift> roster() {
        return roster.roster();
    }

    @Data
    public static class ShiftRequest {
        private String userId;
        private String day;
        private String startsAt;
        private String endsAt;
    }

    @PostMapping("/roster")
    @PreAuthorize("hasAuthority('PERM_LEAD_ASSIGN')")
    @Transactional
    public DutyShift addShift(@RequestBody ShiftRequest request) {
        if (request.getUserId() == null || users.findById(request.getUserId()).isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Choose who is on duty for this shift.");
        }
        DayOfWeek day = parseDay(request.getDay());
        LocalTime from = parseTime(request.getStartsAt(), "shift start");
        LocalTime to = parseTime(request.getEndsAt(), "shift end");
        if (!to.isAfter(from)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "A shift has to end after it starts.");
        }
        return shifts.save(DutyShift.builder()
                .id(UUID.randomUUID().toString())
                .userId(request.getUserId())
                .day(day).startsAt(from).endsAt(to)
                .build());
    }

    @DeleteMapping("/roster/{id}")
    @PreAuthorize("hasAuthority('PERM_LEAD_ASSIGN')")
    @Transactional
    public Map<String, String> removeShift(@PathVariable String id) {
        shifts.deleteById(id);
        return Map.of("status", "removed");
    }

    /** Who would pick up an enquiry arriving right now — shown so gaps in cover are visible. */
    @GetMapping("/on-duty")
    @PreAuthorize("hasAuthority('PERM_LEAD_VIEW_OWN')")
    public Map<String, Object> onDutyNow() {
        return roster.onDutyAt(java.time.LocalDateTime.now())
                .<Map<String, Object>>map(id -> Map.of(
                        "userId", id,
                        "name", users.findById(id).map(u -> u.getDisplayName() == null
                                ? u.getEmail() : u.getDisplayName()).orElse(id)))
                .orElse(Map.of());
    }

    private DayOfWeek parseDay(String raw) {
        try {
            return DayOfWeek.valueOf(raw.trim().toUpperCase(java.util.Locale.ROOT));
        } catch (Exception e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Unrecognised day: " + raw);
        }
    }

    private LocalTime parseTime(String raw, String what) {
        try {
            return LocalTime.parse(raw);
        } catch (Exception e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Enter the " + what + " as a time, for example 10:00.");
        }
    }
}
