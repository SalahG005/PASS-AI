/**
 * Headless screenshot for static HTML preview (optional — requires playwright).
 * Usage: node preview-static-site.mjs file:///path/to/index.html /path/out.png
 */
import { existsSync } from 'node:fs';

const htmlUrl = process.argv[2];
const outPath = process.argv[3];
if (!htmlUrl || !outPath) {
  console.log(JSON.stringify({ ok: false, error: 'usage: node preview-static-site.mjs <file-url> <out.png>' }));
  process.exit(1);
}

try {
  const { chromium } = await import('playwright');
  const browser = await chromium.launch({ headless: true });
  const page = await browser.newPage({ viewport: { width: 1280, height: 900 } });
  await page.goto(htmlUrl, { waitUntil: 'networkidle', timeout: 60000 });
  await page.screenshot({ path: outPath, fullPage: true });
  await browser.close();
  console.log(JSON.stringify({ ok: true, path: outPath }));
} catch (e) {
  console.log(JSON.stringify({ ok: false, error: String(e?.message || e) }));
  process.exit(existsSync(outPath) ? 0 : 1);
}
