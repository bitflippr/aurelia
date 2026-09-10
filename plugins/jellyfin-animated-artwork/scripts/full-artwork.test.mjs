import test from 'node:test';
import assert from 'node:assert/strict';
import fs from 'node:fs/promises';
import os from 'node:os';
import path from 'node:path';
import http from 'node:http';
import {execFile} from 'node:child_process';
import {promisify} from 'node:util';
import {fullArtworkSource, validateFullDuration, downloadFullArtwork} from './full-artwork.mjs';

const run = promisify(execFile);
const playlist = `#EXTM3U
#EXT-X-MAP:URI="clip.mp4",BYTERANGE="100@0"
#EXTINF:3,
#EXT-X-BYTERANGE:500@100
clip.mp4
#EXTINF:3,
#EXT-X-BYTERANGE:500
clip.mp4
#EXT-X-ENDLIST
`;

test('counts the full timeline even when URIs and implicit byte range lengths repeat', () => {
  assert.deepEqual(fullArtworkSource(playlist, 'https://example.com/art/index.m3u8'), {
    url: 'https://example.com/art/clip.mp4', duration: 6, segments: 2,
  });
});

test('rejects first-segment output instead of accepting a successful remux', () => {
  assert.throws(() => validateFullDuration(3, 15), /Incomplete artwork/);
  assert.throws(() => validateFullDuration(3, undefined), /Missing expected full duration/);
  validateFullDuration(15.033, 15);
});

test('rejects incomplete, encrypted or unsupported multi-object playlists', () => {
  assert.throws(() => fullArtworkSource(playlist.replace('#EXT-X-ENDLIST', ''), 'https://example.com/a'), /VOD/);
  assert.throws(() => fullArtworkSource(playlist + '#EXT-X-KEY:METHOD=AES-128', 'https://example.com/a'), /Encrypted/);
  assert.throws(() => fullArtworkSource(playlist.replace('clip.mp4\n#EXT-X-ENDLIST', 'other.mp4\n#EXT-X-ENDLIST'), 'https://example.com/a'), /one complete MP4/);
});

test('downloads every frame of a real multi-range MP4 and refuses a truncated response', async () => {
  const dir = await fs.mkdtemp(path.join(os.tmpdir(), 'full-artwork-test-'));
  let server;
  try {
    await run('ffmpeg', ['-nostdin', '-hide_banner', '-loglevel', 'error', '-f', 'lavfi', '-i',
      'testsrc2=size=64x64:rate=12:duration=4', '-c:v', 'libx264', '-g', '12', '-an', '-f', 'hls',
      '-hls_time', '1', '-hls_list_size', '0', '-hls_segment_type', 'fmp4', '-hls_flags', 'single_file', path.join(dir, 'index.m3u8')]);
    const index = await fs.readFile(path.join(dir, 'index.m3u8'), 'utf8');
    const file = index.match(/#EXT-X-MAP:URI="([^"]+)"/)[1];
    const bytes = await fs.readFile(path.join(dir, file));
    let truncate = false;
    server = http.createServer((req, res) => {
      if (req.url === '/index.m3u8') return res.end(index);
      const range = index.match(/#EXT-X-BYTERANGE:(\d+)@(\d+)/);
      res.end(truncate ? bytes.subarray(0, Number(range[1]) + Number(range[2])) : bytes);
    });
    await new Promise(resolve => server.listen(0, '127.0.0.1', resolve));
    const url = `http://127.0.0.1:${server.address().port}/index.m3u8`;
    const result = await downloadFullArtwork(url, path.join(dir, 'complete.mp4'));
    assert.equal(result.frames, 48);
    assert.equal(result.duration, 4);
    truncate = true;
    await assert.rejects(downloadFullArtwork(url, path.join(dir, 'bad.mp4')), /Incomplete artwork/);
    await assert.rejects(fs.stat(path.join(dir, 'bad.mp4')), {code: 'ENOENT'});
  } finally {
    server?.closeAllConnections();
    if (server) await new Promise(resolve => server.close(resolve));
    await fs.rm(dir, {recursive: true, force: true});
  }
});
