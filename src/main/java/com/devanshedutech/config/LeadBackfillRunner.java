package com.devanshedutech.config;

import com.devanshedutech.model.Lead;
import com.devanshedutech.model.crm.LeadSource;
import com.devanshedutech.model.crm.Stage;
import com.devanshedutech.model.crm.StudentBackground;
import com.devanshedutech.repository.LeadRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;

import java.util.List;

/**
 * Gives leads that predate the CRM the fields the pipeline needs.
 *
 * <p>Existing rows carry a free-text status of "New" and nothing else, so without this they
 * would be invisible to every queue: no stage, no source, no next touch. Each row is only
 * touched where a value is missing, so this is safe on every boot and does nothing once the
 * backfill is complete.</p>
 *
 * <p>Grade and next touch are deliberately left blank rather than invented. An enquiry from
 * months ago is not automatically warm, and giving it a date would drop stale leads into
 * somebody's queue as though they were live. A counsellor grades them, and the SOP's blank
 * next-touch report is what surfaces them.</p>
 */
@Slf4j
@Configuration
public class LeadBackfillRunner {

    @Bean
    @Order(20)
    public ApplicationRunner backfillLeads(LeadRepository leadRepository) {
        return args -> {
            List<Lead> all = leadRepository.findAll();
            int touched = 0;

            for (Lead lead : all) {
                boolean changed = false;

                if (lead.getStage() == null) {
                    lead.setStage(Stage.parse(lead.getStatus(), Stage.NEW));
                    changed = true;
                }
                if (lead.getPhoneNormalized() == null) {
                    lead.setPhoneNormalized(Lead.normalizePhone(lead.getMobileNumber()));
                    changed = true;
                }
                if (lead.getBackground() == null) {
                    StudentBackground bg = StudentBackground.parse(lead.getEducation());
                    if (bg != null) { lead.setBackground(bg); changed = true; }
                }
                if (lead.getSource() == null) {
                    // Every pre-CRM lead arrived through the website enrolment form; that was
                    // the only capture path that existed. Recording it honestly beats guessing.
                    lead.setSource(LeadSource.WEBSITE_FORM);
                    changed = true;
                }
                if (lead.getCallAttempts() == null) { lead.setCallAttempts(0); changed = true; }
                if (lead.getLadderStep() == null) { lead.setLadderStep(1); changed = true; }
                if (lead.getLostUnworked() == null) { lead.setLostUnworked(false); changed = true; }
                if (lead.getGrade() != null && lead.getGradeEnteredAt() == null) {
                    // Without this the ladder would measure a graded lead's age from an epoch
                    // it never had, and fire every overdue step at once on the first pass.
                    lead.setGradeEnteredAt(lead.getCreatedAt());
                    changed = true;
                }
                if (lead.getUpdatesOnly() == null) { lead.setUpdatesOnly(false); changed = true; }
                if (lead.getOptedOut() == null) { lead.setOptedOut(false); changed = true; }

                if (changed) { leadRepository.save(lead); touched++; }
            }

            if (touched > 0) {
                log.info("Backfilled {} of {} leads with pipeline fields.", touched, all.size());
            }

            long ungraded = all.stream().filter(l -> l.getGrade() == null && l.isActive()).count();
            long unassigned = all.stream().filter(l -> l.getAssignedToId() == null && l.isActive()).count();
            long duplicates = all.stream()
                    .filter(l -> l.getPhoneNormalized() != null)
                    .collect(java.util.stream.Collectors.groupingBy(Lead::getPhoneNormalized,
                            java.util.stream.Collectors.counting()))
                    .values().stream().filter(c -> c > 1).count();

            if (ungraded > 0) {
                log.info("{} active lead(s) are waiting to be graded Hot, Warm or Cold.", ungraded);
            }
            if (unassigned > 0) {
                log.info("{} active lead(s) have no owner yet.", unassigned);
            }
            if (duplicates > 0) {
                log.warn("{} phone number(s) appear on more than one lead. These predate duplicate "
                        + "detection and are worth merging by hand.", duplicates);
            }
        };
    }
}
