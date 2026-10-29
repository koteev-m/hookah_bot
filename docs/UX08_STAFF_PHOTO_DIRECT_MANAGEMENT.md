# UX08 — прямое управление фотографиями сотрудников

Задача: `HT-UX08-STAFF-PHOTO-DIRECT-MANAGEMENT-01`, 2026-10-29.
Проект: `Hookah_Tootah`, репозиторий `hookah_bot_ANT`.
Статус: **LOCAL_VERIFIED / READY_FOR_REVIEW / STAGING_ACCEPTANCE_OPEN**.

## Идентичность и границы

Read-only identity gate PASS до чтения исходников и изменений. Ожидаемые и фактические физические
cwd/root: `/Users/maksimmartynov/IdeaProjects/hookah_bot_ANT`; Git dir/common dir: тот же root +
`/.git`; index: `/.git/index`, принадлежащий единственному зарегистрированному checkout.
Origin fetch/push: `github.com/koteev-m/hookah_bot`. Начальный HEAD:
`0256e62b21862ecef0de72b6eea3081ba789f2c6`, ветка `codex/ht-ux08-staff-ui-consent-fix-01`.
EXPECTED_HEAD/EXPECTED_BASE/branch/parent constraints: `NOT_SUPPLIED`.
Источники инструкций: текущая пользовательская задача, переданный и локальный Hookah AGENTS.md;
глобальный `/Users/maksimmartynov/.codex/AGENTS.md` пуст. Других AGENTS на цепочке/в репозитории,
configured instruction overrides, Git includes, core.worktree/hooksPath, alternates, redirect,
namespace или identity-affecting GIT override не обнаружено. `GIT_PAGER=cat` не влияет на identity.

Начальный `git status --short`: только `?? scripts/dev/`. Работа выполнена в существующей ветке.
Разрешены только локальные код/тесты/документы и проверки. Commit/stage/push/PR/merge/SSH/deploy,
Cloudflare R2, staging/production, живые данные и секреты не изменялись. scripts/dev/ не затронут.

## Конфликт и утверждённая модель

Прежняя UX08-модель требовала приватного кандидата, SELF-согласия связанного сотрудника либо
OWNER_RECORDED для карточки без аккаунта и отдельного publish. Новое прямое требование владельца
продукта явно заменяет этот контракт. Исторический `UX08_STAFF_UI_CONSENT_FIX.md` описывает
проверки старой модели; они не являются основанием сохранять старые шаги.

| Область | До | После |
| --- | --- | --- |
| OWNER | Upload, согласие для display-only, отдельный publish/delete | Upload/replace/delete всех карточек своего заведения |
| MANAGER | Фото только управляемых display-only/STAFF-карточек; protected запрещены | Те же права на фото, что у OWNER, включая OWNER/MANAGER/STAFF linked |
| STAFF | Own preview, consent/revoke; legacy own photoRef edit | Только own preview; все мутации фото и legacy photoRef запрещены |
| Права на аккаунты/карточку | Существующие protected/RBAC/linkage правила | Без расширения; bio/tags self-edit сохраняется |
| Успешная загрузка | PENDING candidate, требуются consent + publish | Текущая APPROVED после PUT и атомарной транзакции, без согласия |

Photo RBAC отделён от general profile `canManage`. Фотография не меняет публикацию карточки,
настройки Guest, MANUAL/SCHEDULE или состав «Сегодня работают». Upload сохраняет существующий
server-side module-enabled guard; preview/delete доступны при выключенном модуле.

## Старые API и данные

- `/consent`, `/publish`, `/revoke` остаются явными маршрутами: после photo RBAC OWNER/MANAGER
  получают 410 `STAFF_PHOTO_ACTION_RETIRED` с предложением обновить Mini App; STAFF/чужой участник
  получают 403. Нет изменения revision, consent, audit или указателей. Отказ обоснован: no-op
  выглядел бы подтверждением согласия, а publish создавал бы путь активации старого кандидата.
- `/hide` сохранён как legacy одностороннее скрытие для OWNER/MANAGER с CAS; в новом UI его нет.
  Для повторного показа нужен новый upload. `/delete` остаётся основной операцией удаления.
- Старые активные current/APPROVED сохраняются при соблюдении прежних правил видимости и identity.
  PENDING/HIDDEN candidates и скрытые current не публикуются на чтении, обновлении или replay.
  Новая явная загрузка заменяет их с обычной очисткой. Старый кандидат можно приватно просмотреть
  или удалить; переносить его в current без новой загрузки нельзя.
- Новые consent_kind/actor/at/policy остаются NULL; исторические поля сохранены для совместимости.
  DTO `canConsent=false`, `consentPolicy=staff-photo-direct-v2`; APPROVED означает активное фото,
  а не фиктивное согласие или человеческую модерацию.
- Миграций схемы/данных, массового преобразования или необратимого обновления старых фото нет.
  PostgreSQL V127/H2 V128 и остальные исторические migration files не изменены.

## Атомарность и восстановление

Сохраняются приватное R2, нормализация JPEG/PNG, MIME/signature/size/EXIF ограничения, bounded
body/storage timeouts, quotas и безопасные DTO. До PUT резервируются immutable key, request UUID,
input hash и revision. После PUT финализация заново проверяет membership, identity, revision,
состояние записи и module gate под существующими locks. Одной транзакцией новый объект становится
current, старые current/candidate получают DELETE_PENDING, pointer candidate очищается и пишется
аудит STAFF_PHOTO_UPLOADED. Ошибка PUT или транзакции сохраняет прежние указатели/фото.

Повтор после потерянного HTTP-ответа использует тот же файл/key/revision и не делает второй PUT.
Повтор старого/удалённого запроса не воскрешает фото. Mini App не делает автоматических повторов
и не переносит выбранный файл на новую revision. Delete сразу очищает delivery pointers;
физическое удаление подтверждается только после provider DELETE, с прежними retry/tombstones.

## Проверки и доказательства

| Проверка | Результат | Доказательство |
| --- | --- | --- |
| Фото backend (`*StaffPhoto*`) | 62/62 PASS, 0 failed/errors/skipped; 29 на реальном PostgreSQL | `photo-backend-final.log`, `photo-results-final/summary.json` и XML |
| Staff/Invite/RBAC/Guest/Preview/membership regressions | 189/189 PASS, 0 failed/errors/skipped, включая PostgreSQL concurrency suites | `backend-regression.log`, `regression-results/summary.json` и XML |
| Kotlin compile/ktlint | PASS | `photo-backend-final.log` |
| TypeScript + Vite production build | PASS; прежнее предупреждение chunk >500 kB | `build-final.log` |
| Финальные затронутые Playwright-сценарии | 38/38 PASS | `playwright-focused-final.log` |
| Полный Playwright smoke | 450/450 PASS, без failures/flaky/skips | `playwright-full-final.log` |
| Docs sanity / whitespace / migration identity | PASS: новые локальные ссылки существуют, fences сбалансированы, migrations byte-identical HEAD | `docs-sanity.json`, `migration-identity.json`, `git diff --check` |

Все указанные файлы доказательств находятся в
`/private/tmp/HT-UX08-STAFF-PHOTO-DIRECT-MANAGEMENT-01/`.
R2 тестируется fake storage и loopback SDK; PostgreSQL — реальный локальный Testcontainers,
без изменения test skip policy. Browser tests используют mock API, не живой Telegram/backend.
Скриншоты `.tmp-checks/ht-ux08-photos/screenshots/` проверены визуально: Manager protected card
390×568, Staff own legacy preview 390×844, Owner hidden-card upload с несохранённым profile draft
1280×900. Фото-controls и read-only границы различимы, горизонтального переполнения нет.

Последовательность: сначала узкие проверки, затем регрессии и полный smoke. Первые Kotlin-запуски
выявили только форматирование новых строк; оно исправлено. Первый полный Playwright дал 446 PASS /
4 FAIL: два старых текста о separate publication и два all-buttons=0 ожидания для protected/duplicate
карточек противоречили новой разрешённой photo surface. Исправлены ожидания; запреты на profile
edit/publication/linkage и проверка отсутствия account/profile mutations сохранены. Ни skips,
ни retries, ни timeouts/config не ослаблены. Старые traces сохранены в `playwright-first-failures/`.

Команды (repo root; process-local Docker/JVM настройки):

```bash
DOCKER_HOST=unix:///Users/maksimmartynov/.docker/run/docker.sock \
_JAVA_OPTIONS='-Xmx4g -Dapi.version=1.44' \
./gradlew --offline --no-daemon --max-workers=1 \
  :backend:app:compileKotlin :backend:app:ktlintCheck \
  :backend:app:test --tests '*StaffPhoto*' --console=plain

DOCKER_HOST=unix:///Users/maksimmartynov/.docker/run/docker.sock \
_JAVA_OPTIONS='-Xmx4g -Dapi.version=1.44' \
./gradlew --offline --no-daemon --max-workers=1 :backend:app:test \
  --tests '*VenueStaffRoutesTest*' --tests '*VenueStaffRepositoryTest' \
  --tests '*VenueStaffMutationConcurrencyPostgresTest' --tests '*StaffProfileLinkConcurrencyPostgresTest' \
  --tests '*StaffInviteRepositoryTest' --tests '*StaffInviteReissuePostgresTest' \
  --tests '*VenueStaffModule*Test' --tests '*GuestVenueRoutesTest' \
  --tests '*VenueGuestPreviewRoutesTest' --tests '*VenueRbacRoutesTest' \
  --tests '*PlatformVenueMemberRepositoryTest' --tests '*VenueOwnerAccountRepositoryTest' --console=plain

npm --prefix miniapp run build
MINIAPP_E2E_PORT=5174 npm --prefix miniapp run e2e:smoke -- --workers=2 \
  --grep 'manager duplicate staff links|human profile labels|refreshed accepted member links|hidden guest team|R2 photos|UX08'
MINIAPP_E2E_PORT=5174 npm --prefix miniapp run e2e:smoke -- --workers=2
git --no-optional-locks diff --check
git --no-optional-locks diff --exit-code -- backend/app/src/main/resources/db/migration
git --no-optional-locks status --short
```

Docs-link проверка всего исторического checkpoint обнаружила 13 уже отсутствовавших временных
артефактов; их набор совпадает с HEAD. Новая секция checkpoint и ссылки изменённых документов
валидны; старые ссылки/историю не переписывали. Это ограничение исторических доказательств,
не ошибка текущей реализации.

Sandbox не давал читать локальный Docker socket; проверки выполнены после разрешённого локального
escalation. Ни auto-review rejection, ни внешнего runtime blocker не было.

## Изменённые файлы

22 файла: 5 backend runtime, 6 backend tests/fixtures, 3 Mini App source/tests, 8 docs/checkpoint.

- [PROJECT_STATUS.md](../PROJECT_STATUS.md)
- [backend/app/src/main/kotlin/com/hookah/platform/backend/api/StaffPhotoExceptions.kt](../backend/app/src/main/kotlin/com/hookah/platform/backend/api/StaffPhotoExceptions.kt)
- [backend/app/src/main/kotlin/com/hookah/platform/backend/miniapp/venue/VenueStaffPhotoRoutes.kt](../backend/app/src/main/kotlin/com/hookah/platform/backend/miniapp/venue/VenueStaffPhotoRoutes.kt)
- [backend/app/src/main/kotlin/com/hookah/platform/backend/miniapp/venue/staff/StaffPhotoRepository.kt](../backend/app/src/main/kotlin/com/hookah/platform/backend/miniapp/venue/staff/StaffPhotoRepository.kt)
- [backend/app/src/main/kotlin/com/hookah/platform/backend/miniapp/venue/staff/StaffPhotoService.kt](../backend/app/src/main/kotlin/com/hookah/platform/backend/miniapp/venue/staff/StaffPhotoService.kt)
- [backend/app/src/main/kotlin/com/hookah/platform/backend/miniapp/venue/staff/VenueStaffProfileRepository.kt](../backend/app/src/main/kotlin/com/hookah/platform/backend/miniapp/venue/staff/VenueStaffProfileRepository.kt)
- [backend/app/src/test/kotlin/com/hookah/platform/backend/miniapp/venue/VenueStaffRoutesTest.kt](../backend/app/src/test/kotlin/com/hookah/platform/backend/miniapp/venue/VenueStaffRoutesTest.kt)
- [backend/app/src/test/kotlin/com/hookah/platform/backend/miniapp/venue/VenueStaffRoutesTestManagerProfileParity.kt](../backend/app/src/test/kotlin/com/hookah/platform/backend/miniapp/venue/VenueStaffRoutesTestManagerProfileParity.kt)
- [backend/app/src/test/kotlin/com/hookah/platform/backend/miniapp/venue/staff/StaffPhotoConcurrencyPostgresTest.kt](../backend/app/src/test/kotlin/com/hookah/platform/backend/miniapp/venue/staff/StaffPhotoConcurrencyPostgresTest.kt)
- [backend/app/src/test/kotlin/com/hookah/platform/backend/miniapp/venue/staff/StaffPhotoLifecycleTest.kt](../backend/app/src/test/kotlin/com/hookah/platform/backend/miniapp/venue/staff/StaffPhotoLifecycleTest.kt)
- [backend/app/src/test/kotlin/com/hookah/platform/backend/miniapp/venue/staff/StaffPhotoRoutesPostgresTest.kt](../backend/app/src/test/kotlin/com/hookah/platform/backend/miniapp/venue/staff/StaffPhotoRoutesPostgresTest.kt)
- [backend/app/src/test/kotlin/com/hookah/platform/backend/miniapp/venue/staff/StaffPhotoTestFixture.kt](../backend/app/src/test/kotlin/com/hookah/platform/backend/miniapp/venue/staff/StaffPhotoTestFixture.kt)
- [docs/MEDIA_STORAGE_UPLOAD.md](../docs/MEDIA_STORAGE_UPLOAD.md)
- [docs/PRODUCT_SPEC.md](../docs/PRODUCT_SPEC.md)
- [docs/SECURITY_RBAC_MATRIX.md](../docs/SECURITY_RBAC_MATRIX.md)
- [docs/STAFF_PROFILES_SHIFTS_TIPS.md](../docs/STAFF_PROFILES_SHIFTS_TIPS.md)
- [docs/TESTING_QA_SMOKE_STRATEGY.md](../docs/TESTING_QA_SMOKE_STRATEGY.md)
- [docs/UX08_STAFF_PHOTO_DIRECT_MANAGEMENT.md](../docs/UX08_STAFF_PHOTO_DIRECT_MANAGEMENT.md)
- [docs/VENUE_OPERATIONS.md](../docs/VENUE_OPERATIONS.md)
- [miniapp/e2e/guest-smoke.spec.ts](../miniapp/e2e/guest-smoke.spec.ts)
- [miniapp/src/screens/staffPhoto.ts](../miniapp/src/screens/staffPhoto.ts)
- [miniapp/src/screens/venueStaff.ts](../miniapp/src/screens/venueStaff.ts)

Backend: отдельный photo RBAC, атомарная активация после PUT, retired legacy actions, запрет STAFF
legacy photoRef. Mini App: прямые add/replace/delete, protected photo panel, read-only own Staff,
без согласия/publish; сохранены recovery/navigation/drafts. Тесты проверяют изменившийся контракт
и прежние границы. Канонические документы и checkpoint отражают новое решение и live acceptance gap.

Финальный `git status --short`: 21 tracked ` M`, новый `?? docs/UX08_STAFF_PHOTO_DIRECT_MANAGEMENT.md`,
ранее существовавший `?? scripts/dev/`; staged changes нет. Других новых untracked файлов нет.
Побочные эффекты — локальные build outputs, Testcontainers fixtures, logs/XML/screenshots; приложение,
провайдер и live данные не изменены. Полный status и SHA256 изменённых файлов сохранены в
`final-status.txt` и `source-hashes.json` в каталоге доказательств. Невыполненных локальных операций нет.

## Граница готовности

Локальные проверки не являются staging-приёмкой и не доказывают реальную R2-связность,
Telegram/iPhone picker, live account correspondence или backup recovery. Старым клиентам нужно
обновить Mini App, чтобы получить новые controls; retired endpoints безопасно отказывают.
Следующий шаг после локальной готовности — ревью diff. Затем отдельно разрешённые публикация,
CI точного release SHA, staging deploy и ручная проверка iPhone. Ни один из этих внешних шагов
не выполнен в этой задаче.
