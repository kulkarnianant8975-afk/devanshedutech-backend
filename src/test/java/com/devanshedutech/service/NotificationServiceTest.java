package com.devanshedutech.service;

import com.devanshedutech.model.Lead;
import com.devanshedutech.model.Notification;
import com.devanshedutech.repository.DemoRepository;
import com.devanshedutech.repository.LeadRepository;
import com.devanshedutech.repository.NotificationRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

/**
 * Notification deduplication.
 *
 * <p>This is the whole feature, really. A morning sweep that runs over the same overdue leads
 * every day will, without a key, hand a counsellor fifty copies of a notice they read on Monday
 * — and a person who learns that notifications are noise stops reading the one that mattered.</p>
 */
class NotificationServiceTest {

    private NotificationRepository notifications;
    private NotificationService service;
    private Set<String> stored;

    @BeforeEach
    void setUp() {
        notifications = mock(NotificationRepository.class);
        stored = new HashSet<>();

        // A stand-in for the unique-per-recipient behaviour the dedupe key gives us.
        when(notifications.existsByRecipientIdAndDedupeKey(anyString(), anyString()))
                .thenAnswer(i -> stored.contains(i.getArgument(0) + "|" + i.getArgument(1)));
        when(notifications.save(any())).thenAnswer(i -> {
            Notification n = i.getArgument(0);
            stored.add(n.getRecipientId() + "|" + n.getDedupeKey());
            return n;
        });

        service = new NotificationService(notifications, mock(LeadRepository.class), mock(DemoRepository.class));
    }

    @Test
    @DisplayName("the same fact is announced once, however often the sweep runs")
    void repeatedSweepsDoNotRepeatNotices() {
        assertTrue(service.notify("u1", "FOLLOW_UP_DUE", "due:l1:2026-08-19", "Due today", null, "l1"));
        assertFalse(service.notify("u1", "FOLLOW_UP_DUE", "due:l1:2026-08-19", "Due today", null, "l1"));
        assertFalse(service.notify("u1", "FOLLOW_UP_DUE", "due:l1:2026-08-19", "Due today", null, "l1"));

        verify(notifications, times(1)).save(any());
    }

    @Test
    @DisplayName("a lead that stays overdue is raised again the next day, not silenced")
    void ongoingProblemsAreRaisedDaily() {
        // Keyed by the day it is noticed, so a genuinely unresolved problem keeps surfacing
        // rather than being announced once and forgotten about.
        assertTrue(service.notify("u1", "FOLLOW_UP_MISSED", "missed:l1:2026-08-19", "2 days late", null, "l1"));
        assertTrue(service.notify("u1", "FOLLOW_UP_MISSED", "missed:l1:2026-08-20", "3 days late", null, "l1"));
        verify(notifications, times(2)).save(any());
    }

    @Test
    @DisplayName("two people can each be told about the same lead")
    void dedupeIsPerRecipient() {
        assertTrue(service.notify("u1", "LEAD_ASSIGNED", "assigned:l1:u1", "Yours now", null, "l1"));
        assertTrue(service.notify("u2", "LEAD_ASSIGNED", "assigned:l1:u2", "Yours now", null, "l1"));
        verify(notifications, times(2)).save(any());
    }

    @Test
    @DisplayName("an unassigned lead notifies nobody rather than failing")
    void unassignedLeadsAreSkipped() {
        assertFalse(service.notify(null, "FOLLOW_UP_DUE", "due:l1", "Due", null, "l1"));
        assertFalse(service.notify("  ", "FOLLOW_UP_DUE", "due:l1", "Due", null, "l1"));
        verify(notifications, never()).save(any());
    }

    @Test
    @DisplayName("assignment tells the new owner who handed it over and what it is")
    void assignmentNoticeIsUseful() {
        Lead lead = Lead.builder().id("l1").fullName("Rohit Deshmukh")
                .courseInterested("Data Analytics").cityName("Parbhani").build();

        service.leadAssigned(lead, "u2", "Aditya Jadhav");

        verify(notifications).save(argThat((Notification n) ->
                n.getTitle().contains("Rohit Deshmukh")
                && n.getBody().contains("Aditya Jadhav")
                && n.getBody().contains("Data Analytics")
                && n.getBody().contains("Parbhani")
                && "l1".equals(n.getLeadId())));
    }
}
