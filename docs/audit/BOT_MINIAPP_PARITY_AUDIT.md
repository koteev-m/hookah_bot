# Bot vs Mini App Parity Audit

Дата: 2026-05-25.

> Historical/partial snapshot. Not source of truth without checking `docs/UPDATED_PRODUCT_AI_ROADMAP.md`, `docs/audit/MINI_APP_LAUNCH_SMOKE_CHECKLIST.md` and current code.
>
> Current correction as of 2026-06-03: several P1 gaps described below were later fixed or changed by product decision. In particular, STAFF close bill/order is allowed in Mini App, manager/owner bill controls exist in Mini App, booking screens exist, guest history/favorites exist, pre-QR guest cards no longer expose structured order menu, and info/photo-menu media is served through backend proxy.
> Additional correction: STAFF booking management is split from order status updates. STAFF can view bookings and mark arrived/no-show; confirm/cancel/change/message/settings are MANAGER/OWNER-only.
> Additional correction as of 2026-06-10: Guest/Menu Options & Flavors parity is now an active P1 follow-up in `docs/UPDATED_PRODUCT_AI_ROADMAP.md`. Guest Bot supports hookah option/flavor UX, but selected flavors are not persisted as first-class order modifiers and Guest Mini App still lacks equivalent option selection.

Режим: read-only аудит. Backend, frontend business logic, миграции, checkout, ledger, promotions, loyalty и AI flows не менялись.

## 1. Executive Summary

Текущая готовность: Telegram bot остаётся самой полной поверхностью продукта. Mini App уже покрывает launch-critical operational core: guest catalog/table/menu/cart/checkout/active order/staff call, venue order queue/detail/full bill/status lifecycle, menu/stop-list/tables/staff/support, platform venues/subscription baseline.

Главный вывод: подтверждённых P0 по money correctness, checkout, staff notification, initData/session scoping или role leak в текущем кодовом срезе не найдено. Pilot smoke можно продолжать по core сценарию, но market launch ещё требует закрыть P1 parity gaps, чтобы Mini App не отставал от bot-first логики и не создавал operator confusion.

Mini App отстаёт в основном не по расчётам, а по ширине продукта:

- guest menu options/flavors are not yet structured parity: Bot has selection UX, Mini App lacks it, and order persistence is still item-only/comment-assisted;
- guest profile/promotions/loyalty progress screens не доведены до bot parity; history/favorites baseline уже есть;
- platform placements/support/analytics частично скрыты или объяснены как bot-only;
- final staging smoke remains required for initData, money, staff notifications and role denied paths.

Known fixed/outdated rows in this document:

- `Venue advanced bill management is bot-only` is outdated: Mini App has manager/owner controls for manual discounts and item exclude/restore.
- `STAFF close mismatch` is outdated: STAFF can close bill/order, while discount/exclude/restore remain manager/owner-only.
- `Bookings remain safely hidden` is outdated: Guest and Venue booking screens/actions exist.
- `Guest history/favorites missing` is outdated: Guest account baseline includes visit history and favorites.
- Pre-QR guest `🍽 Menu` behavior changed: order menu is available only after QR/table context; `📖 Фото-меню` is an info section.
- Info-section media parity changed: Mini App uses a backend media proxy instead of Telegram file IDs/raw file URLs.

## 2. Sources Used

Документы:

- `docs/UPDATED_PRODUCT_AI_ROADMAP.md`
- `docs/audit/MINI_APP_PRODUCTION_READINESS_AUDIT.md`
- `docs/audit/MINI_APP_LAUNCH_SMOKE_CHECKLIST.md`
- `docs/audit/ROLE_GUEST.md`
- `docs/audit/ROLE_MANAGER.md`
- `docs/audit/ROLE_STAFF.md`
- `docs/audit/ROLE_PLATFORM_OWNER.md`

Telegram bot:

- `backend/app/src/main/kotlin/com/hookah/platform/backend/telegram/TelegramBotRouter.kt`
- `backend/app/src/main/kotlin/com/hookah/platform/backend/telegram/TelegramKeyboards.kt`
- `backend/app/src/main/kotlin/com/hookah/platform/backend/telegram/StaffChatNotifier.kt`
- `backend/app/src/main/kotlin/com/hookah/platform/backend/telegram/TelegramTypes.kt`

Mini App backend:

- `backend/app/src/main/kotlin/com/hookah/platform/backend/miniapp/guest/*`
- `backend/app/src/main/kotlin/com/hookah/platform/backend/miniapp/venue/*`
- `backend/app/src/main/kotlin/com/hookah/platform/backend/miniapp/venue/orders/*`
- `backend/app/src/main/kotlin/com/hookah/platform/backend/platform/*`

Mini App frontend:

- `miniapp/src/main.ts`
- `miniapp/src/screens/guestApp.ts`
- `miniapp/src/screens/catalog.ts`
- `miniapp/src/screens/guestVenue.ts`
- `miniapp/src/screens/cart.ts`
- `miniapp/src/screens/order.ts`
- `miniapp/src/screens/venueApp.ts`
- `miniapp/src/screens/venueDashboard.ts`
- `miniapp/src/screens/venueOrders.ts`
- `miniapp/src/screens/venueOrderDetail.ts`
- `miniapp/src/screens/venueMenu.ts`
- `miniapp/src/screens/venueStaff.ts`
- `miniapp/src/screens/venueTables.ts`
- `miniapp/src/screens/venueSettings.ts`
- `miniapp/src/screens/supportScreens.ts`
- `miniapp/src/screens/platformApp.ts`
- `miniapp/src/screens/platformVenueDetail.ts`
- `miniapp/src/screens/platformCockpitSections.ts`
- `miniapp/src/shared/api/guestApi.ts`
- `miniapp/src/shared/api/guestDtos.ts`
- `miniapp/src/shared/api/venueApi.ts`
- `miniapp/src/shared/api/venueDtos.ts`
- `miniapp/src/shared/api/platformApi.ts`
- `miniapp/src/shared/api/platformDtos.ts`

## 3. Guest Parity Matrix

| Feature | Bot | Mini App | Status | Gap | Priority | Recommendation |
|---|---|---|---|---|---|---|
| `/start` без QR | Role-aware guest menu, catalog entry, Mini App entry, venue onboarding lead | `mode=guest` opens catalog through WebApp auth | Partial parity | Bot has richer entry menu and onboarding actions | P2 | Keep bot as entrypoint; enrich Mini App catalog after pilot smoke. |
| `/start <tableToken>` / QR | Resolves table, shows table context menu and Mini App choice | Resolves table by token, hides QR CTA after context, routes to venue/menu/cart/order | Done | Needs runtime smoke in Telegram only | P2 | Continue manual smoke with real WebApp `initData`. |
| Table context actions | Menu, cart, my order, quick order, shared bill, staff call, venue promos, favorites, history, profile, change table | Menu, cart, my order, support, staff call; bookings safe fallback | Partial | Profile/history/favorites/promos/change-table are not first-class Mini App actions | P1 | Add guest account hub: profile, history, favorites, venue promotions, loyalty progress. |
| Menu | Bot menu supports order flow and richer callbacks, including hookah flavor/option selection | Venue menu lists categories/items, add to cart, unavailable disabled, but does not expose option/flavor picker | Partial | Guest Mini App does not expose item options/modifiers/photos/descriptions as order modifiers; Bot flavor choice is comment-assisted rather than structured persistence | P1 | Implement Guest/Menu Options & Flavors parity from the active roadmap: backend selected-option persistence, Bot structured submit, Mini App option picker and smoke closure. |
| Cart / checkout | Bot cart includes promo/loyalty preview and fallback quick order | Cart submits scoped `tableSessionId/tabId`, supports personal/shared tabs and fallback chat command | Done / smoke | Cart UI shows basic item totals, not full preview breakdown before checkout | P2 | Add cart preview breakdown later if product needs pre-checkout transparency. |
| Active order / My order | Bot active order shows status and bill lines | Mini App active order uses scoped `tableSessionId/tabId`, shows items, prices, promo/loyalty/manual totals, final total | Done | Needs cross-channel snapshot/manual comparison | P2 | Keep manual parity checklist until snapshot tests exist. |
| Shared bill / tabs | Bot has shared bill entry and invite flow | Cart screen supports personal/shared tab selection, create/join invite | Partial | Table home has no explicit `Общий счёт` action; behavior is buried inside cart | P1 | Add a clear table-home entry or surface tab mode more explicitly in cart header. |
| Quick order / fallback order | Bot has `✍️ Быстрый заказ` | Mini App fallback opens supported bot flow | Partial | Mini App intentionally does not replicate chat quick order | P2 | Keep as fallback; do not rebuild quick order in Mini App before core launch. |
| Staff call | Bot table context can create staff call | Mini App staff call sends `tableSessionId` and backend notifies staff chat | Done | Runtime smoke still required | P2 | Keep smoke checklist step mandatory for each pilot venue. |
| Venue promotions | Bot exposes `🎁 Акции заведения` and global promotions | Mini App has no dedicated venue promotions screen | Missing in Mini App | Guest cannot inspect active venue promotions outside order/bill context | P1 | Add read-only venue promotions screen before market launch, or remove/avoid Mini App promise. |
| Loyalty progress | Bot profile shows progress and reward targets; cart/order shows redemption lines | Mini App active order/full bill shows loyalty discounts; no profile loyalty screen found | Partial | Progress and available rewards are not visible as standalone Mini App account UX | P1 | Add loyalty progress to guest account hub; keep checkout logic unchanged. |
| Profile | Bot supports guest profile and loyalty entry | No Mini App profile route in `guestApp.ts` | Missing in Mini App | Launch roadmap expects profile baseline | P1 | Add profile route or clearly keep profile bot-only from Mini App support text. |
| History / repeat order | Bot has visit history/detail/repeat flow | Backend visit routes exist; no Mini App history screen found | Missing in Mini App | Guest cannot review past visits/orders in Mini App | P1 | Add history list/detail using existing `GuestVisitRoutes`; repeat order can remain bot-only initially. |
| Favorites | Bot favorite venues/items flows exist | Backend favorites routes exist; no Mini App favorite screen found | Missing in Mini App | Favorites button/function is bot-only | P1 | Add favorites view or keep bot-only until account hub block. |
| Bookings | Bot booking flow exists | Mini App bookings route shows safe unavailable message | Safe fallback | Not parity, but not half-working | P1 if bookings launch scope | Keep hidden/safe unless bookings are in pilot scope; otherwise implement minimal screens later. |
| Support | Bot chat remains support/fallback path | Guest support screen is informational and points to staff call/bot | Launch-safe | No ticket automation | P2 | Accept for pilot; design tickets later. |

## 4. Venue / Staff / Manager Parity Matrix

| Feature | Bot | Mini App | Status | Gap | Priority | Recommendation |
|---|---|---|---|---|---|---|
| Venue panel entry | Bot shows role-aware `📱 Панель заведения` WebApp entry | Venue Mini App opens in `mode=venue` and checks access | Done | Needs ongoing initData smoke | P2 | Keep WebApp button smoke in release checklist. |
| Overview | Bot role menus are broad | Mini App overview shows venue name, Russian role label, new/in-work counts, staff chat status | Done | Counts are operational, not analytics | P2 | Add richer dashboard later. |
| Orders queue | Bot has staff/manager queue and staff chat notifications | Mini App queue shows display number, table, status, item count, comment | Done | No advanced filtering by source/guest yet | P2 | Keep for launch; add filters after pilot. |
| Order lifecycle | Bot staff chat supports accept/deliver/close bill path; venue callbacks update statuses | Mini App shows status actions by current status and uses backend transition route | Partial parity | STAFF cannot close from Mini App route, while bot staff chat has close bill action path | P1 | Decide policy: either allow staff close in Mini App when bot allows it, or restrict bot to same roles. |
| Guest notifications | Bot status changes notify guest | Mini App status route enqueues guest notifications for accepted/delivered/closed | Done | Needs smoke with real outbox worker | P2 | Keep in pilot checklist. |
| Full bill display | Bot full bill shows gross/manual/promo/loyalty/excluded/final | Mini App order detail shows management bill from backend DTO | Done | Snapshot parity not automated end-to-end | P2 | Add cross-channel bill snapshot test later. |
| Manual discount / exclusion | Bot has item exclusion and discount dialog states | Mini App shows safe management notice, no controls | Safe fallback / not parity | Operator must switch to bot for bill editing | P1 | Implement minimal bill management controls in Mini App or keep bot-only with direct bot deep-link/context. |
| Cancel/reject order/batch/item | Bot has richer cancel/reject flows | Mini App has order reject for owner/manager; no item-level cancel/exclude controls | Partial | Item/batch granular management is bot-only | P1 | Add only backend-supported actions; otherwise keep explicit bot-only text. |
| Staff calls lifecycle | Bot has active calls and callbacks for ack/done in router paths | Mini App has support/staff screens; route details need smoke for ack/done parity | Partial | Staff call lifecycle may still be more visible in bot than Mini App | P1 | Add/verify Venue Mini App active staff call list with ack/done smoke tests. |
| Menu CRUD | Bot owner/manager menu management exists | Mini App menu supports category/item CRUD, reorder, item availability | Done / partial | Uses prompt-style inputs; not polished | P2 | Accept for pilot; later replace prompts with forms. |
| Option stop-list | Bot stop-list supports item/options, base flavor profile apply and normalize helpers | Mini App displays item-scoped options/flavors, structured guest selected options, explicit item/option stop-list toggles and shared hookah-only `Добавить базовые вкусы` | Mostly aligned | Mini App still lacks bot's normalize/reset helper; broad menu IA parity remains separate | P2 | Keep shared base profile apply in regression smoke; add normalize/reset only if venues need it after pilots. |
| Option line preference note | Bot renders structured order/read-model lines but does not yet collect optional preparation notes | Guest Mini App collects optional `Пожелание к вкусу` on selected option lines | Partial | Bot input parity for line-level preference note is missing; note must remain separate from structured flavor and have no price semantics | P1 follow-up | Add a small Guest Bot conversational step for optional line note after flavor selection, or explicitly skip with “без пожеланий”. |
| Tables / QR | Bot tables/QR flows exist | Mini App tables screen supports create, rotate, rotate all by permission, QR export | Partial | Staff sees tables if backend permission allows read; product policy should confirm | P2 | Align staff table visibility with role matrix before launch. |
| Staff management | Bot owner staff management exists | Mini App staff screen supports list, invite, role update/remove by permissions | Partial | Member labels are `User <id>`, not names; manager invite capability is conservative | P2 | Polish identity display; keep permissions conservative. |
| Staff chat link | Bot supports staff chat bind/status | Mini App chat link/status screen exists | Partial | Needs runtime verification in Telegram group | P2 | Include group binding in venue pilot runbook. |
| Settings | Bot remains canonical for venue settings | Mini App settings nav hidden; direct route has safe message | Launch-safe | Not parity | P1 if settings required in Mini App | Keep hidden for pilot; implement real settings later. |
| Bookings | Bot has booking list/actions | Mini App route shows safe unavailable message | Launch-safe | Not parity | P1 if bookings pilot scope | Keep hidden unless pilot requires booking operations. |
| Stats | Bot owner/manager stats exist | No Venue Mini App stats screen found | Missing in Mini App | Manager cannot use Mini App as stats cockpit | P2 | Add after operational launch blockers. |
| Marketing/promotions/loyalty management | Bot marketing hub is rich | Venue Mini App has no marketing hub | Bot-only | Not exposed as Mini App dead-end | P2 | Keep bot canonical; Mini App marketing builder belongs later phase. |

## 5. Owner Parity Matrix

| Feature | Bot | Mini App | Status | Gap | Priority | Recommendation |
|---|---|---|---|---|---|---|
| Venue profile/settings | Bot owner flows edit profile, description, hours, contacts and settings | Mini App settings hidden safely | Safe fallback | Owner must use bot | P1 | Implement real settings screen only for backend-supported fields. |
| Menu management | Bot owner menu management exists | Mini App supports core menu CRUD and availability | Partial parity | Mini App does not cover all rich content/media/flavor profile flows | P2 | Keep current operational menu; defer rich content. |
| Tables/QR | Bot owner tables/QR flow exists | Mini App tables/QR screen exists | Partial parity | QR export/runtime download needs smoke | P2 | Add to pilot smoke. |
| Personnel | Bot owner staff management exists | Mini App staff management exists | Partial parity | Identity labels and invite UX less friendly | P2 | Polish after pilot. |
| Promotions / marketing hub | Bot supports promotions, banners, placements, top, AI helper | Mini App does not expose marketing hub | Bot-only | Expected by updated roadmap as later Mini App/AI-assisted layer | P2 | Do not add before Mini App operational core is stable. |
| Loyalty setup | Bot owner/manager controls loyalty program, targets, status | Mini App does not expose loyalty setup | Bot-only | No Mini App parity | P2 | Keep bot canonical for launch. |
| Reviews / review link | Bot supports reviews and public review link flows | No Mini App review cockpit found | Bot-only | Not launch-critical for order pilot | P2 | Add when venue cockpit expands. |
| Statistics | Bot stats exist | No Venue Mini App stats screen found | Bot-only | Manager/owner analytics not in Mini App | P2 | Add read-only stats dashboard later. |
| Support | Bot remains operational support path | Mini App support screen is safe/informational | Launch-safe | No ticket automation | P2 | Accept for pilot. |

## 6. Platform Owner Parity Matrix

| Feature | Bot | Mini App | Status | Gap | Priority | Recommendation |
|---|---|---|---|---|---|---|
| Platform panel entry | Bot has `📱 Панель платформы` WebApp entry | `mode=platform` checks platform access | Done | Platform owner config split risk should stay monitored | P2 | Keep config smoke in deployment runbook. |
| Venue list/detail | Bot has platform menus; some old sections were placeholder | Mini App has venue list/detail/status/owner/subscription basics | Mini App stronger | Bot/Mini App responsibilities differ | P2 | Treat Mini App as platform cockpit baseline. |
| Venue lifecycle | Bot supports platform status flows in promotion/platform areas | Mini App supports status actions | Partial parity | Terminology and readiness checks need product alignment | P2 | Add lifecycle/readiness labels later. |
| Owner assignment/invite | Bot platform onboarding exists | Mini App supports owner assignment and invite generation | Partial | Owner invite completion/deep link flow should be smoke-tested | P1 | Verify owner invite end-to-end; fix if invite cannot be accepted cleanly. |
| Subscriptions/pricing | Bot subscription menus may be limited | Mini App supports subscription and price schedule basics | Partial | No full billing/invoice cockpit in Mini App | P1/P2 | Add invoice/payment cockpit if required for launch operations. |
| Onboarding requests | Bot handles connection requests | Mini App safe section says requests remain in bot | Safe fallback | Platform operator must switch surfaces | P1 | Add onboarding request list if platform pilot depends on Mini App. |
| Placements | Bot supports banner/top placements management | Mini App safe section says placements remain in bot | Safe fallback | Not parity | P1 | Add read-only pending/active placements summary before market launch. |
| Support | No full ticket system | Mini App safe support section, no fake tickets | Launch-safe | Manual support only | P2 | Accept for pilot; build tickets later. |
| Analytics | Bot/platform reports partial | Mini App safe analytics section, no fake numbers | Launch-safe | No platform analytics dashboard | P2 | Add real read model later. |

## 7. Promotions / Loyalty Parity

| Feature | Bot | Mini App | Status | Gap | Priority | Recommendation |
|---|---|---|---|---|---|---|
| Promotion setup | Bot supports TEXT/BANNER/HH/GIFT, rules, schedule, stackability, archive | Mini App has no promotion management | Bot-only | Not promised in Mini App UI | P2 | Keep bot canonical until AI-assisted owner onboarding phase. |
| Promotion feed | Bot has global and venue-specific promotions | Mini App catalog/venue screens do not show dedicated promotions feed | Missing in Mini App | Guests cannot browse promotions in Mini App except via order/bill impact | P1 | Add read-only venue promotions and catalog promotion teaser. |
| Cart/checkout promo calculation | Bot and Mini App use backend checkout/read models | Mini App displays active order and venue bill promo lines from backend | Done | Cart pre-submit preview is lighter | P2 | Optional cart preview polish later. |
| Loyalty setup | Bot owner/manager setup and targets | Mini App no setup | Bot-only | Not launch blocker | P2 | Keep in bot. |
| Loyalty redemption display | Bot shows loyalty in cart/order/full bill | Mini App order/full bill display loyalty line | Done | Guest standalone progress missing | P1 | Add guest loyalty progress screen in account hub. |
| Promo/loyalty breakdown | Bot staff/full bill/history show breakdown | Mini App active order and venue bill show discount breakdowns | Done / partial | History Mini App not present | P1 | Add history screens using existing backend visit DTOs. |
| Placements/top | Bot platform/marketing flows exist | Platform Mini App safe fallback only | Safe fallback | Not parity | P1 | Add platform placements cockpit summary. |

## 8. Permissions / Role Parity

| Role | Bot | Mini App | Status | Gap | Priority | Recommendation |
|---|---|---|---|---|---|---|
| GUEST | Full guest menu and table context in bot | Guest Mini App has catalog/table/menu/cart/order/support; account features missing | Partial | Profile/history/favorites/promos/loyalty progress absent | P1 | Build guest account hub and read-only venue promotions. |
| STAFF | Bot staff can operate orders/calls and operational booking arrival/no-show actions | Mini App permits queue/status/menu/tables read-only and operational booking arrival/no-show actions; dangerous actions are denied backend-side | Mostly aligned | Remaining gaps should be verified by smoke, especially old callbacks and direct API denied paths | P1 smoke | Keep STAFF matrix in role docs and release checklist. |
| MANAGER | Bot broad operational/marketing menu | Mini App operational panel, no marketing/stats/settings | Partial | Marketing/stats remain bot-only; settings hidden | P2 | Accept for pilot, document bot canonical flows. |
| OWNER | Bot full venue setup/marketing/loyalty/reviews | Mini App operational panel plus staff/menu/tables; settings hidden | Partial | Owner product setup mostly bot-only | P1/P2 | Add real settings screen next if launch requires Mini App owner setup. |
| PLATFORM_OWNER | Bot has platform flows; old docs mention some placeholders | Mini App has venue/subscription baseline and safe sections | Partial | Platform onboarding/placements/support/analytics not full parity | P1 | Add platform cockpit summaries for onboarding and placements first. |

Security notes:

- Guest active order path now sends `tableSessionId` and `tabId`; backend enforces tab membership.
- Mini App route access is backend-enforced even where frontend nav is broader.
- No Mini App AI entry promising unavailable AI flows was found; internal AI assistant remains Telegram bot only.

## 9. Remaining P0

No confirmed remaining P0 was found in this read-only pass.

P0-sensitive areas that must stay in pilot smoke:

- Telegram WebApp opens with non-empty `initData`.
- Guest order and active order remain scoped to current `tableSessionId/tabId`.
- Staff chat receives Mini App order and staff call notifications.
- Venue Mini App final payable total matches Telegram full bill for the same order.
- Role boundaries are checked for STAFF/MANAGER/OWNER/PLATFORM_OWNER.

## 10. Remaining P1

Current correction note:

- P1.2, P1.3, P1.4 and P1.6 are historical as written. Keep them only as evidence of earlier gaps; use `docs/UPDATED_PRODUCT_AI_ROADMAP.md` and the launch smoke checklist for current backlog.
- Still active from this section: Guest profile/promotions/loyalty parity, Platform cockpit partial sections, final staging smoke, frontend/browser e2e gap and broad test heap/runtime risk.

### P1.1 — Guest account hub parity is missing

Why it matters: Bot has profile, loyalty, history, favorites and venue promotions; Mini App guest shell only exposes catalog/table/menu/cart/order/support. This blocks a complete launch-safe guest Mini App experience.

Affected modules:

- `miniapp/src/screens/guestApp.ts`
- `miniapp/src/shared/api/guestApi.ts`
- `backend/app/src/main/kotlin/com/hookah/platform/backend/miniapp/guest/GuestVisitRoutes.kt`
- `backend/app/src/main/kotlin/com/hookah/platform/backend/miniapp/guest/GuestFavoritesRoutes.kt`
- loyalty/progress repository and existing bot profile flow.

Acceptance:

- Mini App has `👤 Профиль`, `🕘 История`, `⭐ Любимое`, `🎁 Акции` or clearly does not show/claim them.
- Guest loyalty progress is readable without entering Telegram profile.
- History uses backend visit DTO and does not recalculate money on frontend.

### P1.2 — Venue advanced bill management is bot-only

Why it matters: Mini App displays full bill correctly, but manual discounts, item exclusion/cancel and granular batch/item management are available in Telegram bot only. Operators may expect the same controls in the venue cockpit.

Affected modules:

- `miniapp/src/screens/venueOrderDetail.ts`
- `backend/app/src/main/kotlin/com/hookah/platform/backend/telegram/TelegramBotRouter.kt`
- `backend/app/src/main/kotlin/com/hookah/platform/backend/telegram/db/VenueOrdersRepository.kt`
- venue order routes if controls are added.

Acceptance:

- Either supported controls are implemented using existing backend routes, or Mini App exposes a direct, clear bot fallback for this exact order.
- No half-working buttons.
- Full bill calculations remain backend-owned.

### P1.3 — STAFF order/stop-list permissions differ by surface

Why it matters: Staff-facing capabilities must not depend on whether staff uses Telegram bot or Mini App. Current code suggests Mini App disallows STAFF closing an order while bot staff chat has a close bill path; older stop-list flows also require policy alignment.

Affected modules:

- `backend/app/src/main/kotlin/com/hookah/platform/backend/miniapp/venue/VenueRbac.kt`
- `backend/app/src/main/kotlin/com/hookah/platform/backend/miniapp/venue/orders/VenueOrderRoutes.kt`
- `miniapp/src/screens/venueOrderDetail.ts`
- `backend/app/src/main/kotlin/com/hookah/platform/backend/telegram/TelegramBotRouter.kt`
- `backend/app/src/main/kotlin/com/hookah/platform/backend/telegram/TelegramKeyboards.kt`

Acceptance:

- One documented STAFF permission matrix.
- Bot and Mini App show the same allowed operational actions, or intentionally explain why one surface is restricted.
- Tests cover STAFF accept/deliver/close/reject/stop-list allowed/denied behavior.

### P1.4 — Staff call lifecycle parity needs final verification

Why it matters: Guest calls are created and staff chat notification is covered, but operator ack/done lifecycle should be equally usable in bot and Mini App for pilots.

Affected modules:

- `miniapp/src/screens/venueStaff.ts`
- venue staff call routes/repositories, if present.
- `backend/app/src/main/kotlin/com/hookah/platform/backend/telegram/TelegramBotRouter.kt`

Acceptance:

- Venue Mini App can show active calls and perform supported lifecycle actions, or states clearly that lifecycle is handled in Telegram bot.
- STAFF/MANAGER/OWNER permissions are tested.

### P1.5 — Platform cockpit sections are safe fallbacks, not parity

Why it matters: Platform Mini App is launch-safe, but onboarding requests, placements, support and analytics are informational sections. Platform operator still needs Telegram bot for important platform flows.

Affected modules:

- `miniapp/src/screens/platformCockpitSections.ts`
- `miniapp/src/screens/platformApp.ts`
- platform promotion/placement repositories and bot flows.

Acceptance:

- At minimum, add read-only pending/active placements and onboarding request summaries, or keep these explicitly bot-only in pilot runbook.
- No fake analytics or fake ticket controls.

### P1.6 — Bookings remain safely hidden

Why it matters: This is acceptable if bookings are out of pilot scope, but not market-ready if venues expect Mini App booking operations.

Affected modules:

- `miniapp/src/screens/guestApp.ts`
- `miniapp/src/screens/venueApp.ts`
- `backend/app/src/main/kotlin/com/hookah/platform/backend/miniapp/guest/GuestBookingRoutes.kt`
- `backend/app/src/main/kotlin/com/hookah/platform/backend/miniapp/venue/bookings/VenueBookingRoutes.kt`

Acceptance:

- Product decision: bookings excluded from pilot, or minimal working guest/venue booking screens are implemented.

## 11. P2 Backlog

- Rich guest catalog cards: media, hours, contacts, public promotion teasers.
- Guest menu media/descriptions/top-list.
- Cart pre-submit promo/loyalty preview polish.
- Cross-channel bill snapshot tests.
- Venue dashboard stats.
- Venue menu forms instead of `window.prompt`.
- Staff/member display names instead of raw `User <id>`.
- Platform analytics dashboard backed by real read models.
- Platform billing invoices/payments cockpit.
- Mini App AI-assisted flows after operational Mini App hardening.
- Telegram Guest Mode / Business Bots / Managed Bots remain future layers per canonical roadmap.

## 12. Recommended Fix Order

1. Guest account hub parity:
   - profile shell;
   - loyalty progress;
   - visit history;
   - favorites;
   - venue promotions.
2. STAFF permission alignment:
   - close order policy;
   - stop-list policy;
   - tests for bot/Mini App allowed actions.
3. Venue order management fallback:
   - direct bot guidance for current order;
   - then minimal Mini App controls for supported manual discount/exclusion if still needed.
4. Staff call lifecycle parity:
   - active calls list;
   - ack/done or safe bot-only message.
5. Platform cockpit read-only summaries:
   - onboarding requests;
   - pending/active placements.
6. Bookings decision:
   - keep safely hidden for pilot or implement minimal screens.
7. P2 polish and frontend e2e harness.

## 13. First Implementation Prompt

```text
Ты senior Kotlin/Ktor + Vite Mini App + Telegram Bot product engineer.

Контекст:
Главный roadmap-source:
- docs/UPDATED_PRODUCT_AI_ROADMAP.md

Новый supporting audit:
- docs/audit/BOT_MINIAPP_PARITY_AUDIT.md

Goal:
Закрыть первый P1 Bot vs Mini App parity fix-pack: Guest account hub parity.

Scope:
1. Добавить в Guest Mini App launch-safe account hub:
   - 👤 Профиль
   - 🎁 Лояльность
   - 🕘 История
   - ⭐ Любимое
   - 🎁 Акции заведения / текущего venue, если есть table context.
2. Использовать существующие backend read models/routes там, где они уже есть:
   - GuestVisitRoutes;
   - GuestFavoritesRoutes;
   - loyalty progress repository/routes if already exposed, otherwise add minimal read-only route;
   - venue promotions read model if already available.
3. Не пересчитывать деньги на frontend.
4. Не добавлять checkout/ledger/promotions/loyalty business logic.
5. Если какой-то read-only backend route отсутствует и scope растёт, показать safe bot-only message instead of half-working UI.

Do not change:
- checkout calculations;
- promo ledger persistence;
- loyalty redemption/accrual logic;
- DB schema/migrations unless absolutely unavoidable;
- AI/Guest Mode/Managed Bots;
- Venue/Platform Mini App behavior.

Tests/validation:
./gradlew --no-daemon :backend:app:compileKotlin --console=plain
./gradlew --no-daemon :backend:app:test \
  --tests "*GuestVisitRoutesTest*" \
  --tests "*GuestOrderRoutesTest*" \
  --tests "*TelegramBotRouterTableTokenTest*" \
  --tests "*TelegramKeyboardsTest*" \
  --console=plain
cd miniapp && npm run build
```
