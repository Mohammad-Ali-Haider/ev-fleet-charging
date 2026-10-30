# Case 6 — EV fleet charging and trip assignment

Assigns every shift trip to an electric cab and schedules charging so that no cab drops below 15% charge (empty driving included) and no charger
serves two cabs at once. It minimises backup trips first and charging cost second, and re-plans live within 2 seconds when a battery reading drifts
or a charger fails.

**Stack:** Java 17, Spring Boot 3.5, PostgreSQL 17.6, Flyway, Caffeine, Micrometer/Prometheus. The planner and the independent validator are plain
Java classes that need neither Spring nor a database.

**Documents:**
- [docs/DESIGN-NOTE.md](docs/DESIGN-NOTE.md) ([PDF](docs/DESIGN-NOTE.pdf)): the two-page design note, with data model, architecture and sequence
  diagrams, API, algorithm, failure modes and trade-offs.
- [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md): the longer deep dive, including the OOP structure and how the design evolved.

## Run it (one command)

With Docker running:

```sh
docker compose up --build
```

Then open:
- [localhost:8106/docs](http://localhost:8106/docs): the OpenAPI page;
- [localhost:8106/demo](http://localhost:8106/demo): the per-cab timeline page.

PostgreSQL listens on 127.0.0.1:5546. `docker compose down` stops the stack; add `-v` to delete its data.

The compose file has local demo defaults for the database password and token signing key, so no setup is needed. For anything beyond a local demo,
copy [.env.example](.env.example) to `.env` and set your own values. On Windows, `pwsh -File .\run.ps1` does the same with freshly generated random
secrets and waits until the API is healthy.

**Getting a token.** Every API call (except `/health`, `/docs` and `/demo`) needs a token.

A token is a signed text string that says three things:
- which company (tenant) you belong to;
- your role (`admin`, `operator` or `employee`);
- who you are.

The app checks the signature, so nobody can make one without the server's secret key. There is no login page. In a real deployment, the company's
sign-in system would issue these tokens. For local use, the app can print one for you:

```sh
docker compose exec api java -jar /app/app.jar --issue-token demo-tenant admin demo-admin
#                                                            tenant      role  user
```

The printed token is valid for one hour. Send it with each request as the header `Authorization: Bearer <token>`, or paste it into the box on the
demo page.

Use a different tenant name to get a completely separate, empty workspace. On Windows, `pwsh -File .\scripts\demo.ps1` runs the whole flow by itself:
import, plan, battery drift, charger failure and validation.

## Setup guide and troubleshooting

**You need:** Docker Desktop (Windows / Mac) or Docker Engine with the Compose plugin (Linux), and about 2 GB free disk. Nothing else: Java, Maven and
PostgreSQL all run inside Docker.

**Step by step:**

1. Start Docker Desktop. `docker version` should print both a Client and a Server section.
2. Unzip or clone the project and open a terminal in the folder that contains `compose.yaml`.
3. Run `docker compose up --build`. The first run downloads images and builds the app, which takes a few minutes. Later runs take seconds.
4. Check it is up: [localhost:8106/health](http://localhost:8106/health) shows `{"status":"ok"}`.
5. Try it. On Windows, `pwsh -File .\scripts\demo.ps1` does all of this for you. On Mac or Linux, in a second terminal in the same folder:

   ```sh
   TOKEN=$(docker compose exec -T api java -jar /app/app.jar --issue-token demo-tenant admin demo-admin)
   H="Authorization: Bearer $TOKEN"
   curl -s -X POST localhost:8106/api/v1/datasets -H "$H" -H "Content-Type: application/json" --data @examples/scale-dataset.json
   curl -s -X POST localhost:8106/api/v1/plans -H "$H" -H "Idempotency-Key: first-plan"   # prints a jobId
   curl -s localhost:8106/api/v1/jobs/<jobId> -H "$H"                                     # repeat until SUCCEEDED
   curl -s localhost:8106/api/v1/plans/current -H "$H"
   ```

   Then open [localhost:8106/demo](http://localhost:8106/demo), paste the token and load the current plan to see each cab's timeline.
   [localhost:8106/docs](http://localhost:8106/docs) lists every endpoint.

To run in the background instead, use `docker compose up --build -d`. Then `docker compose logs -f api` shows the logs.

**If something does not work:**

| Problem | Cause | Fix |
|---|---|---|
| `Cannot connect to the Docker daemon` / `error during connect` | Docker is not running | Start Docker Desktop and wait until it says it is running |
| `port is already allocated` (8106 or 5546) | Another program uses that port | Stop that program, or change the left-hand number in `compose.yaml`, e.g. `"127.0.0.1:9106:8106"`, then open port 9106 instead |
| The api container keeps restarting, and the logs say `password authentication failed` | The database volume was created earlier with a different password (an older `.env`) | `docker compose down -v` (deletes the old local data), then `docker compose up --build` again |
| The logs say `EV_JWT_SECRET must contain at least 32 characters` | A `.env` sets a signing key that is too short | Use at least 32 characters in `.env`, or delete `.env` to use the defaults |
| `401 Unauthorized` | No token, an expired token (one hour), or a token from before the secret changed | Issue a new token |
| `403 Forbidden` | The role is not allowed (for example, an employee reading fleet data) | Issue an `admin` or `operator` token |
| `404` for a plan you just made | The token is for a different tenant | Use a token with the same tenant name |
| `409 REPAIR_PENDING_OR_INCIDENT` | A repair is still running, or a battery reading below 15% stopped the tenant | Wait for the job to finish, or import the dataset again |
| `422` with an error code | The input broke a rule (for example, unknown ids or values out of range) | Check the JSON against `examples/demo-dataset.json` |
| Build fails while downloading | No internet, or a proxy blocks Maven or Docker Hub | Retry on a normal connection; the first build needs internet |
| `Unable to access jarfile C:/Program Files/Git/app/app.jar` | Git Bash on Windows rewrote the path `/app/app.jar` | Run `export MSYS_NO_PATHCONV=1` first, or use PowerShell |
| Docker runs out of memory | The machine is low on RAM | Give Docker at least 2 GB in its settings |

**Changing settings:**
- Copy `.env.example` to `.env` to set your own database password (`EV_DB_PASSWORD`) and signing key (`EV_JWT_SECRET`). Run `docker compose down -v`
  before the first start with a new password.
- Ports are in `compose.yaml`.
- Planner and worker settings, such as `ev.worker.threads`, are in `src/main/resources/application.properties`. Rebuild with `--build` after changing
  them.

**Start over completely:** `docker compose down -v`, then `docker compose up --build`.

## How it works

1. **Batches.** Trips that overlap in time form a batch, since one cab can serve at most one trip of a batch.
2. **Assignment.** Each batch is solved with the **Hungarian algorithm**. Trips covered come first, then (when repairing) fewest changed
   assignments, then least empty driving.
3. **Charging stops.** A trip no cab can reach directly gets a just-in-time charging stop.
4. **Idle charging.** After each batch, idle cabs too low for an average trip top up.
5. **Local search.**
   - Each backup trip is offered to nearby cabs, which may hand up to two of their trips to other cabs.
   - Each cab's charging is then moved into the cheapest tariff window its timetable allows, and the change is kept only if it is cheaper.
6. **Backup reasons.** Every remaining backup gets one, such as `CHARGER_CONGESTION` or `NO_CAB_WITH_ENOUGH_CHARGE_IN_TIME`, with evidence.
7. **Validation.** An **independent validator** (separate code, BigDecimal arithmetic) replays every plan before it is published. A plan that fails is
   never published.
8. **Live re-plan.**
   - The past and imminent work are frozen.
   - The affected cab and the two cabs nearest its next pickup are reopened.
   - A trip stays on its cab whenever a charging stop fits.
   - The new plan is published only if nothing changed underneath it.

The objective is compared in order (backups, then cost), never as a weighted sum, so cheaper charging can never cost a covered trip.

**Lower bound:** trips no cab could ever run, plus the peak number of overlapping trips minus the number of cabs. Each plan reports
`backupGap` = backups − bound.

**Charging model:** full power to 80% of the battery, then ×0.35 above it, with 90% efficiency. Cost integrates grid energy over every tariff and curve
boundary.

**Consumption:** distance × Wh/km × air-conditioning factor × traffic factor.

## Measured performance

Machine: Windows 11, AMD Ryzen AI 9 HX 370 (24 logical CPUs), 31 GiB RAM, Temurin 17.0.19, JVM heap 1 GiB, PostgreSQL 17.6 in the Compose
container. Shared machine, no CPU isolation.

Workload: 300 cabs / 40 chargers / 1,500 trips, 1 warm-up plus 10 measured seeds. Each timing runs end to end: queue, claim, plan, validate and
commit to PostgreSQL.

| Workflow | Brief's limit | p50 | p99 (= slowest of 10) |
|---|---|---:|---:|
| Initial plan | 30 s | 0.154 s | 0.190 s |
| Mid-shift battery drift re-plan | 2 s | 0.243 s | 0.359 s |
| Charger failure re-plan | 2 s | 0.252 s | 0.289 s |

Results across all ten seeds:
- 0 backups;
- 100% of charging energy bought off-peak;
- ₹3,712–3,885 per day;
- 0 trips reassigned by either re-plan, because the drifted cab kept its trips by taking a charging slot;
- every plan passed the validator;
- sequential throughput: 6.2 plans/s.

**Harder rush-hour day** (`Fixtures.rushHour`: 17–40% starting charge, +30% traffic, +15% AC, plugs busy most of the shift): 64–84 of 1,500 trips
on backups, each explained as charger congestion or not enough charge in time, planned in 0.08–0.12 s. Its lower bound is 0, so these backups are
not proved unavoidable.

**Scaling** (planner only, fixed 2 s budget):

| Cabs / trips | Time | Backups |
|---|---:|---:|
| 100 / 500 | 0.01 s | 0 |
| 300 / 1,500 | 0.04 s | 0 |
| 600 / 3,000 | 0.18 s | 0 |
| 1,200 / 6,000 | 0.41 s | 3 |
| 2,000 / 10,000 | 0.91 s | 0 |

2,000 cabs is the input cap. Beyond it, the batch matrix (trips in a batch × cabs) and the charger calendar scans grow fastest.

**HTTP** (2-cab demo, concurrency 4, 80 reads): 234 requests/s, p50 12 ms, p99 32 ms, 0 errors.

Raw data: [benchmark.json](evidence/benchmark.json), [scaling.json](evidence/scaling.json), [http-smoke.json](evidence/http-smoke.json),
[machine.json](evidence/machine.json), [scale-plan.json](evidence/scale-plan.json). p99 over ten samples is the observed maximum, not an SLA.

## Tests

48 tests, all passing on PostgreSQL 17.6:

| Test class | Tests | What they cover |
|---|---:|---|
| `CoreTests` | 32 | Plain Java, no database. Input limits; every hard rule; the validator rejecting corrupted plans; the brief's mid-shift drift example; charger congestion reasons; off-peak charging; the lower bound; a 300-cab low-churn regression. |
| `PlannerInternalsTests` | 3 | Exact `Fraction` vs `BigInteger`; Hungarian vs brute force; charger calendar release. |
| `PersistenceTests` | 10 | Real PostgreSQL. Duplicate requests and events, out-of-order events, two publishers racing, expired leases, rollback, incidents, queue limits, a failure-injection test of the repair fallback, and a child JVM killed in the middle of publishing. |
| `ApiTests` | 2 | Real HTTP. Login required, roles, tenant isolation, no personal data in errors, the employee view. |
| `JsonCompatibilityTests` | 1 | A saved 1,500-trip plan still reads and validates. |

Run the core tests without a database:

```sh
mvn -Dtest=CoreTests,PlannerInternalsTests test
```

On this Windows machine, use `pwsh -File .\mvnw.ps1` in place of `mvn`. It selects JDK 17 and keeps Maven's files inside the project.

The full suite needs an empty PostgreSQL database and a signing key:

```sh
DATABASE_URL=jdbc:postgresql://127.0.0.1:5546/<empty test database> DB_USER=evfleet DB_PASSWORD=<password> \
EV_JWT_SECRET=<at least 32 random characters> mvn verify
```

The benchmark driver is `src/test/java/com/moveinsync/evfleet/BenchmarkMain.java`. Run it with the same variables; it also runs `ScalingMain` and
writes `evidence/`. Spotless checks the formatting during `validate`; run `mvn spotless:apply` after editing.

## Project layout

| Package | Role |
|---|---|
| `api` | HTTP controller, error handler, health |
| `application` | Use cases: import and plan requests, live events, queries, running a job, cache |
| `planner` | Plain Java planning core: `GreedyPlanner` and one class per phase, `RepairPreparer`, `LowerBound` |
| `validator` | `IndependentValidator` |
| `domain` | Immutable records and `InputRules` |
| `persistence` | `PlanRepository` (JDBC) and stored records; schema in `src/main/resources/db/migration` (Flyway) |
| `security`, `infrastructure` | JWT config and token CLI; background worker and request metrics |

Reading order: `application/PlanningService` → `planner/GreedyPlanner` → `planner/RepairPreparer` → `validator/IndependentValidator`.

## How we got here

The first version was a plain greedy planner. It gave each trip the first cab that could take it and charged only when forced. It met the hard rules
and the 30-second limit (4.3 s per plan), but:
- it charged at peak prices (₹10.6–11.7k per day, 0% off-peak);
- it took about 7 s of work per 1,500 trips, so under a 2-second budget it gave up on 2,999 of 10,000 trips at 2,000 cabs;
- on a busy day it left 135–182 trips on backups, all with the same vague reason.

Profiling showed exact `BigInteger` arithmetic was most of the time. We then made these changes, keeping each only when the benchmark improved:
- switched `Fraction` to plain `long` (still exact);
- assigned overlapping trips together with the Hungarian algorithm, which beat a simpler greedy on backups;
- added off-peak, idle and backup-rescue passes;
- gave each backup a specific reason;
- made live re-plans keep trips on their cab.

The full step-by-step table is in [ARCHITECTURE.md §11](docs/ARCHITECTURE.md#11-how-the-design-evolved).

## Assumptions, trade-offs and limits

**Assumptions:**
- straight-line distances with a constant traffic factor per shift; a real map service can replace `OfflineTravel`;
- trips cannot be split, and backup cabs are unlimited;
- efficiency 0.90 and slow-charge factor 0.35;
- no regenerative braking, hub power limit or battery ageing.

**Trade-offs:**
- **Consistency over throughput.** One writer per tenant at a time. An event that arrives while a repair is pending gets 409 and must be retried.
- **Heuristic, not optimal.** Batch-by-batch assignment and a depth-one rescue can miss better global arrangements. The lower bound is weak on busy
  days.
- **Unsafe readings stop the tenant.** A reading below 15%, or one taken mid-trip, raises a tenant-wide incident instead of guessing. Isolating just
  that cab would be the next improvement.

**Cache:** `PlanCache` (Caffeine) holds decoded plans and datasets. Those rows are never updated, so a cached copy is never stale. The active-plan
pointer and tenant status are always read from the database. The cache is limited by weight (about 75 MB) and entries expire 30 minutes after last
access.

**Observability:**
- logs are structured JSON (Elastic Common Schema) with no personal data;
- Prometheus metrics at `/actuator/prometheus` cover queue depth and lag, job outcomes, HTTP statuses, rejections, retries, deadline misses and
  cache hits.

**Production would add:** OIDC/JWKS in place of the shared signing key, TLS, secrets management, real road and energy data, and alerting.
