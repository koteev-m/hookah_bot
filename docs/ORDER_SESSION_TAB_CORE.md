# Order / Session / Tab Core Model

Дата актуализации: 2026-07-07.

Статус: **current product reference / SPEC UPDATED**. Этот документ фиксирует product model для QR table context, active table order, order batches, personal/shared tabs, bill/request/close flow, visit-history foundation and privacy boundaries. Runtime status is mixed: the old table-only active-order risk is documented as closed in current audit notes, while visit history, force-close policy, some DB-level uniqueness nuances and broader analytics remain future/partial.

Analytics/event semantics for this core are defined in `docs/ANALYTICS_EVENTS.md`. Role, scope and trust-boundary decisions are defined in `docs/SECURITY_RBAC_MATRIX.md`. Structured menu, option/modifier and stop-list rules are defined in `docs/MENU_OPTIONS_STOPLIST.md`. Venue operational surfaces are defined in `docs/VENUE_OPERATIONS.md`.

## Core Rule

The active order belongs to a verified table session/visit, not to a physical table forever. A physical table can have multiple sequential visits; a new visit must not accidentally continue an old active order, tab or bill.

`table_token` and tab invite tokens are short pointers to context. They are not authorization by themselves. Every read/write still needs server-side user/session/venue/tab checks.

## Terms

| Term | Product meaning |
| --- | --- |
| `TABLE_SESSION` | Visit/session for a user or group at a concrete venue table. It is created/resolved through QR table context, has TTL/explicit close/staff close policy and must not live forever. Current implementation notes describe shared physical `table_sessions` plus user-scoped guest exit markers. |
| `ACTIVE_TABLE_ORDER` | Order container for the current table visit/session. Target scope is `venue_id + table_id + table_session_id`, not only `table_id`. There is one active order for the current table session/visit; a closed/expired session cannot receive new batches. |
| `ORDER_BATCH` | One cart submission / one `дозаказ`. It always belongs to an active order, has source such as `miniapp` or `bot_fallback`, is idempotent by client idempotency key and carries item/price/option snapshots. |
| `TAB` | Bill/account inside a visit. Personal tab is default for a guest. Shared tab is explicit and visible only to participants. Each order batch belongs to a `tab_id`. |
| `VISIT` | Product-level history/retention concept derived from `TABLE_SESSION` + closed order + booking seated/no-show signals. It does not have to be a runtime entity until a later implementation creates one. |

## Core Invariants

- QR/table token sets context, not permissions.
- All permissions and ownership checks are server-side.
- Active orders must not mix different `table_session_id` values.
- Guest active order view is scoped by `tableSessionId` + selected/current `tabId`.
- Venue queue may group by physical table, but order detail must preserve session, batches and tabs.
- One physical table can have sequential visits; old visit/order state must not leak into a new visit.
- Batch creation is idempotent by client idempotency key.
- Item name, price, selected options/flavors and price deltas are snapshotted at order time according to `docs/MENU_OPTIONS_STOPLIST.md`.
- Stop-listed or unavailable items/options are rejected server-side at preview/submit.
- Personal tab is default; shared tab requires invite/consent.
- Closed/paid tabs cannot receive new batches unless an allowed role explicitly reopens them with audit.
- Staff chat is a notification/radar/shortcut surface, not the source of truth.

## Current Implementation Vs Target

| Block | Current implementation from docs/audit | Target product model | Gap / future implementation note |
| --- | --- | --- | --- |
| `TABLE_SESSION` | QR resolve and table session creation exist. Guest exit is user-scoped through `guest_table_session_exits`; shared physical `table_sessions` are not closed for all guests by one guest exit. TTL cleanup exists. | Session represents the active venue/table visit context and has explicit close/expire/staff close semantics. | Staff force-close reason/audit and a product-level visit timeline need a dedicated future task if not already implemented. |
| Active order lookup | Current docs state the old active-order-by-`table_id` risk is closed: active order is scoped by `table_session_id`; H2 mirrors PostgreSQL active-order uniqueness. | One active `ACTIVE_TABLE_ORDER` per current `table_session_id`/visit. | Keep regression smoke for sequential visits at same table. Do not re-open without new code/smoke evidence. |
| Guest active order endpoint | Current docs state active order view uses `tableSessionId`/`tabId`, human `Заказ №...`, selected account label and selected-tab totals. | Guest sees only selected personal/shared tab context for the active session. | Keep privacy regression for two guests and shared tab membership. |
| Order batch creation | `add-batch` exists; idempotency key does not duplicate batch; selected options and line notes are snapshotted where implemented. | Every batch belongs to active order + tab and records source `miniapp` / `bot_fallback`. | Source naming and analytics event completeness need verification before analytics work. |
| Tab membership and visibility | Personal/shared tabs and membership checks exist; current docs note H2 mirrors active personal-tab uniqueness while PostgreSQL still permits active `PERSONAL` tabs with `owner_user_id NULL` at schema level. | Personal tab is owner-only; shared tab requires explicit join/consent; every batch has `tab_id`. | PostgreSQL nullable-owner DB nuance and shared-tab DB uniqueness remain follow-ups if product needs DB-level enforcement beyond repository idempotency. |
| Full bill | Guest/Venue/Bot bill identity parity is staging-smoked; Venue detail shows included/excluded/discount/service-charge context and human tab labels. | Bill is backend-owned, shows batches/tabs/service charges and does not expose raw technical ids as primary labels. | Keep money snapshot and role-denial regression. |
| Display order number | Human `Заказ №<display_number>` is used on Guest/Venue/Bot surfaces where present. | Human display number is primary for operators and guests; raw DB ids stay internal/secondary. | No DB uniqueness constraint for display number is documented; keep venue-local day semantics explicit. |
| Staff-call separation | `STAFF_CALL` is separate from support/chat. Bill request is stored through staff-call context but remains order/tab-scoped and deduped. | Staff calls are operational events tied to current table/session/order context where available. | CANCELLED UI/lifecycle and row-level ACK/DONE actor columns remain future where docs already say so. |
| Fallback chat order | Mini App fallback emits `cmd=start_quick_order` with `table_token`; real Telegram fallback remains release smoke. | Bot fallback must create/use the same table session/tab rules as Mini App. | Keep fallback smoke whenever bot order fallback changes. |
| Visit history foundation | Booking/order/table-session close signals exist; Growth docs depend on this foundation. | `VISIT` can be derived from closed table sessions/orders and booking seated/no-show. | Dedicated `VISIT` entity/history rules remain future unless implemented by a later task. |

## Guest UX

- In table context, guest is inside an active `TABLE_SESSION`.
- `Мой заказ` / `Мой счёт` shows the guest's personal tab or joined shared tab, not every guest's personal bill at the physical table.
- `Дозаказать` creates a new `ORDER_BATCH` in the current active order/session and current selected tab.
- Growth `Повторить как шаблон` must not create an order without active table context, selected tab and current menu/stop-list validation.
- After table session expiry/close or user-scoped exit, the guest must scan the table QR again to re-enter.
- If the session is expired or unavailable, guest copy should be safe: `Отсканируйте QR на столе заново.`

## Venue / Staff UX

- Canonical Venue Mode operations model: `docs/VENUE_OPERATIONS.md`.
- Venue queue may group active work by table for operations.
- Order detail must show batches/doporders and preserve session boundaries.
- Bill detail must show tabs/accounts and service charges.
- Staff can process operational order/batch/call statuses according to role, but cannot access unrelated support/venue chats.
- Closing table/visit/order is an important operational action. Force close should require reason and audit if implemented.
- Human display order number should be primary; raw DB id is not staff-facing copy.
- Staff chat notifications can mirror order/call activity but Venue Mode remains the source of truth.

## Split Bill Rules

- Personal tab is default for each guest.
- Shared tab requires explicit invite/consent.
- Guest cannot add to another guest's personal tab.
- Guest can add a batch only to own personal tab or a shared tab they joined.
- Transfer item to another personal tab requires explicit confirmation and permission.
- Shared tab membership is required for read/write.
- Closed/paid tab cannot receive new batches unless an allowed role reopens it with audit.

## Target State Machines

`TABLE_SESSION`:
- `active -> expired`
- `active -> closed_by_guest` where product allows user-level exit
- `active -> closed_by_staff`
- `expired/closed -> immutable for new orders`

`ACTIVE_TABLE_ORDER`:
- `active -> closing_requested` optional
- `active -> closed`
- `active -> force_closed`
- `closed -> immutable`

`TAB`:
- `open -> bill_requested -> paid -> closed`
- `open -> closed` when no items or staff action allows it
- `closed -> immutable unless reopened by allowed role with audit`

`ORDER_BATCH`:
- `new -> accepted -> preparing -> delivering -> delivered`
- `new -> rejected`
- `new -> cancelled`
- `accepted/preparing -> cancelled` only by staff/manager policy if allowed

## Security And Privacy

- Canonical Security/RBAC model: `docs/SECURITY_RBAC_MATRIX.md`.
- Guest cannot read another guest's personal tab.
- Guest cannot add a batch to a tab where they are not owner/member.
- Venue users access only their own venue orders/sessions/tabs.
- Staff sees operational orders/calls only according to role.
- Platform does not need ordinary order detail by default unless support/audit policy explicitly allows it.
- Telegram `callback_data` must use opaque ids/tokens and must not include raw sensitive data.
- `table_token` and tab invite tokens are pointers, not authorities.
- Rate-limit batch creation, staff call creation and tab invites.
- Do not expose raw Telegram payloads, initData, secrets, provider payloads or unrelated PII in order/session/tab docs, DTOs, logs or analytics.

## Analytics And Dependencies

Growth/retention depends on this core:
- Visit history depends on reliable `TABLE_SESSION` / order close.
- Post-visit feedback trigger depends on confirmed visit/closed order.
- Repeat template depends on order history and current availability recheck.
- Loyalty/preorder depends on reliable `visit_count`.

Needed server events for analytics:
- `table_session_started`
- `table_session_closed`
- `order_batch_created`
- `order_batch_status_changed`
- `checkout_failed_out_of_stock`
- `tab_bill_requested`
- `tab_paid`
- `tab_closed`
- `order_closed`
- `booking_seated`
- `booking_no_show`

Use the canonical event envelope, naming convention and privacy rules from `docs/ANALYTICS_EVENTS.md`; do not add raw order notes, Telegram payloads, initData or payment data to analytics events.

## Roadmap Status

- Order/session/tab core spec: `UPDATED`.
- Runtime active-order table-only risk: documented as closed in current audit/roadmap; keep in regression.
- Remaining runtime status: `PARTIAL` for visit entity/history, staff force-close policy/audit, some DB-level uniqueness nuances and broader analytics events.
- Growth dependencies remain blocked by order/session/tab stability until visit history, repeat template, feedback and loyalty/preorder have their own implementation evidence.
- Do not mark Growth, loyalty, preorder or feedback ready until visit foundation is stable.

## Manual Smoke Checklist

1. Guest scans QR and receives table context / `tableSessionId`.
2. Personal tab is created or resolved for that guest.
3. Guest sends first batch.
4. Guest sends second batch; same active order/session, new batch.
5. Second guest scans the same table and receives own personal tab.
6. Second guest cannot see first guest personal tab.
7. Shared tab invite/join requires explicit consent.
8. Guest can add batch only to own personal tab or joined shared tab.
9. Venue queue shows table with batches.
10. Venue detail shows batches and tabs.
11. Staff changes batch status.
12. Guest active order view is scoped to selected tab.
13. Staff chat receives order notification only and is not the source of truth.
14. Close/expire session prevents new order into old active order.
15. Re-scan after close creates or uses the expected new session.
16. Fallback chat order creates batch with the same session/tab rules.
17. Stop-list change before submit blocks unavailable item/option.
