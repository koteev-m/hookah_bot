# Menu / Options / Stop-List Model

Дата актуализации: 2026-08-12.

Статус: **current product reference / SPEC UPDATED**. Guest stale-menu cart recovery is **GUEST CART STALE MENU SELECTION RECOVERY / REMOVED OR UNAVAILABLE ITEMS AND OPTIONS / PAYLOAD-BOUND IDEMPOTENCY + ATOMIC REJECTION / DONE / MVP / STAGING-SMOKE-PASSED**; its **ITEM-LEVEL ACTION AND COPY POLISH / DONE / MVP / STAGING-SMOKE-PASSED**. Menu/options/flavors parity is documented as smoke-closed for the structured selected-option flow. The bounded shift-check slice is **MENU SHIFT CHECK PHASE 1 / DONE / MVP / STAGING-SMOKE-PASSED**; menu item, empty-category and option hard-delete audits are release-closed bounded MVPs. Menu option hard delete includes atomic Telegram base-profile normalization and is **DONE / MVP / STAGING-SMOKE-PASSED**. Menu option rename audit is **DANGEROUS ACTION AUDIT SLICE / MENU OPTION RENAME AUDIT / DONE / MVP / STAGING-SMOKE-PASSED**. Menu option price audit is **DANGEROUS ACTION AUDIT SLICE / MENU OPTION PRICE AUDIT / DONE / MVP / STAGING-SMOKE-PASSED**. Menu option availability audit is **DANGEROUS ACTION AUDIT SLICE / MENU OPTION AVAILABILITY AUDIT / DONE / MVP / STAGING-SMOKE-PASSED**. Menu item availability audit is **DANGEROUS ACTION AUDIT SLICE / MENU ITEM AVAILABILITY AUDIT / DONE / MVP / STAGING-SMOKE-PASSED**. Menu option create audit is **DANGEROUS ACTION AUDIT SLICE / MENU OPTION CREATE AUDIT / DONE / MVP / STAGING-SMOKE-PASSED**. Menu item create audit is **DANGEROUS ACTION AUDIT SLICE / MENU ITEM CREATE AUDIT / MVP IMPLEMENTED / LOCAL VALIDATION PASSED / REVIEW REQUIRED BEFORE COMMIT**. The broader menu constructor, media/top-list governance, remaining audit coverage and permission parity remain **PARTIAL** unless a specific implementation task proves them.

## Core Rule

The structured menu is the source of truth for orders. PDF/photo menu is view-only. Guest order preview and submit must validate item availability, option validity and prices server-side; the cart must never trust client-side prices or stale option state.

Info-section/Photo-PDF-menu storage, upload, delivery and Bot/Mini App compatibility are canonical
in `docs/MEDIA_STORAGE_UPLOAD.md`. Structured menu-item and option/flavor media remain outside its
first slice.

Menu permissions are governed by `docs/SECURITY_RBAC_MATRIX.md`; Venue Mode operational surfaces are governed by `docs/VENUE_OPERATIONS.md`; order/session/tab and snapshot rules are governed by `docs/ORDER_SESSION_TAB_CORE.md`; Telegram callback/staff-chat rules are governed by `docs/TELEGRAM_FALLBACK_STAFF_CHAT.md`; analytics/audit event names are governed by `docs/ANALYTICS_EVENTS.md`; validation strategy is governed by `docs/TESTING_QA_SMOKE_STRATEGY.md`; release/deploy operations are governed by `docs/DEPLOYMENT_RUNBOOK.md`.

## Terms

| Term | Product meaning | Target fields / notes |
| --- | --- | --- |
| `MENU_CATEGORY` | Category inside one venue structured menu. | `id`, `venue_id`, `name`, `description`, `sort_order`, `is_visible`, `archived_at nullable`. |
| `MENU_ITEM` | Orderable structured item. | `id`, `venue_id`, `category_id`, `name`, `description`, `base_price`, `currency`, `photo/media refs`, `is_visible`, `is_available`, `is_featured`, `sort_order`, `archived_at nullable`. |
| `OPTION_GROUP` | Group of modifiers for one item: `Вкусы`, `Крепость`, `Чаша`, `Добавки`, `Лёд`, `Сироп`. | `id`, `item_id`, `name`, `selection_type single|multi`, `is_required`, `min_select`, `max_select`, `sort_order`, `is_visible`. |
| `OPTION_VALUE` | Selectable option value such as `Манго`, `Мята`, `Крепко`, `На фрукте`. | `id`, `group_id`, `label`, `price_delta`, `is_available`, `sort_order`, `archived_at nullable`. |
| `MENU_MEDIA` | Photos, PDF, album, cover image. | Item photos can be guest-visible. PDF/photo menu remains display-only and cannot be ordered from directly. |
| `STOP_LIST` | Temporary operational unavailability for item or option value. | Not deletion, not archive, not price change. |
| `SHIFT_CHECK` | Pre-shift availability review. | Mass confirm availability, see disabled items/options, restore categories, write shift-check evidence where implemented. |
| `FEATURED` / `TOP_LIST` | Manual venue pin/showcase for items. | Not paid placement unless Platform placement/billing/moderation exists separately. |

## Current Implementation Vs Target

| Area | Current implementation from docs | Target product model | Gap / risk / future note |
| --- | --- | --- | --- |
| Category CRUD | Venue menu routes and Mini App menu basics exist; full parity needs verification. | Owner manages category create/edit/reorder/archive; Manager only if product explicitly grants structure management. | Menu constructor implementation remains `PARTIAL`. |
| Item CRUD | Structured menu items, prices and availability exist; Owner/Manager item editing is documented, Staff denied for structure. Existing Mini App and Telegram item hard delete writes one transaction-bound safe audit; item create is locally implemented with one audit per committed row. | Owner manages item create/edit/reorder/archive, price, visibility, featured and media. | Item hard-delete audit is `DONE / MVP / STAGING-SMOKE-PASSED`; item-create audit awaits review/CI/staging, while price/name/type/category/description/currency audit families stay open. |
| Item availability | Owner/Manager/Staff direct Mini App and Telegram changes write one transaction-bound safe audit per real delta; Owner/Manager compound PATCH audits only its availability delta. | Availability toggle is operational state, fast and reversible, with safe actor/source evidence. | Release-closed only for this bounded audit contract. Per-venue `staff_stoplist_enabled` stays future. |
| Option group/value support | Guest/Menu Options & Flavors parity is smoke-closed: item-scoped options/flavors, base profiles and selected-option submit exist. Atomic Telegram base-profile normalization and application-level locked canonical-profile collision checks are release-closed for their bounded contract. | General `OPTION_GROUP`/`OPTION_VALUE` model supports required single/multi modifiers with min/max validation. | Broader non-hookah modifier UX, DB-level uniqueness and Mini App atomic bulk apply remain separate. |
| Option/value availability | Item option/flavor stop-list is documented and smoked. Preview/submit now identify own-cart removed/unavailable item or option lines with a typed server contract. | Guest sees only available choices or disabled copy by venue policy; stale preview/submit is rejected with actionable line recovery. | Keep option ownership, foreign-selection privacy, all-issues and stale availability tests in regression. |
| Guest menu DTO | Guest Bot and Guest Mini App expose option picker where configured. | DTO includes item visibility/availability, option groups, values, price deltas and human copy without leaking internal media/provider data. | Needs verification before broad modifier expansion. |
| Order item modifiers/options snapshot | Current docs say selected option id/name/price delta and line preference notes are preserved where implemented. | Snapshot item and selected option names/prices at submit time; later edits never rewrite old bills/history. | Keep cross-channel bill snapshots in regression; multi-option quantities/counts need future design if introduced. |
| Photos/descriptions | Current structured menu item repositories/DTOs/rendering do not expose item photos, thumbnails or descriptions. The DB has a legacy `menu_items.description` column, but current Bot/Venue/Guest menu paths do not read or write it. | Item photos can be shown in structured menu; descriptions are guest-safe. | Structured menu-item media/description is `MISSING / FUTURE`; do not infer support from working info-section media. |
| Featured/top-list | Product spec requires featured/top list; implementation evidence is partial. | Venue manually pins items; not paid placement. | Paid placement/boosting belongs to Growth/Platform, not menu featured. |
| PDF/media | `Фото-меню` exists as a flat info/media section and is separate from structured order menu. Bot OWNER/MANAGER can add image/PDF attachments, delete one and hide/show the whole section. | PDF/photo menu is view-only; no direct order unless item exists in structured menu. | Venue Mini App authoring/upload is missing; direct replace, per-attachment hide and optional subsections remain future. |
| Shift check | **DONE / MVP / STAGING-SMOKE-PASSED**: OWNER/MANAGER Venue Mini App uses saved menu state, readiness counts, search/filters, local draft, a separate mass-selection mode, confirmation summary and one atomic request. STAFF has no entry/direct permission. | Venue Mode keeps optimistic availability checks, one bounded batch, no-op completion evidence and recoverable stale-state handling. | Keep role/tenant, atomicity, stale-state, Guest availability and Telegram stop-list parity in regression; Telegram shift-check UI and a queryable history table are not part of Phase 1. |
| Audit logs | `MENU_SHIFT_CHECK_COMPLETED` is atomic for a successful batch. Hard-delete, released option mutation audits, item availability and option create are release-closed. Item create is locally validated as one transaction-bound `MENU_ITEM_CREATED` per physical insert. | Price changes, archive/delete, mass stop-list, media removal, option schema change and Staff stop-list toggles write safe audit. | Item create awaits review/CI/staging; item price/name/type/category/description/currency and other menu audit families remain open. The broader audit stays `PARTIAL`. |
| Telegram vs Mini App parity | Options/flavors parity is smoke-closed; some Telegram owner flows remain richer. | Required menu/stop-list operations are aligned across Bot and Mini App or documented as exceptions. | Keep cross-surface parity smoke for Staff stop-list and selected options. |
| Staff stop-list permissions | Current docs say STAFF has `MENU_AVAILABILITY_MANAGE` and can toggle item/option availability; STAFF cannot edit structure/prices/options schema. | Recommended MVP: Staff cannot change menu structure/prices; Staff stop-list works only when `staff_stoplist_enabled` or equivalent policy allows it, and is identical in Bot/Mini App. | Current global Staff stop-list permission is acceptable only if intentionally enabled and audited; per-venue toggle remains target/future. |

## Guest Menu Behavior

- Guest sees only published venue menu data and only visible structured categories/items according to venue policy.
- Venue availability display policy can be:
  - A. hide unavailable items/options;
  - B. show unavailable items/options disabled with `Нет в наличии`.
- Guest cannot order an item or option value that is unavailable, hidden, archived, foreign to the venue or no longer valid for the item.
- Guest cart must not trust client price, option price deltas or availability.
- On preview/submit, the backend verifies:
  - venue is guest-accessible;
  - subscription/lifecycle gates allow guest ordering;
  - `table_session_id` is active and belongs to the table/venue context;
  - item is visible and available;
  - selected option groups/values belong to the item/venue;
  - required options are selected;
  - `min_select` / `max_select` / single-vs-multi rules are valid;
  - item and option prices are snapshotted server-side.
- Deterministic own-cart failures use HTTP `409`, code `CART_MENU_SELECTION_UNAVAILABLE` and
  `details.issues[]` with the current stable client cart-line reference `cartLineRef`, requested
  `itemId`, nullable requested `optionId`,
  `selectionKind=ITEM|OPTION` and `reason=REMOVED|UNAVAILABLE`. The server collects all such issues
  in request order. `cartLineRef` is not yet an opaque identity. The client maps only this exact
  typed contract and never infers a reason from HTTP status or message text.
- Selected option names may be retained on the cart line. Item names shown in recovery copy come
  from the current mutable item cache and are not an immutable cart snapshot; generic copy is used
  when the cache has no name. Foreign venue/item/option ownership stays generic `INVALID_INPUT`.
- Item recovery is `Вернуться в меню` plus line-scoped removal because no safe in-place item
  replacement engine exists. Option recovery reuses the current option picker; removed/unavailable
  options are absent, and quantity/note survive a valid replacement.
- Suggested replacements are future/optional and must not silently substitute an item or option.
- Executable promotions use the same server-owned item/option availability and current-price
  validation at preview and submit. A selected option may affect the eligible line amount, but it
  cannot become an eligibility target until the rule schema and tests explicitly support it.
- An unavailable gift item or option is not substituted silently. A selectable reward requires an
  explicit guest choice from a server-provided allowlist before final submit.
- A gift allowlist entry identifies a reward menu item, not a `menu_item_option`. If a reward item
  requires an option/modifier that the gift contract cannot explicitly select and validate, that
  reward is ineligible; the backend must not insert it with a missing or stale required option.

## Order Snapshot Rules

Target order item snapshot:
- `order_batch_item.name_snapshot`
- `order_batch_item.base_price_snapshot`
- `order_batch_item.quantity`
- `order_batch_item.comment_snapshot` / line-level note snapshot where supported.

Target selected option snapshot:
- `option_group_name_snapshot`
- `option_value_label_snapshot`
- `price_delta_snapshot`
- selected quantity/count if multi-select modifiers need it.

Rules:
- Batch totals are calculated server-side.
- Price/name/option edits after checkout do not change existing order, bill or history snapshots.
- Archiving an item/option must not break old order history or bill display.
- Promotion-affected and gift lines preserve original price, option delta, promotion adjustment and
  final amount alongside the promotion rule/version snapshot.
- Growth `REPEAT_TEMPLATE` must revalidate current availability, current prices and current option rules before applying; it must not create an order without active table context.

## Permissions

Canonical role boundaries are in `docs/SECURITY_RBAC_MATRIX.md`. This section documents the menu-specific policy.

### Venue Owner

Owner can:
- create/edit/reorder/archive categories;
- create/edit/reorder/archive items;
- edit prices;
- manage option groups and option values;
- upload/remove menu media;
- toggle item/option availability;
- run mass stop-list actions;
- run shift check;
- manage featured/top-list items;
- configure guest unavailable-display policy;
- view menu audit where implemented/target.

Dangerous Owner actions:
- price change;
- archive item/category;
- remove/replace media;
- mass availability update;
- option schema change.

These require audit, and confirmation where the UI risk is high.

### Venue Manager

Current implementation from docs: Manager permissions include broad `menu view/manage/availability` in some routes/surfaces.

Target policy:
- Manager can manage stop-list item/option availability.
- Manager can run shift check.
- Manager can update operational order statuses.
- Manager can edit menu structure/prices only if the product explicitly keeps broad Manager `MENU_MANAGE`.

Conservative MVP recommendation:
- Manager: stop-list, shift check, basic item/option availability.
- Owner: structure, price, media, option schema, category/item archive and menu visibility policy.

Current risk: if runtime still grants Manager broad `MENU_MANAGE`, it must be documented as intentional, covered by tests and kept identical across Telegram Bot and Venue Mini App. Otherwise it should be narrowed in a future implementation task.

### Staff

Target decision:
- Staff does not manage menu structure, prices, media, option schema, featured/top-list or menu visibility policy.
- Staff can view menu for operations.
- Staff can change stop-list only if venue policy explicitly enables `staff_stoplist_enabled` or equivalent.
- If Staff stop-list is enabled:
  - only item/option availability can change;
  - no price edit;
  - no create/edit/archive/reorder;
  - audit is required;
  - Telegram Bot and Venue Mini App behavior must match.

Current implementation from docs: Staff has `MENU_VIEW` and `MENU_AVAILABILITY_MANAGE`, and operational item/option stop-list is documented as aligned between Bot and Mini App. Treat that as the current global policy until a per-venue Staff stop-list flag exists.

## Telegram / Mini App Surface Parity

| Feature | Telegram bot | Guest Mini App | Venue Mini App | Platform Mini App | Staff-chat |
| --- | --- | --- | --- | --- | --- |
| Category list/create/edit/reorder | Owner/Manager where implemented. | Read structured categories after QR only. | Owner/Manager where implemented. | No ordinary venue menu management. | No. |
| Item create/edit/archive | Owner/Manager where implemented. | No management. | Owner/Manager where implemented. | No. | No. |
| Item availability | Owner/Manager/Staff where permission allows. | Hides/disables unavailable by policy. | Owner/Manager/Staff where permission allows. | No. | No source-of-truth edits. |
| Option group/value manage | Owner/Manager where implemented. | No management. | Owner/Manager where implemented. | No. | No. |
| Option/value availability | Owner/Manager/Staff where permission allows. | Hides/disables/rejects unavailable. | Owner/Manager/Staff where permission allows. | No. | No. |
| Structured item media/photos/descriptions | No current item photo/thumbnail/description management. | Current structured menu shows no item photo/thumbnail/description. | No current item photo/thumbnail/description management. | No. | No. |
| Public info-section image/PDF | OWNER/MANAGER add and delete attachments and hide/show the whole section; storage uses Telegram `file_id`. | View through guarded backend proxy before QR and in `PUBLISHED_PUBLIC`. | No author/upload/manage flow; both Preview modes are view-only, and `PRIVATE_DRAFT` uses authenticated venue/section/media-scoped delivery without raw refs. | No. | No. |
| PDF/view-only menu | OWNER/MANAGER use the flat info-section image/PDF flow. | View-only `📖 Фото-меню`. | No management; both Preview modes are view-only through their guarded/scoped routes. | No. | No. |
| Option/flavor media | No media fields/actions; name, price delta and availability only. | No option/flavor media. | No media fields/actions. | No. | No. |
| Featured/top-list | Needs verification. | Guest display where implemented. | Needs verification. | Paid placement is separate. | No. |
| Shift check | No Phase 1 UI; existing individual stop-list flow is unchanged. | No. | OWNER/MANAGER Phase 1 is staging-smoke-passed; STAFF hidden and direct API denied. | No. | No. |
| Mass stop-list | No new Telegram batch UI; existing callbacks are unchanged. | No. | Shift-check batch for OWNER/MANAGER only; local draft until one confirmation. | No. | No. |
| Guest menu display | Bot table context only. | Mini App table context only. | Preview/future only. | No. | No. |
| Order submit availability validation | Server-side route, shared by clients. | Must rely on backend preview/submit. | No guest submit. | No. | No. |
| Audit logs | Required for dangerous actions. | No. | Required for dangerous actions. | Platform audit view future/partial. | No. |

Staff-chat rules:
- Staff-chat is not a source of truth for menu.
- Staff-chat must not accept raw menu/price edits.
- Stop-list callbacks from Telegram must verify actor role, venue scope and item/option ownership server-side.
- `callback_data` uses opaque ids/tokens only.
- Staff-chat is not a source of truth for menu or price edits; allowed Telegram shortcuts must call backend routes with RBAC checks.

## Stop-List UX

Venue Mode target screens:

Stop-list overview:
- search;
- category filter;
- `only unavailable` filter;
- item availability toggles;
- indicator `есть выключенные варианты` when an item has unavailable option values.

Option stop-list:
- option value toggles;
- enable all / disable all;
- search.

Shift check Phase 1:
- the existing Menu screen has two task-oriented accordions, both collapsed by default:
  `Редактирование меню` for categories/items/prices/options/top-list and
  `Проверка меню перед сменой` for availability review. Opening one collapses the other; this is
  not a second menu or stock/inventory system;
- normal shift-check rows expose only the availability switch with `В наличии` / `Нет в наличии`;
  options are visually nested under their item and a locally changed row shows `Изменено`.
  Selection checkboxes appear only after `Массовое изменение`, use a different accessible role and
  label from availability, and the mode exposes selected count, make available/unavailable, clear
  selection and exit actions;
- OWNER/MANAGER see saved categories plus available/total item and option counts, search by item or
  option and filters `Все`, `Нет в наличии`, `Есть несохранённые изменения`;
- individual item/option switches and category/item/option mass actions update only the local
  draft. Collapse/reopen preserves the draft; cancel restores saved backend state;
- confirmation shows separate available/unavailable item and option counts. `Отменить изменения`
  clears the draft without a backend mutation or audit;
- `POST /api/venue/menu/shift-check?venueId=<id>` sends only changed item/option ids, expected saved
  availability and desired availability. The combined request is bounded to 500 changes and
  rejects duplicate or invalid ids;
- the backend locks and validates all referenced rows, venue and item/option ownership plus
  expected availability, then applies item changes, option changes and one
  `MENU_SHIFT_CHECK_COMPLETED` audit in one DB transaction;
- a no-op confirmation updates no menu row but writes exactly one completion audit with zero
  changed counts;
- if any expected availability is stale, the whole batch is rejected and the UI shows
  `Меню изменилось. Обновите проверку и повторите подтверждение.`; refresh rebases still-relevant
  draft intent onto current saved state;
- venue switching/disposal collapses both accordions, aborts old reads and confirmation, clears
  draft/selection and prevents a late response from one venue from appearing or being applied in
  another;
- ordinary individual item/option stop-list routes remain immediate and unchanged, including the
  current Staff policy.

Guest behavior:
- unavailable items/options are hidden or disabled based on venue policy;
- stale checkout availability error is safe and actionable;
- suggested replacement is future/optional.

## Media And PDF Policy

- Structured menu is the only source of truth for orders.
- PDF/photo menu is view-only.
- Guest cannot order from PDF directly unless the item is represented in structured menu.
- Current public-card/Photo-PDF-menu attachments are Bot-first: images/PDFs are stored by Telegram
  `file_id`; Guest Mini App and `PUBLISHED_PUBLIC` receive guarded Guest proxy URLs, while
  `PRIVATE_DRAFT` receives authenticated venue/section/media-scoped proxy URLs. Neither preview
  DTO exposes raw refs.
- Current Bot can add/delete attachments and hide/show their whole info section; it has no direct
  replace or per-attachment hide action.
- Current Venue Mini App has no file picker, upload endpoint or media-management UI.
- Current structured menu has no item photo/thumbnail/description contract in active
  Bot/Venue/Guest paths; option/flavor media is absent.
- `menu_category_images` is currently test/seed-oriented with no owner CRUD or active Guest/Venue
  Mini App consumer; it is not structured menu-item photo support.
- Media upload requires a safe storage/proxy strategy.
- Do not expose raw Telegram `file_id`, raw Telegram file URL, bot token, storage secret or provider data as public URL.
- Backend media proxy or safe object storage is required before public display.
- Media moderation is future/optional.

Future **Venue Mini App Media Upload & Management Foundation** must cover OWNER/MANAGER image
upload, PDF only on allowed surfaces, server-side MIME/type/size and venue-scope checks, safe
storage abstraction, replace/hide/show/delete, audit and guest-safe delivery. Bot and Mini App must
share one media source or compatible bridge. `docs/MEDIA_STORAGE_UPLOAD.md` now specifies that
contract; verdict is **STOP_FOR_MEDIA_STORAGE_DECISION** until Telegram technical storage chat
versus hybrid private S3-compatible target (or an operations-qualified filesystem volume) is
approved. No object-storage provider is selected.

## Analytics And Audit

Add/verify analytics events through `docs/ANALYTICS_EVENTS.md` before dashboards:
- `menu_category_created`
- `menu_category_reordered`
- `menu_item_created`
- `menu_item_updated`
- `menu_item_archived`
- `menu_price_changed`
- `menu_item_availability_changed`
- `menu_option_value_availability_changed`
- `menu_media_uploaded`
- `menu_media_removed`
- `shift_check_completed`
- `checkout_failed_out_of_stock`

`shift_check_completed` is the analytics fact name. The Phase 1 operational audit action is the
separate uppercase `MENU_SHIFT_CHECK_COMPLETED`; implementing the audit does not by itself prove
analytics-event emission.

Audit-required:
- price change;
- archive/delete;
- mass stop-list;
- media remove;
- option schema change;
- Staff stop-list toggle if Staff is allowed to change availability.

The shift-check completion audit stores actor/venue ids, changed item/option counts, bounded ids
made available/unavailable and total reviewed item/option counts. Timestamp is supplied by the
existing audit infrastructure. It contains no names, prices, Telegram ids/refs, raw initData,
comments, customer data or full request body.

Item hard-delete status: **DANGEROUS ACTION AUDIT SLICE / MENU ITEM HARD DELETE AUDIT / DONE / MVP /
STAGING-SMOKE-PASSED**.

- One committed delete through the existing Venue Mini App or Telegram management path writes one
  `MENU_ITEM_DELETED` for entity `menu_item` and the item id. Actor is the authenticated user;
  source is server-owned `VENUE_MINI_APP` or `TELEGRAM_BOT`.
- The same JDBC transaction loads and rechecks the authoritative promotion-reference snapshot in
  current parent/rule/item lock order, bumps affected rule versions, deletes the item and appends
  audit. Audit/reference/SQL failure rolls everything back; denial, not-found and repeat write none.
- Confirmation explains that purchase-target and choice-allowlist references are removed
  automatically, while a fixed-reward dependency blocks deletion until the gift is replaced.
- After the locked reference recheck and before any write, an authoritative fixed-reward lookup
  returns HTTP `409` / `MENU_ITEM_DELETE_BLOCKED_BY_FIXED_REWARD`: «Позицию нельзя удалить: она
  используется как фиксированный подарок в акции. Сначала замените подарок или измените акцию,
  затем повторите удаление». The item, reward, rule version/status/timestamps and audit remain
  unchanged, including on repeat.
- Purchase-target and choice-allowlist deletes retain current cleanup/version changes and exactly
  one audit. Remaining CHOICE options stay configured; deleting the last option removes the now
  incomplete reward configuration. Promotion lifecycle status is not rewritten and a fixed reward
  is never replaced automatically. Mini App and Telegram map the same domain result without false
  success or generic DB copy.
- Payload allowlist is `venueId`, `itemId`, `categoryId`, `source`, and
  `affectedPromotionRules`. The nested object has exact unique-id count, first 50 sorted ids,
  explicit omitted count and lowercase SHA-256 of UTF-8 `v1:` plus every sorted unique id joined by
  comma. It never stores the full unbounded list and remains below 4096 UTF-8 bytes.
- Names, prices, media, promotion titles/configuration/schedules/rewards, raw request/callback/
  initData, Telegram fields, secrets and unrelated PII are excluded. Option delete,
  price/name/type/update, availability/stop-list and media audit are not closed by this slice.
- Release closure evidence for HEAD `822233c` records green Actions after rerun of one failed
  backend job, staging deploy and passed smoke: Owner/Manager allowed, Staff denied, fixed-reward
  block on Mini App/Telegram with state and zero audit preserved, CHOICE delete with the remaining
  option preserved and exactly one audit, confirmation/cancel behavior, Guest menu/work-data
  regression and ordinary cleanup. No cause is asserted for the initial failed job.

Category hard-delete status: **DANGEROUS ACTION AUDIT SLICE / MENU CATEGORY HARD DELETE AUDIT /
DONE / MVP / STAGING-SMOKE-PASSED**.

- Only an empty category can be deleted. The existing Mini App and Telegram production callers
  provide authenticated actor plus server-owned `VENUE_MINI_APP` / `TELEGRAM_BOT`; request/query/
  callback data cannot provide either audit authority.
- Authoritative category/venue scope, initial and repeated empty checks, category-reference
  snapshot/recheck, promotion parent/rule locks, category `NOWAIT` lock, bounded summary, current
  target cleanup/version bumps, category delete and `MENU_CATEGORY_DELETED` for
  `menu_category` / category id share one JDBC transaction and commit.
- A committed delete writes exactly one audit. Non-empty, missing/repeated, denied,
  reference/concurrency, SQL, audit and rollback paths write zero. Audit failure restores the
  category, category targets and rule versions/`updated_at`; promotion lifecycle status is unchanged.
- Payload is exactly `venueId`, `categoryId`, `source`, `affectedPromotionRules`. The nested summary
  reuses the item-delete contract: unique ascending ids, first 50 sample, exact omitted count and
  lowercase SHA-256 over UTF-8 `v1:` plus the complete sorted set joined by comma. It is below 4096
  UTF-8 bytes, stores no full unbounded list and never truncates silently.
- Names, prices, promotion content/config/schedules/rewards, media, raw request/callback/initData,
  Telegram identity, secrets and unrelated PII are excluded. For release HEAD `0e30a9b`, the
  user-confirmed evidence records green Actions, staging deploy and the bounded 15-scenario
  role/parity/audit/privacy smoke passed. No migration was added.

Option hard-delete status: **DANGEROUS ACTION AUDIT SLICE / MENU OPTION HARD DELETE AUDIT / ATOMIC
BASE-PROFILE NORMALIZATION INCLUDED / DONE / MVP / STAGING-SMOKE-PASSED**.

- The sole option SQL delete writer serves Venue Mini App direct delete, Telegram direct flavor
  delete and one Telegram single-item base-profile normalization callback. Actor is derived from
  the authenticated session/callback user; source is server-owned `VENUE_MINI_APP` or
  `TELEGRAM_BOT`. Body/query/path/callback data cannot set either.
- Direct delete uses one JDBC transaction: a non-locking parent hint, item lock, item-option locks
  ascending by option id, final venue/item/option recheck, physical delete, same-connection audit
  insert and one commit. Missing/repeated/foreign/denied/failed deletes write no success audit.
- One normalization callback is one repository transaction. After the same stable item-then-option
  locks and a locked reread, existing product rules determine obsolete standard profiles, preserved
  custom/current canonical profiles and missing canonical profiles. Obsolete deletes run in stable
  id order; missing profiles retain existing label/value, zero price delta, available state and
  ordering semantics. Each physical delete keeps one delete audit; under the separate create-audit
  slice, each physically inserted missing profile also gets one create audit. A no-op gets neither.
- The canonical flavor-profile set and its labels are unchanged. Custom options and current
  canonical profiles are preserved; only obsolete standard profiles are removed and missing
  profiles are created under the previous contract. Existing option price/availability is not
  reset, no new base-option selection logic is added, and item lock plus DB-current recheck prevents
  duplicate canonical profiles for the guarded create/actual-rename/normalization paths.
- Generic create and actual rename follow the compatible item-then-option lock order. Only a
  hookah-section create/rename into an existing canonical profile performs the final collision
  check. Non-hookah duplicates and unchanged-name price/availability updates, including legacy
  duplicates, keep their prior behavior. This is not a general uniqueness or base-selection rule.
- Any delete/create/audit failure rolls back the entire direct or normalization operation,
  including earlier writes and audit rows. No process-local lock, idempotency token or best-effort
  audit is used.
- Audit is exactly `MENU_OPTION_DELETED`, entity `menu_item_option`, entity id `optionId`; payload
  keys are exactly `venueId`, `itemId`, `optionId`, `source`. Names, prices, media, order/cart data,
  raw request/callback/initData, Telegram identity, secrets and unrelated PII are excluded.
- Historical order option rows retain immutable name/price snapshots while the live option FK is
  set to null by the existing `ON DELETE SET NULL`. Deleted selections cannot be newly submitted
  and receive the current safe validation error. Promotion rules still hold no menu-option id.
- Owner/Manager own-venue behavior is unchanged; Staff, foreign and unaffiliated users are denied.
  The pre-existing membership-check/revoke race remains separate P2 hardening.
- For release HEAD `03ae0af`, which matches `origin/main` at this handoff, the user-confirmed
  evidence records fully green GitHub Actions, staging deploy and the bounded 17-scenario
  role/parity/audit/history/stale-cart/normalization smoke passed, including ordinary cleanup.

Schema verdict: **NO_MIGRATION_EXPECTED**. This closes only option hard delete plus the included
atomic normalization contract. Option create has the separate release-closed contract below;
option price/availability and the broader Menu/Dangerous Action Audit are not closed by hard delete.

Option create audit status: **DANGEROUS ACTION AUDIT SLICE / MENU OPTION CREATE AUDIT / DONE / MVP /
STAGING-SMOKE-PASSED**.

Production create-writer inventory:

| Caller | Surface | Authenticated actor | Permission | Source | Repository method | Transaction owner | Physical inserts | Create audits | Coverage |
| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |
| `POST /menu/options` | Venue Mini App direct | Session subject | Owner/Manager `MENU_MANAGE` | `VENUE_MINI_APP` | `createOption` | Repository | 0 or 1; success is 1 | Equal to committed inserts | Repository/routes |
| `POST /menu/items/{id}/base-flavor-profiles` | Venue Mini App bulk | Session subject | Owner/Manager `MENU_MANAGE` | `VENUE_MINI_APP` | `applyMissingBaseProfiles` | Repository | N missing | N | Repository/routes |
| Standard-profile callback | Telegram direct canonical | Current callback user | Existing Owner/Manager guard | `TELEGRAM_BOT` | `createOption` | Repository | 0 or 1; success is 1 | Equal to committed inserts | Repository/Telegram/PostgreSQL |
| Add-flavor dialog | Telegram direct custom | Current message user matching dialog owner | Existing Owner/Manager guard | `TELEGRAM_BOT` | `createOption` | Repository | 0 or 1; success is 1 | Equal to committed inserts | Repository/Telegram/PostgreSQL |
| Add-all-standard callback | Telegram bulk | Current callback user | Existing Owner/Manager guard | `TELEGRAM_BOT` | `applyMissingBaseProfiles` | Repository | N missing | N | Repository/Telegram/PostgreSQL |
| Normalize callback | Telegram normalization | Current callback user | Existing Owner/Manager guard | `TELEGRAM_BOT` | `normalizeHookahFlavorProfiles` | Repository | N missing, plus independent deletes | N create audits | Repository/Telegram/PostgreSQL |

`VenueMenuRepository.insertOption` is the single private production SQL writer containing
`INSERT INTO menu_item_options`. No authenticated internal/system/legacy production create writer
or second direct SQL writer exists. `HookahFlavorProfileService` is a pure canonical-profile
planner/helper and opens no authoritative transaction.

- Direct create uses one JDBC connection with `autoCommit=false`: DB-authoritative venue/item/
  category scope, item `FOR UPDATE`, option rows `ORDER BY id FOR UPDATE`, DB-current canonical
  collision recheck, physical insert, generated id, same-connection create audit, result reread and
  one commit. Audit failure restores the insert; success is returned only after commit.
- One add-missing-base-profiles action uses one repository transaction. It locks the same item and
  option rows, plans from DB-current names, inserts only missing profiles in canonical order and
  appends one audit per insert. Custom and current canonical options are preserved; existing price,
  availability and sort behavior are not reset. N=0 writes neither rows nor audits; any insert or
  audit failure restores the full pre-operation snapshot.
- Telegram normalization keeps one repository transaction and deterministic delete/create/audit
  order. Obsolete standard profiles are deleted, custom/current canonical profiles are preserved,
  missing profiles are inserted, and existing `MENU_OPTION_DELETED` plus new
  `MENU_OPTION_CREATED` rows commit together. Failure after earlier deletes, creates or either audit
  family rolls the whole option and audit snapshots back; an already-normal state creates nothing.
- A committed physical insert writes exactly one action `MENU_OPTION_CREATED`, entity type
  `menu_item_option`, entity id `optionId`. Payload keys are exactly `venueId`, `itemId`, `optionId`,
  `source`; actor stays in the audit actor column. Mini App session and current Telegram user are
  the only actor sources, and surface fixes source server-side. Body/query/path/callback/dialog data
  cannot set actor/source.
- Names, price delta, availability, canonical profile value, item/category names, promotions,
  cart/order contents, raw request/initData/callback/update, Telegram identity, media, secrets and
  unrelated PII are excluded from audit and failure logs. Denial, foreign/unaffiliated/not-found,
  canonical collision, duplicate/no-op, insert/audit failure, rollback and concurrent loser write
  zero create audits; no idempotency token is added.
- Automated/local/CI contract evidence is repository `41/0/0/0`, routes `37/0/0/0`, Telegram
  `538/0/0/0`, route/security `1137`, Testcontainers PostgreSQL `26/0/0/0` and Mini App E2E
  `169/169`; full direct/bulk/normalization rollback, no duplicate canonical rows and deterministic
  locking are automated evidence, not manual staging smoke. The PostgreSQL class uses production
  migrations/repositories, independent connections, deterministic barriers and observed blocking;
  its mandatory CI minimum is 26. `compileKotlin`, `ktlintCheck` and the Mini App production build
  also passed. No workflow or migration was added.
- For current release HEAD `0e592ff`, the user confirmed fully green GitHub Actions, staging deploy
  and bounded smoke. Local GitHub CLI did not independently verify Actions because its active token
  is invalid. Smoke confirmed: one Owner Mini App create/audit with `VENUE_MINI_APP`; one Manager
  Telegram create/audit with `TELEGRAM_BOT`; Staff denial; bulk creates only for missing profiles,
  equal audit count and preservation of custom/current canonical profiles, price and availability;
  repeated bulk no-op; normalization restore with one audit followed by no-op; private payload;
  intact Guest menu; and routine cleanup. No rollback/failure injection or concurrency scenario is
  claimed as staging smoke. Schema verdict: **NO_MIGRATION_EXPECTED**.

This closes no broader Menu/Dangerous Action Audit program and does not change rename, price,
availability, item, Shift Check, Guest cart/order, promotions or media behavior.

Item create audit status: **DANGEROUS ACTION AUDIT SLICE / MENU ITEM CREATE AUDIT / MVP
IMPLEMENTED / LOCAL VALIDATION PASSED / REVIEW REQUIRED BEFORE COMMIT**.

Production writer inventory:

| Caller | Surface | Authenticated actor | Permission | Source | Repository method | Transaction owner | Physical inserts | Create audits | Coverage |
| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |
| `POST /menu/items` | Venue Mini App | Session subject | Owner/Manager `MENU_MANAGE` | `VENUE_MINI_APP` | `createItem` | Repository | One per success | One | Repository/routes/PostgreSQL |
| Add-item dialog | Telegram Bot | Current message user matching persisted dialog owner | Owner/Manager venue guard | `TELEGRAM_BOT` | `createItem` | Repository | One per success | One | Repository/Telegram/PostgreSQL |

The sole production `INSERT INTO menu_items` is the private repository insert helper. The staging
seed is operational SQL, not an authenticated runtime writer; no internal/system/legacy writer or
unaudited production overload exists. Item creation does not create options.

- Owner/Manager keep current own-venue create authority and legacy `ADMIN` remains a Manager alias.
  Staff, foreign, unaffiliated and Platform-only actors are denied. Mini App checks membership before
  request/category facts. Telegram checks venue authority before category lookup and requires the
  current message actor to equal the persisted dialog owner; dialog/body/query/callback data cannot
  choose actor or source.
- One repository-owned JDBC connection uses `autoCommit=false`: DB-authoritative category scope,
  blocking category-row lock, scope recheck, existing `MAX(sort_order)+1`, physical insert,
  generated id, same-connection audit, item reread and one commit. Item reorder uses the same lock;
  category delete retains its current `NOWAIT` outcome. Any insert/audit/read/commit failure rolls
  the transaction back; success is returned only after commit.
- A committed physical item writes exactly one `MENU_ITEM_CREATED`, entity `menu_item`, entity id
  `itemId`. Payload keys are exactly `venueId`, `itemId`, `source`; actor remains only in the standard
  audit actor field. Denial, invalid/foreign category, input/SQL/audit failure and rollback write
  zero. Duplicate names remain independent physical creates and no idempotency/unique constraint is
  introduced.
- Item name, price, currency, availability, type, category id/name, description, sort, media,
  options, promotions, cart/order, raw request/initData, Telegram payload/identity, secrets and PII
  are excluded from the audit and new logs. Existing initial values, DTOs, form/dialog behavior and
  absence of automatic option creation are unchanged.
- Local evidence is repository `44/0/0/0`, routes `40/0/0/0`, Telegram `542/0/0/0`, Testcontainers
  PostgreSQL `31/0/0/0`, compile/ktlint, Mini App build and full Playwright `169/169`. Deterministic
  PostgreSQL coverage uses independent PIDs/latches and observed blocking for Mini/Mini,
  Mini/Telegram, create/reorder and concurrent audit failure; create/category-delete verifies the
  current `NOWAIT` loser. Committed item count equals create-audit count and rollback leaves no
  unaudited item. **NO_MIGRATION_EXPECTED**; review, Actions, staging deploy and bounded smoke remain.

This closes no item update, option/category create, Guest cart/order, promotions, media or broader
Menu/Dangerous Action Audit contract.

Option rename status: **DANGEROUS ACTION AUDIT SLICE / MENU OPTION RENAME AUDIT / DONE / MVP /
STAGING-SMOKE-PASSED**.

- `VenueMenuRepository.updateOption` is the sole option-name SQL writer. Venue Mini App compound
  `PATCH /menu/options/{id}` and Telegram rename dialog are the only production rename callers and
  both pass a server-derived authenticated actor plus server-owned `VENUE_MINI_APP` or
  `TELEGRAM_BOT` to that repository contract. Telegram no longer falls back to dialog-state actor.
- Item lock, option rows ascending by id, DB-current target/collision reread, update and audit use
  one JDBC transaction. A real persisted name change writes exactly one `MENU_OPTION_RENAMED` for
  entity `menu_item_option` / option id. Audit failure restores name and co-submitted price/
  availability; route/router never append a second audit.
- Payload keys are exactly `venueId`, `itemId`, `optionId`, `oldName`, `newName`, `source`. Actor
  stays in the standard audit column. Prices, availability, canonical keys, media, order/cart data,
  raw request/callback/initData, Telegram identity, secrets and unrelated PII are excluded.
- Exact-name no-op, repeated same-name request, price/availability-only update, denial/foreign,
  missing target, canonical collision, SQL/audit failure and rollback write zero rename audit. The
  compound Mini App PATCH keeps its existing atomic field behavior but this slice audits only name.
- Existing hookah-only normalized canonical collision policy, self-exclusion, non-hookah duplicate
  behavior, historical snapshots and current-value resolution for future submit are unchanged.
  Real PostgreSQL coverage serializes rename with rename, normalization, canonical create and
  direct delete. The shared class now has 26 tests after the create-audit extension; its XML is
  mandatory with no skipped/failures/errors.
- Schema verdict: **NO_MIGRATION**. Option create audit is tracked by its separate release-closed contract;
  item/category mutations,
  new canonical semantics, membership-revoke linearization, promotions, viewer and media remain
  outside this slice. The broader Menu and Dangerous Action Audit programs remain `PARTIAL`.

Option price audit status: **DANGEROUS ACTION AUDIT SLICE / MENU OPTION PRICE AUDIT / DONE / MVP /
STAGING-SMOKE-PASSED**.

- `VenueMenuRepository.updateOption` remains the only production SQL writer that changes an
  existing `menu_item_options.price_delta_minor`. The authenticated Venue Mini App compound
  `PATCH /menu/options/{id}` is the only price caller; Telegram passes no option price. Option create
  and fixed-zero missing-profile creation remain outside this price-update slice.
- Mini App actor is the authenticated session subject and source is server-fixed
  `VENUE_MINI_APP`; body, query, path and client metadata cannot supply either. Owner/Manager keep
  current own-venue `MENU_MANAGE`; Staff, foreign and unaffiliated actors remain denied.
- One JDBC transaction takes the non-locking option-to-item hint, locks the item, locks all item
  options in ascending id, rereads the DB-current target, conditionally checks a real rename
  collision, applies name/price/availability, writes any rename audit, writes any price audit and
  commits once. Any SQL/audit failure restores every field, `updated_at` and both audit families.
- A real committed price change writes exactly one `MENU_OPTION_PRICE_CHANGED` for entity
  `menu_item_option` / option id. Payload keys are exactly `venueId`, `itemId`, `optionId`,
  `oldPriceDeltaMinor`, `newPriceDeltaMinor`, `source`; actor stays in the audit actor column.
  Names, availability, canonical values, promotion/cart/order contents, media, raw request/initData,
  Telegram fields, secrets and unrelated PII are excluded.
- Exact-price no-op/retry, name-only, availability-only, denial/foreign/not-found, canonical
  collision, SQL/audit failure and rollback write zero price audit. Name+price writes one independent
  rename and one price audit; a real availability delta now adds its independent audit in the same
  atomic update. No idempotency token is added.
- Money validation, integer minor units, zero delta, request/response shape, UI parsing, currency and
  rounding are unchanged. Checkout reloads the current available option and current delta from the
  database; client/stale-cart price is not authority. New order rows snapshot the current delta;
  existing `price_delta_minor_snapshot` rows are never rewritten.
- The current 26-test deterministic PostgreSQL class uses independent connections and observed blocking,
  with no arbitrary sleep. It proves truthful distinct-price ordering, same-target loser no-op and
  serialization with rename, direct delete and atomic normalization. Mandatory CI minimum is 26
  tests with zero skipped/failures/errors. Schema verdict: **NO_MIGRATION**.

Release evidence for current release HEAD `0489a2f`: the user confirmed fully green GitHub Actions,
staging deploy and bounded staging smoke. The confirmed smoke covers price-only success and one price
audit without a rename audit; same-price repeat with no new price audit; atomic name+price with one
audit of each family; server-authoritative current price or safe reconfirmation at submit; intact
working menu/data; and routine cleanup. Historical order-snapshot preservation remains an automated
contract, not a separately confirmed staging scenario.

This closes only the bounded price-audit contract. Option create is separately implemented locally
and still needs review/CI/staging; item-price audit, the broader Menu or Dangerous Action Audit, and
all media/storage work remain open.

Option availability audit status: **DANGEROUS ACTION AUDIT SLICE / MENU OPTION AVAILABILITY AUDIT /
DONE / MVP / STAGING-SMOKE-PASSED**.

- Individual existing-option production paths are direct Venue Mini App availability, compound
  Venue Mini App option PATCH and Telegram Owner/Manager/Staff stop-list callbacks. Direct paths keep
  `MENU_AVAILABILITY_MANAGE`; compound PATCH keeps `MENU_MANAGE` and grants Staff no schema access.
- Direct mutation owns one transaction: option-to-item hint, authoritative item lock, all item
  options by ascending id, DB-current reread, real-delta update, same-connection audit and one commit.
  Compound rename, price and availability audits are independent but commit or roll back together.
- Action/entity are `MENU_OPTION_AVAILABILITY_CHANGED`, `menu_item_option`, option id. Payload keys
  are exactly `venueId`, `itemId`, `optionId`, `oldIsAvailable`, `newIsAvailable`, `source`; actor is
  the authenticated session/callback user and source is server-owned `VENUE_MINI_APP` or
  `TELEGRAM_BOT`.
- Same-state/repeated, name-only, price-only, denial, foreign/not-found, collision, SQL/audit failure
  and rollback write zero availability audit. Audit failure restores all fields, `updated_at` and
  every audit row. No idempotency token was added.
- Shift Check remains excluded: successful mixed/individual/common/no-op batches keep only one
  `MENU_SHIFT_CHECK_COMPLETED`; stale/retry/failed batches add neither batch nor per-option success
  audit. Existing Shift Check payload and RBAC are unchanged.
- Unavailable/new-order, stale-cart, re-enable and historical snapshot behavior stays under the
  current server validation contract. Option create/defaults, normalization, item metadata/price,
  promotions, order schema and media are unchanged.
- Testcontainers PostgreSQL uses independent connections and deterministic latches with an observed
  blocking edge for direct/direct, direct/compound, both direct/Shift Check orders, direct/delete and
  direct/normalization. The shared PostgreSQL class and CI minimum are now `26/0/0/0` after the
  create-audit extension. **NO_MIGRATION_EXPECTED**.

Item availability audit status: **DANGEROUS ACTION AUDIT SLICE / MENU ITEM AVAILABILITY AUDIT /
DONE / MVP / STAGING-SMOKE-PASSED**.

- Existing-item production writers were inventoried: Mini App direct availability, Mini App
  compound item PATCH, Telegram Owner/Manager/Staff detail/root stop-list callbacks and Shift Check.
  Initial create has no old value; delete keeps its existing delete audit. No second authenticated
  runtime or legacy SQL writer exists outside `VenueMenuRepository`.
- Direct and compound writers share one transaction: authoritative venue/item/category scope, item
  `FOR UPDATE`, DB-current reread, real-delta comparison, conditional update, same-connection audit,
  result reread and one commit. Compound item type/metadata fields and the availability audit commit
  or roll back together, without adding metadata or price audit families.
- One real individual delta writes `MENU_ITEM_AVAILABILITY_CHANGED`, entity `menu_item`, item id.
  Payload keys are exactly `venueId`, `itemId`, `oldIsAvailable`, `newIsAvailable`, `source`;
  actor is the Mini App session subject/current Telegram callback user and source is server-fixed
  `VENUE_MINI_APP` or `TELEGRAM_BOT`.
- Same-state/repeated, metadata-only, denial, foreign/not-found, stale/collision, SQL/audit failure
  and rollback write zero item-availability audit. Direct no-op preserves `updated_at`. Audit failure
  restores availability, timestamp, compound fields and all transaction audit rows.
- Direct authority stays Owner/Manager/Staff through `MENU_AVAILABILITY_MANAGE`; compound item PATCH
  remains Owner/Manager `MENU_MANAGE`; Shift Check remains Owner/Manager only. Staff receives no
  compound price/name/type authority.
- Shift Check remains excluded from individual auditing: common, individual, mixed and no-op success
  retain exactly one `MENU_SHIFT_CHECK_COMPLETED`; stale/failure adds neither aggregate success nor
  per-item audits. Its batch writer does not invoke the direct helper.
- Testcontainers PostgreSQL covers item direct/direct, direct/compound, both direct/Shift Check
  orders and delete/direct with independent connections, deterministic latches and an observed
  blocking edge. The shared class XML and CI minimum are `26/0/0/0`. Guest submit contention,
  unavailable typed rejection, zero-write stale carts, re-enable recovery and immutable snapshots
  remain green. **NO_MIGRATION_EXPECTED**.

Audit payloads must use safe ids and old/new safe fields only. Do not include raw media payloads, raw Telegram file URLs, provider data, secrets, raw initData, guest message text or unrelated PII.

## Roadmap Status

- Menu/options/stop-list spec: `UPDATED`.
- Menu constructor implementation: `PARTIAL` unless route/screen/test evidence proves full coverage.
- Option modifiers in orders: structured selected-option parity is documented as `CLOSED / staging smoke passed`; option price, option availability and option create audits are release-closed; broader multi-group modifier model remains `PARTIAL / needs verification`.
- Staff stop-list parity: current docs say item/option availability is aligned between Bot and Mini App; per-venue `staff_stoplist_enabled` remains `FUTURE`.
- Public info-section / Photo-PDF-menu media: `PARTIAL / BOT-FIRST`; Guest rendering and both
  Preview modes work through guarded/scoped proxies, while Venue Mini App upload/manage is
  `MISSING / FUTURE`.
- Structured menu-item media/description/thumbnail and option/flavor media: `MISSING / FUTURE`.
- Featured/top-list: `PARTIAL/FUTURE` unless implementation evidence proves a given slice.
- Shift check: `MENU SHIFT CHECK PHASE 1 / DONE / MVP / STAGING-SMOKE-PASSED`; keep the passed
  role/tenant, UX, atomicity, stale-state, Guest availability and Telegram parity scenarios in
  regression.
- Venue Menu management UX stabilization: **VENUE MENU MANAGEMENT UX STABILIZATION / MOBILE
  RESPONSIVENESS + PRICE INPUT ERGONOMICS + CONTEXT PRESERVATION / DONE / MVP /
  STAGING-SMOKE-PASSED**. The management editor has no horizontal overflow at narrow Telegram Mini App widths;
  item/option cards and action groups stack or wrap inside their cards, while long Russian labels
  remain readable. New item/option price fields are empty with examples, and an existing zero price
  is selected only while its current value is zero, so first typing or paste replaces it without
  reselecting a later edited value. Successful create/update/delete/base-profile mutations preserve
  the current category/item/option anchor through the authoritative reload only when no later user
  scroll/focus/pointer/touch/wheel/keyboard interaction or later mutation supersedes that snapshot.
  A later active menu form is instead reopened by its stable category/item/option ID with its current
  draft and focus preserved; no name/price heuristic is used. Category create, rename and reorder
  focus the native stable-`categoryId` summary, including an empty category; cancel restores the
  relevant item/option/add-option/category-rename trigger or summary without a mutation request.
  Success is announced by the current screen's live region; failure keeps local form values. Context
  and success are discarded on venue/account switch. This changes no server money, RBAC, audit,
  normalization, Guest ordering or DTO contract.
- Item hard-delete audit: **DANGEROUS ACTION AUDIT SLICE / MENU ITEM HARD DELETE AUDIT / DONE / MVP /
  STAGING-SMOKE-PASSED**. The bounded release gates are complete; schema verdict is
  `NO_MIGRATION_EXPECTED`.
- Category hard-delete audit: **DANGEROUS ACTION AUDIT SLICE / MENU CATEGORY HARD DELETE AUDIT /
  DONE / MVP / STAGING-SMOKE-PASSED**. The existing empty-category-only, RBAC, response and
  promotion lifecycle contracts are unchanged; no migration was added. This does not close
  option/price/update/availability/media or broader Menu audit.
- Option hard-delete audit: **DANGEROUS ACTION AUDIT SLICE / MENU OPTION HARD DELETE AUDIT / ATOMIC
  BASE-PROFILE NORMALIZATION INCLUDED / DONE / MVP / STAGING-SMOKE-PASSED**. Current schema is
  sufficient; keep direct-delete, normalization, RBAC, audit, history and stale-cart scenarios in
  regression.
- Option create audit: **DANGEROUS ACTION AUDIT SLICE / MENU OPTION CREATE AUDIT / DONE / MVP /
  STAGING-SMOKE-PASSED**. Exact writer inventory, atomic direct/bulk/normalization behavior,
  per-insert audit cardinality, RBAC/privacy, automated 26-test PostgreSQL gate and the bounded
  cross-surface staging smoke are recorded above. This closes no other menu-create or update family.
- Item create audit: **DANGEROUS ACTION AUDIT SLICE / MENU ITEM CREATE AUDIT / MVP IMPLEMENTED /
  LOCAL VALIDATION PASSED / REVIEW REQUIRED BEFORE COMMIT**. One writer/two callers, atomic
  item+audit rollback, RBAC/dialog actor binding, privacy, real PostgreSQL contention and no-migration
  evidence are recorded above; independent review, CI and staging remain open.
- Option rename audit: **DANGEROUS ACTION AUDIT SLICE / MENU OPTION RENAME AUDIT / DONE / MVP /
  STAGING-SMOKE-PASSED**. Focused backend, PostgreSQL lock, compile/lint, Mini App build and
  Playwright `139/139` were green locally for that release slice; its bounded cross-surface staging
  smoke is recorded passed.
- Option availability audit: **DANGEROUS ACTION AUDIT SLICE / MENU OPTION AVAILABILITY AUDIT / DONE /
  MVP / STAGING-SMOKE-PASSED**.
- Item availability audit: **DANGEROUS ACTION AUDIT SLICE / MENU ITEM AVAILABILITY AUDIT / DONE /
  MVP / STAGING-SMOKE-PASSED**. This closes neither
  item price/name/type audit nor the broader Menu/Dangerous Action Audit.
- Guest cart stale menu recovery: **GUEST CART STALE MENU SELECTION RECOVERY / REMOVED OR UNAVAILABLE
  ITEMS AND OPTIONS / PAYLOAD-BOUND IDEMPOTENCY + ATOMIC REJECTION / DONE / MVP /
  STAGING-SMOKE-PASSED**; **ITEM-LEVEL ACTION AND COPY POLISH / DONE / MVP /
  STAGING-SMOKE-PASSED**. It is category-agnostic, uses all server-owned issues, shares
  preview/submit validation, makes preview read-only and keeps final submit validation plus
  context/order writes in one transaction. PostgreSQL `V123` / H2 `V124` add only nullable
  `request_fingerprint`; raw request/canonical JSON is not stored, `response_snapshot` is not
  repurposed, and there is no bulk backfill or global unique constraint.
- Guest server-side availability validation: `REQUIRED`; current stale/unavailable option rejection is documented as covered for the smoked options/flavors flow, but broader availability validation should stay in regression.
- Promotions/paid placement remain separate from featured/top-list and follow `docs/GROWTH_RETENTION.md` plus `docs/PLATFORM_COCKPIT.md`.

## Smoke Checklist

1. Owner creates a category.
2. Owner creates an item with price.
3. Owner creates an option group and option values.
4. Owner toggles item unavailable; guest cannot order it.
5. Owner toggles option value unavailable; guest cannot select/order it.
6. Guest stale cart recovery is verified before submit:
   - removed/unavailable item preview and submit identify that exact line; expanded before/after
     snapshots cover session timestamps, exits, tabs/memberships, context/dialog, order state,
     idempotency, analytics and outbox,
     retry keeps the issue until re-enable, and removal affects only that line;
   - removed/unavailable selected option uses the retained cart-line option name where present and
     generic `вариант` copy otherwise; the current picker excludes it, and valid replacement keeps
     quantity/note;
   - one unavailable item plus one removed option renders both lines; fixing one keeps the other
     warning and valid lines, and no line is deleted automatically.
7. Price changes after order do not alter existing order snapshot.
8. Archived item remains visible in old order history/bill snapshot.
9. Manager can/cannot edit price according to final policy.
10. Manager can toggle stop-list if target/current policy allows.
11. Staff cannot edit menu structure or price.
12. Staff stop-list behavior is identical in Telegram and Mini App according to current policy.
13. Stop-list actions write audit where implemented.
14. Mass availability update requires confirmation/audit.
15. Guest menu hides or greys unavailable items/options based on venue policy.
16. Staff-chat does not become source of truth for menu edits.
17. Telegram callback actions verify role and venue scope server-side.
18. Owner and Manager can open and confirm shift check for their own venue; Staff and foreign
    venue users are denied, while existing Staff individual availability behavior is unchanged.
19. Draft toggles, category/item/option mass actions and cancel send no availability mutation.
20. One confirmation sends one bounded batch and atomically applies mixed item/option changes.
21. Duplicate, missing, foreign, ownership-mismatched or oversized input writes no partial state
    and no completion audit.
22. Stale expected availability rejects the whole batch with refresh guidance.
23. Successful and no-op confirmation each write exactly one safe `MENU_SHIFT_CHECK_COMPLETED`
    audit; no-op changed counts are zero.
24. Venue switching clears draft/selection and ignores or aborts old-venue requests.
25. Confirmed item/option availability is reflected by Guest menu and is revalidated by stale cart
    preview/add-batch.
26. Owner and Manager own-venue item hard delete preserve the existing success response and write
    exactly one actor-bearing audit; Staff, foreign and unaffiliated actors are denied without audit.
27. Zero/small/>50/duplicate promotion reference sets produce the exact sorted/deduplicated count,
    first-50 sample, omitted count and full-set hash; payload remains below 4096 UTF-8 bytes.
28. Audit/reference/SQL failure leaves the item, promotion rule/version state and audit unchanged;
    PostgreSQL config/delete contention has a consistent committed winner with no partial state.
29. Item-delete confirmation explains automatic purchase-target/choice cleanup and the
    fixed-reward restriction; cancel sends no delete request.
30. Fixed-reward delete shows the exact safe next step in Mini App and Telegram, keeps all state,
    writes no success audit and exposes no promotion title/rule id/SQL or PII.
31. Purchase-target and every choice-allowlist entry, including the stored primary pointer, delete
    atomically with current version cleanup and exactly one audit.
32. Owner/Manager empty-category delete writes one safe `MENU_CATEGORY_DELETED`; Staff/foreign deny.
33. Non-empty, missing/repeated and failed category delete writes zero success audit.
34. Referenced empty-category delete removes targets, bumps rule version, preserves lifecycle status
    and commits exactly one bounded affected-rule summary.
35. Audit failure and deterministic promotion config/category-delete contention leave no partial
    category/target/version/audit state.
36. Mini App and Telegram Owner/Manager real option rename write exactly one actor/source-bearing
    `MENU_OPTION_RENAMED`; Staff, foreign and unaffiliated denial writes none.
37. Same-name repeat, price-only and availability-only updates write zero rename audit. Compound
    name+price/availability writes one name-only audit and audit failure restores every field.
38. Canonical collision preserves the old row and writes no rename audit; rename contention with
    rename, normalization, canonical create and direct delete has only fully ordered outcomes.
39. Rename payload uses the exact allowlist and contains no prices, availability, canonical key,
    media, raw request/callback/initData, Telegram identity, secrets or unrelated PII.
40. At 320/360/390/430 px, the Venue Menu document and editor have no horizontal overflow; item,
    option, availability and destructive/primary actions stay visible and operable, and long Russian
    labels wrap inside their cards.
41. New item and option price inputs start empty; real keyboard entry sends the expected minor-unit
    number. An existing zero remains zero on focus/blur, but is replaced—not prefixed—by typing or
    paste; empty/invalid required item price remains safely actionable.
42. After an authoritative reload following item/option create, edit, availability, delete or
    base-profile mutation, the current category stays expanded and the affected entity or safe
    fallback remains visible with logical focus. Failed inline edits retain input and nearby error.
43. Venue/account switch clears Menu editor context and ignores late old-venue responses; no old
    mutation receives visual confirmation in the new venue.
