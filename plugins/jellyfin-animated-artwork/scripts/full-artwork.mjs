import fs from 'node:fs/promises';
import path from 'node:path';
import {createHash, randomUUID} from 'node:crypto';
import {execFile} from 'node:child_process';
import {promisify} from 'node:util';

const run = promisify(execFile);
const maxBytes = 64 * 1024 * 1024;

// Apple stores these VOD playlists as byte ranges of one complete fragmented MP4.
// Fetch that object directly: FFmpeg can silently stop after the first remote HLS range.
export function fullArtworkSource(playlist, playlistUrl) {
  const lines = playlist.split(/\r?\n/).map(line => line.trim());
  if (!lines.includes('#EXT-X-ENDLIST')) throw new Error('Artwork playlist is not complete VOD');
  if (lines.some(line => line.startsWith('#EXT-X-KEY:') && !line.includes('METHOD=NONE'))) {
    throw new Error('Encrypted artwork is unsupported');
  }
  const media = new Set(lines.filter(line => line && !line.startsWith('#')));
  const maps = lines.filter(line => line.startsWith('#EXT-X-MAP:')).map(line => line.match(/URI="([^"]+)"/)?.[1]);
  if (media.size !== 1 || maps.length !== 1 || !media.has(maps[0])) {
    throw new Error('Expected one complete MP4 shared by all artwork segments');
  }
  const durations = lines.filter(line => line.startsWith('#EXTINF:')).map(line => Number.parseFloat(line.slice(8)));
  const duration = durations.reduce((total, value) => total + value, 0);
  if (!durations.length || durations.some(value => !(value > 0)) || !(duration > 0 && duration <= 90)) {
    throw new Error(`Invalid full artwork duration: ${duration}`);
  }
  const url = new URL(maps[0], playlistUrl);
  if (!['http:', 'https:'].includes(url.protocol)) throw new Error('Invalid media URL');
  return {url: url.href, duration, segments: durations.length};
}

export function validateFullDuration(actual, expected) {
  if (!Number.isFinite(expected) || expected <= 0) throw new Error('Missing expected full duration');
  if (!Number.isFinite(actual) || Math.abs(actual - expected) > 0.1) {
    throw new Error(`Incomplete artwork: expected ${expected.toFixed(3)}s, received ${actual.toFixed(3)}s`);
  }
}

export async function downloadFullArtwork(variantUrl, destination) {
  const response = await fetch(variantUrl, {signal: AbortSignal.timeout(30000)});
  if (!response.ok) throw new Error(`Playlist HTTP ${response.status}`);
  const source = fullArtworkSource(await response.text(), variantUrl);
  await fs.mkdir(path.dirname(destination), {recursive: true});
  const input = `${destination}.source-${randomUUID()}.mp4`;
  const output = `${destination}.complete-${randomUUID()}.mp4`;
  try {
    const media = await fetch(source.url, {signal: AbortSignal.timeout(120000)});
    if (media.status !== 200) throw new Error(`Full media HTTP ${media.status}`);
    const handle = await fs.open(input, 'wx');
    let received = 0;
    try {
      for await (const chunk of media.body) {
        received += chunk.length;
        if (received > maxBytes) throw new Error('Artwork exceeds 64 MiB');
        await handle.writeFile(chunk);
      }
    } finally { await handle.close(); }
    await run('ffmpeg', ['-nostdin', '-hide_banner', '-loglevel', 'error', '-xerror', '-y', '-i', input,
      '-map', '0:v:0', '-an', '-c:v', 'copy', '-movflags', '+faststart', output], {timeout: 120000, maxBuffer: 1024 * 1024});
    const {stdout} = await run('ffprobe', ['-v', 'error', '-count_frames', '-show_entries',
      'stream=codec_name,codec_type,width,height,nb_read_frames:format=duration,size', '-of', 'json', output], {timeout: 120000});
    const probe = JSON.parse(stdout);
    const video = probe.streams.find(stream => stream.codec_type === 'video');
    validateFullDuration(Number(probe.format.duration), source.duration);
    if (video?.codec_name !== 'h264' || !Number(video.nb_read_frames) || Number(probe.format.size) > maxBytes) {
      throw new Error('Invalid complete H.264 artwork');
    }
    await run('ffmpeg', ['-nostdin', '-hide_banner', '-loglevel', 'error', '-xerror', '-i', output,
      '-map', '0:v:0', '-f', 'null', '-'], {timeout: 120000, maxBuffer: 1024 * 1024});
    const sha256 = createHash('sha256').update(await fs.readFile(output)).digest('hex');
    await fs.rename(output, destination);
    return {sha256, bytes: Number(probe.format.size), duration: Number(probe.format.duration),
      width: video.width, height: video.height, frames: Number(video.nb_read_frames),
      expectedDuration: source.duration, segments: source.segments, mediaUrl: source.url};
  } finally {
    await fs.rm(input, {force: true});
    await fs.rm(output, {force: true});
  }
}
