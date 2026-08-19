package com.devanshedutech.service;

import com.devanshedutech.model.Holiday;
import com.devanshedutech.model.WorkingHours;
import com.devanshedutech.repository.HolidayRepository;
import com.devanshedutech.repository.WorkingHoursRepository;
import org.springframework.stereotype.Service;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.EnumMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * The only place that knows when the institute is open.
 *
 * <p>Every duration this application shows a human is measured through here rather than against
 * the wall clock. The difference is not cosmetic: an enquiry arriving at 11pm and answered at
 * 9:30am the next morning is thirty minutes of waiting, not ten and a half hours. Measured the
 * old way the response-time metric mostly recorded what time of day students fill in forms, and
 * a counsellor could only improve it by not sleeping.</p>
 *
 * <p>Everything is computed from stored rows rather than constants, because the hours of an
 * institute in Parbhani are not the hours of one anywhere else, and festival closures move
 * every year.</p>
 */
@Service
public class BusinessCalendar {

    /**
     * Longest gap worth walking day by day. A first response measured in years is not a
     * meaningful number whatever we return, and this keeps one bad row from walking a decade of
     * dates on every metrics request.
     */
    private static final int MAX_DAYS = 366;

    private final WorkingHoursRepository hours;
    private final HolidayRepository holidays;

    public BusinessCalendar(WorkingHoursRepository hours, HolidayRepository holidays) {
        this.hours = hours;
        this.holidays = holidays;
    }

    /** A loaded view of the calendar, so a multi-day span costs two queries rather than 2N. */
    private record Snapshot(Map<DayOfWeek, WorkingHours> week, Set<LocalDate> closedDates) {
        boolean isOpenOn(LocalDate date) {
            WorkingHours h = week.get(date.getDayOfWeek());
            return h != null && !h.isClosed() && !closedDates.contains(date);
        }
    }

    private Snapshot load(LocalDate from, LocalDate to) {
        Map<DayOfWeek, WorkingHours> week = new EnumMap<>(DayOfWeek.class);
        for (WorkingHours h : hours.findAll()) week.put(h.getDay(), h);
        Set<LocalDate> closed = new HashSet<>();
        for (Holiday h : holidays.findByDayBetweenOrderByDayAsc(from, to)) closed.add(h.getDay());
        return new Snapshot(week, closed);
    }

    /** Whether the institute is open at this exact moment. */
    public boolean isOpenAt(LocalDateTime at) {
        Snapshot s = load(at.toLocalDate(), at.toLocalDate());
        if (!s.isOpenOn(at.toLocalDate())) return false;
        WorkingHours h = s.week().get(at.getDayOfWeek());
        LocalTime t = at.toLocalTime();
        return !t.isBefore(h.getOpensAt()) && t.isBefore(h.getClosesAt());
    }

    /**
     * Minutes of working time between two moments.
     *
     * <p>Returns the plain elapsed minutes if no hours have been configured at all. A calendar
     * nobody has filled in must not silently report every response as instant — an empty
     * configuration should look like the old behaviour, not like perfection.</p>
     */
    public long workingMinutesBetween(LocalDateTime start, LocalDateTime end) {
        if (start == null || end == null || !end.isAfter(start)) return 0;

        LocalDate from = start.toLocalDate();
        LocalDate to = end.toLocalDate();
        if (from.plusDays(MAX_DAYS).isBefore(to)) to = from.plusDays(MAX_DAYS);

        Snapshot s = load(from, to);
        if (s.week().isEmpty()) return java.time.Duration.between(start, end).toMinutes();

        long total = 0;
        for (LocalDate d = from; !d.isAfter(to); d = d.plusDays(1)) {
            if (!s.isOpenOn(d)) continue;
            LocalTime dayStart = d.equals(from) ? start.toLocalTime() : LocalTime.MIN;
            LocalTime dayEnd = d.equals(end.toLocalDate()) ? end.toLocalTime() : LocalTime.MAX;
            total += s.week().get(d.getDayOfWeek()).minutesBetween(dayStart, dayEnd);
        }
        return total;
    }

    /**
     * The first working day on or after the date given.
     *
     * <p>Used when booking a follow-up. The SOP's cadence counts plain days — day 3, day 8,
     * day 21 — and a date arrived at by counting lands on a Sunday roughly one time in seven.
     * A follow-up nobody is in the building for is not a follow-up; it becomes an overdue row
     * on Monday morning and quietly teaches everyone that the due date does not mean
     * anything.</p>
     *
     * <p>Rolls forward rather than back, because contacting a student a day later than the SOP
     * says is a small cost and contacting them earlier than agreed is a broken promise.</p>
     */
    public LocalDate nextWorkingDay(LocalDate from) {
        if (from == null) return null;
        Snapshot s = load(from, from.plusDays(14));
        if (s.week().isEmpty()) return from;
        for (int i = 0; i <= 14; i++) {
            LocalDate d = from.plusDays(i);
            if (s.isOpenOn(d)) return d;
        }
        return from;
    }

    /**
     * The next moment the institute is open, at or after the one given.
     *
     * <p>Used to hold a notification that would otherwise wake somebody. Returns the moment
     * itself if nothing is configured, and gives up after a fortnight rather than looping — a
     * calendar with every day closed is a mistake to surface, not one to search forever.</p>
     */
    public LocalDateTime nextOpening(LocalDateTime from) {
        Snapshot s = load(from.toLocalDate(), from.toLocalDate().plusDays(14));
        if (s.week().isEmpty()) return from;

        for (int i = 0; i <= 14; i++) {
            LocalDate d = from.toLocalDate().plusDays(i);
            if (!s.isOpenOn(d)) continue;
            WorkingHours h = s.week().get(d.getDayOfWeek());
            LocalDateTime opens = LocalDateTime.of(d, h.getOpensAt());
            if (i == 0) {
                if (from.toLocalTime().isBefore(h.getOpensAt())) return opens;
                if (from.toLocalTime().isBefore(h.getClosesAt())) return from;
                continue;
            }
            return opens;
        }
        return from;
    }
}
