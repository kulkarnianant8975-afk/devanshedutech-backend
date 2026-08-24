package com.devanshedutech.controller;

import com.devanshedutech.crm.LeadMapper;
import com.devanshedutech.dto.LeadDTOs.LeadPatchRequest;
import com.devanshedutech.model.Lead;
import com.devanshedutech.model.Permission;
import com.devanshedutech.model.User;
import com.devanshedutech.model.crm.Stage;
import com.devanshedutech.repository.LeadRepository;
import com.devanshedutech.repository.BatchRepository;
import com.devanshedutech.repository.UserRepository;
import com.devanshedutech.security.AccessService;
import com.devanshedutech.service.LeadCaptureService;
import com.devanshedutech.service.LeadLadderScheduler;
import com.devanshedutech.service.LeadLadderService;
import com.devanshedutech.service.LeadLifecycleService;
import com.devanshedutech.service.NotificationService;
import com.devanshedutech.controller.RateLimiter;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.security.core.Authentication;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * Ownership scoping — the rule that makes a counsellor role mean anything.
 *
 * <p>The failure this guards against is subtle: a client that simply asks for a lead id it was
 * never shown. Filtering in the response would not help, so the check happens on the way in,
 * and a lead somebody may not see returns 404 rather than 403 — confirming it exists would
 * itself leak that a particular student enquired.</p>
 */
class LeadControllerAccessTest {

    private LeadRepository leads;
    private AccessService access;
    private LeadLifecycleService lifecycle;
    private LeadController controller;
    private Authentication auth;

    private static final String SNEHA = "u-sneha";
    private static final String ADITYA = "u-aditya";

    @BeforeEach
    void setUp() {
        leads = mock(LeadRepository.class);
        access = mock(AccessService.class);
        lifecycle = mock(LeadLifecycleService.class);
        auth = mock(Authentication.class);
        LeadCaptureService capture = mock(LeadCaptureService.class);
        UserRepository users = mock(UserRepository.class);
        BatchRepository batchRepo = mock(BatchRepository.class);
        LeadLadderService ladder = mock(LeadLadderService.class);
        LeadLadderScheduler ladderScheduler = mock(LeadLadderScheduler.class);
        NotificationService notifications = mock(NotificationService.class);
        RateLimiter rateLimiter = mock(RateLimiter.class);
        when(ladder.lane(any())).thenReturn(List.of());
        when(users.findAll()).thenReturn(List.of());

        when(leads.save(any())).thenAnswer(i -> i.getArgument(0));
        when(lifecycle.timeline(any())).thenReturn(List.of());

        User sneha = User.builder().id(SNEHA).email("sneha@x.com").displayName("Sneha").build();
        when(access.requireUser(any())).thenReturn(sneha);

        controller = new LeadController(leads, capture, lifecycle, new LeadMapper(users, batchRepo), access, ladder, ladderScheduler, notifications, rateLimiter,
                mock(com.devanshedutech.service.AssetTrackingService.class),
                mock(com.devanshedutech.repository.LeadActivityRepository.class));
    }

    private Lead leadOwnedBy(String owner) {
        return Lead.builder().id("l1").fullName("Rohit Deshmukh").assignedToId(owner)
                .stage(Stage.CONTACTED).optedOut(false).updatesOnly(false).build();
    }

    /** A counsellor: restricted to their own leads. */
    private void asCounsellor() {
        when(access.ownerFilter(auth)).thenReturn(SNEHA);
        when(access.ownsOrSeesAll(eq(auth), any()))
                .thenAnswer(i -> SNEHA.equals(i.getArgument(1)));
    }

    /** A manager: sees the whole pipeline. */
    private void asManager() {
        when(access.ownerFilter(auth)).thenReturn(null);
        when(access.ownsOrSeesAll(eq(auth), any())).thenReturn(true);
    }

    @Test
    @DisplayName("a counsellor asking for someone else's lead gets 404, not a leak")
    void counsellorCannotReadAnotherLead() {
        asCounsellor();
        when(leads.findById("l1")).thenReturn(Optional.of(leadOwnedBy(ADITYA)));

        ResponseStatusException e = assertThrows(ResponseStatusException.class,
                () -> controller.detail("l1", auth));
        assertEquals(404, e.getStatusCode().value());
        assertFalse(e.getReason().toLowerCase().contains("permission"),
                "the message must not hint that the lead exists");
    }

    @Test
    void counsellorCanReadTheirOwnLead() {
        asCounsellor();
        when(leads.findById("l1")).thenReturn(Optional.of(leadOwnedBy(SNEHA)));
        assertEquals(200, controller.detail("l1", auth).getStatusCode().value());
    }

    @Test
    void managerCanReadAnyLead() {
        asManager();
        when(leads.findById("l1")).thenReturn(Optional.of(leadOwnedBy(ADITYA)));
        assertEquals(200, controller.detail("l1", auth).getStatusCode().value());
    }

    @Test
    @DisplayName("a counsellor cannot widen their view by asking for another owner")
    void ownerParameterCannotBeUsedToEscapeScope() {
        asCounsellor();
        when(leads.findAll(any(Specification.class), any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of()));

        controller.list(auth, null, null, ADITYA, null, false, false, 0, 25);

        // The forced filter comes from permissions, so the query is narrowed in the database
        // rather than the caller being trusted to ask only for their own leads.
        verify(access).ownerFilter(auth);
        verify(leads).findAll(any(Specification.class), any(Pageable.class));
    }

    @Test
    @DisplayName("a counsellor cannot write to a lead that is not theirs")
    void counsellorCannotPatchAnotherLead() {
        asCounsellor();
        when(leads.findById("l1")).thenReturn(Optional.of(leadOwnedBy(ADITYA)));

        ResponseStatusException e = assertThrows(ResponseStatusException.class,
                () -> controller.patch("l1", new LeadPatchRequest(), auth));
        assertEquals(404, e.getStatusCode().value());
        verify(leads, never()).save(any());
    }

    @Test
    @DisplayName("reassignment needs its own permission, so a counsellor cannot take a lead")
    void reassignmentIsSeparatelyPermissioned() {
        asManager();
        when(leads.findById("l1")).thenReturn(Optional.of(leadOwnedBy(ADITYA)));
        doThrow(new ResponseStatusException(org.springframework.http.HttpStatus.FORBIDDEN,
                "Your role does not allow this action."))
                .when(access).require(auth, Permission.LEAD_ASSIGN);

        LeadPatchRequest request = new LeadPatchRequest();
        request.setAssignedToId(SNEHA);

        ResponseStatusException e = assertThrows(ResponseStatusException.class,
                () -> controller.patch("l1", request, auth));
        assertEquals(403, e.getStatusCode().value());
    }

    @Test
    @DisplayName("a student who opted out cannot be worked on any further")
    void optedOutLeadsAreReadOnly() {
        asManager();
        Lead lead = leadOwnedBy(SNEHA);
        lead.setOptedOut(true);
        when(leads.findById("l1")).thenReturn(Optional.of(lead));

        ResponseStatusException e = assertThrows(ResponseStatusException.class,
                () -> controller.patch("l1", new LeadPatchRequest(), auth));
        assertEquals(409, e.getStatusCode().value());

        // Reading the record is still fine; only further contact is refused.
        assertEquals(200, controller.detail("l1", auth).getStatusCode().value());
    }

    @Test
    @DisplayName("a next touch cannot be set in the past")
    void nextTouchMustBeTodayOrLater() {
        asManager();
        when(leads.findById("l1")).thenReturn(Optional.of(leadOwnedBy(SNEHA)));

        LeadPatchRequest request = new LeadPatchRequest();
        request.setNextTouchOn(java.time.LocalDate.now().minusDays(1));

        ResponseStatusException e = assertThrows(ResponseStatusException.class,
                () -> controller.patch("l1", request, auth));
        assertEquals(400, e.getStatusCode().value());
    }

    @Test
    @DisplayName("moving a lead to Lost from the pipeline still demands a reason")
    void lostAlwaysNeedsAReason() {
        asManager();
        when(leads.findById("l1")).thenReturn(Optional.of(leadOwnedBy(SNEHA)));

        LeadPatchRequest request = new LeadPatchRequest();
        request.setStage(Stage.LOST);

        ResponseStatusException e = assertThrows(ResponseStatusException.class,
                () -> controller.patch("l1", request, auth));
        assertEquals(400, e.getStatusCode().value());
    }

    @Test
    void missingLeadIs404() {
        asManager();
        when(leads.findById("nope")).thenReturn(Optional.empty());
        ResponseStatusException e = assertThrows(ResponseStatusException.class,
                () -> controller.detail("nope", auth));
        assertEquals(404, e.getStatusCode().value());
    }
}
