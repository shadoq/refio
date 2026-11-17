# profile-service request cache

`src/cache.js` exports `RequestCache`, the TTL cache in front of the profile
API. The dashboard renders several widgets per key in parallel, so a cold key
is expected to produce a single upstream load shared by every caller;
`warm(keys)` pre-populates entries for the nightly warmup job.

Run the acceptance gate with:

    node checks.js
