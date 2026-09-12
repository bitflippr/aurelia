// Throwaway mockup preview. No dependencies; serves only explicit design assets.
import { createServer } from 'node:http';
import { readFile } from 'node:fs/promises';
const base = new URL('./', import.meta.url);
const prefix = '/design/detail-prototype/';
const files = new Map([
  [prefix + 'index.html', ['index.html', 'text/html']],
  ...['artist', 'album'].map(name => [prefix + `assets/${name}.jpg`, [`assets/${name}.jpg`, 'image/jpeg']]),
  ['/app/src/main/assets/fonts/google_sans_flex_regular.ttf', ['../../app/src/main/assets/fonts/google_sans_flex_regular.ttf', 'font/ttf']],
]);
createServer(async (req, res) => {
  const pathname = new URL(req.url, 'http://localhost').pathname;
  const file = files.get(pathname === '/' ? prefix + 'index.html' : pathname);
  if (pathname === '/') { res.writeHead(302, { Location: prefix + 'index.html' }); res.end(); return; }
  if (!file) { res.writeHead(404); res.end(); return; }
  res.setHeader('Content-Type', file[1]);
  res.end(await readFile(new URL(file[0], base)));
}).listen(8765, '127.0.0.1', () => console.log('Mockups: http://127.0.0.1:8765/design/detail-prototype/index.html?variant=A'));
