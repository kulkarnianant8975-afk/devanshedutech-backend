package com.devanshedutech.service;

import com.devanshedutech.dto.LeadDTOs.LeadRequest;
import com.devanshedutech.model.Lead;
import com.devanshedutech.model.crm.ActivityType;
import com.devanshedutech.model.crm.Direction;
import com.devanshedutech.model.crm.LeadSource;
import com.devanshedutech.model.crm.Stage;
import com.devanshedutech.model.crm.StudentBackground;
import com.devanshedutech.repository.LeadRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;

/**
 * Takes an enquiry from the public forms, the chatbot, or an integration and puts it in the
 * pipeline.
 *
 * <p>Two things happen here that did not before. Attribution is recorded, so the institute can
 * finally answer which channel produces admissions rather than which produces enquiries. And
 * duplicates are detected by phone number, because a student who fills the form twice used to
 * become two leads that two counsellors would then work in parallel.</p>
 */
@Slf4j
@Service
public class LeadCaptureService {

    /**
     * A repeat enquiry inside this window is treated as the same conversation rather than a new
     * lead. Longer than a few minutes because students often submit twice; short enough that a
     * genuine re-enquiry months later starts fresh.
     */
    private static final Duration SAME_ENQUIRY_WINDOW = Duration.ofDays(30);

    private final LeadRepository leadRepository;
    private final LeadLifecycleService lifecycle;
    private final DutyRosterService roster;

    public LeadCaptureService(LeadRepository leadRepository, LeadLifecycleService lifecycle,
                              DutyRosterService roster) {
        this.leadRepository = leadRepository;
        this.lifecycle = lifecycle;
        this.roster = roster;
    }

    /** Outcome of a capture, so the caller knows whether a duplicate was merged. */
    public record Captured(Lead lead, boolean duplicate) {}

    @Transactional
    public Captured capture(LeadRequest request, LeadSource fallbackSource) {
        String name = trimToNull(request.getFullName());
        String phone = trimToNull(request.getMobileNumber());

        if (name == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Please enter the student's name.");
        }
        String normalized = Lead.normalizePhone(phone);
        if (normalized == null || normalized.length() < 10) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Please enter a valid 10-digit mobile number.");
        }

        LeadSource source = resolveSource(request, fallbackSource);

        Lead existing = findRecentDuplicate(normalized);
        if (existing != null) {
            return new Captured(mergeIntoExisting(existing, request, source), true);
        }

        Lead lead = Lead.builder()
                .id(UUID.randomUUID().toString())
                .fullName(name)
                .email(trimToNull(request.getEmail()))
                .mobileNumber(phone)
                .phoneNormalized(normalized)
                .education(trimToNull(request.getEducation()))
                .cityName(trimToNull(request.getCityName()))
                .courseInterested(trimToNull(request.getCourseInterested()))
                .background(resolveBackground(request))
                .source(source)
                .sourceDetail(trimToNull(request.getSourceDetail()))
                .utmSource(trimToNull(request.getUtmSource()))
                .utmMedium(trimToNull(request.getUtmMedium()))
                .utmCampaign(trimToNull(request.getUtmCampaign()))
                .referrerUrl(trimToNull(request.getReferrerUrl()))
                .referredById(trimToNull(request.getReferredById()))
                .notes(trimToNull(request.getNotes()))
                .stage(Stage.NEW)
                .callAttempts(0)
                .updatesOnly(false)
                .optedOut(false)
                .createdAt(LocalDateTime.now())
                .build();

        // Give it an owner now if someone is on duty. An enquiry belonging to nobody waits until
        // a counsellor happens to look at the list, and that wait is what loses students.
        boolean assigned = roster.assignIfUnowned(lead, LocalDateTime.now());

        Lead saved = leadRepository.save(lead);

        lifecycle.log(saved, ActivityType.CAPTURE, null, Direction.INBOUND, "Lead captured",
                "Arrived via " + source.getLabel()
                        + (lead.getSourceDetail() == null ? "" : " (" + lead.getSourceDetail() + ")")
                        + (lead.getUtmCampaign() == null ? "" : ", campaign " + lead.getUtmCampaign())
                        + (assigned ? ". Assigned to whoever was on duty. Not yet graded."
                                    : ". Nobody was on duty, so it is unassigned and needs picking up."),
                LeadLifecycleService.Actor.system());

        return new Captured(saved, false);
    }

    /**
     * Finds a recent lead with the same number. Closed leads are included on purpose: a student
     * who was marked lost and comes back is the same person, and reopening their history is far
     * more useful to a counsellor than a blank second record.
     */
    private Lead findRecentDuplicate(String normalized) {
        List<Lead> matches = leadRepository.findByPhoneNormalized(normalized);
        if (matches.isEmpty()) return null;
        LocalDateTime cutoff = LocalDateTime.now().minus(SAME_ENQUIRY_WINDOW);
        return matches.stream()
                .filter(l -> l.getCreatedAt() != null && l.getCreatedAt().isAfter(cutoff))
                .max(Comparator.comparing(Lead::getCreatedAt))
                .orElse(null);
    }

    /**
     * Folds a repeat enquiry into the lead that already exists. Nothing already known is
     * overwritten — a second submission with a blank city must not erase the city a counsellor
     * confirmed on a call — but a re-enquiry is a strong signal, so it is logged and pulls the
     * next touch forward to today.
     */
    private Lead mergeIntoExisting(Lead lead, LeadRequest request, LeadSource source) {
        boolean courseChanged = false;
        String newCourse = trimToNull(request.getCourseInterested());

        if (isBlank(lead.getEmail())) lead.setEmail(trimToNull(request.getEmail()));
        if (isBlank(lead.getCityName())) lead.setCityName(trimToNull(request.getCityName()));
        if (isBlank(lead.getEducation())) lead.setEducation(trimToNull(request.getEducation()));
        if (lead.getBackground() == null) lead.setBackground(resolveBackground(request));
        if (isBlank(lead.getCourseInterested())) {
            lead.setCourseInterested(newCourse);
        } else if (newCourse != null && !newCourse.equalsIgnoreCase(lead.getCourseInterested())) {
            courseChanged = true;
        }

        lead.setLastInboundAt(LocalDateTime.now());
        if (lead.isActive() && (lead.getNextTouchOn() == null
                || lead.getNextTouchOn().isAfter(java.time.LocalDate.now()))) {
            lifecycle.setNextTouch(lead, java.time.LocalDate.now(), "Enquired again — respond today");
        }

        StringBuilder detail = new StringBuilder("The same number enquired again via ")
                .append(source.getLabel()).append(".");
        if (courseChanged) {
            detail.append(" This time they asked about ").append(newCourse)
                  .append(", previously ").append(lead.getCourseInterested())
                  .append(" — worth confirming which they want.");
        }

        Lead saved = leadRepository.save(lead);
        lifecycle.log(saved, ActivityType.CAPTURE, null, Direction.INBOUND,
                "Repeat enquiry", detail.toString(), LeadLifecycleService.Actor.system());

        log.info("Repeat enquiry merged into existing lead {}", saved.getId());
        return saved;
    }

    /**
     * Works out where the enquiry came from. An explicit source wins; otherwise the UTM tags
     * are read; otherwise the caller's own default applies. Guessing is avoided — an unknown
     * origin is recorded as OTHER rather than attributed to a channel that did not earn it.
     */
    private LeadSource resolveSource(LeadRequest request, LeadSource fallback) {
        LeadSource explicit = LeadSource.parse(request.getSource(), null);
        if (explicit != null) return explicit;

        LeadSource fromUtm = LeadSource.fromUtm(request.getUtmSource(), request.getUtmMedium());
        if (fromUtm != null) return fromUtm;

        if (trimToNull(request.getReferredById()) != null) return LeadSource.REFERRAL;
        return fallback == null ? LeadSource.OTHER : fallback;
    }

    private StudentBackground resolveBackground(LeadRequest request) {
        StudentBackground explicit = null;
        if (request.getBackground() != null && !request.getBackground().isBlank()) {
            try {
                explicit = StudentBackground.valueOf(
                        request.getBackground().trim().toUpperCase(java.util.Locale.ROOT).replace(' ', '_'));
            } catch (IllegalArgumentException ignored) {
                explicit = null;
            }
        }
        return explicit != null ? explicit : StudentBackground.parse(request.getEducation());
    }

    private static String trimToNull(String s) {
        if (s == null) return null;
        String t = s.trim();
        return t.isEmpty() ? null : t;
    }

    private static boolean isBlank(String s) {
        return s == null || s.isBlank();
    }
}
