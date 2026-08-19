package com.devanshedutech.channel;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Chooses the channel and makes attachment URLs reachable.
 *
 * <p>The provider is used when it is configured and the manual hand-off when it is not, so the
 * institute can run today and switch on automatic sending by setting one key. Nothing above
 * this class knows which is in use.</p>
 */
@Slf4j
@Component
public class WhatsAppSender {

    private final AiSensyChannel provider;
    private final ManualWhatsAppChannel manual;

    /**
     * The public address of this application. Attachments are served by us, and WhatsApp fetches
     * them from its own servers, so a relative path or a localhost URL cannot work.
     */
    @Value("${app.crm.public-base-url:}")
    private String publicBaseUrl;

    public WhatsAppSender(AiSensyChannel provider, ManualWhatsAppChannel manual) {
        this.provider = provider;
        this.manual = manual;
    }

    public WhatsAppChannel active() {
        return provider.canSendAutomatically() ? provider : manual;
    }

    public boolean sendsAutomatically() {
        return provider.canSendAutomatically();
    }

    public WhatsAppChannel.SendResult send(String toPhone, String studentName, String message,
                                           List<WhatsAppChannel.Attachment> attachments) {
        return active().send(toPhone, studentName, message, absolute(attachments));
    }

    /** Turns our own relative asset paths into addresses WhatsApp can actually fetch. */
    private List<WhatsAppChannel.Attachment> absolute(List<WhatsAppChannel.Attachment> attachments) {
        if (publicBaseUrl == null || publicBaseUrl.isBlank()) return attachments;
        String base = publicBaseUrl.endsWith("/")
                ? publicBaseUrl.substring(0, publicBaseUrl.length() - 1)
                : publicBaseUrl;
        return attachments.stream()
                .map(a -> a.url() != null && a.url().startsWith("/")
                        ? new WhatsAppChannel.Attachment(a.name(), a.type(), base + a.url())
                        : a)
                .toList();
    }
}
