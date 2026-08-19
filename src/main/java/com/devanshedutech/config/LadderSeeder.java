package com.devanshedutech.config;

import com.devanshedutech.model.LadderStep;
import com.devanshedutech.model.crm.Grade;
import com.devanshedutech.repository.LadderStepRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;

import java.util.List;
import java.util.UUID;

/**
 * Seeds the three follow-up ladders the first time the application starts.
 *
 * <p>Each grade gets seven steps, and the pace differs because that is what the grade means.
 * Hot burns through in a week: someone who is genuinely ready in two weeks and still has not
 * moved after seven days of daily contact was never hot, and demoting them honestly is kinder
 * than spending a counsellor's best hours on them. Warm follows the SOP's twenty-one day
 * cadence exactly. Cold is a slow broadcast track spanning a full intake cycle, so a June
 * enquiry is still on the list when college results come out.</p>
 *
 * <p>A lane is only seeded when it is empty, so tuning the offsets in the database is never
 * overwritten by a redeploy.</p>
 */
@Slf4j
@Configuration
public class LadderSeeder {

    @Bean
    @Order(5)
    public ApplicationRunner seedLadders(LadderStepRepository repo) {
        return args -> {
            seed(repo, Grade.HOT, List.of(
                step(0,  "Instant reply and syllabus",  "Human reply within five minutes, then the syllabus PDF and fee sheet. Try a call the same day.", false),
                step(0,  "Guidance call",               "The five-step call. Book the demo or campus visit on the call itself.", false),
                step(1,  "Demo confirmation",           "Confirm the demo in writing with the exact day and time.", false),
                step(2,  "Demo or campus visit",        "The demo happens. Mark attendance the same day.", false),
                step(3,  "Reserve the seat",            "Offer to hold the seat and share the fee and instalment plan.", false),
                step(5,  "Batch date and benefit",      "Share the batch start date and any early-enrol benefit. Ask for the decision.", false),
                step(7,  "Final ask",                   "\"Shall I hold your seat for this batch?\" Direct, warm, no pressure.", false)
            ));

            seed(repo, Grade.WARM, List.of(
                step(0,  "Reply, syllabus and proof",   "Human reply, syllabus, fee sheet, and one placement story for their course.", false),
                step(1,  "Guidance call",               "The five-step call. Offer the free demo or a campus visit.", false),
                step(3,  "Nudge",                       "\"Batch is filling — shall I hold a demo seat for you?\" plus a student project clip.", false),
                step(5,  "Proof",                       "Alumni testimonial and a short \"what you'll build\" summary.", false),
                step(8,  "Check-in",                    "\"Any questions before you decide? Happy to help.\"", false),
                step(12, "Offer",                       "Batch date plus any early-enrol or referral benefit.", false),
                step(18, "Soft close and wrap-up",      "\"Next batch starts soon, limited seats — reserve yours?\" Friendly wrap-up by day 21.", false)
            ));

            seed(repo, Grade.COLD, List.of(
                step(0,  "New batch announcement",      "Broadcast only. No counsellor time is spent on cold leads.", true),
                step(14, "Free workshop invite",        "Broadcast: the next free workshop or seminar.", true),
                step(30, "Placement results",           "Broadcast: recent placement results and roles.", true),
                step(45, "Student project showcase",    "Broadcast: what current students have built.", true),
                step(60, "Career guide",                "Broadcast: a \"which course suits you\" explainer.", true),
                step(75, "Seasonal intake reminder",    "Broadcast timed to college results, when demand spikes.", true),
                step(90, "Final re-engagement",         "\"Still interested? We'd love to help.\" The last automated touch.", true)
            ));
        };
    }

    private void seed(LadderStepRepository repo, Grade grade, List<Draft> drafts) {
        if (repo.countByGrade(grade) > 0) return;
        int n = 1;
        for (Draft d : drafts) {
            repo.save(LadderStep.builder()
                    .id(UUID.randomUUID().toString())
                    .grade(grade)
                    .stepNo(n++)
                    .dayOffset(d.dayOffset())
                    .title(d.title())
                    .action(d.action())
                    .autoSend(d.autoSend())
                    .active(true)
                    .build());
        }
        log.info("Seeded {} ladder steps for the {} lane", drafts.size(), grade.getLabel());
    }

    private static Draft step(int dayOffset, String title, String action, boolean autoSend) {
        return new Draft(dayOffset, title, action, autoSend);
    }

    private record Draft(int dayOffset, String title, String action, boolean autoSend) {}
}
