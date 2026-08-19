# Running the whole thing locally with Docker

One command gives you Postgres, the API, and the website on a single address — the same
single-origin arrangement production uses behind Caddy. You do not need Java, Maven or Node
installed; the containers build everything.

## Before you start

- **Docker Desktop** installed and running (docker.com/products/docker-desktop — pick the Apple
  Silicon build).
- Both repositories sitting in the same folder, which they already are:
  ```
  ~/devanshedutech-backend
  ~/devanshedutech-frontend
  ```

## 1. Create your settings file

```bash
cd ~/devanshedutech-backend
cp .env.example .env
```

Open `.env` and set two things:

```
DATABASE_PASSWORD=anything-you-like
SUPER_ADMIN_EMAILS=kulkarnianant8975@gmail.com
```

Leave the rest. The optional keys are empty on purpose — those features degrade cleanly instead
of blocking startup.

## 2. Start it

```bash
docker compose up --build
```

The first run takes several minutes: it downloads Maven dependencies and builds the site. Later
runs take seconds. Leave the terminal open — that is where the logs are.

Wait for:

```
Seeded 7 ladder steps for the Hot lane
Team: 0 staff account(s), 0 active administrator(s).
No active administrator. Sign in with an address listed in app.admin.emails to bootstrap access.
Started DevanshEduTechApplication in 9.1 seconds
```

The "no active administrator" line is expected on an empty database. Step 3 fixes it.

## 3. Create your account

A new database has no users, and open sign-up is deliberately off, so the first account is made
once by hand. In a **second terminal**:

```bash
curl -X POST http://localhost:8000/api/auth/register \
  -H 'Content-Type: application/json' \
  -d '{"email":"kulkarnianant8975@gmail.com","password":"choose-a-password","displayName":"Anant"}'
```

If it replies `Accounts are created by an administrator`, add this to `.env`, restart, and try
again:

```
SELF_REGISTRATION_ENABLED=true
```

The account is created with no permissions — but because the address is listed in
`SUPER_ADMIN_EMAILS`, the server resolves it to Super Admin on sign-in. **Turn
`SELF_REGISTRATION_ENABLED` back to `false` once you are in.**

## 4. Open it

**http://localhost:8000/admin** — sign in with the email and password from step 3.

You should land on **My Day**.

## 5. Work through the checklist

The 14-point list is in `DEPLOYMENT.md`. The five that matter most:

1. My Day loads
2. **Team & Access** — create a counsellor account
3. Sign in as that counsellor: they see only their own leads, and no Team tab
4. Submit the enquiry form at **http://localhost:8000** — the lead appears in the pipeline
5. Open a lead, record an outcome such as "No answer" — the timeline, stage and next touch update

## Useful commands

```bash
docker compose logs -f backend     # follow the API logs
docker compose restart backend     # after changing .env
docker compose down                # stop everything, keep the data
docker compose down -v             # stop and wipe the database, for a clean run
```

## Notes

- **Google sign-in will not work locally** unless you add
  `http://localhost:8000/login/oauth2/code/google` as an authorised redirect URI in the Google
  Cloud console. Password sign-in works without it.
- `COOKIE_SECURE=false` is set for local use because a browser will not store a secure cookie
  over plain HTTP. **Never set it on anything reachable from the internet** — production leaves
  it at the default of `true`.
- `LADDER_ENABLED=false` by default here, so the follow-up pass does not change lead grades
  while you are looking around. Use **Run follow-ups** on My Day to trigger it deliberately.
- This Docker setup is for local staging. Production runs behind Caddy and is configured through
  the host environment.
