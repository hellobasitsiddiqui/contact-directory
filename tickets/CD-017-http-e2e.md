# CD-017: HTTP-level e2e on a running app

- **Type:** test
- **Status:** In review
- **Branch:** `CD-017-http-e2e`

## Summary
Add end-to-end tests that boot the real server and exercise it over real HTTP (not MockMvc),
covering the auth → contacts → roles → audit → actuator happy paths.

## Acceptance criteria
- [x] @SpringBootTest(RANDOM_PORT) + TestRestTemplate hitting the live port over HTTP
- [x] login → token → contact lifecycle; user isolation + 403; admin users/audit; actuator health
- [x] Runs in the normal `mvn test` (required gate); tests green

## Notes
Added `src/test/java/com/example/contacts/HttpEndToEndTest.java`. The default
`TestRestTemplate` uses the JDK `HttpURLConnection`, which rejects `PATCH` and
mishandles error responses to streamed POSTs; the test swaps in
`JdkClientHttpRequestFactory` (built into Spring Framework, no new dependency) so
the full verb set and 4xx/5xx codes come back cleanly over the wire.
