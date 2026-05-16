import { spawn } from 'node:child_process';
import parser from './parser.js';

const appServer = spawn('codex', ['app-server'], {
  stdio: ['pipe', 'pipe', 'pipe'],
  env: {
    ...process.env,
    RUST_LOG: 'debug'
  }
});

let initializeParams;
let threadId;
let lists = [];

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

      (async () => {
        await sendAppRequest('thread/inject_items', {
          threadId,
          items: [
            {
              type: 'message',
              role: 'developer',
              content: [
                {
                  type: 'input_text',
                  text: "You are a developer's assistant. You will help users by breaking down tasks to a level where they can understand and grasp what they want to achieve, and by guiding them through pair programming. `checked: true` is treated just as context because the objective has been achieved. Use the `git diff` input as context to understand the state of the pair programming driver. Your response will prioritize the language the user entered."
                }
              ]
            }
          ]
        });

        if (initializeParams.initializationOptions?.filePath) {
          await syncShoakuLists(initializeParams.initializationOptions.filePath, threadId);
        }
      })();

      appServer.stdin.write(JSON.stringify(buildAppRequest("fs/watch", {
        watchId: initializeParams.initializationOptions.filePath,
        path: initializeParams.initializationOptions.filePath
      })) + '\n');
    }

    if (message.id != null && pendingRequests.has(message.id)) {
      const { resolve, reject } = pendingRequests.get(message.id);
      pendingRequests.delete(message.id);
      if (message.error) {
        reject(message.error);
      } else {
        resolve(message);
      }
    }

    if (message.result?.turn?.id != null && pendingTurns.has(message.result.turn.id)) {
      logWarn(`status changed.`)
      const { id, callbacks } = pendingTurns.get(message.result.turn.id);
      callbacks?.onItemStatusChanged(id, message.result.turn.status)
    }

    if (message.params?.turnId != null && pendingTurns.has(message.params.turnId)) {
      const { id, callbacks } = pendingTurns.get(message.params.turnId);
      if (message.method === 'item/completed') {
        callbacks?.onItemCompleted(id, message.params)
      }
    }

    if (message.params?.turn != null && pendingTurns.has(message.params.turn.id)) {
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
        (async () => {
          await syncShoakuLists(initializeParams.initializationOptions.filePath, threadId);
        })();
      }
    }

    if (message.method !== 'item/agentMessage/delta') {
      logWarn(`Received message from app server: ${JSON.stringify(message)}, pendingRequests: ${[...pendingRequests.keys()]}, pendingTurns: ${[...pendingTurns.keys()]}`);
    }
  }
});

async function syncShoakuLists(filePath, threadId) {
  const fileMsg = await sendAppRequest("fs/readFile", {
    path: initializeParams.initializationOptions.filePath
  });
  lists = parser.parse(Buffer.from(fileMsg.result.dataBase64, 'base64').toString('utf-8'));
  process.stdout.write(
    buildNotification("shoaku/notification", {
      id: 0,
      lists
    })
  );

  await startTurn({
    threadId,
    input: [
      {
        type: "text",
        text: `User inputs:\n${JSON.stringify(lists)}}`
      }
    ]}, {
      onItemStatusChanged: async (id, status) => {
        process.stdout.write(
          buildNotification("shoaku/notification", {
            id,
            lists: [{
              status
            }]
          })
        );
      },
      onItemCompleted: async (id, params) => {
        const activeItem = findActiveItem(lists);
        if (activeItem) {
          activeItem.response = params.item.text;
        }
        process.stdout.write(
          buildNotification("shoaku/notification", {
            id,
            lists
          })
        );
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

    switch (message.method) {
      case 'initialize':
        initializeParams = message.params;

        appServer.stdin.write(JSON.stringify(buildAppRequest("initialize", {
          clientInfo: {
            name: "shoaku_intellij",
            title: "Shoaku for IntelliJ",
            version: "0.1.0"
          }
        })) + '\n');

        process.stdout.write(buildResponse(message.id, {
          capabilities: {
            textDocumentSync: {
              change: 2,
              save: true
            }
          }
        }));
        break;

      case 'textDocument/didChange':
        const activeItem = findActiveItem(lists);
        if (threadId != null && activeItem) {
          appServer.stdin.write(JSON.stringify(buildAppRequest('turn/steer', {
            threadId,
            input: [
              {
                type: 'text',
                text: `Users want to achieve "${activeItem.content}". Current edit file location: ${JSON.stringify(message)}`
              }
            ],
            expectedTurnId: pendingTurns.keys().next().value
          })) + '\n');
        }
        break;

      case 'shoaku/reply':
        if (threadId != null) {
          const text = message.params?.text ?? message.text;
          (async () => {
            await startTurn({
              threadId,
              input: [
                {
                  type: 'text',
                  text
                }
              ]}, {
                onItemCompleted: async (id, params) => {
                  const activeItem = findActiveItem(lists);
                  if (activeItem) {
                    activeItem.response = params.item.text;
                  }
                  process.stdout.write(
                    buildNotification("shoaku/notification", {
                      id,
                      lists
                    })
                  );
                }
              }
            );
          })();
        }
        break;
    }

    logWarn(`Received message from language client: ${JSON.stringify(message)}`)
  }
});

process.stdin.on('end', () => {
  appServer.stdin.end();
});

function findActiveItem(lists, root = true) {
  if (!lists) {
    return null;
  }

  for (const item of lists) {
    if (!root && item.checked === false) {
      return item;
    }

    const found = findActiveItem(item.children, false);
    if (found) {
      return found;
    }
  }

  return null;
}

const pendingTurns = new Map();
async function startTurn(params, callbacks) {
  if (pendingTurns.size > 0) {
    return;
  }

  const msg = await sendAppRequest('turn/start', params);

  await sendAppRequest('thread/shellCommand', {
    threadId,
    command: 'git diff --unified=0'
  });

  return new Promise((resolve, reject) => {
    pendingTurns.set(msg.result.turn.id, { id: msg.id, resolve, reject, callbacks });
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
