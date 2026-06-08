# CD-002: Add Actuator health checks & metrics

- **Type:** feature (ops)
- **Status:** In review
- **Branch:** `CD-002-actuator-health-metrics`

## Summary
Expose Spring Boot Actuator health + metrics for deployment/monitoring; health public, metrics secured.

## Acceptance criteria
- [x] spring-boot-starter-actuator added; health/info/metrics exposed
- [x] /actuator/health public (probes); /actuator/metrics requires auth
- [x] Test: health 200 UP (public); metrics 401 unauth / 200 authed
- [x] COMMON-FEATURES Health/Metrics → ✅; README updated; tests green
