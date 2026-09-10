#!/usr/bin/env node
// Place downloaded MP4s and a generated GIF directly beside each album's tracks.
import fs from 'node:fs/promises';
import path from 'node:path';
import {createHash, randomUUID} from 'node:crypto';
import {execFile} from 'node:child_process';
import {promisify} from 'node:util';
const run = promisify(execFile);
const sourceArg = process.argv[2];
if (!sourceArg || process.argv.length !== 3) {
  console.error('Usage: node scripts/prepare-artwork.mjs DOWNLOADED_ARTWORK_DIRECTORY');
  process.exit(1);
}
const source = await fs.realpath(sourceArg);
const manifest = JSON.parse(await fs.readFile(path.join(source, 'manifest.json'), 'utf8'));
const library = await fs.realpath(manifest.library);
const hash = bytes => createHash('sha256').update(bytes).digest('hex');
const within = (root, candidate) => { const relative = path.relative(root, candidate); return relative && relative !== '..' && !relative.startsWith('../') && !path.isAbsolute(relative); };

async function place(sourcePath, destination, expectedHash) {
  const bytes = await fs.readFile(sourcePath);
  if (hash(bytes) !== expectedHash) throw new Error(`Checksum mismatch: ${sourcePath}`);
  try {
    const existing = await fs.lstat(destination);
    if (!existing.isFile() || hash(await fs.readFile(destination)) !== expectedHash) throw new Error(`Existing sidecar differs; refusing to overwrite ${destination}`);
    return;
  } catch (error) { if (error.code !== 'ENOENT') throw error; }
  const temporary = destination + '.tmp-' + randomUUID();
  try {
    await fs.writeFile(temporary, bytes, {flag: 'wx', mode: 0o644});
    await fs.link(temporary, destination); // Atomic creation without overwriting an existing file.
  } finally { await fs.rm(temporary, {force: true}); }
}

let count = 0;
for (const album of Object.values(manifest.albums)) {
  if (album.status !== 'downloaded' || !album.assets?.square) continue;
  const directory = await fs.realpath(album.directory);
  if (!within(library, directory)) throw new Error('Album is outside the music library');
  for (const [variant, filename] of [['square', 'animated-cover.mp4'], ['tall', 'animated-cover-tall.mp4']]) {
    const asset = album.assets[variant];
    if (!asset) continue;
    const input = await fs.realpath(path.resolve(source, asset.path));
    if (!within(source, input)) throw new Error('Artwork source escapes the download directory');
    await place(input, path.join(directory, filename), asset.sha256);
  }
  const gif = path.join(directory, 'animated-cover.gif');
  const temporary = path.join(directory, '.animated-cover-' + randomUUID() + '.gif');
  try {
    await run('ffmpeg', ['-nostdin', '-hide_banner', '-loglevel', 'error', '-y', '-i', path.join(directory, 'animated-cover.mp4'),
      '-filter_complex', '[0:v]fps=12,scale=480:480:flags=lanczos,split[a][b];[a]palettegen=max_colors=128[p];[b][p]paletteuse=dither=bayer:bayer_scale=3',
      '-an', '-loop', '0', temporary], {timeout: 120000, maxBuffer: 1024 * 1024});
    await run('ffmpeg', ['-nostdin', '-hide_banner', '-loglevel', 'error', '-xerror', '-ignore_loop', '1', '-i', temporary, '-f', 'null', '-'], {timeout: 60000});
    await place(temporary, gif, hash(await fs.readFile(temporary)));
  } finally { await fs.rm(temporary, {force: true}); }
  console.log(`${++count}: ${album.artist} — ${album.album}`);
}
console.log(`Artwork is beside the tracks in ${count} album folders. No server index or path configuration is required.`);
