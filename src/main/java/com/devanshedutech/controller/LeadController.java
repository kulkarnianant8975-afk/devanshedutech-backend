package com.devanshedutech.controller;

import com.devanshedutech.crm.LeadMapper;
import com.devanshedutech.crm.LeadSpecifications;
import com.devanshedutech.dto.LeadDTOs.*;
import com.devanshedutech.model.Lead;
import com.devanshedutech.model.LeadActivity;
import com.devanshedutech.model.Permission;
import com.devanshedutech.model.User;
import com.devanshedutech.model.crm.*;
import com.devanshedutech.repository.LeadActivityRepository;
import com.devanshedutech.repository.LeadRepository;
import com.devanshedutech.security.AccessService;
import com.devanshedutech.service.AssetTrackingService;
import com.devanshedutech.service.LeadCaptureService;
import com.devanshedutech.service.LeadLadderScheduler;
import com.devanshedutech.service.LeadLadderService;
import com.devanshedutech.service.LeadLifecycleService;
import com.devanshedutech.service.NotificationService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.annotation.Value;
import com.devanshedutech.service.LeadLifecycleService.Actor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.time.LocalDate;
import java.util.Arrays;
import java.util.Map;
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
    private final NotificationService notifications;
    private final RateLimiter rateLimiter;
    private final AssetTrackingService tracking;
    private final LeadActivityRepository activityRepository;

    public LeadController(LeadRepository leadRepository,
                          LeadCaptureService capture,
                          LeadLifecycleService lifecycle,
                          LeadMapper mapper,
                          AccessService access,
                          LeadLadderService ladder,
                          LeadLadderScheduler ladderScheduler,
                          NotificationService notifications,
                          RateLimiter rateLimiter,
                          AssetTrackingService tracking,
                          LeadActivityRepository activityRepository) {
        this.leadRepository = leadRepository;
        this.capture = capture;
        this.lifecycle = lifecycle;
        this.mapper = mapper;
        this.access = access;
        this.ladder = ladder;
        this.ladderScheduler = ladderScheduler;
        this.notifications = notifications;
        this.rateLimiter = rateLimiter;
        this.tracking = tracking;
        this.activityRepository = activityRepository;
    }

    // ==================================================================
    // Public capture
    // ==================================================================

    /**
     * The public enquiry form. Deliberately unauthenticated — this is the front door of the
     * business — and deliberately forgiving about missing detail, because a rejected enquiry is
     * a lost student.
     */
    /**
     * Enquiries a single address may submit per minute. A real student submits once, but a
     * college seminar where thirty people fill the form on one office wifi is a legitimate
     * burst — hence configurable rather than baked in.
     */
    @Value("${app.crm.capture.rate-limit-per-minute:6}")
    private int captureLimitPerMinute;

    /**
     * A lead entered by a counsellor: a walk-in, a phone enquiry, a name from a seminar.
     *
     * <p>Separate from the public endpoint for three reasons. It is not rate limited, because a
     * counsellor typing in the twenty names they collected at a college is not abuse. It records
     * who entered it, so the timeline says a person did rather than "system". And it assigns the
     * lead to whoever added it, because somebody standing in front of a student is already the
     * owner — waiting for the duty roster to decide would be absurd.</p>
     *
     * <p>Everything else is shared with the public path: the same phone normalisation, the same
     * thirty-day duplicate check. A student who filled the website form last week and walks in
     * today is one person, and a counsellor should see that history rather than start a rival
     * record.</p>
     */
    @PostMapping("/manual")
    @PreAuthorize("hasAuthority('PERM_LEAD_CREATE')")
    public ResponseEntity<CaptureResponse> createManualLead(@RequestBody LeadRequest request,
                                                            Authentication auth) {
        LeadSource source = LeadSource.parse(request.getSource(), LeadSource.WALK_IN);
        LeadCaptureService.Captured result = capture.capture(request, source);
        Lead lead = result.lead();

        Actor actor = actor(auth);
        if (lead.getAssignedToId() == null) {
            var me = access.requireUser(auth);
            String myName = me.getDisplayName() == null ? me.getEmail() : me.getDisplayName();
            lifecycle.assign(lead, me.getId(), myName, actor);
        }
        lifecycle.log(lead, com.devanshedutech.model.crm.ActivityType.SYSTEM, null,
                com.devanshedutech.model.crm.Direction.INTERNAL,
                result.duplicate() ? "Added by hand — matched an existing lead" : "Added by hand",
                result.duplicate()
                        ? "This number was already in the pipeline, so the enquiry was folded into "
                          + "the record that exists rather than starting a second one."
                        : "Entered from a " + source.getLabel().toLowerCase(java.util.Locale.ROOT) + ".",
                actor);

        return ResponseEntity.status(result.duplicate() ? HttpStatus.OK : HttpStatus.CREATED)
                .body(CaptureResponse.builder()
                        .id(lead.getId())
                        .fullName(lead.getFullName())
                        .duplicate(result.duplicate())
                        .message(result.duplicate()
                                ? "That number is already in the pipeline — opening the existing lead."
                                : "Added. It is in your day now.")
                        .build());
    }

    @PostMapping
    public ResponseEntity<CaptureResponse> createLead(@RequestBody LeadRequest request,
                                                      HttpServletRequest http) {
        // The front door of the business, so it is deliberately open — and therefore the one
        // endpoint worth limiting. A pipeline full of junk is not just noise: counsellors work
        // this queue by hand, so every fake enquiry costs somebody real time.
        if (rateLimiter.exceeded("lead-capture", rateLimiter.clientKey(http), captureLimitPerMinute)) {
            throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS,
                    "Too many submissions. Please wait a moment and try again.");
        }
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
                .opens(tracking.forLead(id).stream()
                        .map(l -> com.devanshedutech.dto.LeadDTOs.AssetOpenResponse.builder()
                                .assetKey(l.getAssetKey())
                                .assetName(l.getAssetName())
                                .opens(l.opens())
                                .firstOpenedAt(l.getFirstOpenedAt())
                                .lastOpenedAt(l.getLastOpenedAt())
                                .build())
                        .toList())
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

    /**
     * Every follow-up made in a window, across all leads.
     *
     * <p>The timeline answers "what happened to this student". Nothing answered "what did we
     * actually do this week", so a counsellor could not review their own day without opening
     * leads one at a time, and an owner could not see whether a counsellor's leads were being
     * worked at all until the numbers moved — by which point the batch has started.</p>
     *
     * <p>Scope follows the same rule as every other lead read: a counsellor sees their own work
     * whatever they ask for, and only a privileged caller may filter by somebody else. The
     * student's name is resolved here rather than left as an id, because a list of activity
     * against UUIDs is not a thing anybody can review.</p>
     */
    @GetMapping("/activity")
    @PreAuthorize("hasAnyAuthority('PERM_LEAD_VIEW_ALL','PERM_LEAD_VIEW_OWN')")
    public ResponseEntity<List<ContactLogResponse>> activity(
            @RequestParam(required = false) String from,
            @RequestParam(required = false) String to,
            @RequestParam(required = false) String counsellorId,
            Authentication auth) {

        LocalDate start = from == null || from.isBlank() ? LocalDate.now().minusDays(6) : LocalDate.parse(from);
        LocalDate end = to == null || to.isBlank() ? LocalDate.now() : LocalDate.parse(to);
        if (end.isBefore(start)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "The end of the range is before its start.");
        }

        String scope = resolveOwnerScope(auth, counsellorId);
        List<LeadActivity> contacts = activityRepository.findContactsBetween(
                start.atStartOfDay(), end.plusDays(1).atStartOfDay(), scope);

        // One lookup for the whole page rather than one per row.
        Map<String, Lead> leads = leadRepository.findAllById(
                        contacts.stream().map(LeadActivity::getLeadId).distinct().toList())
                .stream().collect(Collectors.toMap(Lead::getId, l -> l));

        return ResponseEntity.ok(contacts.stream().map(a -> {
            Lead lead = leads.get(a.getLeadId());
            return ContactLogResponse.builder()
                    .id(a.getId())
                    .leadId(a.getLeadId())
                    .studentName(lead == null ? "A removed lead" : lead.getFullName())
                    .course(lead == null ? null : lead.getCourseInterested())
                    .type(a.getType() == null ? null : a.getType().name())
                    .outcomeLabel(a.getOutcome() == null ? a.getSummary() : a.getOutcome().getLabel())
                    .note(a.getDetail())
                    .counsellor(a.getCreatedByName())
                    .at(a.getCreatedAt())
                    .nextTouchOn(lead == null ? null : lead.getNextTouchOn())
                    .build();
        }).toList());
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
        // The student's own details. Each is written only when sent, so a form that submits one
        // field cannot blank the rest.
        if (request.getFullName() != null) {
            String name = request.getFullName().trim();
            if (name.isEmpty()) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "A student needs a name.");
            }
            lead.setFullName(name);
        }
        if (request.getEmail() != null) lead.setEmail(blankToNull(request.getEmail().trim()));
        if (request.getCityName() != null) lead.setCityName(blankToNull(request.getCityName().trim()));

        if (request.getMobileNumber() != null) {
            String typed = request.getMobileNumber().trim();
            String normalised = Lead.normalizePhone(typed);
            if (normalised == null) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                        "That does not look like a phone number.");
            }
            // Refused rather than merged. Two records for one student means two counsellors
            // ringing them, and silently pointing this lead at somebody else's history would be
            // worse than saying no.
            leadRepository.findByPhoneNormalized(normalised).stream()
                    .filter(other -> !other.getId().equals(lead.getId()))
                    .findFirst()
                    .ifPresent(other -> {
                        throw new ResponseStatusException(HttpStatus.CONFLICT,
                                "That number already belongs to " + other.getFullName()
                                + ". Open their record instead of moving this one onto it.");
                    });

            // Set explicitly: preUpdate only fills this in when it is null, so a changed number
            // would otherwise keep matching the old one for every duplicate check afterwards.
            lead.setMobileNumber(typed);
            lead.setPhoneNormalized(normalised);
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
            // Told immediately rather than at tomorrow's sweep: a lead handed over today is
            // work somebody needs to pick up today.
            if (newOwner != null && !newOwner.equals(who.id())) {
                notifications.leadAssigned(lead, newOwner, who.name());
            }
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
     * Retunes one step of a follow-up ladder.
     *
     * <p>These offsets are the numbers most worth adjusting once real conversion data arrives —
     * the SOP's day 3 nudge may turn out to work better on day 2 here. Changing them is a
     * settings change, not a deployment, which is why the ladders live in the database.</p>
     */
    @PatchMapping("/ladder/{id}")
    @PreAuthorize("hasAuthority('PERM_SETTINGS_MANAGE')")
    public ResponseEntity<LadderStepResponse> updateLadderStep(@PathVariable String id,
                                                               @RequestBody LadderStepRequest request,
                                                               Authentication auth) {
        com.devanshedutech.model.LadderStep step = ladder.step(id);
        if (request.getDayOffset() != null) {
            if (request.getDayOffset() < 0 || request.getDayOffset() > 365) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                        "A step must fall between day 0 and day 365.");
            }
            step.setDayOffset(request.getDayOffset());
        }
        if (request.getTitle() != null && !request.getTitle().isBlank()) step.setTitle(request.getTitle().trim());
        if (request.getAction() != null) step.setAction(request.getAction());
        if (request.getActive() != null) step.setActive(request.getActive());

        return ResponseEntity.ok(mapper.toResponse(ladder.saveStep(step, actor(auth)), null));
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
    /**
     * Moves a lead to the recycle bin.
     *
     * <p>The row stays. Removing it took a student's name, their number and every note anybody
     * had written about them, with no undo — on records that exist precisely because they are
     * expensive to acquire. It disappears from every screen either way; the difference is
     * whether it can come back.</p>
     */
    @DeleteMapping("/{id}")
    @PreAuthorize("hasAuthority('PERM_LEAD_DELETE')")
    @Transactional
    public ResponseEntity<Void> deleteLead(@PathVariable String id, Authentication auth) {
        // readable(), so a counsellor cannot delete a lead they are not allowed to open. The
        // previous version checked only the permission, never the ownership.
        Lead lead = readable(id, auth);
        User actor = access.requireUser(auth);

        lead.setDeletedAt(LocalDateTime.now());
        lead.setDeletedById(actor.getId());
        leadRepository.save(lead);

        log.info("Lead {} moved to the recycle bin by {}", id, actor.displayNameOrEmail());
        return ResponseEntity.noContent().build();
    }

    /** What is in the bin. Only somebody who could have deleted it may look. */
    @GetMapping("/deleted")
    @PreAuthorize("hasAuthority('PERM_LEAD_DELETE')")
    public ResponseEntity<List<LeadResponse>> deleted() {
        return ResponseEntity.ok(map(leadRepository.findDeleted(), mapper.ownerNames()));
    }

    /** Puts one back, exactly as it was. */
    @PostMapping("/{id}/restore")
    @PreAuthorize("hasAuthority('PERM_LEAD_DELETE')")
    @Transactional
    public ResponseEntity<Void> restoreLead(@PathVariable String id, Authentication auth) {
        if (leadRepository.restore(id) == 0) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND,
                    "That lead is not in the recycle bin.");
        }
        log.info("Lead {} restored by {}", id, access.requireUser(auth).displayNameOrEmail());
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

    /** An empty box on a form means "no value", not the empty string. */
    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value;
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
