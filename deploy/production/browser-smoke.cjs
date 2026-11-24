// Run only on the operator Mac, through the SSH connection that owns the deployment.
const { chromium } = require('../../miniapp/node_modules/playwright');

async function main() {
  const [port, sha, instance] = process.argv.slice(2);
  const origin = 'http://127.0.0.1:' + Number(port);
  const browser = await chromium.launch({executablePath: chromium.executablePath()});
  try {
    if (port === '--preflight') return;
    const page = await browser.newPage();
    const assets = new Set();
    const errors = [];
    page.on('pageerror', error => errors.push(error.name));
    page.on('response', response => {
      if (new URL(response.url()).origin === origin && response.status() === 200) {
        assets.add(response.request().resourceType());
      }
    });
    const version = await (await page.request.get(origin + '/version')).json();
    if (version.env !== 'prod' || version.version !== sha || version.instance !== instance) {
      throw Error('Unexpected backend behind SSH tunnel');
    }
    const entry = await page.goto(origin + '/miniapp/');
    await page.waitForFunction(() => document.querySelector('#app')?.innerText.trim().length > 0);
    if (entry.status() !== 200 || !assets.has('script') || !assets.has('stylesheet') || errors.length) {
      throw Error('Mini App render failed');
    }
  } finally {
    await browser.close();
  }
}
main().catch(() => { console.error('Closed Chromium smoke failed'); process.exitCode = 1; });
