# Analytics And Events Model

## Товары и дозаказы — локальный read model

HT-VENUE-OPERATIONS-STATS-UX-01: LOCAL IMPLEMENTED / REVIEW PENDING; D1–D4 остаются APPROVED.
`stats/v1` совместимо принимает новые section=products|additions. Прежний all не включает новые
большие товарные данные. products содержит optional ProductReport; при отсутствии он не сериализуется.
Права те же OWNER/MANAGER своего venue, repeatable-read и один server asOf, UTC [from,to),
календарные даты в timezone venue. GET не дописывает факты.

Товары идентифицируются menu_item_id. Цифровые единицы — qty активных, не исключённых строк
неотменённых заказов/неотклонённых отправок, по first_delivered_at (fallback на достоверный
item.fulfilled_at). Историческая цена + snapshot option delta дают стоимость ДО скидок.
Подарочное количество берётся из reward_qty / выданной APPLIED loyalty redemption (одна единица):
совпадающее свидетельство одной строки не складывается, результат ограничивается qty; остальные единицы не называются
оплаченными. Сохранённое имя/цена не заменяются текущим меню. Если имя snapshot отсутствует, текущая карточка
может дать подпись известному ID, но сумма остаётся unknown и coverage PARTIAL. Варианты не умножают базовую qty.
Ручные AccountingSale с source=MANUAL и без orderId включаются отдельно по sale.createdAt;
canceled/disputed исключены. Нераспознанный source, issue или недопустимые qty/цены в legacy snapshots
дают unknown вместо угадывания ручной продажи.
Цифровая accounting projection с orderId не считается второй раз. Неразнесённые строки ручных продаж
показываются отдельным количеством; отсутствие ID не восстанавливается по имени.
Net по товарам UNAVAILABLE: общие скидки/сборы/adjustments не распределяются произвольно.
Валюты раздельны; суммы decimal strings в minor units, без float.

Каталог позволяет показывать текущие позиции без найденных фактов. Текущая категория/доступность
не доказывает доступность весь период. Legacy timestamps, неструктурированные выдачи и отсутствующие
snapshots дают PARTIAL: «нет подтверждённых продаж в доступных данных», а не надёжный ноль.

HT-VENUE-PRODUCTS-CATEGORY-UX-01 — LOCAL IMPLEMENTED / REVIEW PENDING. ProductReport совместимо
добавляет `summary`, `categoryOptions`, `categoryTotals`, optional `categoryPage`; прежние rows,
categories, page/totalRows и source-specific значения сохраняются. Сводка и категории считаются по
полной подходящей выборке ДО пагинации. Units = сумма зарегистрированных qty выдач + допустимых
ручных строк с menuItemId; giftUnits использует прежнее подтверждённое распределение, без повторного
учёта accounting projection. Gross = сумма исторических (base + option delta) × qty по каждой валюте,
до скидок и подарков. Количества сводок используют BigInteger, деньги — BigDecimal/decimal strings.
Неразнесённые ручные единицы исключены из распределения и показаны отдельным предупреждением.

`categoryKey` — фильтр по стабильному menu category ID (каноническая положительная десятичная
строка) либо `uncategorized`; несовместим со старым `category` по имени. Текущая категория определяется
через venue-scoped каталог, исторические позиции без связи идут в «Без категории». Одинаковые
названия разных ID не объединяются. Поиск, categoryKey, валюта и сортировка применяются на сервере;
amount требует одну валюту. `expandedCategoryKey` выбирает только страницу раскрытой категории,
не фильтруя итог. Все эти параметры допустимы только для section=products; неизвестные/чужие ключи
дают общий validation error без раскрытия чужих данных. Старый category по имени совместим.

В categoryPage строки объединены только по (menuItemId, currency), source-specific значения остаются
в `sources`; ручная и подтверждённая выдача не показываются двумя одинаковыми строками. Историческое
имя выдачи имеет приоритет над именем ручной записи. Page size=50; стабильные tie-breakers itemId,
currency исключают повторы при неизменных данных. Summary, categoryTotals и categoryPage читаются
одной repeatable-read транзакцией с одним asOf. Следующая страница/раскрытие заново читает ВЕСЬ ответ,
обновляя итоги и asOf вместе. Это новый согласованный ответ, а не сохранённый снимок между запросами;
при новых операциях состав страниц может измениться. Frontend не суммирует страницы.

Неполная временная/текстовая история и неразнесённые записи сохраняют PARTIAL, в том числе после
фильтра: их категорию/валюту нельзя уверенно определить. Неизвестная цена делает денежный итог
PARTIAL с observedValue известной части, не скрывая известные units. Неоднозначные подарки делают
giftUnits PARTIAL, не превращая остальные единицы в оплаченные. Достоверный ноль доступен только
при полном соответствующем источнике; прочерк частичного нуля не означает отсутствие операций.
Net остаётся UNAVAILABLE. Классификация категорий текущая, не историческая на дату продажи.

HT-VENUE-PRODUCTS-CATEGORY-UX-R1-R3-FIX-01 / LOCAL FIX / REVIEW PENDING: поиск сопоставляет
сохранённые имена источников со стабильным itemId и включает все его источники/валюты до
вычисления итогов и страниц. Валютный фильтр принимает известный трёхбуквенный код либо явный `UNKNOWN` и отбирает только факты своей
валюты. UNKNOWN не назначается RUB или другой валюте: в подходящей по поиску/категории выборке
он сохраняет PARTIAL для выбранной известной валюты, с observedValue только известной части.
Без валютного фильтра известные количества сохраняются; денежные итоги известных валют не
объявляются полными при подходящем UNKNOWN. `PRODUCT_CURRENCY_UNKNOWN` в существующем KpiValue.reason
объясняет эту неопределённость. У товара она ограничена его ID, у категории — её стабильным ID;
в достоверно другой категории или исключённом поиском товаре она не распространяется.


F1–F5 review corrections (HT-VENUE-OPERATIONS-STATS-UX-F1-F5-FIX-01 / LOCAL / REVIEW PENDING):
цифровая accounting-проекция не добавляет количество повторно. Её `order:<id>` / `item:<batchItemId>`
сопоставляются с тем же order, menu ID, qty, валютой и исторической ценой. Подтверждённый line giftReason
означает подарок всей строки. Promotion/loyalty и accounting evidence одной единицы не суммируются.
Денежная корректировка без однозначной unit allocation не делится пропорционально: giftUnits и
nonGiftUnits в такой строке null, общая coverage PARTIAL; units и gross сохраняются. Для известных
строк поля и значения прежнего контракта сохраняются. Accounting writers/исторические суммы не меняются.

Дозаказы: выполненные отправки группируются по (orderId,tabId), с venue/session JOIN на tab.
В итоговую когорту включается только tab.status=CLOSED при order.status=CLOSED.
Основание периода — orders.closed_at (`CLOSED_TABS_OF_ORDERS_CLOSED_AT`), не дата закрытия tab; COUNT отправок минус одна первая выполненная
даёт число дозаказов. Отклонённая/невыполненная отправка не даёт успешную единицу. Счета 1/2/3+
выполненных отправок дают группы 0/1/2+ дозаказов; доля = счета 2+ / счета с выполненными позициями.
При знаменателе 0 доля null. ACTIVE tabs с выполненными позициями — отдельный snapshot на asOf, включая уже закрытые orders;
в итоговую долю они не входят. CLOSED tab при ACTIVE order не входит в когорту закрытых orders.
TAB не имеет paid_at/closed_at и состояния PAID; закрытие не означает оплату.
Неизвестный tab/legacy history или закрытый order без closed_at даёт PARTIAL/unknown.

PostgreSQL V139 / H2 V140 добавляют nullable orders.closed_at, range index и индекс первой отправки (order_id, tab_id, id). Единственный существующий
close-writer фиксирует его атомарно с закрытием из server instant после блокировок (UTC, microseconds),
COALESCE сохраняет первое время при retry. Время начала PostgreSQL-транзакции не подменяет момент действия.
Старые CLOSED не backfill-ятся из updated_at. Состояние CLOSED не становится оплатой или выдачей.
Analytics event store, billing и accounting writers не меняются.


## Analytics & Statistics stage 2 — KPI read models

`HT-ANALYTICS-KPI-READMODELS-IMPLEMENT-01`: **LOCAL IMPLEMENTED / INDEPENDENT REVIEW PASS**.
Stage 1 is **LOCAL IMPLEMENTED / INDEPENDENT REVIEW PASS** (owner-supplied independent
review: no findings, FULL_BACKEND_PASS=YES). Stage 2 review including the bounded KPI fixes
passed without open findings (owner-supplied result). Stage 3 Venue UI including the ISO money fix is
**LOCAL IMPLEMENTED / INDEPENDENT REVIEW PASS** (owner-supplied targeted review, no findings).
Stage 4 Platform UI is **LOCAL IMPLEMENTED / REVIEW PENDING**.
Overall block: **LOCAL / FINAL INTEGRATION REVIEW PENDING**.
All stages remain unpublished; publication requires a separate task after stage 4 and overall review.
D1–D4 below remain APPROVED. The original three backlog IDs remain open for their later scope.

### Versioned GET contract

- `GET /api/venue/{venueId}/stats/v1?from=YYYY-MM-DD&to=YYYY-MM-DD`
- `GET /api/platform/analytics/v1?from=YYYY-MM-DD&to=YYYY-MM-DD`
- Required dates define **[from, to)**, with `to` exclusive. Venue uses `venue_settings.timezone`
  and the existing default when absent/invalid (explicit `VENUE_TIMEZONE_FALLBACK` limitation).
  Platform uses UTC. Calendar ranges are 1–370 days, following the existing Venue schedule
  range ceiling as a technical request bound, not a tariff. DST days need not be 24 hours.
- Each response includes `definitionVersion=2026-11-analytics-v1`, one server `asOf`, requested
  UTC `period.from/to`, timezone and interval. `asOf` is truncated to microseconds before
  SQL binding and serialization, matching timestamp precision on PostgreSQL/H2. Realized facts are clipped at `min(to, asOf)`;
  scheduled bookings can include future dates. No historical-asOf parameter is accepted.
- `section`: Venue `all|orders|finance|bookings|seatings|calls`; Platform
  `all|venues|subscriptions|invoices|onboarding|support`. Default `all`.
- Optional Venue `tableId` requires one operational section (`orders|bookings|seatings|calls`).
  It means the object's current table association, including a seating after MOVE. Finance/all
  do not accept it. Table ownership is checked after membership. Unknown/duplicate parameters,
  source/payment-method filters, invalid dates and incompatible filters return validation errors.
- Values are exact decimal **strings**, not floating point money. Metrics include `unit`, `basis`,
  `samples`, `unknown`, `availability`, `coverage`, `value`, `observedValue`, safe `reason`.
  `value=null` for partial history; `observedValue` is the confirmed part or measurable-sample
  statistic, **not** the full KPI. A complete empty count is `0`; an empty ratio/mean is null.
  Unsupported sources have `UNAVAILABLE_SOURCE`. COMPLETE_RECORDED_SOURCE describes retained
  domain rows, not historical completeness before rollout or before data retention/deletion.
- State/status breakdowns are current snapshot attributes, not transitions in the date range.
  `breakdowns` are complete grouped counts except `items.deliveredTop` (explicit top 20).
  Frozen/open finances and invoice currency totals are separate structures. No implicit currency
  conversion. Average money rounds HALF_UP to a minor unit; ratios to 6 decimals, durations to
  milliseconds with 3 decimals. Unsupported long monetary totals return NUMERIC_OVERFLOW.
- All queries run on one read-only REPEATABLE_READ connection, including membership, timezone
  and open-shift projection. GET never ensures subscriptions, writes events or repairs history.
  Cache-Control is `no-store`. The old `/stats?period=today|7d|30d` DTO and behavior are unchanged.

### Implemented definitions

| Metric/group | Definition and authoritative source | Basis / limitations |
| --- | --- | --- |
| `orders.created`, `batches.created` | COUNT of orders / batches separately | Each entity's created_at; no status exclusion |
| `orders.firstAccepted` | Complete-history orders whose first_accepted_at is in interval | Legacy orders cannot prove first-ever acceptance; unknown count covers pre-end legacy candidates |
| `batches.accepted/delivered/rejected` | Distinct batches with matching `order_batch_status_facts` transition in interval | EXISTS prevents repeated transitions/retries inflating a batch count; venue/order/batch identities all joined |
| `batches.cohortAcceptedRate`, `cohortDeliveredRate` | Confirmed first acceptance/delivery before asOf / same batch-created cohort size | Rates share one denominator; acceptance survives rejection; no CLOSED→DELIVERED inference |
| `batches.acceptedDelay`, `deliveredDelay` | Mean first acceptance/delivery minus batch.created_at | Same created cohort; complete-history measurable samples only, nonnegative time |
| `orders.cancelledSnapshot`, `items.excludedSnapshot` | Current CANCELLED orders / excluded or CANCELED item rows in created cohort | Item rows, not quantities; not cancellation-time metrics or refunds |
| `items.deliveredTop` | SUM qty by immutable item_name_snapshot of first-observed-delivered batches in interval | Current eligible item state excludes exclusions/CANCELED; ignores live menu name/price; coverage counts covered batches and unknown candidate batches (including free-text batches without item rows); repeated deliveries do not multiply quantity |
| Closed-shift finance | Frozen final_report.totals across shifts with closed_at in interval, grouped currency | Accrued, paid/operatorConfirmed, discount, gift, unpaid, sales count, CASH/CARD/TRANSFER; no live receipt/menu aggregation |
| Average accrued sale | SUM frozen accruedMinor / SUM frozen salesCount | Includes manual sales; canceled sales treatment remains the frozen accounting definition; zero denominator null |
| Open-shift finance | Existing accounting projection with locking disabled inside read-only snapshot | Separate provisional current shift; never added to closed-shift totals; same accounting calculations/writers |
| Financial coverage | Invalid/unreadable reports, non-ISO/mixed sale/report currency or unresolved sale.issue make coverage partial | Such reports never become zero; no currency invented for an empty report set; `finance.closedReports` distinguishes empty from missing |
| Bookings created | COUNT and current statuses of bookings created in interval | Separate from current scheduled_at cohort; rescheduling changes scheduled cohort membership |
| Bookings scheduled | COUNT/current statuses whose scheduled_at falls in interval | SEATED, NO_SHOW, CANCELED, EXPIRED separately; unresolved means other status before asOf, future means other status at/after asOf |
| `bookings.attendance` | SEATED/(SEATED+NO_SHOW) of scheduled cohort | CHANGED is unresolved/future, never inferred seating; zero denominator null |
| `seatings.registeredSessions` | COUNT DISTINCT table_session_id in table_seatings by started_at | Includes WALK_IN/offline, MOVE is same session; not Guest History or unique people |
| Calls | Created count; ACK, OPERATOR completed, ORDER_CLOSED auto completed, cancelled by actual timestamps | Auto BILL completion has no invented ACK; creation cohort mean acknowledged_at−created_at, complete-history samples; legacy unknown separate |
| Venue lifecycle/created | Full SQL lifecycle grouping and created_at count | Lifecycle is snapshot; creation is period |
| Guest availability | Existing VenueAvailabilityResolver(lifecycle, normalized subscription status) | PUBLISHED alone insufficient; past_due allowed; missing row disclosed as unknown, no lazy subscription creation |
| WAAV | COUNT DISTINCT venue with ACCEPTED/DELIVERED fact in [asOf−168h,asOf) | Independent of selected dates/current lifecycle/subscription; legacy candidate venues mark partial history |
| Subscription states | Full LEFT JOIN venues/subscriptions, normalized statuses | UNKNOWN and MISSING separately; no writes |
| Paid-through | MAX period_end of PAID invoices per venue | Inclusive domain DATE, distribution and count covering current UTC date; absent recorded paid period unknown |
| Effective paid-through | MAX(base paid-through, courtesy new_paid_through selected by the billing contract: ORDER BY new_paid_through DESC, id DESC) | Only COURTESY_DAYS created before asOf; a later shorter grant never shortens the effective date. Courtesy separate, not payment; full grouped read, not a 50-row invoice list |
| Invoices | Snapshot OPEN/PAST_DUE, overdue due_at<asOf, PAID; sums per currency | No bank settlement; counted invoice once even with several payment events |
| Manual confirmations | Distinct PAID invoices paid_at in interval with exact manual:id SUCCEEDED event matching invoice/provider/amount/currency | Existing server manual provenance; no raw payload or amounts of mismatched events summed |
| Reconciliation warnings | Paid invoices without matching evidence, inconsistent successful events, invoices with >1 matching success | Diagnostics, not incoming money. Multiple-success warning counts each invoice created before asOf once, regardless of its current status; only SUCCEEDED events before asOf matching invoice/provider/amount/currency qualify |
| Onboarding | Full request count/statuses/created_at cohort and FK-confirmed venue links | No lead-to-paid funnel |
| Staff-chat | Venue has persisted staff_chat_id binding | Aggregate only, no IDs; linkage does not prove delivery |
| Support | SUPPORT_TICKET total/active states OPEN, NEW, IN_PROGRESS, WAITING_USER; oldest active age | Excludes BOOKING_THREAD and VENUE_CHAT; no content or PII |
| Support TTFR | First recorded non-SYSTEM VENUE/PLATFORM message with human author minus ticket.created_at, for created cohort | Guest and SYSTEM excluded; no recorded reply counted separately (not zero); resolution/reopen/escalation durations unavailable |

Financial totals use frozen business facts, not analytics events or provider event totals.
No settlement/refund/profit/MRR/unique-person metric, client funnel, export or AI summary is added.
Historical item exclusions and seating table associations are snapshots; reconstructing their
past timelines is outside this version. Stage-1 legacy markers remain false after later observed
transitions. An installed migration alone never makes coverage complete.

Implementation: `analytics/KpiModels.kt`, `KpiRepository.kt`, `KpiQueries.kt`,
`VenueKpiQueries.kt`, `FinancialKpiQueries.kt`, `PlatformKpiQueries.kt`, `KpiRoutes.kt`.
Permanent evidence: `KpiReadModelsH2Test`, `KpiReadModelsPostgresTest`, `KpiRoutesTest`.
No stage-2 schema migration: existing indexes are measured with PostgreSQL EXPLAIN ANALYZE;
plans and final regression provenance are recorded in the stage-2 local report.

`HT-ANALYTICS-KPI-REVIEW-FIX-01` is included in the stage-2 **INDEPENDENT REVIEW PASS**.
The two review corrections align courtesy selection with `BillingAdjustmentRepository` and
aggregate matching payments in SQL before joining invoices, avoiding a full payment scan per
invoice. Provider-event idempotency, money sources, DTOs and RBAC are unchanged. No new index or
migration is required. `PlatformBillingKpiH2Test`/`PlatformBillingKpiPostgresTest` compare billing
semantics, cutoff/unknown cases and old/new reconciliation results; the PostgreSQL dense fixture
records both plans and complete snapshot equality. Timings are synthetic local evidence only.

### Venue presentation — stage 3

`HT-ANALYTICS-VENUE-STATS-UI-IMPLEMENT-01` consumes this exact v1 contract in the existing
Venue statistics section. Today/last 7/last 30 include the venue's current calendar date;
custom end date is inclusive in the form and converted to the following calendar date for GET.
The server resolves UTC boundaries/DST. The screen first obtains timezone/asOf through the
existing minimal `seatings` read, discards that result, then requests the chosen period.
No new metadata endpoint, backend calculation or write-capable getter is introduced.

Decimal-string money is formatted with BigInt and ISO currency digits, without Number rounding.
`HT-ANALYTICS-VENUE-MONEY-FORMAT-FIX-01` pins minor-unit exponents to the official SIX
[ISO 4217 List One](https://www.six-group.com/dam/download/financial-information/data-center/iso-currrency/lists/list-one.xml),
published 2026-09-17, retrieved 2026-11-11. The static `iso4217MinorUnits.json` projection is
verified against a checked-in LF-normalized XML snapshot (upstream and normalized SHA-256 in
the projection). IQD=3, MGA=2, CLF=4; Intl defaults are never a stored-unit scale. Both fraction
digit options are explicit; BigInt quotient/remainder keeps Long limits exact. Unknown, invalid,
withdrawn or N.A. codes without a supported exponent display «Недостаточно данных», with no
default division by 100. No runtime download, FX conversion or backend financial change.
Updating the snapshot/exponents requires an explicit reviewed source update, not a runtime refresh.
Mean durations are labeled «Среднее». Transition totals, created cohorts and current status
distributions remain separate. No time series is synthesized from aggregates. Top items use
the server's immutable names and explicit coverage. Partial values display the observed subset
as such, never as the full KPI; missing/unsupported means and ratios remain unavailable.
Russian «Как считается» explanations, visible financial/legacy limits and scoped filters are
specified in `VENUE_OPERATIONS.md`. Runtime version/context validation fails closed; old
`/stats?period=...` and its DTO/client remain compatible. Permanent real HTTP fixtures are
generated and compared by `VenueStatsUiContractTest`, consumed by `venue-stats.spec.ts`.


### Platform presentation — stage 4

`HT-ANALYTICS-PLATFORM-DASHBOARD-IMPLEMENT-01` replaces the existing Platform analytics placeholder.
The same Platform Owner-only entry opens four overview cards and five expandable groups:
venues, subscriptions, invoices/payments, onboarding/staff-chat and support. No additional API,
KPI store, financial calculation or write action is introduced. Stage 3 formatter is reused unchanged.

- `from/to` remain required exclusive UTC API boundaries. UI dates are inclusive; presets today/7d/30d
  use server asOf (corrected after the first response), custom dates allow 1–370 days. Only `section`
  filters are offered. Snapshot/selected-period/rolling-168h cards are explicitly distinguished.
- Registered, published, guest-available and active venues remain separate. WAAV uses server facts,
  not created orders. Missing subscriptions and legacy history retain partial values/unknown counts.
- Subscription states come from server breakdowns. Paid-through and effective paid-through dates/counts
  are displayed as supplied; courtesy is not payment and is never recomputed by the frontend.
- Invoice open/overdue/paid amounts are snapshots grouped by currency. Overdue is a subset of open.
  Manual-confirmed invoices use paid_at in the selected period; reconciliation counters have distinct
  units and must not be added as money or a combined unique warning count. No bank settlement source.
- Support totals/queue/oldest age are snapshots. TTFR is the mean among measurable created-period
  tickets, with unknown/partial coverage shown. SYSTEM/GUEST messages and BOOKING_THREAD/VENUE_CHAT
  are excluded by backend facts; the screen neither reads text nor creates its own response history.
- Availability reasons and support-status distributions are not returned by v1 and are explicitly
  unavailable. No invented charts/series, MRR/profit/refunds, TTR/reopen/CSAT or lead-to-paid funnel.
- All non-obvious cards have tap/keyboard `Как считается`; zero, unavailable and observed partial
  subsets remain separate. Missing fields do not become zero; incompatible envelopes fail closed.
- Safe links lead to existing venues/billing, applications and Support Center without carrying filters.
  Platform grant does not add venue accounting access. Errors/DOM contain no PII, messages or raw payloads.

## Analytics & Statistics foundation — HT-ANALYTICS-FOUNDATION-IMPLEMENT-01

**LOCAL IMPLEMENTED / INDEPENDENT REVIEW PASS.** This bounded stage retains backlog IDs
`ANALYTICS-EVENT-COVERAGE-001`, `VENUE-STATS-ADVANCED-001` and
`ANALYTICS-PLATFORM-DASHBOARD-001`. No KPI API or UI is included.

Approved product definitions (D1–D4, **APPROVED**, supersede ambiguous target formulas below):
- D1: first accepted order = first actual acceptance of any of its batches; later batches do
  not count as new first accepted orders. WAAV = distinct venues with an actual batch transition
  to ACCEPTED or DELIVERED in `[asOf - 168 hours, asOf)`. Lifecycle, subscription and guest
  availability are separate snapshots. Creation time/current status cannot establish activity.
- D2: financial MVP is **totals of shifts closed in the period**, using frozen `final_report`
  and `closed_at`. Open shift is provisional and separate. Accrual, operator-confirmed
  CASH/CARD/TRANSFER, discounts/gifts, recorded unpaid balance and average accrued sale
  including manual sales remain separate. This is neither bank reconciliation nor calendar
  cash flow; VOID_PAYMENT is not a real refund. Accounting writers/calculations are unchanged.
- D3: bookings created in a period and bookings scheduled for visit dates are separate cohorts.
  Scheduled cohort arrival share = `SEATED / (SEATED + NO_SHOW)`; unresolved/future/canceled/
  expired are separate. Empty denominator = unavailable share, not 0% or all-request conversion.
- D4: physical seating = distinct `table_session_id` among registered seating sessions,
  including offline. MOVE within a session is not another seating. Guest History and unique
  persons are different; unique visitors are outside MVP.

All future shared reads use `[from,to)`, a single server `asOf`, UTC instants, Venue calendar
boundaries in its timezone, and explicitly UTC Platform boundaries. Missing history is not zero.

PG V138 / H2 V139 add domain `order_batch_status_facts` (one row per actual transition,
key batch + transition sequence), first confirmed acceptance/delivery on batches and first
confirmed acceptance on orders. First timestamps never clear on rejection or overwrite on
append/replay. DELIVERED does not imply a known ACCEPTED time; CLOSED does not imply delivery
or payment. Facts, state and analytics append share the writer transaction. Existing venue-first
locking serializes single/bulk/whole-order and call writers; sequences distinguish actual reentry
without modifying the allowed workflow. Facts are an extension of orders, not a second state machine.

Physical names and existing store rows/keys remain unchanged. `canonicalAnalyticsEventType`
normalizes `batch_created` to `order_batch_created`, `batch_status_changed` to
`order_batch_status_changed` **at read time, one physical row counted once**. An alias of an old row does not establish
a trustworthy batch transition or occurrence time. New envelopes have
`contractVersion=2`, `canonicalEventType`, `occurredAt`, `producer`, `channel`; transitions add
`batchFromStatus`, `batchToStatus`, `transitionSequence`. Legacy `fromStatus`/`toStatus` keep their
workflow meanings for old consumers and must not be interpreted as exact batch statuses.
New transition keys are `batch_status_changed:v2:<venue>:<order>:<batch>:<sequence>`;
creation retains `batch_created:<venue>:<batch>`. Unrelated dedupe semantics are unchanged.
Creation occurrence is the persisted server creation time; transition occurrence is server UTC
at microsecond precision after locking, distinct from analytics row insertion time.
Producer is the repository; channel MINIAPP/TELEGRAM is passed by trusted server code, otherwise
UNKNOWN. `order_batches.source=MINIAPP` is not channel evidence. No reasons, comments, user ids,
raw Telegram/initData, tokens or payment payloads are added to analytics.

Legacy completeness: migrations default `status_history_complete` (batches/calls) and
`acceptance_history_complete` (orders) to false; timestamps stay null. Upgraded creation writers
explicitly set true. A new transition of a legacy object records a known fact, **never** changes
its incomplete flag. For incomplete objects, first timestamps mean earliest confirmed observation,
not a claim about unknown earlier history. Null + complete means no recorded qualifying transition;
null + incomplete means unknown history. Complete flags describe instrumented writer history,
not migration installation or proof of fleet-wide rollout; mixed old writers cannot establish full
coverage. Readers never repair timestamps or emit events. No historical audit/created_at/updated_at
backfill, retention change, financial source substitution or global analytics explorer is introduced.

Next stages of the same block: shared KPI/read models → Venue Stats Advanced → Platform Analytics.
Broad events (including feedback/subscription gaps), provider funnels and AI remain open backlog.


Evidence reconciliation (2026-11-09): main `8292696…` / reported staging V132 include the
accounting report and auto-open; live product acceptance remains open. Current literal server
event names observed in source are `table_session_started`, `batch_created`, `batch_status_changed`,
`subscription_status_changed`, `feedback_submitted` and `promotion_applied`. The event catalog below
is a target contract; it does not prove emission of every named event. See
[roadmap Section 13.0](UPDATED_PRODUCT_AI_ROADMAP.md#130-current-evidence-boundary--2026-11-09).

Дата актуализации: 2026-07-23.

Статус: **current product reference / SPEC UPDATED**. Analytics implementation is **PARTIAL / needs verification** unless a specific repository, migration, dashboard or smoke result is cited by an implementation task. This document defines target events, KPI formulas, dashboards, audit boundaries and privacy rules for Telegram bot + Mini App.

## Core Rule

Analytics is not the operational source of truth. Domain tables remain authoritative:
- orders, order_batches, tabs and table_sessions;
- bookings;
- staff_calls;
- support_tickets / support messages;
- subscriptions, invoices and payments;
- venue_members, venue settings and menu tables.

Menu/options/stop-list product semantics are defined in `docs/MENU_OPTIONS_STOPLIST.md`.
Venue Mode operational surfaces and role dashboards are defined in `docs/VENUE_OPERATIONS.md`.
Booking lifecycle semantics for booking events, reminders and booking-to-visit conversion are defined in `docs/BOOKING_LIFECYCLE.md`.
Telegram fallback and staff-chat event semantics are defined in `docs/TELEGRAM_FALLBACK_STAFF_CHAT.md`.
Staff profile, today shift and future staff-tip event semantics are defined in `docs/STAFF_PROFILES_SHIFTS_TIPS.md`.
Testing and smoke strategy for event implementation is defined in `docs/TESTING_QA_SMOKE_STRATEGY.md`. Release/deploy, logs and incident operations are defined in `docs/DEPLOYMENT_RUNBOOK.md`.

Analytics events are immutable facts for reporting, funnels and diagnostics. They must not drive money, access, billing, order state or support state.

Local operational monitor (`HT-VENUE-DASHBOARD-ALERTS-FINDINGS-FIX-01`): protocol 2 reads a
bounded complete snapshot of current NEW batches/NEW calls, not an analytics or Telegram log.
`batch:<id>` and `call:<id>` identify distinct domain records, including append batches of one
order. No MAX(id)/time cursor excludes delayed commits. Capacity overflow is explicit, never
proof that omitted events were handled. A creation completed/deleted before first observation
may be unavailable without a separate durable log. UI kinds `ORDER_CREATED`, `BATCH_ADDED` and
`STAFF_CALL_CREATED` do not claim new canonical analytics emission. Detection/baseline and
channel delivery receipts are separate bounded device state; successful Web Audio completion
is not proof of physical audibility, human action, payment, order completion or call ACK.
See `VENUE_OPERATIONS.md` for capacity, lease/retry, retention and background boundaries.

## Event Families

| Family | Purpose | Source of truth |
| --- | --- | --- |
| `analytics_events` | Product/operational facts for KPI, funnels and dashboards. | Derived from domain writes and selected client diagnostics. |
| `audit_events` / `audit_logs` | Who changed critical state, when, from what to what. | Written alongside sensitive mutations. |
| `notification_outbox` / delivery events | Technical message delivery, retry and Telegram/provider state. | Outbox/delivery tables and workers. |
| `support_audit` | Ticket status, scope, assignee and escalation changes. | Support domain tables plus safe audit payloads. |
| `billing_events` | Subscription, invoice, payment and provider lifecycle. | Billing/subscription/invoice/payment domain tables. |

## Naming Convention

- Event names use `snake_case`.
- Events are past-tense facts: `order_batch_created`, not `create_order_batch`.
- One event = one meaningful business fact.
- IDs are opaque/internal.
- Timestamps are UTC.
- Venue timezone is used for aggregation/reporting only.
- Do not log raw PII, raw Telegram payloads, raw Mini App initData, card data, provider secrets or message text in analytics events.

Target analytics event envelope:

```text
event_id
event_name
event_ts
source: server | miniapp | bot | worker | platform
actor_user_id nullable
venue_id nullable
table_id nullable
table_session_id nullable
order_id nullable
batch_id nullable
tab_id nullable
booking_id nullable
support_ticket_id nullable
subscription_id nullable
properties json
correlation_id nullable
```

## MVP Server-Side Events

These are the target minimum. Mark each implementation task as DONE only after the event is emitted from the authoritative server-side mutation path and covered by tests/smoke.

Table/session:
- `table_token_resolved`
- `table_session_started`
- `table_session_touched`
- `table_session_expired`
- `table_session_closed`

Order/batch:
- `active_order_created`
- `order_batch_created`
- `order_batch_status_changed`
- `order_batch_rejected`
- `order_closed`
- `fallback_order_created`

Tab/bill:
- `personal_tab_created`
- `shared_tab_created`
- `shared_tab_invite_created`
- `shared_tab_joined`
- `tab_bill_requested`
- `tab_paid`
- `tab_closed`

Staff call:
- `staff_call_created`
- `staff_call_acknowledged`
- `staff_call_completed`
- `staff_call_cancelled`

Booking:
- `booking_created`
- `booking_confirmed`
- `booking_time_proposed`
- `booking_guest_accepted_time`
- `booking_guest_rejected_time`
- `booking_canceled_by_guest`
- `booking_canceled_by_venue`
- `booking_expired`
- `booking_no_show`
- `booking_seated`
- `booking_reminder_scheduled`
- `booking_reminder_sent`
- `booking_reminder_action_clicked`

Support:
- `support_ticket_created`
- `support_ticket_message_created`
- `support_ticket_status_changed`
- `support_ticket_transferred_to_platform`
- `support_ticket_closed`
- `support_ticket_reopened`

Communication:
- `venue_chat_created`
- `venue_chat_message_created`
- `booking_chat_message_created`

Telegram fallback / staff-chat:
- `bot_start_received`
- `table_token_resolved`
- `bot_table_context_shown`
- `fallback_order_started`
- `fallback_order_confirmed`
- `fallback_order_cancelled`
- `fallback_order_batch_created`
- `staff_call_created_from_bot`
- `staff_chat_link_code_created`
- `staff_chat_linked`
- `staff_chat_unlinked`
- `staff_chat_test_sent`
- `staff_chat_notification_enqueued`
- `staff_chat_notification_delivered`
- `staff_chat_callback_action_clicked`
- `staff_chat_callback_action_rejected`

Menu/options/stop-list:
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

Billing:
- `subscription_trial_started`
- `subscription_activated`
- `subscription_past_due`
- `subscription_suspended`
- `subscription_canceled`
- `invoice_created`
- `invoice_paid`
- `invoice_voided`
- `payment_webhook_received`
- `payment_webhook_rejected`

Growth:
- `favorite_venue_added`
- `favorite_venue_removed`
- `visit_recorded`
- `repeat_template_created`
- `repeat_template_applied`
- `feedback_requested` (future/disabled while automated prompts are disabled)
- `feedback_submitted`
- `promotion_viewed`
- `promotion_previewed`
- `promotion_applied`
- `promotion_recalculated`
- `promo_code_copied`
- `promo_code_redeemed`

`promotion_applied` is emitted server-side in the same submit transaction as the persisted
application. It carries venue/order/batch correlation plus opaque promotion/rule/version ids and
uses the application dedupe reference as its analytics idempotency key. Preview does not emit it,
and idempotent replay does not create a duplicate. Management create/update/status events and
`promotion_previewed` / `promotion_recalculated` remain follow-ups unless they can reuse the
existing audit/event infrastructure with a small change.

Executable promotion analytics must not contain raw cart payloads, trusted client discount values,
message text, Telegram payloads or unrelated PII. The persisted promotion application remains
financial/order history truth; analytics never recalculates eligibility or discounts.

Staff profiles / today shift / staff tips:
- `staff_profile_viewed`
- `staff_shift_viewed`
- `staff_profile_published`
- `staff_shift_marked_active`
- `staff_schedule_shift_created` (future)
- `staff_shift_confirmed` (future)
- `staff_tip_intent_created` (future)
- `staff_tip_clicked` (future)

## Client-Side Events

Client events are lower-trust UX diagnostics. They help funnels and UI debugging only. They must not drive money, access, billing, order state, support state or venue permissions.

Target client events:
- `miniapp_opened`
- `screen_viewed`
- `catalog_viewed`
- `venue_card_viewed`
- `menu_viewed`
- `item_viewed`
- `item_added_to_cart`
- `cart_viewed`
- `checkout_submit_attempted`
- `checkout_failed`
- `fallback_clicked`
- `support_form_opened`
- `venue_question_clicked`
- `help_opened`

## Audit Events

Audit is separate from analytics. Audit answers who changed critical state; analytics answers what happened for reporting.

Audit required for:
- role granted/revoked;
- owner/manager/staff invite created/accepted/revoked;
- venue status changed;
- venue published/hidden/suspended/archived/deleted;
- subscription override changed;
- invoice manually marked paid;
- menu price changed;
- menu availability changed;
- menu option schema changed;
- menu media removed;
- mass stop-list update;
- promotion schedule/eligibility/reward/status changed;
- staff stop-list toggle where Staff availability management is allowed;
- shift check completed where implemented;
- table QR token rotated;
- order force closed;
- tab reopened;
- support status/assignee changed;
- billing provider config changed;
- analytics export, if implemented.

Audit payload rules:
- `actor_user_id` required;
- target entity required;
- include old/new values for safe fields;
- no message text unless explicitly needed and safe;
- no raw payment secrets;
- no raw Telegram initData;
- no card data.

## KPI Formulas

Guest / in-venue funnel:
- QR Start Rate = `table_token_resolved` / guest venue-card or QR entry impressions where available.
- Table Session Start Rate = `table_session_started` / `table_token_resolved`.
- Mini App Open Rate = `miniapp_opened` / `table_token_resolved` or bot WebApp entry clicks.
- Menu View -> Cart = guests with `item_added_to_cart` / guests with `menu_viewed`.
- Cart -> Batch Submit = `order_batch_created` / carts with `checkout_submit_attempted`.
- Batch Acceptance Rate = accepted batches / `order_batch_created`.
- Delivery Rate = delivered batches / `order_batch_created`.
- Fallback Share = `fallback_order_created` / (`order_batch_created` + `fallback_order_created`).
- TTFO = time from `table_session_started` to first `order_batch_created`.
- Time to Accept = time from `order_batch_created` to first accepted status.
- Time to Deliver = time from `order_batch_created` to delivered status.

Venue operations:
- New batches backlog = current `new` batches not accepted/rejected/cancelled.
- Median accept time = median Time to Accept by venue/period.
- Median deliver time = median Time to Deliver by venue/period.
- Staff call response time = `staff_call_acknowledged` - `staff_call_created`.
- Reject rate = rejected batches / created batches.
- Out-of-stock checkout failure rate = checkout failures caused by unavailable item/option / checkout attempts.
- Staff-call completion rate = completed staff calls / created staff calls.
- Booking response time = first venue booking status response - `booking_created`.

Use `docs/VENUE_OPERATIONS.md` for which operational metrics belong to Owner, Manager and Staff surfaces.

Booking:
- Booking submit rate = `booking_created` / booking form starts where tracked.
- Booking confirm rate = `booking_confirmed` / `booking_created`.
- Booking cancel rate = (`booking_canceled_by_guest` + `booking_canceled_by_venue`) / `booking_created`.
- Booking no-show rate = `booking_no_show` / confirmed or changed bookings.
- Booking seated rate = `booking_seated` / confirmed or changed bookings.
- Time to confirm = `booking_confirmed` - `booking_created`.
- Reminder confirmation rate = attendance confirmations / sent reminders. This is future/rollout-gated where reminders remain disabled by default.
- Booking-to-visit conversion = `booking_seated` or linked completed visit / `booking_created`; cancelled, no-show, expired, pending and changed bookings are not visit conversions.

Support:
- TTFR = first non-guest support reply - `support_ticket_created`.
- TTR = resolved/closed timestamp - `support_ticket_created`.
- Escalation rate = transferred/escalated tickets / created tickets.
- Reopen rate = reopened tickets / closed tickets.
- CSAT = post-resolution rating average/completion rate. Future unless CSAT exists.
- Top issue themes = category/tag/theme distribution; do not derive from raw message text unless a safe classifier pipeline is approved.

Platform:
- WAAV = Weekly Active Accepted Venues: venues with at least one accepted/delivered order batch in the last 7 days.
- Venues created/published/suspended = counts of venue lifecycle facts.
- Onboarding completion = published or first accepted batch / approved onboarding requests.
- Time to first accepted batch = first accepted batch - venue created/published.
- Active/trial/past_due/suspended venues = subscription state snapshot by period.
- MRR manual/card/stars = recurring revenue by provider; card/stars are future until provider rollout.
- Support ticket volume by venue = support tickets created by venue/period/category.

Growth:
- Favorite rate = guests adding favorite / venue card viewers.
- Repeat visit rate = guests with more than one `visit_recorded` in period.
- Current Feedback MVP reports submitted count/rating distribution from `feedback_submitted`; Review completion = `feedback_submitted` / `feedback_requested` is future while automated prompts and `feedback_requested` stay disabled.
- Promo view/redeem = `promo_code_redeemed` / promotion views or copied codes.
- Repeat template usage = `repeat_template_applied` / `repeat_template_created`.
- Opt-in notification rate = opted-in guests / eligible guests.

## Venue accounting money semantics (in main; live acceptance open)

`VenueStatsRepository.revenueMinor` remains an **estimate of completed order value**, not paid
revenue. Its existing API shape is retained; snapshot item prices/names, option deltas and currency
are now preferred over the legacy menu fallback. Mini App/Bot labels explicitly say it is not
payment. Legacy Stats fallback is not admissible evidence for accounting receipts.

The separate accounting report uses integer RUB minor units: gross minus discounts minus gifts
is accrued; sum of non-void CASH/CARD/TRANSFER entries is operator-confirmed paid; accrued minus
paid is unpaid. Canceled records contribute zero accrued and cannot retain active receipts.
Promotion reward value is a gift, ordinary promotion/manual adjustments are discounts. A digital
order is counted once; mixed-source breakdowns sum its line sources, plus explicit service charges.
Missing/invalid source snapshots are listed as disputed and excluded from amount totals. They
block financial recording and final closure rather than manufacturing a value from today's menu.

These confirmed amounts mean an operator recorded an external receipt; they are not bank/provider
verification. No receipt is derived from bill-request payment preference or operational order state.
`VENUE_ACCOUNTING_*` audit actions accompany immutable actor/time/old/new history; analytics events
are not the financial source of truth. Closed reports preserve the acknowledged unpaid/disputed
state. STAFF receives the current operational report only, not historical business analytics.

## Dashboards By Role

Guest:
- No analytics dashboard in MVP.
- Later: history/profile-facing summaries only, not operator analytics.

Venue Owner:
- Today/7d/30d orders.
- Accepted/delivered batches.
- Revenue estimate only if bill data is reliable.
- Average accept/deliver time.
- Top items.
- Stop-list pain points.
- Staff calls.
- Bookings.
- Support ticket summary.
- Growth metrics later.

Venue Manager:
- Shift dashboard.
- Queue backlog.
- SLA timers.
- Active staff calls.
- Bookings waiting response.
- No billing metrics.

Staff:
- Operational queue only.
- No business analytics by default.

Platform Owner:
- WAAV.
- Venue health.
- Onboarding funnel.
- Billing state.
- Support load.
- Risk indicators.
- Platform-wide fallback/reject/SLA.
- Event/audit explorer as future/partial.

## Privacy And Security

- Security/RBAC source of truth: `docs/SECURITY_RBAC_MATRIX.md`.
- No raw PII in analytics events.
- Telegram user id can be an internal operational id; analytics exports should use pseudonymized user id.
- No raw initData.
- No payment secrets or card data.
- Message text should not be copied into analytics events.
- Support/chat text belongs in domain message tables with RBAC, not analytics events.
- Client events are untrusted; server validates critical facts.
- Rate-limit event ingestion if a public endpoint exists.
- Correlation IDs are safe opaque IDs.

## Current Implementation Vs Target

| Area | Current implementation from docs | Target product model | Gap / future note |
| --- | --- | --- | --- |
| Analytics events table/repository | Docs mention `AnalyticsEventRepository` and event writes, but coverage needs verification. | Unified `analytics_events` envelope for server facts and client diagnostics. | Needs implementation audit before DONE. |
| Table/session events | Table session creation/TTL/exit behavior exists; analytics emission coverage needs verification. | Emit `table_token_resolved`, `table_session_started/touched/expired/closed`. | Required for visit history and QR funnel. |
| Order/batch events | Order/batch routes and some order audit exist; analytics coverage needs verification. | Emit active order, batch create/status/reject/closed and fallback facts. | Needed for QR->order, TTFO, accept/deliver KPIs. |
| Menu/options/stop-list events | Options/flavors parity is documented as smoke-closed; broader event coverage needs verification. | Emit menu create/update/archive, price, availability, media and shift-check facts with safe payloads. | Needed for stop-list pain points, out-of-stock checkout failure rate and menu operations audit. |
| Booking events | Booking lifecycle and reminders exist in docs; event coverage needs verification. Canonical lifecycle is `docs/BOOKING_LIFECYCLE.md`. | Emit booking lifecycle events, reminder facts and booking-chat facts where enabled. | Booking analytics remains partial/future until confirmed. |
| Support events | Support-ticket audit exists for status/scope/assignment/escalation where implemented. | Emit support analytics events without raw message text. | CSAT and broad support analytics are future. |
| Telegram fallback / staff-chat events | Fallback payload, staff-call ACK/DONE and staff-chat diagnostics are closed for current smoke paths; broad event coverage needs verification. | Emit safe bot entrypoint, fallback order, staff-call, link/test, notification and callback facts. | Delivery/outbox telemetry is not business source of truth; payloads must not include message text or secrets. |
| Billing events | Billing audit exists for checkout ensure, mark-paid and courtesy days. Provider/webhook analytics needs verification. | Emit subscription, invoice and payment webhook facts. | Card/Stars metrics future until provider rollout. |
| Staff profile/shift/tip events | Phase 1 staff profiles/today shift runtime is done/local-smoke-passed; analytics emission coverage still needs verification. Canonical model is `docs/STAFF_PROFILES_SHIFTS_TIPS.md`. | Emit safe profile view/publish, today-shift view/mark-active, future schedule shift-created/confirmed and future tip intent/click facts. | Tip intent/click must not be interpreted as payment confirmation; schedule/tip events remain future until those domains exist. |
| Growth events | Guest History and Feedback are closed; `feedback_submitted` and server-side transactional `promotion_applied` are implemented. Favorite-item, repeat, loyalty and placement runtime foundations exist, but their analytics coverage still needs verification. | Emit favorite, visit, repeat, feedback, promotion, loyalty and placement facts only from authoritative server mutations. | `visit_recorded` must count only real completed visits/orders. `feedback_submitted` excludes raw comment text; `feedback_requested` and automated prompts remain disabled. Do not mistake partial domain runtime for complete event coverage. |
| Dashboard UI | Venue v1 UI independently reviewed; bounded Platform v1 dashboard locally implemented, stage-4 review pending. | Role-specific dashboards from authoritative domain facts; events remain supplementary. | Final integration review and separate publication before release acceptance; broader analytics stays future. |
| Audit logs | Several critical audit rows exist where implemented. | Audit all critical state changes with actor and safe old/new fields. | Fill gaps for menu price, QR rotate, force close, tab reopen, provider config. |
| Notification outbox | Telegram outbox/workers exist and expose operational metrics. | Delivery state is technical telemetry, separate from analytics facts. | Do not treat outbox rows as business conversions. |

## Roadmap Status

- Analytics/events spec: `UPDATED`.
- Analytics implementation: `PARTIAL / needs verification`.
- Security/RBAC matrix: `UPDATED` in `docs/SECURITY_RBAC_MATRIX.md`; analytics exports and audit views must follow that role/scope model.
- Platform analytics dashboard: `FUTURE/PARTIAL`.
- Booking/support/Telegram fallback/staff-chat/growth events: `PARTIAL/FUTURE` unless implementation evidence exists.
- Growth analytics can build on completed History, `feedback_submitted` and transactional `promotion_applied`; Platform dashboards and broader visit/favorite/repeat/loyalty/placement coverage remain future/partial until emission and aggregation semantics are verified.
- Advanced dashboards should wait until event emission, payload safety and aggregation semantics are reliable.

## Smoke / Acceptance Checklist

1. QR scan emits or records `table_session_started`.
2. Add-batch emits `order_batch_created`.
3. Batch status change emits `order_batch_status_changed`.
4. Staff-call create emits `staff_call_created`.
5. Booking status change emits the correct `booking_*` event if implemented; otherwise remains marked future.
6. Support ticket create/status/transfer emits `support_*` event if implemented; otherwise remains marked future.
7. Subscription state change emits `subscription_*` event if implemented.
8. Audit log exists for role/status/billing dangerous changes.
9. Analytics event payload contains no message text, raw initData, payment secrets or card data.
10. Platform Owner can see/export relevant analytics only where implemented.
11. Venue Owner cannot see another venue analytics.
12. Staff does not see platform analytics.
13. Staff profile/shift analytics contains only public display fields or opaque ids, not `linked_user_id`, private Telegram usernames, phone/email or external payment secrets.
14. If `visit_recorded` exists, it excludes cancelled/no-show/expired/pending/changed bookings, hides legacy invalid rows and dedupes booking `SEATED` + closed order for the same real visit.
15. `feedback_submitted`, where emitted, fires once per submitted feedback and contains no raw comment text; `feedback_requested` remains absent while automated prompts are disabled.
