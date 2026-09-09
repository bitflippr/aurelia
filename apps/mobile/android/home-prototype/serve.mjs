// Throwaway home design study. Run: node apps/mobile/android/home-prototype/serve.mjs
import http from 'node:http';
import { readFileSync } from 'node:fs';
const page = new URL('./index.html', import.meta.url);
const routes = new Map([
  ['/', [page, 'text/html; charset=utf-8']],
  ['/genres', [new URL('./genres.html', import.meta.url), 'text/html; charset=utf-8']],
  ['/genres.html', [new URL('./genres.html', import.meta.url), 'text/html; charset=utf-8']],
  ['/font.ttf', [new URL('../app/src/main/assets/fonts/google_sans_flex_regular.ttf', import.meta.url), 'font/ttf']],
]);
http.createServer((req, res) => {
  const route = routes.get(new URL(req.url, 'http://localhost').pathname);
  if (!route) {
    res.writeHead(404); return res.end();
  }
  res.writeHead(200, { 'Content-Type': route[1], 'Cache-Control': 'no-store' });
  res.end(readFileSync(route[0]));
}).listen(5188, '0.0.0.0', () => console.log('Home prototype: http://localhost:5188'));
