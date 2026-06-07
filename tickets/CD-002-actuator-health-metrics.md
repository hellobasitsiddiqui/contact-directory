# CD-002: Add Actuator health checks & metrics

- **Type:** feature
- **Status:** Open
- **Branch:** `CD-002-actuator-health-metrics`

## Summary
Add Spring Boot Actuator so the app exposes health (readiness/liveness) and metrics endpoints —
standard for deployment and monitoring.

## Acceptance criteria
- [ ] `spring-boot-starter-actuator` dependency
- [ ] `/actuator/health` exposed (public); other endpoints secured/limited
- [ ] Metrics available (`/actuator/metrics`, Prometheus optional)
- [ ] Security config permits health appropriately; tests cover access
- [ ] COMMON-FEATURES "Health checks" / "Metrics" flipped to ✅, README updated

## Notes
Mind the existing JWT SecurityConfig — decide which actuator endpoints are public vs admin-only.
