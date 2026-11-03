# Personal venue links: production root domain

This checklist supplements the existing [deployment runbook](DEPLOYMENT_RUNBOOK.md#personal-venue-links--platform-prerequisites-local-implementation-only). DNS, Caddy and production activation are separate operations.

Keep `TELEGRAM_WEBAPP_PUBLIC_URL` and the environment's bot unchanged. By default the platform
issues `/v/<slug>` using the HTTPS origin of that Mini App URL. An explicit
`VENUE_PUBLIC_LINK_ORIGIN` changes only the origin in generated personal links; it does not
change the Mini App, Telegram routing or the venue registry. Leave it unset in staging and
until production routing is verified. The backend rejects a nonempty override outside production
or an override that is not an HTTPS origin (no credentials, path other than `/`, query or fragment).
Host and forwarded host do not affect generated links or Telegram targets.

### Production root domain activation checklist

1. **Starting evidence (2026-11-03, public DNS read only):** the zone uses Porkbun nameservers.
   The apex `hookahtootah.club` answers A `207.207.210.229` and `207.207.210.107`; staging
   answers A `178.20.209.5`. Neither hostname answered with AAAA or CNAME. These observations do
   not establish what service owns the apex. A read-only HTTPS check returned `200` with
   `Server: openresty` on the apex, and the application's `404` with `Cache-Control: no-store`
   on an unknown staging slug. The [staging proxy example](STAGING_DEPLOYMENT.md#6-reverse-proxy)
   forwards paths to the staging backend, including `/v/*`. The live Caddyfile and production
   upstream/edge are not verified in this task.
2. **Separate authorization and inventory:** in Porkbun, export/record the current zone, TTLs,
   forwarding settings and apex purpose. Confirm the production edge, backend, bot and database
   identity before planning any write. Preserve staging and dev records, any existing
   `www`/other subdomains, NS, MX/TXT (including verification and mail), and all unrelated Caddy
   sites/routes. Do not add IPv6 unless the production edge is reachable over IPv6. Keep the
   production Mini App origin and every legacy production hostname serving venue links.
3. **Route before issuing links:** on the production edge only, prepare a distinct Caddy site
   for `hookahtootah.club` with trusted HTTPS. Route `/v/*` with its path intact to the verified
   **production** backend. Keep existing website routes and the legacy production `/v/*` handler;
   do not send the apex or real customers to staging. Validate the candidate Caddy config and
   compare existing routes before changing only the approved apex records in Porkbun. Confirm
   DNS convergence and TLS for apex and old hosts. Existing staging URLs continue to resolve in
   staging; never translate a staging alias or `venue_id` into a production one by name.
4. **Smoke before enabling:** with generation still unchanged, open an approved published
   production venue's current slug and an alias on the apex and legacy production host; confirm
   exact venue/address and production bot, an unknown/unavailable slug gives the safe no-store
   response, unlisted venue works by link, and `startapp` (if enabled) and Bot fallback open the
   correct venue. Confirm Mini App and working printed table QR are unchanged; verify staging
   still serves only its own bot/data. Only after those checks set
   `VENUE_PUBLIC_LINK_ORIGIN=https://hookahtootah.club` in the authorized production rollout
   and verify the actual copied URL from Venue Mode.
5. **Rollback:** clear `VENUE_PUBLIC_LINK_ORIGIN` in production to resume issuing the previous
   Mini App origin. Leave both production `/v/*` routes on the same database when possible so
   links already shared on the apex still work. If the edge/DNS change itself must be reverted,
   restore the inventoried apex records and prior Caddy config, then verify the old hostname
   and any retained apex route. Do not rename/reassign slugs or run a data migration to roll back
   link generation; aliases remain reserved in the original database.
