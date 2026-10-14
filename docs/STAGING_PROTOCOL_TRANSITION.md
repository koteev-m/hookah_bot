# Разовый переход ordinary staging-протокола

**Текущий статус (2026-10-14): переход ordinary V125 выполнен.** Controller установлен;
`initialize/status` принят как `ACCEPTED`, затем QR-релиз принят как `SUCCESS / ACCEPTED`.
Действующая [ordinary инструкция](STAGING_ORDINARY_RELEASE.md) описывает последующие
V125-релизы без миграции. Описанные ниже подготовка, решение и шаги установки —
исторический план перехода; их не нужно выполнять повторно как условие текущего
ordinary release. Они не дают права на V126 или новые серверные действия.

run724 сохраняет исторический `UNKNOWN`: первичные execution records утрачены,
поиск завершён. f782 и все прежние records сохраняются неизменно. Новый accepted
baseline описывает свежее состояние и не превращает старые операции в SUCCESS.
По историческому контексту перехода staging был на V125 без V126, source
`f577934691a1a7a79ba327c54e2055425142b7be`. Это не текущий принятый QR image
и не Docker image ID; идентичность образа подтверждается отдельно.

## Исторический план отдельного решения владельца

Нужны утверждённые оператор/окно для существующего staging, перечень прекращаемых
ordinary полномочий, допустимые изменения конфигурации, backup/recovery point/RPO,
принимаемый baseline и остаточный риск общей инфраструктуры/run724. Разрешение на
конкретный следующий candidate release выдаётся отдельно.

После такого решения, одной ограниченной операцией:

1. Подтвердить текущие legacy writers: supervisor/descriptor routes, процессы и
   SSH sessions, timers/jobs, deploy keys и доступ к staging configuration/Docker.
   Прекратить/ограничить их ordinary полномочия и незавершённые исполнения; intents
   не повторять. Не активировать V126 execution path, не отключать его строгие gates.
   Удаление старого lock или создание нового JSON не заменяет этот шаг.
2. Подтвердить staging Compose project/physical path, один backend/poller,
   actual immutable image ID/source/CI/artifact bytes/migration manifest, policy,
   backend/PG binding, system ID/DB/user/volume/network, V125/V126=0/failed=0.
   Отдельно зафиксировать production и общие container identities. Сверить
   running `APP_VERSION`, `/version` и persistent Compose image с проверенным record.
   Если нужны исправление `.env` image/version или backend restart, включить их
   конкретно в approval: автоматически менять текущий baseline ради PASS нельзя.
3. Проверить backup, успешный restore в изолированную среду, recovery point и
   понятный RPO — какие более поздние записи допустимо потерять при DB restore.
   Утвердить регулярную backup policy и периодическую restore проверку.
4. Проверить runtime/tool versions и ресурсы VPS. Установить reviewed
   `scripts/staging_release.py` в root-owned `/opt/hookah-bot/staging_release.py`.
   Выбрать один SSH alias/проверенный host key для root worker. Для отдельного deploy
   key ограничить SSH forced command этим worker/target, запретить shell, PTY,
   forwarding и agent forwarding; не выдавать новый общий Docker доступ.
   Применение этих ограничений и изменение существующих credentials входят в approval.
5. Подготовить **единственную** root-owned (0600) регистрацию
   `/etc/hookah-bot/staging-release.json`: `target`, `transition_approved: true`,
   `scope` из подтверждённых фактов. `scope` содержит project ID/project, physical
   directory/compose file, local/public origins, SHA256 Compose bytes, fingerprint
   effective configuration без release image/version, migration manifest и pinned
   PostgreSQL/production/shared identities. Использовать `CommandRuntime.observe`
   для фактических fingerprints, сверив полученные identities с утверждёнными;
   произвольный fixture не является baseline. Scope и Compose неизменны между релизами.
   Создать в staging directory root-owned 0700 `.staging-release`; сам directory,
   Compose и `.env` должны быть root-owned, без symlinks и group/world write.
   Никакие эти файлы текущая задача не создаёт на сервере.
6. С проверенным текущим image record выполнить только первоначальную регистрацию
   состояния (не Docker recreate):

   ```bash
   bash scripts/deploy-staging.sh initialize --target <target> \
     --candidate accepted-current.json --owner-approved
   bash scripts/deploy-staging.sh status --target <target>
   ```

   `initialize` требует существующую approved registration, проверяет image,
   snapshot и health, создаёт ACCEPTED state и отказывается перезаписывать существующий.
   Проверить production/PostgreSQL и baseline smoke. Старый ordinary route заменён,
   конкурирующих активных writers нет. Дальнейшие deploy/rollback имеют свой approval.

## Остаточный риск общего Docker daemon

Нельзя абсолютно доказать отсутствие утраченных daemon requests run724. Свежий
baseline, прекращение известных writers и спокойное окно наблюдения уменьшают
вероятность поздних эффектов, но не восстанавливают историю. Владелец принимает
этот риск либо оставляет переход закрытым; повторять завершённый поиск не нужно.

`--project-name`, один lock и fingerprints ограничивают/обнаруживают действия
controller, но не изолируют root/Docker authority и не предотвращают сторонние
mutations. Ресурсная нагрузка image load/recreate может повлиять на production:
до разрешённой доставки проверить свободные disk/RAM/CPU и выбрать спокойное окно.
Практические меры — один оператор, прекращение legacy writers, ограниченный SSH key,
безопасный backend без mounts/host privileges, pinned resources и проверки до/после.

Daemon restart/prune, общие socket ACL/Docker group, глобальное fencing, остановка
общих units или изменение production credentials могут затронуть production.
Controller этого не делает. Если такие меры потребуются, владелец отдельно выбирает
их точный перечень, downtime и влияние; локальная реализация не принимает решение.

**Исторический следующий шаг закрыт для ordinary V125:** разовый переход и первый
принятый release состоялись. run724 сохраняет `UNKNOWN`; V126 требует отдельной
авторизации и свежих доказательств по [cutover contract](V126_STAGING_CUTOVER_CONTRACT.md).
