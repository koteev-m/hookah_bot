# Migration policy (Flyway)

Canonical general release/deploy and rollback policy is `docs/DEPLOYMENT_RUNBOOK.md`. The single
PostgreSQL V126 policy/state-machine order and recovery boundary are
`docs/V126_STAGING_CUTOVER_CONTRACT.md`; executable authority is only
`scripts/v126-cutover.sh`. This file remains the Flyway migration detail policy and does not
authorize migration execution.

Документ описывает безопасный процесс валидации и обычного применения миграций без даунтайма;
контролируемое окно V126 на staging является явно описанным исключением ниже.

## Политика для CI

1. Любое изменение схемы должно идти через Flyway-миграции (`classpath:db/migration/...`).
2. В CI обязательно выполняются:
   - `./gradlew :backend:app:ktlintCheck`
   - `./gradlew :backend:app:test` (включая Testcontainers-сценарии, требуется Docker)
3. Валидация миграций в CI выполняется тестами, которые запускают Flyway `validate + migrate` на H2/Postgres.
   Минимальная проверка локально:
   - `./gradlew :backend:app:test --tests "*Migration*"`

> Даже если SQL-миграции в PR не менялись, `:backend:app:test` остаётся обязательным, чтобы поймать рассинхрон схемы и кода.

## Применение миграций в production

1. Миграции применяются автоматически на старте backend через `DatabaseFactory` (Flyway).
2. Для обычной совместимой миграции:
   - сначала запустить один инстанс новой версии (canary/pre-deploy),
   - дождаться успешного завершения Flyway в логах,
   - затем раскатывать остальные инстансы.
3. После старта проверить `GET /db/health` и отсутствие ошибок Flyway в логах.

### Исключение для PostgreSQL V126 на staging

Общий canary/rolling порядок выше к V126 не применяется. PostgreSQL V126 меняет семантику курсора
непрочитанных сообщений; старый V125 backend обновляет только `last_read_at`, поэтому смешанная
работа V125/V126 может повторно создавать или искажать unread-индикаторы. V126 разрешён только через
20 последовательных receipt-gated состояний `scripts/v126-cutover.sh` по политике
`docs/V126_STAGING_CUTOVER_CONTRACT.md`: публичный generic `503` drain, остановленный V125 backend,
нулевые writer/unidentified/idle-in-transaction sessions, prepared transactions и replication
slots, обе проверенные backup/rehearsal точки и финальный preflight предшествуют отдельно
разрешённому запуску ровно одного V126 backend под `PRODUCT` + временным `V126_SMOKE`.
Baseline дополнительно требует content hashes restricted database/identity files, полного staging
`.env` и ordinary Caddyfile, а также побайтовое совпадение удалённых `docker-compose.yml`,
maintenance guard и admission guard с exact `RELEASE_SHA` Git objects. Каждая зависимая remote
операция повторно проверяет точное baseline- или завершённое maintenance/Caddy receipt-состояние;
частичный переход без receipt допустим только в точной recovery-позиции, если его детерминированное
обратное преобразование восстанавливает immutable source bytes. Это отдельная предпосылка подготовки
HT-13; sequencer не загружает и не исправляет execution surface. Запуск backend выполняется
create-only/`--no-build`, с restart policy `no`, `RestartCount=0` и ровно одним явным start; runtime
gate требует один long-polling V126 backend, global V125 zero и project old-image zero.

Точный main `ecb09601975678a41d89e5c824cc7812c7876481` (tree
`8c97996e317f0182b4871d2a2537a732d4830f64`, родители по порядку
`9f51ebbd2dae0702b4b2f6333c1b42fc94cd1fc1` и
`d9c656b1c5feb757b79558209f130c08cba81cf5`) является базой HT-12P, а не финальным release SHA.
Финальный SHA выбирается только после отдельной авторизации интеграции HT-12P в main и нового
успешного exact-SHA main Actions run. До этого миграционные tree/blob identities должны оставаться
побайтно равны базе; любое расхождение блокирует release identity.

После применения V126 нельзя запускать V125 поверх этой базы и нельзя использовать частичный
restore, `flyway repair`, ручное редактирование `flyway_schema_history`, cursor rows или domain data.
Каждая recovery branch классифицирует Flyway до изменения Caddy/backend. Основной ответ на ошибку —
выполнить bounded post-V126 stop: сохранить публичный drain, остановить exact V126, уже
остановленное, неожиданное V125 или unknown/multiple состояние до доказанного backend zero, явно
запретить V125 и потребовать отдельно reviewed V126-compatible forward fix. Pre-V126 recovery может
завершить только проверенную частичную Caddy activation перед безопасным V125 rollback. Full-DR
sequencer проверяет backup/session prerequisites и фиксирует принятую точку
восстановления/data-loss boundary, но всегда останавливается до отдельной restore-авторизации.
Частичный или автоматический restore запрещён.

## Правила безопасности миграций

- Не редактировать уже применённые versioned-миграции (`V*__*.sql`), добавлять только новую версию.
- Для потенциально долгих DDL использовать совместимую, поэтапную стратегию (expand/contract).
- Любые destructive-операции (`DROP`, массовые `UPDATE`) предварительно прогонять на staging/restore-копии.
- Никогда не изменять уже зафиксированные V126/H2 V127 blobs или checksums для подготовки релиза;
  исправление после применённой V126 оформляется новой reviewed forward migration.
