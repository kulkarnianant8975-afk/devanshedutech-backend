package com.devanshedutech.controller;

import com.devanshedutech.ai.GeminiClient;
import com.devanshedutech.model.Lead;
import com.devanshedutech.repository.LeadRepository;
import com.devanshedutech.security.AccessService;
import com.devanshedutech.service.LeadAssistantService;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * The counsellor's assistant: an opinion on a lead, a briefing before a call, a draft message.
 *
 * <p>Nothing here changes a lead. A grade suggestion is returned for a person to accept or
 * ignore, and a drafted message is returned for a person to edit and send. That is the whole
 * design: grading drives the follow-up ladder, so a model quietly regrading a student it misread
 * would change how often a real person gets contacted for reasons nobody could reconstruct.</p>
 */
@Slf4j
@RestController
@RequestMapping("/api/leads/{id}/ai")
public class LeadAssistantController {

    private final LeadAssistantService assistant;
    private final LeadRepository leads;
    private final AccessService access;

    public LeadAssistantController(LeadAssistantService assistant, LeadRepository leads,
                                   AccessService access) {
        this.assistant = assistant;
        this.leads = leads;
        this.access = access;
    }

    /** Whether to draw the buttons at all. Cheaper than letting every one of them fail. */
    @GetMapping("/available")
    @PreAuthorize("hasAnyAuthority('PERM_LEAD_VIEW_ALL','PERM_LEAD_VIEW_OWN')")
    public Map<String, Boolean> available(@PathVariable String id) {
        return Map.of("available", assistant.isAvailable());
    }

    @PostMapping("/grade")
    @PreAuthorize("hasAuthority('PERM_LEAD_EDIT')")
    public Map<String, Object> suggestGrade(@PathVariable String id, Authentication auth) {
        Lead lead = readable(id, auth);
        LeadAssistantService.GradeSuggestion suggestion = run(() -> assistant.suggestGrade(lead));

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("grade", suggestion.getGrade());
        body.put("reasoning", suggestion.getReasoning());
        // Stated explicitly in the response so no caller has to assume it.
        body.put("applied", false);
        return body;
    }

    @PostMapping("/summary")
    @PreAuthorize("hasAnyAuthority('PERM_LEAD_VIEW_ALL','PERM_LEAD_VIEW_OWN')")
    public Map<String, String> summarise(@PathVariable String id, Authentication auth) {
        Lead lead = readable(id, auth);
        return Map.of("summary", run(() -> assistant.summarise(lead)));
    }

    @Data
    public static class DraftRequest {
        /** What the message needs to achieve. Free text, written by the counsellor. */
        private String intent;
    }

    @PostMapping("/draft")
    @PreAuthorize("hasAuthority('PERM_LEAD_EDIT')")
    public Map<String, String> draft(@PathVariable String id, @RequestBody(required = false) DraftRequest request,
                                     Authentication auth) {
        Lead lead = readable(id, auth);
        String name = access.requireUser(auth).getDisplayName();
        String intent = request == null ? null : request.getIntent();
        return Map.of("draft", run(() -> assistant.draftReply(lead, intent, name)));
    }

    /**
     * Turns an unavailable model into a plain refusal the counsellor can act on.
     *
     * <p>503 rather than 500: nothing is broken, the feature simply is not switched on or the
     * provider is not answering, and the counsellor should write the message themselves rather
     * than wait.</p>
     */
    private <T> T run(java.util.function.Supplier<T> work) {
        try {
            return work.get();
        } catch (GeminiClient.AiUnavailableException e) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, e.getMessage());
        }
    }

    /** The same ownership rule the rest of the lead endpoints use. */
    private Lead readable(String id, Authentication auth) {
        Lead lead = leads.findById(id).orElseThrow(() ->
                new ResponseStatusException(HttpStatus.NOT_FOUND, "That lead no longer exists."));
        String owner = access.ownerFilter(auth);
        if (owner != null && !owner.equals(lead.getAssignedToId())) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "That lead belongs to someone else.");
        }
        return lead;
    }
}
