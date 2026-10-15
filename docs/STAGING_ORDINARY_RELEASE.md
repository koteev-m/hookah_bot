# Обычный staging-релиз без миграций

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
