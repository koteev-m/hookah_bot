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
