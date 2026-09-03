# Catalog Map And Automatic Per-Venue Public Links

Дата проверки: **2026-09-03**.

Статус двух продуктовых блоков: **ЗАПЛАНИРОВАНО**. Они не считаются реализованными или
частично реализованными только потому, что в проекте уже есть отдельные координаты, каталог,
Telegram `start`/`startapp` parsing или table QR. Реализация отложена до завершения текущего
V126 cutover, если продуктовый приоритет не будет изменён отдельным решением. Даты и сроки в
этом документе не назначаются.

## 1. Краткое резюме

Платформа планирует два связанных, но независимо поставляемых блока:

1. **Автоматическая постоянная публичная ссылка каждого заведения.** Каждая новая запись
   venue автоматически получает ровно одну каноническую ссылку. Это стандартная platform-wide
   capability, а не ручная настройка пилота.
2. **Интерактивная карта Guest catalog.** Переключатель `Список | Карта` даёт дополнительное
   представление того же каталога. Список, поиск, карточка, бронирование и прямая venue link
   продолжают работать без карты.

Рекомендуемый первый вариант карты: **MapLibre GL JS как renderer + данные на основе
OpenStreetMap + заменяемый hosted tile provider**. Более поздний вариант — региональные PMTiles
в собственном или объектном хранилище. Geocoder, routing и приложение внешней навигации —
отдельные зависимости, а не функции MapLibre.

Рекомендуемый внешний постоянный адрес, пока только как архитектурное предложение:

```text
https://go.hookahtootah.club/v/<public_slug>
```

DNS, TLS, владение и эксплуатационная готовность этого домена текущим документом **не
подтверждаются**. Собственный HTTPS URL остаётся стабильным, а его Telegram destination может
меняться без перепечатки уже опубликованных ссылок и QR.

## 2. Текущее состояние

Оба целевых блока остаются **ЗАПЛАНИРОВАНО**. На дату проверки код даёт следующую техническую
основу, но не готовую функцию:

| Проверка | Подтверждённое текущее состояние |
| --- | --- |
| Venue token / public slug | Публичного opaque venue token, stable public slug и отдельной public-link identity нет. |
| Telegram routing | Есть bot `/start` routing, parsing `tgWebAppStartParam` / `startapp` / `start_param` и отдельное числовое начало Venue Mode. Текущий Mini App start parameter используется прежде всего как table token; это не public venue link. |
| Coordinates | В `venues` уже есть nullable `latitude` и `longitude` с pair/range constraints; DTO каталога и venue detail их возвращают. Метаданных source/precision/verification нет. |
| Server-side catalog search | Есть authenticated `GET /api/guest/catalog?q=&city=`: поиск по имени/городу/адресу, city filter и lifecycle/subscription filtering. Pagination, ranking, viewport query и geo search не реализованы. |
| Map screen | Экрана карты, MapLibre/PMTiles integration, markers и clustering нет. |
| Coordinate editing | Venue public-card settings позволяют хранить адрес и координаты; есть отключённый по умолчанию optional Yandex geodata adapter/backend endpoints. Embedded map, draggable marker и verification workflow в текущем UI не подтверждены. |
| Guest preview / owner link management | Уже есть read-only own-venue `Предпросмотр для гостя` для Owner/Manager через `GET /api/venue/{venueId}/guest-preview`. Он не создаёт и не показывает public URL/share token. Блока управления public venue link, QR и alias/rotation UI нет. |
| QR generation | Есть table QR. Генерации QR для canonical venue link нет. |
| Own-domain resolver | Сервиса `/v/<public_slug>`, безопасного browser fallback и own-domain redirect нет. |

Существующие table QR, staff invite, owner invite и table-token routing не являются public venue
link. Текущий внутренний guest detail endpoint с числовым venue ID также не задаёт формат
публичного адреса.

Текущая lifecycle-модель едина в коде и каноническом Platform документе:
`DRAFT`, `PUBLISHED`, `HIDDEN`, `PAUSED`, `SUSPENDED`, `ARCHIVED`, `DELETED`. Product language
`onboarding` сейчас соответствует `DRAFT`; `paused_by_owner` — `PAUSED`;
`suspended_by_platform` — `SUSPENDED`; старое `deletion_requested` не является отдельным
runtime-статусом и соответствует `DELETED`.

## 3. Автоматическая ссылка каждого venue

Статус: **ЗАПЛАНИРОВАНО**.

### 3.1 Platform-wide principle

При создании записи заведения платформа автоматически создаёт ровно одну canonical public-link
identity и ровно одну каноническую постоянную ссылку. Правило одинаково действует для первого
пилота, любого следующего владельца, venue от Platform Owner, venue из заявки на подключение,
владельцев одного или нескольких venues и будущих сетей.

```text
1 venue → 1 canonical public link
10 venues → 10 canonical public links
500 venues → 500 canonical public links
```

Не существует операторского списка «разрешённых заведений», для которого разработчик вручную
создаёт ссылки. Создание очередной ссылки не требует:

- нового endpoint для конкретного venue;
- изменения router или кода;
- нового deploy;
- ручной записи в configuration/operator file;
- отдельного Telegram-бота;
- отдельного Mini App;
- отдельного домена.

Все ссылки используют общий домен и один server-side resolver. После создания venue будущая
система автоматически:

1. создаёт immutable public-link identity;
2. создаёт уникальный public slug;
3. проверяет отсутствие коллизии;
4. связывает identity и slug с точным venue;
5. показывает ссылку авторизованному Venue Owner;
6. показывает ссылку Platform Owner;
7. подготавливает QR на основе той же canonical URL.

Обычное создание не требует подтверждения Platform Owner. Ссылка возникает сразу, но public
resolution всегда соблюдает lifecycle, subscription и RBAC.

Resolution chain: canonical slug или разрешённый old alias → immutable public-link identity →
exact venue; Telegram `venue_<public_token>` → та же identity → exact venue. Ни один этап не
принимает raw internal venue ID как публичный selector.

### 3.2 Identity, slug и название

Три понятия не смешиваются:

- `display name` — редактируемое название для человека;
- `canonical_slug` — стабильный читаемый сегмент канонического URL;
- `public_link_identity` — immutable internal identity публичной ссылки, не зависящая от названия
  и не равная внутреннему venue ID;
- `public_token` — normally stable opaque routing token, связанный с immutable identity. Он не
  выводится из названия; replacement допускается только в контролируемом incident flow.

Рекомендуемое правило: link identity immutable; token никогда не зависит от названия и обычно
стабилен; slug не меняется автоматически при переименовании; ручная смена slug доступна только
контролируемой роли; прежний slug остаётся redirect alias; alias не может быть назначен другому
venue без отдельной явной policy.

Рекомендуемый формат и примеры — предложение, не текущий runtime:

```text
https://go.hookahtootah.club/v/<public_slug>

https://go.hookahtootah.club/v/mix
https://go.hookahtootah.club/v/lounge-21
https://go.hookahtootah.club/v/smoke-room
```

Ссылка определяет только то, карточку какого venue запросил пользователь. Она:

- не выдаёт роль или membership;
- не является Staff/Manager/Owner invite;
- не создаёт table session и не даёт table context;
- не открывает чужой Venue Mode или Platform Mode;
- не обходит subscription, lifecycle или RBAC;
- не содержит raw internal venue ID, Telegram user ID, секрет, контекст стола, JWT или initData.

Все availability и authorization checks повторно выполняются сервером.

### 3.3 Optional source parameter

Допустим короткий безопасный attribution parameter:

```text
https://go.hookahtootah.club/v/mix?src=yandex_business
```

`src` не содержит персональных данных, не меняет target venue и права, а неизвестное значение
безопасно игнорируется. Аналитика отделена от link identity; canonical URL работает без query.
Это не проектирование рекламной платформы.

## 4. Link lifecycle

Статус: **ЗАПЛАНИРОВАНО**.

Canonical identity создаётся вместе с venue и не пересоздаётся при обычном lifecycle transition.
Public response определяется актуальным серверным статусом:

| Runtime status | Владельческий preview | Поведение для постороннего гостя |
| --- | --- | --- |
| `DRAFT` (включая onboarding) | Авторизованный Owner видит безопасный preview и статус «не опубликовано». | Не показывать как published; нейтральный unavailable/not-found response без закрытых данных. |
| `PUBLISHED` | Доступен preview. | Открывается точная публичная карточка при прохождении остальных availability checks. |
| `HIDDEN` | Preview с объяснением состояния для разрешённой роли. | Нейтральное сообщение о временной недоступности; каталог и guest actions соблюдают lifecycle. |
| `PAUSED` | Preview с состоянием паузы. | Нейтральное сообщение о временной недоступности; guest actions заблокированы согласно lifecycle. |
| `SUSPENDED` | Только разрешённые management surfaces, без обхода Platform policy. | Публичные guest actions заблокированы; внутренняя причина блокировки не раскрывается. |
| `ARCHIVED` | Исторический/management view только по разрешённому контракту. | Нейтральное «Заведение недоступно». Не показывать как действующую точку. |
| `DELETED` | Только если отдельная retention/audit policy это разрешает. | Не раскрывать прежние данные; безопасное состояние отсутствия/`404` или эквивалент. |

Переименование display name не ломает URL. Controlled slug change создаёт аудит, сохраняет old
alias согласно утверждённой retention policy и не меняет immutable identity. При abuse/security
incident Platform Owner может отключить token или выпустить replacement token, привязанный к той
же immutable identity, в отдельном безопасном сценарии. Одна canonical own-domain URL при этом не
меняется; прежний token выключается по incident policy. Ротация не раскрывает новый token и не
должна создавать open redirect.

## 5. Telegram и own-domain routing

Статус: **ЗАПЛАНИРОВАНО**.

Проверенные официальные Telegram formats:

```text
# Main Mini App, если Main Mini App настроен у бота
https://t.me/<bot_username>?startapp=venue_<public_token>

# Именованный Direct Mini App
https://t.me/<bot_username>/<short_name>?startapp=venue_<public_token>

# Bot-first fallback, не Mini App link
https://t.me/<bot_username>?start=venue_<public_token>
```

Для Main и Direct Mini App непустой `startapp` передаётся в validated init data как
`start_param` и в URL как `tgWebAppStartParam`. `initDataUnsafe` и URL parameter сами по себе не
доверенные: backend валидирует Telegram init data и отдельно разрешает public token. Для bot
deep link официальный `start` ограничен 64 base64url characters. Telegram Mini Apps page не
фиксирует рядом с `startapp` такой же явный числовой предел; поэтому рекомендация реализации —
использовать короткий URL-safe opaque payload и удерживать весь `venue_<public_token>` в пределах
64 символов до отдельного compatibility test.

Own-domain URL — внешний канонический адрес. Telegram URL — allowlisted внутренняя цель, которую
можно заменить при изменении bot username, Main Mini App configuration или Direct Mini App short
name. Resolver принимает только известные destination templates и никогда не перенаправляет на
произвольный query-provided URL.

Из уже открытого Mini App внешний HTTPS URL запускается через `WebApp.openLink` только после
явного действия пользователя; Telegram link — через `WebApp.openTelegramLink`. Начиная с Bot API
7.0 вызов `openTelegramLink` не закрывает Mini App. Если у бота не настроен Main Mini App,
официальный Main Mini App link имеет username-link fallback, поэтому own-domain page не должна
считать сам факт перехода доказательством успешного открытия exact venue.

Telegram документирует единый protocol и доступное Mini App API, но не даёт на этой странице
вечной гарантии одинакового поведения каждой исторической версии Android, iOS, Desktop и Web.
Перед выпуском нужны compatibility smoke на актуальных клиентах. Canonical HTTPS fallback
обязателен независимо от client detection.

## 6. Venue Mode UX

Статус: **ЗАПЛАНИРОВАНО**.

В контексте каждого venue для авторизованного Owner:

```yaml
Публичная ссылка на заведение

https://go.hookahtootah.club/v/<public_slug>

[Скопировать]
[Поделиться]
[Скачать QR]
```

Планируемые действия: Copy link, Telegram/system share, Download QR, Preview as Guest, indicator
публичной доступности и, если policy разрешит, request controlled slug change. Copy, Share и QR
всегда используют одну canonical own-domain URL.

Текущий read-only `Предпросмотр для гостя` уже доступен Owner/Manager своего venue, но не связан с
публичной ссылкой. Планируемый `Preview as Guest` в link block переиспользует безопасную guest-card
семантику, не расширяя Manager permissions на slug/token/QR management.

Owner видит только venues, которыми вправе управлять. Если у Owner три venue, в каждом venue
context показывается своя отдельная ссылка: один владелец не получает общий URL на все свои
заведения. Manager и Staff не управляют public link, пока отдельная точная permission matrix не
разрешит это явно.

Для координат Venue Mode планирует: показать текущий адрес; найти точку по адресу; показать и
передвинуть marker; подтвердить coordinates; показать Guest preview; предупредить о
неподтверждённой точке. Owner работает только со своими venues. Manager получает право только по
отдельному решению permission matrix; Staff координаты не редактирует.

## 7. Platform Mode UX

Статус: **ЗАПЛАНИРОВАНО**.

Platform Owner сможет:

- увидеть, скопировать и открыть guest preview canonical link любого venue;
- скачать QR и проверить collision/status;
- видеть redirect aliases;
- управлять slug/token только в контролируемом сценарии с аудитом;
- отключить или ротировать resolution при abuse/security incident;
- увидеть/исправить точку любого venue, отметить verification;
- найти venues без coordinates, выполнить безопасную массовую проверку и увидеть coordinate
  change audit.

Публикация нового venue не требует ручного создания ссылки Platform Owner. Platform Mode
управляет исключениями и безопасностью, но не является генератором ссылок поштучно.

## 8. Guest behavior

Статус: **ЗАПЛАНИРОВАНО**.

Canonical link может размещаться в карточке Яндекс Бизнеса, на сайте, в социальных сетях,
Telegram-канале, мессенджерах, объявлениях, партнёрских публикациях, визитках, печатных
материалах, QR на афишах/стойках и пересылаться постоянным гостям.

После resolution открывается exact venue detail, а не общий каталог. Из detail доступен возврат в
общий каталог. Safe launch contract:

| Условие | Планируемое поведение |
| --- | --- |
| Telegram установлен и target поддерживается | Явная кнопка «Открыть в Telegram» ведёт на allowlisted Main/Direct Mini App destination. |
| Telegram не установлен | Canonical HTTPS page остаётся открытой и показывает minimal browser fallback. Не зацикливать redirects. |
| Desktop/Web | Показывать ту же безопасную fallback page и рабочую Telegram кнопку; Telegram сам выбирает поддерживаемый client/web flow. |
| Mini App недоступно или Main Mini App не настроено | Не показывать ложный успех; оставить fallback, copy action и нейтральное объяснение. |
| Клиент не поддерживает deep link | Сохранить собственный URL, показать copy/retry или bot-first fallback только из allowlist. |
| Venue недоступно по lifecycle/subscription | Показать lifecycle-safe нейтральное состояние, не раскрывая внутреннюю причину. |

Минимальный browser fallback — не второй веб-продукт. Он содержит название venue только когда
lifecycle допускает public display, нейтральное описание, «Открыть в Telegram» и при разрешении
copy address. Административные данные, закрытые настройки и internal lifecycle reasons
отсутствуют.

## 9. Map product UX

Статус: **ЗАПЛАНИРОВАНО**.

Guest catalog получает переключатель:

```text
Список | Карта
```

Карта помогает выбрать город/район, увидеть расположение и доступность заведений, открыть exact
venue и передать публичные coordinates во внешнюю навигацию. Она является дополнительным view
одного каталога, а не отдельной source of truth.

Обязательный UX contract:

- List и Map синхронизируют текущие search/filter conditions;
- markers показывают только допустимые публичные venues с сохранёнными coordinates;
- viewport loading/filtering не меняет authorization и lifecycle rules;
- большие наборы точек используют clustering;
- marker выделяет venue и открывает preview card;
- preview ведёт в exact venue detail;
- возврат из detail восстанавливает filters, selected venue и прежний viewport;
- предусмотрены empty, loading, tile-error и fallback states;
- venue без coordinates остаётся доступным в списке;
- ошибка renderer/tile provider не блокирует list, search, detail, booking или direct venue link.

Preview card может показывать name, address, достоверно вычисленный operating status, короткие
характеристики, «Открыть заведение» и «Построить маршрут». Ratings, distance, `open now` и price
level не обещаются, пока data model и authoritative calculation их не поддерживают.

Guest geolocation optional. Не запрашивать её при первом открытии. Запрос возможен только после
явного действия `Показать рядом`, `Моё местоположение` или `Найти ближайшие`, с объяснением цели.
Отказ не ломает карту. Если Telegram LocationManager недоступен, safe fallback — выбор города,
ручное перемещение карты или, после такого же явного действия и отдельной проверки privacy/
browser permissions, browser Geolocation. Скрытого tracking нет.

## 10. Map provider architecture

Статус: **ЗАПЛАНИРОВАНО**.

Рекомендуемая начальная композиция:

```text
MapLibre GL JS                  — renderer в Mini App
OpenStreetMap-derived data      — исходные картографические данные
replaceable hosted tile provider — styles/tiles/fonts/sprites delivery
separate geocoder               — address ↔ coordinates
separate routing/navigation     — построение пути или внешний переход
```

Границы компонентов:

| Слой | Ответственность | Чем не является |
| --- | --- | --- |
| Renderer | MapLibre GL JS рисует интерактивную WebGL-карту из style/tiles и поддерживает client UX. | Не поставляет OSM data, production tiles, geocoding или routes. |
| Map data | OpenStreetMap contributors создают геоданные под ODbL. | Не бесплатный безлимитный production CDN. |
| Tile provider | Доставляет raster/vector tiles и обычно style/assets по тарифу и quotas. | Не обязательно владелец данных и не обязательно geocoder. |
| Geocoder | Преобразует адрес и coordinates, со своей license, rate limit, caching и SLA policy. | Не tile CDN и не runtime catalog search. |
| Routing provider | Вычисляет маршрут, если продукт решит делать это внутри. | Не обязателен для открытия внешней navigation app. |
| External navigation app | Получает публичные coordinates/name и строит маршрут вне Mini App. | Не embedded renderer и не источник venue authorization. |

MapLibre GL JS — open-source renderer. OpenStreetMap data требует attribution, а публичные OSM
tile servers имеют отдельную usage policy, ограниченную capacity и no SLA. Поэтому renderer,
data license и production delivery выбираются и проверяются независимо.

Кнопка «Построить маршрут» provider-neutral: передаёт только публичные WGS84 coordinates и
название в безопасный `geo:` URI или утверждённый universal HTTPS navigation URL. Ни roles,
tokens, internal IDs, initData, ни table context не передаются. Default navigation provider —
открытое решение, не hard dependency embedded map.

## 11. MAPS.ME suitability verdict

Статус исследования: **ПРОВЕРЕНО 2026-09-03; provider не выбран**.

На официальном сайте MAPS.ME найден consumer offline map/mobile application. В действующих
официальных Terms сервис лицензируется для personal/non-commercial use. В официальных материалах
не найден актуальный публичный browser Web SDK/API с подтверждёнными commercial embedding terms,
production capacity/SLA, pricing и attribution contract. Официальный GitHub содержит native
Android/iOS integration repositories, которые исторически открывают установленное приложение и
показывают marks; это не подтверждает browser embedding в Telegram Mini App.

Следовательно, бесплатное пользовательское приложение не равно бесплатному коммерческому Web
SDK. MAPS.ME не объявляется плохим или запрещённым вообще. Его можно отдельно рассмотреть только
как external navigation app после проверки актуальных URL schemes, client compatibility и terms.
Технической и юридической основы выбрать его embedded renderer/provider сейчас не подтверждено.

```text
MAPS.ME_NOT_SELECTED_AS_EMBEDDABLE_PROVIDER
```

## 12. Coordinates and geocoding

Статус: **ЗАПЛАНИРОВАНО** для verification workflow и provider selection; базовые nullable
`latitude`/`longitude` уже существуют.

Минимальная будущая модель metadata:

```text
latitude
longitude
coordinates_source
coordinates_verified_at
coordinates_updated_at
```

Также рассматриваются `coordinates_precision`, `coordinates_verified_by`, `geocoder_provider`
и `public_map_visibility`. Вариант хранения — расширить текущую venue location model либо вынести
metadata в отдельную сущность; это не финальное schema decision.

Рекомендуемый flow:

1. Owner или Platform Owner вводит address.
2. Backend вызывает выбранный geocoder.
3. Найденная точка показывается на карте.
4. Разрешённая роль подтверждает или двигает marker.
5. Coordinates и verification metadata сохраняются.
6. Guest catalog читает сохранённую точку, а не geocodes каждый guest request.

Address и confirmed coordinates обязательно сохраняются/кешируются в нашей DB и не вычисляются
заново на каждый guest request. Geocoder, terms которого не разрешают нужное persistence/caching,
не подходит для этого product flow и не получает no-cache fallback. Provider меняется без
изменения venue identity/data contract.

### 12.1 Geocoder operating contract

| Вариант | Ограничения и решение |
| --- | --- |
| Public Nominatim | Maximum 1 request/second для приложения, valid identifying User-Agent/Referer, attribution и caching. Client-side autocomplete, systematic/bulk queries запрещены policy; SLA нет, access может быть withdrawn. Не использовать как безлимитный production autocomplete. |
| Commercial hosted geocoder | Проверить persistent-storage/caching rights, autocomplete terms, quotas, regional coverage, attribution, DPA/SLA и cost. Требуется проверка тарифа перед внедрением. |
| Self-hosted geocoder | Больше operational ownership: imports, updates, storage, monitoring и data license; снимает policy public endpoint, но не ODbL/attribution obligations. |
| Existing optional Yandex adapter | Кодовая основа не означает provider selection. Текущая конфигурация disabled by default/commercial-only; persistence и production use допустимы только по подтверждённой коммерческой лицензии/terms. |

Telegram LocationManager (Bot API 8.0+) требует initialization, сообщает availability/requested/
granted states, запрашивает location через явный UI flow и позволяет открыть settings только из
user interaction. Полученный location не становится доверенным venue coordinate и не сохраняется
как постоянный guest profile по умолчанию.

## 13. Tiles, PMTiles and operating costs

Статус: **ЗАПЛАНИРОВАНО**. Стоимость зависит прежде всего от map sessions, tile requests, zoom,
cache hit rate и географии, а не только от количества venue. Непроверенные цены не фиксируются.

| Вариант | Стартовая стоимость | Ограничения / поддержка | Attribution | Масштабирование и lock-in | Пригодность |
| --- | --- | --- | --- | --- | --- |
| Hosted tile provider, free/low-cost start | Может иметь free tier; commercial rights не предполагаются. **Требуется проверка тарифа перед внедрением.** | Quotas, key/domain limits, fair use; SLA может отсутствовать. Низкая сложность старта. | OSM/data + provider attribution по terms. | Простое начало; умеренный lock-in style/API/asset URLs. | 10/50 venues — хороший pilot при разрешённом трафике; 500+ зависит от sessions/quotas, не числа rows. |
| Hosted provider with commercial SLA | Платный тариф/overage. **Требуется проверка тарифа перед внедрением.** | Contract, SLA/support, quotas and key controls; низкая внутренняя эксплуатационная сложность. | По provider/data/style terms. | Хорошо масштабируется по контракту; умеренный/высокий vendor lock-in. | 10 — может быть избыточно; 50 и 500+ — подходит при подтверждённой нагрузке и бюджете. |
| Regional PMTiles on own/object storage | Storage, egress/CDN и request costs; не «бесплатно». **Требуется проверка тарифа перед внедрением.** | Нужно производить/обновлять archive, styles/fonts/sprites, CORS, cache и monitoring. Выше сложность. | OSM/derived data и style/assets attribution остаётся. | Предсказуемый regional archive, низкий tile-API lock-in; storage/CDN заменяемы. | 10 — обычно преждевременно; 50 — по трафику; 500+ — сильный вариант при устойчивой региональной нагрузке. |
| Public OSM tile servers | Нет счёта за endpoint, но это community-funded shared capacity, не production entitlement. | No SLA, blocking possible, caching headers, valid identification; no bulk/offline/prefetch. | Видимое `© OpenStreetMap contributors` и link. | Не является масштабируемым безусловным CDN. | Development или официально разрешённая малая нагрузка; не baseline для 10/50/500+ commercial growth. |

Поздняя рекомендуемая композиция:

```text
MapLibre GL JS + regional PMTiles + own/object storage
```

PMTiles — single-file tiled archive, читаемый через HTTP range requests; MapLibre подключает его
через protocol integration. Перед выбором проверяются право на caching/offline use, derived-data
license, public-download exposure, update cadence и storage/CDN terms.

Во всех вариантах обязательны корректная OpenStreetMap attribution, attribution tile provider,
соблюдение licenses данных/styles/fonts/sprites, видимость attribution на mobile, проверка caching
и offline/PMTiles rights, а также запись выбранного provider и версии его условий в docs. Нельзя
скрывать attribution ради дизайна.

## 14. Privacy and security

Статус: **ЗАПЛАНИРОВАНО**.

- Resolver делает server-side mapping public token/slug → exact venue и применяет rate limit.
- Raw internal venue ID не является публичным адресом; unknown token/slug даёт одинаково
  безопасный unavailable/not-found response без enumeration hints.
- Link не authorizes, не создаёт membership/table context и не заменяет validated init data.
- Venue management проверяет tenant scope/RBAC; Platform management — Platform role server-side.
- Slug/token rotation, alias changes и coordinate changes аудируются с actor и безопасным old/new.
- Slug может быть читаемым, но opaque token/identity и одинаковые responses снижают риск
  enumeration; дополнительные anti-enumeration measures определяются threat model.
- Redirect destination строится сервером из allowlist Telegram templates; произвольный `next`,
  scheme или host запрещён — защита от open redirect.
- Lifecycle/subscription checks выполняются на каждом public resolution и guest action; причины
  `SUSPENDED` и иные внутренние сведения гостю не раскрываются.
- Guest geolocation запрашивается только явно, минимизируется и по умолчанию не становится
  permanent profile. Для analytics предпочтительны aggregate/coarse facts.
- Точные guest coordinates не помещаются в `src`, analytics URL, logs или share link.
- Coordinates venue и public name допустимы для внешней навигации только как public context.

Предварительные сущности для обсуждения, не финальная migration/API schema:

```text
venue_public_links
- venue_id
- public_link_identity
- public_token
- canonical_slug
- status
- created_at
- updated_at
- rotated_at

venue_public_link_aliases
- venue_id
- slug
- status
- created_at

venue_coordinates
- venue_id
- latitude
- longitude
- source
- precision
- verified_at
- verified_by
- updated_at
```

Точная migration и API contracts определяются отдельной implementation-задачей после V126
cutover с учётом существующих `venues.latitude/longitude`, а не добавляются этим документом.

## 15. Lifecycle states

Статус: **ЗАПЛАНИРОВАНО** для link/map behavior; названия runtime lifecycle уже действуют.

Единое правило для resolver, list, map и exact detail:

- `PUBLISHED` может быть публичным только при прохождении действующих subscription/availability
  checks;
- `DRAFT`, `HIDDEN` и `PAUSED` не маскируются под опубликованные точки;
- `SUSPENDED`, `ARCHIVED` и `DELETED` не появляются как действующие markers;
- owner preview не превращает venue в публичный и не выдаёт гостю management data;
- venue без coordinates может быть допустимо опубликован в list/detail, но не получает marker;
- старый alias всегда приходит к тому же lifecycle check, что и canonical slug;
- resolver и map не раскрывают внутреннюю причину lifecycle transition.

Catalog и map не создают третью систему статусов. Любое будущее расхождение product wording с
runtime enum оформляется явным mapping в каноническом Platform lifecycle document.

## 16. Acceptance criteria

### 16.1 Automatic public links

1. Создание одного venue автоматически создаёт одну canonical link.
2. Создание десяти venues автоматически создаёт десять unique links.
3. Два venues с одинаковым display name не создают collision.
4. Обычное переименование не ломает старую опубликованную ссылку.
5. Ссылка открывает exact venue, а не общий каталог.
6. Из venue detail можно вернуться в общий каталог.
7. Venue Owner видит link только authorized venue.
8. Platform Owner видит links всех venues.
9. Link не выдаёт role, membership или table context.
10. `DRAFT`/`HIDDEN`/`PAUSED`/`SUSPENDED`/`ARCHIVED`/`DELETED` обрабатываются безопасно.
11. Copy, Share и Download QR используют одну canonical URL.
12. Link работает на актуальных Telegram Android, iOS, Desktop и Web либо показывает
    подтверждённый fallback; результат зафиксирован compatibility smoke.
13. Смена bot username или Mini App short name не меняет опубликованный own-domain URL.

### 16.2 Guest catalog map

1. List и Map используют один набор catalog filters.
2. Карта показывает только допустимые public venues.
3. Marker открывает exact venue preview.
4. Preview открывает exact venue detail.
5. Возврат сохраняет viewport и filters.
6. Два venues с близкими coordinates корректно кластеризуются.
7. Venue без coordinates остаётся доступным в list.
8. Отказ от geolocation не блокирует catalog.
9. Geolocation запрашивается только по явному действию.
10. Owner не может изменить coordinates чужого venue.
11. Guest не может изменить coordinates.
12. `SUSPENDED`/`ARCHIVED`/`DELETED` не появляются как действующие точки.
13. Tile provider error не блокирует list view.
14. Attribution видна и соответствует terms.
15. Public OSM tiles не используются как безусловный production CDN.
16. MAPS.ME не используется как embedded provider без официального подтверждения условий.

## 17. Implementation order

Все этапы **ЗАПЛАНИРОВАНО** и остаются после V126 cutover до отдельной смены приоритета:

1. Automatic direct canonical link каждого venue.
2. Хранение, verification и редактирование coordinates в Venue/Platform Mode.
3. Map view Guest catalog.
4. Optional nearby search, distance sorting и external navigation.
5. Сложная geoanalytics только после появления реальной нагрузки и privacy contract.

Порядок минимизирует риск: direct links сразу полезны существующим venues, работают для Яндекс
Бизнеса/сайтов/social и не требуют map provider; проверенные coordinates нужны до карты; nearby
search нельзя запускать до корректных coordinates и privacy contract.

## 18. Open decisions

Ни одно из решений ниже не принимается молча:

| Решение | Рекомендуемый простой старт, не реализованный факт |
| --- | --- |
| Final canonical domain | Один платформенный HTTPS domain с `/v/<stable_slug>`; подтвердить ownership, DNS, TLS и operations отдельно. |
| Hosted tile provider | MapLibre-compatible commercial-use plan с ясными quotas/attribution и заменяемым config. |
| Переход на PMTiles | Только после измеренной regional traffic/cost и готового update/monitoring процесса. |
| Geocoder provider | Backend abstraction; для production выбрать provider с разрешённым persistence, SLA/limits и region quality. |
| Manual slug change rules | Platform-controlled request + audit + collision check; Owner self-service не включать без policy. |
| Redirect alias retention | Долгий/indefinite safe alias предпочтителен для печатных QR, но retention и abuse policy утвердить отдельно. |
| Browser fallback card | Да, минимальная lifecycle-safe card с Telegram CTA; не второй web catalog. |
| Custom owner slug | Нет на первом этапе; system-generated stable slug уменьшает collisions/support. |
| Map analytics | Только aggregate events без exact guest coordinates после privacy decision. |
| Distance | Не показывать до verified coordinates, explicit geolocation и согласованной calculation semantics. |
| `open now` filter | Не включать до authoritative schedule/timezone model. |
| Default navigation provider | Начать с platform-supported `geo:`/safe universal selection; не связывать embedded map и route destination. |

Также отдельно утверждаются token length/alphabet, unknown-link HTTP semantics, alias abuse policy,
tile budget, map style/license и exact permission matrix для Manager.

## 19. Official sources and verification date

Все внешние условия ниже проверены **2026-09-03**. Перед implementation/release тарифы, terms и
client compatibility проверяются повторно: внешние правила изменяемы.

### Telegram

- [Telegram Mini Apps](https://core.telegram.org/bots/webapps): Main Mini App и Direct Mini App
  links, `startapp` → `start_param` / `tgWebAppStartParam`, validated init data, platform/version,
  `openLink`, `openTelegramLink`, LocationManager и user-interaction restrictions.
- [Telegram Deep Links](https://core.telegram.org/api/links): bot `start` links и ограничение
  bot-start parameter до 64 base64url characters.

### Renderer, data, tiles and geocoding

- [MapLibre GL JS documentation](https://maplibre.org/maplibre-gl-js/docs/): WebGL/TypeScript map
  renderer, style/source model и clustering examples.
- [MapLibre GL JS license](https://github.com/maplibre/maplibre-gl-js/blob/main/LICENSE.txt):
  BSD-3-Clause renderer license.
- [OpenStreetMap copyright and license](https://www.openstreetmap.org/copyright): ODbL data and
  attribution requirements.
- [OpenStreetMap Tile Usage Policy](https://operations.osmfoundation.org/policies/tiles/): no SLA,
  attribution, identification, caching и prohibition on bulk/offline use of public tile service.
- [OpenStreetMap Vector Tile Usage Policy](https://operations.osmfoundation.org/policies/vector/):
  отдельные условия public vector service.
- [Public Nominatim Usage Policy](https://operations.osmfoundation.org/policies/nominatim/):
  1 request/second maximum, identification, caching, attribution, no client autocomplete/bulk and
  no SLA.

### PMTiles and hosted services

- [PMTiles documentation](https://docs.protomaps.com/pmtiles/): single-file tiled archive and HTTP
  range request model.
- [PMTiles with MapLibre](https://docs.protomaps.com/pmtiles/maplibre): protocol integration.
- [Protomaps basemap downloads](https://docs.protomaps.com/basemaps/downloads): OSM-derived
  produced work, copy-to-own-storage guidance and attribution.
- [PMTiles cloud storage](https://docs.protomaps.com/pmtiles/cloud-storage): object storage/CDN,
  CORS, request/egress considerations.
- [MapTiler Cloud pricing](https://www.maptiler.com/cloud/pricing/): official example of separate
  noncommercial/free and commercial plans/SLA tiers; no price is frozen here.
- [MapTiler attribution guidance](https://docs.maptiler.com/guides/map-design/attribution/add-attribution/):
  provider and OpenStreetMap attribution obligations for that option.

### Navigation and MAPS.ME

- [RFC 5870 — `geo` URI](https://www.rfc-editor.org/rfc/rfc5870): provider-neutral WGS84 point
  identifier.
- [Google Maps URLs](https://developers.google.com/maps/documentation/urls/get-started): example
  universal HTTPS external-navigation option, separate from embedded renderer.
- [MAPS.ME official site](https://maps.me/): consumer offline map application description.
- [MAPS.ME Terms of Use](https://maps.me/terms/): official use terms; no current commercial Web SDK
  grant or pricing was identified.
- [MAPS.ME GitHub organization](https://github.com/mapsme): native application/API repositories;
  not evidence of current commercial browser embedding rights.

### Existing optional project-provider context

- [Yandex commercial Geocoder documentation](https://yandex.ru/dev/commercial/doc/en/concepts/jsapi-geocoder):
  commercial persistence/usage context for the existing optional adapter.
- [Yandex Maps API Terms](https://yandex.com/legal/maps_api/): terms must be reconciled with the
  selected commercial license before persistent production use.
