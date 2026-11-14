# Squad — Race-Proof Hackathon Team Formation

Squad is a hackathon team formation platform designed to handle concurrent membership operations safely and consistently.

Built around PostgreSQL as the source of truth, the system uses database constraints, transactional operations and row-level locking to prevent race conditions during team formation.

The platform supports team creation, invite-based joining, join requests, membership management, teammate recommendations and real-time updates.

## Tech Stack

| Layer | Technology |
|---|---|
| Backend | Java 21, Spring Boot 3 |
| Database | PostgreSQL 16 |
| Database Migrations | Flyway |
| Frontend | React, TypeScript, Tailwind CSS |
| Authentication | JWT, Spring Security |
| API Documentation | Swagger / OpenAPI |
| Real-time Updates | Server-Sent Events |
| Testing | JUnit, Testcontainers |
| Load Testing | k6 |
| Infrastructure | Docker, Docker Compose |
| CI | GitHub Actions |

## Architecture

```text
team-formation/
│
├── backend/       Spring Boot REST API
├── frontend/      React + TypeScript application
├── loadtest/      k6 tests and database seed data
├── scripts/       Database invariants and race simulations
├── docs/          Architecture and design documentation
│
├── .github/       CI workflows
├── docker-compose.yml
├── docker-compose.chaos.yml
├── .env.example
├── .gitignore
└── README.md
```

### Backend Modules

The backend follows a feature-oriented package structure:

- `auth` — Registration, login and authentication.
- `user` — User profiles, skills and time zones.
- `hackathon` — Hackathon management and team boards.
- `team` — Team creation and membership operations.
- `recommendation` — Complementary skill and timezone matching.
- `audit` — Membership and team activity history.
- `realtime` — Server-Sent Events.
- `idempotency` — Safe handling of repeated requests.
- `config` — Application configuration and security.
- `common` — Shared components and exception handling.

## Core Engineering Design

### Concurrency Control

PostgreSQL acts as the source of truth for membership operations.

The implementation combines:

- Transactional membership changes.
- Team-row locking to serialize competing join operations.
- Database constraints as a final integrity boundary.
- Atomic persistence of membership changes and idempotency records.

This ensures that concurrent requests cannot exceed a team's configured capacity.

### Idempotency

Idempotency keys are supported for:

- Team creation.
- Joining a team.
- Join requests.
- Accepting or declining requests.

Repeated requests using the same key do not create duplicate operations.

### Team Lifecycle

The platform supports:

- Configurable minimum and maximum team sizes.
- One team per user per hackathon.
- Invite codes with 128-bit entropy.
- SHA-256 hashed invite codes.
- 48-hour invite expiration.
- Join requests and leader-controlled approvals.
- Leadership transfer when a leader leaves.
- Automatic team deletion when the last member leaves.

### Security

- JWT-based authentication.
- Role and membership-based authorization.
- Rate limiting for authentication and joining.
- Invite-code expiration and brute-force protection.
- Audit logging for team activity.

## Race Arena

Race Arena provides a controlled demonstration of concurrent team joining.

It creates a temporary team with one remaining seat and releases multiple participants simultaneously using a synchronization barrier.

Three execution strategies can be compared:

| Strategy | Behaviour |
|---|---|
| Safe | Team-row locking protects the final seat. |
| No Lock | Database constraints prevent overbooking, but competing requests can encounter constraint errors. |
| Naive | Check-then-insert logic can overbook teams and cause counter inconsistencies. |

The arena includes database invariant checks and an audit of the resulting membership state.

It is intended for controlled demonstrations and is disabled by default in production.

## API Overview

All endpoints return JSON. Authentication uses Bearer JWT tokens, except public authentication endpoints.

| Method | Endpoint | Description |
|---|---|---|
| POST | `/api/auth/register` | Register a user |
| POST | `/api/auth/login` | Authenticate a user |
| GET / PUT | `/api/users/me` | Retrieve or update profile |
| GET | `/api/hackathons` | List hackathons |
| GET | `/api/hackathons/{id}/board` | Paginated team board |
| GET | `/api/hackathons/{id}/my-team` | Retrieve current team |
| POST | `/api/teams` | Create a team |
| GET | `/api/teams` | List teams |
| GET / PATCH | `/api/teams/{id}` | Retrieve or update team |
| POST | `/api/teams/{id}/invites` | Generate invite code |
| POST | `/api/teams/{id}/join` | Join using invite code |
| POST / GET | `/api/teams/{id}/requests` | Create or list join requests |
| PATCH | `/api/requests/{id}` | Accept or decline request |
| DELETE | `/api/teams/{id}/leave` | Leave a team |
| DELETE | `/api/teams/{id}/members/{userId}` | Remove a member |
| GET | `/api/teams/{id}/audit` | Retrieve audit history |
| GET | `/api/teams/{id}/suggestions` | Retrieve teammate recommendations |
| POST / GET | `/api/arena/race` | Execute race demonstration |
| POST / GET | `/api/arena/status` | Retrieve arena status |
| GET | `/api/stream/hackathons/{id}` | Hackathon event stream |
| GET | `/api/stream/teams/{id}` | Team event stream |

Full API documentation is available through Swagger UI at `/swagger-ui.html`.

## Testing & Reliability

The backend test suite covers approximately 60 test cases across:

- Authentication and security.
- Team and membership rules.
- Invite management.
- Join requests.
- Leadership transfer and team deletion.
- Idempotency.
- Audit logging.
- Board queries.
- Recommendations.
- Rate limiting.
- Concurrent membership operations.

Concurrency scenarios include:

- 50 users competing for one remaining seat.
- One user attempting to join 20 teams.
- 20 requests sharing the same idempotency key.
- Three simultaneous acceptance requests competing for one seat.
- Ten concurrent team creations.
- Interleaved joins and leaves.

The last-seat race test runs five times per build. CI additionally repeats the concurrency test class to detect flaky behaviour.

### Running Tests

```bash
cd backend
mvn verify
```

Testcontainers requires Docker to run the integration tests against PostgreSQL 16.

## Load Testing

The load-testing setup includes:

- 10,000 seeded users.
- 2,000 seeded teams.
- k6 board endpoint testing.
- 100 virtual users.
- A target p95 latency below 200 ms.

Run the load test:

```bash
docker compose up -d --build

docker compose exec -T db \
  psql -U teamformation -d teamformation \
  < loadtest/seed.sql

k6 run \
  -e BASE_URL=http://localhost:8080 \
  loadtest/board.js
```

Generated results are written to `loadtest/report.json`.

### Database Invariant Checks

```bash
docker compose exec -T db \
  psql -U teamformation -d teamformation \
  < scripts/invariants.sql
```

The invariant query is designed to return zero rows when database invariants hold.

## Chaos Testing

The chaos testing script simulates server failure during concurrent membership operations.

```bash
./scripts/chaos.sh
```

The scenario includes:

1. Preparing teams and competing users.
2. Generating a concurrent join storm.
3. Terminating the API process during execution.
4. Restarting the API.
5. Retrying requests with their original idempotency keys.
6. Verifying database invariants and idempotency consistency.

A separate SQL-only simulation is available:

```bash
./scripts/sql_race_sim.sh
```

This tests concurrent database operations using 50 PostgreSQL sessions and a synchronization barrier.

## Local Development

### Prerequisites

- Java 21
- Maven
- Node.js and npm
- Docker and Docker Compose
- PostgreSQL 16 (optional when using Docker)

### Environment Configuration

Create a local `.env` file using `.env.example` as a template.

Configure the PostgreSQL credentials and JWT secret before starting the application.

### Start the Application

```bash
docker compose up --build
```

Application endpoints:

| Service | URL |
|---|---|
| Frontend | http://localhost:3000 |
| Backend | http://localhost:8080 |
| Swagger UI | http://localhost:8080/swagger-ui.html |

### Run Services Individually

Start PostgreSQL:

```bash
docker compose up db
```

Start the backend:

```bash
cd backend
mvn spring-boot:run
```

Start the frontend:

```bash
cd frontend
npm install
npm run dev
```

The development frontend runs on port `5173` and proxies `/api` requests to the backend on port `8080`.

## Deployment

The application supports containerized backend deployment and static frontend hosting.

A typical deployment consists of:

- React frontend hosted as a static site.
- Spring Boot backend hosted as a Docker web service.
- Managed PostgreSQL database.

The deployment configuration uses environment variables for database connectivity, authentication secrets and CORS origins.

## Design Documentation

Additional architectural decisions, database design, concurrency handling and trade-offs are documented in:

`docs/DESIGN.md`

---

**Squad**  
*Reliable team formation, even when everyone clicks Join at the same time.*
