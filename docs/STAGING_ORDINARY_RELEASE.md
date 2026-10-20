# Staging: простой install/update и исторические release-контракты

## HT70 — простой STAGING: install и последующие обновления

Решение владельца от 2026-10-20: для staging без реальных клиентов обязательными
условиями больше не являются HDC/1, HDC/2, независимые Authority/CAS/custody,
external GitHub anchor, отдельные verifier/custodian keys, privileged HDC executor,
CR-1 и историческая V125→V126 cutover-цепочка. Это решение имеет приоритет над
противоречащими staging-рекомендациями ниже и в связанных исторических документах.
История HT46–HT69, исходники и evidence сохраняются. Строгие контракты production
не выполнены этим решением; будущая production-установка требует отдельного допуска.
Нельзя применять этот путь к старой БД V125, существующей CR-1 registration или
переносить сюда незавершённые операции старого окружения.

Текущий entrypoint для **нового простого staging** — `scripts/simple_staging.py`.
Исторический `deploy-staging.sh` / Controller не меняет своего назначения.
Новый entrypoint не устанавливает ОС, не вызывает SSH, не настраивает Caddy/DNS,
не восстанавливает production/MarketTap/AI Bot и не выполняет cleanup/DB restore.

Согласованная топология нового AEZA: только `hookah-bot` и `clubs-bot-stage`.
Системный Caddy: `staging.hookahtootah.club → 127.0.0.1:8080`,
`stage.nightconcierge.help → 127.0.0.1:8081`. Hookah создаёт только Compose services
`postgres`/`backend`, volume `hookah-bot_pgdata`, network `hookah-bot_default`.
PostgreSQL 17 не публикует host port; credentials/config/volumes/networks раздельные.
Общий lock `/run/lock/shared-staging-docker.lock` удерживается от проверки до
результата операции. Ожидание по умолчанию 30 секунд, `--lock-wait` допускает 0–60.
Это кооперативный `flock`, не защита от другого root или прямого доступа к Docker.
Clubs должен использовать **тот же inode/path и flock на всё время операции**;
lock нельзя удалять/заменять после освобождения. Общий Coordinator не требуется.

Все Docker-команды на VPS выполняет доверенный системный оператор (Linux root).
Обычному пользователю деплоя не выдаются docker group, socket, произвольный sudo
или доступ на запись к скрипту/конфигурации. Root на общем daemon уже имеет полные
полномочия: раздельные Compose projects не являются границей привилегий root.
Код фиксирует `hookah-bot`, порт 8080 и локальный `/var/run/docker.sock`, отвергает
ambient DOCKER_/COMPOSE_ overrides; чужих Compose resources он не изменяет.

### Подготовка образа после зелёного CI

На доверенной build-машине нужен чистый checkout точного опубликованного release
SHA, Docker Buildx, Python 3.9+ и `gh` с доступом чтения Actions. Образ собирается
вне VPS из `backend/Dockerfile`, включает Mini App с URL staging. Для первого
install packaged PostgreSQL manifest обязан содержать ровно V1–V126.
Оператор отдельно аутентифицируется в GHCR с правом публикации. Запуск следующего
скрипта **публикует image** и требует отдельного разрешения; в HT70 не запускался:

```bash
# RELEASE_SHA и CI_RUN_ID — фактический SHA и уже завершённый успешный push-run CI.
# ARTIFACT_DIR — новый каталог вне checkout; скрипт откажет, если он существует.
bash scripts/build-simple-staging-image.sh "$RELEASE_SHA" "$CI_RUN_ID" "$ARTIFACT_DIR"
```

Скрипт проверяет точный HEAD/чистоту checkout, читает один законченный CI run и его
jobs без ожидания/polling, требует все обязательные backend/Compose/Mini App/Docker
jobs. Затем публикует `ghcr.io/koteev-m/hookah_bot/backend:<SHA>` и создаёт
`candidate.json` с **registry manifest digest**, SHA и CI run ID. Deploy использует
только `ghcr.io/koteev-m/hookah_bot/backend@sha256:…`; tag не является selector.
Повторная сборка того же SHA может дать другой digest — это другой кандидат.
CI подтверждает source SHA, а trusted builder связывает с ним image; независимая
криптографическая provenance здесь не заявляется. Не подменять digest image ID.

На VPS перед каждым install/deploy ещё раз читается завершённый CI run, выполняется
pull точного digest и inspect platform/source/revision. Из never-started контейнера
читается packaged JAR; проверяются V1–V126, SQL SHA-256 и Flyway checksums.
Нельзя обойти проверки флагом `ci_success=true` или ручным manifest в candidate.
`inspect` позволяет получить проверенный manifest до изменения приложения:

```bash
umask 077
python3 -B /opt/hookah-bot/simple_staging.py inspect --directory /opt/hookah-bot \
  --candidate /opt/hookah-bot/candidate.json > /opt/hookah-bot/candidate-inspected.json
```

### Первый запуск на чистом AEZA

Все пути `/opt/hookah-bot` ниже — **новый предлагаемый каталог**, который оператор
создаёт этими командами; его существование на AEZA не утверждается. Сначала оператор
проверяет выбранный сервер/SSH host key, архитектуру (build helper: linux/amd64),
устанавливает Docker Engine 28+, Compose v2+, Python 3.9+ и GitHub CLI по официальной
инструкции ОС. Системный Caddy устанавливается/настраивается отдельно.
Нужно заранее обеспечить доступ `gh` к Actions и Docker к приватному GHCR.
Не переносить прежние credentials или чужие volumes. Не запускать команды на старом
сервере как способ «починить» незавершённый CR-1/HDC.

```bash
# На выбранном новом VPS, доверенный root-оператор:
install -d -m 0700 /opt/hookah-bot
hostname
uname -m
docker version
docker compose version
python3 --version
gh auth status
```

После отдельного разрешения оператор доставляет проверенный `scripts/simple_staging.py`
и `candidate.json` по SSH/SCP в этот каталог (имя SSH target берётся из его реальной
конфигурации). Оба файла должны принадлежать root, mode 0600; каталог mode 0700.
Пример доставки с build-машины: `$SSH_TARGET` — явно выбранный root/admin target
с заранее проверенным host key; если вход не root, установку файлов выполняет
доверенный оператор отдельно, без выдачи deploy-пользователю произвольного sudo:

```bash
scp -o StrictHostKeyChecking=yes scripts/simple_staging.py \
  "$ARTIFACT_DIR/candidate.json" "$SSH_TARGET:/opt/hookah-bot/"
ssh -o StrictHostKeyChecking=yes "$SSH_TARGET"
```

`POSTGRES_DIGEST_REF` — независимо выбранный immutable `postgres@sha256:…`
**PostgreSQL 17** для архитектуры сервера; скрипт проверяет PG_MAJOR и platform.
`EXPECTED_HOST` — ожидаемый hostname именно нового сервера, сверенный оператором.

```bash
chmod 0600 /opt/hookah-bot/simple_staging.py /opt/hookah-bot/candidate.json
python3 -B /opt/hookah-bot/simple_staging.py init-config \
  --directory /opt/hookah-bot --host "$EXPECTED_HOST" \
  --architecture amd64 --postgres-image "$POSTGRES_DIGEST_REF"
python3 -B /opt/hookah-bot/simple_staging.py install --directory /opt/hookah-bot \
  --candidate /opt/hookah-bot/candidate.json
```

`init-config` создаёт случайные DB/JWT/pepper secrets в private `config.json`,
не печатает их, не перезаписывает существующий config. Telegram token пустой,
polling выключен. `install` отказывает при уже существующих неуправляемых ресурсах
`hookah-bot`; создаёт отдельную пустую БД, backend применяет реальный Flyway V1–V126.
Проверяются `/health`, `/db/health` (DB readiness; отдельного `/ready` в backend нет),
`/version`, защищённый `/api/guest/catalog` (401 без токена), HTML и assets `/miniapp/`.
Это технический smoke, не полный authenticated product/Telegram smoke.
`SUCCESS` сохраняется в `result.json` и `operation-<id>.json`; исходный кандидат,
manifest и DB binding входят в результат. `compose.json` генерируется скриптом,
содержит secrets, mode 0600; вручную его не редактируют.
Повторный `install` того же image проверяет runtime и возвращает `unchanged: true`.

### Одно штатное обновление

Подготовить новый image/candidate тем же build helper после зелёного CI точного SHA,
доставить новый candidate на VPS по согласованному SSH-каналу, mode 0600. Затем:

```bash
python3 -B /opt/hookah-bot/simple_staging.py deploy --directory /opt/hookah-bot \
  --candidate /opt/hookah-bot/candidate.json
```

Для неизменного SQL manifest выполняется только
`compose up -d --no-build --pull never --no-deps --force-recreate backend`.
PostgreSQL не перезапускается/не пересоздаётся. Проверяется прежний container ID,
volume, network ID, PostgreSQL system identifier и конфигурация. Никаких `down -v`,
`prune`, общего cleanup или операций с 8081/Caddy нет.

Forward migration допускается только append-only с неизменными старыми SQL bytes и
checksums. Оператор/reviewer обязан проверить совместимость SQL и приложения;
скрипт не выдаёт наличие `ADD` за доказательство безопасности произвольного SQL.
Для `--compatibility-review /opt/hookah-bot/compatibility.json` (mode 0600) нужны:
`from_manifest` = digest manifest текущего result, `to_manifest` = digest manifest
из `inspect`, `backward_compatible: true`, `reason` = содержательное объяснение.
Digest вычисляется функцией `digest()` из доставленного `simple_staging.py`:
SHA-256 JSON с `sort_keys=True, separators=(',', ':')`. Проверить пару можно так:

```bash
cd /opt/hookah-bot
python3 -B - <<'PY'
import json
from simple_staging import digest
for filename, key in [('result.json', 'target'), ('candidate-inspected.json', None)]:
    value = json.load(open(filename))
    print(filename, digest((value[key] if key else value)['manifest']))
PY
python3 -B /opt/hookah-bot/simple_staging.py deploy --directory /opt/hookah-bot \
  --candidate /opt/hookah-bot/candidate.json \
  --compatibility-review /opt/hookah-bot/compatibility.json
```

Перед новой схемой останавливается **только Hookah backend**, создаётся `pg_dump -Fc`,
проверяется `pg_restore --list`, сохраняются SHA-256 и metadata, затем запускается
candidate. Ошибка backup блокирует миграцию. Автоматического DB rollback нет.
Backup и result следует скопировать в защищённое хранилище вне VPS по принятой
политике; локальный dump не защищает от потери сервера. PostgreSQL major upgrades
и изменение DB credentials/binding не входят в обычный app update.

### Статус, логи, backup и восстановление

```bash
python3 -B /opt/hookah-bot/simple_staging.py status --directory /opt/hookah-bot
python3 -B /opt/hookah-bot/simple_staging.py backup --directory /opt/hookah-bot
# Только чтение логов своих сервисов; вывод может содержать операционные данные.
docker compose -p hookah-bot --project-directory /opt/hookah-bot \
  --env-file /dev/null -f /opt/hookah-bot/compose.json logs --tail 100 backend postgres
```

`APPLYING` после прерывания или `UNKNOWN` после ошибки не разрешают следующий deploy.
Не удалять `result.json`, lock, volume и историю, не повторять install для обхода.
`status` показывает записанный intent и наблюдаемые контейнеры; не объявляет intent
фактическим успешным deployment. Сначала проверить status/логи/свободное место.
Если candidate сам завершил запуск после потери ответа, следующая команда только
проверяет точные image/config/DB/history/HTTP и подтверждает текущий результат:

```bash
python3 -B /opt/hookah-bot/simple_staging.py reconcile --directory /opt/hookah-bot
```

При неработающем новом приложении и **том же полном SQL manifest и runtime config**:

```bash
python3 -B /opt/hookah-bot/simple_staging.py rollback --directory /opt/hookah-bot
```

Rollback заново проверяет CI/digest предыдущего image и точную DB history, меняет
только backend и повторяет smoke. При любом schema/config drift он отказывает;
после forward migration нужен совместимый forward fix с отдельным решением, а не
schema downgrade. Неизвестная/частично созданная DB без сохранённого binding,
ошибка Flyway, отсутствующая БД или невозможность доказать history требуют ручной
диагностики оператора; автоматического reinstall/reset нет. Recovery не закрывается
одним успешным освобождением lock. Восстановление данных из dump — отдельная
опасная операция с согласованной потерей записей, проверкой restore в изолированной
БД и разрешением владельца; entrypoint не содержит destructive restore-команды.

### HTTPS и Telegram после smoke

После локального технического и нужного product smoke оператор отдельно подключает
Caddy/DNS только для `staging.hookahtootah.club → 127.0.0.1:8080`; проверяет HTTPS
health/DB health/Mini App в браузере. Hookah script не редактирует общий Caddy и не
затрагивает маршрут Clubs на 8081. При согласованном включении Telegram оператор
редактирует **только** `runtime` в private `config.json`: реальные bot token/username,
owner Telegram ID, `TELEGRAM_BOT_ENABLED="true"`. Затем тем же проверенным candidate:

```bash
python3 -B /opt/hookah-bot/simple_staging.py deploy --directory /opt/hookah-bot \
  --candidate /opt/hookah-bot/candidate.json --apply-config
```

Флаг явно разрешает замену runtime config; DB/host binding изменить им нельзя.
PRODUCT/RBAC остаются серверными проверками, maintenance OFF, static lists пустые.
До включения polling оператор исключает другой активный poller того же бота.
После включения нужен реальный Telegram/Mini App и соответствующий изменению smoke
по QA strategy. `SUCCESS` технической установки не объявляет production readiness.

---

## Исторические V125 / CR-1 / HDC процедуры

Следующие разделы сохранены как история и контракты прежних targets. Их требования
не блокируют новый простой staging HT70 и не предоставляют ему автоматический
production-допуск.


**Текущий статус (2026-10-14): ordinary V125 controller установлен и работает.**
Первоначальные `initialize/status` дали `ACCEPTED`; QR-релиз source
`1a2011ed27b0ea13d24bc6c88e48a0c7e9dcb5ff` с image
`sha256:888e4d41e9a713873328aed4f542569a5089ce968c8f29aa481377a7c0b29c23`
завершился `SUCCESS / ACCEPTED`. Ручной Mix QR smoke — 7/7 PASS; Alpha Hookah и
прямой доступ к чужому заведению вручную не проверены. Последняя подтверждённая
staging schema — V125 (`V126=0`, `failed=0`). [Разовый переход](STAGING_PROTOCOL_TRANSITION.md)
сохранён как история. Единственный ordinary entrypoint — `scripts/deploy-staging.sh`;
`deploy-staging-controlmaster.sh` только передаёт ему аргументы. Controller не
является механизмом V126 migration и не разрешает V126 cutover.

Локальное изменение `HT-V126-ONE-VPS-SAFE-IMPLEMENT-01` сохраняет V125-контракт.
Любая запись `.v126-target-operations` (включая partial/UNKNOWN, файл или symlink)
блокирует controller под существующим `deployment.lock`; её нельзя удалять для
возобновления ordinary release. Genesis перед созданием registry удерживает тот же
существующий ordinary lock. Неполный/неизвестный ordinary result не становится
завершённым после освобождения lock. Применение обновлённых исполнителей на VPS
не выполнено и требует отдельного разрешения; старый установленный код этим diff
не изменён.

`postgresql_head=126`, даже вместе с `handoff_approved_and_applied=true`, не разрешает
новые V126 ordinary images: `V126_ORDINARY_AUTHORITY_TRANSFER_REQUIRED`.
Существующий `v126-ordinary-deploy.py` умеет только повторное развёртывание уже
принятого image после полного terminal/applied-handoff/transfer chain. Передача
полномочий от этого контракта новому image-selection controller остаётся
`DECISION_REQUIRED`; подробности — [operational handoff](V126_OPERATIONAL_HANDOFF.md#current-ordinary-controller-boundary).
Обычный релиз не применяет 125→126 и не разрешает rollback V125 поверх V126.

## Подготовка образа и approval

Для новой чистой установки V126 утверждён [CR-1](CLEAN_REINSTALL_CONTRACT.md).
HT33 реализует typed initial acceptance и subsequent releases в локальном API
существующего `Controller`: неизменный manifest либо reviewed compatible forward,
exact CI/image/tool/DDL/history, recovery prerequisites, один lock и независимый
CAS intent/result. FAILED/UNKNOWN блокируют replay, rollback после изменения schema
по умолчанию запрещён. Полные правила, tests и remaining adapter/enrollment decision
находятся в CR-1, а не в дополнительном runbook.
HT34 подключает disposable Docker installer и подписанный local evidence adapter к
тому же CLI (команды и trust matrix — в CR-1). Live worker остаётся V125-only;
production enrollment/provider/custody adapters закрыты: BLOCKED_EXTERNAL_ENROLLMENT.
Примеры V125 ниже не применимы к CR-1 target и не разрешают фиктивный handoff.

Бот, backend и Mini App обновляются одним immutable image. Для V125 весь PostgreSQL
migration manifest должен совпадать с принятым; schema head = 125, V126/failed = 0.
Изменение схемы или migration bytes требует отдельного migration-процесса по
[runbook](DEPLOYMENT_RUNBOOK.md). Текущий main содержит V126 и автоматически не подходит.

1. Завершённый зелёный GitHub CI для точного release SHA; проверки области изменения
   по [QA strategy](TESTING_QA_SMOKE_STRATEGY.md). CI не отслеживается controller.
2. Построить образ **вне VPS** существующим Hookah image build/save tooling.
   Независимо проверить artifact bytes, фактический migration manifest, image ID,
   source/revision и linux/amd64. OCI labels и JSON flags сами по себе этого не доказывают.
   Отдельно разрешённая доставка загружает проверенный образ в daemon; controller не
   выполняет build, pull или load и не удаляет предыдущий образ.
3. Владелец разрешает конкретный candidate ID/SHA и staging target. Действующая
   backup policy должна обеспечивать успешный backup допустимого возраста, принятый
   RPO и периодическую проверку восстановления. Полный restore rehearsal нужен для
   первого подключения и конкретного риска, а не каждого изменения Mini App.

Подготовить JSON record проверенного образа, без секретов (значения ниже — placeholders):

```json
{
  "id": "sha256:<64 hex image ID>", "revision": "<40 hex release SHA>",
  "source": "https://github.com/koteev-m/hookah_bot", "platform": "linux/amd64",
  "ci_sha": "<тот же SHA>", "ci_status": "success", "approved": true,
  "available": true, "postgresql_head": 125,
  "migration_fingerprint": "<64 hex принятого migration manifest>"
}
```

Record фиксирует проверку оператора, а не заменяет её. Controller сверяет доступный
image ID/platform/OCI labels и сохраняет record вместе с точным предыдущим образом.

## Первый и последующие релизы после подключения

`<target>` — явно выбранный SSH alias с проверенным host key и root worker,
совпадающий с единственной серверной регистрацией. Нет default host, произвольного
server path или fixture в качестве live authority. Python 3.9+, Docker CLI/Engine 28+
(inspect по immutable ID с проверкой OS/architecture), Compose v2, curl и PostgreSQL psql проверяются при переходе.
Ни одну команду этого раздела сейчас на VPS выполнять нельзя.

```bash
# Узнать accepted/current image и состояние. Подставить accepted ID в следующую команду.
bash scripts/deploy-staging.sh status --target <target>
bash scripts/deploy-staging.sh deploy --target <target> --candidate candidate.json \
  --expected-current sha256:<accepted image ID> --owner-approved
# SUCCESS / READY_FOR_SMOKE: выполнить ручной smoke Telegram, Mini App и изменённой области.
bash scripts/deploy-staging.sh accept --target <target> --smoke-passed
```

Один серверный `flock` закрывает параллельные операции всех клиентов. Под lock
проверяются Compose project/physical directory/file, staging policy, единственный
backend, credentials и единственный PostgreSQL DNS endpoint, system ID/DB/user,
V125, production/PostgreSQL/общие container identities. Drift блокирует операцию.
Завершённый restart защищённых сервисов при неизменной структурной идентичности не
требует нового baseline: время запуска, счётчик рестартов, runtime endpoint ID и MAC
без достоверного desired value не входят в fingerprint. Остановка, pause/restarting,
изменение container/image/config,
mounts, сетевой привязки или DB binding по-прежнему блокируют операцию.
Порядок `Mounts` с независимыми абсолютными destinations не влияет на fingerprint:
записи сортируются по полному JSON, сохраняя все поля и повторы. Для совпадающих,
вложенных или неопределённых destinations порядок сохраняется и его изменение
остаётся drift. Исходный Docker inspect не изменяется. Upgrade/recreate
требуют отдельной проверки и решения владельца о новом baseline; автоматического
rebaseline/reset нет. `status` проверяет свежий snapshot для определённого состояния;
при UNKNOWN он показывает только intended image, не выдаёт его за фактический.
Сетевой fingerprint сохраняет состав сетей, NetworkID, адреса, aliases/DNSNames,
IPAMConfig и driver options. MAC не классифицируется по наблюдаемому адресу: Docker
может показывать в endpoint как динамический, так и явно заданный MAC. Явный MAC
проверяется по закреплённой staging Compose configuration для конкретного network
attachment или service с единственной сетью; legacy Config.MacAddress при единственной
сети сохраняет MAC в fingerprint. Для production/shared без достоверного desired MAC
его проверка не обещается; неоднозначный service-level MAC при нескольких сетях также
не подтверждается. Container/config/network identity и отдельные проверки фактического
backend → staging PostgreSQL binding остаются обязательными. Статический MAC не требуется.

Controller атомарно сохраняет **только `BACKEND_IMAGE` и `APP_VERSION` в staging `.env`**
(mode 0600), проверяет неизменность остальной effective Compose configuration и
выполняет только `up -d --no-build --pull never --no-deps --force-recreate --scale backend=1 backend`.
Остальные `.env` bytes сохраняются; multiline quoted values требуют предварительного
совместимого оформления в отдельно разрешённом переходе. PostgreSQL/production/общие
сервисы не запускаются и не пересоздаются. Обычный следующий Compose invocation без
shell image overrides и restart сохраняют выбранный image. Ручные изменения image
или запуск legacy Compose writers запрещены; controller обнаруживает их расхождение.

Docker/configuration replacement имеет общий лимит 120 секунд. Readiness — ещё
120 секунд на локальные и публичные `/health`, `/db/health`, GET `/miniapp/` и локальный
`/version`; финальный poststate — до 5 секунд. Raw `.env`, inspect Env, HTTP bodies
и command stderr не выводятся. `SUCCESS / READY_FOR_SMOKE` ещё требует ручного smoke;
`SUCCESS / ACCEPTED` завершает релиз. Следующий deploy использует новый accepted ID:
A → B → C, без перепривязки target или изменения baseline.

## FAILED, UNKNOWN и rollback

- `FAILED` до mutation означает отказ в проверках. Readiness failure с точным
  подтверждённым poststate оставляет `READINESS_FAILED`: владелец выбирает диагностику
  или явный rollback. Повторный deploy до завершения текущей операции запрещён.
- `UNKNOWN` означает незавершённый `APPLYING`, timeout/неуспех/interruption Docker,
  потерю poststate либо SSH result. **Никаких автоматических retry или rollback.**
  Сервер сохраняет текущий intent и безопасную диагностику. При потере SSH result
  оператор сначала получает `status` и подтверждает фактическое состояние; worker
  мог завершиться после обрыва. Серверные `UNKNOWN`/`APPLYING` остаются закрытыми
  для всех mutations; отдельное решение оператора необходимо для reconciliation.
- Rollback разрешён только из известного состояния на сохранённый доступный
  проверенный предыдущий image; после него снова health и ручной smoke:

```bash
bash scripts/deploy-staging.sh rollback --target <target> --owner-approved
bash scripts/deploy-staging.sh accept --target <target> --smoke-passed
```

Rollback также сохраняет image/revision в `.env`. Он не откатывает данные, поэтому
записи candidate должны быть совместимы с предыдущим приложением. DB restore —
отдельное решение с границей потери данных. Один `.staging-release/state.json` и
`deployment.lock` хранят текущую операцию; исторические run724/f782 не изменяются.

## Локальная проверка без VPS

```bash
python3 -B -m unittest discover -s scripts -p test_staging_release.py -v
# Только одноразовые локальные hts02 Compose проекты/volumes/images; нужен Docker.
HT_STAGING_DOCKER_FIXTURE=1 python3 -B -m unittest discover -s scripts \
  -p test_staging_release_docker.py -v
```

Docker fixture проверяет настоящий subprocess → Docker/Compose → PostgreSQL/HTTP/TLS
путь, сохранение выбора при свежем Compose invocation, readiness failure, rollback
и потерю результата реальной Docker-операции. Он не проверяет VPS, SSH transport,
приложение или реальный CI provenance; artifact records и HTTP приложение синтетические.
На macOS можно явно задать `HT_STAGING_FIXTURE_DOCKER_SOCKET=unix:///…/docker.sock`.
Offline `plan`/`simulate --fixture … --state-dir …` остаются доступны с вымышленными
observations; `SIMULATED_ACCEPTED` не является operational evidence.
