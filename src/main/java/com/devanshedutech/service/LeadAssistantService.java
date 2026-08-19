package com.devanshedutech.service;

import com.devanshedutech.ai.GeminiClient;
import com.devanshedutech.model.Lead;
import com.devanshedutech.model.LeadActivity;
import com.devanshedutech.model.crm.Grade;
import com.devanshedutech.repository.LeadActivityRepository;
import lombok.Builder;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;

/**
 * Reads a lead's history and offers a counsellor an opinion on it.
 *
 * <p>Everything here suggests and nothing here decides. A grade suggestion is written to the
 * screen for a counsellor to accept or ignore; it never moves the lead itself. That line is
 * deliberate. Grading drives the whole follow-up ladder, so a model quietly demoting a student
 * it misread would change how often a real person gets contacted, and nobody would know why.</p>
 *
 * <p>Every feature here is optional. With no API key configured the endpoints say so plainly
 * and the rest of the CRM is unaffected — a counsellor who has always written their own messages
 * loses nothing.</p>
 */
@Slf4j
@Service
public class LeadAssistantService {

    private static final DateTimeFormatter WHEN = DateTimeFormatter.ofPattern("d MMM, h:mma", Locale.ENGLISH);

    /** Enough history to judge by. Beyond this the oldest entries stop being informative. */
    private static final int MAX_ACTIVITIES = 40;

    private final GeminiClient gemini;
    private final LeadActivityRepository activities;

    public LeadAssistantService(GeminiClient gemini, LeadActivityRepository activities) {
        this.gemini = gemini;
        this.activities = activities;
    }

    public boolean isAvailable() {
        return gemini.isConfigured();
    }

    /** A suggestion, with its reasoning, that a counsellor applies or discards. */
    @Data
    @Builder
    public static class GradeSuggestion {
        private Grade grade;
        private String reasoning;
        /** Always false. The grade is only ever changed by a person pressing a button. */
        private boolean applied;
    }

    /**
     * Suggests Hot, Warm or Cold from what has actually happened on the lead.
     *
     * <p>The SOP's own definitions are given to the model rather than left to it, so the answer
     * means the same thing the rest of the application means by those words.</p>
     */
    public GradeSuggestion suggestGrade(Lead lead) {
        String system = """
                You grade sales leads for an Indian IT training institute, using the institute's
                own definitions and no others:

                - HOT: ready to decide now. Asked about fees, batch dates, or how to pay; agreed
                  to a demo or a visit; replying quickly; a parent involved in the conversation.
                - WARM: genuinely interested but not yet deciding. Engaged with the material,
                  asked real questions, but is waiting on results, money, or a family decision.
                - COLD: enquired and went quiet, or is browsing with no timeline. Not rude and
                  not lost — just not now.

                Judge only on what the history below shows. Do not assume interest from silence
                and do not reward politeness. If the history is too thin to tell, say COLD and say
                why in the reasoning: an unworked lead is not a warm one.

                Reply in exactly two lines and nothing else:
                GRADE: <HOT|WARM|COLD>
                WHY: <one sentence, referring to something that actually happened>
                """;

        String answer = gemini.complete(system, describe(lead));
        return parseGrade(answer);
    }

    /**
     * Summarises the lead for a counsellor about to pick up the phone.
     *
     * <p>Written for the thirty seconds before a call, which is the only moment it will ever be
     * read.</p>
     */
    public String summarise(Lead lead) {
        String system = """
                You brief a counsellor in the thirty seconds before they ring a student. That is
                the only moment this will ever be read, so it must be short and immediately
                usable.

                Give at most four short bullet points:
                - who they are and what they want
                - where the conversation got to
                - the thing standing in the way, if the history shows one
                - what to open the call with

                Use only what the history states. If something is not known — their budget, their
                exam results, whether a parent is involved — leave it out rather than guessing;
                a counsellor acting on an invented detail loses the student in one sentence.
                """;
        return gemini.complete(system, describe(lead));
    }

    /**
     * Drafts a message for the counsellor to edit and send.
     *
     * <p>Never sent automatically. It is a starting point that saves typing, and the counsellor
     * remains the author of anything a student actually receives.</p>
     */
    public String draftReply(Lead lead, String intent, String counsellorName) {
        String system = """
                You draft a WhatsApp message for a counsellor at an Indian IT training institute
                to send to a prospective student. The counsellor will read it, change it, and
                send it themselves — so write something a person would plausibly have typed.

                Rules:
                - Under 60 words. This is WhatsApp, not email.
                - Plain, warm, ordinary English. No marketing language, no exclamation marks, no
                  emoji unless the student used them first.
                - State only facts present in the history. Never invent a fee, a discount, a batch
                  date, or a placement figure. If one is needed and not known, leave a short
                  bracket like [confirm batch date] for the counsellor to fill in.
                - Ask exactly one question, so there is something easy to reply to.
                - Sign off with the counsellor's first name only.
                - Output the message alone. No preamble, no alternatives, no explanation.
                """;

        String ask = describe(lead)
                + "\n\nCounsellor's name: " + (counsellorName == null ? "the counsellor" : counsellorName)
                + "\nWhat this message needs to do: "
                + (intent == null || intent.isBlank() ? "move the conversation forward" : intent.trim());

        return gemini.complete(system, ask);
    }

    /**
     * The lead and its history as plain text.
     *
     * <p>Deliberately excludes the phone number and email. They add nothing to any of these
     * judgements, and there is no reason to send a student's contact details to a third party to
     * be told that somebody sounds interested.</p>
     */
    String describe(Lead lead) {
        StringBuilder sb = new StringBuilder();
        sb.append("Student: ").append(lead.getFullName()).append('\n');
        if (lead.getCourseInterested() != null) sb.append("Asked about: ").append(lead.getCourseInterested()).append('\n');
        if (lead.getCityName() != null) sb.append("From: ").append(lead.getCityName()).append('\n');
        if (lead.getEducation() != null) sb.append("Studying: ").append(lead.getEducation()).append('\n');
        if (lead.getSource() != null) sb.append("Came via: ").append(lead.getSource().getLabel()).append('\n');
        if (lead.getStage() != null) sb.append("Stage: ").append(lead.getStage().getLabel()).append('\n');
        if (lead.getGrade() != null) sb.append("Current grade: ").append(lead.getGrade().name()).append('\n');
        sb.append("Contact attempts so far: ").append(lead.getCallAttempts()).append('\n');
        if (lead.getNotes() != null && !lead.getNotes().isBlank()) {
            sb.append("Counsellor's notes: ").append(lead.getNotes()).append('\n');
        }

        List<LeadActivity> timeline = activities.findByLeadIdOrderByCreatedAtDesc(lead.getId());
        sb.append("\nHistory, most recent first:\n");
        if (timeline.isEmpty()) {
            sb.append("- Nothing recorded yet. Nobody has worked this lead.\n");
        }
        timeline.stream().limit(MAX_ACTIVITIES).forEach(a -> {
            sb.append("- ");
            if (a.getCreatedAt() != null) sb.append(a.getCreatedAt().format(WHEN)).append(": ");
            sb.append(a.getSummary() == null ? a.getType() : a.getSummary());
            if (a.getDetail() != null && !a.getDetail().isBlank()) sb.append(" — ").append(a.getDetail());
            sb.append('\n');
        });
        return sb.toString();
    }

    /**
     * Reads the model's two-line answer.
     *
     * <p>An unrecognised grade becomes no suggestion rather than a guess. The counsellor sees
     * the reasoning and decides, which is the same thing they would have done anyway.</p>
     */
    GradeSuggestion parseGrade(String answer) {
        Grade grade = null;
        String why = null;
        for (String line : answer.split("\\R")) {
            String trimmed = line.trim();
            if (trimmed.toUpperCase(Locale.ROOT).startsWith("GRADE:")) {
                String value = trimmed.substring(6).trim().toUpperCase(Locale.ROOT).replaceAll("[^A-Z]", "");
                try {
                    grade = Grade.valueOf(value);
                } catch (IllegalArgumentException e) {
                    log.debug("Model suggested an unrecognised grade: {}", value);
                }
            } else if (trimmed.toUpperCase(Locale.ROOT).startsWith("WHY:")) {
                why = trimmed.substring(4).trim();
            }
        }
        if (why == null || why.isBlank()) why = answer.trim();
        return GradeSuggestion.builder().grade(grade).reasoning(why).applied(false).build();
    }
}
