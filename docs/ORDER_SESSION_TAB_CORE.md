# Order / Session / Tab Core Model

Дата актуализации: 2026-08-12.

Статус: **current product reference / SPEC UPDATED**. Этот документ фиксирует product model для QR table context, active table order, order batches, personal/shared tabs, bill/request/close flow, visit-history foundation and privacy boundaries. Guest stale-menu cart recovery is **GUEST CART STALE MENU SELECTION RECOVERY / REMOVED OR UNAVAILABLE ITEMS AND OPTIONS / PAYLOAD-BOUND IDEMPOTENCY + ATOMIC REJECTION / DONE / MVP / STAGING-SMOKE-PASSED**; its item-level action and copy polish is **DONE / MVP / STAGING-SMOKE-PASSED**. Platform test status is **PLATFORM OWNER CONTROLLED GUEST QR TEST ESCAPE / DONE / MVP / STAGING-SMOKE-PASSED**; the bounded Telegram/session/privacy smoke is complete. Runtime status is mixed: the old table-only active-order risk and Guest History Foundation MVP are documented as closed in current audit notes, while force-close policy, some DB-level uniqueness nuances, repeat/feedback/loyalty/preorder and broader analytics remain future/partial.

The adjacent mutation status is **DANGEROUS ACTION AUDIT SLICE / MENU ITEM AVAILABILITY AUDIT /
DONE / MVP / STAGING-SMOKE-PASSED**; it preserves every
Guest order/session/tab and historical snapshot invariant in this document.

Analytics/event semantics for this core are defined in `docs/ANALYTICS_EVENTS.md`. Role, scope and trust-boundary decisions are defined in `docs/SECURITY_RBAC_MATRIX.md`. Structured menu, option/modifier and stop-list rules are defined in `docs/MENU_OPTIONS_STOPLIST.md`. Venue operational surfaces are defined in `docs/VENUE_OPERATIONS.md`. Booking seated/no-show lifecycle inputs are defined in `docs/BOOKING_LIFECYCLE.md`. Telegram fallback order and staff-chat behavior are defined in `docs/TELEGRAM_FALLBACK_STAFF_CHAT.md`. Testing/smoke strategy is defined in `docs/TESTING_QA_SMOKE_STRATEGY.md`. Release/deploy and incident operations are defined in `docs/DEPLOYMENT_RUNBOOK.md`.

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
| `VISIT` | Product-level history/retention concept derived from `TABLE_SESSION` + closed order + booking `SEATED` signals. `CANCELED`, `NO_SHOW`, `EXPIRED`, `PENDING` and `CHANGED` bookings are not visits. Booking lifecycle source: `docs/BOOKING_LIFECYCLE.md`. |

## Core Invariants

- QR/table token sets context, not permissions.
- For exact Platform Owner, a table token creates no context/session until explicit controlled confirmation. The five-minute process-local opaque pending is not session state or authority and is lost safely on restart. Phase 1 uses single-instance long-polling; a callback missing on another instance fails closed.
- Confirmed Platform Guest activation is all-or-nothing: final token/venue/table/availability/subscription validation, session resolve/create/touch, exit-marker clear, persisted Guest-dialog clear and exact chat-context save share one JDBC transaction. In-memory cart/draft cleanup follows commit only.
- Exact Platform Owner Mini App create/touch or explicit-session resolve requires a matching active server-owned Telegram chat context and no user-exit marker. Client token, session id, `mode=guest` and Platform role are never sufficient authority.
- All permissions and ownership checks are server-side.
- Active orders must not mix different `table_session_id` values.
- Guest active order view is scoped by `tableSessionId` + selected/current `tabId`.
- Venue queue may group by physical table, but order detail must preserve session, batches and tabs.
- One physical table can have sequential visits; old visit/order state must not leak into a new visit.
- Guest Mini App batch idempotency is scoped to `tableSessionId + idempotencyKey`; the same key in a
  different table session is an independent operation. Within one session, actor, venue, tab,
  normalized comment and canonical normalized lines are payload-bound by a versioned server SHA-256
  fingerprint.
- Exact replay returns the already committed batch before current menu/gift validation and produces
  no duplicate order, batch, analytics, outbox or staff notification. Reuse with a different
  fingerprint fails closed as `ORDER_IDEMPOTENCY_PAYLOAD_MISMATCH`.
- Mini App keeps the key for an exact in-screen network retry, rotates it after business or
  account/venue/table-session/tab mutation, and ignores server-only price/availability/pricing-
  fingerprint changes. Mismatch keeps the cart and creates a new key only on explicit retry;
  unverifiable legacy replay offers active-order review or an explicit new submit and never resends
  automatically. A success for an older in-flight payload acknowledges that committed order but does
  not clear or navigate away from a newer cart mutation.
- A legacy idempotency row with a `NULL` fingerprint is reconstructed only from unambiguous immutable
  committed facts. Missing option identity or another required component fails closed as
  `ORDER_IDEMPOTENCY_REPLAY_UNVERIFIABLE`; multiple physical legacy rows in one logical session/key
  namespace are likewise unverifiable. Mutable menu names/prices are never used to guess identity.
- The additive fingerprint column stores only `v1:<sha256>`; raw request/canonical JSON is not stored,
  `response_snapshot` is not repurposed, legacy rows are not bulk-backfilled and table-session scope
  is not widened by a global unique constraint.
- Item name, price, selected options/flavors and price deltas are snapshotted at order time according to `docs/MENU_OPTIONS_STOPLIST.md`.
- Removed, stop-listed or otherwise unavailable items/options are rejected by the same server-owned
  validation at preview and submit. Deterministic own-venue stale selections use typed line issues;
  foreign item/venue/option ownership remains generic and discloses no existence detail.
- Executable promotion eligibility, prices and adjustments are server-owned and recalculated at
  preview and final submit; clients never submit trusted discount amounts.
- A promotion does not mutate menu base price. Its name, rule identity/version, original amount,
  adjustment and final amount are stored as an immutable application snapshot.
- Idempotent batch replay cannot create a second promotion application/reward. Canceled, rejected
  and excluded lines receive no promotion, and a payable amount cannot become negative.
- Manual staff discount and promotion do not stack without an explicit deterministic policy.
- Personal tab is default; shared tab requires invite/consent.
- Closed/paid tabs cannot receive new batches unless an allowed role explicitly reopens them with audit.
- Staff chat is a notification/radar/shortcut surface, not the source of truth.

## Current Implementation Vs Target

| Block | Current implementation from docs/audit | Target product model | Gap / future implementation note |
| --- | --- | --- | --- |
| `TABLE_SESSION` | QR resolve and table session creation exist. Exact Platform Owner controlled test reuses this same Guest engine only after explicit confirm; prompt/cancel/audit failure create or touch no session, and activation late failures roll back session/context/exit/dialog together. Guest exit is user-scoped through `guest_table_session_exits`, uses saved context for teardown without current token/venue/table/subscription availability, and does not close a shared physical session for all guests. TTL cleanup exists. | Session represents the active venue/table visit context and has explicit close/expire/staff close semantics. | Staff force-close reason/audit and a product-level visit timeline need a dedicated future task if not already implemented. |
| Active order lookup | Current docs state the old active-order-by-`table_id` risk is closed: active order is scoped by `table_session_id`; H2 mirrors PostgreSQL active-order uniqueness. | One active `ACTIVE_TABLE_ORDER` per current `table_session_id`/visit. | Keep regression smoke for sequential visits at same table. Do not re-open without new code/smoke evidence. |
| Guest active order endpoint | Current docs state active order view uses `tableSessionId`/`tabId`, human `Заказ №...`, selected account label and selected-tab totals. | Guest sees only selected personal/shared tab context for the active session. | Keep privacy regression for two guests and shared tab membership. |
| Order batch creation | `add-batch` uses payload-bound, table-session-scoped idempotency. New rows store `v1:<sha256>`; exact replay bypasses new-operation validation, mismatch conflicts, and reconstructable legacy rows may be lazily upgraded. Selected options and line notes are snapshotted where implemented. | Every batch belongs to active order + tab and records source `miniapp` / `bot_fallback`. | PostgreSQL `V123` / H2 `V124` are additive and nullable. All order-writing instances must run the new binary before rollout closure; old writers can still create legacy `NULL` rows. Global uniqueness across sessions remains intentionally out of scope. |
| Guest stale cart recovery | Preview is read-only. Final submit locks authoritative context, session, idempotency and menu rows, validates, then touches session/ensures personal tab and creates order state in one transaction. The server returns all deterministic own-cart issues as `CART_MENU_SELECTION_UNAVAILABLE`; ordinary Guest and exact Platform Owner rejection leave the expanded persistence snapshot unchanged. | Guest sees each affected line and an exact removal/reselection action. Item recovery removes the exact line before Menu navigation or removes it in place; both paths authoritatively recalculate the remaining cart. Current menu state and prices remain authoritative. | Item copy still uses a mutable item cache, not an immutable cart name snapshot. In-place item replacement is intentionally not introduced. |
| Tab membership and visibility | Personal/shared tabs and membership checks exist; current docs note H2 mirrors active personal-tab uniqueness while PostgreSQL still permits active `PERSONAL` tabs with `owner_user_id NULL` at schema level. | Personal tab is owner-only; shared tab requires explicit join/consent; every batch has `tab_id`. | PostgreSQL nullable-owner DB nuance and shared-tab DB uniqueness remain follow-ups if product needs DB-level enforcement beyond repository idempotency. |
| Full bill | Guest/Venue/Bot bill identity parity is staging-smoked; Venue detail shows included/excluded/discount/service-charge context and human tab labels. | Bill is backend-owned, shows batches/tabs/service charges and does not expose raw technical ids as primary labels. | Keep money snapshot and role-denial regression. |
| Display order number | Human `Заказ №<display_number>` is used on Guest/Venue/Bot surfaces where present. | Human display number is primary for operators and guests; raw DB ids stay internal/secondary. | No DB uniqueness constraint for display number is documented; keep venue-local day semantics explicit. |
| Staff-call separation | `STAFF_CALL` is separate from support/chat. Bill request is stored through staff-call context but remains order/tab-scoped and deduped. | Staff calls are operational events tied to current table/session/order context where available. | CANCELLED UI/lifecycle and row-level ACK/DONE actor columns remain future where docs already say so. |
| Fallback chat order | Mini App fallback emits `cmd=start_quick_order` with `table_token`; real Telegram fallback remains release smoke. Canonical fallback/staff-chat rules are in `docs/TELEGRAM_FALLBACK_STAFF_CHAT.md`. | Bot fallback must create/use the same table session/tab rules as Mini App. | Keep fallback smoke whenever bot order fallback changes. |
| Visit history foundation | Guest History Foundation MVP is DONE / staging-smoke-passed: current-user list/detail shows closed-order visits and booking-only `SEATED` visits, filters non-seated booking statuses, preserves but hides legacy invalid rows, opens legacy closed order details safely and keeps privacy filters strict. | `VISIT` is derived from closed orders and booking `SEATED`, with merge/dedup where booking + order represent the same real visit. | Repeat templates, post-visit feedback, full base item historical snapshotting where still missing, loyalty/preorder `visit_count` and analytics event completeness remain follow-ups. |

## Guest UX

- In table context, guest is inside an active `TABLE_SESSION`.
- `Мой заказ` / `Мой счёт` shows the guest's personal tab or joined shared tab, not every guest's personal bill at the physical table.
- `Дозаказать` creates a new `ORDER_BATCH` in the current active order/session and current selected tab.
- Growth `Повторить как шаблон` must not create an order without active table context, selected tab and current menu/stop-list validation.
- `ITEM / UNAVAILABLE` explains that the exact line must be removed before choosing another item;
  `ITEM / REMOVED` explains that removal is required to continue. `Удалить и выбрать другую` removes
  only that line, waits for authoritative recalculation and opens the existing Guest menu without
  selecting a replacement. `Удалить из корзины` removes only that line, stays in cart and restores
  focus to the next line or cart heading. Other lines/issues remain and continue blocking submit.
  A stale option line keeps `Выбрать другой вариант` and its existing line-removal action, reuses the
  current item option picker and preserves quantity and preference note until a successful
  authoritative preview.
- Unknown, network, database, pricing, venue, table or session failures retain generic retry UX and
  are never inferred as stale-menu reasons by HTTP status or message text.
- The release-closed menu-item availability audit slice does not change Guest authority or order
  semantics. A disabled item still yields typed `ITEM / UNAVAILABLE`; preview remains read-only and
  stale submit writes no session/tab/order/idempotency state. Re-enable restores authoritative
  recovery. Direct availability mutation and submit serialize on the production item lock, while
  existing order item name/price snapshots remain immutable and are never rewritten by the toggle.
- Account `История` shows completed visits/orders only: closed-order visits, booking-only `SEATED` visits and merged same-real-visit records where dedup applies. Cancelled/no-show/expired/pending/changed bookings are hidden as visits.
- History detail opens the current guest's closed order detail, tolerates legacy missing optional fields such as `promotionDiscounts`, options and notes, and keeps the safe error copy `Не удалось загрузить детали истории.` for real 404/errors.
- History detail has `← Назад к истории`; Telegram BackButton inside detail returns to the History list, not app home.
- After table session expiry/close or user-scoped exit, the guest must scan the table QR again to re-enter.
- If the session is expired or unavailable, guest copy should be safe: `Отсканируйте QR на столе заново.`
- A confirmed Platform Owner test is an ordinary Guest table context: the same menu/order/staff-call/tab rules and Guest Mini App `mode=guest` apply, with no Platform privilege inside Guest APIs.
- Existing `Завершить визит` clears that actor's user-scoped context, persisted dialog, cart/draft and pending confirmation after token rotation/revoke, table disable/delete, venue pause/unpublish or subscription block. It records or preserves the user-exit marker when the linked session remains resolvable; if deletion makes that impossible, context and local Guest state are still cleared and re-entry remains fail-closed. Session detach/close is best-effort; cleanup and Platform menu restoration are not availability-dependent. No persistent impersonation/test flag or second session engine exists.
- A tokenless `/start` during an active confirmed Platform Guest context keeps the Guest table menu or shows the safe instruction to use `Завершить визит`; it does not overlay Platform menu on active Guest routing. After exit, `/start` returns to Platform Mode.

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
- Promotion evaluation remains batch/item-scoped inside the authorized tab unless a later rule
  explicitly defines a broader scope; a table/session/tab token never grants discount authority.

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
- Guest History detail is current-user scoped: foreign visit detail returns 404, a guest does not see another guest's personal tab/order detail, and a shared-tab-only member does not see чужие personal/order details.
- Guest cannot add a batch to a tab where they are not owner/member.
- Venue users access only their own venue orders/sessions/tabs.
- Staff sees operational orders/calls only according to role.
- Platform does not need ordinary order detail by default unless support/audit policy explicitly allows it.
- Telegram `callback_data` must use opaque ids/tokens and must not include raw sensitive data.
- `table_token` and tab invite tokens are pointers, not authorities.
- Platform role and opaque pending reference are not table authority. Confirm must revalidate exact actor/chat/TTL and commit safe `PLATFORM_GUEST_QR_TEST_CONFIRMED` before atomic Guest context activation. The audit means confirmation only, not `GUEST_CONTEXT_APPLIED`; activation performs the final token/published-venue/subscription/active-table/identity validation inside its transaction.
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
- `booking_no_show` for booking analytics only; no-show bookings must not be counted as visits in Guest History.

Use the canonical event envelope, naming convention and privacy rules from `docs/ANALYTICS_EVENTS.md`; do not add raw order notes, Telegram payloads, initData or payment data to analytics events.

## Roadmap Status

- Order/session/tab core spec: `UPDATED`.
- Guest cart stale menu recovery: **GUEST CART STALE MENU SELECTION RECOVERY / REMOVED OR UNAVAILABLE
  ITEMS AND OPTIONS / PAYLOAD-BOUND IDEMPOTENCY + ATOMIC REJECTION / DONE / MVP /
  STAGING-SMOKE-PASSED**; **ITEM-LEVEL ACTION AND COPY POLISH / DONE / MVP /
  STAGING-SMOKE-PASSED**. Preview/submit parity, expanded zero-write rejection, rollback and
  item-level removal/recalculation UX remain regression contracts. PostgreSQL `V123` and H2 `V124`
  add nullable `request_fingerprint` without backfill or global uniqueness.
- Runtime active-order table-only risk: documented as closed in current audit/roadmap; keep in regression.
- Guest History Foundation MVP: `DONE / STAGING-SMOKE-PASSED`; keep privacy, terminal-status filtering, legacy closed-order detail compatibility, BackButton/list return and merge/dedup behavior in regression.
- Remaining runtime status: `PARTIAL` for staff force-close policy/audit, some DB-level uniqueness nuances, repeat template, post-visit feedback, loyalty/preorder `visit_count` and broader analytics events.
- Controlled Platform Guest QR test: **PLATFORM OWNER CONTROLLED GUEST QR TEST ESCAPE / DONE / MVP / STAGING-SMOKE-PASSED**; `NO_MIGRATION`, with session/exit, old-link fail-closed, new-confirmation and ordinary-role regression covered by the bounded release evidence.
- Growth dependencies can now build on the completed History foundation, but repeat template, feedback, loyalty/preorder and promotions still need their own implementation evidence.
- Do not mark repeat, loyalty, preorder, promotions or feedback ready until each feature has code/test/smoke evidence.

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
18. New guest sees empty History state.
19. Closed order appears in History and detail shows positions/total.
20. Old closed order with missing `promotionDiscounts`, options or notes opens without crashing.
21. Booking-only `SEATED` visit can show safe no-order-lines copy.
22. `CANCELED`, `NO_SHOW`, `EXPIRED`, `PENDING` and `CHANGED` bookings do not appear as visits.
23. `← Назад к истории` and Telegram BackButton inside detail return to the History list.
24. Real 404/error shows `Не удалось загрузить детали истории.`.
25. Foreign detail returns 404, чужие personal/order details remain hidden, and booking `SEATED` + order closed does not double-count where merge/dedup applies.
26. Platform Owner valid QR prompt leaves session, exit marker, persisted dialog, booking draft, cart/draft and context unchanged before confirm; cancel, stale token and audit failure create none.
27. Late injected failure after session resolve/touch, exit clear or dialog clear rolls back session, exit, dialog and context together; the truthful confirmation audit may remain.
28. Confirmed Platform Owner uses the same Guest table session/tab/action engine and Mini App `mode=guest`; matching server-owned chat context is required, and old token/session/button after exit fails closed without touch/create/personal-tab/exit-clear side effects.
29. Exit clears context/dialog/cart/draft/pending and preserves exit semantics after token rotation/revoke, table disable/delete, venue pause/unpublish and subscription block, then returns to Platform menu.
30. Tokenless `/start` keeps Guest routing while confirmed context is active and returns to Platform Mode only after exit.
31. Ordinary Guest and Venue Owner/Manager/Staff QR/role precedence remains unchanged.
