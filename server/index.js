import { spawn } from 'node:child_process';
import { pathToFileURL } from "url";

const appServer = spawn('codex', ['app-server'], {
  stdio: ['pipe', 'pipe', 'pipe'],
  env: {
    ...process.env,
    RUST_LOG: 'debug'
  }
});

let initializeParams;
let threadId;

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
      appServer.stdin.write(JSON.stringify(buildAppRequest("thread/start", {
        cwd: initializeParams.rootPath
      })) + '\n');
    } else if (message.id === 1) {
      threadId = message.result.thread.id;

      (async () => await sendAppRequest("thread/inject_items", {
        threadId,
        items: [
          {
            type: "message",
            role: "assistant",
            content: [
              {
                type: "output_text",
                text: "You are a developer's assistant. You will help users by breaking down tasks to a level where they can understand and grasp what they want to achieve, and by guiding them through pair programming."
              }
            ]
          }
        ]
      }))();

      appServer.stdin.write(JSON.stringify(buildAppRequest("fs/watch", {
        watchId: initializeParams.initializationOptions.filePath,
        path: initializeParams.initializationOptions.filePath
      })) + '\n');
    } else if (message.id != null && pendingRequests.has(message.id)) {
      const { resolve, reject } = pendingRequests.get(message.id);
      pendingRequests.delete(message.id);
      if (message.error) {
        reject(message.error);
      } else {
        resolve(message.result);
      }
    } else if (message.params?.turnId != null && pendingTurns.has(message.params.turnId)) {
      const { callbacks } = pendingTurns.get(message.params.turnId);
      if (message.method === 'item/completed') {
        callbacks?.onItemCompleted(message.params)
      }
    } else if (message.params?.turn != null && pendingTurns.has(message.params.turn.id)) {
      const { resolve, reject } = pendingTurns.get(message.params.turn.id);
      if (message.method === 'turn/completed') {
        pendingTurns.delete(message.params.turn.id);
        if (message.error) {
          reject(message.error);
        } else {
          resolve(message.result);
        }
      }
    }

    if (initializeParams.initializationOptions?.filePath) {
      if (message.params?.watchId === initializeParams.initializationOptions.filePath && threadId) {
        handleFileChange(initializeParams.initializationOptions.filePath, threadId);
      }
    }

    logWarn(`Received message from app server: ${JSON.stringify(message)}, pendingRequests: ${[...pendingRequests.keys()]}, pendingTurns: ${[...pendingTurns.keys()]}`);
  }
});

async function handleFileChange(filePath, threadId) {
  const fileResult = await sendAppRequest("fs/readFile", {
    path: initializeParams.initializationOptions.filePath
  });
  if (!fileResult.dataBase64) {
    return;
  }

  await startTurn({
    threadId,
    input: [
      {
        type: "text",
        text: `User inputs:\n${Buffer.from(fileResult.dataBase64, 'base64').toString('utf-8')}`
      }
    ]}, {
      onItemCompleted: async (params) => {
        if (!params?.item?.text) {
          return;
        }
      }
    }
  );
}

let appErrBuf = Buffer.alloc(0);
appServer.stderr.on('data', (chunk) => {
  appErrBuf = Buffer.concat([appErrBuf, chunk]);

  let newlineIdx;
  while ((newlineIdx = appErrBuf.indexOf('\n')) !== -1) {
    const line = appErrBuf.slice(0, newlineIdx).toString('utf-8');
    appErrBuf = appErrBuf.slice(newlineIdx + 1);

    // Remove ANSI escape codes
    logInfo(line.replace(/\x1b\[[0-9;]*m/g, ''))
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
      initializeParams = message.params;

      appServer.stdin.write(JSON.stringify(buildAppRequest("initialize", {
        clientInfo: {
          name: "shoaku_intellij",
          title: "Shoaku for IntelliJ",
          version: "0.1.0"
        }
      })) + '\n');

      process.stdout.write(buildResponse(message.id, {
        capabilities: {}
      }));
    }

    logWarn(`Received message from language client: ${JSON.stringify(message)}`)
  }
});

process.stdin.on('end', () => {
  appServer.stdin.end();
});

function logInfo(message) {
  process.stdout.write(
    buildNotification("window/logMessage", {
      type: 3,
      message
    })
  );
}

function logWarn(message) {
  process.stdout.write(
    buildNotification("window/logMessage", {
      type: 2,
      message
    })
  );
}

function logError(message) {
  process.stdout.write(
    buildNotification("window/showMessage", {
      type: 1,
      message
    })
  );
}

const pendingTurns = new Map();
async function startTurn(params, callbacks) {
  const result = await sendAppRequest('turn/start', params);
  return new Promise((resolve, reject) => {
    pendingTurns.set(result.turn.id, { resolve, reject, callbacks });
  });
}

const pendingRequests = new Map();
function sendAppRequest(method, params) {
  const req = buildAppRequest(method, params);
  return new Promise((resolve, reject) => {
    pendingRequests.set(req.id, { resolve, reject });
    appServer.stdin.write(JSON.stringify(req) + '\n');
  });
}

let appParamId = 0;
function buildAppRequest(method, params) {
  return {
    jsonrpc: '2.0',
    id: appParamId++,
    method,
    params
  };
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
