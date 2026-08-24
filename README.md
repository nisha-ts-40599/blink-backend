# blink-backend

Java 25 / Spring Boot 4.1.0 API for the `blink_demo` wizard. It persists the first two screens to Render Postgres and builds the download zip on the requirements screen.

## What the 3 screens do

1. **Welcome** — user picks New or Existing. That value is sent with the project on the next screen.
2. **Project & Stakeholders** — **Save & Continue** `POST`/`PUT`s:
   - `project` (`project_name`, generated `project_code`, `description`, `status`, `project_type`, audit columns)
   - `stakeholder` rows (name, email, `role_id` → `stakeholder_roles`)
3. **Requirements** — **Download Project** returns a zip containing:
   - `automation_sdlc/`
   - generated Spring Boot 4.1.0 Maven project
   - `requirement.md`

## Run locally

JDK 25 and Maven 3.9+ are required. From this folder:

```powershell
$env:DATABASE_URL = "postgres://USER:PASSWORD@HOST:5432/DATABASE"
$env:BLINK_AUTOMATION_SDLC_PATH = "..\automation_sdlc"
mvn spring-boot:run
```

The API listens on `http://localhost:8090`. Vite in `blink_demo` already proxies `/api` there.

Then in another terminal:

```powershell
cd ..\blink_demo
npm install
npm run dev
```

Open `http://localhost:5173`.

## Deploy on Render

You already have Postgres on Render. Add **two more services** from the same Git repo (repo root must contain `Dockerfile`, `blink-backend/`, `blink_demo/`, and `automation_sdlc/`).

### 1. Backend — Web Service (Docker)

1. **New → Web Service** → this Git repo.
2. Runtime: **Docker**. Dockerfile path: `Dockerfile`. Do **not** set a Root Directory.
3. Instance: at least **1 GB RAM** (zip generation is heavy for 512 MB).
4. Health check: `/actuator/health`
5. Environment:

| Key | Value |
| --- | --- |
| `DATABASE_URL` | Postgres **Internal** Database URL (Link the existing Blink database) |
| `BLINK_CORS_ORIGINS` | `https://YOUR-FRONTEND.onrender.com` (add after the static site exists; you can also keep `http://localhost:5173`) |
| `JAVA_OPTS` | `-XX:MaxRAMPercentage=75.0` |

Render sets `PORT` for you. After deploy, note the URL, e.g. `https://blink-backend-xxxx.onrender.com`.

Confirm: `https://YOUR-BACKEND.onrender.com/api/stakeholder-roles` returns the YAML people.

### 2. Frontend — Static Site

1. **New → Static Site** → same repo.
2. Root Directory: `blink_demo`
3. Build: `npm install && npm run build`
4. Publish: `dist`
5. Environment (**build-time**):

| Key | Value |
| --- | --- |
| `VITE_API_URL` | `https://YOUR-BACKEND.onrender.com/api` |

Rebuild the static site after this value is set. Then put that site origin into `BLINK_CORS_ORIGINS` on the backend and redeploy the API.

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

## API

| Method | Path | Used by |
| --- | --- | --- |
| GET | `/api/stakeholder-roles` | Role dropdown |
| POST | `/api/projects` | Save & Continue (first time) |
| PUT | `/api/projects/{id}` | Save & Continue (after going back) |
| GET | `/api/projects/{id}` | Reload |
| POST | `/api/projects/{id}/download` | Download Project (`multipart`: `file`, `requirementsText`) |
| GET | `/actuator/health` | Health |

## Table mapping

| Render table | Used for |
| --- | --- |
| `users` | Demo operator for `created_by` / `updated_by` |
| `project` | Name, description, type, code, status |
| `stakeholder_roles` | Role catalog |
| `stakeholder` | People assigned on screen 2 (created by the API) |