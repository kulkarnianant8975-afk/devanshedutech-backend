# Testing WhatsApp with Meta's Cloud API

Meta gives every developer app a **test phone number** that sends free messages to up to five
recipients you verify yourself. That is the cheapest and safest way to watch a real message land
on a real phone — no provider account, no billing, and nobody's actual enquiry at risk.

Work through this once and you will know the integration works before a student is on the other
end of it.

---

## What you need from Meta

1. Go to **developers.facebook.com** → *My Apps* → **Create App** → type **Business**.
2. On the app dashboard, add the **WhatsApp** product.
3. Open **WhatsApp → API Setup**. That page has everything:

   | On the page | What it is | Goes into |
   |---|---|---|
   | *Temporary access token* | Expires in **24 hours** | `WHATSAPP_ACCESS_TOKEN` |
   | *Phone number ID* | The long number **under** the test number — not the number itself | `WHATSAPP_PHONE_NUMBER_ID` |
   | *To* → **Manage phone number list** | Add your own mobile, confirm the OTP | — |

The most common mistake here is copying the **test phone number** into
`WHATSAPP_PHONE_NUMBER_ID`. It wants the numeric **ID** shown beneath it. If you get this wrong
the CRM will tell you so in those words.

---

## Point the CRM at it

In `.env`:

```
WHATSAPP_CHANNEL=meta
WHATSAPP_ACCESS_TOKEN=EAAG...            # the temporary token
WHATSAPP_PHONE_NUMBER_ID=123456789012345 # the ID, not the number
```

Pin `WHATSAPP_CHANNEL=meta` rather than leaving it on `auto` while testing. With `auto`, adding
an AiSensy key later silently moves every message to a different channel, and a test that passed
last week starts failing for a reason nobody will look for.

Then restart: `docker compose up -d --build backend`

---

## The three checks, in order

### 1. Is anything connected?

```
GET /api/settings/whatsapp/status
```

Should report `"channel": "WhatsApp Cloud API"`. If it says *Manual WhatsApp*, one of the two
values above is missing — both are required, since a token with no phone number ID cannot
address anything.

### 2. Send yourself the template

```
POST /api/settings/whatsapp/test    { "phone": "919876543210" }
```

This sends the pre-approved `hello_world` template to your phone.

A template, not a normal message, and deliberately so: WhatsApp only allows free-form text
within **24 hours of the student's last message**, and your phone has never messaged the
institute, so that window has never opened. This is exactly the path a real first contact takes.

### 3. Reply from your phone, then send a real message

Replying opens the 24-hour window. Now:

```
POST /api/settings/whatsapp/test-message    { "phone": "919876543210" }
```

If that arrives, sending works.

---

## Attachments need a public address

Attachments are **fetched by WhatsApp's own servers** from the URL the CRM provides. A
`localhost` address is accepted by the API and then never arrives — the message goes, the file
silently does not.

So to test attachments, `PUBLIC_BASE_URL` must be an address reachable from the internet. On a
laptop, a tunnel (`ngrok http 8000` or similar) is enough:

```
PUBLIC_BASE_URL=https://your-tunnel-address
```

Once that is set, send a real pack from a real lead. That is the only test that exercises the
whole path: message, attachments, and open-tracking.

---

## When something fails

The CRM translates Meta's error codes into the actual fix. The four you are most likely to meet:

| What you will see | What it means |
|---|---|
| *The access token has expired…* | The 24-hour test token ran out. Generate a new one, or make a permanent one from a **System User** in Business Settings. |
| *That number is not on the test number's allowed list* | Add it under **To → Manage phone number list** and confirm the OTP. Maximum five. |
| *This student has not messaged in over 24 hours* | The free window closed. Only an approved template will send until they reply. |
| *Check the phone number id is the id from the dashboard* | You pasted the phone number instead of its ID. |

Meta's own wording is written to the server log and never returned to the browser, because its
error text can quote the request back — including the token.

---

## Going beyond testing

The test number cannot message real students. For that you need to register the institute's own
number on the Cloud API, submit your message templates for approval, and swap the temporary
token for a permanent System User token.

If that turns out to be more setup than you want, `WHATSAPP_CHANNEL=aisensy` switches to the
reseller instead, and nothing else in the CRM changes — the pipeline, the packs and the reply
window are all unaffected by which channel is underneath.
