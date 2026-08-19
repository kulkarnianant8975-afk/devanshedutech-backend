package com.devanshedutech.config;

import com.devanshedutech.model.Asset;
import com.devanshedutech.model.SendPack;
import com.devanshedutech.repository.AssetRepository;
import com.devanshedutech.repository.SendPackRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;

import java.util.List;
import java.util.UUID;

/**
 * Seeds the message packs from the Counsellor SOP.
 *
 * <p>The wording is the SOP's own, because it was written for this institute and tested on real
 * students. Only seeded when missing, so edits made in the product survive a redeploy.</p>
 */
@Slf4j
@Configuration
public class SendPackSeeder {

    @Bean
    @Order(6)
    public ApplicationRunner seedSendPacks(AssetRepository assets, SendPackRepository packs) {
        return args -> {
            // The course brochure already lives in this database; the asset points at it and
            // fills in the course from the lead, so one row covers every course.
            asset(assets, "syllabus", "{{course}} — syllabus and fees", "PDF",
                    "/api/public/brochure/download/{{course_id}}", "PDF", true);
            asset(assets, "brochure_general", "Institute brochure", "PDF",
                    "/api/public/brochure/download", "PDF", true);
            asset(assets, "demo_link", "Book your free demo", "LINK", "/contact", "tracked", true);
            asset(assets, "courses_page", "All courses and outcomes", "LINK", "/courses", "tracked", true);

            pack(packs, "guidance", "Post-call guidance pack",
                    "SOP section 4, step 5 — never end a call vague",
                    "Great speaking with you, {{first_name}}! 😊 As promised, here is everything for "
                  + "{{course}} — the syllabus, the fees with instalment options, and the link to book "
                  + "your free demo. Have a look and tell me what you think.\n\n— {{counsellor}}, Devansh Edu-Tech",
                    "syllabus,demo_link", false);

            pack(packs, "dnp", "Missed-call recovery",
                    "SOP section 6.1 — send this the moment a call goes unanswered",
                    "Hi {{first_name}}, tried calling about your {{course}} enquiry 📞 No worries! "
                  + "Sharing the syllabus and fees here. When is a good time to call you back — does "
                  + "evening work?",
                    "syllabus", false);

            pack(packs, "parents", "Parent summary",
                    "SOP section 6.3 — support the decision, do not treat it as a rejection",
                    "That is completely right, involving your parents is a good idea 🙏 Here is a short "
                  + "summary they can read — what you will learn, placement support, fees and instalment "
                  + "options. I am also happy to speak with them directly if that helps.",
                    "syllabus,brochure_general", false);

            pack(packs, "demo_confirm", "Demo confirmation",
                    "SOP section 4 — confirm every booking in writing",
                    "Confirmed, {{first_name}}! Your free {{course}} demo is booked. I will message you "
                  + "a reminder on the morning. See you there 👍",
                    "demo_link", false);

            pack(packs, "afterdemo", "Day 1 after the demo",
                    "SOP section 6.7 — where enrolments are won or lost",
                    "Hi {{first_name}}! How did you find the demo class? 😊 I would love to hear your "
                  + "thoughts. If it felt right, I can reserve your seat in the upcoming batch and share "
                  + "the fee and instalment details.",
                    "syllabus", false);

            pack(packs, "comparing", "Comparing other institutes",
                    "SOP section 6.5 — calm confidence, not desperation",
                    "Smart to compare 👍 Here is what makes us different — eight years of hands-on "
                  + "training, real projects, and a local placement network here in {{city}}. Come to a "
                  + "free demo and compare us directly; that will tell you more than any brochure.",
                    "courses_page,demo_link", false);

            pack(packs, "welcome", "Enrolment welcome",
                    "SOP section 7 — a happy student is the cheapest source of new leads",
                    "Welcome to Devansh Edu-Tech, {{first_name}}! 🎉 So glad to have you on {{course}}. "
                  + "I will send your joining details shortly. Any question at all, just message me.",
                    "brochure_general", false);
        };
    }

    private void asset(AssetRepository repo, String key, String name, String type,
                       String url, String size, boolean tracked) {
        if (repo.countByKey(key) > 0) return;
        repo.save(Asset.builder().id(UUID.randomUUID().toString())
                .key(key).name(name).type(type).url(url).sizeLabel(size)
                .tracked(tracked).active(true).build());
    }

    private void pack(SendPackRepository repo, String key, String name, String situation,
                      String cover, String assetKeys, boolean auto) {
        if (repo.countByKey(key) > 0) return;
        repo.save(SendPack.builder().id(UUID.randomUUID().toString())
                .key(key).name(name).situation(situation).coverTemplate(cover)
                .assetKeys(assetKeys).autoSend(auto).active(true).build());
        log.info("Seeded message pack '{}'", name);
    }
}
