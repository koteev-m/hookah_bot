# Personal venue links on the root domain

The prelaunch setup uses one HT70 `hookah-bot` Compose project on the existing VPS,
one PostgreSQL database, and the current Telegram bot and Mini App. Keep
`APP_ENV=staging` and `TELEGRAM_WEBAPP_PUBLIC_URL=https://staging.hookahtootah.club/miniapp/`.
The staging hostname remains the Mini App origin and a compatible address for links already shared.

By default, links use the HTTPS origin of `TELEGRAM_WEBAPP_PUBLIC_URL`. The optional
`runtime.VENUE_PUBLIC_LINK_ORIGIN` in HT70's private `config.json` accepts exactly
`https://hookahtootah.club`. Leave the key absent until activation. The override
changes the origin in generated links only. The same backend serves both hosts and
uses the same venue registry, aliases, availability checks and Telegram targets.
Request Host and forwarded headers cannot choose the generated origin.

The public preflight on 2026-11-03 found Porkbun parking on the root domain (A
`207.207.210.107` and `207.207.210.229`), `www` forwarding to the root, and
staging at `178.20.209.5`. Recheck those observations, confirm the actual Hookah
upstream and public IPv4, and record the current zone, TTLs, parking/forwarding
and Caddy configuration before any authorized change. The root DNS cutover affects
the whole hostname. Agree on what to serve outside `/v/*` before the cutover.

## Activation

A Draft PR or green CI does not authorize DNS, Caddy or HT70 changes. Use the
[HT70 release procedure](STAGING_ORDINARY_RELEASE.md) for exact release admission
and the existing runtime, Compose and database binding checks.

1. **A — Caddy.** Add a site for `hookahtootah.club` to the existing edge. Match
   `handle /v/*` and reverse proxy to the verified existing Hookah upstream
   (HT70 default `127.0.0.1:8080`). Preserve the full URI so the backend receives
   `/v/<slug>`. Serve the agreed root/other paths separately. Validate the Caddy
   candidate, compare it with the saved configuration, and keep staging and Clubs
   routes intact. Ensure the root can obtain trusted TLS.
2. **B — Porkbun.** Replace only the root's parking A records with the confirmed
   VPS IPv4. Preserve `www`, staging, NS, mail/verification and other records.
   Confirm what visitors following the existing `www` redirect will see.
3. **C — Public smoke before issuance.** Check DNS convergence and trusted HTTPS
   for the root and staging. Verify `https://hookahtootah.club/v/miks` and the old
   staging link show the same published venue and Telegram target. An unknown or
   unavailable slug must return the safe `404` with `Cache-Control: no-store`.
   Stop before D if any check fails.
4. **D — HT70 activation.** After exact-release CI and candidate admission, deliver
   the matching HT70 tool and add `"VENUE_PUBLIC_LINK_ORIGIN": "https://hookahtootah.club"`
   to `runtime` in the root-owned private `/opt/hookah-bot/config.json`. Use the
   existing HT70 state and candidate files. Apply the change through the standard
   config gate, leaving the generated Compose file to the tool:

   ```bash
   python3 -B /opt/hookah-bot/simple_staging.py deploy --directory /opt/hookah-bot \
     --candidate /opt/hookah-bot/candidate.json --apply-config
   ```

   Follow the ordinary compatibility and backup rules if the selected candidate
   changes the schema. This feature adds no migration.
5. **E — Acceptance.** Confirm Venue Mode copies the root link. Open it and the
   previously shared staging link in Telegram on mobile. Check a known alias and
   an unlisted test venue by direct link if fixtures exist. Verify the bot
   `/start` fallback opens the same venue, the Mini App and a working printed
   table QR still open correctly, and Clubs passes its existing public smoke.

## Rollback

Remove the key from `runtime` and use the same admitted release with
`deploy --apply-config`. Issuance resumes on the staging origin. Keep both
`/v/*` routes while safe so already shared links keep working. If the edge
change must also be undone, restore the saved Porkbun parking records/settings
and prior Caddy configuration, then verify DNS/TLS, staging links and Clubs.
Restoring parking removes the root venue route for already shared links.
Leave slug/alias reservations and database contents as they are.

## Venue subdomains

`VENUE_PUBLIC_LINK_FORMAT` is an optional HT70 `runtime` key. Omit it or set
`path` to issue the existing `/v/<slug>` format with the current origin.
Set it to `subdomain` only after the edge and HTTPS checks below pass.
On Hookah staging the application can serve the subdomain landing before
issuance is enabled, and it keeps serving it if issuance is later disabled.
The Mini App copies the URL supplied by the API.

Each new or renamed address uses the existing slug registry, a maximum of
63 ASCII characters, and an unreserved DNS label. The application reserves
`www`, `dev`, `staging`, `markettap-stg`, `api`, `admin`, `app`, `mail`,
`webmail`, `autoconfig` and `autodiscover`. Review explicit DNS and Caddy
hostnames before adding future services to the zone. For an old service-name
or 64-character slug, the API issues a stable `venue--<base36-id>` label in
subdomain mode. Double hyphens cannot collide with registry slugs. This
label opens only a venue that has an incompatible slug reserved in the
registry, even after it chooses a new address. The original `/v/<slug>`
keeps working and existing aliases stay reserved for their original
venue. Eligible aliases serve on both formats. No database migration is required.

### TLS choice and activation state

The initial 2026-11-03 read-only SSH check confirmed Caddy 2.11.7, no `dns.providers.*` modules,
and the live `staging.hookahtootah.club` route to `127.0.0.1:8080`. The route check did not
find an apex, `www`, or wildcard host match. Public DNS returned the wildcard
CNAME to `pixie.porkbun.com.`, apex parking IPv4 answers, A answers at
`178.20.209.5` for dev, staging and markettap-stg, and existing TXT values
at `_acme-challenge`. Public DNS can show a flattened apex ALIAS as A answers
and synthesize a CNAME for `www`. These are historical observations, not the
current activation baseline.

Before this PR, the separate wildcard TLS task installed the reviewed Caddy
2.11.7/Porkbun build and obtained `*.hookahtootah.club`. Credentials use protected
systemd `LoadCredential` files and runtime `{file.*}` placeholders; `--environ`
is absent. Staging retains its own certificate through `tls force_automate`.
The owner reports that public DNS now resolves `miks.hookahtootah.club` to
`178.20.209.5` and external HTTPS returns Caddy's safe 404. This publication task
does not repeat live infrastructure checks or change DNS/Caddy. The application
release and wildcard backend route are still pending. Treat a 404 at this stage
as edge readiness only, not a working venue entry flow.

Prefer one wildcard certificate for `*.hookahtootah.club` with automatic DNS-01.
It needs one certificate and renewable DNS credentials for the controlled zone.
The installed custom build contains `dns.providers.porkbun`; do not replace it
as part of application activation. Preserve the existing service, TLS policy,
protected credentials, certificate storage and credential-aware rollback path.
Use private service credentials limited to the required domain. Verify
that DNS-01 adds and removes only its own challenge TXT values and that
renewal works when due. The initial live issuance and cleanup were verified;
actual automatic renewal has not yet been exercised. See [Caddy wildcard TLS](https://caddyserver.com/docs/caddyfile/patterns#wildcard-certificates)
and the [Porkbun DNS module](https://github.com/caddy-dns/porkbun#config-examples).

On-Demand TLS can issue individual certificates using the stock binary,
but would require a private, fail-closed `ask` endpoint that authorizes only
the exact zone, one valid nonreserved label and a registered, eligible
venue or alias. It adds first-visit issuance and per-domain certificate
management. The wildcard plan avoids this extra admission service. Do not
enable unrestricted On-Demand TLS. See [Caddy On-Demand TLS](https://caddyserver.com/docs/automatic-https#on-demand-tls).

### Future subdomain activation

1. Inventory the Porkbun zone, TTLs, forwarding rules and effective Caddy
   config. Confirm the Hookah upstream and VPS IPv4 independently. Deploy
   the compatible Hookah release through HT70 with the format key absent.
   Compare the existing staging, Mini App, legacy link and Clubs smoke.
   Verify the configured path origin actually serves `/v/` before issuing
   subdomain links. If the apex is parked, staging remains the working path
   origin unless the apex is separately routed.
2. In a separately authorized rollout, validate a candidate against the
   existing Caddy configuration. Keep all explicit sites, the installed
   binary, wildcard DNS-01 policy, systemd credentials and staging's
   `tls force_automate` intact. Replace only the wildcard site's deny-only
   handler with a GET-only root route and terminal 404. Preserve the original
   Host for the HTTP upstream. The app accepts one valid label in the fixed
   zone and ignores forwarded hosts for venue lookup. TLS/SNI is terminated
   by Caddy; the loopback backend does not receive or authenticate SNI.
   Example handler replacement inside the existing wildcard site, if the
   confirmed Hookah upstream is still `127.0.0.1:8080`:

   ```caddyfile
   @venueEntry {
       method GET
       path /
       not host www.hookahtootah.club dev.hookahtootah.club staging.hookahtootah.club markettap-stg.hookahtootah.club api.hookahtootah.club admin.hookahtootah.club app.hookahtootah.club mail.hookahtootah.club webmail.hookahtootah.club autoconfig.hookahtootah.club autodiscover.hookahtootah.club
   }
   handle @venueEntry {
       reverse_proxy 127.0.0.1:8080 {
           header_up -Cookie
           header_up -Authorization
       }
   }
   handle {
       respond 404
   }
   ```

   Validate and review the candidate before activation, including explicit
   host precedence, Host/SNI handling and forwarded-host spoofing. Check the
   wildcard certificate and preserve the configured renewal mechanism.
   With the format still disabled, use `curl --resolve` with the confirmed
   IPv4 to test trusted TLS and the route before public DNS changes.
3. Verify the already prepared public DNS against its approved baseline;
   no DNS mutation belongs to this PR or application rollout. For a future
   fresh installation only, the DNS transition is to replace
   `CNAME * → pixie.porkbun.com.` with `A *` to the confirmed IPv4 under
   separate authorization. Leave the root ALIAS, explicit A records for `dev`,
   `staging`, `markettap-stg`, and existing `_acme-challenge` TXT, NS,
   mail, CAA and verification records in place. If `www` currently
   inherits the wildcard, first add an explicit `CNAME www → pixie.porkbun.com.`
   to preserve current Porkbun handling, or use a separately approved redirect. If it already
   has a record, preserve it. Validate `www` forwarding independently.
   The wildcard does not cover the apex. If the apex is parked, this
   procedure leaves it parked. The legacy staging `/v/` remains
   routable. Apex `/v/` requires a separately routed apex as above.
   See [Porkbun wildcard records](https://kb.porkbun.com/article/94-what-is-a-wildcard-dns-record)
   and [existing ACME TXT records](https://kb.porkbun.com/article/270-why-are-there-acme-challenge-dns-records-on-my-domain).
4. Verify public DNS and trusted TLS. Check `miks.hookahtootah.club`, an
   eligible alias, an unlisted venue, and a random or unavailable
   subdomain. Confirm the last case gives the safe no-store 404, and that
   a wildcard host cannot serve Mini App or API paths. Check service
   hosts, both existing staging links and any already routed apex
   `/v/`, and the existing Clubs smoke. Verify no certificate is
   issued per venue. Stop before changing issuance if a check fails.
5. Add `"VENUE_PUBLIC_LINK_FORMAT": "subdomain"` under `runtime` in the
   private HT70 `config.json`, then use `deploy --apply-config` as
   documented above with the admitted candidate. Keep
   `VENUE_PUBLIC_LINK_ORIGIN` as configured for path fallback.
   In Venue Mode verify the displayed and copied URL for the selected
   venue. Open old and new links in Telegram on mobile, including
   `/start` fallback, and verify an existing printed table QR.

To roll back issuance, remove the format key and use `deploy --apply-config`
with the same admitted release. Keep wildcard DNS/TLS and its route while
safe so shared subdomain links still work. If undoing the edge change is
necessary, restore the saved deny-only wildcard handler and verify both
projects. Keep the existing DNS/TLS and credential configuration; undoing
that separately completed infrastructure stage needs its own authorization
and credential-aware rollback plan. Verify apex, `www`, staging,
explicit service hosts, and Clubs. Removing wildcard routing breaks
links already shared on subdomains. Keep slug reservations and venue data.
