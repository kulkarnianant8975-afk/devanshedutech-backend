package com.devanshedutech.service;

import com.devanshedutech.model.Holiday;
import com.devanshedutech.model.WorkingHours;
import com.devanshedutech.repository.HolidayRepository;
import com.devanshedutech.repository.WorkingHoursRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class BusinessCalendarTest {

    private WorkingHoursRepository hours;
    private HolidayRepository holidays;
    private BusinessCalendar calendar;

    /** Monday 20 July 2026 — a Monday, so the weekday arithmetic below is readable. */
    private static final LocalDate MONDAY = LocalDate.of(2026, 7, 20);

    @BeforeEach
    void setUp() {
        hours = mock(WorkingHoursRepository.class);
        holidays = mock(HolidayRepository.class);
        when(holidays.findByDayBetweenOrderByDayAsc(any(), any())).thenReturn(List.of());
        calendar = new BusinessCalendar(hours, holidays);
        openMondayToSaturday();
    }

    /** Ten to seven, six days a week — the institute's actual pattern. */
    private void openMondayToSaturday() {
        List<WorkingHours> week = new ArrayList<>();
        for (DayOfWeek d : DayOfWeek.values()) {
            week.add(WorkingHours.builder()
                    .day(d)
                    .opensAt(LocalTime.of(10, 0))
                    .closesAt(LocalTime.of(19, 0))
                    .closed(d == DayOfWeek.SUNDAY)
                    .build());
        }
        when(hours.findAll()).thenReturn(week);
    }

    private LocalDateTime at(LocalDate d, int h, int m) {
        return LocalDateTime.of(d, LocalTime.of(h, m));
    }

    @Test
    @DisplayName("a reply within the same morning is measured normally")
    void sameDayGapIsPlain() {
        assertEquals(30, calendar.workingMinutesBetween(at(MONDAY, 10, 30), at(MONDAY, 11, 0)));
    }

    @Test
    @DisplayName("the night in between does not count against the counsellor")
    void overnightGapCountsOnlyOpeningHours() {
        // Arrives 23:00 Monday, answered 10:30 Tuesday. Wall clock says 11.5 hours; the student
        // actually waited thirty minutes of opening time, and nobody was awake for the rest.
        long minutes = calendar.workingMinutesBetween(at(MONDAY, 23, 0), at(MONDAY.plusDays(1), 10, 30));
        assertEquals(30, minutes);
        assertEquals(690, java.time.Duration.between(
                at(MONDAY, 23, 0), at(MONDAY.plusDays(1), 10, 30)).toMinutes(),
                "the wall-clock figure this replaces");
    }

    @Test
    @DisplayName("an enquiry arriving before opening starts counting at opening, not at midnight")
    void earlyMorningWaitsForOpening() {
        assertEquals(15, calendar.workingMinutesBetween(at(MONDAY, 6, 0), at(MONDAY, 10, 15)));
    }

    @Test
    @DisplayName("Sunday is skipped entirely")
    void closedDaysContributeNothing() {
        LocalDate saturday = MONDAY.plusDays(5);
        // 18:30 Saturday to 10:15 Monday: half an hour of Saturday, none of Sunday, 15 of Monday.
        assertEquals(45, calendar.workingMinutesBetween(at(saturday, 18, 30), at(MONDAY.plusDays(7), 10, 15)));
    }

    @Test
    @DisplayName("a holiday closes a day the weekly pattern says is open")
    void holidaysAreHonoured() {
        LocalDate tuesday = MONDAY.plusDays(1);
        when(holidays.findByDayBetweenOrderByDayAsc(any(), any()))
                .thenReturn(List.of(Holiday.builder().day(tuesday).name("Ganesh Chaturthi").build()));
        assertEquals(0, calendar.workingMinutesBetween(at(tuesday, 10, 0), at(tuesday, 18, 0)));
    }

    @Test
    @DisplayName("a full open day is nine hours, however the span is clipped")
    void wholeDaysAreCounted() {
        // Monday 09:00 to Wednesday 20:00 — two full days plus Wednesday's nine hours.
        assertEquals(27 * 60, calendar.workingMinutesBetween(at(MONDAY, 9, 0), at(MONDAY.plusDays(2), 20, 0)));
    }

    @Test
    @DisplayName("with no hours configured it reports plain elapsed time rather than nothing")
    void emptyCalendarFallsBackToWallClock() {
        when(hours.findAll()).thenReturn(List.of());
        // The alternative — returning 0 — would make an unconfigured institute look like it
        // answers every enquiry instantly, which is the most misleading number available.
        assertEquals(690, calendar.workingMinutesBetween(at(MONDAY, 23, 0), at(MONDAY.plusDays(1), 10, 30)));
    }

    @Test
    @DisplayName("time does not run backwards")
    void reversedOrEqualBoundsAreZero() {
        assertEquals(0, calendar.workingMinutesBetween(at(MONDAY, 15, 0), at(MONDAY, 11, 0)));
        assertEquals(0, calendar.workingMinutesBetween(at(MONDAY, 11, 0), at(MONDAY, 11, 0)));
        assertEquals(0, calendar.workingMinutesBetween(null, at(MONDAY, 11, 0)));
    }

    @Test
    @DisplayName("open means open")
    void isOpenAtTracksTheHours() {
        assertTrue(calendar.isOpenAt(at(MONDAY, 10, 0)), "the minute it opens");
        assertTrue(calendar.isOpenAt(at(MONDAY, 18, 59)));
        assertFalse(calendar.isOpenAt(at(MONDAY, 19, 0)), "closing time is closed");
        assertFalse(calendar.isOpenAt(at(MONDAY, 9, 59)));
        assertFalse(calendar.isOpenAt(at(MONDAY.plusDays(6), 12, 0)), "Sunday");
    }

    @Test
    @DisplayName("a follow-up date lands on a day someone is in the building")
    void nextWorkingDayRollsForwardOffClosures() {
        LocalDate sunday = MONDAY.plusDays(6);
        assertEquals(MONDAY, calendar.nextWorkingDay(MONDAY), "an open day is left alone");
        assertEquals(MONDAY.plusDays(7), calendar.nextWorkingDay(sunday), "Sunday rolls to Monday");

        when(holidays.findByDayBetweenOrderByDayAsc(any(), any())).thenReturn(List.of(
                Holiday.builder().day(MONDAY.plusDays(7)).name("Independence Day").build()));
        assertEquals(MONDAY.plusDays(8), calendar.nextWorkingDay(sunday),
                "a Monday holiday rolls on to Tuesday");
    }

    @Test
    @DisplayName("the next opening is when someone will actually see it")
    void nextOpeningSkipsNightsAndSundays() {
        assertEquals(at(MONDAY, 12, 0), calendar.nextOpening(at(MONDAY, 12, 0)),
                "already open — now");
        assertEquals(at(MONDAY, 10, 0), calendar.nextOpening(at(MONDAY, 6, 0)),
                "before opening — this morning");
        assertEquals(at(MONDAY.plusDays(1), 10, 0), calendar.nextOpening(at(MONDAY, 22, 0)),
                "after closing — tomorrow morning");
        LocalDate saturday = MONDAY.plusDays(5);
        assertEquals(at(MONDAY.plusDays(7), 10, 0), calendar.nextOpening(at(saturday, 20, 0)),
                "Saturday night rolls past Sunday to Monday");
    }
}
