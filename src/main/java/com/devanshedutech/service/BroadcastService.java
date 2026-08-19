package com.devanshedutech.service;

import com.devanshedutech.channel.WhatsAppChannel;
import com.devanshedutech.channel.WhatsAppSender;
import com.devanshedutech.crm.LeadSpecifications;
import com.devanshedutech.model.Broadcast;
import com.devanshedutech.model.Lead;
import com.devanshedutech.model.crm.ActivityType;
import com.devanshedutech.model.crm.Direction;
import com.devanshedutech.model.crm.Stage;
import com.devanshedutech.repository.BroadcastRepository;
import com.devanshedutech.repository.LeadRepository;
import com.devanshedutech.service.LeadLifecycleService.Actor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Announcements to the people nobody is chasing.
 *
 * <p>Cold leads and closed ones are not dead, they are dormant: the SOP keeps a lost lead
 * precisely because students return for the next intake, and the cold track exists so those
 * people hear about a new batch without a counsellor spending a morning on them. Without this
 * the follow-up ladder decays leads into silence, which is a slower version of deleting them.</p>
 */
@Slf4j
@Service
public class BroadcastService {

    /** Segments a broadcast may target, with the audience each one means. */
    public enum Segment {
        COLD("Cold leads", "Browsing rather than deciding. Announcements only, never chased."),
        UPDATES_ONLY("Updates-only list", "Reached the end of their follow-up without replying."),
        LOST("Lost leads", "Said no or went quiet. Kept, because students come back for the next intake."),
        ENROLLED("Current and past students", "For referral asks and alumni news."),
        ALL_DORMANT("Everyone dormant", "Cold, updates-only and lost together.");

        private final String label;
        private final String description;

        Segment(String label, String description) {
            this.label = label;
            this.description = description;
        }
        public String getLabel() { return label; }
        public String getDescription() { return description; }
    }

    private final BroadcastRepository broadcasts;
    private final LeadRepository leads;
    private final LeadLifecycleService lifecycle;
    private final WhatsAppSender whatsapp;

    public BroadcastService(BroadcastRepository broadcasts, LeadRepository leads,
                            LeadLifecycleService lifecycle, WhatsAppSender whatsapp) {
        this.broadcasts = broadcasts;
        this.leads = leads;
        this.lifecycle = lifecycle;
        this.whatsapp = whatsapp;
    }

    public Specification<Lead> specFor(Segment segment) {
        Specification<Lead> base = LeadSpecifications.broadcastable();
        return switch (segment) {
            case COLD -> base.and(LeadSpecifications.cold());
            case UPDATES_ONLY -> base.and(LeadSpecifications.updatesOnly());
            case LOST -> base.and(LeadSpecifications.stageIn(List.of(Stage.LOST)));
            case ENROLLED -> base.and(LeadSpecifications.stageIn(List.of(Stage.ENROLLED)));
            case ALL_DORMANT -> base.and(
                    LeadSpecifications.cold()
                            .or(LeadSpecifications.updatesOnly())
                            .or(LeadSpecifications.stageIn(List.of(Stage.LOST))));
        };
    }

    /** How many a segment currently matches, so nobody sends blind. */
    public Map<String, Object> preview(Segment segment) {
        long total = leads.count(specFor(segment));
        long optedOut = leads.findAll().stream().filter(l -> Boolean.TRUE.equals(l.getOptedOut())).count();

        Map<String, Object> m = new LinkedHashMap<>();
        m.put("segment", segment.name());
        m.put("label", segment.getLabel());
        m.put("description", segment.getDescription());
        m.put("recipients", total);
        m.put("optedOutExcluded", optedOut);
        return m;
    }

    /**
     * Sends to everyone in a segment.
     *
     * <p>Each recipient is written to their own timeline, so a counsellor opening a lead months
     * later can see the institute did contact them and when. A broadcast that leaves no trace on
     * the record is indistinguishable from never having happened.</p>
     */
    @Transactional
    public Broadcast send(String title, String message, Segment segment, Actor actor) {
        if (title == null || title.isBlank() || message == null || message.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "A broadcast needs a title and a message.");
        }

        List<Lead> recipients = leads.findAll(specFor(segment));
        if (recipients.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Nobody is in that segment right now, so there is nothing to send.");
        }

        Broadcast record = broadcasts.save(Broadcast.builder()
                .id(UUID.randomUUID().toString())
                .title(title.trim()).message(message.trim())
                .segment(segment.name()).status("SENDING")
                .recipientCount(recipients.size())
                .createdById(actor == null ? null : actor.id())
                .createdByName(actor == null ? "System" : actor.name())
                .createdAt(LocalDateTime.now())
                .build());

        int sent = 0;
        int failed = 0;
        for (Lead lead : recipients) {
            WhatsAppChannel.SendResult result = whatsapp.send(
                    lead.getMobileNumber(), lead.getFullName(), personalise(message, lead), List.of());

            if (result.sent()) {
                sent++;
                var entry = lifecycle.log(lead, ActivityType.WHATSAPP, null, Direction.OUTBOUND,
                        "Broadcast: " + title, message, Actor.system());
                entry.setDeliveryStatus("sent");
            } else {
                failed++;
            }
        }

        record.setSentCount(sent);
        record.setFailedCount(failed);
        record.setSkippedOptedOut(0);
        record.setSentAt(LocalDateTime.now());
        // A hand-off channel cannot fan out to a list, so a broadcast with no provider is
        // honestly recorded as failed rather than as sent-but-invisible.
        record.setStatus(sent > 0 ? "SENT" : "FAILED");
        broadcasts.save(record);

        if (sent == 0) {
            log.warn("Broadcast '{}' reached nobody. Without a messaging provider configured, "
                    + "announcements cannot be sent to a list.", title);
        } else {
            log.info("Broadcast '{}' sent to {} of {} in segment {}", title, sent, recipients.size(), segment);
        }
        return record;
    }

    /** First name only, because a broadcast that opens with a full legal name reads as a mailshot. */
    private String personalise(String message, Lead lead) {
        String first = lead.getFullName() == null ? "there" : lead.getFullName().split("\\s+")[0];
        return message.replace("{{first_name}}", first)
                      .replace("{{course}}", lead.getCourseInterested() == null
                              ? "our courses" : lead.getCourseInterested());
    }

    public List<Broadcast> recent() {
        return broadcasts.findTop50ByOrderByCreatedAtDesc();
    }

    public boolean canSend() {
        return whatsapp.sendsAutomatically();
    }
}
