#!/usr/bin/env node
/**
 * OpenMesh host test server (P1 lab only).
 *
 * The emulator reaches the host at 10.0.2.2. The debug build allows exactly
 * 10.0.2.2:8080 through the loopback SOCKS frontend; every other private
 * destination stays blocked.
 *
 *   node tools/test-server.mjs
 *
 * Endpoints:
 *   GET /health             small JSON health response
 *   GET /bytes?kb=256       deterministic payload of N kilobytes (max 8192)
 *   GET /slow?ms=500        delayed JSON response
 *   POST /echo              echoes a bounded request body
 */
import http from 'node:http';
import os from 'node:os';

const HOST = process.env.OPENMESH_TEST_HOST ?? '0.0.0.0';
const PORT = Number(process.env.OPENMESH_TEST_PORT ?? 8080);
const MAX_BYTES_KB = 8192;

const startedAt = new Date();
const master = Buffer.alloc(1024 * 1024, 0x4f); // 'O'

function timestamp() {
  return new Date().toISOString();
}

function log(message) {
  process.stdout.write(`[${timestamp()}] ${message}\n`);
}

function sendJson(res, status, body) {
  const payload = Buffer.from(JSON.stringify(body, null, 2));
  res.writeHead(status, {
    'content-type': 'application/json; charset=utf-8',
    'content-length': payload.length,
    'cache-control': 'no-store',
  });
  res.end(payload);
  return payload.length;
}

const server = http.createServer((req, res) => {
  const requestStarted = Date.now();
  const url = new URL(req.url ?? '/', `http://${req.headers.host ?? 'localhost'}`);
  let responseBytes = 0;

  const requestMeta = {
    method: req.method,
    path: url.pathname + url.search,
    remote: `${req.socket.remoteAddress}:${req.socket.remotePort}`,
    client: req.headers['user-agent'] ?? '',
    marker: req.headers['x-openmesh-test'] ?? '',
  };

  res.on('finish', () => {
    log(
      `${requestMeta.method} ${requestMeta.path} -> ${res.statusCode} ` +
        `${responseBytes} B in ${Date.now() - requestStarted} ms ` +
        `from ${requestMeta.remote} ts=${requestMeta.marker}`,
    );
  });

  res.on('close', () => {
    if (!res.writableFinished) {
      log(`${requestMeta.method} ${requestMeta.path} aborted after ${Date.now() - requestStarted} ms`);
    }
  });

  if (req.method === 'GET' && url.pathname === '/health') {
    responseBytes = sendJson(res, 200, {
      service: 'openmesh-test-server',
      status: 'ok',
      serverTime: timestamp(),
      startedAt: startedAt.toISOString(),
      pid: process.pid,
      host: os.hostname(),
      marker: requestMeta.marker || null,
    });
    return;
  }

  if (req.method === 'GET' && url.pathname === '/bytes') {
    const kb = Math.max(1, Math.min(MAX_BYTES_KB, Number(url.searchParams.get('kb') ?? 256) || 256));
    const total = kb * 1024;
    res.writeHead(200, {
      'content-type': 'application/octet-stream',
      'content-length': total,
      'cache-control': 'no-store',
      'x-openmesh-bytes': String(total),
    });
    let remaining = total;
    while (remaining > 0) {
      const chunk = Math.min(remaining, master.length);
      res.write(master.subarray(0, chunk));
      responseBytes += chunk;
      remaining -= chunk;
    }
    res.end();
    return;
  }

  if (req.method === 'GET' && url.pathname === '/slow') {
    const ms = Math.max(0, Math.min(30_000, Number(url.searchParams.get('ms') ?? 500) || 0));
    setTimeout(() => {
      if (res.destroyed) return;
      responseBytes = sendJson(res, 200, {
        status: 'ok',
        delayedMs: ms,
        serverTime: timestamp(),
      });
    }, ms);
    return;
  }

  if (req.method === 'POST' && url.pathname === '/echo') {
    const chunks = [];
    let size = 0;
    req.on('data', (chunk) => {
      size += chunk.length;
      if (size <= 1024 * 1024) chunks.push(chunk);
    });
    req.on('end', () => {
      if (size > 1024 * 1024) {
        responseBytes = sendJson(res, 413, { error: 'request body exceeds 1 MiB' });
        return;
      }
      const body = Buffer.concat(chunks).toString('utf8');
      responseBytes = sendJson(res, 200, {
        status: 'ok',
        bytesReceived: size,
        body,
      });
    });
    return;
  }

  responseBytes = sendJson(res, 404, {
    error: 'not found',
    endpoints: ['/health', '/bytes?kb=256', '/slow?ms=500', 'POST /echo'],
  });
});

server.on('error', (error) => {
  log(`server error: ${error.message}`);
  process.exitCode = 1;
});

server.listen(PORT, HOST, () => {
  log(`OpenMesh test server listening on http://${HOST}:${PORT}`);
  log(`Android emulator URL: http://10.0.2.2:${PORT}/health`);
  log('Stop with Ctrl+C.');
});

function shutdown(signal) {
  log(`${signal}: shutting down`);
  server.close(() => process.exit(0));
  setTimeout(() => process.exit(1), 3000).unref();
}

process.on('SIGINT', () => shutdown('SIGINT'));
process.on('SIGTERM', () => shutdown('SIGTERM'));
