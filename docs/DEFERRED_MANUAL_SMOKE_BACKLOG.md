# Deferred Manual Smoke Backlog

Дата актуализации: 2026-08-04.

Статус: **current product reference / ACTIVE BACKLOG**.

## Purpose

Этот документ — единственный canonical backlog обязательных ручных smoke-проверок, которые
нельзя выполнить сейчас из-за отсутствующего окружения, тестовых данных, внешних интеграций
или физических prerequisites.

Он дополняет общую QA-стратегию из
[`docs/TESTING_QA_SMOKE_STRATEGY.md`](TESTING_QA_SMOKE_STRATEGY.md), но не дублирует её:

- автоматическая локальная проверка не закрывает environment-dependent manual smoke;
- отложенная проверка не считается выполненной без записанного результата;
- открытая запись сохраняет production-readiness gate для своего сценария, но не блокирует
  разработку независимых bounded runtime-блоков;
- подробные шаги хранятся здесь, а audit/roadmap/product docs ссылаются на запись;
- для одной и той же проверки нельзя создавать второй параллельный QA backlog.

## Statuses

| Status | Meaning |
| --- | --- |
| `PLANNED` | Проверка обязательна, но prerequisites ещё не оценены полностью. |
| `BLOCKED_BY_ENVIRONMENT` | Проверка не может быть запущена из-за отсутствующего окружения, данных, интеграции или физического prerequisite. |
| `READY_TO_RUN` | Все prerequisites подтверждены, проверку можно начинать. |
| `IN_PROGRESS` | Ручной прогон начат, но итог ещё не зафиксирован. |
| `PASSED` | Все обязательные сценарии прошли, cleanup выполнен, дата/исполнитель/результат записаны. |
| `FAILED` | Найден дефект или обязательный expected result не подтверждён. |
| `SUPERSEDED` | Проверка заменена другой canonical записью; должна содержать ссылку на замену и причину. |

## Required Entry Shape

Каждая запись хранит:

- ID;
- feature;
- priority;
- current status;
- reason deferred;
- prerequisites;
- automated evidence already available;
- manual steps;
- expected results;
- cleanup/restoration steps;
- result/date/actor placeholders;
- blocking impact;
- related docs.

## Backlog Index

| ID | Feature | Priority | Current status | Blocking impact |
| --- | --- | --- | --- | --- |
| [`REPEAT-MANUAL-001`](#repeat-manual-001) | Repeat as Template Phase 1 | P1 | `BLOCKED_BY_ENVIRONMENT` | Repeat production-readiness remains open for environment-dependent parity/privacy/context scenarios; independent bounded development may continue. |
| [`CATALOG-SEARCH-MANUAL-001`](#catalog-search-manual-001) | Catalog Search and Filter Phase 1 | P2 | `BLOCKED_BY_ENVIRONMENT` | Does not block the current MVP/release; required before catalog pagination, ranking, map/geo or a large pilot rollout. |
| [`STAFF-IDENTITY-MANUAL-001`](#staff-identity-manual-001) | Staff Identity create-from-member free-account scenario | P2 | `BLOCKED_BY_ENVIRONMENT` | Non-blocking coverage gap only; Identity Linking remains `DONE / MVP / STAGING-SMOKE-PASSED`. |

## STAFF-IDENTITY-MANUAL-001

- **Feature:** Staff Identity Linking UX + Duplicate Prevention
- **Priority:** P2 / non-blocking
- **Current status:** `BLOCKED_BY_ENVIRONMENT`
- **Runtime status:** `DONE / MVP / STAGING-SMOKE-PASSED`
- **Reason deferred:** current smoke records do not identify a separate accepted Staff account in
  the venue that had no active linked staff card. Automated create-from-member/concurrency evidence
  and the broader identity-linking manual smoke are complete, but they are not a substitute for this
  exact environment-dependent UI path.
- **Blocking impact:** none for the completed Identity Linking MVP and no downgrade of the completed
  Optional Team/Schedule Module Slice B. Run before a broader staff-directory pilot when a
  disposable free Staff member is available. Do not create a second backlog entry for this case.

### Prerequisites

- one staging venue with Owner access;
- one accepted `STAFF` venue member whose `disabled_at`-active profile link count is zero;
- the member's current safe Telegram `displayName` and nullable username available through normal
  authentication/upsert;
- a cleanup plan that preserves memberships and unrelated schedule/history rows.

### Automated Evidence Already Available

- create-from-member derives current identity server-side and requires explicit subtype;
- the created profile is an active Guest-hidden draft;
- one-active-link enforcement and typed duplicate conflict are transaction-bound;
- PostgreSQL concurrent create/relink tests passed with one winner and winner-only audit;
- Guest DTO/privacy, protected profile and venue/account isolation regressions passed.

### Manual Steps And Expected Results

1. Owner opens `Доступ сотрудников` for the staging venue.
2. Confirm the free Staff member is shown by human `displayName`, `@username` or `Без username`,
   role and `NOT_LINKED` state without a raw-id primary label.
3. Select `Создать карточку`, choose an explicit subtype and save.
4. Confirm exactly one card is created with the server-derived current identity, hidden from Guest;
   the member row changes to `Открыть карточку`, and a second active link is rejected.
5. Confirm no automatic Guest Today publication, no second profile row and no unrelated
   schedule/history mutation.
6. Cleanup by disabling/unlinking only the disposable test card through the normal authorized flow;
   verify the venue membership and unrelated rows remain.

### Result

- **Result:** not run; no qualifying free Staff account is recorded.
- **Date / actor:** pending.
- **Cleanup:** pending with the scenario.
- **Related docs:** `docs/STAFF_PROFILES_SHIFTS_TIPS.md`,
  `docs/TESTING_QA_SMOKE_STRATEGY.md`,
  `docs/audit/MINI_APP_LAUNCH_SMOKE_CHECKLIST.md`.

## REPEAT-MANUAL-001

- **Feature:** Repeat as Template Phase 1
- **Priority:** P1
- **Current status:** `BLOCKED_BY_ENVIRONMENT`
- **Runtime status:** `MVP IMPLEMENTED / LOCAL VALIDATION PASSED / MANUAL ENVIRONMENT-DEPENDENT SMOKE DEFERRED`
- **Reason deferred:** нет второго тестового venue; нет подготовленного набора
  физических/реальных QR-кодов; нельзя полноценно проверить wrong-venue scenario; не
  подготовлены управляемые test items/options для availability и price changes; часть
  Bot ↔ Mini App parity и personal/shared-tab сценариев требует расширенного окружения.
- **Blocking impact:** production-readiness gate для перечисленных Repeat-сценариев остаётся
  открытым. Не использовать `STAGING-SMOKE-PASSED` до закрытия этой записи. Разработка
  следующих независимых bounded блоков разрешена.

### Prerequisites

- Venue A в `PUBLISHED` и guest-available состоянии;
- Venue B в `PUBLISHED` и guest-available состоянии;
- реальный table context в обоих venues;
- Guest A;
- Guest B;
- один completed order в Venue A;
- booking-only `SEATED` visit без order;
- completed order с несколькими позициями;
- позиция с selected option;
- позиция с note/comment;
- возможность временно менять item availability;
- возможность временно менять option availability;
- возможность временно менять item price;
- personal tab Guest A;
- при возможности shared tab с членством;
- доступ к Telegram repeat flow;
- сохранённое исходное состояние цен и availability для восстановления после теста.

### Automated Evidence Already Available

- targeted backend tests passed;
- `compileKotlin` passed;
- `ktlintCheck` passed;
- Mini App build passed;
- Playwright smoke `64/64` passed;
- `git diff --check` passed;
- no migration required.

Это evidence подтверждает локальную реализацию, но не заменяет manual smoke ниже.

### Manual Steps And Expected Results

#### 1. Booking-only visit

**Steps**

1. Guest открывает `Профиль → История`.
2. Guest открывает booking-only `SEATED` visit.

**Expected**

- видно посещение по брони;
- действия `Повторить заказ` нет.

#### 2. No table context

**Steps**

1. Guest выходит из table context.
2. Guest открывает `Профиль → История → completed order`.
3. Guest нажимает `Повторить заказ`.

**Expected**

- показан текст `Чтобы повторить заказ, отсканируйте QR на столе в этом заведении.`;
- cart не меняется;
- order/batch не создаётся;
- staff-chat notification не создаётся.

#### 3. Wrong venue

**Steps**

1. Guest устанавливает table context Venue B.
2. Guest открывает completed order из Venue A.
3. Guest нажимает repeat.

**Expected**

- показан текст `Этот заказ можно повторить только в том же заведении.`;
- cart Venue B не меняется;
- order/batch не создаётся.

#### 4. Correct same-venue context

**Steps**

1. Guest устанавливает table context Venue A.
2. Guest открывает completed order Venue A.
3. Guest нажимает repeat.

**Expected preview**

- показаны eligible lines;
- показаны quantities;
- показаны selected options;
- показан safe note/comment;
- показаны current prices;
- показаны current option price deltas;
- показан current total;
- показаны skipped lines с причинами;
- доступна кнопка `Добавить в корзину`;
- до подтверждения cart не меняется.

#### 5. Cart-only mutation

**Steps**

1. В корректном preview нажать `Добавить в корзину`.

**Expected**

- показан текст `Доступные позиции добавлены в корзину.`;
- появляется `Перейти в корзину`;
- quantity/options/note сохранены;
- order/batch ещё не создан;
- staff-chat notification отсутствует;
- batch создаётся только через обычный cart preview/add-batch flow.

#### 6. Duplicate-click guard

**Steps**

1. Открыть repeat preview.
2. Быстро несколько раз нажать `Добавить в корзину`.

**Expected**

- pending/disabled guard работает;
- одна pending operation не создаёт случайные дубли;
- итоговое количество в cart корректно.

#### 7. Unavailable item

**Steps**

1. Сохранить исходное availability позиции.
2. Временно выключить одну исходную позицию.
3. Повторить заказ.

**Expected**

- позиция находится в skipped;
- показана понятная причина;
- остальные eligible lines остаются доступны;
- после подтверждения добавляются только eligible lines.

**Scenario cleanup**

- вернуть item availability в сохранённое исходное состояние.

#### 8. Unavailable selected option

**Steps**

1. Сохранить исходное availability selected option.
2. Временно выключить selected option исходной строки.
3. Повторить заказ.

**Expected**

- вся строка пропускается;
- option не заменяется молча;
- позиция не добавляется без required historical option;
- видна причина `Выбранный вариант больше недоступен` или безопасный эквивалент.

**Scenario cleanup**

- вернуть option availability в сохранённое исходное состояние.

#### 9. All lines unavailable

**Steps**

1. Сохранить исходное availability всех повторяемых позиций.
2. Временно выключить все повторяемые позиции.
3. Повторить заказ.

**Expected**

- показан текст `Сейчас ни одну позицию из этого заказа повторить нельзя.`;
- cart не меняется.

**Scenario cleanup**

- вернуть availability всех позиций в сохранённое исходное состояние.

#### 10. Current price

**Steps**

1. Сохранить исходную цену позиции.
2. Временно изменить цену позиции.
3. Повторить старый заказ.

**Expected**

- preview использует текущую цену;
- историческая цена не используется как новая цена;
- текущий total пересчитан сервером.

**Scenario cleanup**

- вернуть исходную цену.

#### 11. Multiple orders in one visit

**Steps**

1. Открыть visit с несколькими completed orders.
2. Последовательно выбрать разные orders для repeat.

**Expected**

- пользователь выбирает конкретный order;
- разные orders не объединяются молча;
- `sourceOrderId` соответствует выбранному order.

#### 12. Personal-tab privacy

**Steps**

1. Guest A повторяет свой заказ в свой personal tab.
2. Guest B пытается получить тот же visit/order.
3. Guest B пытается использовать personal tab Guest A.

**Expected**

- foreign visit/order недоступен;
- Guest B не может использовать personal tab Guest A;
- возвращается безопасный 404/denial в текущем стиле.

#### 13. Shared-tab membership

**Steps**

1. Участник shared tab повторяет свой заказ в shared tab.
2. Пользователь без membership пытается использовать тот же shared tab.

**Expected**

- повтор разрешён только при действующем membership;
- пользователь без membership получает denial.

#### 14. Telegram parity

**Steps**

1. Через Telegram Bot открыть repeat flow для того же completed order.
2. Сравнить preview/outcome с Mini App.
3. Подтвердить добавление в bot draft cart.

**Expected**

- используются те же current prices;
- действуют те же availability rules;
- показаны те же skipped reasons;
- ничего не отправляется автоматически;
- eligible lines добавляются в bot draft cart;
- order/batch не создаётся до обычного подтверждения.

#### 15. Regression

Проверить:

- Guest History list/detail;
- Post-Visit Feedback;
- Guest Favorites;
- Catalog;
- Cart;
- Active Order;
- Chats;
- Help;
- booking-only visit rendering.

**Expected**

- перечисленные закрытые потоки не имеют новых регрессий;
- Repeat не меняет их ownership, navigation или mutation semantics.

### Cleanup / Restoration

После прогона:

1. Восстановить исходные item prices.
2. Восстановить item и option availability.
3. Восстановить временно изменённые venue/status/test settings.
4. Закрыть или пометить тестовые carts/orders/batches по принятой staging-процедуре.
5. Убедиться, что тест не оставил активные staff-chat notifications или некорректный table context.
6. Записать restoration evidence в result block.

### Result Record

- **Result:** `<PASSED | FAILED>`
- **Date:** `<YYYY-MM-DD>`
- **Actor:** `<name/role>`
- **Environment:** `<staging or other production-like environment>`
- **Venue A / Venue B:** `<ids or safe labels>`
- **Guest accounts:** `<safe test-account labels; no tokens or unrelated PII>`
- **Defects:** `<links/IDs or none>`
- **Bot/Mini App parity:** `<confirmed/not confirmed>`
- **Two-account privacy:** `<confirmed/not confirmed>`
- **Cleanup/restoration evidence:** `<summary or link>`
- **Notes:** `<optional>`

### Closure Criteria

`REPEAT-MANUAL-001` можно перевести в `PASSED` только когда:

- все обязательные сценарии выполнены на staging или другом production-like environment;
- восстановлены изменённые prices/availability/statuses;
- записана дата;
- указан исполнитель;
- зафиксированы найденные defects или `none`;
- Bot/Mini App parity подтверждена;
- privacy scenarios подтверждены двумя аккаунтами.

До выполнения всех условий нельзя использовать статус `STAGING-SMOKE-PASSED`.

### Related Docs

- [`docs/GROWTH_RETENTION.md`](GROWTH_RETENTION.md)
- [`docs/UPDATED_PRODUCT_AI_ROADMAP.md`](UPDATED_PRODUCT_AI_ROADMAP.md)
- [`docs/TESTING_QA_SMOKE_STRATEGY.md`](TESTING_QA_SMOKE_STRATEGY.md)
- [`docs/audit/MINI_APP_LAUNCH_SMOKE_CHECKLIST.md`](audit/MINI_APP_LAUNCH_SMOKE_CHECKLIST.md)
- [`docs/ORDER_SESSION_TAB_CORE.md`](ORDER_SESSION_TAB_CORE.md)
- [`docs/MENU_OPTIONS_STOPLIST.md`](MENU_OPTIONS_STOPLIST.md)

## CATALOG-SEARCH-MANUAL-001

- **Feature:** Catalog Search and Filter Phase 1
- **Priority:** P2
- **Current status:** `BLOCKED_BY_ENVIRONMENT`
- **Runtime status:** `CATALOG SEARCH AND FILTER PHASE 1 / DONE / MVP /
  STAGING-SMOKE-PASSED`
- **Deferred scope status:** `EXTENDED MULTI-VENUE CATALOG DATASET REGRESSION /
  NON-BLOCKING DEFERRED MANUAL SMOKE / CATALOG-SEARCH-MANUAL-001`
- **Reason deferred:** недостаточно опубликованных тестовых заведений; недостаточно городов и
  повторяющихся городов; нет подготовленного постоянного QA-каталога; ограничены возможности
  двухаккаунтной проверки favorites/search state.
- **Blocking impact:** не блокирует текущий MVP/release. Проверка обязательна до catalog
  pagination, ranking, map/geo или большого pilot rollout.

### Prerequisites

- минимум 5 `PUBLISHED` guest-available venues;
- минимум 3 разных города;
- минимум 2 venue в одном городе;
- уникальные поисковые слова в `name`;
- уникальные поисковые слова в `address` / `formatted_address`;
- минимум 1 `DRAFT` / `HIDDEN` venue с известными `name` / `city` / `address`;
- Guest A;
- Guest B;
- возможность временно hide/publish тестовое venue;
- сохранённое исходное состояние favorites/statuses для cleanup.

### Automated Evidence Already Available

- `GuestVenueRoutesTest` `23/23`;
- `compileKotlin` PASS;
- `ktlintCheck` PASS;
- Mini App build PASS;
- Playwright `104/104`;
- GitHub Actions green;
- current limited-dataset staging smoke passed.

Это evidence подтверждает закрытие текущего Phase 1 MVP, но не считается выполнением расширенных
manual scenarios ниже.

### Manual Steps And Expected Results

1. City filter возвращает несколько venues одного города.
2. City filter исключает venues остальных городов.
3. Address search находит venue по уникальному слову адреса.
4. `q + city` даёт корректное пересечение.
5. City options не дублируются по регистру.
6. Stable ordering сохраняется при нескольких результатах.
7. Hidden / `DRAFT` venue не находится по точному `name` / `city` / `address`.
8. `%`, `_`, `!`, backslash и SQL-like input не раскрывают весь каталог.
9. Быстрый ввод подтверждает latest-response-wins на большем наборе данных.
10. Favorite add/remove работает внутри выдачи из нескольких результатов.
11. Guest A / Guest B получают собственное `isFavorite`.
12. Hide → publish корректно убирает и возвращает venue в поиск.
13. Reset возвращает полный guarded каталог.
14. Cleanup восстанавливает venue statuses и favorites.

Expected result для каждого шага: наблюдаемое поведение совпадает с описанием, guarded catalog не
раскрывает недоступные venue, а пользовательское search/favorite state не пересекается между Guest
A и Guest B.

### Cleanup / Restoration

1. Восстановить исходные venue statuses.
2. Восстановить исходные favorites Guest A и Guest B.
3. Подтвердить, что временно скрытое venue возвращено в исходное состояние.
4. Сверить итоговое состояние с сохранённым baseline и записать restoration evidence.

### Result Record

- **Result:** `<PASSED | FAILED>`
- **Date:** `<YYYY-MM-DD>`
- **Actor:** `<name/role>`
- **Environment:** `<staging or other production-like environment>`
- **Venue dataset:** `<safe venue labels/count/cities; no unrelated PII>`
- **Guest accounts:** `<Guest A / Guest B safe labels>`
- **Defects:** `<links/IDs or none>`
- **Two-account isolation:** `<confirmed/not confirmed>`
- **Cleanup/restoration evidence:** `<summary or link>`
- **Notes:** `<optional>`

### Closure Criteria

`CATALOG-SEARCH-MANUAL-001` можно перевести в `PASSED` только когда:

- все сценарии выполнены на staging или другом production-like environment;
- записаны дата и исполнитель;
- зафиксированы defects или `none`;
- восстановлены venue statuses/favorites;
- подтверждена двухаккаунтная изоляция.

До выполнения всех условий нельзя утверждать, что extended multi-venue catalog dataset regression
пройдена. Открытая запись не понижает статус текущего Catalog Search and Filter Phase 1 MVP.

### Related Docs

- [`docs/PRODUCT_SPEC.md`](PRODUCT_SPEC.md)
- [`docs/UPDATED_PRODUCT_AI_ROADMAP.md`](UPDATED_PRODUCT_AI_ROADMAP.md)
- [`docs/TESTING_QA_SMOKE_STRATEGY.md`](TESTING_QA_SMOKE_STRATEGY.md)
- [`docs/audit/MINI_APP_LAUNCH_SMOKE_CHECKLIST.md`](audit/MINI_APP_LAUNCH_SMOKE_CHECKLIST.md)
- [`docs/audit/ROLE_GUEST.md`](audit/ROLE_GUEST.md)
