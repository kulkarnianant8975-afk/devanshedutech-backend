package com.devanshedutech.service;

import com.devanshedutech.crm.LeadSpecifications;
import com.devanshedutech.model.Demo;
import com.devanshedutech.model.Lead;
import com.devanshedutech.model.Notification;
import com.devanshedutech.repository.DemoRepository;
import com.devanshedutech.repository.LeadRepository;
import com.devanshedutech.repository.NotificationRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

/**
 * Tells counsellors what needs their attention, rather than waiting to be asked.
 *
 * <p>Everything here is deduplicated by a key describing the fact, not the announcement. The
 * morning sweep runs over the same overdue leads every day, and without that a counsellor would
 * arrive to fifty copies of a notice they read on Monday — at which point they stop reading
 * notifications at all, and the feature is worse than not having it.</p>
 *
 * <p>Notices are also deliberately few. Only things a person can act on today are worth
 * interrupting them for.</p>
 */
@Slf4j
@Service
public class NotificationService {

    public static final String LEAD_ASSIGNED = "LEAD_ASSIGNED";
    public static final String FOLLOW_UP_DUE = "FOLLOW_UP_DUE";
    public static final String FOLLOW_UP_MISSED = "FOLLOW_UP_MISSED";
    public static final String NO_NEXT_STEP = "NO_NEXT_STEP";
    public static final String DEMO_TOMORROW = "DEMO_TOMORROW";
    public static final String DEMO_UNMARKED = "DEMO_UNMARKED";

    private final NotificationRepository notifications;
    private final LeadRepository leads;
    private final DemoRepository demos;

    public NotificationService(NotificationRepository notifications,
                               LeadRepository leads,
                               DemoRepository demos) {
        this.notifications = notifications;
        this.leads = leads;
        this.demos = demos;
    }

    /**
     * Records a notice unless the same fact has already been announced to this person.
     *
     * @return true when something was actually created
     */
    @Transactional
    public boolean notify(String recipientId, String kind, String dedupeKey,
                          String title, String body, String leadId) {
        if (recipientId == null || recipientId.isBlank()) {
            // An unassigned lead has nobody to tell. That is itself worth knowing, and the
            // unassigned queue on My Day is where it shows up.
            return false;
        }
        if (notifications.existsByRecipientIdAndDedupeKey(recipientId, dedupeKey)) {
            return false;
        }
        notifications.save(Notification.builder()
                .id(UUID.randomUUID().toString())
                .recipientId(recipientId)
                .kind(kind)
                .dedupeKey(dedupeKey)
                .title(title)
                .body(body)
                .leadId(leadId)
                .createdAt(LocalDateTime.now())
                .build());
        return true;
    }

    /** Fired the moment a lead changes hands, because that is when it is useful. */
    @Transactional
    public void leadAssigned(Lead lead, String recipientId, String assignedBy) {
        notify(recipientId, LEAD_ASSIGNED,
                "assigned:" + lead.getId() + ":" + recipientId,
                lead.getFullName() + " is now yours",
                (assignedBy == null ? "A manager" : assignedBy) + " assigned this "
                        + (lead.getCourseInterested() == null ? "enquiry" : lead.getCourseInterested() + " enquiry")
                        + (lead.getCityName() == null ? "" : " from " + lead.getCityName()) + ".",
                lead.getId());
    }

    /**
     * The morning sweep. Runs after the follow-up ladder so the day's work is already decided.
     */
    @Scheduled(cron = "${app.crm.notifications.cron:0 15 6 * * *}", zone = "${app.crm.timezone:Asia/Kolkata}")
    public void morningSweep() {
        LocalDate today = LocalDate.now();
        int created = 0;

        for (Lead lead : leads.findAll(LeadSpecifications.all(
                LeadSpecifications.open(), LeadSpecifications.nextTouchOn(today)))) {
            created += notify(lead.getAssignedToId(), FOLLOW_UP_DUE,
                    "due:" + lead.getId() + ":" + today,
                    lead.getFullName() + " is due today",
                    lead.getNextTouchNote() == null ? "A follow-up is scheduled for today."
                            : lead.getNextTouchNote(),
                    lead.getId()) ? 1 : 0;
        }

        for (Lead lead : leads.findAll(LeadSpecifications.all(
                LeadSpecifications.open(), LeadSpecifications.nextTouchBefore(today)))) {
            long late = java.time.temporal.ChronoUnit.DAYS.between(lead.getNextTouchOn(), today);
            // Keyed by the day it is noticed, so a lead that stays overdue is raised again
            // tomorrow rather than announced once and forgotten.
            created += notify(lead.getAssignedToId(), FOLLOW_UP_MISSED,
                    "missed:" + lead.getId() + ":" + today,
                    lead.getFullName() + " is " + late + " day" + (late == 1 ? "" : "s") + " overdue",
                    "This should already have happened. " +
                    (lead.getNextTouchNote() == null ? "" : lead.getNextTouchNote()),
                    lead.getId()) ? 1 : 0;
        }

        for (Lead lead : leads.findAll(LeadSpecifications.all(
                LeadSpecifications.open(), LeadSpecifications.blankNextTouch()))) {
            created += notify(lead.getAssignedToId(), NO_NEXT_STEP,
                    "blank:" + lead.getId() + ":" + today,
                    lead.getFullName() + " has no next step",
                    "An active lead with no date is how leads die. Book the next touch.",
                    lead.getId()) ? 1 : 0;
        }

        LocalDate tomorrow = today.plusDays(1);
        for (Demo demo : demos.findScheduledBetween(tomorrow.atStartOfDay(), tomorrow.plusDays(1).atStartOfDay())) {
            leads.findById(demo.getLeadId()).ifPresent(lead ->
                    notify(lead.getAssignedToId(), DEMO_TOMORROW,
                            "demo:" + demo.getId(),
                            demo.getStudentName() + "'s demo is tomorrow",
                            demo.getMode() + " at " + demo.getScheduledAt().toLocalTime()
                                    + ". Confirm it with them today.",
                            lead.getId()));
        }

        for (Demo demo : demos.findAwaitingMarking(LocalDateTime.now())) {
            leads.findById(demo.getLeadId()).ifPresent(lead ->
                    notify(lead.getAssignedToId(), DEMO_UNMARKED,
                            "unmarked:" + demo.getId() + ":" + today,
                            "Did " + demo.getStudentName() + " attend?",
                            "Their demo was on " + demo.getScheduledAt().toLocalDate()
                                    + " and is still unmarked. The follow-ups are not booked until it is.",
                            lead.getId()));
        }

        log.info("Morning notification sweep created {} new notice(s).", created);
    }

    /** Housekeeping: read notices stop being useful quickly. */
    @Scheduled(cron = "${app.crm.notifications.cleanup-cron:0 30 3 * * SUN}",
               zone = "${app.crm.timezone:Asia/Kolkata}")
    @Transactional
    public void purgeOldRead() {
        int removed = notifications.deleteReadBefore(LocalDateTime.now().minusDays(30));
        if (removed > 0) log.info("Removed {} read notification(s) older than 30 days.", removed);
    }

    public long unreadCount(String userId) {
        return notifications.countByRecipientIdAndReadAtIsNull(userId);
    }

    public List<Notification> recent(String userId, int limit) {
        return notifications.findByRecipientIdOrderByCreatedAtDesc(userId,
                org.springframework.data.domain.PageRequest.of(0, Math.min(Math.max(limit, 1), 100)))
                .getContent();
    }

    @Transactional
    public void markRead(Notification n) {
        if (n.getReadAt() == null) {
            n.setReadAt(LocalDateTime.now());
            notifications.save(n);
        }
    }

    @Transactional
    public int markAllRead(String userId) {
        return notifications.markAllRead(userId, LocalDateTime.now());
    }
}
