package com.devanshedutech.service;

import com.devanshedutech.channel.WhatsAppChannel;
import com.devanshedutech.channel.WhatsAppSender;
import com.devanshedutech.model.Asset;
import com.devanshedutech.model.Lead;
import com.devanshedutech.model.SendPack;
import com.devanshedutech.model.crm.ActivityType;
import com.devanshedutech.model.crm.Direction;
import com.devanshedutech.model.Course;
import com.devanshedutech.repository.AssetRepository;
import com.devanshedutech.repository.BatchRepository;
import com.devanshedutech.repository.CourseRepository;
import com.devanshedutech.repository.LeadRepository;
import com.devanshedutech.repository.SendPackRepository;
import com.devanshedutech.service.LeadLifecycleService.Actor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Prepares what a counsellor is about to send, and records it once they have.
 *
 * <p>Sending itself is deliberately not automated. The message is composed here, the counsellor
 * reviews and sends it from their own WhatsApp, and confirms — which keeps a person on every
 * message a student receives. When the WhatsApp Business API is connected, only
 * {@code deliver} changes; everything above it stays as it is.</p>
 */
@Slf4j
@Service
public class SendPackService {

    /** WhatsApp allows free-form replies for 24 hours after the student's last message. */
    private static final long REPLY_WINDOW_MINUTES = 24 * 60;

    private final SendPackRepository packs;
    private final AssetRepository assets;
    private final LeadRepository leads;
    private final CourseRepository courses;
    private final BatchRepository batches;
    private final LeadLifecycleService lifecycle;
    private final WhatsAppSender whatsapp;
    private final AssetTrackingService tracking;

    /** The institute's WhatsApp number, digits only, used to build the deep link. */
    @Value("${app.crm.whatsapp.number:}")
    private String whatsappNumber;

    public SendPackService(SendPackRepository packs, AssetRepository assets,
                           LeadRepository leads, CourseRepository courses,
                           BatchRepository batches, LeadLifecycleService lifecycle,
                           WhatsAppSender whatsapp, AssetTrackingService tracking) {
        this.packs = packs;
        this.assets = assets;
        this.leads = leads;
        this.courses = courses;
        this.batches = batches;
        this.lifecycle = lifecycle;
        this.whatsapp = whatsapp;
        this.tracking = tracking;
    }

    /** One asset, resolved for a particular lead. */
    public record ResolvedAsset(String key, String name, String type, String url,
                                String sizeLabel, boolean tracked) {}

    /** Everything the client needs to show and send a pack. */
    public record Prepared(String packKey, String packName, String situation, String message,
                           List<ResolvedAsset> assets, Long replyWindowMinutesLeft,
                           boolean freeReplyOpen, String whatsappUrl, String note,
                           boolean sendsAutomatically, String channel) {}

    /** What happened when a pack was sent. */
    public record SendOutcome(boolean sent, String status, String detail,
                              String handoffUrl, String channel) {}

    /** Every placeholder a template may use, so the editor can list them rather than guess. */
    public static final Map<String, String> PLACEHOLDERS = Map.of(
            "first_name", "The student's first name only",
            "full_name", "Their full name",
            "course", "The course they asked about",
            "city", "Their city or area",
            "counsellor", "The counsellor sending it",
            "batch", "The next scheduled intake, described in full");

    public List<SendPack> all() {
        return packs.findAllByOrderByNameAsc().stream().filter(SendPack::isActive).toList();
    }

    public List<Asset> allAssets() {
        return assets.findAll().stream().filter(Asset::isActive).toList();
    }

    public SendPack byKey(String key) {
        return packs.findByKey(key).filter(SendPack::isActive).orElseThrow(() ->
                new ResponseStatusException(HttpStatus.NOT_FOUND, "That message pack no longer exists."));
    }

    /**
     * Fills a pack in for one lead.
     *
     * <p>Reports whether WhatsApp's free-reply window is open, because it decides what the
     * counsellor is allowed to send: inside it, ordinary messages; outside it, only an approved
     * template. Showing that plainly is the difference between a counsellor understanding the
     * rule and believing the software is broken.</p>
     */
    public Prepared prepare(Lead lead, String packKey, String counsellorName) {
        SendPack pack = byKey(packKey);

        Long windowLeft = null;
        if (lead.getLastInboundAt() != null) {
            long used = Duration.between(lead.getLastInboundAt(), LocalDateTime.now()).toMinutes();
            long left = REPLY_WINDOW_MINUTES - used;
            if (left > 0) windowLeft = left;
        }
        boolean open = windowLeft != null;

        Map<String, String> vars = variables(lead, counsellorName);
        String message = fill(pack.getCoverTemplate(), vars);

        List<ResolvedAsset> resolved = new ArrayList<>();
        for (String key : pack.assets()) {
            assets.findByKey(key).filter(Asset::isActive).ifPresent(a -> {
                String url = fill(a.getUrl(), vars);
                // A lead's course is free text and may not match the catalogue, which would
                // leave the per-course brochure path pointing at nothing. Sending a student a
                // broken download is worse than sending the general brochure, so fall back.
                if (url.endsWith("/brochure/download/")) {
                    url = "/api/public/brochure/download";
                    log.debug("Lead has no matched course; using the general brochure for asset {}", key);
                }
                String name = fill(a.getName(), vars);
                // Tracked assets go out as this lead's own link, so opening one is recorded
                // against them. Untrackable ones fall back to the plain URL rather than
                // sending a student a link that does not work.
                String sent = tracking.trackedUrl(lead, a.getKey(), name, url, a.isTracked());
                resolved.add(new ResolvedAsset(a.getKey(), name, a.getType(),
                        sent, a.getSizeLabel(), a.isTracked()));
            });
        }

        boolean auto = whatsapp.sendsAutomatically();
        String note = open
                ? resolved.size() + " attachment(s) plus the message"
                  + (auto ? ", sent straight to the student." : ", sent from your own WhatsApp.")
                : "This student has not messaged in over 24 hours, so WhatsApp only allows an "
                  + "approved template until they reply. The opener goes now; the rest follows "
                  + "once they answer.";

        return new Prepared(pack.getKey(), pack.getName(), pack.getSituation(), message, resolved,
                windowLeft, open, whatsappUrl(lead, message), note, auto, whatsapp.active().name());
    }

    /**
     * A deep link that opens WhatsApp with the message ready to send.
     *
     * <p>This is the manual channel: the counsellor still presses send. It works today with no
     * provider account, and every message is demonstrably sent by a person — which is what the
     * SOP asks for.</p>
     */
    private String whatsappUrl(Lead lead, String message) {
        String to = Lead.normalizePhone(lead.getMobileNumber());
        if (to == null) return null;
        String withCountry = to.length() == 10 ? "91" + to : to;
        return "https://wa.me/" + withCountry + "?text="
                + URLEncoder.encode(message, StandardCharsets.UTF_8);
    }

    /**
     * Resolves the lead's free-text course against the catalogue.
     *
     * <p>Leads record what a student typed or picked on the website, which does not always match
     * a course record. Matching by name here means the per-course brochure works for the leads
     * where it can, without waiting for the catalogue to be reconciled.</p>
     */
    private String resolveCourseId(Lead lead) {
        if (lead.getCourseId() != null && !lead.getCourseId().isBlank()) return lead.getCourseId();
        String wanted = lead.getCourseInterested();
        if (wanted == null || wanted.isBlank()) return "";
        return courses.findAll().stream()
                .filter(c -> c.getName() != null && c.getName().equalsIgnoreCase(wanted.trim()))
                .map(Course::getId)
                .findFirst()
                .orElse("");
    }

    private Map<String, String> variables(Lead lead, String counsellorName) {
        String first = lead.getFullName() == null ? "there" : lead.getFullName().split("\\s+")[0];
        return Map.of(
                "first_name", first,
                "full_name", lead.getFullName() == null ? first : lead.getFullName(),
                "course", lead.getCourseInterested() == null ? "the course" : lead.getCourseInterested(),
                "course_id", resolveCourseId(lead),
                "city", lead.getCityName() == null ? "Parbhani" : lead.getCityName(),
                "counsellor", counsellorName == null ? "the team" : counsellorName,
                // The ladder's day-twelve and day-eighteen steps both quote a start date, so a
                // pack has to be able to. When no intake is scheduled it says so rather than
                // leaving a placeholder in a student's message.
                "batch", nextBatchDescription(lead));
    }

    private String nextBatchDescription(Lead lead) {
        java.time.LocalDate today = java.time.LocalDate.now();
        String courseId = resolveCourseId(lead);
        var forCourse = courseId.isBlank() ? java.util.List.<com.devanshedutech.model.Batch>of()
                : batches.findUpcomingForCourse(courseId, today);
        var chosen = !forCourse.isEmpty() ? forCourse.get(0)
                : batches.findUpcoming(today).stream().findFirst().orElse(null);
        return chosen == null ? "the next batch" : chosen.describe();
    }

    private String fill(String template, Map<String, String> vars) {
        if (template == null) return "";
        String out = template;
        for (Map.Entry<String, String> e : vars.entrySet()) {
            out = out.replace("{{" + e.getKey() + "}}", e.getValue());
        }
        return out;
    }

    /**
     * Edits a pack.
     *
     * <p>These messages go to students, so the wording is worth changing without a deployment —
     * a phrase that works in Parbhani may not be the one written here. The placeholder syntax is
     * validated on save: an unknown one would otherwise reach a student as literal braces in the
     * middle of a sentence.</p>
     */
    @Transactional
    public SendPack update(String key, String name, String situation, String coverTemplate,
                           List<String> assetKeys, Boolean active) {
        SendPack pack = byKey(key);

        if (coverTemplate != null && !coverTemplate.isBlank()) {
            List<String> unknown = unknownPlaceholders(coverTemplate);
            if (!unknown.isEmpty()) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                        "This message uses a placeholder that does not exist: "
                        + String.join(", ", unknown)
                        + ". A student would receive it as literal text. Available: "
                        + String.join(", ", PLACEHOLDERS.keySet()));
            }
            pack.setCoverTemplate(coverTemplate.trim());
        }
        if (name != null && !name.isBlank()) pack.setName(name.trim());
        if (situation != null) pack.setSituation(situation);
        if (active != null) pack.setActive(active);

        if (assetKeys != null) {
            List<String> missing = assetKeys.stream()
                    .filter(k -> assets.findByKey(k).isEmpty()).toList();
            if (!missing.isEmpty()) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                        "No such attachment: " + String.join(", ", missing));
            }
            pack.setAssetKeys(String.join(",", assetKeys));
        }

        log.info("Message pack '{}' edited", pack.getName());
        return packs.save(pack);
    }

    /** Placeholders in a template that nothing will ever fill in. */
    List<String> unknownPlaceholders(String template) {
        java.util.regex.Matcher m = java.util.regex.Pattern
                .compile("\\{\\{\\s*([a-z_]+)\\s*\\}\\}").matcher(template);
        List<String> unknown = new ArrayList<>();
        while (m.find()) {
            String name = m.group(1);
            if (!PLACEHOLDERS.containsKey(name) && !unknown.contains(name)) unknown.add(name);
        }
        return unknown;
    }

    /**
     * Sends a pack to the student.
     *
     * <p>The message the counsellor edited is what goes, not the stored template. Whether it
     * leaves automatically or opens in their WhatsApp depends on whether a provider is
     * configured, and either way the timeline records only what actually happened: a provider
     * that accepted it is "sent", a hand-off is not recorded until the counsellor confirms.</p>
     */
    @Transactional
    public SendOutcome send(Lead lead, String packKey, String editedMessage,
                            List<String> includedAssets, Actor actor) {
        SendPack pack = byKey(packKey);
        String message = editedMessage == null || editedMessage.isBlank()
                ? prepare(lead, packKey, actor == null ? null : actor.name()).message()
                : editedMessage;

        Prepared prepared = prepare(lead, packKey, actor == null ? null : actor.name());
        List<WhatsAppChannel.Attachment> attachments = prepared.assets().stream()
                .filter(a -> includedAssets == null || includedAssets.isEmpty()
                          || includedAssets.contains(a.key()))
                .map(a -> new WhatsAppChannel.Attachment(a.name(), a.type(), a.url()))
                .toList();

        // Normalised first. A stored number is whatever the student typed — "+91 98765 43210"
        // — and a wa.me link containing a space or a plus is simply broken.
        WhatsAppChannel.SendResult result = whatsapp.send(
                Lead.normalizePhone(lead.getMobileNumber()), lead.getFullName(), message, attachments);

        if (result.sent()) {
            recordSent(lead, packKey, attachments.stream()
                    .map(WhatsAppChannel.Attachment::name).toList(), actor);
        } else if (result.handoffUrl() == null) {
            // A genuine failure is written down too. A message that did not go and left no
            // trace is how a lead ends up looking contacted when nobody contacted them.
            var entry = lifecycle.log(lead, ActivityType.WHATSAPP, null, Direction.OUTBOUND,
                    "Send failed: " + pack.getName(), result.detail(), actor);
            entry.setPackKey(packKey);
            entry.setDeliveryStatus("failed");
        }

        return new SendOutcome(result.sent(), result.status(), result.detail(),
                result.handoffUrl(), whatsapp.active().name());
    }

    /**
     * Records that a pack was sent.
     *
     * <p>Called after the counsellor has actually sent it, so the timeline reflects what
     * happened rather than what was prepared. Marked "sent" rather than "delivered": nothing
     * here can observe a delivery receipt, and claiming otherwise would put a fact on the
     * timeline that nobody checked.</p>
     */
    @Transactional
    public Lead recordSent(Lead lead, String packKey, List<String> sentAssets, Actor actor) {
        SendPack pack = byKey(packKey);
        String attachments = sentAssets == null || sentAssets.isEmpty()
                ? "no attachments"
                : String.join(", ", sentAssets);

        var entry = lifecycle.log(lead, ActivityType.WHATSAPP, null, Direction.OUTBOUND,
                "Sent: " + pack.getName(),
                "Sent from WhatsApp by hand. Included: " + attachments + ".", actor);
        entry.setPackKey(packKey);
        entry.setDeliveryStatus("sent");

        LocalDateTime now = LocalDateTime.now();
        lead.setLastTouchAt(now);
        lead.setLastTouchNote("Sent " + pack.getName());
        if (lead.getFirstRespondedAt() == null) {
            lead.setFirstRespondedAt(now);
        }
        return leads.save(lead);
    }
}
