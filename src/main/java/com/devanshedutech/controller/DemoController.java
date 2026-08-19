package com.devanshedutech.controller;

import com.devanshedutech.dto.DemoDTOs.*;
import com.devanshedutech.model.Demo;
import com.devanshedutech.model.Lead;
import com.devanshedutech.model.User;
import com.devanshedutech.repository.DemoRepository;
import com.devanshedutech.repository.LeadRepository;
import com.devanshedutech.security.AccessService;
import com.devanshedutech.service.DemoService;
import com.devanshedutech.service.LeadLifecycleService.Actor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.time.LocalDate;
import java.util.List;

/**
 * Demo classes and campus visits.
 *
 * <p>Ownership is checked against the demo's lead rather than the demo itself, so a counsellor
 * cannot reach somebody else's student through the calendar.</p>
 */
@RestController
@RequestMapping("/api/demos")
public class DemoController {

    private final DemoRepository demoRepository;
    private final LeadRepository leadRepository;
    private final DemoService demos;
    private final AccessService access;

    public DemoController(DemoRepository demoRepository, LeadRepository leadRepository,
                          DemoService demos, AccessService access) {
        this.demoRepository = demoRepository;
        this.leadRepository = leadRepository;
        this.demos = demos;
        this.access = access;
    }

    /** The calendar, defaulting to the current week. */
    @GetMapping
    @PreAuthorize("hasAnyAuthority('PERM_LEAD_VIEW_ALL','PERM_LEAD_VIEW_OWN')")
    public ResponseEntity<DemoBoardResponse> list(
            Authentication auth,
            @RequestParam(required = false) LocalDate from,
            @RequestParam(required = false) LocalDate to) {

        LocalDate start = from != null ? from : LocalDate.now().with(java.time.DayOfWeek.MONDAY);
        LocalDate end = to != null ? to : start.plusDays(6);
        if (end.isBefore(start)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "The end date is before the start date.");
        }

        List<Demo> scheduled = demos.between(start, end).stream().filter(d -> visible(auth, d)).toList();
        List<Demo> unmarked = demos.awaitingMarking().stream().filter(d -> visible(auth, d)).toList();

        long attended = scheduled.stream().filter(d -> Boolean.TRUE.equals(d.getAttended())).count();
        long marked = scheduled.stream().filter(d -> d.getAttended() != null).count();

        return ResponseEntity.ok(DemoBoardResponse.builder()
                .from(start).to(end)
                .demos(scheduled.stream().map(this::toResponse).toList())
                .awaitingMarking(unmarked.stream().map(this::toResponse).toList())
                .scheduled(scheduled.size())
                .attended(attended)
                // Withheld until something has been marked, rather than reporting 0% for a week
                // whose demos have not happened yet.
                .attendanceRate(marked == 0 ? null : Math.round(1000.0 * attended / marked) / 10.0)
                .build());
    }

    @PostMapping
    @PreAuthorize("hasAuthority('PERM_LEAD_EDIT')")
    public ResponseEntity<DemoResponse> book(@RequestBody BookDemoRequest request, Authentication auth) {
        Lead lead = leadFor(request.getLeadId(), auth);
        return ResponseEntity.status(HttpStatus.CREATED).body(toResponse(
                demos.book(lead, request.getScheduledAt(), request.getMode(), actor(auth))));
    }

    @PostMapping("/{id}/mark")
    @PreAuthorize("hasAuthority('PERM_LEAD_EDIT')")
    public ResponseEntity<DemoResponse> mark(@PathVariable String id,
                                             @RequestBody MarkDemoRequest request,
                                             Authentication auth) {
        Demo demo = find(id, auth);
        if (request.getAttended() == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Say whether the student attended.");
        }
        return ResponseEntity.ok(toResponse(
                demos.mark(demo, request.getAttended(), request.getFeedback(), actor(auth))));
    }

    @DeleteMapping("/{id}")
    @PreAuthorize("hasAuthority('PERM_LEAD_EDIT')")
    public ResponseEntity<Void> cancel(@PathVariable String id,
                                       @RequestParam(required = false) String reason,
                                       Authentication auth) {
        demos.cancel(find(id, auth), reason, actor(auth));
        return ResponseEntity.noContent().build();
    }

    // ------------------------------------------------------------------

    private Demo find(String id, Authentication auth) {
        Demo demo = demoRepository.findById(id).orElseThrow(() ->
                new ResponseStatusException(HttpStatus.NOT_FOUND, "That demo no longer exists."));
        if (!visible(auth, demo)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "That demo no longer exists.");
        }
        return demo;
    }

    /** A demo is visible when its lead is. Otherwise the calendar would be a back door. */
    private boolean visible(Authentication auth, Demo demo) {
        return leadRepository.findById(demo.getLeadId())
                .map(l -> access.ownsOrSeesAll(auth, l.getAssignedToId()))
                .orElse(false);
    }

    private Lead leadFor(String leadId, Authentication auth) {
        Lead lead = leadRepository.findById(leadId).orElseThrow(() ->
                new ResponseStatusException(HttpStatus.NOT_FOUND, "That lead no longer exists."));
        if (!access.ownsOrSeesAll(auth, lead.getAssignedToId())) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "That lead no longer exists.");
        }
        return lead;
    }

    private Actor actor(Authentication auth) {
        User u = access.requireUser(auth);
        return new Actor(u.getId(), u.displayNameOrEmail());
    }

    private DemoResponse toResponse(Demo d) {
        return DemoResponse.builder()
                .id(d.getId())
                .leadId(d.getLeadId())
                .studentName(d.getStudentName())
                .course(d.getCourse())
                .scheduledAt(d.getScheduledAt())
                .mode(d.getMode())
                .attended(d.getAttended())
                .feedback(d.getFeedback())
                .awaitingMarking(d.isAwaitingMarking())
                .build();
    }
}
