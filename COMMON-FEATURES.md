# Common App Features — Baseline Checklist

A reusable checklist of capabilities **almost every production app needs**, regardless of domain.
Use it as a starting template for new projects. The **This app** column tracks the
[contact-directory](README.md) reference implementation and is **kept up to date as features land**.

**Legend:** ✅ implemented · ➖ partial · ⬜ not yet

---

## 1. Authentication & accounts
| Feature | Why | This app |
|---|---|:--:|
| Registration & login | Let users in | ✅ |
| Password hashing (bcrypt/argon2) | Never store plaintext | ✅ |
| Token or session auth (JWT / session) | Stateless or stateful identity | ✅ JWT |
| Change own password | Self-service | ✅ |
| Forgot / reset password (via email) | Recovery without admin | ⬜ |
| Email verification | Confirm ownership | ⬜ |
| 2FA / MFA (TOTP) | Second factor | ⬜ |
| Brute-force lockout / login rate-limit | Stop credential stuffing | ✅ |
| Account enable/disable + lifecycle | Offboarding, suspension | ➖ (no soft-delete) |
| Refresh tokens / session revocation | "Sign out everywhere" | ⬜ |

## 2. Authorization
| Feature | Why | This app |
|---|---|:--:|
| Role-based access (RBAC) | Coarse permissions | ✅ USER/ADMIN |
| Fine-grained permissions | Beyond roles | ⬜ |
| Resource ownership / per-user data | Users see only their data | ✅ |
| Multi-tenancy / organizations | Isolated tenants | ⬜ |
| Admin user management | Operate accounts | ✅ |

## 3. API design
| Feature | Why | This app |
|---|---|:--:|
| Versioning (`/api/v1`) | Evolve without breaking | ✅ |
| Consistent error model | Predictable clients | ✅ |
| Request validation | Reject bad input early | ✅ |
| Pagination / sort / filter | Scale list endpoints | ✅ |
| OpenAPI / Swagger docs | Discoverable API | ✅ |
| Optimistic concurrency | Prevent lost updates | ✅ `@Version` |
| CORS configuration | Cross-origin clients | ➖ (same-origin) |
| Global rate limiting | Abuse protection | ⬜ |

## 4. Data & persistence
| Feature | Why | This app |
|---|---|:--:|
| ORM / data-access layer | Maintainable persistence | ✅ |
| DB migrations (Flyway/Liquibase) | Versioned schema | ⬜ (ddl-auto) |
| Seed / reference data | Usable on first run | ✅ |
| Soft delete + trash | Reversible deletes | ✅ (contacts) |
| Created/updated timestamps | Provenance | ✅ |
| Backups / restore | Disaster recovery | ⬜ |

## 5. Observability
| Feature | Why | This app |
|---|---|:--:|
| Structured logging | Debuggability | ➖ |
| Audit log (who did what) | Accountability | ✅ |
| Health checks (readiness/liveness) | Orchestration | ⬜ (no Actuator) |
| Metrics (Micrometer/Prometheus) | Monitoring | ⬜ |
| Distributed tracing | Cross-service debugging | ⬜ |
| Error monitoring (Sentry, etc.) | Catch prod errors | ⬜ |

## 6. Security hardening
| Feature | Why | This app |
|---|---|:--:|
| Secrets via env / vault | No secrets in code | ➖ (env-overridable, dev defaults) |
| TLS / HTTPS | Encrypt in transit | ⬜ (deploy concern) |
| Security headers (CSP, HSTS…) | Browser hardening | ➖ |
| Dependency vulnerability scanning | Known-CVE deps | ✅ Dependabot + dependency-review |
| Static analysis (SAST) | Code-level flaws | ✅ CodeQL |
| Input validation / output encoding | Injection/XSS defense | ✅ |
| CSRF protection | State-changing forms | ➖ (stateless JWT, N/A) |

## 7. Testing
| Feature | Why | This app |
|---|---|:--:|
| Unit tests | Fast feedback | ✅ |
| Integration tests | Wiring correctness | ✅ |
| Coverage measurement + gate | Prevent regressions | ✅ (JaCoCo gate) |
| End-to-end / UI tests | User-flow confidence | ⬜ |
| Isolated test data | Deterministic tests | ✅ |

## 8. CI/CD & build
| Feature | Why | This app |
|---|---|:--:|
| Build tool + wrapper | Reproducible builds | ✅ Maven wrapper |
| CI on every push/PR | Catch breakage early | ✅ GitHub Actions |
| Coverage gate in CI | Enforce quality bar | ✅ |
| Static analysis in CI | Automated review | ✅ CodeQL |
| Automated dependency updates | Stay current/secure | ✅ Dependabot |
| Containerization (Docker) | Portable runtime | ✅ |
| Artifact build & publish | Distributable output | ✅ JAR + GHCR image |
| Release automation (on tag) | Versioned releases | ⬜ |
| Deployment / CD | Ship to environments | ⬜ |
| Status badges | At-a-glance health | ✅ |
| Protected main + PR workflow | Reviewed, gated merges | ✅ |
| Ticket / issue tracking | Traceable work | ✅ (plain-text `tickets/`) |

## 9. Configuration & ops
| Feature | Why | This app |
|---|---|:--:|
| Environment-based config | 12-factor | ✅ |
| Profiles (dev/test/prod) | Per-env behavior | ✅ (test profile) |
| Feature flags | Safe rollout | ⬜ |
| Graceful shutdown | No dropped requests | ➖ (framework default) |

## 10. Frontend / UX (if applicable)
| Feature | Why | This app |
|---|---|:--:|
| Responsive layout | Mobile + desktop | ➖ |
| Dark / light theme | Comfort + preference | ✅ |
| Accessibility (a11y) | Inclusive, legally safer | ➖ (some ARIA) |
| Internationalization (i18n) | Multi-language | ⬜ |
| Loading / empty / error states | Polished UX | ✅ |

## 11. Documentation
| Feature | Why | This app |
|---|---|:--:|
| README (setup + run) | Onboarding | ✅ |
| API documentation | Consumers | ✅ OpenAPI |
| Walkthrough / screenshots | Show, don't tell | ✅ |
| CHANGELOG | Track changes | ⬜ |
| LICENSE | Usage terms | ⬜ |
| CONTRIBUTING | Contributor guide | ✅ |

## 12. Compliance & privacy
| Feature | Why | This app |
|---|---|:--:|
| Data export / account deletion (GDPR) | User rights | ⬜ |
| Consent / terms tracking | Legal basis | ⬜ |
| Audit / data retention policy | Compliance | ⬜ |

---

> Maintained alongside the contact-directory app — the **This app** column is updated whenever a new
> baseline capability is added. See [FEATURES.md](FEATURES.md) for this app's full feature list.
