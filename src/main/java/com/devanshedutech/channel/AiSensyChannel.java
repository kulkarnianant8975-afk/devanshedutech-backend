package com.devanshedutech.channel;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.web.client.RestTemplateBuilder;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;

import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Sends through AiSensy, an official WhatsApp Business API provider.
 *
 * <p>Only active when an API key is configured; without one the manual channel takes over, so a
 * missing or expired key degrades to a counsellor sending by hand rather than to messages
 * silently vanishing.</p>
 *
 * <p>Two things about the WhatsApp API shape the code here. Outside the twenty-four hour reply
 * window only an approved template may be sent, so the pack's template name is passed through
 * and the message text is carried as template parameters. And media is fetched by WhatsApp from
 * a URL, which therefore has to be publicly reachable — a link to localhost will be accepted by
 * the API and then never arrive.</p>
 *
 * <p>The request shape below follows AiSensy's campaign API. Their payload has changed before
 * and the endpoint is configurable for that reason; confirm it against the account's own
 * documentation before the first production send.</p>
 */
@Slf4j
@Component
public class AiSensyChannel implements WhatsAppChannel {

    private final RestTemplate http;

    @Value("${app.crm.whatsapp.aisensy.api-key:}")
    private String apiKey;

    @Value("${app.crm.whatsapp.aisensy.url:https://backend.aisensy.com/campaign/t1/api/v2}")
    private String endpoint;

    /** The approved template used when the free-reply window has closed. */
    @Value("${app.crm.whatsapp.aisensy.campaign:crm_message}")
    private String campaign;

    public AiSensyChannel(RestTemplateBuilder builder) {
        // Short timeouts on purpose: a counsellor waiting on a spinner needs an answer, and a
        // provider that is slow today is better reported than waited on.
        this.http = builder
                .setConnectTimeout(Duration.ofSeconds(5))
                .setReadTimeout(Duration.ofSeconds(10))
                .build();
    }

    @Override
    public String name() { return "AiSensy"; }

    @Override
    public boolean canSendAutomatically() {
        return apiKey != null && !apiKey.isBlank();
    }

    @Override
    public SendResult send(String toPhone, String studentName, String message, List<Attachment> attachments) {
        if (!canSendAutomatically()) {
            return SendResult.failed("No AiSensy API key is configured.");
        }
        String destination = PhoneNumbers.toWhatsApp(toPhone);
        if (destination == null) {
            return SendResult.failed("This lead has no usable phone number.");
        }

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("apiKey", apiKey);
        body.put("campaignName", campaign);
        body.put("destination", destination);
        body.put("userName", studentName == null ? "Student" : studentName);
        body.put("templateParams", List.of(message));

        // Media rides as the template header. WhatsApp fetches it, so a URL this server cannot
        // publish is worse than no attachment at all: it is accepted and then never delivered.
        List<Attachment> reachable = attachments.stream()
                .filter(a -> a.url() != null && a.url().startsWith("http"))
                .toList();
        if (!reachable.isEmpty()) {
            Attachment first = reachable.get(0);
            body.put("media", Map.of("url", first.url(), "filename", first.name()));
        }

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);

        try {
            http.postForEntity(endpoint, new HttpEntity<>(body, headers), String.class);

            List<String> notes = new ArrayList<>();
            notes.add("Sent through AiSensy.");
            if (reachable.size() < attachments.size()) {
                // Said out loud rather than hidden: a counsellor who believes a syllabus went
                // and finds out days later that it did not has lost the student's momentum.
                notes.add((attachments.size() - reachable.size())
                        + " attachment(s) were not sent because they are not publicly reachable. "
                        + "Set app.crm.public-base-url to a public address.");
            }
            return SendResult.accepted(String.join(" ", notes));

        } catch (RuntimeException e) {
            // The provider's message can carry the API key back in the request echo, so it is
            // logged and not returned to the browser.
            log.error("AiSensy send failed for {}", destination, e);
            return SendResult.failed("WhatsApp did not accept the message. It has not been sent.");
        }
    }
}
