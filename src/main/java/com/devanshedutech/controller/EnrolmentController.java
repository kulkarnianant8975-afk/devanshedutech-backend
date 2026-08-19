package com.devanshedutech.controller;

import com.devanshedutech.crm.LeadMapper;
import com.devanshedutech.dto.LeadDTOs.LeadResponse;
import com.devanshedutech.model.Lead;
import com.devanshedutech.model.User;
import com.devanshedutech.repository.LeadRepository;
import com.devanshedutech.security.AccessService;
import com.devanshedutech.service.EnrolmentService;
import com.devanshedutech.service.LeadLifecycleService.Actor;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.Map;

/** Enrolling a student, and recording who referred whom. */
@RestController
@RequestMapping("/api/leads")
public class EnrolmentController {

    private final LeadRepository leads;
    private final EnrolmentService enrolments;
    private final LeadMapper mapper;
    private final AccessService access;

    public EnrolmentController(LeadRepository leads, EnrolmentService enrolments,
                               LeadMapper mapper, AccessService access) {
        this.leads = leads;
        this.enrolments = enrolments;
        this.mapper = mapper;
        this.access = access;
    }

    /** The next intake to offer this student, so a counsellor never has to go looking. */
    @GetMapping("/{id}/next-batch")
    @PreAuthorize("hasAnyAuthority('PERM_LEAD_VIEW_ALL','PERM_LEAD_VIEW_OWN')")
    public ResponseEntity<Map<String, Object>> nextBatch(@PathVariable String id, Authentication auth) {
        Lead lead = readable(id, auth);
        return ResponseEntity.ok(enrolments.nextBatchFor(lead)
                .map(b -> Map.<String, Object>of(
                        "id", b.getId(), "name", b.getName(),
                        "startDate", b.getStartDate().toString(),
                        "description", b.describe()))
                .orElse(Map.of()));
    }

    @PostMapping("/{id}/enrol")
    @PreAuthorize("hasAuthority('PERM_LEAD_EDIT')")
    public ResponseEntity<LeadResponse> enrol(@PathVariable String id,
                                              @RequestBody EnrolRequest request,
                                              Authentication auth) {
        Lead lead = readable(id, auth);
        return ResponseEntity.ok(mapper.toResponse(enrolments.enrol(
                lead, request.getBatchId(), request.getFeePlan(), request.getPaymentStatus(), actor(auth))));
    }

    /** Records that this student referred somebody already in the pipeline. */
    @PostMapping("/{id}/referrals")
    @PreAuthorize("hasAuthority('PERM_LEAD_EDIT')")
    public ResponseEntity<LeadResponse> refer(@PathVariable String id,
                                              @RequestBody ReferralRequest request,
                                              Authentication auth) {
        Lead referrer = readable(id, auth);
        Lead referred = readable(request.getReferredLeadId(), auth);
        return ResponseEntity.ok(mapper.toResponse(
                enrolments.recordReferral(referrer, referred, actor(auth))));
    }

    @GetMapping("/{id}/referrals")
    @PreAuthorize("hasAnyAuthority('PERM_LEAD_VIEW_ALL','PERM_LEAD_VIEW_OWN')")
    public ResponseEntity<List<LeadResponse>> referrals(@PathVariable String id, Authentication auth) {
        readable(id, auth);
        return ResponseEntity.ok(enrolments.referralsBy(id).stream().map(mapper::toResponse).toList());
    }

    private Actor actor(Authentication auth) {
        User u = access.requireUser(auth);
        return new Actor(u.getId(), u.displayNameOrEmail());
    }

    private Lead readable(String id, Authentication auth) {
        Lead lead = leads.findById(id).orElseThrow(() ->
                new ResponseStatusException(HttpStatus.NOT_FOUND, "That lead no longer exists."));
        if (!access.ownsOrSeesAll(auth, lead.getAssignedToId())) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "That lead no longer exists.");
        }
        return lead;
    }

    @Data @Builder @NoArgsConstructor @AllArgsConstructor
    public static class EnrolRequest {
        private String batchId;
        private String feePlan;
        private String paymentStatus;
    }

    @Data @Builder @NoArgsConstructor @AllArgsConstructor
    public static class ReferralRequest {
        private String referredLeadId;
    }
}
