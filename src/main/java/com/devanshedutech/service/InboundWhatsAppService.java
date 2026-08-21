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

    /** Sent once a student has picked a course, so it can name and attach the right one. */
    private static final String COURSE_CHOSEN_PACK = "course_chosen";

    /** Prefix on a menu row id, so a tap is never confused with anything else. */
    private static final String COURSE_ROW = "course:";

    /** A row that opens a second menu rather than choosing a course. */
    private static final String AREA_ROW = "area:";

    /** The row offered when the catalogue does not fit, so nothing is silently hidden. */
    private static final String OTHER_ROW = "course:other";

    /** WhatsApp's own cap. Exceeding it rejects the entire message rather than trimming it. */
    private static final int MENU_LIMIT = 10;

    /**
     * A gap this long means the student is starting a fresh conversation rather than continuing.
     *
     * <p>Defaults to WhatsApp's own free-reply window, which is the natural boundary: if the
     * window had closed, they are starting again. Configurable mainly so the flow can be
     * exercised without waiting a day between messages — set it to a minute while testing.</p>
     */
    private Duration newConversation = Duration.ofHours(24);

    @Value("${app.crm.whatsapp.conversation-gap-minutes:1440}")
    void setConversationGapMinutes(long minutes) {
        this.newConversation = Duration.ofMinutes(Math.max(1, minutes));
    }

    private final InboundMessageRepository inbound;
    private final LeadRepository leads;
    private final LeadCaptureService capture;
    private final LeadLifecycleService lifecycle;
    private final SendPackService packs;
    private final CourseMatcher courseMatcher;
    private final com.devanshedutech.repository.CourseRepository courses;
    private final com.devanshedutech.channel.WhatsAppSender sender;

    @Value("${app.crm.whatsapp.auto-reply:true}")
    private boolean autoReplyEnabled;

    public InboundWhatsAppService(InboundMessageRepository inbound, LeadRepository leads,
                                  LeadCaptureService capture, LeadLifecycleService lifecycle,
                                  SendPackService packs, CourseMatcher courseMatcher,
                                  com.devanshedutech.repository.CourseRepository courses,
                                  com.devanshedutech.channel.WhatsAppSender sender) {
        this.inbound = inbound;
        this.leads = leads;
        this.capture = capture;
        this.lifecycle = lifecycle;
        this.packs = packs;
        this.courseMatcher = courseMatcher;
        this.courses = courses;
        this.sender = sender;
    }

    /** One message, already pulled out of Meta's envelope. */
    public record Incoming(String messageId, String fromPhone, String profileName, String text,
                           String selectionId) {
        /** A student who tapped a menu row rather than typing. */
        public boolean isSelection() { return selectionId != null && !selectionId.isBlank(); }
    }

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

                    out.add(new Incoming(id, from, profileName, textOf(m), selectionOf(m)));
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

        // Tapping an area is not a choice of course — it opens the next menu. Handled before
        // anything else so it is never mistaken for one.
        if (message.isSelection() && message.selectionId().startsWith(AREA_ROW)) {
            String area = message.selectionId().substring(AREA_ROW.length());
            lifecycle.recordInbound(lead, message.text(), LeadLifecycleService.Actor.system());
            List<com.devanshedutech.model.Course> inArea =
                    byArea().getOrDefault(area, java.util.List.of());
            if (!sendCoursesIn(lead, inArea, "Here is what we teach in " + area + ".")) {
                log.warn("Could not show courses for area '{}' to lead {}", area, lead.getId());
            }
            return Optional.of(lead);
        }

        // A tap is unambiguous, so it is honoured ahead of anything read out of free text.
        boolean chose = message.isSelection() && applySelection(lead, message.selectionId());
        if (!chose) {
            // Read the course out of what they wrote, before the reply is recorded, so the
            // timeline reads in the order it happened.
            noteCourse(lead, message.text());
        }

        // Everything a reply means — the reply window, the grade promotion on a buying signal,
        // reopening a lost lead, today's next touch — already lives here.
        lifecycle.recordInbound(lead, message.text(), LeadLifecycleService.Actor.system());

        if (chose) {
            // They have told us what they want. Send that course's syllabus and fees rather than
            // the introduction again, and leave the rest to a counsellor.
            sendPack(lead, COURSE_CHOSEN_PACK);
        } else if (shouldAutoReply(lead, firstOfConversation)) {
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
        return last == null || Duration.between(last, LocalDateTime.now()).compareTo(newConversation) >= 0;
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

    /**
     * Fills in the course when a student names one and we do not already know it.
     *
     * <p>Never overwrites. A counsellor who confirmed the course on a call knows more than a
     * phrase in a message, and a student mentioning another course in passing must not silently
     * move them.</p>
     */
    private void noteCourse(Lead lead, String text) {
        if (lead.getCourseInterested() != null && !lead.getCourseInterested().isBlank()) return;

        courseMatcher.match(text).ifPresent(course -> {
            lead.setCourseInterested(course.getName());
            lead.setCourseId(course.getId());
            leads.save(lead);
            lifecycle.log(lead, com.devanshedutech.model.crm.ActivityType.SYSTEM, null,
                    com.devanshedutech.model.crm.Direction.INTERNAL,
                    "Course identified",
                    "They said they want " + course.getName() + ", so the lead is now filed under "
                    + "it — the message packs and the course brochure follow from this.",
                    LeadLifecycleService.Actor.system());
            log.info("Lead {} matched to course {}", lead.getId(), course.getName());
        });
    }

    /**
     * Records the course a student tapped.
     *
     * <p>Overwrites whatever was assumed from free text, because a tap is the student saying it
     * themselves rather than us inferring it.</p>
     */
    private boolean applySelection(Lead lead, String selectionId) {
        if (!selectionId.startsWith(COURSE_ROW)) return false;
        if (OTHER_ROW.equals(selectionId)) {
            // Not a course, and deliberately not guessed at. A counsellor asks them.
            lifecycle.log(lead, com.devanshedutech.model.crm.ActivityType.WHATSAPP, null,
                    com.devanshedutech.model.crm.Direction.INBOUND,
                    "Wants something not on the menu",
                    "They picked \"Something else\", so ask what they are looking for.",
                    LeadLifecycleService.Actor.system());
            return false;
        }
        String courseId = selectionId.substring(COURSE_ROW.length());

        return courses.findById(courseId).map(course -> {
            lead.setCourseInterested(course.getName());
            lead.setCourseId(course.getId());
            leads.save(lead);
            lifecycle.log(lead, com.devanshedutech.model.crm.ActivityType.WHATSAPP, null,
                    com.devanshedutech.model.crm.Direction.INBOUND,
                    "Chose " + course.getName(),
                    "They picked it from the menu, so this is what they want — not a guess.",
                    LeadLifecycleService.Actor.system());
            log.info("Lead {} chose course {}", lead.getId(), course.getName());
            return true;
        }).orElse(false);
    }

    /**
     * The opening reply: a tappable list of courses where the channel allows one.
     *
     * <p>A tap answers the only question that matters at this point — which course — and answers
     * it unambiguously. Typed free text has to be guessed at, and "full stack" describes three of
     * them. Where menus are not available the plain introduction goes instead.</p>
     */
    private void autoReply(Lead lead) {
        if (sender.active().supportsMenus() && sendCourseMenu(lead)) return;
        sendPack(lead, AUTO_REPLY_PACK);
    }

    /**
     * Groups courses the way a student would look for them.
     *
     * <p>The stored category is used but not trusted: it is free text typed over months, so
     * "AI" and "Ai" arrive as different groups and would occupy two rows of a ten-row menu
     * saying the same thing.</p>
     */
    private java.util.Map<String, List<com.devanshedutech.model.Course>> byArea() {
        java.util.Map<String, List<com.devanshedutech.model.Course>> areas = new java.util.LinkedHashMap<>();
        courses.findAll().stream()
                .filter(c -> c.getName() != null && !c.getName().isBlank())
                .sorted(java.util.Comparator.comparing(com.devanshedutech.model.Course::getName))
                .forEach(c -> {
                    String raw = c.getCategory() == null || c.getCategory().isBlank()
                            ? "Other" : c.getCategory().trim();
                    String area = Character.toUpperCase(raw.charAt(0))
                            + raw.substring(1).toLowerCase(java.util.Locale.ROOT);
                    areas.computeIfAbsent(area, k -> new java.util.ArrayList<>()).add(c);
                });
        return areas;
    }

    /**
     * The opening menu: areas of study, not the whole catalogue.
     *
     * <p>WhatsApp allows ten rows in a list and refuses the entire message above that. With
     * fifteen courses a flat menu could show nine, which means telling six students the institute
     * does not teach the thing it teaches. Asking for the area first and the course second keeps
     * every course reachable in two taps.</p>
     */
    private boolean sendCourseMenu(Lead lead) {
        java.util.Map<String, List<com.devanshedutech.model.Course>> areas = byArea();
        if (areas.isEmpty()) return false;

        // A single area is not worth a menu of one; go straight to the courses.
        if (areas.size() == 1) {
            return sendCoursesIn(lead, areas.values().iterator().next(), "Tap the course you want");
        }

        List<java.util.Map.Entry<String, List<com.devanshedutech.model.Course>>> ordered =
                new java.util.ArrayList<>(areas.entrySet());
        // Biggest areas first, so the rows most students want are the ones that survive any cut.
        ordered.sort((a, b) -> Integer.compare(b.getValue().size(), a.getValue().size()));

        List<com.devanshedutech.channel.WhatsAppChannel.MenuRow> rows = new java.util.ArrayList<>();
        boolean truncated = ordered.size() > MENU_LIMIT;
        int shown = truncated ? MENU_LIMIT - 1 : ordered.size();

        for (var area : ordered.subList(0, shown)) {
            int n = area.getValue().size();
            rows.add(new com.devanshedutech.channel.WhatsAppChannel.MenuRow(
                    AREA_ROW + area.getKey(), area.getKey(),
                    n == 1 ? area.getValue().get(0).getName() : n + " courses"));
        }
        if (truncated) {
            rows.add(new com.devanshedutech.channel.WhatsAppChannel.MenuRow(
                    OTHER_ROW, "Something else", "Tell us what you are looking for"));
            log.info("Menu shows {} of {} areas; the rest are behind \"Something else\".",
                    shown, ordered.size());
        }

        String first = lead.getFullName() == null ? "there" : lead.getFullName().split("\\s+")[0];
        var result = sender.active().sendMenu(lead.getMobileNumber(),
                "Hi " + first + "! \uD83D\uDC4B Thanks for messaging Devansh Edu-Tech.\n\n"
                + "What are you interested in? Tap an area and I will show you the courses, then "
                + "send the syllabus and fees straight away. A counsellor will call you shortly.",
                "Choose an area", rows);

        if (result.sent()) {
            lifecycle.log(lead, com.devanshedutech.model.crm.ActivityType.WHATSAPP, null,
                    com.devanshedutech.model.crm.Direction.OUTBOUND,
                    "Course menu sent", "They were asked to pick a course.",
                    LeadLifecycleService.Actor.system());
            return true;
        }
        log.warn("Course menu to lead {} was not delivered: {}", lead.getId(), result.detail());
        return false;
    }

    /** The second menu: the courses inside one area. */
    private boolean sendCoursesIn(Lead lead, List<com.devanshedutech.model.Course> list, String prompt) {
        if (list.isEmpty()) return false;

        // The ten-row limit applies here too. One area holding more courses than a menu can show
        // would drop the overflow just as silently as a flat catalogue did, so the last row is a
        // way out rather than an arbitrary tenth course.
        boolean truncated = list.size() > MENU_LIMIT;
        int shown = truncated ? MENU_LIMIT - 1 : list.size();

        List<com.devanshedutech.channel.WhatsAppChannel.MenuRow> rows =
                new java.util.ArrayList<>(list.subList(0, shown).stream()
                        .map(c -> new com.devanshedutech.channel.WhatsAppChannel.MenuRow(
                                COURSE_ROW + c.getId(), c.getName(), c.getDuration()))
                        .toList());
        if (truncated) {
            rows.add(new com.devanshedutech.channel.WhatsAppChannel.MenuRow(
                    OTHER_ROW, "Something else", "Tell us what you are looking for"));
            log.info("Showing {} of {} courses; the rest are behind \"Something else\".",
                    shown, list.size());
        }

        var result = sender.active().sendMenu(lead.getMobileNumber(), prompt, "Choose a course", rows);
        if (result.sent()) {
            lifecycle.log(lead, com.devanshedutech.model.crm.ActivityType.WHATSAPP, null,
                    com.devanshedutech.model.crm.Direction.OUTBOUND,
                    "Course menu sent", "They were shown " + rows.size() + " course(s) to pick from.",
                    LeadLifecycleService.Actor.system());
            return true;
        }
        log.warn("Course menu to lead {} was not delivered: {}", lead.getId(), result.detail());
        return false;
    }

    private void sendPack(Lead lead, String packKey) {
        try {
            SendPackService.SendOutcome outcome =
                    packs.send(lead, packKey, null, null, LeadLifecycleService.Actor.system());
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

    /**
     * The id of a menu row the student tapped, if they tapped one.
     *
     * <p>The id is used rather than the visible label because the label is truncated to fit
     * WhatsApp's limits — matching on it would mean matching on a shortened string.</p>
     */
    @SuppressWarnings("unchecked")
    private String selectionOf(Map<String, Object> m) {
        Object interactive = m.get("interactive");
        if (!(interactive instanceof Map<?, ?> i)) return null;
        Object reply = ((Map<String, Object>) i).get("list_reply");
        if (reply == null) reply = ((Map<String, Object>) i).get("button_reply");
        if (!(reply instanceof Map<?, ?> r)) return null;
        return str(((Map<String, Object>) r).get("id"));
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
