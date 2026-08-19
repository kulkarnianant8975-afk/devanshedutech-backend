package com.devanshedutech.service;

import com.devanshedutech.crm.LeadSpecifications;
import com.devanshedutech.model.Lead;
import com.devanshedutech.repository.LeadRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Runs the follow-up ladder once a day, before the institute opens.
 *
 * <p>Deliberately a single early-morning pass rather than a continuous job: every decision the
 * ladder makes is measured in days, and the point is that counsellors find the day's work
 * already laid out when they open the pipeline — which is exactly what the SOP's daily
 * checklist tells them to do first.</p>
 */
@Slf4j
@Service
public class LeadLadderScheduler {

    private final LeadRepository leadRepository;
    private final LeadLadderService ladder;

    @Value("${app.crm.ladder.enabled:true}")
    private boolean enabled;

    public LeadLadderScheduler(LeadRepository leadRepository, LeadLadderService ladder) {
        this.leadRepository = leadRepository;
        this.ladder = ladder;
    }

    @Scheduled(cron = "${app.crm.ladder.cron:0 0 6 * * *}", zone = "${app.crm.timezone:Asia/Kolkata}")
    public void dailyPass() {
        if (!enabled) {
            log.info("The follow-up ladder is disabled; skipping today's pass.");
            return;
        }
        runNow();
    }

    /** Exposed so a manager can trigger the pass by hand, and so it can be tested. */
    public Map<String, Long> runNow() {
        LocalDate today = LocalDate.now();
        List<Lead> candidates = leadRepository.findAll(LeadSpecifications.open());

        Map<String, Long> summary = candidates.stream()
                .map(lead -> {
                    try {
                        return ladder.advance(lead, today).orElse(null);
                    } catch (RuntimeException e) {
                        // One bad row must not stop the pass for everybody else.
                        log.warn("Ladder pass failed for lead {}: {}", lead.getId(), e.getMessage());
                        return null;
                    }
                })
                .filter(java.util.Objects::nonNull)
                .collect(Collectors.groupingBy(LeadLadderService.LadderOutcome::action,
                        LinkedHashMap::new, Collectors.counting()));

        log.info("Follow-up pass over {} open lead(s): {}", candidates.size(),
                summary.isEmpty() ? "nothing changed" : summary);

        // Surfaced loudly on purpose. A lead that decayed without ever being contacted is a
        // follow-up failure, not a student who said no, and it must not hide in the numbers.
        long untouched = summary.getOrDefault("demoted-untouched", 0L)
                       + summary.getOrDefault("lost-unworked", 0L);
        if (untouched > 0) {
            log.warn("{} lead(s) moved down today having never really been worked. "
                    + "Review the counsellor scorecard.", untouched);
        }
        return summary;
    }
}
