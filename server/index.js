import { spawn } from 'node:child_process';

const appServer = spawn('codex', ['app-server'], {
  stdio: ['pipe', 'pipe', 'pipe'],
  env: {
    ...process.env,
    RUST_LOG: 'debug'
  }
});

let appBuf = Buffer.alloc(0);
appServer.stdout.on('data', (chunk) => {
  appBuf = Buffer.concat([appBuf, chunk]);

  let newlineIdx;
  while ((newlineIdx = appBuf.indexOf('\n')) !== -1) {
    const line = appBuf.slice(0, newlineIdx).toString('utf-8');
    appBuf = appBuf.slice(newlineIdx + 1);

    let message;
    try {
      message = JSON.parse(line);
    } catch {
      logError(`Received non-JSON message from app server: ${line}`);
      continue;
    }

    if (message.id === 0) {
      appServer.stdin.write(buildAppRequest("thread/start", {}));
    } else if (message.id === 1) {
      appServer.stdin.write(buildAppRequest("turn/start", {
        threadId: message.result.thread.id,
        input: [
          {
            type: "text",
            text: "Hello world"
          }
        ]
      }));
    }

    logError(`Received message from app server: ${JSON.stringify(message)}`)
  }
});

let appErrBuf = Buffer.alloc(0);
appServer.stderr.on('data', (chunk) => {
  appErrBuf = Buffer.concat([appErrBuf, chunk]);

  let newlineIdx;
  while ((newlineIdx = appErrBuf.indexOf('\n')) !== -1) {
    const line = appErrBuf.slice(0, newlineIdx).toString('utf-8');
    appErrBuf = appErrBuf.slice(newlineIdx + 1);

    // Remove ANSI escape codes
    logError(line.replace(/\x1b\[[0-9;]*m/g, ''))
  }
});

let buf = Buffer.alloc(0);
process.stdin.on('data', (chunk) => {
  buf = Buffer.concat([buf, chunk]);

  while (true) {
    const headerEnd = buf.indexOf(Buffer.from('\r\n\r\n'));
    if (headerEnd === -1) break;

    const header = buf.slice(0, headerEnd).toString('ascii');
    // Read "Content-Length: x" header to determine body length
    const contentLength = parseInt(header.substring(16))
    const bodyStart = headerEnd + 4;
    if (buf.length < bodyStart + contentLength) break;

    const body = buf.slice(bodyStart, bodyStart + contentLength).toString('utf-8');
    buf = buf.slice(bodyStart + contentLength);

    let message;
    try {
      message = JSON.parse(body);
    } catch {
      logError(`Received non-JSON message from language client: ${body}`);
      continue;
    }

    if (message.method === 'initialize') {
      const params = {
        clientInfo: {
          name: "shoaku_intellij",
          title: "Shoaku for IntelliJ",
          version: "0.1.0"
        }
      };
      appServer.stdin.write(buildAppRequest("initialize", params));

      const result = {
        capabilities: {}
      };
      process.stdout.write(buildResponse(message.id, result));
    } else {
    }
  }
});

process.stdin.on('end', () => {
  appServer.stdin.end();
});

function logError(message) {
  process.stdout.write(
    buildNotification("window/logMessage", {
      type: 1,
      message
    })
  );
}

let appParamId = 0;
function buildAppRequest(method, params) {
  const body = JSON.stringify({
    jsonrpc: '2.0',
    id: appParamId++,
    method,
    params
  });
  return `${body}\n`
}

function buildNotification(method, params) {
  const body = JSON.stringify({
    jsonrpc: '2.0',
    method,
    params
  });
  return `Content-Length: ${Buffer.byteLength(body)}\r\n\r\n${body}`;
}

function buildResponse(id, result) {
  const body = JSON.stringify({
    jsonrpc: '2.0',
    id,
    result
  });
  return `Content-Length: ${Buffer.byteLength(body)}\r\n\r\n${body}`;
}
