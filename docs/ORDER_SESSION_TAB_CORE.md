# Order / Session / Tab Core Model


F2 correction / LOCAL REVIEW PENDING: завершённый tab определяется только tab.status=CLOSED.
CLOSED order не закрывает ACTIVE tab для целей статистики. Дозаказы анализируются по отдельным tabId,
с завершённой когортой «Завершённые счета в заказах, закрытых в период» по orders.closed_at.
Достоверной даты закрытия tab нет; её не восстанавливают из order. ACTIVE tabs — отдельный snapshot,
CLOSED tab незакрытого order вне этой когорты. В схеме TAB только ACTIVE/CLOSED, PAID не существует.


## Рабочая отправка и когорта закрытия — локальное расширение

HT-VENUE-OPERATIONS-STATS-UX-01 / REVIEW PENDING: UI называет отправки «Первый заказ» и «Дозаказ»
внутри конкретного личного/общего tab. Монитор ведёт задачи по batch ID, а deep link выделяет
точную отправку; открытие не принимает её. Два личных счёта одной посадки не объединяются.
Read model выполненных дозаказов использует (orderId,tabId), подтверждённую выдачу и closed order cohort.
Nullable orders.closed_at (PG V139 / H2 V140) записывается существующим close-writer в его транзакции
из того же server instant, который вычисляется после блокировок и используется для действия;
исторические закрытия остаются unknown. Ни lifecycle, ownership, writer оплаты, ни D1–D4 не меняются.


## Analytics stage 2 consumers

Read-only KPI consumers use stage-1 domain first times and transition facts. Created orders and
created batches are separate units; distinct accepted/delivered/rejected batches use occurrence
windows. Creation-cohort acceptance/delivery rates share the same batch denominator. Rejection
does not erase acceptance; CLOSED is not delivery/payment. Legacy first-ever acceptance remains
unknown. Top item names use immutable snapshots and disclose current exclusion-state semantics.
No writer/workflow/checkout change. Versioned API/formulas: `ANALYTICS_EVENTS.md`.


## Confirmed transition facts — analytics foundation

`HT-ANALYTICS-FOUNDATION-IMPLEMENT-01`: LOCAL IMPLEMENTED / INDEPENDENT REVIEW PASS.
D1 is APPROVED: first accepted order is the first acceptance of any batch; append batches do not
reset it. D4 is APPROVED: distinct registered `table_session_id` is a physical seating, including
offline; MOVE is not a new seating. Guest History/unique persons are outside this counter.

`VenueOrdersRepository` writes actual batch from/to facts, first confirmed times and compatible
analytics in the state transaction. Single updates affect only that batch. Bulk and whole-order
updates record each changed batch; rejecting/closing never fabricates delivery or payment.
No-op/replay emits no fact. Domain transition sequences preserve real repeated transitions;
the operational workflow, checkout, accounting, loyalty and receipt rules remain unchanged.
PG V138/H2 V139 leave old history unknown. Incomplete legacy objects retain that marker after
new known transitions. See `ANALYTICS_EVENTS.md` for the approved definitions, version 2 payload,
legacy workflow-field compatibility and honest first-observation interpretation.


Loyalty boundary (`HT-GROWTH-LOYALTY-PRODUCTIZE-002/003`): CLOSED alone never earns.
Only fully settled, undisputed digital sales qualify fulfilled, attributed snapshot-priced units.
Checkout reserves; explicit confirmed delivery applies. One active reward slot belongs to
program + guest + table session across batches/orders. OWNER/MANAGER can compensate one proven
paid-unit id/index without changing quantities, prices, receipts or historical bills. An affected
RESERVED/APPLIED promise keeps its discount as venue-funded; only actual issue creates a noncash
expense fact. Canceling unissued compensation frees the slot without restoring invalid entitlement;
other paid units and future rewards stay intact. All dependent facts/audit commit atomically under
the existing venue-first locks. See `GROWTH_RETENTION.md`; no second checkout/order lifecycle.

Evidence reconciliation (2026-11-09): PR #252 is in main `8292696…` and reported staging V132.
Normal recorded seating RELEASE (`SeatingRepository.release`) refuses active orders/calls, ends
the physical session, closes active tabs and removes their invites. It neither records payment
nor writes off debt; Guest exit remains user-scoped. `ORDER-SESSION-CLOSE-POLICY-001` now concerns
exceptional force-close/override and legacy handling; `ORDER-TAB-LIFECYCLE-001` retains unresolved
settlement/reopen/member policy. New physical-release live acceptance remains open; see
[roadmap Section 13.0](UPDATED_PRODUCT_AI_ROADMAP.md#130-current-evidence-boundary--2026-11-09).


Bot free-text quick checkout freezes the QR token, session and tab when the dialog starts and
persists one request UUID with the confirmed text. Confirmation does not resolve a replacement
session/tab. Ordinary and confirmed Platform Guest writers use the existing atomic checkout:
venue → table/token → exact active session/tab/member → order/batch/idempotency. A moved/released
context, closed tab or rotated token rejects before writes; it never joins the next company.
CHAT source and author are retained. Exact replay creates no additional batch/notification.
An older unfinished dialog without frozen context must restart from QR; database uncertainty
retains the original request for retry. The first successful checkout also opens the accounting
shift in that transaction if none is OPEN; a rejected context or exact replay does not open one.

## Physical seating and movement (HT-HALLS-SEATING-TABLE-BOOKING-01)

`table_seatings` records explicit physical occupancy of an existing physical table; it is not a
second table registry and not a financial event. It references the canonical visit, table_session
and optional booking. QR scan, GET, screen entry and personal-tab creation do not themselves record
physical arrival. Unresolved legacy sessions/orders are shown as RECONCILE instead of assumed free.
A staff reconciliation reuses only a proven session/visit identity. For a booking, an existing
booking-to-visit link must match both session and guest (including nullable offline identity).
Otherwise exactly one unlinked visit in that session must match the booking's non-null verified
Telegram guest. Matching table/time or two NULL guests is insufficient. Foreign/ambiguous visits
remain «Требует сверки»; reconciliation must not rewrite their history.

Explicit seating uses an active session whose technical expiry is kept beyond ordinary QR TTL;
this is not a promised guest visit duration. `agreed_end_at` is separate and may be NULL. Expiry
cleanup/ordinary closeSession cannot end a currently recorded physical seating. The table remains
occupied after its agreed end and requires staff release or a conflict-checked extension.

Movement locks the venue, policy and sorted source/destination tables before session/tab/order.
It retains seating/visit/session/order/tab IDs and writes `table_seating_moves`. It updates the
session's current table, ACTIVE orders' operational table reference and NEW/ACK staff calls.
An active call keeps its ID, author, session and original creation history; each committed move
adds `STAFF_CALL_MOVED` audit with the real initiator and old/new table. DONE calls are unchanged.
Call completion and movement serialize on the same venue row, so a completed call retains the
location at completion; no duplicate call or delivery success is synthesized. Closed order references,
accounting sales/receipts and final report snapshots are unchanged. The former table/session pair
fails subsequent context validation. Scanning the old table starts a different session; it cannot
rejoin the moved company's tab/order/conversation. Own historical visits remain user-scoped.

Release is separate from payment. ACTIVE orders must finish through their existing workflow, and
NEW/ACK staff calls must resolve first. Release then ends the session and active tabs, invalidates
tab invites and records release. It does not mark debt paid, delete orders or modify closed reports.
The next company gets a new session and no access to old user-scoped history or bill.

Lock order for seating, booking and relevant Bot/Mini App order paths is venue (PostgreSQL
NO KEY UPDATE), policy, current membership/booking, sorted tables, session, tabs, orders. The venue
lock precedes financial work and avoids the former table/session -> venue inversion. Independent
venues use distinct row locks. Session-bound staff-call creation (Mini App and Bot, including
BILL) validates and locks venue → active table → exact active, unexpired session in its insertion
transaction. Completion/acknowledgement also takes the venue lock before the call. A release that
wins first prevents insertion; a call that wins first prevents release until resolved. Rejection
rolls back the call and cannot produce a successful notification. Existing BILL deduplication and
seating command replay contracts remain unchanged. Physical operations never open an accounting
shift. Auto-opening belongs only to a successful manual sale or digital checkout and shares the
venue-first lock order with physical operations.
For a staff-recorded seating, staff extend the agreed end on the floor after checking
the next booking. The guest has no paid technical-session extension offer. Creating a new
request or approving an outstanding one for this seating leaves the bill and agreed end intact.
The internal open-ended expiry stays out of guest quotes. Registered-seating analytics count
the physical start and table release records the actual end. Offline/walk-in physical visits retain NULL user identity; personal
history remains scoped to verified order participants. Feedback scheduling excludes offline visits.

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
- Tokenless Mini App startup restores an authenticated active visit without updating its expiry.
  A closed order does not end an active physical session. The read requires an active tab
  membership or an eligible confirmed Bot visit and checks table, token, venue, subscription and
  the guest's exit marker. Controlled Platform Guest requires its exact confirmed Bot binding.
  A saved device pointer is verified by token and session. A rejected pointer does not select
  another table. Without a pointer, the most recent eligible Bot confirmation for this actor
  takes priority. Tied confirmations for different visits or several eligible memberships
  without a Bot selection leave the table unselected. Automatic reads of staff-call status,
  tabs, active orders and extension options preserve both session timestamps. A guest action
  such as a confirmed QR scan can still renew the session.
- A temporary restore failure retains the exact visit and the local cart for retry. A confirmed
  exit removes the matching user/token/session pointers and draft. A later confirmed visit
  can then be discovered independently.
- The server identifies a guest's participation by authenticated actor, physical session and
  `visitGeneration`. The initial generation is the decimal string `"0"`. Only a successful
  rejoin advances it to the consumed exit event ID. Bot and Mini App take the same session lock
  and commit consumption and generation together. Resolve and restore return this value only
  for an authorized active participation. Reads leave the generation and expiry unchanged.
- A Mini App exit sends `expectedVisitGeneration`. The server checks it under the session lock
  before changing the visit or its bills. A stale exit returns HTTP 409
  (`VISIT_GENERATION_CHANGED`). A repeated end for the same generation keeps its first exit event.
  Preview and add-batch carry the same expected version. The server checks the active actor
  and version under the physical session lock. Price, signed gift decision and request
  fingerprints are scoped to participation. An exact authorized replay returns the stored batch.
  Versionless new commands require a client update (HTTP 428). Previously shipped preview
  screens display a generic calculation error until the guest closes and reopens the app.
  Reads retain their access checks. Opening a link or
  observing an active session does not acknowledge a rejoin or cancel a pending exit.
- Within one account and origin, each explicit scan or Bot entry registers a new choice in the
  shared IndexedDB store. Only that choice can publish its authenticated result. Retrying
  keeps its identity. Passive launch, reload, and focus take a snapshot before requesting the
  server and recheck the affected session before publishing a reply. Choosing B does not
  prevent cleanup of a definitively rejected A. A later confirmation at A protects its draft.
  A cold window records its start before opening IndexedDB. If a newer choice registered in
  the meantime or the readings are indistinguishable, the cold window asks for a new scan.
  Coordination covers windows sharing the same website data and browser clock.
- A cart key contains the account, table token, physical session and server generation, including
  generation zero. Reopening the same participation restores its draft. An exit followed by a
  confirmed rejoin starts with an empty cart even when either HTTP response was lost. Drafts
  created before generation tracking remain untouched under their historical local keys.
  Their original participation cannot be verified, so the updated client does not import them.
  This transition has no separate UI. The basket stays on the device.
- Exact restore can return `context: null` with `unavailableReason: "SUBSCRIPTION_BLOCKED"`
  when participation is still authorized but service is suspended. Ordering stays disabled.
  Restore responses carry `visitProtocol: 1`. An unmarked null response from an older server
  leaves the cart and pointer intact and offers retry. The sole local store for an active cart
  is the existing IndexedDB object store. Each cart mutation merges its changes in a read/write
  transaction. Independent changes and quantity deltas combine. Conflicting replacements
  require a guest decision. Versioned LocalStorage drafts migrate on first access. The original
  survives until commit. Historical unversioned drafts stay archived. If a write fails, edits
  remain in the living WebView for retry. Destroying that WebView can lose uncommitted edits.
  Exit invalidates the exact generation. Same-origin peers use IndexedDB and an exit signal.
  Isolated storage or unavailable messaging can prevent immediate peer notification. The server
  still rejects commands for a retired participation. Current accessible bills and explicit
  choices are checked again within the next generation. Bot carts, choices and quick-order
  confirmations likewise belong to one actor, physical session and generation.
  A definitive exit or rejection affects only its participation. Account switching selects the
  new account's storage and invalidates outstanding requests.

### Guest participation commands

A participation is identified by the authenticated guest, venue, table, physical session and server-issued generation. Venue pause and a blocked subscription suspend service. Exact verification of an authorized active participation preserves its basket until service resumes. Expiry, exit and lost access invalidate that participation. A missing protocol marker on an older server leaves the outcome unknown.

| Guest action | Target and authority | Atomic effect and handling of a lost reply |
| --- | --- | --- |
| Open or resume the Mini App | Exact pointer or authorized discovery. Platform Guest requires the confirmed Bot binding. | Verify participation without extending its lifetime. Recheck after a temporary error. |
| Edit the Mini App basket | Account, table and generation in the existing IndexedDB store. | One transaction per edit. A variant change consumes the original line and writes its replacement together. Incompatible edits require an explicit choice. Following a failed write, the accepted draft stays in memory and editing pauses until retry. |
| Preview and submit a batch | Server checks membership of the selected bill, physical session and generation. Pricing fingerprint and business contents belong to that submission. | Before sending, IndexedDB commits the immutable payload, bill, key and submitted quantities. An explicit retry sends the same operation. Opening the app sends nothing. A committed key returns the original batch after a bill membership change. An authoritative membership, menu or gift refusal closes the same key in the order ledger without a batch. Only a committed outcome releases the local reservation. Temporary failures, ordinary conflicts and unverifiable legacy results keep the immutable request for review or exact retry. Success removes submitted quantities and leaves new items available. |
| Create a personal or shared bill, issue an invitation or join | Session and generation, authenticated guest, scoped invitation or bill owner as applicable. | Resolve the short code to an invitation ID and bill before confirmation. Join checks that exact instance, expiry, bill, guest and participation in the membership transaction. After an unknown result, reload accessible bills. |
| Request payment | Session, generation, bill membership, specific active order and payment method. | Lock the order and create or return its open payment request. A subsequent order needs its own request. |
| Call staff or request a shift extension | Staff call belongs to the table and participation. An extension and a payment call also target a bill and order. | Recheck authority before writing. Check staff-call status after a lost reply. Mini App prepares extension requests in IndexedDB. Each new extension needs a nonblank idempotency key and confirmation of the specific bill, order, duration, price, currency and tariff revision. The request transaction locks the venue settings and compares the quote. A stale quote changes neither the request ledger nor visit timing. The server binds accepted keys to one request, including coalesced requests with matching agreed terms. A retry returns the recorded status and terms. Approval locks the venue and physical session, checks that the session, bill and order are active and counts approvals across all bills against the current session limit. Each accepted decision adds its saved minutes to the current deadline and posts one saved-price charge to its original bill in the same transaction. A repeat returns the decision. The Mini App displays confirmation against the matching order. |
| Use a Bot action or enter free text | The Bot addresses the action to the actor, chat and original participation. Order-specific actions include the bill and order. Prepared quick orders retain their request ID. | An old unaddressed button offers a fresh confirmation. Checkout records the original request in PostgreSQL before submission. Retry uses the same key and contents after a restart. Draft buttons include the bill and draft revision. A committed rejection restores eligible lines and lists unavailable choices. A successful prepared checkout atomically queues an internal staff handoff and the guest receipt before deleting the preparation. Mini App checkout, quick text orders and direct Bot reorders stage the same staff handoff within the batch transaction for ordinary and controlled Platform Guests. The initial checkout key replays the committed batch. A venue without a bound staff chat can still take orders. Retired Bot preparations with a committed batch remain recoverable through the outbox worker, using their original chat, actor, venue and batch. A recovered receipt carries no table keyboard. New unsent draft edits stay in the live Bot process. A direct reorder confirms the item, bill and active order. The SQL writer rejects an order change. Web App Data names the original session, generation and bill for quick order. An unbound ordinary Guest can bind that active participation and open the addressed dialog in one transaction after bill membership is confirmed. Platform Guest uses the existing controlled binding. Legacy write commands direct the guest to the current menu. Guest-entered shared-bill codes and comments require a current confirmation. |
| End and rejoin | Expected generation on exit. Rejoin consumes the specific outstanding exit event. | The exit and checks commit together. Late Bot cleanup addresses the ended participation. A sessionless binding can be cleaned by exact actor, token and binding timestamp. The Bot checks for a fresh binding before queuing an exit keyboard and again before dispatch. Messages already accepted by Telegram can still arrive. |
| Create, reply to, resolve or reopen a Platform Guest support ticket at a table | Authenticated Platform Guest, confirmed Bot binding, physical session, generation and verified order reference when supplied. Replies and status changes also address the ticket and expected generation. | Recheck the ticket, binding and participation in the same transaction as the write. Support tickets without a table context follow their own access rules. |

Incomplete writes from an older Mini App return HTTP 428 before mutation. Read access follows existing authorization. A previously completed add-batch can be replayed when its original key and contents are verifiable. General staff calls and the creation of shared bills have no durable client retry key. Check current state before issuing another action.

- Ordinary Guest Mini App `/api/guest/table/resolve` with `available=false` does not create or touch table
  sessions, create personal tabs/memberships, clear user exit markers or emit session-start events. An
  explicitly requested session may be read within the resolved venue/table scope; its status does not override
  unavailable venue/subscription access. Without an explicit session, an unavailable resolve returns the
  existing missing-session representation (`0`, `UNKNOWN`, `TABLE_SESSION_MISSING`), including
  `resolveMode=create`. An available first QR entry may reuse or create the physical session and personal tab.
  Once this actor has exited, GET (including legacy `resolveMode=create`), restore, reload and focus
  leave the exit in force. A scan without a session ID returns `tableSessionActive=false`,
  `TABLE_SESSION_EXITED` and the current `exitEventId` as a decimal string. After the guest confirms,
  the Mini App sends authenticated `POST /api/guest/table/session/rejoin` with `tableToken`,
  `tableSessionId` and `exitEventId`. The server locks the session, verifies current availability
  and consumes the matching actor/session/event in the same transaction. A guest with no bill can
  also resume a previously confirmed Bot visit. On exit the Bot removes the token from its server-owned
  chat binding. Only that actor’s matching chat, table, physical session and current exit event can
  reactivate it. This restores the visit without creating a tab or granting access to existing bills.
  Exact table reads, bill lists and active-order reads leave the bill list unchanged. When the guest has no personal bill, «Открыть личный счёт»
  calls `POST /api/guest/tabs/personal`. An ordinary guest needs a current membership in that
  session or a confirmed Bot visit at that table. An exit blocks the operation. Repeating it
  returns the same personal bill. Checkout on a SHARED bill uses its selected membership and leaves the set of bills unchanged. A revoked membership cannot be restored by opening the bill.
  A guest with a confirmed Bot visit can also end it in the Mini App before opening a bill.
  The Mini App clears the visit only after the server confirms the exit. On refusal, the guest checks the current table in the Bot menu before ending a visit there.
  After a lost SDK rejoin response, the guest can explicitly retry. The Mini App first verifies
  the original physical session read-only, including after a reload in the same WebView. That response can supply a fresh challenge for the
  same session and requires a new confirmation. A replacement session requires a new scan.
  If the chat has moved to another table and the guest has no bill membership at the old one,
  the Bot explains the loss of access and offers no rejoin button.
  Success returns the active resolve response. A consumed or superseded event returns HTTP 409
  (`TABLE_REJOIN_STALE`); the guest can scan again for a fresh confirmation. Older Mini Apps can
  reopen the updated scanner or use the table QR through the ordinary Telegram Bot /start flow.
  After an exit the Bot shows a
  one-time confirmation bound to the actor, chat, table and current exit event. Legacy web_app_data
  leaves the exit in force. Platform controlled QR also binds its existing prompt to the observed event. Disabled tables/revoked tokens and unpublished venues retain their existing not-found
  response.
- For exact Platform Owner, showing a QR confirmation reads subscription status without creating a trial.
  A missing subscription after exit blocks rejoin. Only an authorized initial admission can provision the usual trial.
- For exact Platform Owner, a table token creates no context/session until explicit controlled confirmation. The five-minute process-local opaque pending is not session state or authority and is lost safely on restart. Phase 1 uses single-instance long-polling; a callback missing on another instance fails closed.
- Ordinary and controlled Bot QR confirmation persist the selected physical session in `telegram_chat_context.table_session_id` in the transaction that locks the table and session. Legacy chat rows keep a null session ID until the next explicit QR confirmation. They can serve informational Bot screens. Moving a different session onto the same seat grants no authority to the old chat.
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
- Mini App persists the submitted payload and key before sending. After a lost response, the guest
  can explicitly retry that operation in a new WebView. Edits and price refreshes leave the pending
  payload intact. Confirmation subtracts its reserved quantities and preserves subsequent edits.
  A payload mismatch or unverifiable legacy replay preserves the reservation and allows active-order
  review. A confirmed success or durably closed refusal releases the reservation. Each deliberate
  new batch gets a fresh key, including a batch with identical contents.
- A legacy idempotency row with a `NULL` fingerprint is reconstructed only from unambiguous immutable
  committed facts. Missing option identity or another required component fails closed as
  `ORDER_IDEMPOTENCY_REPLAY_UNVERIFIABLE`; multiple physical legacy rows in one logical session/key
  namespace are likewise unverifiable. Mutable menu names/prices are never used to guess identity.
- The fingerprint column stores `v1:<sha256>` and remains scoped to the table session. For
  durably rejected checkout keys, `response_snapshot` stores the structured refusal, including
  actionable menu errors when available. A legacy rejection row with an empty snapshot represents
  the earlier shared-bill denial. Existing successful batch receipts retain their original format.
- Fee settings use their existing monotonic `updated_at` revision. A quote that was rejected after
  a tariff change stays obsolete if the previous price returns. An accepted key uses the saved
  amount, duration, currency and exact confirmed revision even after tariff changes.
  V147 records the revision per accepted key, including aliases. Older keys with NULL revision
  can replay the fields actually recorded. The original key on an older extension
  request remains an authorized read-only replay through its guest, venue, session, bill, order and
  stored comment. Its historical participation generation is unknown, so it is kept in the original
  row. Additional historical keys never recorded by a writer cannot be recovered.
- The dev-only operational reset locks the selected venue before checkout. It ends its active
  physical sessions, closes their bill access, releases recorded seatings and removes Bot
  preparations and operational order history in one transaction. Old commands retain the
  retired physical session even if supplied a new order ID. The next explicit QR entry creates
  a fresh session. Other venues retain their sessions, preparations and orders.
- The existing outbox records the staff handoff by batch before the order commits. Its worker
  verifies the original recipient and active handoff while claiming the batch and queuing its
  Telegram message. Rebinding cancels pending work, including a rebind back to the initial
  chat. External dispatch locks the claimed row through the Bot API call. Telegram may already
  have accepted a message before relinking or loss of its acknowledgement. The staff message
  shows stored item amounts, currency, applied discounts and the batch total when available. Guest receipts are deduplicated by batch. A queued row records local
  responsibility. Telegram delivery follows its usual retry and failure rules.
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

Bot quick-order confirmation/edit/cancel buttons identify the saved request UUID. A confirmed
checkout rejection finishes only that request and clears its dialog payload before reporting
the refusal. An old button cannot execute the request after MOVE back or a new seating, nor act
on a newer dialog. A new order starts through the normal flow with a fresh UUID and current
QR/session/tab. An unknown database result retains the original text, UUID and frozen context
for exact idempotent replay. Legacy buttons without a UUID refresh the saved confirmation without
executing it. A known successful checkout finishes the dialog; repeated callbacks create no batch.

Quick-order execution and terminal transitions share the PostgreSQL dialog-row lock. Both checkout
writers acquire venue first, then lock the matching chat/request and verify its frozen text,
token/session/tab before creating a batch. They retain that lock through commit/rollback; a known
context refusal clears the request in that same transaction. A callback that read an older dialog
before waiting cannot revive a refused, cancelled or edited request after MOVE back or new seating.
The existing Platform Guest coordinator retains its authorization locks before entering the writer;
the writer never acquires a chat-context lock after the dialog lock.

Edit/cancel lock only the dialog row, then read committed batch idempotency in a separate statement
without taking venue/session/order locks. If checkout committed first, they refuse the change and
do not claim cancellation. Success acknowledgement clears only its matching dialog. Until
acknowledgement, the original payload stays intact for exact replay through durable idempotency,
including after response loss or process restart. There is no intermediate EXECUTING state, new
migration or process-local mutex. Failed transactions retain the original request; Telegram delivery
failure cannot reopen a request already completed in PostgreSQL.

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
| Fallback chat order | Mini App fallback emits `cmd=start_quick_order` with `table_token` and the originating `table_session_id`; real Telegram fallback remains release smoke. Canonical fallback/staff-chat rules are in `docs/TELEGRAM_FALLBACK_STAFF_CHAT.md`. | Bot fallback must create/use the same table session/tab rules as Mini App. | Keep fallback smoke whenever bot order fallback changes. |
| Visit history foundation | Guest History Foundation MVP is DONE / staging-smoke-passed: current-user list/detail shows closed-order visits and booking-only `SEATED` visits, filters non-seated booking statuses, preserves but hides legacy invalid rows, opens legacy closed order details safely and keeps privacy filters strict. | `VISIT` is derived from closed orders and booking `SEATED`, with merge/dedup where booking + order represent the same real visit. | Repeat templates, post-visit feedback, full base item historical snapshotting where still missing, loyalty/preorder `visit_count` and analytics event completeness remain follow-ups. |

## Personal-link browsing versus active visit (local candidate)

A personal venue entry selects a browsing card only. It cannot create, end, move or relink a
`table_session`, `tab`, cart, order or booking. A Guest with active visit A may browse venue B,
including B's menu, while A's server-owned context and user-scoped cart remain intact. Existing
same-venue `canPlaceOrders` and server table/session/tab membership checks remain authoritative;
no B order/staff call/extension is enabled with A's context. The UI provides an explicit return to
A and a separate general catalog exit. Reopening a personal card does not scan or apply a table QR.

## Guest order isolation consolidation — 2026-09-16

**LOCAL CANDIDATE / UNCOMMITTED / RELEASE AND STAGING GATES OPEN**.
The verified local baseline is `77caf48a46e5d3c61b01ffdfc0a43bc9ec55ceaa`, a descendant
of `4daf5546fb622a6b967398f5c25b7bed41d7fa05`. PostgreSQL V61 and H2 V112 already enforce
one active order per `table_session_id`; the historical table-only P0 remains closed in code.
This does not attest any deployed runtime or database.

Two independent candidate defects were reproduced on the newer baseline:

- Retained shared membership let an exited ordinary Guest read subsequent batches and request
  a bill. Explicit active-order reads and ordinary bill requests now use the existing authorized
  transaction coordinator, validating token/venue/table/session, exit and tab access before the
  read or mutation. Legacy personal lookup checks exit inside its transaction and rolls back
  on denial. Confirmed Platform Guest preflight is preserved.
- Tab-scoped details filtered batches and promotions but loaded service charges for the whole
  order. Charges now use both `order_id` and `tab_id`; full-order/staff reads retain all charges.

Review also reproduced an alternate disclosure through the existing Telegram active-order summary:
its promotion totals were order-wide, and retained membership/authorship bypassed exit or revoked
membership. Summary items and promotions now use the same currently authorized batch set. Active
session, no exit, active tab and membership are required for tab-backed batches; personal ownership
is checked. The existing own-author/idempotency fallback remains only for legacy batches without
any tab, under the same active-session/no-exit checks. This changes no booking or history flow.

The monetary review covers batch comments/items, option snapshots, manual discounts, exclusions,
canceled lines, promotion/loyalty adjustments, gift/reward attachments, service charges and derived
Guest totals. Item attachments are joined by authorized batch/item IDs; charge filtering closes the
remaining selected-tab attachment gap. No migration, API/DTO, Mini App or order identity change.

Exit remains user-scoped and retains shared membership: a valid explicit QR re-entry clears the exit
marker and restores existing membership; it does not restore a deleted membership. Other guests
keep their physical session. Verification is recorded in `PROJECT_STATUS.md`; the pending release
smoke is [`ORDER-CONTEXT-MANUAL-001`](DEFERRED_MANUAL_SMOKE_BACKLOG.md#order-context-manual-001).

## Guest UX

- In table context, guest is inside an active `TABLE_SESSION`.
- Mini App scanner accepts the generated HTTPS `t.me/<bot>?start=` form, existing `startapp`, same-origin Mini App token URLs and raw base64url tokens. URL parameters are decoded once; duplicate/conflicting tokens, foreign origins and unsupported routes are rejected. When launch context includes a bot username it must match; otherwise Telegram bot-link recognition is structural and backend token validation remains mandatory.
- QR scanning inside Mini App and phone-camera `/start` entry use the same backend table resolution. Scanner cancellation, malformed/unsupported QR and rejected replacement tokens preserve the previous working context; successful scans cannot grant access without backend validation. If a scan cancels an unfinished restore, rejection returns to the last stable state (initially no table), not an abandoned loading state; another scan remains available. Explicit visit exit invalidates late responses. Repeated scanner delivery is consumed once per scan.
- After ordinary Guest QR entry, the bot first removes its old reply keyboard and offers Mini App or bot ordering. Changing the interface or re-entering the same active visit preserves session/tab, cart and active order; the full bot order keyboard is shown on explicit bot choice.
- Existing bot and Mini App selected-tab state remains local to each interface. This change preserves each selection and server-side tab membership; it does not add a cross-client shared-tab selection synchronization contract.
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

## Accounting shift attachment (local candidate)

`orders.accounting_shift_id` attaches an existing order to one venue accounting period without
changing `table_session`, `order_batch` or personal/shared `tab` ownership. The sole order creator
sets the reference under the venue lock; the first authorized checkout creates the shift atomically
if absent, using the actual initiator for AUTO_OPEN audit. Both checkout transaction overloads
acquire the venue lock before table/session/tab, idempotency and menu/order locks, matching
Mini App authorization and Bot checkout even when appending to an existing order. PostgreSQL
NO KEY UPDATE remains compatible with FK checks; accounting closure uses that same venue lock.
Existing shift opening can adopt
unassigned ACTIVE orders. Exact replays return their original order before any new opening. Old
closed orders are left untouched. Canonical bill snapshots remain the source for digital estimates.
Payment recording requires operationally CLOSED orders; close itself never implies payment.
The accounting journal stores explicit external receipt/correction facts and a frozen shift report,
not another ordering engine. Manual sales create no order, guest, visit, tab or notification.
Journal discounts/gifts are explicitly separate from the original guest bill. See
`docs/VENUE_OPERATIONS.md` for the exact settlement, correction and closure prerequisites.

Manual accounting entry creates only an independent accounting sale, never a guest/session/tab,
order or kitchen/staff-chat work. A structured Bot checkout and Mini App add-batch share the
existing order identity and its accounting shift; their projection is `order:<id>`. PAY (or one atomic PAY_SPLIT for multiple methods) is a
separate operator confirmation. Split components do not create additional sales, orders or tabs;
loyalty reconciles only after the complete logical payment result. Text fallback without trustworthy items/prices retains that
order identity and requires reconciliation; it is never inferred from a similar menu name or
represented as an additional manual purchase. Journal percentage corrections snapshot a monetary
discount and do not rewrite the original guest bill.

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
