package com.devanshedutech.channel;

import java.util.List;

/**
 * How a message actually reaches a student.
 *
 * <p>An interface rather than a direct call to one provider, because the choice is an
 * operational one that will change: the institute starts on whatever is configured today and
 * may move providers later, and the pipeline should not have to know. Everything above this —
 * packs, templates, the reply window, the timeline — is unaffected by which implementation is
 * active.</p>
 */
public interface WhatsAppChannel {

    /** One attachment: WhatsApp fetches media by URL, so it must be publicly reachable. */
    record Attachment(String name, String type, String url) {}

    /**
     * What happened when we tried.
     *
     * @param sent      true when the provider accepted it
     * @param status    sent / queued / failed / manual
     * @param detail    something a counsellor can act on, not a stack trace
     * @param handoffUrl set only when the channel cannot send itself and a person must
     */
    record SendResult(boolean sent, String status, String detail, String handoffUrl) {
        public static SendResult accepted(String detail) {
            return new SendResult(true, "sent", detail, null);
        }
        public static SendResult failed(String detail) {
            return new SendResult(false, "failed", detail, null);
        }
        public static SendResult handoff(String url, String detail) {
            return new SendResult(false, "manual", detail, url);
        }
    }

    /** A short name for the timeline and the logs. */
    String name();

    /** Whether this channel can send without a person. */
    boolean canSendAutomatically();

    SendResult send(String toPhone, String studentName, String message, List<Attachment> attachments);
}
