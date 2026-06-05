import fs, { mkdtemp } from 'node:fs/promises';
import { join } from 'node:path';
import { promisify } from 'node:util';
import child_process, { spawn } from 'node:child_process';
import { tmpdir } from 'node:os';
import parser from './parser.js';

const exec = promisify(child_process.exec);
const appServer = spawn('codex', ['app-server'], {
  stdio: ['pipe', 'pipe', 'pipe'],
  env: {
    ...process.env,
    RUST_LOG: 'debug'
  }
});

let initializeParams;
let workDir;
let navigatorThreadId = '';
let explorerThreadId = '';
let activeParentItem;
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
    const logPrefix = message.params?.threadId === navigatorThreadId ? '[navigator] ' : message.params?.threadId === explorerThreadId ? '[explorer] ' : '';
    logInfo(`${logPrefix}AS-> ${JSON.stringify(message)}, pendingRequests: ${[...pendingRequests.keys()]}, pendingTurns: ${[...pendingTurns.keys()]}`);

    if (message.id === 0) {
      (async () => {
        syncShoakuLists(initializeParams.initializationOptions.filePath);

        const [navigatorRes, explorerRes] = await Promise.all([
          sendAppRequest('thread/start', {
            cwd: initializeParams.rootPath,
            approvalPolicy: 'never',
            sandbox: 'read-only'
          }),
          (async () => {
            workDir = await mkdtemp(join(tmpdir(), 'shoaku-'));
            logWarn(`Created temporary work directory: ${workDir}`)
            await exec(`git -C ${initializeParams.rootPath} worktree add ${workDir}`);
            return sendAppRequest('thread/start', {
              cwd: workDir,
              approvalPolicy: 'on-request',
              sandbox: 'workspace-write'
            })
          })()
        ]);
        navigatorThreadId = navigatorRes.result.thread.id;
        explorerThreadId = explorerRes.result.thread.id;

        await Promise.all([
          sendAppRequest('thread/inject_items', {
            threadId: navigatorThreadId,
            items: [
              {
                type: 'message',
                role: 'developer',
                content: [
                  {
                    type: 'input_text',
                    text: `
                    You are an assistant that helps developers understand and progress their work.

                    Responsibilities:
                    - Understand the user's overall goals and short-term tasks from their TODO list.
                    - Use LSP events to observe user actions.
                    - Provide step-by-step guidance and sample code showing what the user should do next.
                    `
                  }
                ]
              }
            ]
          }),
          sendAppRequest('thread/inject_items', {
            threadId: explorerThreadId,
            items: [
              {
                type: 'message',
                role: 'developer',
                content: [
                  {
                    type: 'input_text',
                    text: `
                    You can understand what the developer wants to achieve and implement it autonomously.

                    Responsibilities:
                    - Understand the user's overall goals and short-term tasks from their TODO list.
                    - Independently generate code to achieve the user's goals.
                    `
                  }
                ]
              }
            ]
          })
        ]);

        activeParentItem = findActiveParentItem(lists);
        const childItem = findActiveItem(activeParentItem?.children ?? []);
        logWarn(`Parent item: ${activeParentItem?.content}, child item: ${childItem?.content}`)

        await startTurn({
          threadId: navigatorThreadId,
          input: [
            {
              type: "text",
              text: `My goal is ${activeParentItem?.content}, and in the short term, I want to solve ${childItem?.content}.\nUser To Do List:\n${JSON.stringify(lists)}`
            }
          ]}, {
            onItemCompleted: async (id, params) => {
              process.stdout.write(
                buildNotification("shoaku/notification", {
                  lists,
                  response: params.item.text
                })
              );
            }
          }
        );
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
      if (message.method === 'item/completed' && message.params.item.type === 'agentMessage') {
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
      if (message.params?.watchId === initializeParams.initializationOptions.filePath && navigatorThreadId) {
        (async () => {
          syncShoakuLists(initializeParams.initializationOptions.filePath);

          for (const item of lists) {
            if (item.type === activeParentItem?.type && item.content === activeParentItem?.content && item.checked) {
              await startTurn({
                threadId: navigatorThreadId,
                input: [
                  {
                    type: "text",
                    text: `Regarding ${activeParentItem?.content}, summarize only the conversations that will be useful in the future, listing them as bullet points.`
                  }
                ]}, {
                  onItemCompleted: async (id, params) => {
                    process.stdout.write(
                      buildNotification("shoaku/showDiff", {
                        response: params.item.text
                      })
                    );
                  }
                }
              );
              break;
            }
          }
        })();
      }
    }
  }
});

async function syncShoakuLists(filePath) {
  const content = await fs.readFile(initializeParams.initializationOptions.filePath, { encoding: 'utf8' });
  lists = parser.parse(content);
  process.stdout.write(
    buildNotification("shoaku/notification", {
      lists,
      response: "..."
    })
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
    // Read "Content-Length: ..." header to determine body length
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
    logInfo(`LC-> ${JSON.stringify(message)}`)

    switch (message.method) {
      case 'initialize':
        initializeParams = message.params;

        appServer.stdin.write(JSON.stringify(buildAppRequest("initialize", {
          clientInfo: {
            name: "shoaku_intellij",
            title: "Shoaku for IntelliJ",
            version: "0.1.0"
          },
          capabilities: {
            optOutNotificationMethods: ['item/agentMessage/delta']
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

      case 'shutdown':
        spawn('git', ['-C', initializeParams.rootPath, 'worktree', 'remove', workDir]);
        workDir = null;
        process.stdout.write(buildResponse(message.id, null));
        break;

      case 'textDocument/didSave':
        if (navigatorThreadId) {
          sendAppRequest('thread/inject_items', {
            threadId: navigatorThreadId,
            items: [
              {
                type: 'message',
                role: 'user',
                content: [
                  {
                    type: 'input_text',
                    text: JSON.stringify(message)
                  }
                ]
              }
            ],
          });
        }
        break;

      case 'shoaku/reply':
        if (navigatorThreadId) {
          const text = message.params?.text ?? message.text;
          (async () => {
            await startTurn({
              threadId: navigatorThreadId,
              input: [
                {
                  type: 'text',
                  text
                }
              ]}, {
                onItemCompleted: async (id, params) => {
                  process.stdout.write(
                    buildNotification("shoaku/notification", {
                      lists,
                      response: params.item.text
                    })
                  );
                }
              }
            );
          })();
        }
        break;
    }
  }
});

process.stdin.on('end', () => {
  appServer.stdin.end();
});

function findActiveParentItem(lists) {
  if (!lists) {
    return null;
  }

  for (const item of lists) {
    if (item.checked === false) {
      return item;
    }
  }

  return null;
}

function findActiveItem(lists) {
  if (!lists) {
    return null;
  }

  for (const item of lists) {
    if (item.checked === false) {
      return item;
    }

    const found = findActiveItem(item.children);
    if (found) {
      return found;
    }
  }

  return null;
}

const pendingTurns = new Map();
async function startTurn(params, callbacks) {
  for (const turnId of pendingTurns.keys()) {
    await sendAppRequest('turn/interrupt', {
      threadId: navigatorThreadId,
      turnId
    });
  }

  const msg = await sendAppRequest('turn/start', params);

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
