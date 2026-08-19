package com.devanshedutech.service;

import com.devanshedutech.model.DutyShift;
import com.devanshedutech.model.Lead;
import com.devanshedutech.model.Role;
import com.devanshedutech.model.User;
import com.devanshedutech.repository.DutyShiftRepository;
import com.devanshedutech.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class DutyRosterServiceTest {

    private DutyShiftRepository shifts;
    private UserRepository users;
    private DutyRosterService roster;

    private static final LocalDate MONDAY = LocalDate.of(2026, 7, 20);

    @BeforeEach
    void setUp() {
        shifts = mock(DutyShiftRepository.class);
        users = mock(UserRepository.class);
        when(shifts.findByDayOrderByStartsAtAsc(any())).thenReturn(List.of());
        roster = new DutyRosterService(shifts, users);
    }

    private void staff(String id, Role role, boolean active) {
        when(users.findById(id)).thenReturn(Optional.of(User.builder()
                .id(id).email(id + "@devanshedutech.com").role(role.name()).active(active).build()));
    }

    private DutyShift shift(String id, String userId, int fromHour, int toHour) {
        return DutyShift.builder().id(id).userId(userId).day(DayOfWeek.MONDAY)
                .startsAt(LocalTime.of(fromHour, 0)).endsAt(LocalTime.of(toHour, 0)).build();
    }

    private LocalDateTime monday(int hour) {
        return LocalDateTime.of(MONDAY, LocalTime.of(hour, 0));
    }

    @Test
    @DisplayName("the enquiry goes to whoever is on duty")
    void assignsToTheCounsellorOnDuty() {
        staff("priya", Role.SALES_EXECUTIVE, true);
        when(shifts.findByDayOrderByStartsAtAsc(DayOfWeek.MONDAY))
                .thenReturn(List.of(shift("s1", "priya", 10, 14)));

        Lead lead = Lead.builder().id("l1").build();
        assertTrue(roster.assignIfUnowned(lead, monday(11)));
        assertEquals("priya", lead.getAssignedToId());
    }

    @Test
    @DisplayName("a shift is over at its end time, not during the hour after")
    void shiftBoundariesAreHalfOpen() {
        staff("priya", Role.SALES_EXECUTIVE, true);
        when(shifts.findByDayOrderByStartsAtAsc(DayOfWeek.MONDAY))
                .thenReturn(List.of(shift("s1", "priya", 10, 14)));

        assertTrue(roster.onDutyAt(monday(10)).isPresent(), "the minute it starts");
        assertTrue(roster.onDutyAt(monday(13)).isPresent());
        assertFalse(roster.onDutyAt(monday(14)).isPresent(), "the handover minute belongs to the next shift");
        assertFalse(roster.onDutyAt(monday(9)).isPresent());
    }

    @Test
    @DisplayName("a counsellor already working the lead does not lose it to a shift change")
    void anOwnedLeadIsLeftAlone() {
        staff("priya", Role.SALES_EXECUTIVE, true);
        when(shifts.findByDayOrderByStartsAtAsc(DayOfWeek.MONDAY))
                .thenReturn(List.of(shift("s1", "priya", 10, 14)));

        Lead lead = Lead.builder().id("l1").assignedToId("amit").build();
        assertFalse(roster.assignIfUnowned(lead, monday(11)));
        assertEquals("amit", lead.getAssignedToId(), "mid-conversation ownership survives");
    }

    @Test
    @DisplayName("a shift naming someone who has left is skipped, not honoured")
    void deactivatedStaffDoNotSwallowEnquiries() {
        // The failure this prevents is silent: someone leaves, and every Monday enquiry lands in
        // an inbox nobody opens. An unassigned lead is visible; one owned by a ghost is not.
        staff("gone", Role.SALES_EXECUTIVE, false);
        staff("priya", Role.SALES_EXECUTIVE, true);
        when(shifts.findByDayOrderByStartsAtAsc(DayOfWeek.MONDAY))
                .thenReturn(List.of(shift("s1", "gone", 10, 14), shift("s2", "priya", 10, 14)));

        assertEquals(Optional.of("priya"), roster.onDutyAt(monday(11)));
    }

    @Test
    @DisplayName("a shift naming someone who cannot work leads is skipped")
    void staffWithoutLeadAccessAreSkipped() {
        staff("nobody", Role.NONE, true);
        when(shifts.findByDayOrderByStartsAtAsc(DayOfWeek.MONDAY))
                .thenReturn(List.of(shift("s1", "nobody", 10, 14)));

        assertTrue(roster.onDutyAt(monday(11)).isEmpty());
    }

    @Test
    @DisplayName("a shift naming an account that no longer exists is skipped")
    void deletedStaffAreSkipped() {
        when(users.findById("ghost")).thenReturn(Optional.empty());
        when(shifts.findByDayOrderByStartsAtAsc(DayOfWeek.MONDAY))
                .thenReturn(List.of(shift("s1", "ghost", 10, 14)));

        assertTrue(roster.onDutyAt(monday(11)).isEmpty());
    }

    @Test
    @DisplayName("with two people covering the same hour, the earlier shift takes it")
    void overlappingShiftsResolvePredictably() {
        staff("priya", Role.SALES_EXECUTIVE, true);
        staff("amit", Role.SALES_EXECUTIVE, true);
        when(shifts.findByDayOrderByStartsAtAsc(DayOfWeek.MONDAY))
                .thenReturn(List.of(shift("s2", "amit", 12, 18), shift("s1", "priya", 10, 14)));

        assertEquals(Optional.of("priya"), roster.onDutyAt(monday(13)),
                "sorted by start time, so the answer does not depend on row order");
    }

    @Test
    @DisplayName("nobody on duty leaves the lead unassigned rather than guessing")
    void anEmptyRosterAssignsNobody() {
        Lead lead = Lead.builder().id("l1").build();
        assertFalse(roster.assignIfUnowned(lead, monday(23)));
        assertNull(lead.getAssignedToId());
    }
}
