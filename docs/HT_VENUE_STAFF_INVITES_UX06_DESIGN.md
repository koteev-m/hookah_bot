# HT-VENUE-STAFF-INVITES-UX06 — адресные приглашения и фото

Дата проверки: 2026-10-27. Статус обеих частей: **ПРЕДЛОЖЕНИЕ, НЕ РЕАЛИЗОВАНО**.
Исследован Local checkout `hookah_bot_ANT`, ветка `codex/ht-venue-staff-invites-ux06`,
база `446d6a6785ff43a2c9253bead77ed010712a6851`. Identity gate: PASS;
проект `Hookah_Tootah`, физические root/common-dir и origin совпали с заданием.
В исследуемых частях 3–4 backend, SQL, storage и реальные Telegram-операции не изменялись.
Отдельно согласованное UI-объединение добавляет только существующий opaque handle в ответ
создания; это не адресный получатель и не изменение модели выдачи прав.

## Адресное приглашение: рекомендуемое решение

Использовать серверную операцию выбора одного Telegram-пользователя через
`request_users` → `UsersShared`, затем явно создать приглашение с неизменяемым
`targetTelegramUserId`. Это отдельное изменение модели приглашений и требует решения
до реализации.

### Проверенные механизмы Telegram

- `KeyboardButton.request_users` выбирает пользователей в приватном чате;
  `UsersShared` содержит `request_id` и выбранных пользователей. Для одного человека:
  `max_quantity=1`, `user_is_bot=false`, запрос имени и username только для подписи.
  `SharedUser.user_id` — идентификатор; имя и username не заменяют его. Получение ID
  само по себе не даёт боту возможности первым написать человеку.
  [Bot API: выбор пользователя](https://core.telegram.org/bots/api#keyboardbuttonrequestusers),
  [UsersShared](https://core.telegram.org/bots/api#usersshared),
  [SharedUser](https://core.telegram.org/bots/api#shareduser).
- Подготовленная кнопка применима к Mini App: `savePreparedKeyboardButton` допускает
  `request_users`; её `user_id` обозначает владельца кнопки, то есть приглашающего.
  [Bot API: подготовленная кнопка](https://core.telegram.org/bots/api#savepreparedkeyboardbutton).
  Mini App вызывает `requestChat(preparedButtonId)` при поддержке Bot API 9.6+;
  callback сообщает boolean, результат выбора надо получать сервером.
  [Mini Apps: методы и версия](https://core.telegram.org/bots/webapps#initializing-mini-apps).
- `savePreparedInlineMessage` + `shareMessage` — механизм пересылки подготовленного
  сообщения. Callback/event может сообщить успешную отправку, но не возвращает
  адресата и не доказывает получение, прочтение или принятие. Текущий `share/url`
  не даёт даже такого callback отправки.
  [Подготовленное сообщение](https://core.telegram.org/bots/api#savepreparedinlinemessage),
  [Mini Apps: события](https://core.telegram.org/bots/webapps#events-available-for-mini-apps).

Официальная документация проверена через web после отказа Firecrawl из-за отсутствия
кредитов. Реальные клиенты и Bot API сервера в этой задаче не вызывались.
Сочетание подготовленного `request_users` и Mini App `requestChat` следует из двух
официальных контрактов; перед выпуском нужен отдельный smoke iOS/Android/Desktop.

### Минимальная серверная последовательность

1. Авторизованный Owner/Manager начинает операцию выбора в конкретном заведении.
   Сервер повторно проверяет membership, разрешённую роль приглашения и создаёт
   короткоживущую одноразовую операцию: opaque operation ID, actor Telegram ID,
   venue ID, роль, срок, состояние и уникальный в пределах активных операций
   этого actor `request_id` типа signed 32-bit. Клиент не передаёт выбранный user ID.
2. Сервер готовит `request_users` для этого actor. Mini App проверяет наличие метода
   и `isVersionAtLeast('9.6')`, открывает подготовленную кнопку. На старом клиенте
   явный переход в приватный чат бота с одноразовым opaque nonce открывает обычную
   reply-кнопку той же операции. Если и она не поддерживается, предложить обновить
   Telegram; не понижать адресное приглашение до свободной ссылки незаметно.
3. Результат принимается только в доверенном Bot update через существующий
   transport/admission-контур. Проверить private chat, совпадение sender/chat с actor,
   актуальную операцию/request_id, TTL, ровно одного допустимого человека, текущие
   права actor и venue. Дедуплицировать update и атомарно погасить выбор; просроченный,
   чужой и повторный результат отклонить. Mini App callback и `web_app_data` не
   являются доказательством выбранной личности. Клиент читает результат только по
   своей авторизованной операции.
4. Показать выбранного человека и роль, затем создать приглашение по operation ID
   после явного подтверждения. Сервер снова проверяет права и переносит выбранный
   Telegram ID в immutable target. Отмена выбора/создания не выдаёт доступ.
5. Во всех путях accept, включая Bot и Mini App, сравнивать target с Telegram ID
   авторизованного принимающего **до** создания/повышения membership. Проверка target,
   TTL, revoke, одноразовое использование, membership и audit должны выполняться в
   той же транзакции/конкурирующем claim. Пересланная другому ссылка не выдаёт прав.

У владельца отображать отдельно «Предназначено: …» из серверного выбора и «Принял: …»
из `used_by_user_id`/фактического accept. Имена — подписи, identity — Telegram ID;
при отсутствии имени использовать безопасную подпись с коротким ID-hint. Не выдавать
эту информацию в Guest DTO. Факт назначения не является доставкой сообщения.
Отправку ссылки выполняет человек; предложение не требует, чтобы бот писал выбранному.

### Что потребуется изменить после решения

- Миграция: nullable target Telegram ID для новых адресных приглашений и серверное
  хранилище операций выбора; явное различие свободного и адресного типов. Старые
  строки с null сохраняют прежнюю семантику, их не перепривязывать автоматически.
- `TelegramModels.kt`, `TelegramApiClient.kt`, `TelegramBotRouter.kt`: модели
  request/users-shared, подготовка кнопки и безопасная обработка серверного результата.
- `StaffInviteRepository.kt`: сохранять target и проверять его в общей accept-операции.
  `VenueStaffRoutes.kt`: endpoints выбора/создания, повторный RBAC и безопасная
  проекция адресата/фактически принявшего для владельца; pending DTO не получает code/hash.
- Mini App: выбор, подтверждение, client fallback и чтение состояния операции;
  никаких доказательств через произвольные userId/username/ручное имя.
- Регрессии: подмена actor/venue/request_id, replay/TTL, потеря роли, другой accepter,
  гонка accept/revoke, старые свободные ссылки, неподдерживаемый клиент, отмена.

Текущая база: `VenueStaffRoutes.kt` принимает при create только роль/TTL, а pending
возвращает handle/роль/время. `StaffInviteRepository.kt` уже хранит
`used_by_user_id`, но pending API не предоставляет историю принятия. Исчезновение
pending и появление membership нельзя соединять в выдуманную пару «ссылка → человек».

## Фото сотрудника: prerequisite и один рекомендуемый вариант

**Готовой одобренной staff-photo инфраструктуры нет.** Канонический
[STAFF_PROFILES_SHIFTS_TIPS.md](STAFF_PROFILES_SHIFTS_TIPS.md#photo-upload-future)
оставляет фото в FUTURE и требует согласия сотрудника/модерации;
[MEDIA_STORAGE_UPLOAD.md](MEDIA_STORAGE_UPLOAD.md) имеет
`STOP_FOR_MEDIA_STORAGE_DECISION` и отдельно исключает staff photo из первого slice.

Проверка реального кода на указанной базе:

| Необходимая часть | Наблюдение |
| --- | --- |
| Постоянные bytes | `docker-compose.yml` монтирует persistent volume только PostgreSQL. Backend media volume и настроенного object storage нет. |
| Upload и проверка | `VenueStaffRoutes.kt` принимает nullable строку `photoRef` и ограничивает её длину; file endpoint, проверка содержимого/размера и staff-media lifecycle отсутствуют. |
| RBAC/черновик | У профиля есть actor-aware управление и публикация, но нет отдельного безопасного photo asset/приватного draft-byte delivery. |
| Guest delivery | `GuestTodayStaffResolver.kt` выставляет `photoRef=null` в MANUAL и SCHEDULE; `guestVenue.ts:renderTodayStaff` рисует инициал. |
| Замена/удаление | У staff photo нет asset ownership, атомарной замены, отзыва Guest URL и физической очистки. |

Существующий Telegram `file_id` flow для info-section media и
`TelegramApiClient.downloadFile/sendPhotoBytes` не закрывают эти требования и
не являются одобренным staff-photo pipeline.

Рекомендуется **один private S3-compatible storage adapter для новых bytes**,
совместимый с направлением canonical media spec, плюс отдельный asset lifecycle
staff profile. Не переносить venue/media таблицы в staff domain и не применять
архивный media-код. Минимальный будущий slice:

1. Одна фотография JPEG/PNG на карточку, максимум 10 MiB, 8192 px на сторону и
   32 MP как исходные лимиты media spec; сервер sniff/decode, ограничение потока и
   pixel count, безопасное повторное кодирование с удалением metadata. Запрет SVG,
   произвольных URL и Telegram-аватара как автоматической замены фотографии.
2. DB asset с venue/profile ownership, случайным storage key и состояниями private
   draft/approved/deleted. Upload/replacement проверяет профильный RBAC и согласие;
   Owner/Manager — только разрешённые карточки. Возможность own-Staff upload требует
   отдельного решения; не наследовать venue-media разрешения автоматически.
3. Закрытый preview только авторизованным управляющим. Публикация одобренного фото
   учитывает consent и текущую eligibility профиля. Guest получает opaque platform
   delivery URL; проверять текущую видимость, замену/удаление и не раскрывать storage ref.
   Расписание/публикационные условия профиля не менять.
4. Заменять asset атомарно после полной проверки нового; ошибка оставляет прежнее
   фото. Скрытие/удаление немедленно закрывает delivery и инвалидирует cache;
   физическое удаление — подтверждаемый retry-job по согласованному retention.

До реализации согласовать provider/region, staging/production buckets, владельца
эксплуатации и credentials, доступ/backup/restore, RPO/RTO, retention/versioning,
физическое удаление, quota/мониторинг и месячный бюджет. Появляются расходы на
storage, requests, egress и backup; сумма неизвестна до выбора провайдера и нагрузки,
бесплатность не обещается. Проверить durability после пересоздания контейнера,
изоляцию/приватный draft, malformed/oversize files, revoke delivery, replacement,
cleanup и восстановление прежде, чем показывать рабочий picker.

До этого UI оставляет честный placeholder/сообщение о недоступности загрузки.
Отсутствие storage не блокирует согласованные UI-исправления приглашений UX06.
