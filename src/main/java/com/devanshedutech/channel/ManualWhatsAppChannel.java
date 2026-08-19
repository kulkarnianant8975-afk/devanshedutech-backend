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
        String url = "https://wa.me/" + number + "?text="
                + URLEncoder.encode(message, StandardCharsets.UTF_8);

        // Attachments cannot ride along on a deep link, so they are named in the message rather
        // than silently dropped — a counsellor who is told what to attach will attach it.
        String detail = attachments.isEmpty()
                ? "Opens in your WhatsApp, ready to send."
                : "Opens in your WhatsApp. Attach: "
                  + attachments.stream().map(Attachment::name).reduce((a, b) -> a + ", " + b).orElse("");

        return SendResult.handoff(url, detail);
    }
}
