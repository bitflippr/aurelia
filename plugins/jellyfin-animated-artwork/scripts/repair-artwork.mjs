#!/usr/bin/env node
// Repair only assets whose hashes still match our manifests, retaining all originals.
import fs from 'node:fs/promises';
import path from 'node:path';
import {createHash, randomUUID} from 'node:crypto';
import {execFile} from 'node:child_process';
import {promisify} from 'node:util';
import {downloadFullArtwork, validateFullDuration} from './full-artwork.mjs';

const run = promisify(execFile);
if (process.argv.length !== 3) throw new Error('Usage: node repair-artwork.mjs DOWNLOADED_ARTWORK_DIRECTORY');
const root = await fs.realpath(process.argv[2]);
const manifestFile = path.join(root, 'manifest.json');
const placementFile = path.join(root, 'sidecar-placement.json');
const manifest = JSON.parse(await fs.readFile(manifestFile, 'utf8'));
const placement = JSON.parse(await fs.readFile(placementFile, 'utf8'));
const library = await fs.realpath(manifest.library);
const staging = path.join(root, 'full-loop-repair-' + randomUUID());
await fs.mkdir(staging);
await fs.copyFile(manifestFile, path.join(staging, 'manifest.before.json'));
await fs.copyFile(placementFile, path.join(staging, 'placement.before.json'));
const hash = bytes => createHash('sha256').update(bytes).digest('hex');
const replacements = [];
const report = [];
const within = (base, file) => file.startsWith(base + path.sep);

async function expectFile(file, expected) {
  const stat = await fs.lstat(file);
  if (!stat.isFile() || hash(await fs.readFile(file)) !== expected) throw new Error(`Existing file changed: ${file}`);
}

for (const album of Object.values(manifest.albums)) {
  if (album.status !== 'downloaded' || !album.assets?.square) continue;
  const directory = await fs.realpath(album.directory);
  if (!within(library, directory)) throw new Error('Album is outside the music library');
  for (const [kind, asset] of Object.entries(album.assets)) {
    const original = await fs.realpath(path.resolve(root, asset.path));
    if (!within(root, original)) throw new Error('Asset is outside the download directory');
    await expectFile(original, asset.sha256);
    const prepared = path.join(staging, `${album.id}-${kind}.mp4`);
    const complete = await downloadFullArtwork(asset.variantUrl, prepared);
    const filename = kind === 'square' ? 'animated-cover.mp4' : 'animated-cover-tall.mp4';
    const sidecar = path.join(directory, filename);
    const placed = placement.files.find(file => file.path === sidecar);
    if (!placed || placed.sha256 !== asset.sha256) throw new Error(`Untracked sidecar: ${sidecar}`);
    await expectFile(sidecar, asset.sha256);
    replacements.push({source: prepared, path: original, oldHash: asset.sha256, newHash: complete.sha256});
    replacements.push({source: prepared, path: sidecar, oldHash: asset.sha256, newHash: complete.sha256});
    report.push({album: album.album, artist: album.artist, kind, previousDuration: asset.duration, ...complete});
    Object.assign(asset, complete);
    placed.sha256 = complete.sha256;
    console.log(`${album.artist} — ${album.album} [${kind}]: ${complete.duration.toFixed(3)}s, ${complete.frames} frames`);
  }
  const gif = path.join(staging, `${album.id}.gif`);
  await run('ffmpeg', ['-nostdin', '-hide_banner', '-loglevel', 'error', '-y',
    '-i', path.join(staging, `${album.id}-square.mp4`), '-filter_complex',
    '[0:v]fps=12,scale=480:480:flags=lanczos,split[a][b];[a]palettegen=max_colors=128[p];[b][p]paletteuse=dither=bayer:bayer_scale=3',
    '-an', '-loop', '0', gif], {timeout: 180000, maxBuffer: 1024 * 1024});
  const {stdout} = await run('ffprobe', ['-v', 'error', '-ignore_loop', '1', '-show_entries', 'format=duration', '-of', 'json', gif]);
  validateFullDuration(Number(JSON.parse(stdout).format.duration), album.assets.square.expectedDuration);
  await run('ffmpeg', ['-nostdin', '-hide_banner', '-loglevel', 'error', '-xerror', '-ignore_loop', '1', '-i', gif, '-f', 'null', '-'],
    {timeout: 120000, maxBuffer: 1024 * 1024});
  const sidecar = path.join(directory, 'animated-cover.gif');
  const placed = placement.files.find(file => file.path === sidecar);
  if (!placed) throw new Error(`Untracked GIF: ${sidecar}`);
  const newHash = hash(await fs.readFile(gif));
  replacements.push({source: gif, path: sidecar, oldHash: placed.sha256, newHash});
  placed.sha256 = newHash;
}

// Validate the whole replacement set before modifying any library or archive file.
for (const file of replacements) {
  await expectFile(file.path, file.oldHash);
  await expectFile(file.source, file.newHash);
}
const backups = path.join(staging, 'originals');
await fs.mkdir(backups);
for (const [index, file] of replacements.entries()) {
  file.backup = path.join(backups, String(index) + path.extname(file.path));
  await fs.copyFile(file.path, file.backup);
}
await fs.writeFile(path.join(staging, 'replacement-plan.json'), JSON.stringify(replacements, null, 2));
for (const file of replacements) {
  await expectFile(file.path, file.oldHash);
  const temporary = file.path + '.repair-' + randomUUID();
  try {
    await fs.copyFile(file.source, temporary);
    await fs.chmod(temporary, 0o644);
    await fs.rename(temporary, file.path);
  } finally { await fs.rm(temporary, {force: true}); }
}
manifest.updatedAt = new Date().toISOString();
manifest.validation = {checkedAt: manifest.updatedAt, passed: report.length, failed: 0,
  method: 'Full HLS timeline duration comparison, frame count, SHA-256 and complete decode'};
placement.completedAt = manifest.updatedAt;
for (const [file, value] of [[manifestFile, manifest], [placementFile, placement]]) {
  await fs.writeFile(file + '.repair', JSON.stringify(value, null, 2));
  await fs.rename(file + '.repair', file);
}
for (const file of replacements) await expectFile(file.path, file.newHash);
await fs.writeFile(path.join(root, 'full-loop-repair-report.json'), JSON.stringify({completedAt: manifest.updatedAt, backupDirectory: staging, assets: report}, null, 2));
console.log(`REPAIRED ${report.length} MP4s and ${placement.albums} GIFs; originals retained in ${staging}`);
