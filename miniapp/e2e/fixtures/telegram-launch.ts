import { readFileSync } from 'node:fs'

// Synthetic shape of an authenticated inline/menu/main/direct-link launch; never server-valid.
export const telegramLaunchInitData = new URLSearchParams({
  query_id: 'AAE-synthetic-ux04',
  user: JSON.stringify({ id: 7001, first_name: 'Fixture' }),
  auth_date: '1793098800',
  hash: '0'.repeat(64)
}).toString()

// Reproduce the relevant official SDK bootstrap with no launch parameters:
// https://telegram.org/js/telegram-web-app.js creates WebApp unconditionally,
// with empty initData/initDataUnsafe and platform="unknown", even outside Telegram.
// Optional local source override verifies these same assertions against the real SDK;
// CI never fetches the CDN.
export const telegramSdkBootstrap = process.env.UX04_TELEGRAM_SDK_PATH
  ? readFileSync(process.env.UX04_TELEGRAM_SDK_PATH, 'utf8')
  : `(() => {
      var WebApp = {};
      var webAppInitData = '', webAppInitDataUnsafe = {};
      var webAppPlatform = 'unknown';
      if (!window.Telegram) window.Telegram = {};
      Object.defineProperty(WebApp, 'initData', { get: function () { return webAppInitData; } });
      Object.defineProperty(WebApp, 'initDataUnsafe', { get: function () { return webAppInitDataUnsafe; } });
      Object.defineProperty(WebApp, 'platform', { get: function () { return webAppPlatform; } });
      window.Telegram.WebApp = WebApp;
    })();`
