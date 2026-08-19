# Deploying the CRM

This covers the first deployment of the CRM work (features F1, F2, F3, F5 and F9). Read the
**Before you deploy** section fully — one of the changes alters who can sign in.

---

## What changes on the running system

The schema changes are **additive only**. Hibernate runs with `ddl-auto: update`, which can add
columns and tables but cannot alter or drop them, so nothing existing is modified or removed.
On first boot the application adds:

| Table | Change |
|---|---|
| `leads` | 33 new columns (stage, grade, source, owner, next touch, ladder, attribution) |
| `users` | 6 new columns (active, phone, timestamps) |
| `lead_activities` | new table, 11 columns |
| `ladder_steps` | new table, seeded with 21 rows |
| `audit_logs` | new table, 9 columns |

Three startup runners then fill in the gaps. They only touch rows where a value is missing, so
they are safe to run on every boot and do nothing once complete.

**Watch the logs on first boot.** They report what happened:

```
Normalised 4 of 12 user accounts.
Team: 3 staff account(s), 1 active administrator(s).
Seeded 7 ladder steps for the Hot lane
Backfilled 340 of 340 leads with pipeline fields.
340 active lead(s) are waiting to be graded Hot, Warm or Cold.
2 phone number(s) appear on more than one lead.
```

---

## Two behaviour changes to expect

**1. Self-registration is off.** `POST /api/auth/register` now returns 403 unless
`SELF_REGISTRATION_ENABLED=true`. Accounts are created by a manager in the Team screen. Anyone
who registered while it was open now holds the `NONE` role and has no access — they had no admin
access before either, so nothing is lost, but they will need a real role granted.

**2. Set `SUPER_ADMIN_EMAILS` before first boot.** A configured address always resolves to that
role regardless of what is stored, which is the bootstrap path: sign in with Google and you have
access before any account exists to grant it to you. If no administrator is configured *and* none
exists in the database, the log says so explicitly:

```
No active administrator. Sign in with an address listed in app.admin.emails to bootstrap access.
```

---

## Configuration

**Required — the application will not start without these:**

| Variable | Notes |
|---|---|
| `DATABASE_URL` | JDBC URL for Postgres |
| `DATABASE_USERNAME` | |
| `DATABASE_PASSWORD` | |

Everything else has a working default. `Backend CI` fails the build if a new setting becomes
required without being recorded here.

**Worth setting deliberately:**

| Variable | Default | What it does |
|---|---|---|
| `SUPER_ADMIN_EMAILS` | *(empty)* | Bootstrap access. Set this. |
| `ADMIN_EMAILS` | two addresses in `application.yml` | Existing admins |
| `MANAGER_EMAILS`, `COUNSELLOR_EMAILS`, `VIEWER_EMAILS` | *(empty)* | Roles pinned by config; day-to-day roles are set in the Team screen |
| `ALLOWED_ORIGINS` | localhost | **The first entry is also the post-login redirect target**, so the canonical public origin must come first |
| `CRM_TIMEZONE` | `Asia/Kolkata` | Every follow-up rule is a date. A server in UTC would put an 8pm follow-up on the wrong day |
| `SELF_REGISTRATION_ENABLED` | `false` | Leave off |
| `LADDER_ENABLED` | `true` | **Set to `false` for the first staging run** — see below |
| `LADDER_CRON` | `0 0 6 * * *` | The daily follow-up pass |
| `LADDER_GRACE_DAYS` | `3` | Slack after the last step before a lead changes grade |
| `GEMINI_API_KEY` | *(empty)* | Website chatbot. Without it the chatbot returns "temporarily unavailable"; everything else works |
| `CLOUDINARY_*` | placeholders | Image uploads |
| `GOOGLE_CLIENT_ID` / `_SECRET` | placeholders | Google sign-in |

---

## Staging procedure

1. **Take a database backup.** The changes are additive, but this is the rollback plan.
2. Deploy with `LADDER_ENABLED=false`. The follow-up pass changes lead grades on a schedule, and
   you want to see the backfill land before anything starts moving on its own.
3. Watch the startup log for the runner output above. A stack trace here means stop.
4. Work through the checklist below.
5. Grade a handful of leads by hand, then set `LADDER_ENABLED=true` and use **Run follow-ups**
   on My Day to trigger the pass on demand rather than waiting for 6am. Check the result in the
   log:
   ```
   Follow-up pass over 340 open lead(s): {step=12, held=2}
   ```
6. Only then promote to production.

### Checklist

- [ ] Application starts; no stack traces in the first 60 seconds
- [ ] The four startup runner lines appear in the log
- [ ] An administrator can sign in (password and Google)
- [ ] **Team screen**: create a counsellor, then sign in as them
- [ ] That counsellor sees only their own leads, and no Team tab
- [ ] Deactivate them; confirm they lose access on their *next request*, not at session expiry
- [ ] The public enquiry form still creates a lead — test it on the live site
- [ ] Submit the same number twice: the second is recognised, not duplicated
- [ ] Existing leads appear in the leads list with a stage
- [ ] Record an outcome on a lead; check the timeline, stage and next touch all update
- [ ] "Not interested" refuses to save without a reason
- [ ] Dashboard loads; figures with too little data read "Not enough data yet" rather than 0%
- [ ] Mobile: the leads table scrolls sideways without the page doing so
- [ ] Trigger the follow-up pass and read the summary line

---

## Rollback

The application code rolls back cleanly — redeploy the previous image or commit. The previous
version ignores the new columns entirely, so a rollback needs **no schema change**.

The one thing that does not roll back is data written by the new version: activity log rows,
audit entries, and backfilled fields. None of it is destructive, so the safe order is:

1. Redeploy the previous application version.
2. Leave the new tables and columns in place — the old code does not read them.
3. Restore the database backup **only** if the backfill itself caused a problem, which would show
   up as errors in the startup log rather than later.

If you roll back after users have worked leads in the new version, that work lives in columns the
old code cannot see. It is not lost, and it reappears when you roll forward again.

---

## Known limitations at this deployment

- **Nothing has run against Postgres.** The full suite (150 tests, including an application boot)
  runs against H2 in Postgres-compatibility mode. That proves the mappings, queries and startup
  are sound; it does not prove identical behaviour on Postgres. This is the reason for a staging
  run.
- **No browser testing.** The screens typecheck and build but have not been seen rendered.
- **Funnel history starts now.** Conversion rates are computed from the deepest stage a lead ever
  reached, and leads that predate this deployment have no transition history, so they count from
  their current stage. Expect the numbers to settle over the first few weeks.
- **No rate limiting on the public enquiry endpoint.** The chat endpoint is limited; the lead form
  is not.
- The repository's `nginx.conf` and `docker-compose.yml` predate the move to Caddy and are not
  what production runs. They are left as-is rather than half-updated.
