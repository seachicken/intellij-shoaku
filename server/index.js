import { spawn } from 'node:child_process';

const appServer = spawn('codex', ['app-server'], {
  stdio: ['pipe', 'pipe', 'inherit'],
  env: {
    ...process.env,
    RUST_LOG: 'debug',
    LOG_FORMAT: 'json'
  }
});
appServer.stdout.pipe(process.stdout);
let buffer = Buffer.alloc(0);

process.stdin.on('data', (chunk) => {
  buffer = Buffer.concat([buffer, chunk]);

  while (true) {
    const headerEnd = buffer.indexOf(Buffer.from('\r\n\r\n'));
    if (headerEnd === -1) break;

    const header = buffer.slice(0, headerEnd).toString('ascii');
    // Read "Content-Length: x" header to determine body length
    const contentLength = parseInt(header.substring(16))
    const bodyStart = headerEnd + 4;
    if (buffer.length < bodyStart + contentLength) break;

    const body = buffer.slice(bodyStart, bodyStart + contentLength).toString('utf-8');
    const raw = buffer.slice(0, bodyStart + contentLength);
    buffer = buffer.slice(bodyStart + contentLength);

    let message;
    try {
      message = JSON.parse(body);
    } catch {
      appServer.stdin.write(raw);
      continue;
    }

    if (message.method === 'initialize') {
      const result = {
        capabilities: {}
      };
      printResponse(message.id, result);
    } else {
      appServer.stdin.write(raw);
    }
  }
});

function printResponse(id, result) {
  const resBody = JSON.stringify({
    jsonrpc: '2.0',
    id,
    result
  });
  process.stdout.write(`Content-Length: ${Buffer.byteLength(resBody)}\r\n\r\n${resBody}`);
}

process.stdin.on('end', () => {
  appServer.stdin.end();
});
