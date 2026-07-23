# Deferred Manual Smoke Backlog

Дата актуализации: 2026-07-23.

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
