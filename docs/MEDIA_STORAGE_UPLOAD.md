# Venue Media Storage And Upload

Дата актуализации: 2026-10-29 (direct staff-photo management; local implementation).

Статус: **CANONICAL SPEC / STAFF PHOTOS LOCAL IMPLEMENTATION / OTHER UPLOAD RUNTIME FUTURE**.

Live R2/staging and production storage admission remain unverified.

This document is the canonical source of truth for venue-owned media storage, upload, delivery,
lifecycle and Bot/Mini App compatibility. It covers the foundation needed by public-card
info-section media and the flat Photo/PDF menu. Structured menu-item media and staff profile photos
remain separate product slices; staff-photo local implementation is specified below.

## UX08 Staff Photos — Local R2 Implementation (2026-10-28)

Task `HT-UX08-STAFF-PHOTOS-R2-IMPLEMENT-01` authorizes the bounded staff-photo runtime below.
It supersedes the previous UX08 E storage-decision blocker. Venue info-section/menu upload remains
future work; this does not approve a shared-media rewrite or production storage admission.

Existing staging bucket: **`hookah-tootah-media-staging-eu`**. User testimony reports an empty
bucket on 2026-10-28; the account/bucket was not inspected by this implementation. Actual default
class, jurisdiction, public access, credentials and backup remain unverified. The `-eu` name does
not establish jurisdiction. No production bucket is approved. No Cloudflare writes were performed.

### Approved contract change — 2026-10-29

`HT-UX08-STAFF-PHOTO-DIRECT-MANAGEMENT-01` explicitly supersedes the original UX08 per-image
consent and separate publication model, including `HT-UX08-STAFF-UI-CONSENT-FIX-01`'s consent UI.
The product owner now requires equal OWNER/MANAGER photo management, including protected linked
cards, automatic current-photo activation after successful upload, and no employee-consent step
or owner attestation. This is an intentional product-contract change, not a claim that the old
implementation met the new requirement. Historical consent evidence is not new authorization.

No schema/data migration or bulk photo conversion is needed. Existing current `APPROVED` photos
remain active under unchanged card/Guest/Today visibility. Existing `PENDING`/`HIDDEN` candidates
remain private and never promote on read, retry or upgrade; a fresh explicit upload is required.
New records leave all consent fields NULL. No actor action is presented as employee consent.
Historical consent fields remain compatibility/history fields, not a delivery prerequisite.

### Implemented contract

- AWS SDK for Java v2 **2.57.5**, synchronous S3 client with URLConnection transport, explicit static
  credentials, `auto` region, path-style requests, disabled chunked encoding and required-only SDK
  checksums. No default AWS credential chain, custom signing, public URLs, CORS or filesystem fallback.
  Every PUT explicitly requests **STANDARD**. Connect/socket/attempt/whole-call limits: 3/10/15/20s;
  bounded GET reads occur inside the SDK response transformer. PUT/GET/HEAD/DELETE errors are safe.
- Backend-mediated authenticated JPEG/PNG upload: input ≤5 MiB, ≤4096 px per side, ≤16 MP; two global
  decode/upload slots and a 30s absolute body deadline. Validate signature/MIME/CRC/actual decoding,
  reject corruption/animation, normalize EXIF orientation, strip metadata/GPS and re-encode RGB JPEG
  ≤1024 px/side and ≤2 MiB. `metadata-extractor` **2.22.0** reads bounded EXIF only. HEIC/HEIF/WebP
  are rejected with instructions to export JPEG. Real iPhone picker conversion remains unverified.
- PostgreSQL **V127** / H2 **V128** add `staff_profile_photos` durable ledger and
  `staff_profile_photo_state` revision/current/candidate pointers. Existing migrations are unchanged.
  State reads initialize an empty revision row under the profile lock so later identity changes
  invalidate an already selected file even before the first upload. `photo_ref` remains legacy;
  Guest always gets `photoRef=null` and a guarded `photoUrl`, never storage keys or Telegram identity.
- Reserve metadata and a random immutable object key before PUT. Unique actor/profile/request UUID
  plus input digest and expected revision prevent duplicate PUT after a lost response. Reservation
  increments revision; finalization rechecks live RBAC, identity, row state and revision. It locks the
  photo record against cleanup. A failed replacement leaves the current approved photo intact.
- OWNER and MANAGER have equal own-venue photo upload/replace/delete rights for display-only,
  STAFF-, MANAGER- and OWNER-linked cards. Photo authorization is independent of general profile
  `canManage`; it grants no role/account/linkage/finance or card-publication rights. STAFF may view
  only their own card/photos and cannot upload, approve, consent, publish, hide, revoke or delete.
  Legacy STAFF self-edit of `photoRef` is denied too; bio/tags self-edit remains unchanged.
- Successful PUT is followed by one transaction: recheck RBAC/identity/revision, set the new row
  `APPROVED`, switch `current_id`, clear `candidate_id`, tombstone the previous current and any
  legacy candidate, and audit `STAFF_PHOTO_UPLOADED`. `APPROVED` now means active/current photo,
  not human approval or consent. Failed PUT/finalization preserves the previous pointers and queues
  the attempted object for durable cleanup. New upload still requires the team module enabled;
  reads and management deletion remain possible when disabled. Deleted venues are denied.
- Guest bytes require exact current `APPROVED` photo ID and matching linked identity, plus existing
  authoritative venue, subscription, card and MANUAL/SCHEDULE visibility, checked before and after
  storage reads. Hidden/unpublished cards never become public merely by uploading a photo.
  Delivery is authenticated, `image/jpeg`, `nosniff`, `no-store, private`; no permanent bearer URL.
- Delete immediately clears both pointers and queues cleanup. Legacy `/hide` remains a one-way
  private-hide action for OWNER/MANAGER only, omitted from the new UI; reactivation requires a new
  upload. Relink, membership removal and role transitions still invalidate affected photos.
  Ledger ownership survives hard profile/venue deletion for eventual cleanup.
- Cleanup is a small bounded batch (10/minute), gated by the existing autonomous-write maintenance
  policy. Expired 10-minute upload leases and DELETE_PENDING rows are retried. Only acknowledged
  DELETE becomes DELETED; a failed delete remains pending. Tombstones are retained and reconciled
  daily to catch an exceptionally late PUT. Cleanup success means provider acknowledgement, not backup
  erasure. No recovery window is promised: deleted bytes require a fresh upload.
- Upload quota: global 120/10min, actor+venue 30/10min. Legacy revoke/hide and management delete use a separate
  actor 60/minute quota; reads global 1200/minute, actor 240/minute and 16 concurrent storage reads.
  No automatic UI retries. Selection/pending state survives noncancelable same-document Back only
  in memory, scoped to user/venue/role/permissions. Refresh never silently rebases a selected image.

### API and configuration

Authenticated routes under `/api`:

| Method/path | Meaning |
| --- | --- |
| GET `/venue/{v}/staff/photos/me` | Own linked profiles and photo state; STAFF read-only, OWNER/MANAGER photo management |
| GET `/venue/{v}/staff/profiles/{p}/photos` | Authorized current/legacy candidate, revision and visibility; canConsent=false, consentPolicy=staff-photo-direct-v2 |
| POST same path | Raw JPEG/PNG, `Idempotency-Key` UUID and `X-Photo-Revision`; atomically becomes current after successful PUT |
| POST same path + `/delete` | OWNER/MANAGER only; revision-checked immediate delivery removal and cleanup |
| POST same path + `/hide` | Legacy OWNER/MANAGER-only revision-checked private hide; no new UI control |
| POST same path + `/consent`, `/publish`, `/revoke` | Retired: after same-venue photo RBAC, 410 `STAFF_PHOTO_ACTION_RETIRED` for OWNER/MANAGER, 403 for STAFF/outsiders; no writes, consent or candidate promotion |
| GET same path + `/{photoId}/content` | Current/legacy candidate private preview: own-venue OWNER/MANAGER or linked self |
| GET `/guest/venue/{v}/staff/{p}/photos/{photoId}` | Exact currently eligible Guest image |

Server-only configuration: `STAFF_PHOTOS_R2_ENABLED` (default false),
`STAFF_PHOTOS_R2_ENDPOINT`, `STAFF_PHOTOS_R2_BUCKET`, `STAFF_PHOTOS_R2_ACCESS_KEY`,
`STAFF_PHOTOS_R2_SECRET_KEY`, `STAFF_PHOTOS_R2_REGION` (`auto`),
`STAFF_PHOTOS_R2_JURISDICTION` (`default` or `eu`, explicitly verified),
`STAFF_PHOTOS_MAX_INPUT_BYTES` (may lower but not raise 5 MiB). Invalid/incomplete configuration
safely disables uploads while other backend functions keep working. Production configuration is
explicitly disabled until a separate admission change. Configured bucket must equal the staging
bucket above; endpoint must be the verified account's HTTPS R2 endpoint, without arbitrary paths.

### Separate staging configuration and acceptance checklist

These are prerequisites for a later authorized task, not permission for external changes now:

1. Owner verifies the exact Hookah bucket, actual jurisdiction and S3 endpoint in Cloudflare;
   confirm **Standard** default and object class, disabled r2.dev and no public custom domains.
   EU requires `<account>.eu.r2.cloudflarestorage.com`; ordinary default uses
   `<account>.r2.cloudflarestorage.com`. Do not infer location from the name.
2. Owner supplies dedicated bucket-only **Object Read & Write** credentials through the approved
   server secret channel, never chat/PR/Git/logs. Do not reuse another project's keys. Token creation,
   rotation or changes require separate authorization; prefer time-limited scoped credentials.
3. Current HT70 uses private root-owned `config.json` runtime and generated `compose.json`,
   both mode 0600, directory 0700. Existing configurations without photo keys stay valid and disabled;
   enabling requires the complete validated R2 block and an explicit `deploy --apply-config`.
   A separately authorized operator supplies credentials only through the approved server secret
   channel. The executor validates old Compose/runtime hashes and the actual previous backend
   before replacing it; it passes photo fields only to backend environment. Never edit generated
   Compose or put secrets in candidate, Git/CI/chat/logs/browser. Legacy `docker-compose.yml` / `.env`
   is a different deployment path and is not HT70 configuration delivery. See the exact key and
   transition contract in [HT70](STAGING_ORDINARY_RELEASE.md#ux08-staff-photos-optional-r2-runtime-в-ht70).
   HT70 rejects malformed/incomplete configuration before deployment; the backend independently
   retains its safe disabled fallback. Before V126 -> V127, require an exact compatibility review,
   verified backup and off-VPS readiness. No old-schema rollback after V127. No media volume or
   browser credentials are needed. Confirm maintenance policy permits cleanup.
4. Record operator and budget. Standard free allowance is 10 GB-month, 1M Class A operations/month,
   10M Class B/month; R2 egress is free. These are account allowances, not a $0 hard cap. Inspect R2
   dashboard storage/operations/billable usage regularly; small normalized photos and rate limits
   reduce usage. No paid Workers/CDN/image service or billing change is part of this slice.
5. With separate live-write permission and exact release acceptance, upload a synthetic JPEG/PNG;
   inspect actual object Standard class, dimensions, metadata stripping, authenticated preview,
   direct activation with no consent/publish request, protected-card Manager rights, legacy-route
   denial, all visibility toggles, replacement and restart durability. Test real
   iPhone Telegram picker/cancel/HEIC error/orientation, short viewport and desktop.
6. Delete, verify immediate Guest/private denial, durable pending state under simulated
   delete failure, then actual R2 HEAD absence after acknowledged cleanup. No secrets/object keys
   should appear in public artifacts. Do not claim deletion before that observation.
7. Agree backup destination, encryption, operator, retention and RPO/RTO before production. In an
   authorized isolated restore drill, reconcile metadata/checksums and deletion tombstones before
   enabling delivery, ensuring revoked/deleted images cannot reappear from backups. Existing AP-00
   S3 DR scripts use different semantics and are not staff-photo backup evidence. No backup or
   restoration capability is claimed by the local fake-storage tests.

Local deterministic fake storage and loopback SDK tests validate contracts, not Cloudflare
connectivity. PostgreSQL migration/concurrency tests and browser screenshots are local evidence;
actual Cloudflare configuration, staging acceptance and production privacy/backup admission remain open.

Official references checked 2026-10-28: [Java SDK setup](https://developers.cloudflare.com/r2/examples/aws/aws-sdk-java/),
[API compatibility](https://developers.cloudflare.com/r2/api/s3/api/),
[storage classes](https://developers.cloudflare.com/r2/buckets/storage-classes/),
[jurisdiction endpoints](https://developers.cloudflare.com/r2/reference/data-location/),
[private/public access](https://developers.cloudflare.com/r2/buckets/public-buckets/),
[bucket-only credentials](https://developers.cloudflare.com/r2/api/tokens/),
[pricing](https://developers.cloudflare.com/r2/pricing/).

## Core Rule

The platform database is the source of truth for media identity, venue ownership, section linkage,
visibility and lifecycle. The configured storage backend is the source of truth for bytes.
Telegram, Venue Mini App, Guest Mini App and Preview must read and mutate the same asset rows; they
must not maintain independent media lists.

Raw Telegram `file_id`, Telegram file paths, object keys, filesystem paths, bot tokens, storage
credentials and provider payloads are private. Public and Guest DTOs expose only an opaque delivery
identifier and a guarded platform URL.

Do not store durable uploads in the backend container filesystem. Do not start the runtime slice
for broader media or production until storage, backup, deletion and operations ownership are approved.
The explicitly authorized local staff-photo slice above remains disabled without valid configuration.

## Scope And Product Boundaries

The first bounded runtime slice is only:

**Venue Public Card / Info-Section Media Upload in Mini App**.

It includes existing public info sections, including the flat `section_type=menu` Photo/PDF menu.
That menu remains view-only and is not the structured order menu.

It does not include:

- structured menu-item photos, descriptions or thumbnails;
- category, option or flavor media;
- staff profile photos;
- promotion/banner media migration;
- video or arbitrary documents;
- image editor or cropper;
- CDN/image optimization;
- bulk upload;
- AI image processing;
- public galleries or social features.

Staff profile photos remain bound to the employee profile domain. Their direct OWNER/MANAGER
management, guest-visibility and deletion policy are specified above; they must not be added as a side effect of
venue media work.

## Future Surfaces

After the first slice is release-closed, later specs may evaluate:

- structured menu-item description/photo/thumbnail using the structured menu domain;
- option/flavor media only after its Guest ordering value is proven;
- promotion/banner migration from its existing Telegram-specific table;
- staff profile photos only through the direct-management rules in `docs/STAFF_PROFILES_SHIFTS_TIPS.md`.

These surfaces may reuse the approved private storage adapter and safe delivery primitives. They do
not automatically share one asset row, lifecycle or authorization policy, and none is part of the
first implementation prompt.

## Current Architecture Evidence

### Schema And Repositories

- `backend/app/src/main/resources/db/migration/postgresql/V46__venue_info_sections.sql` and the H2
  counterpart create `venue_info_sections`; `venue_id` owns the section and `is_visible` hides or
  shows the whole section.
- `backend/app/src/main/resources/db/migration/postgresql/V48__venue_info_section_media.sql` and
  the H2 counterpart create `venue_info_section_media(id, section_id, media_type,
  telegram_file_id, sort_order, created_at)`. The only media types are `image` and `pdf`.
- `backend/app/src/main/kotlin/com/hookah/platform/backend/telegram/db/VenueInfoSectionMediaRepository.kt`
  inserts, lists, finds, counts and hard-deletes those rows. It stores no bytes, original filename,
  normalized MIME, dimensions, checksum, actor, update time, lifecycle status or deletion state.
- `backend/app/src/main/kotlin/com/hookah/platform/backend/telegram/db/VenueInfoSectionsRepository.kt`
  scopes section reads and visibility changes by `venue_id`. Its media repository currently checks
  only that `section_id` exists on insert; the Bot flow supplies the server-created dialog state,
  but a future HTTP route must use an explicit venue/section ownership join.
- `backend/app/src/main/resources/db/migration/postgresql/V35__menu_category_images.sql` contains
  legacy `menu_category_images.image_url`. Its repository seeds placeholder URLs for named Bot test
  venues; it has no Owner CRUD or active Guest/Venue Mini App consumer and is not structured
  menu-item media.
- `backend/app/src/main/resources/db/migration/postgresql/V91__venue_promotion_media.sql` has a
  separate Telegram-`file_id` promotion banner model. Promotion media is adjacent evidence, not
  part of the first slice.
- `backend/app/src/main/resources/db/migration/postgresql/V117__staff_profiles_today_shifts.sql`
  contains nullable untyped `staff_profiles.photo_ref`, which is not delivery authority. The separate
  staff-photo ledger and R2 runtime are described in the UX08 E section above.

### Telegram Upload And Storage

- `backend/app/src/main/kotlin/com/hookah/platform/backend/telegram/TelegramModels.kt` receives
  Telegram `PhotoSize` and `Document` metadata, including `file_id`, optional `file_unique_id`,
  filename, MIME and size.
- `backend/app/src/main/kotlin/com/hookah/platform/backend/telegram/TelegramBotRouter.kt` allows
  users passing `hasVenueAdminOrOwner` to enter
  `OWNER_VENUE_DESCRIPTION_WAIT_SECTION_MEDIA`. For a photo it stores the largest received
  `file_id`; for a document it accepts PDF when Telegram MIME equals `application/pdf` or the
  filename ends in `.pdf`.
- The current Bot flow does not download or inspect bytes before accepting media. It has no explicit
  file-size, image-dimension or attachment-count limit. PDF validation trusts Telegram metadata or
  filename and is not content sniffing.
- The Bot can append multiple attachments, hard-delete a row and toggle the whole section
  visibility. It has no direct replace action and no per-attachment hidden state.
- `backend/app/src/main/kotlin/com/hookah/platform/backend/telegram/TelegramOutboxEnqueuer.kt`
  sends existing media back to Telegram with `sendPhoto`/`sendDocument` and the stored `file_id`.
- `backend/app/src/main/kotlin/com/hookah/platform/backend/telegram/TelegramApiClient.kt` can upload
  PNG photo bytes, but that method returns only Boolean and has no document-bytes equivalent. It
  therefore does not currently implement the technical-storage-chat bridge required by option A.

Telegram's official Bot FAQ says `file_id` values can be treated as persistent. The Bot API also
states that a `file_id` is bot-specific, `getFile` currently downloads files up to 20 MB, generated
file links are valid for at least one hour, and original filename/MIME may not survive `getFile`:

- [Telegram Bots FAQ: media and persistent file IDs](https://core.telegram.org/bots/faq#handling-media)
- [Telegram Bot API: File and getFile](https://core.telegram.org/bots/api#getfile)

### Guest And Preview Delivery

- `backend/app/src/main/kotlin/com/hookah/platform/backend/telegram/TelegramApiClient.kt`
  implements `downloadFile(fileId)`: call Telegram `getFile`, obtain a temporary `file_path`, then
  download the whole response into a `ByteArray`.
- `backend/app/src/main/kotlin/com/hookah/platform/backend/miniapp/guest/GuestVenueReadService.kt`
  returns only numeric media id, type, sort order and the platform-relative URL
  `/api/guest/venue/{venueId}/info-sections/{sectionId}/media/{mediaId}`. It does not expose
  `telegram_file_id`.
- `backend/app/src/main/kotlin/com/hookah/platform/backend/miniapp/guest/GuestVenueRoutes.kt`
  registers that media URL without Bearer authentication so browser `<img>` and PDF links can load
  it. The route still checks exact Guest venue/subscription availability, venue-to-section
  ownership, section visibility and media-to-section ownership before asking Telegram for bytes.
- The current proxy uses Telegram's returned image content type when it is an image; otherwise it
  falls back to JPEG. For a database media type of PDF it forces `application/pdf`. This is response
  typing, not upload-time content validation.
- `miniapp/src/screens/guestVenue.ts` lazy-loads images through the platform URL and opens PDFs in a
  new tab. It never constructs a Telegram file URL.
- `backend/app/src/main/kotlin/com/hookah/platform/backend/miniapp/venue/VenueGuestPreviewRoutes.kt`
  reuses the exact Guest read model. Published Preview therefore uses the same platform media URLs
  and Guest availability rules.
- `backend/app/src/main/kotlin/com/hookah/platform/backend/miniapp/venue/VenueGuestPreviewRoutes.kt`
  serves saved private-preview media through authenticated `/api/venue/{venueId}/guest-preview/`
  routes. Owner/Manager access, venue/section/media ownership and child visibility are rechecked;
  responses use no-store. No raw refs or public Guest access to unpublished media are granted.

### Infrastructure, Configuration And Operations

- `docker-compose.yml` defines one persistent named volume, `pgdata`, mounted only into PostgreSQL.
  The backend has no persistent media volume.
- `backend/Dockerfile` runs the runtime image as `appuser` and contains only application and built
  Mini App files. Recreating the backend container cannot be treated as durable media storage.
- `docs/STAGING_DEPLOYMENT.md` documents a manual PostgreSQL `pg_dump`/`pg_restore` procedure and
  explicitly leaves backup storage and retention as operational decisions. It does not back up
  arbitrary backend filesystem bytes.
- `docs/DEPLOYMENT_RUNBOOK.md` says exact production backup/restore and rollback commands still need
  verification.
- Staff-photo configuration now exists in `application.conf` with server-only environment variables;
  the SDK dependencies are declared in the Gradle catalog. Existing Compose env_file forwards them.
  General info-section/menu storage configuration remains future; no credentials are committed.
- Staff photos have a raw bounded upload route and guarded byte retrieval. General info-section
  HTTP upload remains absent; Telegram multipart is outbound Bot transport.
- Staff photos have checksum/content validation and durable cleanup. These do not implement general
  media cleanup, antivirus/PDF scanning or backup recovery for other media domains.
- The generic `audit_log` and `AuditLogRepository` exist, but current info-section upload,
  section-visibility and attachment deletion do not append media audit events.

### Current Flow Map

```text
OWNER/MANAGER Telegram message
    -> Telegram hosts bytes and returns bot-specific file_id
    -> venue_info_section_media stores metadata pointer
       -> Guest / Published Preview safe DTO
          -> public guarded backend route
             -> Telegram getFile + temporary bot-token URL
                -> backend response bytes
       -> Guest Bot sendPhoto/sendDocument(file_id)
       -> Draft Preview: authenticated private media proxy, no raw ref/public Guest URL
```

### Current Source Of Truth By Surface

| Surface | Platform metadata/link truth | Byte/delivery truth | Current status |
| --- | --- | --- | --- |
| Public-card info-section image/PDF | `venue_info_sections` + `venue_info_section_media` | Telegram bytes addressed by `telegram_file_id`; Guest proxy downloads on demand | `PARTIAL / BOT-FIRST` |
| Flat Photo/PDF menu | Same info-section tables with `section_type=menu` | Same Telegram/proxy path; Guest view-only | `PARTIAL / BOT-FIRST` |
| Published Preview | Exact Guest info-section DTO; no separate rows | Same guarded Guest proxy | `DONE / MVP / STAGING-SMOKE-PASSED`, read-only |
| Draft Preview | Saved visible sections and safe private media URLs | Authenticated Owner/Manager proxy; no raw refs/public Guest URL | Existing runtime; UX08 adds no deployment evidence |
| Structured menu item | `menu_items` has legacy description column, but active read/write DTOs omit description/photo/thumbnail | No runtime byte source or delivery | `MISSING / FUTURE` |
| Legacy category image seed | `menu_category_images.image_url` for named test venues | External placeholder URL when seeded; no active Guest/Venue consumer | Test/seed-oriented, not item media |
| Promotion banner | Separate `venue_promotion_media` row | Telegram `file_id`, Bot-oriented | Adjacent existing model; out of first slice |
| Staff profile photo | Photo ledger + revision/current/candidate state; legacy photo_ref is not authority | Private R2 adapter + guarded backend bytes | Local implementation; live R2 unverified |

## Current Architecture Answers

| Question | Evidence-backed answer |
| --- | --- |
| 1. Source of truth by surface | Public info-section image/PDF and flat Photo/PDF menu: `venue_info_sections` + `venue_info_section_media` are platform metadata/link truth; Telegram addressed by `telegram_file_id` is byte truth. Structured menu item media: no runtime source exists. Staff photo: dedicated ledger/pointers and private R2 contract above. Promotion banner: separate `venue_promotion_media`, outside this slice. |
| 2. Does the platform store bytes? | Staff photo runtime targets private R2. Current venue info media stores only Telegram `file_id`; delivery downloads bytes on demand into process memory. |
| 3. Can backend obtain bytes after upload? | Yes, while the same bot token can resolve the bot-specific `file_id` and Telegram accepts `getFile`. `TelegramApiClient.downloadFile` already does this. The hosted Bot API currently documents a 20 MB download limit. |
| 4. What if the Telegram message/file is deleted? | Platform code stores no `chat_id`/`message_id`, never calls Telegram message deletion for attachment removal and cannot correlate the original message afterward. Deleting a chat message does not mutate the DB row. Telegram documents `file_id` as persistent, so message deletion is not a platform deletion mechanism; nevertheless the platform cannot provide its own byte-level durability/restore guarantee and remains bound to that bot token and Telegram service. |
| 5. Persistent filesystem volume? | Staging Compose has persistent `pgdata` only. There is no backend/media volume. Production filesystem persistence is not evidenced in the repo/runbook. |
| 6. Backup for such a volume? | No media-volume backup exists. Only a manual PostgreSQL dump/restore procedure is documented for staging; its off-host storage/retention is still open. |
| 7. Object-storage abstraction/dependency? | StaffPhotoStorage uses AWS SDK v2 for the bounded staff-photo slice. The separate Python AP-00 DR S3 transport is not a media adapter or evidence of deployed R2. |
| 8. Public delivery URLs? | Current Guest/Published path is `/api/guest/venue/{venueId}/info-sections/{sectionId}/media/{mediaId}` on the backend origin. Guest Bot uses Telegram send methods with `file_id`; no Telegram raw URL is given to Guest. Draft Preview uses authenticated `/api/venue/{venueId}/guest-preview/info-sections/{sectionId}/media/{mediaId}` URLs. |
| 9. Identifiers that can leak | Public DTOs expose numeric venue/section/media database ids and an enumerable route, though availability/ownership/visibility guards constrain delivery. Raw `telegram_file_id`, Telegram temporary path and bot token stay server-side in this flow. The private staff DTO retains legacy untyped `photo_ref`, but `GuestTodayStaffResolver` explicitly returns `photoRef=null` and Guest uses guarded photoUrl or initials. Neither this legacy field nor `menu_category_images.image_url` should be reused as a public storage reference. |
| 10. Real deletion semantics | Attachment delete hard-deletes only the DB row. Section/venue cascade can also remove rows. No Telegram bytes are physically deleted, no tombstone/retention/audit exists and no orphan cleanup runs. Section visibility is reversible; attachment visibility is not. |

## Storage Options Comparison

| Criterion | A. Telegram `file_id` primary | B. Persistent server filesystem | C. S3-compatible object storage | D. Hybrid migration |
| --- | --- | --- | --- | --- |
| Current Bot compatibility | Native; existing Bot already reads/writes `file_id`. Mini App upload needs backend multipart -> Telegram technical chat -> returned `file_id`. | Bot must receive bytes or a guarded URL; legacy `file_id` needs a branch. | Bot must receive bytes or a guarded URL; legacy `file_id` needs a branch. | Best compatibility: legacy rows stay `TELEGRAM_FILE`, new rows use the selected target and one delivery adapter. |
| Implementation complexity | Medium: technical chat config/permissions, image/PDF multipart send, response parsing and compensation. | Medium: volume, atomic writes, safe paths, quotas, delivery and cleanup. | Medium/high: client, private bucket config, retries, idempotency and secrets. | Medium/high initially; D is a migration/model choice and still requires A, B or C for new bytes. |
| Security | Refs can stay private, but bot token becomes media credential and physical deletion is not controlled. | Strong private control if guarded; path traversal, mount permission and host compromise are platform risks. | Private bucket plus guarded/signed delivery is strong if IAM, TLS, rotation and expiry are correct. | Same as target; legacy Telegram limitations remain explicit. |
| Operational burden | Low infrastructure, high external dependency and token continuity risk. | High on one VPS: disk/inode alerts, mount ownership, off-host backup and restore. | Credentials, lifecycle, monitoring, versioning and cost controls; not tied to one app host. | Two read adapters/deletion behaviors, but no immediate backfill. |
| Backup/restore | DB restores refs only; Telegram bytes are outside platform backup. | Must add consistent off-host byte backup and prove restore; none exists. | Versioning/replication/backup and RPO/RTO are selectable but must be funded; a bucket is not automatically a backup. | Legacy uses Telegram durability; new assets use target policy; metadata stays PostgreSQL. |
| Deletion | Platform removes only its reference and cannot prove physical erasure. | Controlled grace/delete is possible. | Controlled delete/lifecycle is possible, subject to retention/versioning. | Logical delete is uniform; physical result is source-specific and honest. |
| Cost | No new vendor invoice evidenced; Telegram limits/dependency remain. | VPS disk, off-host backup capacity and operator time. | New storage/egress/request/backup cost; no provider/budget approved. | Target cost plus dual-adapter maintenance; no mandatory legacy backfill cost. |
| Portability | Poor; bot-specific `file_id` couples bytes to Telegram. | Medium; files are standard but tied to host/volume/backup layout. | Good when using a small common S3-compatible operation set; egress/provider behavior still varies. | Best model portability because source type and public contract are storage-neutral. |
| Migration/backfill | No legacy migration; Mini App creates more Telegram rows. | Legacy can remain Telegram or needs later backfill. | Legacy can remain Telegram or needs later backfill. | No first-slice byte backfill; optional migration later. |
| One Bot/Mini App truth | Yes only if both write the same asset row and technical chat is never the list. | Yes through one asset row and adapter. | Yes through one asset row and adapter. | Yes by design: one ledger, private source adapters and one DTO. |

## Recommendation And Required Decision

This section applies only to future info-section/menu uploads. The authorized staff-photo R2
slice above is excluded from this provider decision gate.

Recommended architecture, pending approval:

**D. Hybrid migration with C. private S3-compatible object storage for new Mini App uploads.**

It preserves working Telegram media without an all-at-once byte backfill, gives new uploads a
platform-controlled lifecycle, avoids tying new browser/non-Telegram surfaces to a bot token and
avoids the current one-VPS media-volume/backup gap. `TELEGRAM_FILE` rows may remain readable
indefinitely. No concrete provider is selected by this spec.

Option A is the valid lowest-new-cost alternative only if product/operations accepts Telegram and
bot-token lock-in, metadata-only backup, reference-only deletion, hosted Bot API limits and a
technical storage chat as production infrastructure.

Option B is not acceptable with the current deployment baseline. It becomes eligible only after a
dedicated media volume, off-host backup, retention, restore drill, disk monitoring and production
owner are approved. The container root filesystem is never eligible.

Choose exactly one:

1. **Hybrid + private S3-compatible target (recommended).** Approve recurring
   storage/request/egress/backup cost, provider-neutral config, versioning/retention, RPO/RTO and
   operations owner. Select the actual provider separately.
2. **Telegram technical storage chat.** Approve Telegram as durable byte dependency,
   bot-token lock-in, metadata-only backup and reference-only deletion. Provide private chat/channel
   id and ownership/permission/recovery procedure.
3. **Hybrid + persistent filesystem.** Approve only with a named media volume, off-host backup and
   tested restore, disk monitoring/capacity policy and accepted single-host risk. Not recommended.

The decision record must include storage owner, staging/production separation, credentials owner,
RPO, RTO, retention/versioning, physical deletion behavior, monthly budget boundary and incident
contact. Until then the verdict remains `STOP_FOR_MEDIA_STORAGE_DECISION`.

## Target Source-Of-Truth Model

### Extend The Existing Table

For the first slice, do not create a second generic `media_assets` table. Evolve
`venue_info_section_media` in place. Each row is the asset record and its only live owner is one
info section.

This is sufficient because:

- the first slice has one actual media surface and an existing canonical link table;
- `venue_id` is unambiguously derived by `venue_info_section_media.section_id ->
  venue_info_sections.venue_id`;
- duplicating `venue_id` in the media row would create drift unless a composite FK is added;
- generic cross-surface sharing is not required and would complicate reference-safe deletion;
- structured menu media, promotion media and staff photos have different lifecycle rules and must
  not be prematurely collapsed into this slice.

Reconsider a separate common asset/link model only when a second approved runtime surface must
share the same physical asset. If that happens, migrate this ledger rather than creating a parallel
list.

### Logical Asset Fields

| Field | Contract and migration |
| --- | --- |
| `assetId` | Existing numeric `id`; internal management/audit identity, never a storage ref. |
| `publicId` | New random, non-sequential 128-bit identifier, unique and immutable. The only public delivery identifier. |
| `venueId` | Required logical field derived through the owned section join; every repository mutation includes and verifies it. Never trust client `sectionId` alone. |
| `sectionId` | Existing required FK and sole first-slice owner/link. |
| `surface` | Logical constant `INFO_SECTION`; section type distinguishes `menu`, default and custom sections. No redundant column is needed. |
| `mediaType` | Existing `image`/`pdf`, normalized in domain code to `IMAGE`/`PDF`. |
| `sourceType` | `TELEGRAM_FILE`, `OBJECT_STORAGE`, or `FILESYSTEM` only if approved. Legacy backfill is `TELEGRAM_FILE`. |
| `sourceRef` | Private non-blank Telegram `file_id`, object key or relative media-volume key. Rename/migrate `telegram_file_id`; never return or log it. |
| `originalFilename` | Sanitized display metadata, max 160 Unicode characters. Nullable only for legacy unknowns and never used as key/path identity. |
| `mimeType` | Server-sniffed normalized MIME. Nullable for legacy; required for every new `READY` asset. |
| `sizeBytes` | Exact validated byte size. Nullable for legacy; required for new `READY`. |
| `width` / `height` | Server-decoded dimensions. Null for PDF/unknown legacy; required for new image `READY`. |
| `checksumSha256` | Lowercase SHA-256 of validated bytes. Nullable for legacy; required for new `READY`. |
| `status` | `UPLOADING`, `READY`, `FAILED`, `HIDDEN`, `DELETED`. Legacy backfill is `READY`. |
| `sortOrder` | Existing ordering; replacement inherits the replaced row position. |
| `createdByUserId` / `updatedByUserId` | Required for new mutations; nullable only where a legacy actor cannot be reconstructed. |
| `replacesAssetId` | Nullable self-reference on the new asset created by replace; preserves lineage without overwriting working bytes. |
| timestamps | Keep `created_at`; add `updated_at`, `deleted_at`, `storage_deleted_at`. The latter proves physical cleanup where supported. |

Venue management DTO may contain safe filename, MIME, size, dimensions, status and timestamps.
Guest DTO contains only `publicId`, media type, dimensions when useful, sort order and guarded
delivery URL. It returns only effectively visible `READY` assets.

### Safe Delivery Contract

Target public URL:

`GET /api/guest/media/{publicId}`

The route resolves the asset server-side and rechecks:

- asset exists and status is `READY`;
- owning section is visible;
- asset belongs to that section and venue;
- exact Guest venue lifecycle/subscription availability;
- media type is supported.

One storage-neutral delivery service then:

- downloads `TELEGRAM_FILE` through Telegram;
- streams `OBJECT_STORAGE` from the private object client;
- reads `FILESYSTEM` only from a configured media-volume root if approved.

Do not redirect to raw Telegram URLs or expose permanent bucket/object URLs. Short-lived signed
object URLs may be considered later only if visibility/lifecycle revocation remains exact; the first
slice should prefer the guarded backend URL for parity with the working Guest path.

Responses use stored normalized MIME, `X-Content-Type-Options: nosniff`, a safe generated
`Content-Disposition` filename and bounded streaming/back-pressure. PDFs are opened only after an
explicit Guest action and are never embedded in the main page. No provider header, source ref or
secret may reach the response, redirect, error or log.

The old numeric route may remain as a temporary compatibility adapter during rollout, but new DTOs
use `publicId`. It must call the same delivery service and keep the same guards.

## Asset Lifecycle

### Upload

1. Venue Mini App sends one authenticated multipart request with one file and target section.
2. Backend validates session, OWNER/MANAGER role, own venue, lifecycle mutability, section
   ownership, surface policy, rate limit and attachment count before storage.
3. Backend creates `UPLOADING` metadata with opaque `publicId`, actor and no delivery URL.
4. Backend reads a bounded stream, computes SHA-256, sniffs magic MIME, parses image dimensions or
   PDF structure and enforces limits.
5. Backend writes through the selected adapter under a random private reference. Filename never
   controls a path/key.
6. One transaction records private `sourceRef`, normalized metadata and `READY`, then writes safe
   audit evidence. Only then does the management API return a safe DTO.
7. Failure marks the row `FAILED`, removes partial bytes best-effort and returns a stable safe error
   code without storage metadata.

Temporary scratch space, if a validator cannot stream, is private, strictly bounded and deleted in
the same request/failure path. It is never durable storage.

### Visibility, Replace And Delete

An attachment is guest-visible only when section `is_visible=true`, asset status is `READY` and the
venue passes Guest lifecycle/subscription guards. `HIDDEN` is reversible per attachment. Whole
section hide/show remains and is distinct from attachment visibility. Every surface uses this same
effective rule.

Replace is non-destructive until new bytes are ready:

1. upload/validate a new `UPLOADING` row with `replacesAssetId`;
2. store bytes and make the new row ready;
3. atomically give it the old sort position and mark the old row `DELETED`;
4. append one safe replacement audit;
5. enqueue old physical bytes after retention.

If a pre-swap step fails, the old asset remains visible and unchanged.

Deletion contract:

- setting `DELETED` immediately removes the asset from Guest, Published Preview and Bot; do not
  hard-delete its tombstone/audit identity;
- object storage or approved filesystem bytes are physically deleted after a 7-day recovery window;
  success sets `storage_deleted_at`, failures retry with bounded backoff;
- failed partial objects are removed immediately where possible and by a sweep within 24 hours;
- a daily reconciler deletes uncommitted objects only when older than 24 hours and no row references
  them;
- `TELEGRAM_FILE` delete removes only the platform reference; physical Telegram erasure is
  unsupported and must be reported honestly;
- first MVP does not share/deduplicate physical objects across rows. SHA-256 may make an identical
  retry idempotent inside the same section, but no cross-section/venue physical dedup exists;
- any future shared source is physically deleted only after proving no non-deleted asset references
  it.

## Security And Validation Contract

### Authorization

- OWNER and MANAGER may list/upload/replace/hide/show/delete only inside their own venue.
- STAFF is denied by direct API regardless of hidden UI.
- Guest and Platform-only access do not grant venue media management.
- Foreign venue, foreign section and mismatched asset/section ids are denied before storage access.
- Every mutation rechecks current membership and venue/section/asset ownership server-side.
- Existing Venue settings lifecycle policy applies; missing/DELETED/unsupported states fail closed.
  Upload never publishes a venue or bypasses Draft policy.
- Bearer Mini App session authentication is required. No query credentials, public forms or
  cookie-only trust.

### Formats And Limits

| Kind | Allowed MIME | Limit | Notes |
| --- | --- | --- | --- |
| Image | `image/jpeg`, `image/png` | 10 MiB | Maximum 8192 px per side and 32 megapixels; dimensions decoded before `READY`. |
| PDF | `application/pdf` | 10 MiB | Allowed only for public `INFO_SECTION`, including the flat Photo/PDF menu. |

Additional rules:

- Server magic-byte sniffing and conservative parse must agree with allowed normalized MIME.
  Browser `Content-Type`, extension and Telegram metadata are hints only.
- WebP is excluded from first MVP because the current JVM dependencies do not evidence a safe
  server-side WebP decoder/dimension validator. Add only with explicit dependency and malformed-file
  tests.
- SVG, executables, scripts, HTML, archives, office files, audio, video and other documents are
  denied.
- Maximum 10 non-deleted attachments per section. Legacy sections above the limit remain
  readable/manageable; no new add until below the limit.
- Reject when either image side or pixel count exceeds its limit even if compressed bytes are small.
- Sanitize filename by removing path segments, NUL/control/bidi-control characters and leading
  dots, normalizing Unicode and limiting to 160 characters. Storage keys stay random.
- Compute SHA-256 while reading; no global/cross-tenant deduplication.
- Stable upload idempotency prevents retry-created duplicate rows/objects.

These are canonical first-slice defaults. Raising them requires security/performance review,
especially while legacy delivery depends on Telegram's hosted Bot API.

### Rate Limits, Malware And Audit

- maximum 5 upload starts per actor + venue per minute;
- maximum 2 simultaneous in-flight uploads per actor;
- byte/dimension limits are enforced while streaming;
- repeated validation failures are safely rate-limited/logged without filenames, bytes or refs;
- a storage-cost quota is a later operations/product decision, not client logic.

No antivirus/PDF scanner exists. It is not a first-MVP release blocker only while upload is limited
to authenticated own-venue OWNER/MANAGER, formats remain JPEG/PNG/PDF, strict content/size parsing
passes, PDF is never auto-embedded, delivery uses `nosniff` and safe filename, and the limitation is
disclosed: structural validation does not prove a PDF malware-free. Scanning becomes mandatory
before Guest/Staff upload, arbitrary documents, automatic PDF embedding/processing or an enterprise
policy that requires it. A scanner can later gate `UPLOADING -> READY` without changing the model.

Use existing `audit_log` for upload started/ready/failed, replace, attachment hide/show, section
hide/show through this surface, logical delete and terminal cleanup failure/success. Audit contains
actor, venue, section, safe asset/public id, source type, normalized MIME, size, old/new status and
timestamps. It excludes source ref, raw filename/bytes, Telegram/provider response, secrets, raw
initData and unrelated PII.

## Bot / Mini App Bridge

One repository/service returns the ordered effective asset list to every surface.

For `TELEGRAM_FILE`:

- Bot sends the private `file_id`;
- Guest and Published Preview use the guarded platform route;
- Venue Mini App manages the same row through safe DTOs.

For `OBJECT_STORAGE` or approved `FILESYSTEM`:

- Guest and Published Preview use the same guarded route;
- Bot loads validated bytes through the adapter and sends Telegram multipart `sendPhoto` or
  `sendDocument`;
- any Telegram `file_id` returned from delivery is at most a replaceable delivery cache, never the
  canonical source ref or a second attachment row/list.

Bot upload continues to create `TELEGRAM_FILE` rows in the same table. It adopts the same attachment
cap, ownership checks, status, audit and Guest DTO. Because the current Bot trusts Telegram
metadata, target Bot upload must download/sniff/validate bytes before `READY`; invalid media is not
shown to Guest.

Mini App replace/hide/show/delete immediately changes Bot output because Bot reads the same ledger.
Bot mutations call the same lifecycle service.

## Legacy Migration And Backward Compatibility

No physical media backfill is a prerequisite for the first slice.

Required metadata migration:

1. evolve matching PostgreSQL and H2 `venue_info_section_media`;
2. preserve ids, section links, sort order and creation time;
3. rename/migrate `telegram_file_id` to private storage-neutral `source_ref`;
4. backfill `source_type=TELEGRAM_FILE`, `status=READY` and unique opaque `public_id`;
5. leave filename, sniffed MIME, size, dimensions, checksum and actor nullable for legacy unknowns;
6. require complete metadata/actor for every new `READY` row in service/domain validation;
7. cut all reads over in one rollout; no dual-write column or parallel attachment table.

Legacy `TELEGRAM_FILE` may remain indefinitely. Lazy metadata enrichment may inspect a legacy file,
but delivery must not fail solely because old optional metadata is null.

Physical backfill to the selected target is a later optional operations task. It first reports
resolvable/unresolvable refs, total bytes, checksums, cost, rate limits and rollback. It keeps the
same row/public id and changes source only after target bytes verify.

Replacing legacy media creates a new selected-target row at the same sort position and retires the
old Telegram row. No old Telegram bytes must be copied.

## First Bounded Runtime Slice

### Venue Mini App UX

Add a block under `Настройки -> Публичная карточка`:

- OWNER/MANAGER sees existing info sections and ordered attachments;
- picker accepts one JPEG, PNG or PDF at a time;
- helper copy states limits and that Photo/PDF menu is view-only;
- upload shows progress, success and safe Russian error copy;
- attachment actions: replace, hide/show, delete with confirmation;
- whole-section hide/show remains visibly distinct;
- no section text/custom-section authoring, drag reorder or bulk upload;
- STAFF has no entry and direct API is forbidden.

Upload may be allowed for saved Draft under existing settings lifecycle policy. Preserve current
authenticated private Preview delivery; do not grant public Guest access to unpublished media.

### API And Delivery

Illustrative route family; local naming may vary but contracts are mandatory:

- safe management list under `/api/venue/{venueId}/info-sections/...`;
- authenticated one-file multipart upload scoped to `{venueId}/{sectionId}`;
- replace, attachment visibility and delete scoped to `{venueId}/{sectionId}/{assetId}`;
- section visibility scoped to `{venueId}/{sectionId}`;
- storage-neutral `GET /api/guest/media/{publicId}`.

Required acceptance:

- Guest pre-QR/card shows only visible `READY` assets through safe URLs;
- Published Preview remains exact Guest parity with no bypass;
- Draft keeps authenticated private media delivery, without raw source refs or public Guest URLs;
- Guest Bot displays legacy and new-source media;
- Bot and Mini App mutate one ordered ledger;
- Staff, foreign venue and unsupported lifecycle direct requests are denied;
- legacy Telegram media remains readable throughout rollout.

## Likely Runtime Files

Backend/schema:

- next matching PostgreSQL and H2 Flyway migrations;
- `backend/app/src/main/kotlin/com/hookah/platform/backend/telegram/db/VenueInfoSectionMediaRepository.kt`;
- `backend/app/src/main/kotlin/com/hookah/platform/backend/telegram/db/VenueInfoSectionsRepository.kt`;
- focused storage-neutral media service/adapters and Venue media routes;
- `backend/app/src/main/kotlin/com/hookah/platform/backend/Application.kt`;
- `backend/app/src/main/kotlin/com/hookah/platform/backend/miniapp/guest/GuestVenueRoutes.kt`;
- `backend/app/src/main/kotlin/com/hookah/platform/backend/miniapp/guest/GuestVenueReadService.kt`;
- `backend/app/src/main/kotlin/com/hookah/platform/backend/miniapp/guest/api/GuestVenueDtos.kt`;
- `backend/app/src/main/kotlin/com/hookah/platform/backend/miniapp/venue/VenueGuestPreviewRoutes.kt`
  only if wiring requires it; Published reuses Guest and private Preview keeps authenticated delivery;
- `backend/app/src/main/kotlin/com/hookah/platform/backend/telegram/TelegramBotRouter.kt`;
- `backend/app/src/main/kotlin/com/hookah/platform/backend/telegram/TelegramApiClient.kt`;
- existing `AuditLogRepository` usage, not a second audit store;
- `application.conf`, `.env.example`, `docs/env/staging.env.example`;
- `docker-compose.yml` only if the approved option requires it;
- Gradle dependencies only for the approved client and safe parser/sniffer.

Mini App:

- `miniapp/src/screens/venueSettings.ts`;
- `miniapp/src/shared/api/venueApi.ts`;
- `miniapp/src/shared/api/venueDtos.ts`;
- `miniapp/src/shared/api/guestDtos.ts` if public DTO changes;
- `miniapp/src/screens/guestVenue.ts`;
- `miniapp/src/screens/venueGuestPreview.ts` only for parity-safe wiring;
- `miniapp/src/style.css`;
- `miniapp/e2e/guest-smoke.spec.ts`.

This is a plan, not authorization to implement before the storage decision.

## Required Automated Tests

### Backend

- PostgreSQL/H2 migration preserves legacy rows and backfills source/status/public id.
- Repository/service enforces venue -> section -> asset ownership on every operation.
- OWNER/MANAGER own-venue success; STAFF, Guest, Platform-only and foreign denial.
- Valid JPEG/PNG/PDF plus content-type/extension spoof, truncated/malformed file rejection.
- Image dimensions/pixels/size boundaries; WebP/SVG/archive/executable denial.
- PDF only for `INFO_SECTION`; attachment cap, rate and in-flight limits, idempotent retry.
- Filename sanitation, random refs and no source refs in DTO/error/log/audit.
- All lifecycle transitions; atomic replace leaves old media working on failure.
- Delete hides immediately, respects retention and protects referenced sources.
- Legacy Telegram and selected-target delivery; unavailable source fails safely.
- Guest lifecycle/subscription, section/attachment visibility and mismatch guards.
- Published exact Guest parity; Draft authenticated private media and no raw refs/public Guest URL.
- Bot handles Telegram and selected-target assets from one ledger.
- Audit safe fields; cleanup retry/orphan age guards and ref-free metrics.

Likely focused classes include new Venue media route/service/storage tests plus current
`GuestVenueRoutesTest`, `VenueGuestPreviewRoutesTest`, `VenueDraftPreviewRoutesTest`,
`VenueRbacRoutesTest`, Telegram Bot router tests and migration sanity tests.

### Mini App / E2E

- OWNER/MANAGER sees the block; STAFF/direct STAFF hash does not.
- picker allowlist/helper copy matches policy.
- authenticated multipart uses exact venue/section and shows progress.
- validation/rate/cap/network/storage errors preserve prior list with human copy.
- replace is non-destructive until success.
- attachment and section hide/show are distinct.
- delete confirms and removes only after server success.
- venue switch aborts stale upload/list requests and cannot cross venues.
- Guest/Published parity after upload/hide/replace/delete.
- Draft media requires authenticated private access and exposes no source ref/public Guest URL.
- no raw `file_id`, object key/path or provider error in DOM, URL or fixtures.

## Staging Smoke And Operations Gate

The future runtime slice requires green Actions, then:

1. health, DB health and Mini App static checks;
2. OWNER image upload; MANAGER PDF upload; STAFF/foreign direct denial;
3. Guest image and explicit PDF open;
4. Published exact Guest parity;
5. Draft authenticated private delivery and Staff/foreign/public Guest denials;
6. Bot display of one legacy Telegram and one new-source asset;
7. replace without visible gap;
8. attachment/section hide/show;
9. delete visibility removal and source-appropriate cleanup evidence;
10. safe audit and no refs/secrets in browser/log sample;
11. backend container recreate proving storage durability;
12. metadata and byte-store backup/restore evidence for approved RPO/RTO.

Operations requirements:

- Telegram: technical chat ownership, bot access recovery, token rotation impact, size limit, outage
  behavior and no-physical-erasure statement.
- Filesystem: named volume, `appuser` permissions, capacity/inode alerts, off-host backup, retention,
  restore drill and DB/bytes consistency.
- Object storage: private and separated local/staging/production config, least-privilege credentials,
  rotation, TLS, versioning/retention, lifecycle, cost alerts, RPO/RTO and restore drill.

UX08 E and A–D runtime changes require separately authorized staging and real Telegram acceptance;
local tests do not provide that acceptance. Staff-photo prerequisites are listed above.

## Outcome-First Handoff After Decision

> Implement only Venue Public Card / Info-Section Media Upload in Venue Mini App using
> `docs/MEDIA_STORAGE_UPLOAD.md`. Evolve `venue_info_section_media` as the single asset ledger,
> preserve legacy Telegram rows, use the approved private adapter for new uploads, enforce
> OWNER/MANAGER own-venue RBAC and strict JPEG/PNG/PDF lifecycle/audit rules, keep Guest and
> Published Preview on one safe delivery path, keep Draft source-ref-free with authenticated private delivery and preserve Bot compatibility.
> Do not add structured menu media, staff photos, video, bulk upload, CDN optimization or a second
> media source of truth.
