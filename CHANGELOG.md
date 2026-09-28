# Changelog

All notable changes to this project are documented here. Versions use the
`MAJOR.MINOR.PATCH.MICRO` format.

## [0.4.0.0] - 2026-09-28

### Added
- Log in with email and password (`POST /api/auth/login`) to get a 30-minute bearer token.
- Every service now checks tokens: signing in is required to change the catalog or to see
  users, a user can only read, change or delete their own account, and payment and inventory
  endpoints accept only internal service tokens.
- order-service can mint 60-second internal service tokens for its calls to other services.
- Clear 401/403 problem responses, and logins that don't reveal which emails are registered.

### Changed
- Every service needs `JWT_SECRET` (32+ bytes) and refuses to start without it; the compose
  stack provides a local development value.

## [0.3.0.0] - 2026-09-28

### Added
- Users API: register (BCrypt-hashed password, case-insensitive unique email), get, paged
  list, update and soft delete.
- Catalog API: create a product with its stock, get it with available stock, list by category
  (paged), and update catalog fields with optimistic locking (a stale version gets 409).
- Consistent errors: every failure is an RFC 7807 problem response with a `code`, and invalid
  requests list each bad field.
- Database schemas managed by Flyway migrations, checked by Hibernate at startup.

## [0.2.0.0] - 2026-09-28

### Added
- Integration tests against a real MySQL 8.4 (Testcontainers), set up by the same init script
  as the compose stack: the service's database health, per-service schema isolation, and root
  locked to localhost are now checked automatically, not by hand.
- Continuous integration: every push to `main` and every pull request builds and runs all
  tests on GitHub Actions.
- README instructions for running the integration tests on Windows with Docker in WSL.

## [0.1.0.0] - 2026-09-27

### Added
- Four Spring Boot 4.1 / Java 21 services (users 8081, products 8082, orders 8083,
  payments 8084) that start, connect to MySQL and report health at `/actuator/health`.
- One-command local stack: `scripts/build.sh` builds the jars and images (from Git Bash
  with Docker in WSL, from WSL/Linux, or in CI), then `docker compose up -d --wait`
  brings up MySQL 8.4 and all four services, healthy.
- Database per service enforced by MySQL itself: each service logs in as its own user
  that can only access its own schema.
- Maven Wrapper pinned to Maven 3.9.16 with a verified checksum, so builds need no global Maven.
- Tests that run offline in `./mvnw verify`: service health and configuration, ports,
  jar names and passwords that must agree across compose, Dockerfiles and config, and
  the build and MySQL init scripts run against stubs.
- Design doc, README quick start and `.env.example` for local overrides.

### Changed
- Service containers are capped at 512 MB with the JVM heap sized to 60% of that, and
  each image's build context is only its prebuilt jar.

### Fixed
- The stack now recovers after WSL or Docker restarts: MySQL restarts along with the services.
- MySQL reports healthy only after its setup finished; a bad password in `.env` now stops
  setup with a clear message and the `docker compose down -v` fix, instead of starting
  a MySQL with no service users.
- MySQL root can no longer log in over the network with the public dev password.
- `scripts/build.sh` only falls back to WSL from Git Bash, and otherwise shows Docker's
  own error.
