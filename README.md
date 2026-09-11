# blink-backend

Java 25 / Spring Boot 4.1.0 API for the `blink_demo` wizard. It persists the first two screens to Render Postgres and builds the download zip on the requirements screen.

## What the 3 screens do

1. **Welcome** — user picks New or Existing. That value is sent with the project on the next screen.
2. **Project & Stakeholders** — **Save & Continue** `POST`/`PUT`s the project and, when S3 is configured, creates `<slug>_<id>_workspace/` in the bucket and copies the AI-SDLC kit into it.
3. **Download Project** — runs `setup-new-workspace` apply, writes `requirement.md` and `.cursor/` into the same S3 workspace, then zips the kit **from local disk** (does not wait for the full S3 copy).

## Run locally

JDK 25 is required. This repo includes the Maven Wrapper, so you do not need a global `mvn` install.

**Local without a database:** set `SPRING_PROFILES_ACTIVE=nodb` in `.env`. Projects are saved to `.blink-nodb.json` in this folder (gitignored) so they survive a Java restart. Grooming and download still work. Full workspace setup also requires Python 3; on Windows set `BLINK_CANONICAL_SETUP_PYTHON=python` when `python3` is unavailable.

**Production / Render must not set `SPRING_PROFILES_ACTIVE=nodb`.** Use Postgres (`DATABASE_URL`). Always set `BLINK_AGENT_RUNTIME_TOKEN` to the Worker `AGENT_SERVICE_TOKEN`. `.env` is gitignored.

```powershell
Copy-Item .env.example .env
# edit .env: BLINK_AGENT_RUNTIME_TOKEN (and keep SPRING_PROFILES_ACTIVE=nodb)
# S3 keys are optional locally; without them Save still works, S3 copy is skipped
$env:JAVA_HOME = "$env:USERPROFILE\tools\jdk-25"
.\mvnw.cmd spring-boot:run
```

The API listens on `http://localhost:8090`. Vite proxies `/api` to that local port.

In another terminal, from the **frontend** repo (`Development/Blink-Frontend/blink_demo`):

```powershell
npm install
npm run dev
```

Open `http://127.0.0.1:5173`. `.env.development` already sets `VITE_API_URL=/api` so the wizard talks to local Java, not Render.

### Local Python Agent Runtime (optional)

The backend defaults to the live AWS Lambda Agent Runtime (`https://z5i3yybrx1.execute-api.us-west-2.amazonaws.com`). To run the agent from code on this machine instead:

```powershell
cd ..\..\Blink-Framework\automation_sdlc\services\agent-runtime-python
# install requirements
pip install -r requirements.txt
uvicorn app.main:app --port 8787
```

Then in `blink-backend/.env`:

```
BLINK_AGENT_RUNTIME_URL=http://127.0.0.1:8787
BLINK_AGENT_RUNTIME_TOKEN=blink-groom-2026
```

Restart Java after changing `.env`.

## Deploy on Render

You already have Postgres on Render. Deploy the API from the **blink-backend** repo (this folder contains the `Dockerfile`). Deploy the UI as a separate static site from `blink_demo`.

### 1. Backend — Web Service (Docker)

The GitHub repo `blink-backend` already *is* the API. The `Dockerfile` sits at the **repo root** (`Dockerfile`, not `blink-backend/Dockerfile`).

1. **New → Web Service** → `nisha-ts-40599/blink-backend`.
2. Runtime: **Docker**.
3. **Root Directory: leave blank.** Do not set `blink-backend` — that folder does not exist in this repo, and Render will fail with "Root directory 'blink-backend' does not exist".
4. Dockerfile path: `Dockerfile` (default).
5. Instance: at least **1 GB RAM** (zip generation is heavy for 512 MB).
6. Health check: `/actuator/health`
7. Environment:

| Key | Value |
| --- | --- |
| `DATABASE_URL` | **Required.** Link the existing Postgres service, or paste the **Internal** Database URL. Without this the API tries `localhost` and Hibernate fails. **Do not set `SPRING_PROFILES_ACTIVE=nodb` here.** |
| `BLINK_AGENT_RUNTIME_URL` | `https://z5i3yybrx1.execute-api.us-west-2.amazonaws.com` (AWS Lambda endpoint; defaults to this if omitted) |
| `BLINK_AGENT_RUNTIME_TOKEN` | `blink-groom-2026` (defaults to this if omitted) |
| `BLINK_AUTOMATION_SDLC_GIT_URL` | `https://github.com/AtulTalentServ/automation_sdlc.git` (Docker image includes git so the kit can be cloned when `/app/automation_sdlc` is empty) |
| `BLINK_CORS_ORIGINS` | `https://YOUR-FRONTEND.onrender.com` (add after the static site exists; you can also keep `http://localhost:5173`) |
| `AWS_ACCESS_KEY_ID` / `AWS_SECRET_ACCESS_KEY` | **Required for S3 workspaces.** Save & Continue copies the kit to `<slug>_<id>_workspace/`. |
| `AWS_REGION` | `us-west-2` |
| `S3_BUCKET_NAME` | `blink-ai-dev` |
| `S3_PUBLIC_BASE_URL` | `https://blink-ai-dev.s3-us-west-2.amazonaws.com/` |

Render sets `PORT` for you. After deploy, note the URL, e.g. `https://blink-backend-xxxx.onrender.com`.

Confirm: `https://YOUR-BACKEND.onrender.com/api/stakeholder-roles` returns the YAML people.

### 2. Frontend — Static Site

`blink_demo` is **not** inside the `blink-backend` GitHub repo. Do not set Root Directory to `blink_demo` on the backend service.

1. Push `blink_demo` to its own GitHub repo (or a monorepo that actually contains that folder).
2. **New → Static Site** → that frontend repo.
3. Root Directory: **leave blank** if the repo root is `blink_demo`.
4. Build: `npm install && npm run build`
5. Publish: `dist`
6. Environment (**build-time**, then rebuild):

| Key | Value |
| --- | --- |
| `VITE_API_URL` | `https://blink-backend-af7x.onrender.com/api` |

On the **backend** service set:

`BLINK_CORS_ORIGINS=http://localhost:5173,https://YOUR-FRONTEND.onrender.com`

### Local vs Render

| | Frontend | Backend |
| --- | --- | --- |
| Local | http://localhost:5173 | http://localhost:8090 |
| Render | `*.onrender.com` static site | Docker web service |

## Render Postgres

Use the **External Database URL** from the Render dashboard when this API runs on your machine.

The API converts `DATABASE_URL=postgres://...` into JDBC and turns on `sslmode=require` for `*.render.com` hosts.

If you prefer explicit properties:

```
SPRING_DATASOURCE_URL=jdbc:postgresql://HOST:5432/DATABASE?sslmode=require
SPRING_DATASOURCE_USERNAME=...
SPRING_DATASOURCE_PASSWORD=...
```

On first boot Hibernate `ddl-auto=update` will:

- leave existing `users`, `project`, and `stakeholder_roles` in place
- add `project.project_type` if it is missing
- create `stakeholder` if it is missing
- seed a demo user (`blink.system@talentserv.com`) for `created_by`
- seed the wizard roles (`po`, `ba`, `sa`, `tl`, `sc`, `qa`, `devops`) when those `role_code`s are absent

You can also apply `src/main/resources/db/extra-schema.sql` yourself in the Render SQL console.

Set `SPRING_JPA_DDL_AUTO=none` after the schema is stable if you do not want Hibernate to alter tables.

## Stakeholder emails and Jira comments

**Email** — `POST /api/stakeholder-questions/send` groups questions by `recipient_email` and sends **one message per person**.

| Env | Purpose |
| --- | --- |
| `BLINK_SMTP_HOST` | When set, deliver via SMTP |
| `BLINK_SMTP_PORT` | Default `587` |
| `BLINK_SMTP_USERNAME` / `BLINK_SMTP_PASSWORD` | Optional auth |
| `BLINK_SMTP_FROM` | From address |
| `BLINK_SMTP_START_TLS` | Default `true` |
| `BLINK_SMTP_OUTBOX_DIR` | When host is unset, write `.txt` files here (default `.blink-outbox`) |

**Jira** — after epics/stories exist, `POST /api/integrations/jira/comments` posts a clarification comment with `<!-- blink-question:{id} -->`. `POST /api/integrations/jira/comments/poll` returns the next reply after that marker that is not from Blink’s posting account. OAuth already requests `write:jira-work`.

## API

| Method | Path | Used by |
| --- | --- | --- |
| GET | `/api/stakeholder-roles` | Role dropdown |
| POST | `/api/projects` | Save & Continue (first time) |
| PUT | `/api/projects/{id}` | Save & Continue (after going back) |
| GET | `/api/projects/{id}` | Reload |
| POST | `/api/projects/{id}/download` | Download workspace zip. Includes `.cursor/mcp.json` (portable `npx`), `.cursor/mcp.windows.json` (`npx.cmd`), `.cursor/mcp.unix.json`, `.cursor/MCP_SETUP.md`, and `automation_sdlc/.env.mcp.example`. Secrets use `${env:...}` only. Form field `mcpProvider` repeats connected ids (`github`, `jira`, `confluence`). |
| POST | `/api/grooming/clarify` | Hosted `/clarify-requirement` discovery |
| POST | `/api/projects/{id}/setup` | Hosted `/setup-new-workspace` apply |
| GET | `/actuator/health` | Health |

## Table mapping

| Render table | Used for |
| --- | --- |
| `users` | Demo operator for `created_by` / `updated_by` |
| `project` | Name, description, type, code, status |
| `stakeholder_roles` | Role catalog |
| `stakeholder` | People assigned on screen 2 (created by the API) |