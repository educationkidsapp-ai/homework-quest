#!/usr/bin/env node
/**
 * Serves the built dashboard the way the API container will (P3.4), so the e2e suite exercises
 * the real bundle rather than the dev server.
 *
 *   HQ_API=http://localhost:18080 PORT=4300 node e2e/local/serve.mjs
 *
 * Two jobs:
 *   - `/dashboard/**` → `dist/browser`, with the SPA fallback to `index.html` for any path that
 *     is not a file (so `/dashboard/sign-in` and `/dashboard/accept-invite?token=…` reload),
 *     hashed assets immutable and `index.html` no-cache;
 *   - everything else → the API, unchanged, headers and status included — including the
 *     WebSocket upgrade on `/ws/chat`, which the container's own proxy passes through and
 *     which E3 made every dashboard role open (D26). Without it Chrome logs a failed handshake
 *     on every screen and T4's console gate fails the whole suite.
 *
 * It also sets the **same Content-Security-Policy the API will** (backend PR #50), so the suite
 * fails here rather than in QA if the bundle ever needs an inline script or an off-origin
 * fetch. That policy is why `optimization.styles.inlineCritical` is off: Angular's critical-CSS
 * path ships a `<link … onload="…">` handler, which `script-src 'self'` blocks, and the page
 * then renders with no stylesheet at all.
 *
 * The proxy is what makes `environment.apiBaseUrl = ''` true locally: the dashboard and the API
 * are one origin here exactly as they are in the container, so no CORS, no dev-only base URL,
 * and `X-School-Id` and `Authorization` travel as they will in QA.
 */
import { createGzip } from 'node:zlib';
import { createReadStream, existsSync, statSync } from 'node:fs';
import { createServer } from 'node:http';
import { connect } from 'node:net';
import { extname, join, normalize, resolve } from 'node:path';
import { dirname } from 'node:path';
import { fileURLToPath } from 'node:url';

const here = dirname(fileURLToPath(import.meta.url));
const DIST = resolve(here, '../../dist/browser');
const BASE = '/dashboard/';
const API = process.env['HQ_API'] ?? 'http://localhost:18080';
const PORT = Number(process.env['PORT'] ?? 4300);

/** Byte-for-byte the policy the API serves `/dashboard/**` with (backend PR #50). */
const CSP =
  "default-src 'self'; img-src 'self' https: data:; style-src 'self' 'unsafe-inline'; connect-src 'self'";

const TYPES = {
  '.html': 'text/html; charset=utf-8',
  '.js': 'text/javascript; charset=utf-8',
  '.css': 'text/css; charset=utf-8',
  '.json': 'application/json; charset=utf-8',
  '.woff2': 'font/woff2',
  '.ico': 'image/x-icon',
  '.svg': 'image/svg+xml',
  '.png': 'image/png',
  '.txt': 'text/plain; charset=utf-8',
};

if (!existsSync(DIST)) {
  process.stderr.write(`serve — ${DIST} does not exist. Run \`pnpm build\` first.\n`);
  process.exit(1);
}

/** Text the container's server compresses; measuring without it would flatter nothing. */
const COMPRESSIBLE = new Set(['.html', '.js', '.css', '.json', '.svg', '.txt']);

const server = createServer((request, response) => {
  const url = new URL(request.url ?? '/', 'http://localhost');
  if (url.pathname === '/dashboard') {
    response.writeHead(302, { location: BASE }).end();
    return;
  }
  if (url.pathname.startsWith(BASE)) serveFile(url.pathname.slice(BASE.length), request, response);
  else void proxy(request, response, url);
});

/**
 * The WebSocket upgrade, tunnelled rather than proxied.
 *
 * `fetch` cannot carry an upgrade, so the socket is spliced: the upgrade request is replayed on
 * a raw connection to the API and the two sockets are piped together from the API's `101`
 * onwards. That is what Cloud Run does in front of the container, and what `/ws/chat` needs to
 * behave here the way it behaves in QA.
 */
server.on('upgrade', (request, socket, head) => {
  const upstreamUrl = new URL(API);
  const upstream = connect(
    { host: upstreamUrl.hostname, port: Number(upstreamUrl.port || 80) },
    () => {
      const lines = [`${request.method} ${request.url} HTTP/1.1`];
      for (const [name, value] of Object.entries(request.headers)) {
        if (name === 'host') lines.push(`host: ${upstreamUrl.host}`);
        else for (const one of Array.isArray(value) ? value : [value]) lines.push(`${name}: ${one}`);
      }
      upstream.write(`${lines.join('\r\n')}\r\n\r\n`);
      if (head?.length) upstream.write(head);
      upstream.pipe(socket);
      socket.pipe(upstream);
    },
  );
  const drop = () => {
    upstream.destroy();
    socket.destroy();
  };
  upstream.on('error', drop);
  socket.on('error', drop);
});

server.listen(PORT, () =>
  process.stdout.write(`serve — ${BASE} from dist/browser, everything else → ${API}\n`),
);

function serveFile(relative, request, response) {
  // `normalize` then reject anything that climbs out: a path is a filename here, never a route.
  const safe = normalize(relative).replace(/^(\.\.[/\\])+/, '');
  const candidate = safe === '' ? 'index.html' : safe;
  const path = join(DIST, candidate);
  const isFile = existsSync(path) && statSync(path).isFile();
  const file = isFile ? path : join(DIST, 'index.html');
  const type = TYPES[extname(file)] ?? 'application/octet-stream';

  const gzip = COMPRESSIBLE.has(extname(file)) && /\bgzip\b/.test(request.headers['accept-encoding'] ?? '');

  response.writeHead(200, {
    'content-type': type,
    'content-security-policy': CSP,
    // Hashed filenames may be cached for ever; index.html must never be, or a deploy is invisible.
    'cache-control': file.endsWith('index.html') ? 'no-cache' : 'public, max-age=31536000, immutable',
    ...(gzip ? { 'content-encoding': 'gzip', vary: 'accept-encoding' } : {}),
  });

  const source = createReadStream(file);
  if (gzip) source.pipe(createGzip()).pipe(response);
  else source.pipe(response);
}

async function proxy(request, response, url) {
  const body = request.method === 'GET' || request.method === 'HEAD' ? undefined : await read(request);
  const headers = { ...request.headers };
  delete headers.host;
  delete headers.connection;

  try {
    const upstream = await fetch(API + url.pathname + url.search, {
      method: request.method,
      headers,
      body,
      redirect: 'manual',
    });
    const out = Object.fromEntries(upstream.headers.entries());
    delete out['content-encoding'];
    delete out['content-length'];
    delete out['transfer-encoding'];
    response.writeHead(upstream.status, out);
    response.end(Buffer.from(await upstream.arrayBuffer()));
  } catch (error) {
    response.writeHead(502, { 'content-type': 'application/json' });
    response.end(JSON.stringify({ code: 'bad_gateway', message: String(error) }));
  }
}

function read(request) {
  return new Promise((done) => {
    const chunks = [];
    request.on('data', (chunk) => chunks.push(chunk));
    request.on('end', () => done(Buffer.concat(chunks)));
  });
}
