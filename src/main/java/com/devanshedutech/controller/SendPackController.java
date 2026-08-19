package com.devanshedutech.controller;

import com.devanshedutech.crm.LeadMapper;
import com.devanshedutech.dto.LeadDTOs.LeadResponse;
import com.devanshedutech.dto.LeadDTOs.SendPackRequest;
import com.devanshedutech.dto.LeadDTOs.SentPackRequest;
import com.devanshedutech.model.Lead;
import com.devanshedutech.model.User;
import com.devanshedutech.repository.LeadRepository;
import com.devanshedutech.security.AccessService;
import com.devanshedutech.service.LeadLifecycleService.Actor;
import com.devanshedutech.service.SendPackService;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.Map;

/**
 * Message packs: what a counsellor is about to send, and recording that they did.
 *
 * <p>Split out of LeadController, which had grown nine dependencies and broke a test every time
 * a feature was added — the constructor was telling us it had taken on too much.</p>
 */
@RestController
@RequestMapping("/api/leads")
public class SendPackController {

    private final LeadRepository leads;
    private final SendPackService packs;
    private final LeadMapper mapper;
    private final AccessService access;

    public SendPackController(LeadRepository leads, SendPackService packs,
                              LeadMapper mapper, AccessService access) {
        this.leads = leads;
        this.packs = packs;
        this.mapper = mapper;
        this.access = access;
    }

    /** The packs available, so the client never hardcodes a list that can drift. */
    @GetMapping("/packs")
    @PreAuthorize("hasAnyAuthority('PERM_LEAD_VIEW_ALL','PERM_LEAD_VIEW_OWN')")
    public ResponseEntity<List<Map<String, Object>>> list() {
        return ResponseEntity.ok(packs.all().stream().map(p -> Map.<String, Object>of(
                "key", p.getKey(),
                "name", p.getName(),
                "situation", p.getSituation() == null ? "" : p.getSituation())).toList());
    }

    /** Every pack with its full text, for the editor. */
    @GetMapping("/packs/full")
    @PreAuthorize("hasAuthority('PERM_SETTINGS_MANAGE')")
    public ResponseEntity<Map<String, Object>> full() {
        return ResponseEntity.ok(Map.of(
                "packs", packs.all().stream().map(p -> Map.<String, Object>of(
                        "key", p.getKey(),
                        "name", p.getName(),
                        "situation", p.getSituation() == null ? "" : p.getSituation(),
                        "coverTemplate", p.getCoverTemplate(),
                        "assetKeys", p.assets(),
                        "active", p.isActive())).toList(),
                "assets", packs.allAssets().stream().map(a -> Map.<String, Object>of(
                        "key", a.getKey(), "name", a.getName(),
                        "type", a.getType(), "sizeLabel", a.getSizeLabel() == null ? "" : a.getSizeLabel())).toList(),
                "placeholders", SendPackService.PLACEHOLDERS));
    }

    @PatchMapping("/packs/{packKey}")
    @PreAuthorize("hasAuthority('PERM_SETTINGS_MANAGE')")
    public ResponseEntity<Map<String, Object>> update(@PathVariable String packKey,
                                                      @RequestBody UpdatePackRequest request) {
        var pack = packs.update(packKey, request.getName(), request.getSituation(),
                request.getCoverTemplate(), request.getAssetKeys(), request.getActive());
        return ResponseEntity.ok(Map.of(
                "key", pack.getKey(), "name", pack.getName(),
                "situation", pack.getSituation() == null ? "" : pack.getSituation(),
                "coverTemplate", pack.getCoverTemplate(),
                "assetKeys", pack.assets(), "active", pack.isActive()));
    }

    /** A pack filled in for one student, with the reply-window state that decides what may be sent. */
    @GetMapping("/{id}/packs/{packKey}")
    @PreAuthorize("hasAuthority('PERM_LEAD_EDIT')")
    public ResponseEntity<SendPackService.Prepared> prepare(@PathVariable String id,
                                                            @PathVariable String packKey,
                                                            Authentication auth) {
        Lead lead = writable(id, auth);
        return ResponseEntity.ok(packs.prepare(lead, packKey, actor(auth).name()));
    }

    /**
     * Sends the pack.
     *
     * <p>With a provider configured this delivers to the student directly. Without one it
     * returns a hand-off link and nothing is recorded until the counsellor confirms — because
     * writing "sent" on a timeline for a message that may never have left is worse than
     * recording nothing.</p>
     */
    @PostMapping("/{id}/packs/{packKey}/send")
    @PreAuthorize("hasAuthority('PERM_LEAD_EDIT')")
    public ResponseEntity<SendPackService.SendOutcome> send(@PathVariable String id,
                                                            @PathVariable String packKey,
                                                            @RequestBody(required = false) SendPackRequest request,
                                                            Authentication auth) {
        Lead lead = writable(id, auth);
        return ResponseEntity.ok(packs.send(lead, packKey,
                request == null ? null : request.getMessage(),
                request == null ? List.of() : request.getAssets(),
                actor(auth)));
    }

    /**
     * Records that the counsellor sent it.
     *
     * <p>Separate from preparing on purpose. Nothing here can watch a message leave WhatsApp, so
     * the timeline records what a person confirms rather than what the software hoped for.</p>
     */
    @PostMapping("/{id}/packs/{packKey}/sent")
    @PreAuthorize("hasAuthority('PERM_LEAD_EDIT')")
    public ResponseEntity<LeadResponse> recordSent(@PathVariable String id,
                                                   @PathVariable String packKey,
                                                   @RequestBody(required = false) SentPackRequest request,
                                                   Authentication auth) {
        Lead lead = writable(id, auth);
        return ResponseEntity.ok(mapper.toResponse(packs.recordSent(
                lead, packKey, request == null ? List.of() : request.getAssets(), actor(auth))));
    }

    @lombok.Data @lombok.Builder @lombok.NoArgsConstructor @lombok.AllArgsConstructor
    public static class UpdatePackRequest {
        private String name;
        private String situation;
        private String coverTemplate;
        private List<String> assetKeys;
        private Boolean active;
    }

    private Actor actor(Authentication auth) {
        User u = access.requireUser(auth);
        return new Actor(u.getId(), u.displayNameOrEmail());
    }

    /** Same ownership rule as the lead screens: 404 rather than 403, so nothing is confirmed. */
    private Lead writable(String id, Authentication auth) {
        Lead lead = leads.findById(id).orElseThrow(() ->
                new ResponseStatusException(HttpStatus.NOT_FOUND, "That lead no longer exists."));
        if (!access.ownsOrSeesAll(auth, lead.getAssignedToId())) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "That lead no longer exists.");
        }
        if (Boolean.TRUE.equals(lead.getOptedOut())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "This student asked not to be contacted. Their record is read-only.");
        }
        return lead;
    }
}
