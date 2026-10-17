# CLEAN_REINSTALL V126 — CR-1, локальная реализация и граница допуска

Статус HT36: **BLOCKED_EXTERNAL_AUTHORITY / DISPOSABLE_LOCAL RUNTIME / NO SERVER AUTHORITY**.
Проект HT-V126-CLEAN-REINSTALL-IMPLEMENT-32 сохранён; четыре принципа CR-1 и
совместимые последующие forward migrations утверждены владельцем в
HT-V126-CR1-ORDINARY-RELEASE-IMPLEMENT-33. Проверенный application source
`7fc3ed0b7de556363bf2b72569b0c2dd98cfb81e`.
Это единый источник нового lifecycle. Он не открывает исторические
[cutover](V126_STAGING_CUTOVER_CONTRACT.md),
[legacy admission](V126_REPAIR_OPERATION_PROTOCOL.md#approved-one-time-legacy-target-binding),
[prospective epoch](V126_PROSPECTIVE_AUTHORITY_EPOCH.md),
[ordinary release](STAGING_ORDINARY_RELEASE.md) или Policy B.
Локальные переходы реализованы в существующем `staging_release.Controller`.
Серверный worker остаётся V125-only: production authority/runtime adapters и
их enrollment не реализованы. Ни JSON, ни CLI flag не включают CR-1 на VPS.

## Принятый продуктовый объём и точная граница остановки

Владелец подтвердил отсутствие реальных клиентов, допустимость простоя и отказ
от переноса старых тестовых заказов/броней. Используется существующий VPS;
второй постоянный VPS не планируется. Код приложения не переписывается.
Это не разрешение на серверные действия, изменение секретов, удаление всех
backup/operation records или снижение recovery требований будущего продукта.

HT31 доказал только технический bootstrap пустой PostgreSQL 17.8: V1–V126,
126 successful / 0 failed, startup/restart, ограниченные API и browser smoke.
Проверенный backend image:
`sha256:5716515f536a7cb7733808819e70094081f95fb92ff8def3f5697e7b4a0972e3`;
docker-save SHA-256:
`5320517520f8fa51bcc358c13c65192f4cb3102e6019219f8a6d0fece8363ea7`.
Это amd64 backend под эмуляцией и ARM64 PostgreSQL, не native VPS/Telegram/DR proof.
Артефакты HT31: `/private/tmp/HT-V126-CLEAN-STAGING-BOOTSTRAP-31/`;
перед операционным использованием их нужно сохранить в утверждённой custody.

Исторические потребители остаются закрытыми; это не пути первоначального
принятия новой установки. HT33 реализует отдельный typed origin:

| Действующий потребитель | Почему нельзя просто подключить installer |
| --- | --- |
| `v126-legacy-genesis.py`: `POSTSTATE`, `REQUEST` | Poststate требует runtime/image и database version 125/126; request связан с next INIT. EMPTY/retired host и самостоятельной install authority нет. `LEGACY_UNKNOWN_FENCED` доказывает отдельную admission basis, не выполнение старой операции. |
| `v126-authority-epoch.py`: `SCOPES`, `BASE_STATE` | Bootstrap scopes только isolated genesis/copy; base state не доказывает отсутствие БД. Shared old controls/business account не становятся отдельным domain после reinstall. |
| `v125_lifecycle_validator.py`: `inspect_initial_install` | Всегда `PROPOSAL_ONLY`, `INDEPENDENT_INITIAL_INSTALL_AUTHORITY_UNAVAILABLE`; только V1–V125 и declared PG17.10/amd64. Это не acquisition/transport/executor. |
| `v126-policy-b-authority.py` и V126 INIT | Полная authority требует реальный R0 и принятую lineage/baseline. У отсутствующей БД нет R0; существующий stage7/Q нельзя выдумать. |
| `staging_release.py`: `validate_scope`, `validate_image`, `worker` | V126 отвергается до initialize/deploy. `owner_approved`/handoff boolean не являются передачей полномочий. |

В HT32 эти различия требовали решения CR-1. В HT33 одобрение позволяет
реализовать локальное sequencing/policy ядро, но не выбирать от имени владельца
нового независимого verifier, custodian, provider API или trust root.

## Утверждённое решение CR-1 и оставшееся решение об enrollment

Следующие четыре положения утверждены как один ограниченный контракт.
Одобрена локальная реализация/тесты, но не VPS execution.

1. **Successor на том же VPS после доказанного прекращения старых полномочий.**
   Не объявлять его `PROSPECTIVE_ISOLATED_GENESIS`. Разрешить сохранение конкретно
   перечисленных provider ownership/account, IP и DNS имени как заново проверенных
   внешних зависимостей; все execution capabilities проверяются отдельно. Нужна
   независимая связь old resource → retirement/reset receipt → new incarnation.
   Старые run/intent IDs и UNKNOWN остаются неизменной аудиторской ancestry.
   Сохранение bot account допускается только отдельным attachment после проверки
   отзыва старых полномочий и pending effects; до этого он disconnected.
2. **Ограниченная первоначальная authority без фиктивного R0.** Ввести отдельное
   точное действие создания БД и bootstrap EMPTY→V126. Основание — independently
   acquired EMPTY/reset/fence, owner data-loss scope, проверенные image/config/tool
   bytes и одноразовый intent. Оно не даёт public/Telegram access или обычных deploy.
   Это новый вид полномочия, а не исключение внутри существующего INIT/R0 gate.
   После установки закрытой БД — настоящие backup/restore/custody и ongoing readiness
   до connected test traffic. Требования Policy B к R0/Q старого cutover неизменны.
3. **Typed initial acceptance в существующий ordinary controller.** Новый
   `installation_origin=CLEAN_REINSTALL_V126` должен проверяться по защищённой
   независимо приобретённой цепочке install result → acceptance → registration,
   а не по полю из request. Legacy/cutover registrations и их отказы не меняются.
   V126 ordinary dispatch реализуется в существующем controller под его lock;
   не появляется второй release entrypoint и не открывается старый transfer path.
4. **Явная привязка приложения и новых инструментов.** Одобрение связывает пару
   `(application SHA/image/archive/migration bytes, installer/controller SHA/tree/
   tool closure/runtime)` и их совместимость. Старый exact image не содержит новые
   инструменты и не становится image нового source. Новые tooling bytes требуют
   собственного review/CI и enrollment; старый release CI их не квалифицирует.

Должны быть названы независимый verifier/custodian, защищённые источники,
acquisition method и пределы доверия provider/admin. Нельзя сделать исполнителя
его собственным единственным witness либо принимать подпись, впервые пришедшую
из того же request. Если эта схема или доступные provider доказательства
неприемлемы, результат остаётся `DECISION_REQUIRED`, без fallback на очистку Docker.

## Может ли тот же VPS удовлетворить контракту

### HT36: shared-host scoped replacement — локальная реализация утверждена

Read-only inventory 2026-10-16: AEZA `afraid-amber.ptr.network`, пять Compose projects:
`hookah-bot`, `hookah-production`, `markettap-stg`, `clubs-bot-stage`, `ai-bot-stage`.
Текущая задача ограничена **только** `/opt/hookah-bot` / project `hookah-bot`.
`hookah-production` — отдельный защищённый target, несмотря на общее имя продукта.
Evidence и точные resource IDs: `/private/tmp/HT-V126-SHARED-VPS-HOOKAH-35/resource-plan.json`.

Кандидаты после отдельного approval: два staging container IDs (backend/postgres),
`hookah-bot_default`, `hookah-bot_pgdata`; bindings `127.0.0.1:8080/5432` принадлежат
им. Единственный staging route: `staging.hookahtootah.club → 127.0.0.1:8080`.
Другие проекты/volumes/networks/mounts/ports/routes запрещены к изменению, включая
`dev.hookahtootah.club → 127.0.0.1:18080` с неподтверждённым runtime consumer.
Общие OS/provider, Docker daemon/socket/image store, Caddy service/config/TLS,
SSH/root/firewall/DNS нельзя reset/down/prune/reload глобально по этому плану.
История `.v126-runs`, `.staging-release`, `.rollback`, backups и custody сохраняется;
рекурсивное удаление `/opt/hookah-bot` недопустимо.

**Утверждённое в HT36 локальное изменение:** новый typed origin
`SHARED_HOST_HOOKAH_SCOPED_REPLACEMENT` заменяет обязательную связь full-provider-reset →
new-host-incarnation на independently verified Hookah-authority retirement →
new project-resource incarnation **в явно сохранённом доверенном host domain**.
Provider/host/root/Docker/Caddy administrators должны быть отдельно перечисленными
доверенными продолжающимися зависимостями. Это не изоляция от них. Все старые Hookah
executors/keys/sessions/CI/jobs/DB/bot capabilities и delayed effects всё равно должны
быть прекращены либо доказанно исключены из successor; UNKNOWN остаётся историей.
Обнаружены два unrestricted root keys, отдельный restricted Hookah release key и
Docker group member `clubsdeploy`: их полномочия пересекают Compose boundaries.
Если старую automation с root/Docker authority нельзя исключить без воздействия на
соседей, scoped install остаётся BLOCKED; labels/новый volume/новый lock не заменяют fence.

`shared_host_scope.py` сохраняет pure selection/delta checks и `PROPOSAL_ONLY`:
это никогда не authority. Новый binding связывает **полный** пересчитанный plan
(inventory, protected resources/routes, successor proposal), host/engine, полный
список retained-admin identity/owner/capabilities и runtime-resource guard.
Старый HT35 plan сохраняется как исторический снимок; свежая проверка обязательна
перед operational action. Изменение inventory не разрешает автоматический rebaseline. Локальная модель
фиксирует protected baseline на весь lifecycle: даже законное обновление соседа
потребует отдельно проверенной reconciliation/enrollment; такого live пути здесь нет.

Signed verifier evidence для каждого действия связывает этот binding, installation,
project-resource incarnation, complete inventory, owner acceptance конкретных admins,
`OLD_HOOKAH_RETIRED` (executors/writers/delayed effects отсутствуют, inherited authority
пустая, run724 остаётся UNKNOWN), затем `NEW_HOOKAH_RESOURCES_VERIFIED` со ссылкой
на retirement receipt, exact DB identity и guard. До BOOTSTRAP это проверка
зарезервированного mapping и отсутствия ресурсов (`system_id=UNCREATED`), а не
утверждение, что БД уже создана. Фактическое создание подтверждается только poststate/finish. Custodian подтверждает отдельный
readback. Provider role подтверждает **сохранённый host/control и pending actions**,
а не выдуманный reset. Для старого `CLEAN_REINSTALL_V126` reset-проверки неизменны.
Retained admin trust — отдельное решение по проверенным владельцам/доступам;
разрешение локального проектирования его не подменяет.

В local Docker adapter этот binding отображается только на случайные disposable
ресурсы `ht-cr1-local-*`; production Compose provisioning/retirement/ingress adapters
отсутствуют. Guard сравнивает весь protected daemon inventory, включая unlabelled
objects, перед каждой create/run/stop/rename и после операции. Exact names/ownership,
mounts, networks, ports и stopped retained backends проверяются отдельно. Соседние
Env не экспортируются; в evidence попадает общий digest. Docker/root administrator
может обойти эти наблюдения: local guard не является fence или защитой от root.
Изменения Caddy проверяет pure full-config delta helper, но runtime не пишет/reload
Caddy, systemd, SSH или Docker daemon. Его `PUBLIC` остаётся локальным app profile
на internal network без опубликованных портов и без реального Telegram.

Целевая последовательность: **OLD_HOOKAH_RETIRED → NEW_HOOKAH_RESOURCES_VERIFIED →
EMPTY_V126_BOOTSTRAP → ACCEPTANCE → CONTROLLED_TRAFFIC**. Ни один этап не переписывает
исторический UNKNOWN. Один и тот же `deploy-staging.sh` / controller обслуживает
install, admission, ordinary app releases и совместимые forward migrations.

Физический/коммерческий VPS сам по себе не определяет границу эффекта. На нём можно
создать пустой runtime, что доказано HT31 на локальной модели. Но текущая policy
не даёт автоматически назвать shared provider/account/DNS/bot отдельным domain.
В рамках утверждённого CR-1 тот же VPS **условно пригоден** как successor:
все старые capabilities прекращены или надёжно исключены из нового target,
все потенциально отложенные эффекты покрыты, и независимый verifier принимает
полную связь старого и нового состояния. Фактической пригодности VPS пока не доказано.

| Область | Независимое доказательство и требуемый результат |
| --- | --- |
| Provider/kernel | Для full-reset origin: resource/incarnation IDs, audit/reset job ID и terminal completion; проверенный новый boot/runtime. Для scoped origin: retained host/resource identity и проверенные продолжающиеся admin dependencies без требования reset. Инвентарь admin/API/rescue/console/automation полномочий, pending actions и отменённых/завершённых requests. Reinstall receipt без этих связей недостаточен. |
| Disks/storage | Полный перечень attached/detached disks, snapshots, mounts и restore sources; новый system/data volume или доказанно завершённый reset конкретных ресурсов. Старые snapshots/archives сохраняются согласно retention, но автоматический attach/restore в successor исключён. |
| SSH/Docker/jobs | Отзыв old keys/certs/tokens/sessions, прекращение SSH masters и descendants, Docker requests/daemon, cron/systemd/CI/manual/backup-recovery writers. Независимое подтверждение policy и deny probes старых capabilities; новый lock не исключает старого root сам по себе. |
| Database | Новые credentials, cluster system identifier, DB/role/volume/network binding; нет old connections, replication/subscriptions, foreign endpoints, extensions/jobs или shared data. До backend отсутствует application schema; один `public tables=0` не доказывает всю эту границу. |
| Telegram/API | Полный inventory pollers, webhooks, outbound/inbound queues и внешних jobs. Отзыв old token/authority, disposition уже отправленных requests и pending updates; отсутствие двух pollers. Delivered messages не отзываются восстановлением БД. Drop pending updates требует отдельного scope. |
| DNS/ingress | Provider/DNS/reverse-proxy admin inventory и исключение старых automation writers; A/AAAA/routes/listeners/TLS соответствуют successor. Тот же DNS/IP — адрес, не isolation proof. До admission product ingress закрыт. |
| Authority/custody | Независимые reader/verifier/custodian identity и retained high-water/revocations, полная predecessor inventory и readback. Старый receipt нельзя переиздать новым timestamp, убрать из history или использовать как разрешение новой операции. |

Provider наблюдает только свою control plane; он не может подтвердить отсутствие
всех сторонних Telegram/CI/SSH writers. Нужны независимые наблюдения их владельцев
и проверка полного покрытия verifier. Неизвестная capability, недоступный source,
неполный список, stale evidence, необъяснённый pending effect — отказ до bootstrap.
Доказательство нового host не является аттестацией против враждебного provider/root;
эти доверительные предпосылки должны быть явно приняты в CR-1.

## Предлагаемый workflow и fail-closed семантика

Таблица задаёт полный operational lifecycle. Реализованное локальное ядро
описано ниже; ни одной live-команды CLEAN_REINSTALL сейчас нет.

| Переход | Проверяемый вход и разрешённый эффект |
| --- | --- |
| `HISTORY_RETAINED` | Complete predecessor inventory, неизменные primary records/missing-record declarations, hashes и независимый off-VPS readback. Старый UNKNOWN не меняется. |
| `OLD_EFFECTS_FENCED` | Свежая полная cessation/exclusion evidence всех indexed effects; exact owner authorization. Revocation без подтверждения уже dispatched effects недостаточна. |
| `RESET_OBSERVED` (только full-reset origin) | Отдельно разрешённый provider reset завершён; новый incarnation связан с прежним; повторно проверены external capabilities и storage. Это post-action evidence, его нельзя потребовать как уже существующее до reset. |
| `OLD_HOOKAH_RETIRED` → `NEW_HOOKAH_RESOURCES_VERIFIED` (scoped origin) | Независимо подтверждены прекращение старых Hookah effects, retained-admin trust и свежие новые ресурсы; shared host не считается новым изолированным доменом. |
| `EMPTY_ADMITTED` | Новая среда/namespace/resources и отсутствие DB/application data подтверждены независимо. Новый one-shot action привязан к incarnation, resource IDs, ancestry, конфигурации, exact app/tool bytes, сроку и nonce. |
| `INSTALL_INTENT` → `CLOSED_V126` | Durable intent до первой provisioning/Docker/SQL мутации; только новые названные ресурсы. Backend сам выполняет V1–V126; проверяются actual embedded SQL, Flyway checksums/history и schema objects. Все product ingress/egress закрыты, bot отключён. |
| `RECOVERY_QUALIFIED` | Реальная точка новой БД, полный restore в изолированной среде, custody и ongoing Policy B. Старый dump не источник новых бизнес-данных и не recovery point новой БД. |
| `TEST_ADMITTED` | Exact approval на тестовые identities/действия; PRODUCT + проверенный V126_SMOKE, closed ordinary admission, ограниченные sender/worker paths. Никакого возврата к старому ALLOWLIST profile. |
| `RELEASE_ACCEPTED` | Ручной продуктовый/RBAC/V126 smoke, независимый current poststate, backup readiness и typed initial acceptance. Под тем же writer lock создаётся единственная ordinary registration в `AWAITING_PUBLIC_HANDOFF`: ordinary dispatch ещё запрещён. |
| `PUBLIC_ADMITTED` | Отдельное разрешение открытия PRODUCT/OFF после acceptance; actual ingress/egress poststate и durable receipt. Установка restart policy — отдельный связанный handoff, не побочный эффект health=200. Только завершённая цепочка final-config/handoff допускает ordinary dispatch. |

Exact effect authorization должна быть получена до каждого изменяющего шага,
а не только перед INSTALL_INTENT. Отдельные действия с provider/дисками/секретами
не выполняются обычным release controller. Их receipts он потребляет через
защищённые enrolled readers; request не выбирает reader или свой evidence root.

Один permanent writer lock с проверкой inode/ownership/namespace покрывает install,
acceptance, ordinary и recovery consumers нового target. Полная транзакционная
inventory и её независимый high-water проверяются до любой записи. В новом
incarnation старые writers блокируются внешним fence, а не этим новым inode.
Не импортируются old active registry/locks; их immutable audit-копии обязательны
и остаются в lineage. Нельзя удалить мешающий старый registry и продолжить на месте.

`REFUSED_BEFORE_EFFECT` допустим только при доказанном отсутствии mutation/dispatch.
После durable intent timeout, crash, lost ACK, partial result, неизвестный daemon
outcome или недоступный poststate означают `UNKNOWN`; lock release не меняет verdict.
`FAILED_KNOWN` требует independently observed exact poststate и bounds effects;
неполная Flyway миграция сама по себе не разрешает restart/repair/retry.
Нет auto-retry, auto-adopt, auto-reset, auto-rollback или автоматической очистки
частичного target. Reconciliation — отдельно разрешённое наблюдение/решение,
оно сохраняет исходный результат и не фабрикует его completion.

Повторный install request с consumed action отказывает (`INSTALLATION_ALREADY_EXISTS`
либо reconciliation/history mismatch); **ноль** create/run/migrate/delete. Сохранённый
result остаётся evidence, не новым исполнением. Штатный повторный старт
уже принятого exact image — другое разрешённое недеструктивное действие: тот же
DB identity, прежние 126 checksums и отсутствие новой миграции. Новый nonce не
разрешает destructive bootstrap поверх уже зарегистрированного incarnation.

## Реализованное локальное ядро и непоставленные adapters

`Controller.clean_installation(scope, store, runtime, authority)` создаёт новый
typed consumer; `Store`/`deployment.lock`, `deploy`, `accept`, `rollback` остаются
общими с ordinary controller. `clean_install_policy.py` содержит только policy и
абстрактный `Authority`. HT34 подключает `clean_install_runtime.py` и
`clean_install_trust.py` к тому же shell/Python entrypoint только в `DISPOSABLE_LOCAL`;
отдельного sequencer или второго release framework нет.
Import policy ленивый: прежний standalone V125 worker сохраняет свой tooling closure.

Локальная последовательность API: `clean_action("BOOTSTRAP", candidate=...)` →
`CLOSED_V126`; `TEST` с exact configuration → `TEST_ADMITTED`; `accept` с ручным
smoke и независимым receipt → `AWAITING_PUBLIC_HANDOFF`; отдельный `PUBLIC` с
exact configuration/receipt → `ACCEPTED`. Первый bootstrap ограничен 126 миграциями;
повтор, непустая schema, старый registry, concurrent lock, UNKNOWN и удаление
local state при retained independent head блокируют dispatch.

Каждый запрос связывает installation/incarnation, immutable ancestry, database
identity, before-state, app/source/archive/manifest/expected DDL и tooling
source/tree/closure/runtime. Authority должна независимо получить owner/CI/fence,
семь facets cessation, отсутствие external writers и pending effects; после
bootstrap обязательна текущая recovery readiness. Request не назначает reader/key.
`begin` резервирует **физический target/incarnation и DB/volume во всех installation
IDs**, через durable compare-and-swap до local intent и runtime dispatch.
`finish` получает полный sanitized terminal state, независимо проверяет poststate,
сохраняет evidence вне target и возвращает подтверждение; controller перечитывает
независимый head. Потеря любого ACK не допускает автоматического повторения.

Runtime обязан проверить actual immutable archive/image/embedded SQL/tool bytes,
canonical DDL fingerprint (не только max Flyway version), все application objects,
DB binding и writer inventory; исключить writers перед миграцией; ограничить время
каждого вызова. `clean_ready` обязан проверить startup, health/db-health/version,
API и assets для конкретного image. `False` допустим только при доказанном known
poststate; отсутствие наблюдения/transport timeout — exception → UNKNOWN.

**Граница поставки HT34:** локальный installer создаёт собственные internal Docker
network/volume/PostgreSQL, проверяет пустоту до запуска exact backend и выполняет
обычные обновления через тот же controller. Production implementations отсутствуют:
provider reset/fence acquisition, protected real enrollment, off-target CAS/custody
service, approved server Docker/ingress/credential provisioning и live registration.
`registered_action` и `initialize` по-прежнему V125-only. Production profile отвергается
до обращения к Docker/SSH. Локальная registration не служит разрешением работы с VPS.
`SyntheticAuthority` и issuer с private keys находятся только в tests.

Требования к последующему подключению после конкретного enrollment решения:

- Сохранить `deploy-staging.sh` / `staging_release.py` единственным ordinary path;
  расширить versioned registration/initial acceptance и существующие `Store`,
  `Controller`, `CommandRuntime`, bounded runner только для нового typed origin.
- Переиспользовать strict archive verification из `docker-save-archive.py`, actual
  embedded migration comparison, Docker inspect/Compose/DB identity checks, existing
  protected-source/evidence/transport patterns. Не превращать untrusted fixtures,
  JSON `approved=true` или V125 `initialize` в новый trust root.
- До dispatch закрепить одни immutable archive bytes: проверка и load должны видеть
  тот же защищённый snapshot/FD. Проверка пути с последующим повторным открытием
  не исключает замену архива. Проверять actual loaded ID/platform и source bytes.
- Install adapter создаёт только заранее перечисленные новые resources, без delete,
  prune, restore, seed-staging, Flyway repair/baseline/target override, build/pull.
  На общем VPS OS reset запрещён; старые Hookah capabilities выводятся отдельно по scoped retirement plan.
- Не менять старые schema/enum/receipts и guard predicates in place. Новая версия
  acquisition/enrollment/transport с точными action scopes и independently selected
  facts требует review до подключения к effectful runtime. Новые compatibility
  tests должны доказывать, что legacy/V125/V126 routes остаются закрыты как прежде.

## Последующие ordinary V126 releases

Initial acceptance связывает incarnation/installation result/ancestry, app SHA,
immutable image/tag/archive, runtime platform, V1–V126 byte manifest + фактическую
Flyway history, PostgreSQL system ID/DB/role, volumes/networks, effective config,
controller source/runtime и текущую recovery/custody authority. Секретные значения,
initData и per-secret plaintext hashes в публичные manifests не включаются.

Initial acceptance фиксирует закрытую SMOKE-конфигурацию. До ordinary dispatch
нужна отдельно авторизованная exact before/after цепочка переключения PRODUCT/OFF,
ingress/egress и restart policy: durable intent до мутации, один writer lock,
независимое подтверждение final config/runtime и durable completion/readback.
Только затем защищённый current-registration pointer ссылается на final config и
полную принятую цепочку. Это не автоматический rebaseline по наблюдению.
Known failure или partial/UNKNOWN public opening не оставляет registration с правом
ordinary dispatch; незавершённый intent нельзя повторить с новым nonce. Сам факт
уже открытого трафика не даёт completion: неизвестное открытие требует отдельно
разрешённого response/fence, без автоматического retry/reopen.

Под тем же lock новый dispatch требует `ACCEPTED`, independently authorized точную
пару expected-current/candidate, exact green candidate CI, verified artifact/image
и точную историю уже применённых миграций. Target/config/DB drift, pending/UNKNOWN
транзакция нового incarnation или старые capabilities/effects без принятой полной
cessation/exclusion цепочки блокируют действие до мутации. Исторический run724=UNKNOWN
с такой принятой цепочкой и текущим подтверждённым исключением воздействия остаётся
аудиторским фактом; сам этот verdict не является вечным veto нового ordinary path.
Он не получает новый SUCCESS и не разрешает retry старой операции. Candidate заранее доставлен
отдельно разрешённым шагом; controller не делает build/pull.

| Обновление | Условие и поведение локального ядра |
| --- | --- |
| Backend/Mini App без SQL | Exact immutable image, прежний полный manifest и DDL fingerprint; БД не пересоздаётся. |
| Совместимый forward | Только append-only версии после принятого head, старые SHA-256/Flyway checksums неизменны; independently reviewed `COMPATIBLE_FORWARD` receipt связывает точный plan и ожидаемый DDL, CI, backup/recovery и owner approval. Номер V127 сам по себе не даёт разрешения. |
| Несовместимый/destructive | Нет compatible receipt — отказ до intent. SQL keyword scanner не является доказательством совместимости. Нужны отдельный expand/backfill/contract plan, compatibility review и approval; автоматического DROP/data-loss пути нет. |
| FAILED/UNKNOWN | Сохранение intent/history; никакого повторного deploy/migrate/repair/accept. Reconciliation остаётся отдельным решением; API не переписывает результат. |
| Code rollback | Только неизменные manifest **и** DDL fingerprint плюс independent `CODE_ONLY_SAME_SCHEMA` review. Rollback к образу со старым manifest после forward запрещён; forward fix либо отдельно утверждённый restore. Более широкий mixed-schema rollback не реализован. |

После durable intent — только backend recreate `--no-build --pull never --no-deps`,
проверка exact runtime/health/version/DB/schema, `READY_FOR_SMOKE`, ручной smoke,
затем явное `ACCEPTED`. Health не передаёт image authority. Известный readiness
failure сохраняет known poststate; потеря наблюдаемости — UNKNOWN. Recovery требует
отдельной authority; только доказанно совместимый принятый V126 image либо проверенный
DB restore с явно принятым data-loss boundary. V125 поверх V126 запрещён всегда.
Старый закрытый `V126_ORDINARY_AUTHORITY_TRANSFER_REQUIRED` для иных origins сохраняется.

Переходы initial acceptance и subsequent ordinary проверяются локально. Реальный
путь **не готов** до enrollment/runtime adapters, их review/CI и отдельного
разрешения. `v126-ordinary-deploy.py` не получает новых возможностей. Повседневный
целевой интерфейс сохраняет `deploy` → автоматические проверки → `READY_FOR_SMOKE`
→ ручной продуктовый smoke → `accept`. HT34 исполняет этот путь через тот же shell CLI
в disposable local профиле; real enrollment не подключён.

## HT34: исполняемый local installer и trust boundaries

| Boundary | Локальная реализация | Что необходимо для VPS |
| --- | --- | --- |
| Owner | Подписан exact request, scope, epoch, expiry; CLI flag только подтверждает намерение | Enrolled identity владельца, отдельные loss/reset/install/admission и release approvals |
| Provider | Отдельный fixture key, incarnation/reset/pending assertions | Названный provider/VM/disks, авторитетный events/readback API и read-only verifier credentials; проверить delayed control-plane actions |
| Verifier | Отдельный key; request-bound facets/image/CI/recovery/smoke facts; второй observer | Внешний процесс с отдельными ограниченными правами, закреплённые acquisition methods по всем facets; не VPS self-report |
| Custodian | Четвёртый canonical public key, signed revocations, outside-target filesystem CAS/readback | Отдельная административная authority и защищённый versioned/CAS store, retention, независимый readback, anti-replay/high-water |
| Evidence store | O_EXCL immutable event objects, fsync pointer, hash chain, orphan detection, sticky observed revocation | Защита также от target/root/operator rollback/delete, привязка reservation к физическому target во всех installation IDs |
| Writers/effects | Internal network, exact container execution config, DB sessions, stopped old backend, deployment lock | Provider/executor/SSH/DB/bot/webhook/jobs/restore inventory и независимо проверенный retirement; fence pending effects, не только текущих процессов |
| Source/image/tooling | Archive verifier + loaded ID/revision/platform + embedded SQL/CRC + separate tool closure/runtime hashes | Exact application CI/source/image и отдельно reviewed/CI tooling source/tree/closure; protected enrollment вне target |

Все четыре local private keys создаёт **только test issuer** вне target. Worker читает
публичные ключи и signed evidence. Разные ключи и второй observer в одном UID/Docker
не создают независимых административных доменов. Local journal не WORM и не cloud
service: тот же host/root может переписать весь harness. Это явная граница теста,
не security exception для VPS. Yandex Object Storage остаётся кандидатом; bucket,
versioning/Object Lock/CAS, principals и readback не подтверждены и здесь не создаются.

CLI (только enrollment `DISPOSABLE_LOCAL`, private directory
`/private/tmp/ht-cr1-local-<12 hex>`; на Linux допускается `/tmp`):

```bash
scripts/deploy-staging.sh preflight --operation install --local-registration "$REG" --candidate "$IMAGE"
# Внешний issuer подписывает возвращённый exact request; production issuer отсутствует.
scripts/deploy-staging.sh install --local-registration "$REG" --candidate "$IMAGE" --owner-approved
scripts/deploy-staging.sh test-admit --local-registration "$REG" --configuration "$SMOKE_CONFIG_HASH" --owner-approved
scripts/deploy-staging.sh accept --local-registration "$REG" --smoke-passed
scripts/deploy-staging.sh public-admit --local-registration "$REG" --configuration "$PUBLIC_CONFIG_HASH" --owner-approved
scripts/deploy-staging.sh preflight --operation deploy --local-registration "$REG" --candidate "$NEXT_IMAGE" --expected-current "$CURRENT_ID"
scripts/deploy-staging.sh deploy --local-registration "$REG" --candidate "$NEXT_IMAGE" --expected-current "$CURRENT_ID" --owner-approved
scripts/deploy-staging.sh accept --local-registration "$REG" --smoke-passed
scripts/deploy-staging.sh status --local-registration "$REG"
```

`preflight --operation <action>` подготавливает точный request для каждого действия;
свежий bundle должен соответствовать ему. Это transport interface для ещё не enrolled
issuer, а не требование вручную сочинять JSON/receipts при каждом будущем релизе.
Production integration должна автоматически получать proofs в обычном workflow
после одного точного owner approval. Нельзя использовать test issuer operationally.

Tool source/tree в local enrollment — заявленные fixture identities; фактически
проверяются отдельные closure/runtime bytes. Dirty tool closure не становится
CI-approved source tree. Для VPS нужен отдельно опубликованный и проверенный
source-bound tooling artifact, а не доверие к этим полям JSON.

Installer требует preloaded images и проверяет архив перед dispatch; сам не build,
pull или load. PostgreSQL 17.8 и probe image закреплены отдельными digest. Env fixture,
bot выключен, нет host ports, network internal. Даже `PUBLIC` меняет лишь application
maintenance profile в этой закрытой сети; реальный public ingress/Telegram не открывает.
Backend/Mini App обновляются вместе. PostgreSQL **schema migrations** поддерживаются;
обновление major версии самого PostgreSQL Engine/pg_upgrade не реализовано и требует
отдельного data-preserving recovery/compatibility плана.

Перед effect — CAS intent и локальная запись APPLYING; после — точный DB system ID,
image/config/history/DDL, health/db-health/version/API denial/Mini App HTML, независимый
local observer и terminal readback. Known unhealthy HTTP может дать FAILED; отсутствие
наблюдения/timeout, lost ack, partial provisioning, drift или потеря custody дают
UNKNOWN/закрытый dispatch. Нет repair/reset/retry/bootstrap recovery command. Старые
backend containers остаются stopped с уникальными retained names; не перезапускаются.
Незавершённая операция требует отдельного reconciliation, а не удаления state/volume.

Создание volume проверяет labels/driver/options и пустоту mount до PG, затем отсутствие
user objects до backend. Concurrent **администратор Docker daemon** находится вне
доверительной модели local harness: labels/lock не отзывают его права. Production
adapter должен независимо исключить такую authority; проверка списка containers не
доказывает прекращение внешних writers или отложенных provider effects.

Docker regression запускает exact V126 app, затем два явно fixture-derived immutable
images (app-only и SQL V127). Их labels наследуют base source, но overlay не является
CI-approved publishable release; это вход синтетического local enrollment. DDL digest
квалифицируется отдельной disposable PG. Его pg_dump не включает owner/ACL/globals;
production verifier обязан отдельно доказать эти права и backup/restore readiness.
Health/HTML/API denial здесь не заменяют browser/product/tenant/real-Telegram smoke.

## Первый operational plan: только Hookah staging на общем VPS

1. До удаления сохранить вне VPS exact image/source/CI/migration manifest, все
   historical operation records и missing-record declarations, scope inventory,
   provider/authority events и readback. Зафиксировать отдельно retention старых
   backups и секретную recovery custody. Отказ от заказов/броней не отменяет retention
   operation evidence и не разрешает удаление backup objects.
2. Перепроверить HT35 inventory и exact ownership перед исполнением. Кандидаты
   только: backend `7c805762c5fed07133239d7ad695cd8a9aa70572296bc72aab63f169f1677dd1`,
   PostgreSQL `dcc668e340b863c927c8d1c661a890e24235ef7e388f4c0c880f0079367d50e5`,
   сеть `hookah-bot_default` / `b2e61336e072326c8479aa2367e07a69b372617a5e7f374c992c73ceb463e5c6`,
   volume `hookah-bot_pgdata`, root `/opt/hookah-bot`, loopback 8080/5432 и единственный
   route `staging.hookahtootah.club → 127.0.0.1:8080`. Старый volume/каталог/evidence
   сохраняются, автоматического удаления нет. Новые proposed resources:
   `hookah-staging-v126`, `/opt/hookah-bot-v126`, `hookah-staging-v126_default`,
   `hookah-staging-v126_pgdata`; отдельные DB credentials, без публикации PG порта.
   Ни один другой проект, image store или общий host/service не выбирается.
   До retirement approval нужны custody readback, полное описание old Hookah
   CI/deploy/SSH/session/DB/bot/job/restore capabilities и независимый план их fence.
3. Владелец отдельно утверждает полный список потерь: кроме тестовых orders/bookings,
   чистая БД удаляет users/venues/memberships, меню, часы, table tokens/QR/invites,
   chats/support, audit/outbox/session data. Если эти данные нужны, разрешить отдельный
   bounded экспорт/ручное повторное создание; не восстанавливать старую БД целиком.
   Нельзя молча расширить уже принятое решение об orders/bookings на все эти данные.
4. Enroll owner, внешний verifier с ограниченным read-only acquisition, независимый
   custodian и защищённый off-target versioned/CAS store. Подтвердить AEZA resource/control
   источник: конкретный API/audit export/reader пока не установлен. Отдельно установить
   владельцев двух unrestricted root keys, restricted release key, `clubsdeploy`,
   provider/rescue/console и CI principals; перечислить копии/сессии/делегированные права.
   Shared Hookah/MarketTap root credential нельзя глобально отзывать этим планом.
   Невозможность исключить старую automation при сохранении соседей означает BLOCKED.
   После отдельных approvals на downtime/fence и конкретные действия доказать
   прекращение старых executors и delayed effects; отсутствие контейнера недостаточно.
   Только затем допустить новую project-resource incarnation. OS reset не выполняется.
   Выбрать exact PostgreSQL version/platform/digest: HT31 PG17.8/ARM64 не заменяет
   native Linux/amd64 qualification. Реальные adapters требуют отдельного review/CI.
5. После enrollment, реализации/review server adapter и отдельного install approval
   установить проверенный tool closure; загрузить approved
   exact image, создать новые network/volume/PG/DB и closed backend через утверждённый
   executor. Ноль импортированной истории; проверить Flyway 126/126/0, V126 objects,
   DB binding, backend/API/assets и пустые application tables до входа пользователей.
   API origin exact image — `https://staging.hookahtootah.club`; runtime env его не
   переписывает. Другой origin требует нового build/approval.
6. Выполнить настоящие backup/restore/custody и ongoing qualification новой БД.
   Policy B сохраняет RPO ≤86400 s, snapshot 6h, qualification ≤2h, monitor 5min,
   retention 7d/минимум две qualified points, отдельную fence authority на 22h,
   без auto-restore/reopen. Новый install не получает фиктивный старый stage7/Q.
   Restore среда Linux/amd64 должна быть вне staging и не иметь внешних эффектов;
   второй постоянный VPS не обязателен, но тот же staging не заменяет эту среду.
   AP07/capacity/readiness prerequisites остаются обязательными.
7. Только после TEST_ADMITTED подключить отдельно разрешённый реальный bot/учётные
   данные. Подтвердить token revocation, webhook/long-polling mode, pending-update
   disposition, единственный poller и sender; не использовать token rotation как
   доказательство прекращения уже dispatched effects. Проверить PRODUCT/V126_SMOKE
   effective policy и denied ordinary admission до подключения Telegram.
8. Создать Platform Owner/venues/roles/menu/tables/QR через продукт. Выполнить
   Guest/Owner/Staff/Platform, tenant-negative, orders/session/tab/booking smoke,
   communication separation и 17 V126 Gate B assertions из cutover как продуктовый
   acceptance checklist. Новый receipt не является историческим cutover stage14.
   Exactly-one delivery и unread/no-resurrection требуют real Telegram evidence.
9. Принять первый release в **новый** ordinary origin без historical authority transfer, затем отдельно
   открыть PRODUCT/OFF ingress/egress и применить утверждённую restart policy.
   Сравнить protected neighbor snapshot до/после. Если требуется изменение upstream,
   отдельно разрешить только exact Hookah delta после full Caddy config comparison;
   глобальный reload не разрешён данным планом. Дальнейшие изменения идут по ordinary процессу выше; ни live install, ни public
   opening, ни recovery не разрешены одним approval документа CR-1.

## Проверки и точная граница evidence

Исполняемые локальные проверки:

```bash
python3 -B -m unittest discover -s scripts -p test_clean_install_release.py -v
python3 -B -m unittest discover -s scripts -p test_staging_release.py -v
python3 -B -m unittest discover -s scripts -p test_clean_install_runtime.py -v
python3 -B -m unittest discover -s scripts -p test_shared_host_scope.py -v
HT_CR1_RUNTIME_TEST=1 python3 -B -m unittest discover -s scripts -p test_clean_install_runtime.py -v
HT_CLEAN_PG_TEST=1 python3 -B -m unittest discover -s scripts -p test_clean_install_postgres.py -v
```

Обе opt-in Docker проверки требуют уже загруженные exact HT31 image и PostgreSQL 17.8 image,
Docker и local javac. Создаёт только labelled disposable containers/volume/internal
network, random credentials, без ports/Telegram/SSH. Проверяет actual embedded
V1–V126 SQL bytes, выполняет настоящий Flyway validate/migrate из release libraries,
проверяет повтор/no-op, synthetic V127, DDL identity, сохранение application row,
unsafe rollback и lost-ACK replay. V127 создаётся только в temporary fixture.
Ожидаемый DDL предварительно вычисляется в отдельной disposable qualification DB.
В `test_clean_install_postgres.py` image/traffic/CI/health/authority — **synthetic**;
его SQL proof не является runtime app-update или независимым fence/backup proof.
В `test_clean_install_runtime.py` actual image/backend/health проверяются Docker CLI,
но подписанные provider/CI/recovery facts остаются явными fixture assertions. Полный backend/API/
Mini App HT31 smoke переиспользуется только для неизменённого exact приложения.

HT36: **489 tests PASS** — 376 existing/core, 57 shared-host/controller и 56 runtime
(55 portable + один real Docker scenario, 207.727 s). Scoped CLI выполнил install,
test-admit, acceptance, local public profile, app-only и fixture V127 с сохранением
users row при четырёх защищённых соседних контейнерах с Compose project labels.
Проверки `/health`, `/db/health`, `/version`, Mini App HTML и unauthenticated API denial
прошли; browser/RBAC/real Telegram smoke этим не подтверждается. Два найденных P2
(command option aliases и bind storage aliases) исправлены и независимо перепроверены;
новых actionable findings в локальной модели не осталось. Полный отчёт и logs:
`/private/tmp/HT-V126-HOOKAH-SCOPED-INSTALL-36/REPORT.txt`.

HT34 final local validation: 401 tests PASS (376 existing/core regressions + 24
signed-runtime boundaries + 1 real Docker CLI scenario). Реальный сценарий прошёл
CLOSED_V126 → TEST_ADMITTED → AWAITING_PUBLIC_HANDOFF → ACCEPTED, app-only update и
append-only V127 с сохранением users row; unsafe rollback, busy lock, лишний writer/
network и public approval denial проверены. Disposable resources удалены после теста.
Итоговые logs/diff hashes: `/private/tmp/HT-V126-CR1-RUNTIME-AND-TRUST-34/REPORT.txt`.
Focused independent review: открытых findings в DISPOSABLE_LOCAL нет; reviewer
выполнил 24 portable tests, Docker не запускал. Это не CI/server certification.

Ниже — требования также к ещё не подключённым production adapters; их нельзя
целиком считать выполненными по локальным unit/SQL tests:

| Случай | Обязательное наблюдение |
| --- | --- |
| Настоящий fresh bootstrap | Только disposable ресурсы; реальная PG/V126/schema/image проверка; intent/result/readback и zero product egress. HT31 переиспользуется только для неизменного приложения, не нового executor. |
| Повтор install / штатный restart | Consumed operation не вызывает mutations; отдельно разрешённый same-image restart сохраняет все migration rows/checksums. |
| Старый/внешний writer | Полный controller inventory с одной capability/pending effect отвергается до первого intent/command; lock contention и попытка writer между checks проверяются. |
| Reset/fence не подтверждён | Missing/stale/forged provider, wrong incarnation, shared credential/control, неполная cessation scope и self-selected reader отвергаются с нулём эффектов. |
| Подмена image/source/schema | Другие archive bytes, tag/ID, app/tool pair, embedded SQL/checksum, PG/system ID или config отвергаются; отдельно race замены archive/lock/registration. |
| Partial/UNKNOWN | Fault injection до/после каждого durable boundary и Docker dispatch, lost ACK/timeout/reboot: исходный intent retained, UNKNOWN без повторного bootstrap/rollback. Public/handoff failure оставляет ordinary dispatch закрытым; смена nonce не повторяет opening. |
| Второй destructive bootstrap | Same/different nonce, удалённая локальная state-копия или replay старого acceptance не обходят independent inventory/high-water/incarnation binding. |
| Следующее обновление | Подлинная typed acceptance + completed final-config/handoff; unchanged-schema либо exact compatible forward candidate. Wrong current/authority/backup, новый UNKNOWN или uncovered old effect — ноль mutations. Retained old UNKNOWN с принятой cessation и current exclusion не отвергается только за исторический verdict. |
| Legacy regression | Existing V125 ordinary, closed V126 transfer, genesis scope, poststate и UNKNOWN semantics не изменены. Никакой acceptance через schema flag/boolean. |

Нужны focused non-author security review, portable adversarial tests и isolated Linux
execution/concurrency/persistence checks изменённого executor. Необходимые CI/release
gates остаются; тесты/ревью документа не дают operational certification.

**Следующее действие:** owner/security утверждает проверенный scoped enrollment manifest: конкретный независимый
verifier/custodian, защищённые acquisition sources/methods по семи facets и место
off-target durable CAS/evidence custody, с явно принятыми provider/admin assumptions.
Если провайдер/оператор не могут дать полную цепочку прекращения старых effects,
тот же VPS не допускается; очистка Docker/новая UUID/пустая БД не заменяют доказательства.
Это оставшееся решение, а не повторное согласование уже утверждённых четырёх принципов.
