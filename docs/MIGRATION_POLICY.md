# Migration policy (Flyway)

Canonical general release/deploy and rollback policy is `docs/DEPLOYMENT_RUNBOOK.md`. The single
current PostgreSQL V126 staging order and recovery boundary are
`docs/V126_STAGING_CUTOVER_CONTRACT.md`. This file remains the Flyway migration detail policy and
does not authorize migration execution.

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
работа V125/V126 может повторно создавать или искажать unread-индикаторы. V126 разрешён только по
`docs/V126_STAGING_CUTOVER_CONTRACT.md`: публичный generic `503` drain, остановленный V125 backend,
нулевые writer/unidentified/idle-in-transaction sessions, prepared transactions и replication
slots, обе проверенные backup/rehearsal точки и финальный preflight предшествуют запуску ровно одного
V126 backend под `PRODUCT` + временным `V126_SMOKE`.

Точный main `9f51ebbd2dae0702b4b2f6333c1b42fc94cd1fc1` (tree
`4071962a6850d977c4d7c319bfecc7cd4c2273d1`) является базой HT-12C, а не финальным release SHA.
Финальный SHA выбирается только после отдельной авторизации интеграции этой ветки в main и нового
успешного exact-SHA main Actions run. До этого миграционные tree/blob identities должны оставаться
побайтно равны базе; любое расхождение блокирует release identity.

После применения V126 нельзя запускать V125 поверх этой базы и нельзя использовать частичный
restore, `flyway repair`, ручное редактирование `flyway_schema_history`, cursor rows или domain data.
Основной ответ на ошибку — сохранить публичный drain и подготовить reviewed V126-compatible forward
fix. Полный согласованный V125 restore возможен только как отдельно авторизованный recovered-DR
сценарий, не как успешный V126 deployment.

## Правила безопасности миграций

- Не редактировать уже применённые versioned-миграции (`V*__*.sql`), добавлять только новую версию.
- Для потенциально долгих DDL использовать совместимую, поэтапную стратегию (expand/contract).
- Любые destructive-операции (`DROP`, массовые `UPDATE`) предварительно прогонять на staging/restore-копии.
- Никогда не изменять уже зафиксированные V126/H2 V127 blobs или checksums для подготовки релиза;
  исправление после применённой V126 оформляется новой reviewed forward migration.
