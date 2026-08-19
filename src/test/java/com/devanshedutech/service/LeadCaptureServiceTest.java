package com.devanshedutech.service;

import com.devanshedutech.dto.LeadDTOs.LeadRequest;
import com.devanshedutech.model.Lead;
import com.devanshedutech.model.crm.LeadSource;
import com.devanshedutech.model.crm.Stage;
import com.devanshedutech.model.crm.StudentBackground;
import com.devanshedutech.repository.LeadActivityRepository;
import com.devanshedutech.repository.LeadRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.web.server.ResponseStatusException;

import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

class LeadCaptureServiceTest {

    private LeadRepository leads;
    private LeadCaptureService capture;

    @BeforeEach
    void setUp() {
        leads = mock(LeadRepository.class);
        LeadActivityRepository activities = mock(LeadActivityRepository.class);
        when(leads.save(any())).thenAnswer(i -> i.getArgument(0));
        when(activities.save(any())).thenAnswer(i -> i.getArgument(0));
        when(leads.findByPhoneNormalized(anyString())).thenReturn(List.of());
        // A roster that never has anyone on duty, so these tests keep asserting capture itself.
        // Assignment has its own test; mixing the two would hide which one broke.
        DutyRosterService roster = mock(DutyRosterService.class);
        when(roster.assignIfUnowned(any(), any())).thenReturn(false);
        capture = new LeadCaptureService(leads, new LeadLifecycleService(leads, activities, TestCalendars.openEveryDay()), roster);
    }

    private LeadRequest request() {
        return LeadRequest.builder()
                .fullName("Rohit Deshmukh")
                .mobileNumber("+91 98765 43210")
                .cityName("Parbhani")
                .education("Final year BCA")
                .courseInterested("Data Analytics")
                .build();
    }

    // ---------------- phone handling ----------------

    @ParameterizedTest
    @DisplayName("the same number written any way collapses to one key")
    @ValueSource(strings = {"+91 98765 43210", "09876543210", "9876543210", "+91-98765-43210", "91 98765 43210"})
    void phoneVariantsNormaliseTogether(String typed) {
        assertEquals("9876543210", Lead.normalizePhone(typed));
    }

    @Test
    void unusablePhoneNumbersAreRejected() {
        LeadRequest r = request();
        r.setMobileNumber("12345");
        ResponseStatusException e = assertThrows(ResponseStatusException.class,
                () -> capture.capture(r, LeadSource.WEBSITE_FORM));
        assertEquals(400, e.getStatusCode().value());
    }

    @Test
    void nameIsRequired() {
        LeadRequest r = request();
        r.setFullName("   ");
        assertThrows(ResponseStatusException.class, () -> capture.capture(r, LeadSource.WEBSITE_FORM));
    }

    // ---------------- capture ----------------

    @Test
    @DisplayName("a new enquiry lands as New, ungraded and unassigned")
    void newEnquiryStartsClean() {
        var result = capture.capture(request(), LeadSource.WEBSITE_FORM);
        Lead l = result.lead();

        assertFalse(result.duplicate());
        assertEquals(Stage.NEW, l.getStage());
        assertNull(l.getGrade(), "grading is a counsellor's judgement, never guessed at capture");
        assertNull(l.getAssignedToId());
        assertNull(l.getNextTouchOn());
        assertEquals("9876543210", l.getPhoneNormalized());
        assertEquals(StudentBackground.FINAL_YEAR, l.getBackground());
    }

    @Test
    @DisplayName("UTM tags decide the source when the form does not state one")
    void utmDrivesAttribution() {
        LeadRequest r = request();
        r.setUtmSource("instagram");
        r.setUtmMedium("cpc");
        r.setUtmCampaign("parbhani-aug");

        Lead l = capture.capture(r, LeadSource.WEBSITE_FORM).lead();
        assertEquals(LeadSource.INSTAGRAM_AD, l.getSource());
        assertEquals("parbhani-aug", l.getUtmCampaign());
    }

    @Test
    @DisplayName("an explicit source beats the UTM tags")
    void explicitSourceWins() {
        LeadRequest r = request();
        r.setSource("COLLEGE_SEMINAR");
        r.setUtmSource("instagram");
        assertEquals(LeadSource.COLLEGE_SEMINAR, capture.capture(r, LeadSource.WEBSITE_FORM).lead().getSource());
    }

    @Test
    @DisplayName("a referred enquiry is attributed to the referral, not to the page it arrived on")
    void referralIsDetected() {
        LeadRequest r = request();
        r.setReferredById("student-42");
        assertEquals(LeadSource.REFERRAL, capture.capture(r, LeadSource.WEBSITE_FORM).lead().getSource());
    }

    @Test
    @DisplayName("an unknown origin is recorded as Other rather than credited to a channel")
    void unknownSourceIsNotGuessed() {
        LeadRequest r = request();
        r.setUtmSource("some-newsletter");
        assertEquals(LeadSource.WEBSITE_FORM, capture.capture(r, LeadSource.WEBSITE_FORM).lead().getSource());
        assertEquals(LeadSource.OTHER, capture.capture(r, null).lead().getSource());
    }

    // ---------------- duplicates ----------------

    @Test
    @DisplayName("a repeat enquiry folds into the existing lead instead of creating a second one")
    void repeatEnquiryIsMerged() {
        Lead existing = Lead.builder()
                .id("existing").fullName("Rohit Deshmukh").mobileNumber("9876543210")
                .phoneNormalized("9876543210").cityName("Parbhani")
                .courseInterested("Data Analytics").stage(Stage.CONTACTED)
                .createdAt(LocalDateTime.now().minusDays(3))
                .optedOut(false).updatesOnly(false).callAttempts(1)
                .build();
        when(leads.findByPhoneNormalized("9876543210")).thenReturn(List.of(existing));

        var result = capture.capture(request(), LeadSource.WEBSITE_FORM);

        assertTrue(result.duplicate());
        assertEquals("existing", result.lead().getId());
        assertEquals(java.time.LocalDate.now(), result.lead().getNextTouchOn(),
                "enquiring again is a strong signal and pulls the next touch to today");
    }

    @Test
    @DisplayName("a second submission never erases detail a counsellor already confirmed")
    void mergeDoesNotOverwriteKnownDetail() {
        Lead existing = Lead.builder()
                .id("existing").fullName("Rohit Deshmukh").phoneNormalized("9876543210")
                .cityName("Parbhani").email("rohit@example.com").courseInterested("Data Analytics")
                .stage(Stage.CONTACTED).createdAt(LocalDateTime.now().minusDays(1))
                .optedOut(false).updatesOnly(false)
                .build();
        when(leads.findByPhoneNormalized("9876543210")).thenReturn(List.of(existing));

        LeadRequest sparse = request();
        sparse.setCityName(null);
        sparse.setEmail(null);

        Lead merged = capture.capture(sparse, LeadSource.WEBSITE_FORM).lead();
        assertEquals("Parbhani", merged.getCityName());
        assertEquals("rohit@example.com", merged.getEmail());
    }

    @Test
    @DisplayName("an enquiry long after the last one starts a fresh lead")
    void oldLeadsDoNotSwallowNewEnquiries() {
        Lead ancient = Lead.builder()
                .id("ancient").phoneNormalized("9876543210")
                .createdAt(LocalDateTime.now().minusMonths(8))
                .stage(Stage.LOST).optedOut(false).updatesOnly(true)
                .build();
        when(leads.findByPhoneNormalized("9876543210")).thenReturn(List.of(ancient));

        var result = capture.capture(request(), LeadSource.WEBSITE_FORM);
        assertFalse(result.duplicate());
        assertNotEquals("ancient", result.lead().getId());
    }
}
