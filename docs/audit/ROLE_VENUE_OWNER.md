# Venue Owner

Дата актуализации: 2026-07-03.

Статус: **current role reference**. Канонический roadmap: `docs/UPDATED_PRODUCT_AI_ROADMAP.md`. Этот файл разделяет Telegram bot owner setup flow и Venue Mini App owner panel.

## Current status

Venue Owner - главный владелец конкретного заведения. Он управляет карточкой, заказным меню, столами/QR, персоналом, staff chat, бронями, промо, сообщениями с гостями, статистикой и операционными заказами. Platform Owner права не выдаются автоматически.

Runtime Venue Owner access is granted by an active `venue_members` row with role `OWNER`. Legacy/primary owner linkages such as `venues.owner_account_id` and `venue_owner_accounts.primary_owner_user_id` do not by themselves preserve Venue Mini App or Telegram Bot venue-owner access after the OWNER membership is revoked.

Owner может работать в двух поверхностях:
- Telegram bot - более полный setup/onboarding/editor flow;
- Venue Mini App - операционная панель с заказами, счётом, бронями и частью management screens.

## Telegram bot

Основные owner flows:
- создание/настройка заведения;
- профиль/карточка заведения;
- `📖 Фото-меню` как информационный section карточки;
- `🍽 Заказное меню` как structured menu: категории, позиции, цены, availability/stop-list;
- tables/QR;
- staff list/invites/roles;
- staff chat link;
- booking settings, включая custom hold input;
- promotions / promotion UX consolidation;
- media upload для owner info sections без fallback spam;
- явная back-navigation по setup sections.

Важное naming rule:
- `📖 Фото-меню` - информационный text/media/PDF раздел внутри `ℹ️ Информация`;
- `🍽 Заказное меню` - structured ассортимент, который guest использует после QR/table context.

## Mini App

Venue Owner открывает Venue Mini App через inline `web_app` entry, чтобы Telegram передал `initData`.

Доступные owner areas:
- dashboard;
- orders queue/detail;
- full bill with human order display label, personal/shared account context, discounts, service charges and excluded/non-payable items;
- bill controls: manual item discount, exclude/restore item;
- close bill/order;
- bookings queue/actions;
- booking conversation messages/support threads;
- staff calls;
- shift-extension requests/settings;
- menu/availability management;
- tables/QR management;
- staff management where implemented;
- staff chat link/status;
- subscription/payment state screen with adjusted paid-through and next-payment dates;
- read-only stats;
- settings where implemented.

Mini App остаётся backend-RBAC enforced: кнопки в UI не являются security boundary.

## Allowed actions

- Управлять venue profile/card/info sections.
- Загружать и удалять media для info sections.
- Управлять `📖 Фото-меню` как info section.
- Управлять `🍽 Заказным меню`, категориями, позициями, ценами и availability.
- Управлять stop-list item/option availability.
- Управлять tables/QR, включая rotation/export where owner permission allows.
- Управлять staff list/invites/roles with last-owner protection.
- Подключать staff chat.
- Отвязывать staff chat through the owner-only Mini App flow after explicit confirmation.
- Смотреть и вести заказы, менять allowed statuses, закрывать счёт.
- Управлять bill item adjustments: скидки, исключение, возврат позиции в счёт.
- Смотреть и вести брони.
- Подтверждать, отменять, переносить/предлагать новое время, писать гостю по брони и отмечать arrived/no-show.
- Настраивать booking settings.
- Смотреть статистику.
- Читать/отвечать/завершать/возобновлять booking conversation threads.
- Смотреть своё subscription/payment state, включая adjusted paid-through and next-payment date, visible open invoices and human period copy.

## Denied actions

- Platform owner flows: platform venue lifecycle, subscription commercial terms, global connection requests.
- Manual mark-paid, courtesy/free days and Platform Owner billing cockpit controls.
- Доступ к hidden/deleted platform-level venue list без platform owner role.
- Bypass backend RBAC через прямые Mini App/API вызовы.
- Hard delete venue data without platform-level safety decision.
- Keep Venue Mode access after Platform Owner revokes this user's active OWNER membership for the venue.

## Known gaps / needs smoke

- Telegram owner setup остаётся богаче Mini App owner setup; часть настроек всё ещё bot-first.
- Mini App settings and staff management parity should be smoke-tested by role.
- Staff chat diagnostics/unlink polish is closed after M6 staging smoke; keep real Telegram group link/test/unlink and operational notification delivery in per-venue regression.
- Staff-call lifecycle, linked staff-chat notification delivery and ACK/DONE audit hardening are CLOSED / staging smoke passed for Venue Mini App and Telegram staff-chat surfaces. Applied ACK/DONE transitions leave audit evidence with actor user id and source; audit is best-effort.
- Row-level `acked_by` / `done_by` / ACK-DONE timestamp columns, CANCELLED UI/lifecycle and staff-call UX polish are not implemented in this milestone. Guest table-context cleanup/exit is CLOSED / staging smoke passed and belongs to the Guest role regression checklist.
- Menu options/photos/descriptions/top-list richness may still be partial depending on guest surface.
- Multi-venue owner selector/entry should be smoke-tested if owner has several memberships.
- Platform owner invite, owner revoke and ownership access management belong to Platform Owner flow, not this role doc.
- Platform Owner can add a Venue Owner by invite and can revoke one OWNER when another active OWNER remains; create/accept/revoke actions are audited.
- Manual billing cockpit/renewal/courtesy are staging-smoked: Venue Owner sees adjusted subscription state but cannot mark paid or add courtesy/free days.
- Primary/legal/billing owner relink and a full ownership transfer helper are separate Platform Owner milestones;
  current runtime access is controlled by active `venue_members` OWNER memberships.

## Smoke-critical checks

1. Owner bot setup shows separate `🍽 Заказное меню` and `📖 Фото-меню`.
2. Upload multiple media files in info section; state remains stable until `Готово`/`Назад`.
3. Guest sees filled `📖 Фото-меню` through `ℹ️ Информация`.
4. Owner opens Venue Mini App through inline `web_app`; auth succeeds with initData.
5. Owner can view queue/detail/full bill and close bill.
6. Owner can apply/remove manual discount and exclude/restore bill item.
7. Owner can manage bookings/menu/tables/staff according to current UI and backend permissions.
8. Owner can open `Сообщения`, reply to booking threads and use resolve/reopen without changing booking lifecycle.
9. Owner can open `Статистика`.
10. Owner can manage staff chat link/status/test flow, copy or regenerate an active link code, and safely unlink an incorrect staff chat binding.
11. Owner can accept/close staff calls; linked Telegram staff group receives Mini App-created staff-call notification and staff-call ACK/DONE audit rows include actor evidence during regression smoke.
12. Owner opens subscription screen and sees adjusted `Оплачено до ... включительно` / next-payment state when billing/courtesy exists.
13. Owner sees visible open invoice/payment state where allowed, but cannot mark invoice paid or add courtesy/free days.
