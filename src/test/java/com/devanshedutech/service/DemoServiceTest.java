package com.devanshedutech.service;

import com.devanshedutech.model.Demo;
import com.devanshedutech.model.Lead;
import com.devanshedutech.model.crm.Grade;
import com.devanshedutech.model.crm.Stage;
import com.devanshedutech.repository.DemoRepository;
import com.devanshedutech.repository.LeadActivityRepository;
import com.devanshedutech.repository.LeadRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * Demos, and specifically what marking attendance is supposed to set off.
 *
 * <p>The interesting case is the no-show. Treating it as a rejection is the intuitive thing to
 * do and it is wrong: a student who misses a class usually had a bus problem, not a change of
 * heart, and writing them off is how an institute loses people it had already convinced.</p>
 */
class DemoServiceTest {

    private DemoRepository demos;
    private LeadRepository leads;
    private LeadActivityRepository activities;
    private DemoService service;
    private LeadLifecycleService.Actor sneha;

    @BeforeEach
    void setUp() {
        demos = mock(DemoRepository.class);
        leads = mock(LeadRepository.class);
        activities = mock(LeadActivityRepository.class);
        when(demos.save(any())).thenAnswer(i -> i.getArgument(0));
        when(leads.save(any())).thenAnswer(i -> i.getArgument(0));
        when(activities.save(any())).thenAnswer(i -> i.getArgument(0));
        service = new DemoService(demos, leads, new LeadLifecycleService(leads, activities, TestCalendars.openEveryDay()));
        sneha = new LeadLifecycleService.Actor("u1", "Sneha Kulkarni");
    }

    private Lead lead() {
        return Lead.builder().id("l1").fullName("Rohit Deshmukh").courseInterested("Data Analytics")
                .stage(Stage.CONTACTED).callAttempts(0).updatesOnly(false).optedOut(false)
                .createdAt(LocalDateTime.now().minusDays(3)).build();
    }

    private Demo booked(Lead l, LocalDateTime when) {
        return Demo.builder().id("d1").leadId(l.getId()).studentName(l.getFullName())
                .scheduledAt(when).mode("Demo class").createdAt(LocalDateTime.now()).build();
    }

    @Test
    @DisplayName("booking moves the lead to Demo booked and points the next touch at the demo")
    void bookingSetsTheStageAndDate() {
        Lead l = lead();
        LocalDateTime when = LocalDateTime.now().plusDays(2).withHour(17);

        Demo d = service.book(l, when, "Demo class", sneha);

        assertEquals(Stage.DEMO_BOOKED, l.getStage());
        assertEquals(when.toLocalDate(), l.getNextTouchOn());
        assertEquals("Rohit Deshmukh", d.getStudentName());
        assertNull(d.getAttended(), "attendance is unknown until somebody marks it");
    }

    @Test
    @DisplayName("a demo cannot be booked in the past, which is almost always a typo")
    void pastBookingsAreRejected() {
        ResponseStatusException e = assertThrows(ResponseStatusException.class,
                () -> service.book(lead(), LocalDateTime.now().minusDays(3), "Demo class", sneha));
        assertEquals(400, e.getStatusCode().value());
    }

    @Test
    @DisplayName("attending applies the SOP's post-demo rules, including the day+1 and day+3 pair")
    void attendingSchedulesTheFollowUps() {
        Lead l = lead();
        Demo d = booked(l, LocalDateTime.now().minusHours(2));
        when(leads.findById("l1")).thenReturn(Optional.of(l));

        service.mark(d, true, "Loved the hands-on part", sneha);

        assertTrue(d.getAttended());
        assertEquals(Stage.DEMO_DONE, l.getStage());
        assertEquals(Grade.HOT, l.getGrade());
        assertEquals(LocalDate.now().plusDays(1), l.getNextTouchOn());
        assertNotNull(d.getMarkedAt());
    }

    @Test
    @DisplayName("a no-show is a logistics problem, not a rejection")
    void noShowKeepsTheLeadAlive() {
        Lead l = lead();
        service.book(l, LocalDateTime.now().plusHours(3), "Demo class", sneha);
        Demo d = booked(l, LocalDateTime.now().minusHours(1));
        when(leads.findById("l1")).thenReturn(Optional.of(l));

        service.mark(d, false, null, sneha);

        assertFalse(d.getAttended());
        assertEquals(Stage.DEMO_BOOKED, l.getStage(), "the stage must not fall back on a missed class");
        assertNotEquals(Stage.LOST, l.getStage());
        assertEquals(LocalDate.now(), l.getNextTouchOn(), "offer another slot the same day");
        assertEquals(Grade.WARM, l.getGrade(), "someone who booked a demo is at least warm");
    }

    @Test
    @DisplayName("a demo cannot be marked twice")
    void doubleMarkingIsRefused() {
        Lead l = lead();
        Demo d = booked(l, LocalDateTime.now().minusHours(2));
        d.setAttended(true);
        when(leads.findById("l1")).thenReturn(Optional.of(l));

        ResponseStatusException e = assertThrows(ResponseStatusException.class,
                () -> service.mark(d, false, null, sneha));
        assertEquals(409, e.getStatusCode().value());
    }

    @Test
    @DisplayName("cancelling returns the lead to the pipeline rather than stranding it")
    void cancellingRebooks() {
        Lead l = lead();
        service.book(l, LocalDateTime.now().plusDays(1), "Demo class", sneha);
        Demo d = booked(l, LocalDateTime.now().plusDays(1));
        when(leads.findById("l1")).thenReturn(Optional.of(l));

        service.cancel(d, "Student had an exam", sneha);

        assertEquals(Stage.CONTACTED, l.getStage());
        assertEquals(LocalDate.now().plusDays(1), l.getNextTouchOn());
        verify(demos).delete(d);
    }

    @Test
    @DisplayName("an unmarked past demo is distinguishable from one that has not happened yet")
    void unmarkedPastDemosAreIdentifiable() {
        Demo past = booked(lead(), LocalDateTime.now().minusDays(1));
        Demo future = booked(lead(), LocalDateTime.now().plusDays(1));

        assertTrue(past.isAwaitingMarking());
        assertFalse(future.isAwaitingMarking());
        assertTrue(future.isUpcoming());
        assertFalse(past.isUpcoming());
    }
}
