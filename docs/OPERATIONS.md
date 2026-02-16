# OPERATIONS RUNBOOK

Минимальный runbook для дежурства по backend (webhook/очереди/метрики).

## 1) Как смотреть очереди inbound/outbound

Источник истины — Prometheus-метрики на `GET /metrics`.

Ключевые метрики очередей:
- `inbound_queue_depth` — текущая глубина входящей очереди Telegram updates.
- `outbound_queue_depth` — текущая глубина очереди исходящей отправки в Telegram.
- `webhook_processing_lag_seconds` — задержка между приёмом webhook и фактической обработкой.

Быстрая проверка:

```bash
curl -s http://localhost:8080/metrics | rg "inbound_queue_depth|outbound_queue_depth|webhook_processing_lag_seconds"
```

## 2) Как смотреть `/metrics`

1. Убедиться, что backend отвечает:

```bash
curl -sS http://localhost:8080/health
```

2. Снять полный набор метрик:

```bash
curl -sS http://localhost:8080/metrics
```

3. Быстрый фильтр по webhook/outbound:

```bash
curl -s http://localhost:8080/metrics | rg "inbound_queue_depth|outbound_queue_depth|outbound_send_success_total|outbound_send_failed_total|outbound_429_total|webhook_processing_lag_seconds"
```

## 3) Что делать при инцидентах

### 3.1 Telegram API отвечает `429`

Симптомы:
- растёт `outbound_429_total`;
- может расти `outbound_queue_depth`.

Действия:
1. Проверить, что worker не упал и продолжает ретраи/доставку.
2. Проверить интенсивность outbound и временно снизить массовые рассылки/шумные операции.
3. Отслеживать тренд `outbound_queue_depth` и `outbound_send_success_total`:
   - если глубина снижается и успехи растут — система восстанавливается;
   - если глубина продолжает расти длительно — эскалировать (SRE/on-call платформы).

### 3.2 Рост очереди (inbound/outbound)

Симптомы:
- устойчивый рост `inbound_queue_depth` и/или `outbound_queue_depth`;
- рост `webhook_processing_lag_seconds`.

Действия:
1. Проверить доступность БД (`GET /db/health`) и системные ресурсы инстанса.
2. Проверить ошибки в логах worker/webhook обработчика (особенно repeated failures).
3. Если bottleneck в внешнем API (Telegram/Billing), зафиксировать зависимость и включить деградационный режим (fallback bot flow при необходимости).
4. Если после устранения причины очередь не дренируется — перезапустить воркеры контролируемо, без потери idempotency.

### 3.3 Падение webhook

Симптомы:
- Telegram не доставляет updates;
- ошибки/5xx на webhook endpoint;
- lag и inbound очередь выходят за базовый уровень.

Действия:
1. Проверить секрет webhook в конфигурации (`TELEGRAM_WEBHOOK_SECRET_TOKEN`) и прокси-маршрутизацию до backend.
2. Проверить endpoint health и логи на ошибки валидации/авторизации.
3. Убедиться, что сервис быстро ACK-ает update и обработка не блокирует HTTP ответ.
4. При длительном инциденте переключить операционный сценарий на fallback bot flow и эскалировать.

## 4) Критерий восстановления

Инцидент можно считать купированным, когда:
- `outbound_429_total` перестал резко расти;
- `inbound_queue_depth` и `outbound_queue_depth` возвращаются к стабильному базовому уровню;
- `webhook_processing_lag_seconds` вернулся к нормальному диапазону;
- новые webhook updates стабильно принимаются и доставляются.
