package com.devanshedutech.controller;

import com.devanshedutech.crm.LeadMapper;
import com.devanshedutech.crm.LeadSpecifications;
import com.devanshedutech.dto.LeadDTOs.*;
import com.devanshedutech.model.Lead;
import com.devanshedutech.model.Permission;
import com.devanshedutech.model.User;
import com.devanshedutech.model.crm.*;
import com.devanshedutech.repository.LeadRepository;
import com.devanshedutech.security.AccessService;
import com.devanshedutech.service.LeadCaptureService;
import com.devanshedutech.service.LeadLadderScheduler;
import com.devanshedutech.service.LeadLadderService;
import com.devanshedutech.service.LeadLifecycleService;
import com.devanshedutech.service.LeadLifecycleService.Actor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.time.LocalDate;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;

/**
 * The pipeline API.
 *
 * <p>Every read and write is scoped by {@link AccessService#ownerFilter}: a counsellor's
 * queries are narrowed to their own leads in the database, and single-lead operations
 * re-check ownership rather than trusting that the client only asked for permitted ids.</p>
 */
@RestController
@RequestMapping("/api/leads")
public class LeadController {

    private final LeadRepository leadRepository;
    private final LeadCaptureService capture;
    private final LeadLifecycleService lifecycle;
    private final LeadMapper mapper;
    private final AccessService access;
    private final LeadLadderService ladder;
    private final LeadLadderScheduler ladderScheduler;

    public LeadController(LeadRepository leadRepository,
                          LeadCaptureService capture,
                          LeadLifecycleService lifecycle,
                          LeadMapper mapper,
                          AccessService access,
                          LeadLadderService ladder,
                          LeadLadderScheduler ladderScheduler) {
        this.leadRepository = leadRepository;
        this.capture = capture;
        this.lifecycle = lifecycle;
        this.mapper = mapper;
        this.access = access;
        this.ladder = ladder;
        this.ladderScheduler = ladderScheduler;
    }

    // ==================================================================
    // Public capture
    // ==================================================================

    /**
     * The public enquiry form. Deliberately unauthenticated — this is the front door of the
     * business — and deliberately forgiving about missing detail, because a rejected enquiry is
     * a lost student.
     */
    @PostMapping
    public ResponseEntity<CaptureResponse> createLead(@RequestBody LeadRequest request) {
        LeadCaptureService.Captured result = capture.capture(request, LeadSource.WEBSITE_FORM);
        Lead lead = result.lead();
        return ResponseEntity.status(result.duplicate() ? HttpStatus.OK : HttpStatus.CREATED)
                .body(CaptureResponse.builder()
                        .id(lead.getId())
                        .fullName(lead.getFullName())
                        .duplicate(result.duplicate())
                        .message(result.duplicate()
                                ? "We already have your enquiry — a counsellor will call you shortly."
                                : "Thanks! A counsellor will contact you shortly.")
                        .build());
    }

    // ==================================================================
    // Reading the pipeline
    // ==================================================================

    @GetMapping
    @PreAuthorize("hasAnyAuthority('PERM_LEAD_VIEW_ALL','PERM_LEAD_VIEW_OWN')")
    public ResponseEntity<PageResponse<LeadResponse>> list(
            Authentication auth,
            @RequestParam(required = false) Stage stage,
            @RequestParam(required = false) Grade grade,
            @RequestParam(required = false) String owner,
            @RequestParam(required = false) String q,
            @RequestParam(defaultValue = "false") boolean unassignedOnly,
            @RequestParam(defaultValue = "false") boolean openOnly,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "25") int size) {

        String scope = resolveOwnerScope(auth, owner);
        int capped = Math.min(Math.max(size, 1), 200);

        Specification<Lead> spec = LeadSpecifications.all(
                LeadSpecifications.ownedBy(scope),
                LeadSpecifications.stageIs(stage),
                LeadSpecifications.gradeIs(grade),
                LeadSpecifications.matching(q),
                unassignedOnly ? LeadSpecifications.unassigned() : null,
                openOnly ? LeadSpecifications.open() : null);

        Page<Lead> found = leadRepository.findAll(spec,
                PageRequest.of(Math.max(page, 0), capped, Sort.by(Sort.Direction.DESC, "createdAt")));

        var names = mapper.ownerNames();
        return ResponseEntity.ok(PageResponse.<LeadResponse>builder()
                .items(found.getContent().stream().map(l -> mapper.toResponse(l, names)).toList())
                .total(found.getTotalElements())
                .page(found.getNumber())
                .size(found.getSize())
                .totalPages(found.getTotalPages())
                .build());
    }

    @GetMapping("/{id}")
    @PreAuthorize("hasAnyAuthority('PERM_LEAD_VIEW_ALL','PERM_LEAD_VIEW_OWN')")
    public ResponseEntity<LeadDetailResponse> detail(@PathVariable String id, Authentication auth) {
        Lead lead = readable(id, auth);
        LeadResponse body = mapper.toResponse(lead);
        var lane = lead.getGrade() == null ? List.<com.devanshedutech.model.LadderStep>of()
                                           : ladder.lane(lead.getGrade());
        body.setLadderTotal(lane.size());
        lane.stream()
                .filter(s -> s.getStepNo().equals(lead.getLadderStep()))
                .findFirst()
                .ifPresent(s -> body.setLadderCurrentTitle(s.getTitle()));

        return ResponseEntity.ok(LeadDetailResponse.builder()
                .lead(body)
                .activities(lifecycle.timeline(id).stream().map(mapper::toResponse).toList())
                .ladder(lane.stream().map(s -> mapper.toResponse(s, lead.getLadderStep())).toList())
                .build());
    }

    /** The counsellor's daily queues, in the order the SOP's checklist asks for them. */
    @GetMapping("/my-day")
    @PreAuthorize("hasAnyAuthority('PERM_LEAD_VIEW_ALL','PERM_LEAD_VIEW_OWN')")
    public ResponseEntity<MyDayResponse> myDay(Authentication auth,
                                               @RequestParam(required = false) String owner) {
        String scope = resolveOwnerScope(auth, owner);
        LocalDate today = LocalDate.now();
        Specification<Lead> mine = LeadSpecifications.ownedBy(scope);

        List<Lead> awaiting = find(LeadSpecifications.all(mine, LeadSpecifications.awaitingFirstReply()));
        List<Lead> overdue = find(LeadSpecifications.all(mine, LeadSpecifications.open(),
                LeadSpecifications.nextTouchBefore(today)));
        List<Lead> due = find(LeadSpecifications.all(mine, LeadSpecifications.open(),
                LeadSpecifications.nextTouchOn(today)));
        List<Lead> blank = find(LeadSpecifications.all(mine, LeadSpecifications.open(),
                LeadSpecifications.blankNextTouch()));

        var names = mapper.ownerNames();
        return ResponseEntity.ok(MyDayResponse.builder()
                .awaitingFirstReply(map(awaiting, names))
                .overdue(map(overdue, names))
                .dueToday(map(due, names))
                .blankNextTouch(map(blank, names))
                .awaitingCount(awaiting.size())
                .overdueCount(overdue.size())
                .dueTodayCount(due.size())
                .blankNextTouchCount(blank.size())
                .build());
    }

    /**
     * The pipeline board: every stage with its leads, in one request.
     *
     * <p>Each column is capped and reports its true total, so a busy stage shows "showing 50 of
     * 214" rather than quietly leaving leads out. Loading a board by calling the list endpoint
     * once per stage would be seven round trips and seven chances to disagree with itself.</p>
     */
    @GetMapping("/board")
    @PreAuthorize("hasAnyAuthority('PERM_LEAD_VIEW_ALL','PERM_LEAD_VIEW_OWN')")
    public ResponseEntity<BoardResponse> board(
            Authentication auth,
            @RequestParam(required = false) String owner,
            @RequestParam(required = false) Grade grade,
            @RequestParam(required = false) String q,
            @RequestParam(defaultValue = "50") int limit) {

        String scope = resolveOwnerScope(auth, owner);
        int capped = Math.min(Math.max(limit, 1), 200);
        var names = mapper.ownerNames();

        List<BoardColumn> columns = Arrays.stream(Stage.values()).map(stage -> {
            Specification<Lead> spec = LeadSpecifications.all(
                    LeadSpecifications.ownedBy(scope),
                    LeadSpecifications.stageIs(stage),
                    LeadSpecifications.gradeIs(grade),
                    LeadSpecifications.matching(q));
            Page<Lead> page = leadRepository.findAll(spec,
                    PageRequest.of(0, capped, Sort.by(Sort.Direction.ASC, "nextTouchOn")));
            return BoardColumn.builder()
                    .stage(stage)
                    .label(stage.getLabel())
                    .leads(page.getContent().stream().map(l -> mapper.toResponse(l, names)).toList())
                    .total(page.getTotalElements())
                    .build();
        }).toList();

        return ResponseEntity.ok(new BoardResponse(columns, capped));
    }

    /** The vocabularies, so the client never hardcodes a list that can drift from the server. */
    @GetMapping("/options")
    @PreAuthorize("hasAnyAuthority('PERM_LEAD_VIEW_ALL','PERM_LEAD_VIEW_OWN')")
    public ResponseEntity<OptionsResponse> options() {
        return ResponseEntity.ok(OptionsResponse.builder()
                .stages(Arrays.stream(Stage.values())
                        .map(s -> new Option(s.name(), s.getLabel(), null)).toList())
                .grades(Arrays.stream(Grade.values())
                        .map(g -> new Option(g.name(), g.getLabel(), null)).toList())
                .sources(Arrays.stream(LeadSource.values())
                        .map(s -> new Option(s.name(), s.getLabel(), null)).toList())
                .backgrounds(Arrays.stream(StudentBackground.values())
                        .map(b -> new Option(b.name(), b.getLabel(), null)).toList())
                .outcomes(Arrays.stream(OutcomeCode.values())
                        .map(o -> new Option(o.name(), o.getLabel(), hintFor(o))).toList())
                .lostReasons(Arrays.stream(LostReason.values())
                        .map(r -> new Option(r.name(), r.getLabel(), null)).toList())
                .build());
    }

    // ==================================================================
    // Working the pipeline
    // ==================================================================

    /** Records what happened on a contact and applies the consequences the SOP prescribes. */
    @PostMapping("/{id}/outcome")
    @PreAuthorize("hasAuthority('PERM_LEAD_EDIT')")
    public ResponseEntity<LeadDetailResponse> recordOutcome(@PathVariable String id,
                                                            @RequestBody OutcomeRequest request,
                                                            Authentication auth) {
        Lead lead = writable(id, auth);
        lifecycle.applyOutcome(lead, request.getOutcome(), request.getNote(),
                request.getLostReason(), actor(auth));
        return detail(id, auth);
    }

    @PatchMapping("/{id}")
    @PreAuthorize("hasAuthority('PERM_LEAD_EDIT')")
    public ResponseEntity<LeadResponse> patch(@PathVariable String id,
                                              @RequestBody LeadPatchRequest request,
                                              Authentication auth) {
        Lead lead = writable(id, auth);
        Actor who = actor(auth);

        if (request.getStage() != null) {
            if (request.getStage() == Stage.LOST && request.getLostReason() == null
                    && lead.getLostReason() == null) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                        "Marking a lead lost needs a reason, so the institute can learn from it.");
            }
            lifecycle.moveStage(lead, request.getStage(), request.getReason(), who);
        }
        if (request.getGrade() != null) {
            lifecycle.moveGrade(lead, request.getGrade(), request.getReason(), who);
        }
        if (request.getLostReason() != null) {
            lead.setLostReason(request.getLostReason());
            lead.setLostNote(request.getLostNote());
        }
        if (request.getSource() != null) lead.setSource(request.getSource());
        if (request.getSourceDetail() != null) lead.setSourceDetail(request.getSourceDetail());
        if (request.getBackground() != null) lead.setBackground(request.getBackground());
        if (request.getCourseInterested() != null) lead.setCourseInterested(request.getCourseInterested());
        if (request.getNotes() != null) lead.setNotes(request.getNotes());

        if (request.getNextTouchOn() != null) {
            if (request.getNextTouchOn().isBefore(LocalDate.now())) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                        "The next touch has to be today or later.");
            }
            lifecycle.setNextTouch(lead, request.getNextTouchOn(), request.getNextTouchNote());
        }

        // Reassignment is a separate permission: a counsellor may work a lead without being
        // able to hand it to somebody else, or quietly take one that is not theirs.
        boolean clearing = Boolean.TRUE.equals(request.getClearOwner());
        if (clearing || request.getAssignedToId() != null) {
            access.require(auth, Permission.LEAD_ASSIGN);
            String newOwner = clearing ? null : request.getAssignedToId();
            lifecycle.assign(lead, newOwner, access.nameOf(newOwner), who);
        }

        return ResponseEntity.ok(mapper.toResponse(leadRepository.save(lead)));
    }

    /** Records a message received from the student, applying the SOP's promotion rules. */
    @PostMapping("/{id}/inbound")
    @PreAuthorize("hasAuthority('PERM_LEAD_EDIT')")
    public ResponseEntity<LeadResponse> inbound(@PathVariable String id,
                                                @RequestBody InboundRequest request,
                                                Authentication auth) {
        Lead lead = writable(id, auth);
        return ResponseEntity.ok(mapper.toResponse(
                lifecycle.recordInbound(lead, request.getText(), actor(auth))));
    }

    /** A free-text note or a manually logged message. */
    @PostMapping("/{id}/activity")
    @PreAuthorize("hasAuthority('PERM_LEAD_EDIT')")
    public ResponseEntity<ActivityResponse> addActivity(@PathVariable String id,
                                                        @RequestBody ActivityRequest request,
                                                        Authentication auth) {
        Lead lead = writable(id, auth);
        if (request.getSummary() == null || request.getSummary().isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Write what happened.");
        }
        return ResponseEntity.status(HttpStatus.CREATED).body(mapper.toResponse(
                lifecycle.log(lead,
                        request.getType() == null ? ActivityType.NOTE : request.getType(),
                        null,
                        request.getDirection() == null ? Direction.INTERNAL : request.getDirection(),
                        request.getSummary(), request.getDetail(), actor(auth))));
    }

    /**
     * Freezes the follow-up sequence — exams, a holiday, a gap between intakes. The lead stays
     * in the pipeline and stops being chased, instead of being marked lost to silence it.
     */
    @PostMapping("/{id}/pause")
    @PreAuthorize("hasAuthority('PERM_LEAD_EDIT')")
    public ResponseEntity<LeadResponse> pauseLadder(@PathVariable String id,
                                                    @RequestBody PauseRequest request,
                                                    Authentication auth) {
        Lead lead = writable(id, auth);
        if (request.getUntil() == null || !request.getUntil().isAfter(LocalDate.now())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Choose a date in the future to pause until.");
        }
        return ResponseEntity.ok(mapper.toResponse(
                ladder.pause(lead, request.getUntil(), request.getReason(), actor(auth))));
    }

    @PostMapping("/{id}/resume")
    @PreAuthorize("hasAuthority('PERM_LEAD_EDIT')")
    public ResponseEntity<LeadResponse> resumeLadder(@PathVariable String id, Authentication auth) {
        Lead lead = writable(id, auth);
        return ResponseEntity.ok(mapper.toResponse(ladder.resume(lead, actor(auth))));
    }

    /** The configured ladders, so the admin can see and later tune the schedule. */
    @GetMapping("/ladder")
    @PreAuthorize("hasAnyAuthority('PERM_LEAD_VIEW_ALL','PERM_LEAD_VIEW_OWN')")
    public ResponseEntity<List<LadderStepResponse>> ladderConfig() {
        return ResponseEntity.ok(ladder.allSteps().stream()
                .map(s -> mapper.toResponse(s, null)).toList());
    }

    /**
     * Runs the daily pass on demand. The pass is idempotent for a given day — a step already
     * advanced is not advanced again — so triggering it twice is harmless.
     */
    @PostMapping("/ladder/run")
    @PreAuthorize("hasAuthority('PERM_LEAD_ASSIGN')")
    public ResponseEntity<java.util.Map<String, Long>> runLadder() {
        return ResponseEntity.ok(ladderScheduler.runNow());
    }

    @PostMapping("/{id}/opt-out")
    @PreAuthorize("hasAuthority('PERM_LEAD_EDIT')")
    public ResponseEntity<LeadResponse> optOut(@PathVariable String id, Authentication auth) {
        Lead lead = writable(id, auth);
        return ResponseEntity.ok(mapper.toResponse(lifecycle.optOut(lead, actor(auth))));
    }

    /** Kept for the existing frontend. Moves the stage through the same rules as everything else. */
    @PatchMapping("/{id}/status")
    @PreAuthorize("hasAuthority('PERM_LEAD_EDIT')")
    public ResponseEntity<LeadResponse> updateLeadStatus(@PathVariable String id,
                                                         @RequestBody LeadStatusUpdate request,
                                                         Authentication auth) {
        Lead lead = writable(id, auth);
        Stage next = Stage.parse(request.getStatus(), null);
        if (next == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "\"" + request.getStatus() + "\" is not a pipeline stage.");
        }
        if (next == Stage.LOST && lead.getLostReason() == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Marking a lead lost needs a reason. Use the lead screen to record it.");
        }
        lifecycle.moveStage(lead, next, "Changed from the leads list", actor(auth));
        return ResponseEntity.ok(mapper.toResponse(leadRepository.save(lead)));
    }

    /**
     * Hard delete. Held by admins only and discouraged: the SOP is explicit that a lead who
     * goes nowhere is marked lost and kept, because they may return for a later intake.
     */
    @DeleteMapping("/{id}")
    @PreAuthorize("hasAuthority('PERM_LEAD_DELETE')")
    public ResponseEntity<Void> deleteLead(@PathVariable String id) {
        if (!leadRepository.existsById(id)) {
            return ResponseEntity.notFound().build();
        }
        leadRepository.deleteById(id);
        return ResponseEntity.noContent().build();
    }

    // ==================================================================
    // Helpers
    // ==================================================================

    private List<Lead> find(Specification<Lead> spec) {
        return leadRepository.findAll(spec, Sort.by(Sort.Direction.ASC, "nextTouchOn"));
    }

    private List<LeadResponse> map(List<Lead> leads, java.util.function.Function<String, String> names) {
        return leads.stream().map(l -> mapper.toResponse(l, names)).collect(Collectors.toList());
    }

    private Actor actor(Authentication auth) {
        User u = access.requireUser(auth);
        return new Actor(u.getId(), u.displayNameOrEmail());
    }

    /**
     * Works out which owner the query is restricted to. A privileged caller may filter by any
     * counsellor; anyone else is pinned to their own leads no matter what they ask for.
     */
    private String resolveOwnerScope(Authentication auth, String requestedOwner) {
        String forced = access.ownerFilter(auth);
        if (forced != null) return forced;
        return requestedOwner == null || requestedOwner.isBlank() ? null : requestedOwner;
    }

    private Lead readable(String id, Authentication auth) {
        Lead lead = leadRepository.findById(id).orElseThrow(() ->
                new ResponseStatusException(HttpStatus.NOT_FOUND, "That lead no longer exists."));
        if (!access.ownsOrSeesAll(auth, lead.getAssignedToId())) {
            // 404 rather than 403: confirming a lead exists would leak that a student enquired.
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "That lead no longer exists.");
        }
        return lead;
    }

    private Lead writable(String id, Authentication auth) {
        Lead lead = readable(id, auth);
        if (Boolean.TRUE.equals(lead.getOptedOut())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "This student asked not to be contacted. Their record is read-only.");
        }
        return lead;
    }

    private String hintFor(OutcomeCode o) {
        StringBuilder hint = new StringBuilder();
        if (o.getStage() != null) hint.append("Moves to ").append(o.getStage().getLabel()).append(". ");
        if (o.getGrade() != null) hint.append("Grades ").append(o.getGrade().getLabel()).append(". ");
        if (o.getNextTouchDays() != null) {
            hint.append(o.getNextTouchDays() == 0 ? "Next touch today. "
                    : "Next touch in " + o.getNextTouchDays() + " day(s). ");
        }
        int[] extras = o.getExtraFollowUpDays();
        if (extras.length > 0) {
            hint.append("Also books day ")
                .append(Arrays.stream(extras).mapToObj(d -> "+" + d).collect(Collectors.joining(" and ")))
                .append(". ");
        }
        if (o.isRequiresReason()) hint.append("A note is required.");
        return hint.toString().trim();
    }
}
