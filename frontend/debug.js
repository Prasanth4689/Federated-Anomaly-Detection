import puppeteer from 'puppeteer';

(async () => {
  try {
    const browser = await puppeteer.launch({ headless: true });
    const page = await browser.newPage();
    
    // Capture console messages
    page.on('console', msg => console.log('PAGE LOG:', msg.text()));
    
    // Capture page errors (unhandled exceptions)
    page.on('pageerror', error => console.error('PAGE ERROR:', error.message));
    
    // Capture failed requests
    page.on('requestfailed', request =>
      console.error('REQUEST FAILED:', request.url(), request.failure()?.errorText)
    );

    console.log('Navigating to localhost:5174...');
    await page.goto('http://localhost:5174', { waitUntil: 'networkidle0', timeout: 10000 });
    
    console.log('Page title:', await page.title());
    await browser.close();
  } catch (err) {
    console.error('Puppeteer script failed:', err);
    process.exit(1);
  }
})();
