package com.devanshedutech.channel;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Chooses the channel and makes attachment URLs reachable.
 *
 * <p>Three are possible: Meta's own Cloud API, a reseller, or handing off to the counsellor's
 * WhatsApp. Whichever is configured is used, so the institute runs today and switches on
 * automatic sending by setting keys. Nothing above this class knows which is in use.</p>
 *
 * <p>Meta is preferred when both are configured, because it is the one being tested against
 * and a silent preference for the other would make a test look like it had failed.</p>
 */
@Slf4j
@Component
public class WhatsAppSender {

    private final MetaCloudChannel meta;
    private final AiSensyChannel aisensy;
    private final ManualWhatsAppChannel manual;

    /**
     * Which channel to use: {@code auto}, {@code meta}, {@code aisensy} or {@code manual}.
     *
     * <p>{@code auto} takes whatever is configured. The explicit values exist so a test can be
     * pinned to one channel — otherwise adding a second set of keys silently changes where
     * every message goes.</p>
     */
    @Value("${app.crm.whatsapp.channel:auto}")
    private String choice;

    /**
     * The public address of this application. Attachments are served by us, and WhatsApp fetches
     * them from its own servers, so a relative path or a localhost URL cannot work.
     */
    @Value("${app.crm.public-base-url:}")
    private String publicBaseUrl;

    public WhatsAppSender(MetaCloudChannel meta, AiSensyChannel aisensy, ManualWhatsAppChannel manual) {
        this.meta = meta;
        this.aisensy = aisensy;
        this.manual = manual;
    }

    public WhatsAppChannel active() {
        return switch (choice == null ? "auto" : choice.trim().toLowerCase(java.util.Locale.ROOT)) {
            case "meta" -> meta;
            case "aisensy" -> aisensy;
            case "manual" -> manual;
            default -> meta.canSendAutomatically() ? meta
                     : aisensy.canSendAutomatically() ? aisensy
                     : manual;
        };
    }

    public boolean sendsAutomatically() {
        return active().canSendAutomatically();
    }

    /** The Cloud API itself, for the connection test. Null unless it is the active channel. */
    public MetaCloudChannel metaChannel() {
        return active() == meta ? meta : null;
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
