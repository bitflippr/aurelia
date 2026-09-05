// Throwaway home design study. Run: node apps/mobile/android/home-prototype/serve.mjs
import http from 'node:http';
import { readFileSync } from 'node:fs';
const page = new URL('./index.html', import.meta.url);
http.createServer((req, res) => {
  if (new URL(req.url, 'http://localhost').pathname !== '/') {
    res.writeHead(404); return res.end();
  }
  res.writeHead(200, { 'Content-Type': 'text/html; charset=utf-8', 'Cache-Control': 'no-store' });
  res.end(readFileSync(page));
}).listen(5188, '0.0.0.0', () => console.log('Home prototype: http://localhost:5188'));
