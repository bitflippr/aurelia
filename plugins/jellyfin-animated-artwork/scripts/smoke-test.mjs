#!/usr/bin/env node
// Runs only against a fresh, isolated server and synthetic media. Never connects to an existing server.
import fs from 'node:fs/promises';
import {createWriteStream} from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import {spawn, execFile} from 'node:child_process';
import {promisify} from 'node:util';
import {createHash, randomUUID} from 'node:crypto';
import {fileURLToPath} from 'node:url';
import assert from 'node:assert/strict';
import {createServer} from 'node:net';

const run = promisify(execFile);
const project = path.dirname(path.dirname(fileURLToPath(import.meta.url)));
const binary = process.env.JELLYFIN_BINARY || 'jellyfin';
const root = await fs.mkdtemp(path.join(os.tmpdir(), 'jellyfin-artwork-smoke-'));
const port = Number(process.env.ARTWORK_TEST_PORT || 18112);
const basePath = '/jellyfin-test';
const origin = `http://127.0.0.1:${port}`;
const base = origin + basePath;
const pluginId = 'b5329c03-6d1e-4d41-9fd4-6c5ed58d2446';
const password = randomUUID();
let server;
let token;
const pause = ms => new Promise(resolve => setTimeout(resolve, ms));
const output = createWriteStream(path.join(root, 'server.log'));
const headers = () => ({Authorization: 'MediaBrowser Client="Artwork Smoke Test", Device="Test", DeviceId="animated-artwork-smoke", Version="1.0"' + (token ? `, Token="${token}"` : '')});
async function request(route, {method = 'GET', body, authenticated = true, extra = {}} = {}) {
  return fetch(base + route, {method, headers: {...(authenticated ? headers() : {}), ...(body ? {'Content-Type': 'application/json'} : {}), ...extra},
    ...(body ? {body: JSON.stringify(body)} : {}), signal: AbortSignal.timeout(15000)});
}
async function json(route, options) {
  const response = await request(route, options);
  if (!response.ok) throw new Error(`${options?.method || 'GET'} ${route}: HTTP ${response.status} ${(await response.text()).slice(0, 300)}`);
  return response.status === 204 ? null : response.json();
}
async function expectStatus(route, status, options) {
  const response = await request(route, options);
  assert.equal(response.status, status, `${options?.method || 'GET'} ${route}`);
  return response;
}
async function makeMusic(folder, album) {
  await fs.mkdir(folder, {recursive: true});
  await run('ffmpeg', ['-hide_banner', '-loglevel', 'error', '-f', 'lavfi', '-i', 'sine=frequency=440:duration=1',
    '-metadata', 'artist=Fixture Artist', '-metadata', 'album_artist=Fixture Artist', '-metadata', `album=${album}`,
    '-metadata', 'title=Fixture Track', '-y', path.join(folder, '01.flac')]);
  await run('ffmpeg', ['-hide_banner', '-loglevel', 'error', '-f', 'lavfi', '-i', 'color=c=blue:s=64x64', '-frames:v', '1', '-y', path.join(folder, 'cover.jpg')]);
}
async function asset(file, width, height, duration) {
  const bytes = await fs.readFile(path.join(root, 'music/Fixture Artist/Fixture Album', file));
  return {file, sha256: createHash('sha256').update(bytes).digest('hex'), bytes: bytes.length, width, height, duration};
}

try {
  const portProbe = createServer();
  await new Promise((resolve, reject) => { portProbe.once('error', reject); portProbe.listen({host: '127.0.0.1', port, exclusive: true}, resolve); });
  await new Promise((resolve, reject) => portProbe.close(error => error ? reject(error) : resolve()));
  await fs.mkdir(path.join(root, 'config'), {recursive: true});
  await fs.mkdir(path.join(root, 'data/plugins/AnimatedArtwork_0.1.0.0'), {recursive: true});
  await fs.copyFile(process.env.ARTWORK_PLUGIN_DLL || path.join(project, 'Jellyfin.Plugin.AnimatedArtwork/bin/Release/net10.0/Jellyfin.Plugin.AnimatedArtwork.dll'),
    path.join(root, 'data/plugins/AnimatedArtwork_0.1.0.0/Jellyfin.Plugin.AnimatedArtwork.dll'));
  await fs.writeFile(path.join(root, 'config/network.xml'), `<?xml version="1.0"?><NetworkConfiguration><InternalHttpPort>${port}</InternalHttpPort><PublicHttpPort>${port}</PublicHttpPort><BaseUrl>${basePath}</BaseUrl><EnableIPv6>false</EnableIPv6><EnableRemoteAccess>false</EnableRemoteAccess><AutoDiscovery>false</AutoDiscovery><LocalNetworkAddresses><string>127.0.0.1</string></LocalNetworkAddresses></NetworkConfiguration>`);
  await makeMusic(path.join(root, 'music/Fixture Artist/Fixture Album'), 'Fixture Album');
  await makeMusic(path.join(root, 'other/Fixture Artist/Static Album'), 'Static Album');
  await run('ffmpeg', ['-hide_banner', '-loglevel', 'error', '-f', 'lavfi', '-i', 'testsrc2=s=64x64:r=5:d=1', '-c:v', 'libx264', '-pix_fmt', 'yuv420p', '-an', '-movflags', '+faststart', '-y', path.join(root, 'music/Fixture Artist/Fixture Album/animated-cover.mp4')]);
  await run('ffmpeg', ['-hide_banner', '-loglevel', 'error', '-i', path.join(root, 'music/Fixture Artist/Fixture Album/animated-cover.mp4'), '-loop', '0', '-y', path.join(root, 'music/Fixture Artist/Fixture Album/animated-cover.gif')]);
  const square = await asset('animated-cover.mp4', 64, 64, 1);
  const gif = await asset('animated-cover.gif', 64, 64, 1);

  server = spawn(binary, ['--datadir', path.join(root, 'data'), '--configdir', path.join(root, 'config'), '--cachedir', path.join(root, 'cache'), '--logdir', path.join(root, 'log'), '--nonetchange'], {stdio: ['ignore', 'pipe', 'pipe']});
  server.stdout.pipe(output, {end: false});
  server.stderr.pipe(output, {end: false});
  server.on('error', error => console.error(error.message));
  for (let attempt = 0; ; attempt++) {
    if (server.exitCode !== null) throw new Error(`Test server exited ${server.exitCode}`);
    try { if ((await request('/System/Info/Public', {authenticated: false})).ok) break; } catch {}
    if (attempt >= 60) throw new Error('Test server did not become ready');
    await pause(1000);
  }
  console.log('Isolated Jellyfin server ready');
  const serverInfo = await json('/System/Info/Public');
  const isolatedServerId = (await fs.readFile(path.join(root, 'data/data/device.txt'), 'utf8')).trim();
  assert.equal(serverInfo.Id, isolatedServerId, 'Refusing to configure any server other than the one created by this test');
  await json('/Startup/User');
  await expectStatus('/Startup/User', 204, {method: 'POST', body: {Name: 'artwork-test', Password: password}});
  await expectStatus('/Startup/Complete', 204, {method: 'POST'});
  const auth = await json('/Users/AuthenticateByName', {method: 'POST', body: {Username: 'artwork-test', Pw: password}});
  token = auth.AccessToken;
  assert.ok(token);
  assert.equal((await json('/AnimatedArtwork')).ApiVersion, 1);
  await expectStatus('/AnimatedArtwork', 401, {authenticated: false});
  const configuration = {EnableAnimatedImages: true};
  await expectStatus(`/Plugins/${pluginId}/Configuration`, 204, {method: 'POST', body: configuration});
  for (const [name, folder] of [['Animated', 'music'], ['Static', 'other']]) {
    await expectStatus(`/Library/VirtualFolders?name=${name}&collectionType=music&refreshLibrary=false`, 204, {method: 'POST', body: {LibraryOptions: {
      PathInfos: [{Path: path.join(root, folder)}], EnableRealtimeMonitor: false, EnableLUFSScan: false,
      TypeOptions: ['MusicAlbum', 'MusicArtist', 'Audio'].map(Type => ({Type, MetadataFetchers: [], ImageFetchers: []}))
    }}});
  }
  await expectStatus('/Library/Refresh', 204, {method: 'POST'});
  let items;
  for (let attempt = 0; ; attempt++) {
    items = (await json('/Items?Recursive=true&IncludeItemTypes=MusicAlbum,Audio&Fields=Path')).Items;
    if (items.filter(item => item.Type === 'MusicAlbum').length >= 2) break;
    if (attempt >= 45) throw new Error('Synthetic music library did not scan');
    await pause(1000);
  }
  assert.equal(items.filter(item => item.Type === 'Audio').length, 2, 'Artwork sidecars must not appear as music tracks');
  const album = items.find(item => item.Type === 'MusicAlbum' && item.Name === 'Fixture Album');
  const track = items.find(item => item.Type === 'Audio' && item.Album === 'Fixture Album');
  const staticAlbum = items.find(item => item.Type === 'MusicAlbum' && item.Name === 'Static Album');
  assert.ok(album && track && staticAlbum);
  const route = `/AnimatedArtwork/Items/${album.Id}`;
  const metadata = await json(route);
  assert.equal(metadata.Square.Sha256, square.sha256);
  assert.ok(metadata.Square.Url.startsWith(basePath + '/AnimatedArtwork/'));
  assert.ok(metadata.Tall == null);
  assert.equal((await json(`/AnimatedArtwork/Items/${track.Id}`)).AlbumId.replaceAll('-', ''), album.Id.replaceAll('-', ''));
  await expectStatus(route, 401, {authenticated: false});
  await expectStatus(route + '/square', 401, {authenticated: false});
  await expectStatus(`/AnimatedArtwork/Items/${staticAlbum.Id}`, 404);
  await expectStatus('/AnimatedArtwork/Items/00000000-0000-0000-0000-000000000001', 404);
  await expectStatus(route + '/unknown', 404);
  await expectStatus(route + '/tall', 404);
  const file = await expectStatus(route + '/square', 200);
  assert.equal(file.headers.get('content-type'), 'video/mp4');
  assert.equal(createHash('sha256').update(Buffer.from(await file.arrayBuffer())).digest('hex'), square.sha256);
  const head = await expectStatus(route + '/square', 200, {method: 'HEAD'});
  assert.equal(Number(head.headers.get('content-length')), square.bytes);
  const range = await expectStatus(route + '/square', 206, {extra: {Range: 'bytes=0-31'}});
  assert.equal((await range.arrayBuffer()).byteLength, 32);
  assert.equal(range.headers.get('content-range'), `bytes 0-31/${square.bytes}`);
  await expectStatus(route + '/square', 304, {extra: {'If-None-Match': `"${square.sha256}"`}});
  const image = await expectStatus(`/Items/${album.Id}/Images/Primary?maxWidth=100&format=webp`, 200, {authenticated: false});
  assert.equal(image.headers.get('content-type'), 'image/gif');
  assert.equal(createHash('sha256').update(Buffer.from(await image.arrayBuffer())).digest('hex'), gif.sha256);
  assert.equal((await expectStatus(`/Items/${track.Id}/Images/Primary`, 200)).headers.get('content-type'), 'image/gif');
  assert.equal((await expectStatus(`/Items/${album.Id}/Images/Primary/0`, 200)).headers.get('content-type'), 'image/gif');
  assert.notEqual((await expectStatus(`/Items/${album.Id}/Images/Primary?animated=false`, 200)).headers.get('content-type'), 'image/gif');
  assert.notEqual((await expectStatus(`/Items/${staticAlbum.Id}/Images/Primary`, 200)).headers.get('content-type'), 'image/gif');
  await expectStatus(`/Items/${album.Id}/Images/Primary/1`, 404);
  console.log('Passed: metadata, album/track mapping, authentication, GIF compatibility, fallback, HEAD/range/ETag, base URL');

  const limitedPassword = randomUUID();
  const limited = await json('/Users/New', {method: 'POST', body: {Name: 'restricted-test', Password: limitedPassword}});
  const folders = await json('/Library/VirtualFolders');
  const staticFolder = folders.find(folder => folder.Name === 'Static');
  assert.ok(staticFolder.ItemId);
  await expectStatus(`/Users/${limited.Id}/Policy`, 204, {method: 'POST', body: {...limited.Policy, IsAdministrator: false, EnableAllFolders: false, EnabledFolders: [staticFolder.ItemId]}});
  const limitedAuth = await json('/Users/AuthenticateByName', {method: 'POST', body: {Username: 'restricted-test', Pw: limitedPassword}});
  const adminToken = token;
  token = limitedAuth.AccessToken;
  await expectStatus(route, 404);
  await expectStatus(route + '/square', 404);
  await expectStatus(`/Items/${album.Id}/Images/Primary`, 404);
  token = adminToken;
  await expectStatus(`/Plugins/${pluginId}/Configuration`, 204, {method: 'POST', body: {...configuration, EnableAnimatedImages: false}});
  assert.notEqual((await expectStatus(`/Items/${album.Id}/Images/Primary`, 200)).headers.get('content-type'), 'image/gif');
  await expectStatus(route + '/square', 200);
  console.log('Passed: restricted library access and compatibility toggle with MP4 API retained');
  await expectStatus(`/Plugins/${pluginId}/Configuration`, 204, {method: 'POST', body: configuration});
  await fs.writeFile(path.join(root, 'result.json'), JSON.stringify({server: base, albumId: album.Id, userId: auth.User.Id, passed: true}, null, 2));
  if (process.env.KEEP_TEST_SERVER === '1') {
    await fs.writeFile(path.join(root, 'browser-login.json'), JSON.stringify({username: 'artwork-test', password}), {mode: 0o600});
    console.log(`Smoke tests passed. Test server retained for browser verification: ${root}`);
    await new Promise(resolve => {process.once('SIGINT', resolve); process.once('SIGTERM', resolve);});
  } else console.log(`All integration checks passed. Logs: ${root}`);
} catch (error) {
  console.error(`${error.stack}\nServer log: ${root}/server.log`);
  process.exitCode = 1;
} finally {
  if (server && server.exitCode === null) {
    server.kill('SIGTERM');
    await Promise.race([new Promise(resolve => server.once('exit', resolve)), pause(5000)]);
    if (server.exitCode === null) server.kill('SIGKILL');
  }
  output.end();
}
