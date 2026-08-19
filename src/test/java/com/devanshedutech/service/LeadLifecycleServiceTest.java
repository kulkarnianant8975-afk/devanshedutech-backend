package com.devanshedutech.service;

import com.devanshedutech.model.Lead;
import com.devanshedutech.model.LeadActivity;
import com.devanshedutech.model.crm.Grade;
import com.devanshedutech.model.crm.LostReason;
import com.devanshedutech.model.crm.OutcomeCode;
import com.devanshedutech.model.crm.Stage;
import com.devanshedutech.repository.LeadActivityRepository;
import com.devanshedutech.repository.LeadRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;

import java.time.LocalDate;
import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * The SOP's section 6 rules, expressed as tests.
 *
 * <p>These are the consequences a counsellor should never have to remember: what the stage
 * becomes, when the next touch falls, and which outcomes refuse to proceed without an answer.</p>
 */
class LeadLifecycleServiceTest {

    private LeadRepository leads;
    private LeadActivityRepository activities;
    private LeadLifecycleService service;
    private LeadLifecycleService.Actor sneha;

    @BeforeEach
    void setUp() {
        leads = mock(LeadRepository.class);
        activities = mock(LeadActivityRepository.class);
        when(leads.save(any())).thenAnswer(i -> i.getArgument(0));
        when(activities.save(any())).thenAnswer(i -> i.getArgument(0));
        service = new LeadLifecycleService(leads, activities, TestCalendars.openEveryDay());
        sneha = new LeadLifecycleService.Actor("u1", "Sneha Kulkarni");
    }

    private Lead lead() {
        return Lead.builder()
                .id("l1").fullName("Rohit Deshmukh").mobileNumber("+91 9876543210")
                .courseInterested("Data Analytics").stage(Stage.NEW).callAttempts(0)
                .updatesOnly(false).optedOut(false)
                .createdAt(LocalDateTime.now().minusDays(2))
                .build();
    }

    // ---------------- outcomes ----------------

    @Test
    @DisplayName("a connected call moves to Contacted and books tomorrow")
    void connectedCallBooksNextTouch() {
        Lead l = lead();
        service.applyOutcome(l, OutcomeCode.CONNECTED, "Wants a job after college", null, sneha);

        assertEquals(Stage.CONTACTED, l.getStage());
        assertEquals(LocalDate.now().plusDays(1), l.getNextTouchOn());
        assertNotNull(l.getNextTouchNote());
        assertNotNull(l.getFirstRespondedAt(), "first response must be stamped on first contact");
    }

    @Test
    @DisplayName("an unanswered call counts an attempt; answering resets the run")
    void callAttemptsCountConsecutiveMisses() {
        Lead l = lead();
        service.applyOutcome(l, OutcomeCode.NO_ANSWER, null, null, sneha);
        service.applyOutcome(l, OutcomeCode.NO_ANSWER, null, null, sneha);
        assertEquals(2, l.getCallAttempts());

        // The SOP's three-attempt limit is about consecutive misses, not calls ever placed.
        service.applyOutcome(l, OutcomeCode.CONNECTED, "Spoke at last", null, sneha);
        assertEquals(0, l.getCallAttempts());
    }

    @Test
    @DisplayName("a missed call still books the next attempt for tomorrow")
    void dnpBooksNextDay() {
        Lead l = lead();
        service.applyOutcome(l, OutcomeCode.NO_ANSWER, null, null, sneha);
        assertEquals(LocalDate.now().plusDays(1), l.getNextTouchOn());
        assertEquals(Stage.NEW, l.getStage(), "a missed call is not a stage change");
    }

    @Test
    @DisplayName("'asking parents' books the day+1 and day+3 pair the playbook says everyone forgets")
    void parentsOutcomeSchedulesBothFollowUps() {
        Lead l = lead();
        service.applyOutcome(l, OutcomeCode.PARENTS, "Will speak to father tonight", null, sneha);

        assertEquals(LocalDate.now().plusDays(1), l.getNextTouchOn());
        verify(activities, atLeastOnce()).save(argThat((LeadActivity a) ->
                a.getSummary().equals("Follow-ups scheduled")
                        && a.getDetail().contains("day +1")
                        && a.getDetail().contains("day +3")));
    }

    @Test
    @DisplayName("attending a demo grades the lead Hot and schedules the post-demo pair")
    void demoAttendedGradesHot() {
        Lead l = lead();
        service.applyOutcome(l, OutcomeCode.DEMO_ATTENDED, null, null, sneha);
        assertEquals(Stage.DEMO_DONE, l.getStage());
        assertEquals(Grade.HOT, l.getGrade());
        assertEquals(LocalDate.now().plusDays(1), l.getNextTouchOn());
    }

    // ---------------- refusals ----------------

    @Test
    @DisplayName("'thinking about it' will not be logged without the real reason")
    void thinkingRequiresANote() {
        Lead l = lead();
        ResponseStatusException e = assertThrows(ResponseStatusException.class, () ->
                service.applyOutcome(l, OutcomeCode.THINKING, "   ", null, sneha));
        assertEquals(400, e.getStatusCode().value());
        assertEquals(Stage.NEW, l.getStage(), "nothing may change when the outcome is refused");
    }

    @Test
    @DisplayName("a lead cannot be lost without a reason the institute can learn from")
    void losingRequiresAReason() {
        Lead l = lead();
        ResponseStatusException e = assertThrows(ResponseStatusException.class, () ->
                service.applyOutcome(l, OutcomeCode.NOT_INTERESTED, "Said no thanks", null, sneha));
        assertEquals(400, e.getStatusCode().value());
    }

    @Test
    @DisplayName("an enrolled student cannot be marked lost by picking the wrong dropdown item")
    void enrolledCannotBeLost() {
        Lead l = lead();
        l.setStage(Stage.ENROLLED);
        ResponseStatusException e = assertThrows(ResponseStatusException.class, () ->
                service.applyOutcome(l, OutcomeCode.NOT_INTERESTED, "changed mind",
                        LostReason.OTHER, sneha));
        assertEquals(409, e.getStatusCode().value());
        assertEquals(Stage.ENROLLED, l.getStage());
    }

    @Test
    @DisplayName("a lost lead keeps its record, clears its next touch, and moves to updates only")
    void losingIsRecordedNotDeleted() {
        Lead l = lead();
        service.applyOutcome(l, OutcomeCode.NOT_INTERESTED, "Joined a Nanded institute",
                LostReason.CHOSE_COMPETITOR, sneha);

        assertEquals(Stage.LOST, l.getStage());
        assertEquals(LostReason.CHOSE_COMPETITOR, l.getLostReason());
        assertNull(l.getNextTouchOn());
        assertTrue(l.getUpdatesOnly());
        verify(leads, never()).deleteById(any());
    }

    // ---------------- inbound and promotion ----------------

    @Test
    @DisplayName("a student asking about fees is promoted to Hot and called today")
    void buyingSignalPromotesToHot() {
        Lead l = lead();
        l.setGrade(Grade.COLD);
        service.recordInbound(l, "what are the fees for data analytics?", sneha);

        assertEquals(Grade.HOT, l.getGrade());
        assertEquals(LocalDate.now(), l.getNextTouchOn());
        assertNotNull(l.getLastInboundAt());
    }

    @Test
    @DisplayName("a cold lead who simply replies becomes warm again")
    void anyReplyWarmsAColdLead() {
        Lead l = lead();
        l.setGrade(Grade.COLD);
        service.recordInbound(l, "ok thanks", sneha);
        assertEquals(Grade.WARM, l.getGrade());
    }

    @Test
    @DisplayName("a lost lead who comes back is reopened, which is why they were never deleted")
    void lostLeadReturning() {
        Lead l = lead();
        l.setStage(Stage.LOST);
        l.setLostReason(LostReason.FEES);
        l.setUpdatesOnly(true);

        service.recordInbound(l, "is the next batch still open?", sneha);

        assertEquals(Stage.CONTACTED, l.getStage());
        assertNull(l.getLostReason());
        assertFalse(l.getUpdatesOnly());
        assertEquals(LocalDate.now(), l.getNextTouchOn());
    }

    // ---------------- transitions ----------------

    @Test
    @DisplayName("grading a lead Hot pulls the next touch to today")
    void hotIsCalledToday() {
        Lead l = lead();
        l.setNextTouchOn(LocalDate.now().plusDays(5));
        service.moveGrade(l, Grade.HOT, "Asked for a demo", sneha);
        assertEquals(LocalDate.now(), l.getNextTouchOn());
        assertNotNull(l.getGradeEnteredAt());
    }

    @Test
    @DisplayName("enrolling schedules the week-one check-in that leads to a referral")
    void enrolmentSchedulesWeekOne() {
        Lead l = lead();
        service.moveStage(l, Stage.ENROLLED, "Paid in full", sneha);
        assertEquals(LocalDate.now().plusDays(7), l.getNextTouchOn());
    }

    @Test
    @DisplayName("every stage and grade change is written to the timeline")
    void transitionsAreAlwaysRecorded() {
        Lead l = lead();
        service.moveStage(l, Stage.CONTACTED, "called", sneha);
        service.moveGrade(l, Grade.WARM, "exploring", sneha);
        verify(activities, times(2)).save(any());
    }

    @Test
    @DisplayName("a no-op transition writes nothing")
    void sameStageIsNotLogged() {
        Lead l = lead();
        service.moveStage(l, Stage.NEW, "no change", sneha);
        verify(activities, never()).save(any());
    }

    @Test
    @DisplayName("opting out clears the next touch and every future contact")
    void optOutStopsEverything() {
        Lead l = lead();
        l.setNextTouchOn(LocalDate.now());
        service.optOut(l, sneha);

        assertTrue(l.getOptedOut());
        assertFalse(l.getUpdatesOnly());
        assertNull(l.getNextTouchOn());
        assertFalse(l.isActive());
    }

    @Test
    @DisplayName("a follow-up that counts onto a Sunday is booked for the Monday instead")
    void followUpsAvoidClosedDays() {
        // The SOP counts plain days, so about one follow-up in seven lands on a closed day.
        // Booking it there produces an overdue row on Monday morning for a call nobody could
        // have made, and a due date that is routinely wrong stops being read at all.
        com.devanshedutech.repository.WorkingHoursRepository hours =
                mock(com.devanshedutech.repository.WorkingHoursRepository.class);
        com.devanshedutech.repository.HolidayRepository holidays =
                mock(com.devanshedutech.repository.HolidayRepository.class);
        java.util.List<com.devanshedutech.model.WorkingHours> week = new java.util.ArrayList<>();
        for (java.time.DayOfWeek d : java.time.DayOfWeek.values()) {
            week.add(com.devanshedutech.model.WorkingHours.builder().day(d)
                    .opensAt(java.time.LocalTime.of(10, 0))
                    .closesAt(java.time.LocalTime.of(19, 0))
                    .closed(d == java.time.DayOfWeek.SUNDAY).build());
        }
        when(hours.findAll()).thenReturn(week);
        when(holidays.findByDayBetweenOrderByDayAsc(any(), any())).thenReturn(java.util.List.of());

        LeadLifecycleService closedSundays =
                new LeadLifecycleService(leads, activities, new BusinessCalendar(hours, holidays));

        Lead lead = Lead.builder().id("l1").fullName("Rohit").build();
        java.time.LocalDate sunday = java.time.LocalDate.of(2026, 7, 26);
        assertEquals(java.time.DayOfWeek.SUNDAY, sunday.getDayOfWeek());

        closedSundays.setNextTouch(lead, sunday, "Call about the batch");
        assertEquals(sunday.plusDays(1), lead.getNextTouchOn());
    }
}
