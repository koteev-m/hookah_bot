# Menu / Options / Stop-List Model

Дата актуализации: 2026-07-07.

Статус: **current product reference / SPEC UPDATED**. Menu/options/flavors parity is documented as smoke-closed for the structured selected-option flow, but the broader menu constructor, media/top-list governance, shift check, audit coverage and permission parity remain **PARTIAL** unless a specific implementation task proves them.

## Core Rule

The structured menu is the source of truth for orders. PDF/photo menu is view-only. Guest order preview and submit must validate item availability, option validity and prices server-side; the cart must never trust client-side prices or stale option state.

Menu permissions are governed by `docs/SECURITY_RBAC_MATRIX.md`; Venue Mode operational surfaces are governed by `docs/VENUE_OPERATIONS.md`; order/session/tab and snapshot rules are governed by `docs/ORDER_SESSION_TAB_CORE.md`; Telegram callback/staff-chat rules are governed by `docs/TELEGRAM_FALLBACK_STAFF_CHAT.md`; analytics/audit event names are governed by `docs/ANALYTICS_EVENTS.md`.

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
| Item CRUD | Structured menu items, prices and availability exist; Owner/Manager item editing is documented, Staff denied for structure. | Owner manages item create/edit/reorder/archive, price, visibility, featured and media. | Manager structure/price scope needs final policy if broad `MENU_MANAGE` remains. |
| Item availability | Item-level stop-list is documented for OWNER/MANAGER/STAFF operational availability. | Availability toggle is operational state, fast and reversible, with audit for Staff/Manager where implemented. | Per-venue `staff_stoplist_enabled` flag is target/future; current global Staff permission must stay cross-surface consistent. |
| Option group/value support | Guest/Menu Options & Flavors parity is smoke-closed: item-scoped options/flavors, base profiles and selected-option submit exist. | General `OPTION_GROUP`/`OPTION_VALUE` model supports required single/multi modifiers with min/max validation. | Broader non-hookah modifier UX and DB race/duplicate protection need verification. |
| Option/value availability | Item option/flavor stop-list is documented and smoked. | Guest sees only available choices or disabled copy by venue policy; stale submit is rejected. | Keep option ownership and stale availability tests in regression. |
| Guest menu DTO | Guest Bot and Guest Mini App expose option picker where configured. | DTO includes item visibility/availability, option groups, values, price deltas and human copy without leaking internal media/provider data. | Needs verification before broad modifier expansion. |
| Order item modifiers/options snapshot | Current docs say selected option id/name/price delta and line preference notes are preserved where implemented. | Snapshot item and selected option names/prices at submit time; later edits never rewrite old bills/history. | Keep cross-channel bill snapshots in regression; multi-option quantities/counts need future design if introduced. |
| Photos/descriptions | Item/media support exists in parts; guest media proxy is used for info sections. | Item photos can be shown in structured menu; descriptions are guest-safe. | Deep menu media/photo governance remains partial. |
| Featured/top-list | Product spec requires featured/top list; implementation evidence is partial. | Venue manually pins items; not paid placement. | Paid placement/boosting belongs to Growth/Platform, not menu featured. |
| PDF/media | `Фото-меню` exists as info/media section and is separate from structured order menu. | PDF/photo menu is view-only; no direct order unless item exists in structured menu. | Optional owner-defined subsections and richer media policy remain future. |
| Shift check | Operational readiness is a target concept; no complete shift-check flow is proven here. | Venue Mode has readiness cards, availability counts, mass enable and `shift_check_completed` evidence. | `FUTURE/PARTIAL`; do not call DONE without implementation evidence. |
| Audit logs | Some role/menu/stop-list audit gaps remain in audit docs. | Price changes, archive/delete, mass stop-list, media removal, option schema change and Staff stop-list toggles write safe audit. | Dangerous-action audit remains `PARTIAL`. |
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
- If item/option availability changes between cart and submit, guest copy should be safe: `Позиция или выбранный вариант больше недоступны. Удалите из корзины или выберите замену.`
- Suggested replacements are future/optional and must not silently substitute an item or option.

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
| Media/photos | Owner where implemented. | View safe proxied media where exposed. | Owner/Manager where implemented. | No. | No. |
| PDF/view-only menu | Owner info/media flow where implemented. | View-only `Фото-меню`. | Owner/Manager info/media where implemented. | No. | No. |
| Featured/top-list | Needs verification. | Guest display where implemented. | Needs verification. | Paid placement is separate. | No. |
| Shift check | Future/partial. | No. | Future/partial. | No. | No. |
| Mass stop-list | Future/partial; role-checked. | No. | Future/partial; role-checked. | No. | No. |
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

Shift check:
- category readiness cards;
- available/total counts;
- `Подтвердить готовность меню`;
- mass enable by category;
- audit event `shift_check_completed`.

Guest behavior:
- unavailable items/options are hidden or disabled based on venue policy;
- stale checkout availability error is safe and actionable;
- suggested replacement is future/optional.

## Media And PDF Policy

- Structured menu is the only source of truth for orders.
- PDF/photo menu is view-only.
- Guest cannot order from PDF directly unless the item is represented in structured menu.
- Media upload requires a safe storage/proxy strategy.
- Do not expose raw Telegram `file_id`, raw Telegram file URL, bot token, storage secret or provider data as public URL.
- Backend media proxy or safe object storage is required before public display.
- Media moderation is future/optional.

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

Audit-required:
- price change;
- archive/delete;
- mass stop-list;
- media remove;
- option schema change;
- Staff stop-list toggle if Staff is allowed to change availability.

Audit payloads must use safe ids and old/new safe fields only. Do not include raw media payloads, raw Telegram file URLs, provider data, secrets, raw initData, guest message text or unrelated PII.

## Roadmap Status

- Menu/options/stop-list spec: `UPDATED`.
- Menu constructor implementation: `PARTIAL` unless route/screen/test evidence proves full coverage.
- Option modifiers in orders: structured selected-option parity is documented as `CLOSED / staging smoke passed`; broader multi-group modifier model remains `PARTIAL / needs verification`.
- Staff stop-list parity: current docs say item/option availability is aligned between Bot and Mini App; per-venue `staff_stoplist_enabled` remains `FUTURE`.
- Photos/media/top-list: `PARTIAL/FUTURE` unless implementation evidence proves a given slice.
- Shift check: `FUTURE/PARTIAL`.
- Guest server-side availability validation: `REQUIRED`; current stale/unavailable option rejection is documented as covered for the smoked options/flavors flow, but broader availability validation should stay in regression.
- Promotions/paid placement remain separate from featured/top-list and follow `docs/GROWTH_RETENTION.md` plus `docs/PLATFORM_COCKPIT.md`.

## Smoke Checklist

1. Owner creates a category.
2. Owner creates an item with price.
3. Owner creates an option group and option values.
4. Owner toggles item unavailable; guest cannot order it.
5. Owner toggles option value unavailable; guest cannot select/order it.
6. Guest has item in cart, then item becomes unavailable; submit is rejected safely.
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
