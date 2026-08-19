package com.devanshedutech.service;

import com.devanshedutech.model.AssetLink;
import com.devanshedutech.model.Lead;
import com.devanshedutech.model.crm.ActivityType;
import com.devanshedutech.model.crm.Direction;
import com.devanshedutech.repository.AssetLinkRepository;
import com.devanshedutech.repository.LeadRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.security.SecureRandom;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * Counts who actually opens what was sent to them.
 *
 * <p>A student who opens the syllabus three times in an evening is a stronger buying signal
 * than anything they say on a call, and it was completely invisible: assets went out as plain
 * URLs and disappeared. Every tracked asset is now sent as a link belonging to that one lead,
 * which records the open and forwards them on.</p>
 *
 * <p>Two things stop this producing signals that are not real, which matters more here than
 * catching every open. Both would otherwise show a counsellor interest that does not exist and
 * send them chasing it.</p>
 */
@Slf4j
@Service
public class AssetTrackingService {

    /**
     * Opens closer together than this count once.
     *
     * <p>A single tap can produce several requests — a redirect followed by a range request for
     * a PDF, or the browser reloading after the app switches. Counting each one would turn one
     * glance into "opened it four times", which reads as strong interest and is not.</p>
     */
    private static final Duration SAME_VISIT = Duration.ofMinutes(2);

    /**
     * Fragments of the user agents that fetch a link without a person involved.
     *
     * <p>WhatsApp fetches every link a message contains to build its preview card, before the
     * student has seen anything. Left uncounted for, every asset would register as opened the
     * instant it was sent, which is exactly the false positive that would make the whole signal
     * worthless.</p>
     */
    private static final List<String> ROBOTS = List.of(
            "whatsapp", "facebookexternalhit", "facebot", "telegrambot", "twitterbot",
            "slackbot", "linkedinbot", "discordbot", "skypeuripreview", "bot", "crawler",
            "spider", "preview", "curl", "wget", "python-requests", "okhttp", "headlesschrome");

    private static final SecureRandom RANDOM = new SecureRandom();
    private static final String ALPHABET = "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789";

    private final AssetLinkRepository links;
    private final LeadRepository leads;
    private final LeadLifecycleService lifecycle;

    @Value("${app.crm.public-base-url:}")
    private String publicBaseUrl;

    public AssetTrackingService(AssetLinkRepository links, LeadRepository leads,
                                LeadLifecycleService lifecycle) {
        this.links = links;
        this.leads = leads;
        this.lifecycle = lifecycle;
    }

    /**
     * A tracked URL for this lead and asset, or the plain one if it cannot be tracked.
     *
     * <p>Falls back whenever there is no public address configured. A tracking link pointing at
     * localhost is not a degraded feature, it is a broken attachment on a student's phone, and
     * a working file matters more than a statistic.</p>
     */
    @Transactional
    public String trackedUrl(Lead lead, String assetKey, String assetName, String targetUrl,
                             boolean tracked) {
        if (!tracked || lead == null || targetUrl == null) return targetUrl;
        String base = baseUrl();
        if (base == null) {
            log.debug("No public base URL, so {} is sent untracked.", assetKey);
            return targetUrl;
        }

        // One link per lead and asset, so opening the same syllabus next week adds to the same
        // count rather than starting a new one nobody joins up.
        AssetLink link = links.findByLeadIdAndAssetKey(lead.getId(), assetKey)
                .orElseGet(() -> AssetLink.builder()
                        .token(newToken())
                        .leadId(lead.getId())
                        .assetKey(assetKey)
                        .createdAt(LocalDateTime.now())
                        .openCount(0)
                        .build());
        link.setTargetUrl(absolute(targetUrl, base));
        link.setAssetName(assetName);
        links.save(link);

        return base + "/api/public/a/" + link.getToken();
    }

    /** Where a token points, and whether this request should count as a person opening it. */
    public record Opened(String targetUrl, boolean counted) {}

    /**
     * Records an open and returns where to send the visitor.
     *
     * <p>Returns empty for an unknown token so the caller can answer with a plain not-found
     * rather than redirecting anywhere a guessed token asks for.</p>
     */
    @Transactional
    public Optional<Opened> open(String token, String userAgent) {
        Optional<AssetLink> found = links.findById(token);
        if (found.isEmpty()) return Optional.empty();
        AssetLink link = found.get();

        if (isRobot(userAgent)) {
            log.debug("Link {} fetched by {}, not counted.", token, userAgent);
            return Optional.of(new Opened(link.getTargetUrl(), false));
        }

        LocalDateTime now = LocalDateTime.now();
        if (link.getLastOpenedAt() != null
                && Duration.between(link.getLastOpenedAt(), now).compareTo(SAME_VISIT) < 0) {
            return Optional.of(new Opened(link.getTargetUrl(), false));
        }

        boolean first = link.opens() == 0;
        link.setOpenCount(link.opens() + 1);
        link.setLastOpenedAt(now);
        if (first) link.setFirstOpenedAt(now);
        links.save(link);

        recordOnTimeline(link, first);
        return Optional.of(new Opened(link.getTargetUrl(), true));
    }

    /**
     * Writes the open into the lead's timeline, where a counsellor will actually see it.
     *
     * <p>A number on a screen nobody opens is not a signal. This does not touch the follow-up
     * schedule: an open is interest, not a reply, and moving the next touch on a file view would
     * quietly rewrite the SOP's cadence.</p>
     */
    private void recordOnTimeline(AssetLink link, boolean first) {
        leads.findById(link.getLeadId()).ifPresent(lead -> {
            String what = link.getAssetName() == null ? link.getAssetKey() : link.getAssetName();
            String detail = first
                    ? "Opened " + what + " for the first time."
                    : "Opened " + what + " again — " + link.opens() + " times now.";
            lifecycle.log(lead, ActivityType.SYSTEM, null, Direction.INBOUND,
                    "Opened " + what, detail, LeadLifecycleService.Actor.system());
        });
    }

    /** Everything this lead has opened, most recent first. */
    public List<AssetLink> forLead(String leadId) {
        return links.findByLeadIdOrderByLastOpenedAtDesc(leadId).stream()
                .filter(l -> l.opens() > 0)
                .toList();
    }

    private boolean isRobot(String userAgent) {
        // No user agent at all is treated as a robot. Every real browser sends one, and the
        // safer mistake is to miss an open rather than invent one.
        if (userAgent == null || userAgent.isBlank()) return true;
        String ua = userAgent.toLowerCase(Locale.ROOT);
        return ROBOTS.stream().anyMatch(ua::contains);
    }

    private String baseUrl() {
        if (publicBaseUrl == null || publicBaseUrl.isBlank()) return null;
        return publicBaseUrl.endsWith("/")
                ? publicBaseUrl.substring(0, publicBaseUrl.length() - 1)
                : publicBaseUrl;
    }

    /** Relative asset paths are served by this application; anything else is already absolute. */
    private String absolute(String url, String base) {
        return url.startsWith("/") ? base + url : url;
    }

    private static String newToken() {
        StringBuilder sb = new StringBuilder(24);
        for (int i = 0; i < 24; i++) sb.append(ALPHABET.charAt(RANDOM.nextInt(ALPHABET.length())));
        return sb.toString();
    }
}
