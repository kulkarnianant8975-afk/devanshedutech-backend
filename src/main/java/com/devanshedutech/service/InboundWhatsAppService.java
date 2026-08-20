package com.devanshedutech.service;

import com.devanshedutech.dto.LeadDTOs.LeadRequest;
import com.devanshedutech.model.InboundMessage;
import com.devanshedutech.model.Lead;
import com.devanshedutech.model.crm.LeadSource;
import com.devanshedutech.repository.InboundMessageRepository;
import com.devanshedutech.repository.LeadRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * What happens when a student messages the institute on WhatsApp.
 *
 * <p>This closes the loop the playbook cares most about. A student writes at nine in the
 * evening; the reply, the syllabus and their place in somebody's queue all happen before a
 * counsellor has seen it. The five-minute rule becomes five seconds, and the counsellor picks up
 * a conversation that has already started rather than a cold enquiry the next morning.</p>
 *
 * <p>The auto-reply is sent through the ordinary message packs, so its wording is edited in
 * Message Scripts like everything else. Nobody should need a deployment to change what a student
 * reads first.</p>
 */
@Slf4j
@Service
public class InboundWhatsAppService {

    /** The pack sent automatically. Editable in the product like any other. */
    private static final String AUTO_REPLY_PACK = "auto_reply";

    /**
     * A message this long after the previous one starts a fresh conversation.
     *
     * <p>Matches WhatsApp's own free-reply window, which is the natural boundary: if the window
     * had closed, the student is starting again rather than continuing.</p>
     */
    private static final Duration NEW_CONVERSATION = Duration.ofHours(24);

    private final InboundMessageRepository inbound;
    private final LeadRepository leads;
    private final LeadCaptureService capture;
    private final LeadLifecycleService lifecycle;
    private final SendPackService packs;

    @Value("${app.crm.whatsapp.auto-reply:true}")
    private boolean autoReplyEnabled;

    public InboundWhatsAppService(InboundMessageRepository inbound, LeadRepository leads,
                                  LeadCaptureService capture, LeadLifecycleService lifecycle,
                                  SendPackService packs) {
        this.inbound = inbound;
        this.leads = leads;
        this.capture = capture;
        this.lifecycle = lifecycle;
        this.packs = packs;
    }

    /** One message, already pulled out of Meta's envelope. */
    public record Incoming(String messageId, String fromPhone, String profileName, String text) {}

    /**
     * Reads Meta's webhook body into the messages it contains.
     *
     * <p>Written defensively rather than mapped onto classes: this payload arrives from outside,
     * its shape varies by message type, and Meta adds fields without warning. A malformed entry
     * should cost us that one message, not the whole batch — and never an exception that makes
     * Meta retry a payload we could never parse.</p>
     */
    @SuppressWarnings("unchecked")
    public List<Incoming> parse(Map<String, Object> payload) {
        List<Incoming> out = new java.util.ArrayList<>();
        if (payload == null) return out;

        Object entries = payload.get("entry");
        if (!(entries instanceof List<?> entryList)) return out;

        for (Object entryObj : entryList) {
            if (!(entryObj instanceof Map<?, ?> entry)) continue;
            Object changes = ((Map<String, Object>) entry).get("changes");
            if (!(changes instanceof List<?> changeList)) continue;

            for (Object changeObj : changeList) {
                if (!(changeObj instanceof Map<?, ?> change)) continue;
                Object valueObj = ((Map<String, Object>) change).get("value");
                if (!(valueObj instanceof Map<?, ?> value)) continue;
                Map<String, Object> v = (Map<String, Object>) value;

                // Delivery receipts arrive on the same hook. They are not messages and must not
                // create leads or trigger replies.
                Object messages = v.get("messages");
                if (!(messages instanceof List<?> messageList)) continue;

                String profileName = firstProfileName(v);

                for (Object messageObj : messageList) {
                    if (!(messageObj instanceof Map<?, ?> message)) continue;
                    Map<String, Object> m = (Map<String, Object>) message;

                    String id = str(m.get("id"));
                    String from = str(m.get("from"));
                    if (id == null || from == null) continue;

                    out.add(new Incoming(id, from, profileName, textOf(m)));
                }
            }
        }
        return out;
    }

    /**
     * Handles one message: files it, records it, and replies if this is the start of something.
     *
     * @return the lead it belongs to, or empty if it was a duplicate delivery
     */
    @Transactional
    public Optional<Lead> handle(Incoming message) {
        if (inbound.existsById(message.messageId())) {
            log.debug("WhatsApp message {} already handled; ignoring the redelivery.", message.messageId());
            return Optional.empty();
        }

        Lead lead = findOrCreate(message);
        boolean firstOfConversation = startsAConversation(lead);

        inbound.save(InboundMessage.builder()
                .messageId(message.messageId())
                .fromPhone(message.fromPhone())
                .leadId(lead.getId())
                .receivedAt(LocalDateTime.now())
                .build());

        // Everything a reply means — the reply window, the grade promotion on a buying signal,
        // reopening a lost lead, today's next touch — already lives here.
        lifecycle.recordInbound(lead, message.text(), LeadLifecycleService.Actor.system());

        if (shouldAutoReply(lead, firstOfConversation)) {
            autoReply(lead);
        }
        return Optional.of(lead);
    }

    /**
     * Finds the student, or files them as a new enquiry.
     *
     * <p>Capture already matches on the phone number and folds a repeat into the existing lead,
     * so a student who filled the website form last week and messages today is one person, not
     * two — which is the whole reason that logic is shared rather than repeated here.</p>
     */
    private Lead findOrCreate(Incoming message) {
        String normalized = Lead.normalizePhone(message.fromPhone());
        if (normalized != null) {
            List<Lead> existing = leads.findByPhoneNormalized(normalized);
            if (!existing.isEmpty()) {
                return existing.stream()
                        .max(java.util.Comparator.comparing(l ->
                                l.getCreatedAt() == null ? LocalDateTime.MIN : l.getCreatedAt()))
                        .orElse(existing.get(0));
            }
        }

        LeadRequest request = LeadRequest.builder()
                .fullName(usableName(message.profileName()))
                .mobileNumber(message.fromPhone())
                .source(LeadSource.WHATSAPP.name())
                .sourceDetail("Messaged us first")
                .notes("Their first message: " + trim(message.text()))
                .build();

        Lead created = capture.capture(request, LeadSource.WHATSAPP).lead();
        log.info("New lead {} created from an inbound WhatsApp message.", created.getId());
        return created;
    }

    /**
     * Whether this message opens a conversation rather than continuing one.
     *
     * <p>A brand new lead always does. An existing one does only if their last message was long
     * enough ago to count as starting again.</p>
     */
    private boolean startsAConversation(Lead lead) {
        LocalDateTime last = lead.getLastInboundAt();
        return last == null || Duration.between(last, LocalDateTime.now()).compareTo(NEW_CONVERSATION) >= 0;
    }

    /**
     * Whether to send the automatic reply.
     *
     * <p>Only at the start of a conversation. The failure this avoids is worse than a missed
     * auto-reply: a counsellor is mid-conversation with a student, the student answers a
     * question, and the institute robotically sends them the introductory course list again.
     * That reads as nobody being there, which is the opposite of what this is for.</p>
     *
     * <p>Opting out is absolute and is checked here as well as in sending, because a student who
     * asked not to be contacted saying "stop" must not be answered by a machine.</p>
     */
    private boolean shouldAutoReply(Lead lead, boolean firstOfConversation) {
        if (!autoReplyEnabled) return false;
        if (!firstOfConversation) return false;
        if (Boolean.TRUE.equals(lead.getOptedOut())) return false;
        return true;
    }

    private void autoReply(Lead lead) {
        try {
            SendPackService.SendOutcome outcome =
                    packs.send(lead, AUTO_REPLY_PACK, null, null, LeadLifecycleService.Actor.system());
            // Reported as it actually went. A send that WhatsApp refused is already written to
            // the lead's timeline, and a log claiming success would be the one place somebody
            // looks when a student says they never heard back.
            if (outcome != null && outcome.sent()) {
                log.info("Auto-replied to lead {} on WhatsApp.", lead.getId());
            } else {
                log.warn("Auto-reply to lead {} was not delivered: {}", lead.getId(),
                        outcome == null ? "no outcome returned" : outcome.detail());
            }
        } catch (RuntimeException e) {
            // Never fatal. The message is already filed and a counsellor can still pick it up;
            // failing the webhook here would only make Meta redeliver it.
            log.warn("Could not auto-reply to lead {}: {}", lead.getId(), e.getMessage());
        }
    }

    /**
     * The name WhatsApp reports, if it is worth having.
     *
     * <p>A profile name is whatever the student typed into WhatsApp, so it may be a nickname, an
     * emoji or blank. It is better than nothing for a counsellor about to call, and the
     * alternative — a lead called by a phone number — is worse.</p>
     */
    private String usableName(String profileName) {
        String name = profileName == null ? null : profileName.trim();
        return name == null || name.isEmpty() ? "WhatsApp enquiry" : name;
    }

    @SuppressWarnings("unchecked")
    private String firstProfileName(Map<String, Object> value) {
        Object contacts = value.get("contacts");
        if (!(contacts instanceof List<?> list) || list.isEmpty()) return null;
        if (!(list.get(0) instanceof Map<?, ?> contact)) return null;
        Object profile = ((Map<String, Object>) contact).get("profile");
        if (!(profile instanceof Map<?, ?> p)) return null;
        return str(((Map<String, Object>) p).get("name"));
    }

    /**
     * The readable content of a message, whatever type it is.
     *
     * <p>Students send images of their marksheet and voice notes asking about fees. Those cannot
     * be read here, but the fact that something arrived is exactly what a counsellor needs to
     * know, so the type is recorded rather than the message being dropped.</p>
     */
    @SuppressWarnings("unchecked")
    private String textOf(Map<String, Object> m) {
        Object text = m.get("text");
        if (text instanceof Map<?, ?> t) {
            String body = str(((Map<String, Object>) t).get("body"));
            if (body != null) return body;
        }
        Object interactive = m.get("interactive");
        if (interactive instanceof Map<?, ?> i) {
            Object reply = ((Map<String, Object>) i).get("button_reply");
            if (reply == null) reply = ((Map<String, Object>) i).get("list_reply");
            if (reply instanceof Map<?, ?> r) {
                String title = str(((Map<String, Object>) r).get("title"));
                if (title != null) return title;
            }
        }
        Object button = m.get("button");
        if (button instanceof Map<?, ?> b) {
            String t = str(((Map<String, Object>) b).get("text"));
            if (t != null) return t;
        }
        String type = str(m.get("type"));
        return "[" + (type == null ? "message" : type) + " — open WhatsApp to view it]";
    }

    private static String str(Object o) {
        if (!(o instanceof String s)) return null;
        String t = s.trim();
        return t.isEmpty() ? null : t;
    }

    private static String trim(String s) {
        if (s == null) return "";
        String t = s.strip().replaceAll("\\s+", " ");
        return t.length() <= 300 ? t : t.substring(0, 297) + "…";
    }
}
