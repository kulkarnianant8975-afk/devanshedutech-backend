package com.devanshedutech.channel;

import org.springframework.stereotype.Component;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * The fallback: hand the message to the counsellor's own WhatsApp and let them press send.
 *
 * <p>Active whenever no provider is configured, which means the product works on day one
 * without an account, and stops working the moment somebody's API key expires only in the
 * sense that it quietly reverts to a person doing it by hand.</p>
 */
@Component
public class ManualWhatsAppChannel implements WhatsAppChannel {

    @Override
    public String name() { return "Manual WhatsApp"; }

    @Override
    public boolean canSendAutomatically() { return false; }

    @Override
    public SendResult send(String toPhone, String studentName, String message, List<Attachment> attachments) {
        String number = PhoneNumbers.toWhatsApp(toPhone);
        if (number == null) {
            return SendResult.failed("This lead has no usable phone number.");
        }

        String full = withAttachments(message, attachments);
        String url = "https://wa.me/" + number + "?text="
                + URLEncoder.encode(full, StandardCharsets.UTF_8);

        String detail = attachments.isEmpty()
                ? "Opens in your WhatsApp, ready to send."
                : "Opens in your WhatsApp with the message and " + attachments.size()
                  + " link" + (attachments.size() == 1 ? "" : "s") + " ready to send.";

        return SendResult.handoff(url, detail);
    }

    /**
     * Puts the brochure and the video into the message as links.
     *
     * <p>A deep link carries text and nothing else — there is no way to pre-attach a file to
     * one. This used to list the attachments by name and ask the counsellor to attach them,
     * which reads as reasonable and is not: those files live on the server, so following that
     * instruction means downloading each one to a phone first, mid-conversation. Nobody does
     * that, so in practice the brochure simply never arrived.</p>
     *
     * <p>A link is the form that actually works here, and it is the better one besides — these
     * URLs are per-lead and tracked, so the CRM can see that a student opened the syllabus.
     * An attachment tells you nothing after it leaves.</p>
     */
    private String withAttachments(String message, List<Attachment> attachments) {
        StringBuilder out = new StringBuilder(message);
        for (Attachment a : attachments) {
            if (a.url() == null || a.url().isBlank()) continue;
            // Already named in the text — the pack's own links are appended upstream, and a URL
            // repeated twice in one message looks like a mistake to the student reading it.
            if (message.contains(a.url())) continue;
            out.append("\n\n").append(a.name()).append(":\n").append(a.url());
        }
        return out.toString();
    }
}
