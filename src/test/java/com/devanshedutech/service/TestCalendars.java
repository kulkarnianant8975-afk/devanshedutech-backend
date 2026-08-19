package com.devanshedutech.service;

import com.devanshedutech.model.WorkingHours;
import com.devanshedutech.repository.HolidayRepository;
import com.devanshedutech.repository.WorkingHoursRepository;

import java.time.DayOfWeek;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * Calendars for tests that are not about the calendar.
 *
 * <p>Every one of these suites asserts a follow-up date the SOP arrives at by counting days —
 * "day +3", "day +8". Those assertions are about the cadence, not about opening hours, so they
 * run against an institute that never closes and the dates come out exactly as counted.
 * BusinessCalendarTest is where closures are actually exercised.</p>
 */
final class TestCalendars {

    private TestCalendars() {}

    /** Open every day, so no follow-up date is ever moved. */
    static BusinessCalendar openEveryDay() {
        WorkingHoursRepository hours = mock(WorkingHoursRepository.class);
        HolidayRepository holidays = mock(HolidayRepository.class);
        List<WorkingHours> week = new ArrayList<>();
        for (DayOfWeek d : DayOfWeek.values()) {
            week.add(WorkingHours.builder().day(d)
                    .opensAt(LocalTime.of(10, 0)).closesAt(LocalTime.of(19, 0)).closed(false).build());
        }
        when(hours.findAll()).thenReturn(week);
        when(holidays.findByDayBetweenOrderByDayAsc(any(), any())).thenReturn(List.of());
        return new BusinessCalendar(hours, holidays);
    }
}
