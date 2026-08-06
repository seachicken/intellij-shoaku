#!/usr/bin/env node

import { homedir } from 'node:os';
import { mkdir, mkdtemp, readFile, watch, writeFile } from 'node:fs/promises';
import { basename, join } from 'node:path';
import { promisify } from 'node:util';
import child_process, { spawn } from 'node:child_process';
import { tmpdir } from 'node:os';
import YAML from 'yaml';
import AgentInputBuilder, { inputType } from './agent-input-builder.js';
import { renderSummary, renderSummaryDiff } from './diff-snippet.js';
import { cleanupStaleBranches } from './git.js';
import parser from './parser.js';

const exec = promisify(child_process.exec);
const appServer = spawn('codex', ['app-server'], {
  stdio: ['pipe', 'pipe', 'pipe'],
  env: {
    ...process.env,
    RUST_LOG: 'warn'
  }
});
const shoakuDir = join(homedir(), '.shoaku');
const sessionsDir = join(shoakuDir, 'sessions');
const sessionToShoaku = new Map();
const shoakuToSession = new Map();
const chatByShoakuId = new Map();

let config;
let initializeParams;
let lspInputBuilder;
let goalInputBuilder;
let lists = [];
let activeGoalItem;

let appBuf = Buffer.alloc(0);
appServer.stdout.on('data', async (chunk) => {
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
    const shoakuId = sessionToShoaku.get(message.params?.threadId)
    const logPrefix = shoakuToSession.get(shoakuId)?.navigatorThreadId === message.params?.threadId ? '[navigator] ' : message.params?.threadId ? '[explorer] ' : '';
    logInfo(`${logPrefix}AS-> ${JSON.stringify(message)}, pendingRequests: ${[...pendingRequests.keys()]}, pendingTurns: ${[...pendingTurns.values()].flatMap((v) => [...v.keys()])}`);

    if (message.id != null && pendingRequests.has(message.id)) {
      const { resolve, reject } = pendingRequests.get(message.id);
      pendingRequests.delete(message.id);
      if (message.error) {
        reject(new Error(JSON.stringify(message.error)));
      } else {
        resolve(message);
      }
    }

    switch (message.method) {
      case 'thread/status/changed': {
        const shoakuId = sessionToShoaku.get(message.params.threadId);
        if (!shoakuId) {
          break;
        }

        const status = chatByShoakuId.get(shoakuId).status;
        const session = shoakuToSession.get(shoakuId);
        if (message.params.threadId === session.navigatorThreadId) {
          status.navigator = message.params.status.type;
        } else {
          status.explorer = message.params.status.type;
        }

        await syncShoakuLists(initializeParams.initializationOptions.filePath);
        break;
      }

      case 'thread/tokenUsage/updated': {
        const shoakuId = sessionToShoaku.get(message.params.threadId);
        if (!shoakuId) {
          break;
        }

        const usage = chatByShoakuId.get(shoakuId).tokenUsage;
        const session = shoakuToSession.get(shoakuId);
        if (message.params.threadId === session.navigatorThreadId) {
          usage.navigatorTokens = message.params.tokenUsage.total.totalTokens;

          lspInputBuilder.ingest({
            type: inputType.TOKEN_USAGE,
            lastInputTokens: message.params.tokenUsage.last.inputTokens
          });
          goalInputBuilder.ingest({
            type: inputType.TOKEN_USAGE,
            lastInputTokens: message.params.tokenUsage.last.inputTokens
          });
        } else {
          usage.explorerTokens = message.params.tokenUsage.total.totalTokens;
        }

        await syncShoakuLists(initializeParams.initializationOptions.filePath);

        try {
          const metaData = await readFile(join(sessionsDir, shoakuId, 'meta.json'), { encoding: 'utf8' }).then((content) => JSON.parse(content));
          metaData.maxTokens = usage.maxTokens;
          metaData.navigator.tokenUsage = usage.navigatorTokens;
          metaData.explorer.tokenUsage = usage.explorerTokens;
          await writeFile(join(sessionsDir, shoakuId, 'meta.json'), JSON.stringify(metaData, null, 2));
        } catch (e) {
          if (e.code !== 'ENOENT') {
            throw e;
          }
        }
        break;
      }

      case 'item/started':
        if (message.params.item.type === 'contextCompaction') {
          message.params.item.type = 'contextCompactionStarted';
          appendChatHistory(sessionToShoaku.get(message.params.threadId), message.params.turnId, message.params.item);
          await syncShoakuLists(initializeParams.initializationOptions.filePath);
        }
        break;

      case 'item/completed':
        if (pendingTurns.get(message.params.threadId)?.has(message.params.turnId)) {
          const { id, callbacks } = pendingTurns.get(message.params.threadId).get(message.params.turnId);
          callbacks?.onItemCompleted(id, message.params)
        }

        // `thread/compact/start` does not return a `turnId`, so process it here instead of adding it to `pendingTurns`.
        if (message.params.item.type === 'contextCompaction') {
          appendChatHistory(sessionToShoaku.get(message.params.threadId), message.params.turnId, message.params.item);
          await syncShoakuLists(initializeParams.initializationOptions.filePath);
        }
        break;

      case 'turn/completed':
        if (pendingTurns.get(message.params.threadId)?.has(message.params.turn.id)) {
          const turns = pendingTurns.get(message.params.threadId);
          const { resolve, reject } = turns.get(message.params.turn.id);
          turns.delete(message.params.turn.id);
          if (turns.size === 0) {
            pendingTurns.delete(message.params.threadId);
          }
          if (message.error) {
            reject(message.error);
          } else {
            resolve(message.result);
          }
        }
        break;
    }
  }
});

async function startNewSession(goalItem) {
  const workDir = await mkdtemp(join(tmpdir(), 'shoaku-'));
  const shoakuId = basename(workDir);
  chatByShoakuId.set(shoakuId, {
    messages: [],
    status: {
      navigator: '',
      explorer: ''
    },
    tokenUsage: {
      maxTokens: config?.defaultTokenBudget || 0,
      navigatorTokens: 0,
      explorerTokens: 0
    },
    temporaryWorkspace: workDir
  });

  const [navigatorRes, explorerRes] = await Promise.all([
    sendAppRequest('thread/start', {
      cwd: initializeParams.rootPath,
      approvalPolicy: 'never',
      sandbox: 'read-only'
    }),
    (async () => {
      logInfo(`Created temporary work directory: ${workDir}`)
      await exec(`git -C ${initializeParams.rootPath} worktree add ${workDir} -b ${shoakuId.replace('-', '/')}`);
      return sendAppRequest('thread/start', {
        cwd: workDir,
        approvalPolicy: 'never',
        sandbox: 'workspace-write'
      })
    })()
  ]);
  const navigatorThreadId = navigatorRes.result.thread.id;
  const explorerThreadId = explorerRes.result.thread.id;
  sessionToShoaku.set(navigatorThreadId, shoakuId);
  sessionToShoaku.set(explorerThreadId, shoakuId);
  shoakuToSession.set(shoakuId, {
    navigatorThreadId,
    explorerThreadId
  });

  await Promise.all([
    sendAppRequest('thread/name/set', {
      threadId: navigatorThreadId,
      name: `[${shoakuId}] ${goalItem.content}`
    }),
    sendAppRequest('thread/name/set', {
      threadId: explorerThreadId,
      name: `[${shoakuId}] ${goalItem.content}`
    }),
  ]);

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
              text: [
                'Responsibilities:',
                'You ask users questions when there is a gap in understanding between you and them about achieving their goals.',
                '',
                'Interaction policy:',
                '- Unless directly instructed by the user, you will act autonomously within the scope of your authority, without seeking the user\'s consultation or approval.',
                `- You can reference a temporary working directory "${workDir}" when proposing code, but you behave to the user as if the working directory does not exist.`,
                '',
                'Input handling:',
                '- You don\'t design or implement things yourself unless you\'re directly asked by the user.',
                '- Inputs marked with "[Shoaku:IGNORE*]" are internal assistant messages and should be treated separately from user input when responding.',
                '- Driver operations are observational context only. They are not user instructions on their own. Use them only as a supporting signal for the current task.',
              ].join('\n')
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
              text: [
                'You can understand what the users wants to achieve and implement it autonomously.',
                '',
                'Responsibilities:',
                '- Understand the user\'s overall goals and short-term tasks from their TODO list.',
                '- Independently generate code to achieve the user\'s goals.',
              ].join('\n')
            }
          ]
        }
      ]
    })
  ]);

  const childItem = findActiveItem(goalItem?.children ?? []);

  try {
    const content = await readFile(initializeParams.initializationOptions.filePath, { encoding: 'utf8' });
    const lines = content.split('\n');
    // Avoid duplicate shoaku-id writing.
    const currentLabel = lines[goalItem.line].slice(goalItem.labelPosition.start, goalItem.labelPosition.end);
    if (currentLabel === '[shoaku]') {
      lines[goalItem.line] =
        lines[goalItem.line].slice(0, goalItem.labelPosition.start)
        + `[${shoakuId}]`
        + lines[goalItem.line].slice(goalItem.labelPosition.end);
      await writeFile(initializeParams.initializationOptions.filePath, lines.join('\n'), { encoding: 'utf8' });
    }
  } catch (e) {
    if (e.code !== 'ENOENT') {
      throw e;
    }
  }

  const sessionDir = join(sessionsDir, shoakuId);
  await mkdir(sessionDir, { recursive: true });
  const metaData = {
    navigator: {
      threadId: navigatorThreadId
    },
    explorer: {
      threadId: explorerThreadId
    },
  };
  await writeFile(join(sessionDir, 'meta.json'), JSON.stringify(metaData, null, 2), { flag: 'wx' }).catch((e) => {
    if (e.code !== 'EEXIST') {
      throw e;
    }
  });

  startTurn({
    threadId: navigatorThreadId,
    input: [
      {
        type: 'text',
        text: [
          '[Shoaku:IGNORE]',
          `My goal is "${goalItem?.content}", and in the short term, I want to solve "${childItem?.content}".`,
          'Please return all subsequent responses in the language of the list.'
        ].join('\n')
      }
    ]}, {
      onItemCompleted: async (id, params) => {
        appendChatHistory(sessionToShoaku.get(params.threadId), params.turnId, params.item);
        await syncShoakuLists(initializeParams.initializationOptions.filePath);
      }
    }
  );
}

async function resumeSession(shoakuId) {
  const metaData = await readFile(join(sessionsDir, shoakuId, 'meta.json'), { encoding: 'utf8' }).then((content) => JSON.parse(content));
  if (!metaData.navigator?.threadId || !metaData.explorer?.threadId) {
    logWarn(`Invalid session metadata for shoakuId ${shoakuId}`);
    return;
  }

  chatByShoakuId.set(shoakuId, {
    messages: [],
    status: {
      navigator: '',
      explorer: ''
    },
    tokenUsage: {
      maxTokens: metaData.maxTokens || config?.defaultTokenBudget || 0,
      navigatorTokens: metaData.navigator.tokenUsage || 0,
      explorerTokens: metaData.explorer.tokenUsage || 0
    }
  });

  const [navigatorRes, explorerRes] = await Promise.all([
    sendAppRequest('thread/resume', {
      threadId: metaData.navigator.threadId
    }),
    sendAppRequest('thread/resume', {
      threadId: metaData.explorer.threadId
    })
  ]);
  const navigatorThreadId = navigatorRes.result.thread.id;
  const explorerThreadId = explorerRes.result.thread.id;
  sessionToShoaku.set(navigatorThreadId, shoakuId);
  sessionToShoaku.set(explorerThreadId, shoakuId);
  shoakuToSession.set(shoakuId, {
    navigatorThreadId: metaData.navigator.threadId,
    explorerThreadId: metaData.explorer.threadId
  });
  const chat = chatByShoakuId.get(sessionToShoaku.get(navigatorThreadId));

  const explorerReadRes = await sendAppRequest('thread/read', {
    threadId: explorerThreadId,
    includeTurns: false
  });
  chat.temporaryWorkspace = explorerReadRes.result.thread.cwd;

  if (!chat?.messages || chat.messages.length === 0) {
    sendAppRequest('thread/read', {
      threadId: navigatorThreadId,
      includeTurns: true
    }).then(async (res) => {
      for (const turn of res.result.thread.turns) {
        for (let item of turn.items) {
          if (item.content?.[0]?.text?.startsWith('[Shoaku:IGNORE_ALL]')) {
            break;
          }

          if (typeof item.text === 'string') {
            try {
              item = {
                ...item,
                ...JSON.parse(item.text)
              };
            } catch(_) {
            }
          }
          appendChatHistory(sessionToShoaku.get(navigatorThreadId), turn.id, item);
        }
      }
      activeGoalItem = findItemByShoakuId(lists, shoakuId);
      await syncShoakuLists(initializeParams.initializationOptions.filePath);
    });
  }
}

async function syncShoakuLists(filePath) {
  try {
    const content = await readFile(filePath, { encoding: 'utf8' });
    lists = parser.parse(content);
  } catch (e) {
    if (e.code === 'EPERM') {
      logWarn('Access to the Goals file is restricted. On macOS, please allow access in System Settings > Privacy & Security > Files and Folders.');
    } else if (e.code !== 'ENOENT') {
      throw e;
    }
    lists = [];
  }

  for (const goal of lists) {
    if (goal.shoakuId) {
      goal.sessionId = shoakuToSession.get(goal.shoakuId)?.navigatorThreadId;
      goal.messages = chatByShoakuId.get(goal.shoakuId)?.messages;
      goal.tokenUsage = chatByShoakuId.get(goal.shoakuId)?.tokenUsage;
      goal.status = chatByShoakuId.get(goal.shoakuId)?.status;
      goal.temporaryWorkspace = chatByShoakuId.get(goal.shoakuId)?.temporaryWorkspace;
    }
  }
  process.stdout.write(
    buildNotification('shoaku/syncGoals', {
      lists
    })
  );
  return lists;
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
process.stdin.on('data', async (chunk) => {
  try {
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

      if (lspInputBuilder) {
        lspInputBuilder.ingest({
          type: inputType.LSP,
          content: message
        });
      }

      switch (message.method) {
        case 'initialize':
          await mkdir(shoakuDir, { recursive: true });
          await mkdir(sessionsDir, { recursive: true });
          const data = [
            '---',
            'defaultTokenBudget: 1000000',
          ].join('\n');
          await writeFile(join(shoakuDir, 'config.yaml'), data, { flag: 'wx' }).catch((e) => {
            if (e.code !== 'EEXIST') {
              throw e;
            }
          });
          config = await readFile(join(shoakuDir, 'config.yaml'), { encoding: 'utf8' }).then((content) => YAML.parse(content));

          initializeParams = message.params;
          await syncShoakuLists(initializeParams.initializationOptions.filePath);

          cleanupStaleBranches(initializeParams.rootPath);

          lspInputBuilder = new AgentInputBuilder(initializeParams.rootPath, 10000);
          lspInputBuilder.onAgentInput(async (input, shouldCompact) => {
            if (!activeGoalItem?.shoakuId || !shoakuToSession.has(activeGoalItem.shoakuId)) {
              return;
            }

            await sendAppRequest('thread/inject_items', {
              threadId: shoakuToSession.get(activeGoalItem.shoakuId).navigatorThreadId,
              items: [
                {
                  type: 'message',
                  role: 'assistant',
                  content: [
                    {
                      type: 'output_text',
                      text: input
                    }
                  ]
                }
              ]
            });

            startTurn({
              threadId: shoakuToSession.get(activeGoalItem.shoakuId).navigatorThreadId,
              input: [
                {
                  type: 'text',
                  text: [
                    '[Shoaku:IGNORE]',
                    'If you feel that the user\'s current task and coding direction are unclear or inappropriate, ask a simple question to clarify any misunderstandings.',
                    'If the next task is not reasonable for achieving the goal, return an alignmentScore below 0.9.'
                  ].join('\n')
                }
              ],
              outputSchema: {
                type: 'object',
                properties: {
                  alignmentScore: {
                    description: [
                      'It anticipates all tasks necessary to achieve the objective and returns a score of 0-1 indicating how well they match the user\'s tasks.'
                    ].join('\n'),
                    type: 'number'
                  }
                },
                required: [ 'alignmentScore' ],
                additionalProperties: false
              }
            }, {
                onItemCompleted: async (id, params) => {
                  let response = params.item;
                  if (typeof response.text === 'string') {
                    try {
                      response = {
                        ...response,
                        ...JSON.parse(params.item.text)
                      };
                    } catch(_) {
                    }
                  }
                  appendChatHistory(sessionToShoaku.get(params.threadId), params.turnId, response);
                  await syncShoakuLists(initializeParams.initializationOptions.filePath);
                }
              }
            );
          });

          goalInputBuilder = new AgentInputBuilder(initializeParams.rootPath, 3000);
          goalInputBuilder.onAgentInput(async (input, shouldCompact) => {
            if (!input?.shoakuId || !shoakuToSession.has(input.shoakuId)) {
              return;
            }

            await sendAppRequest('thread/inject_items', {
              threadId: shoakuToSession.get(input.shoakuId).navigatorThreadId,
              items: [
                {
                  type: 'message',
                  role: 'assistant',
                  content: [
                    {
                      type: 'output_text',
                      text: [
                        `Current Goal/Tasks: "${JSON.stringify(input)}"`
                      ].join('\n')
                    }
                  ]
                }
              ]
            });

            // FIXME: Duplicate code
            startTurn({
              threadId: shoakuToSession.get(input.shoakuId).navigatorThreadId,
              input: [
                {
                  type: 'text',
                  text: [
                    '[Shoaku:IGNORE]',
                    'If you feel that the user\'s current task and coding direction are unclear or inappropriate, ask a simple question to clarify any misunderstandings.',
                    'If the next task is not reasonable for achieving the goal, return an alignmentScore below 0.9.'
                  ].join('\n')
                }
              ],
              outputSchema: {
                type: 'object',
                properties: {
                  alignmentScore: {
                    description: [
                      'It anticipates all tasks necessary to achieve the objective and returns a score of 0-1 indicating how well they match the user\'s tasks.'
                    ].join('\n'),
                    type: 'number'
                  }
                },
                required: [ 'alignmentScore' ],
                additionalProperties: false
              }
            }, {
                onItemCompleted: async (id, params) => {
                  const response = params.item.type === 'agentMessage'
                    ? {
                        ...params.item,
                        ...JSON.parse(params.item.text)
                      }
                    : params.item;
                  const message = appendChatHistory(sessionToShoaku.get(params.threadId), params.turnId, response);
                  await syncShoakuLists(initializeParams.initializationOptions.filePath);

                  if (response.alignmentScore >= 0.9) {
                    const used = (chatByShoakuId.get(input.shoakuId).tokenUsage.navigatorTokens || 0) - (chatByShoakuId.get(input.shoakuId).tokenUsage.explorerTokens || 0);
                    const tokenBudget = chatByShoakuId.get(input.shoakuId).tokenUsage.maxTokens - used;
                    if (tokenBudget <= 0) {
                      logInfo(`Token budget exceeded for shoakuId ${input.shoakuId}. Used: ${used}, Max: ${chatByShoakuId.get(input.shoakuId).tokenUsage.maxTokens}`);
                      return;
                    }

                    await sendAppRequest('thread/goal/set', {
                      threadId: shoakuToSession.get(input.shoakuId).explorerThreadId,
                      objective: [
                        `Implement it autonomously to achieve the user's goal. User's goal: ${input.content}`
                      ].join('\n'),
                      tokenBudget
                    });
                  }
                }
              }
            );
          });

          appServer.stdin.write(JSON.stringify(buildAppRequest('initialize', {
            clientInfo: {
              name: 'shoaku_intellij',
              title: 'Shoaku for IntelliJ',
              version: '0.1.0'
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

          watchGoalsFileUpdates();
          break;

        case 'shutdown':
          process.stdout.write(buildResponse(message.id, null));
          break;

        case 'shoaku/didChangeGoalsFilePath':
          initializeParams.initializationOptions.filePath = message.params.filePath;
          await syncShoakuLists(initializeParams.initializationOptions.filePath);
          watchGoalsFileUpdates();
          break;

        case 'shoaku/startSession':
          if (message.params.shoakuId) {
            resumeSession(message.params.shoakuId);
          }
          break;

        case 'shoaku/applyDiff':
          try {
            const content = await readFile(initializeParams.initializationOptions.filePath, { encoding: 'utf8' });
            const updatedContent = renderSummary(content, message.params.shoakuId, message.params.response);
            await writeFile(initializeParams.initializationOptions.filePath, updatedContent, { encoding: 'utf8' });
          } catch(e) {
            if (e.code !== 'ENOENT') {
              throw e;
            }
          }
          break;

        case 'shoaku/reply':
          const navigatorThreadId = shoakuToSession.get(message.params.shoakuId).navigatorThreadId;
          if (!navigatorThreadId) {
            logWarn(`No active session found for shoakuId ${message.params.shoakuId}`);
            break;
          }

          await startTurn({
            threadId: navigatorThreadId,
            input: [
              {
                type: 'text',
                text: message.params.text
              }
            ]}, {
              onItemCompleted: async (id, params) => {
                appendChatHistory(sessionToShoaku.get(params.threadId), params.turnId, params.item);
                await syncShoakuLists(initializeParams.initializationOptions.filePath);
              }
            }
          );
          break;

        case 'shoaku/makeMeExplain':
          await startTurn({
            threadId: shoakuToSession.get(message.params.shoakuId).navigatorThreadId,
            input: [
              {
                type: 'text',
                text: [
                  '[Shoaku:IGNORE]',
                  'Ask thorough questions about anything the user seems to lack understanding of based on their previous actions, which could be why they are unable to complete the current task.'
                ].join('\n')
              }
            ]}, {
              onItemCompleted: async (id, params) => {
                appendChatHistory(sessionToShoaku.get(params.threadId), params.turnId, params.item);
                await syncShoakuLists(initializeParams.initializationOptions.filePath);
              }
            }
          );
          break;

        case 'shoaku/startFinalCheck':
          await startTurn({
            threadId: shoakuToSession.get(message.params.shoakuId).navigatorThreadId,
            input: [
              {
                type: 'text',
                text: [
                  '[Shoaku:IGNORE]',
                  'Compare the temporary working directory and the current working directory, and summarize any overlooked issues in a report. Ignore minor differences in code style.'
                ].join('\n')
              }
            ],
            outputSchema: {
              type: 'object',
              properties: {
                text: {
                  description: 'Summary of results.',
                  type: 'string'
                },
                inlineReviewComments: {
                  type: 'array',
                  items: {
                    type: 'object',
                    properties: {
                      path: {
                        type: 'string'
                      },
                      line: {
                        type: 'number'
                      },
                      text: {
                        type: 'string'
                      }
                    },
                    required: [ 'path', 'line', 'text' ],
                    additionalProperties: false
                  }
                }
              },
              required: [ 'text', 'inlineReviewComments' ],
              additionalProperties: false
            }
          }, {
              onItemCompleted: async (id, params) => {
                const response = params.item.type === 'agentMessage'
                  ? {
                      ...params.item,
                      ...JSON.parse(params.item.text)
                    }
                  : params.item;
                appendChatHistory(sessionToShoaku.get(params.threadId), params.turnId, response);
                await syncShoakuLists(initializeParams.initializationOptions.filePath);
              }
            }
          );
          break;

        case 'shoaku/didChangeMaxTokens': {
          const chat = chatByShoakuId.get(message.params.shoakuId);
          chat.tokenUsage.maxTokens = message.params.tokens;
          break;
        }
      }
    }
  } catch (err) {
    logError(`Error processing message from language client: ${err}`);
  }
});

process.stdin.on('end', () => {
  appServer.stdin.end();
});

let prevValidGoalsFilePath = null;
async function watchGoalsFileUpdates() {
  try {
    const filePath = initializeParams.initializationOptions.filePath;
    for await (const event of watch(filePath)) {
      if (prevValidGoalsFilePath != null && initializeParams.initializationOptions.filePath !== prevValidGoalsFilePath) {
        prevValidGoalsFilePath = null;
        return;
      }
      prevValidGoalsFilePath = filePath;

      await syncShoakuLists(filePath);

      if (goalInputBuilder && activeGoalItem) {
        const { messages, tokenUsage, status, ...content } = activeGoalItem;
        goalInputBuilder.ingest({
          type: inputType.GOAL,
          content
        });
      }

      for (const item of lists) {
        if (item.checked === false && !item.shoakuId) {
          await startNewSession(item);
        }
      }
    }
  } catch (err) {
    if (err.code !== 'ENOENT') {
      logError(`Error watching file: ${err}`);
    }
  }
}

function findItemByShoakuId(lists, shoakuId) {
  if (!lists) {
    return null;
  }

  return lists.find((item) => item.shoakuId === shoakuId);
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

function appendChatHistory(shoakuId, turnId, item) {
  if (item.content?.[0]?.text?.startsWith('[Shoaku:IGNORE]')) {
    return null;
  }

  const message = {
    turnId,
    type: item.type,
    phase: item.phase,
    text: item.text ||
      item.content?.filter(c => c.type === 'text').map(c => c.text).join('\n'),
    command: item.command || item.query,
    alignmentScore: item.alignmentScore,
    inlineReviewComments: item.inlineReviewComments
  };
  chatByShoakuId.get(shoakuId)?.messages.push(message);

  return message;
}

const pendingTurns = new Map();
async function startTurn(params, callbacks) {
  const turns = pendingTurns.get(params.threadId) || new Map();
  for (const turnId of turns.keys()) {
    try {
      await sendAppRequest('turn/interrupt', {
        threadId: params.threadId,
        turnId
      });
    } catch(e) {
      logInfo(`Failed to interrupt turn ${turnId}, it might have already been completed. error: ${e}`);
    }
  }

  const msg = await sendAppRequest('turn/start', params);
  return new Promise((resolve, reject) => {
    turns.set(msg.result.turn.id, { id: msg.id, resolve, reject, callbacks });
    pendingTurns.set(params.threadId, turns);
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
    buildNotification('window/logMessage', {
      type: 3,
      message
    })
  );
}

function logWarn(message) {
  process.stdout.write(
    buildNotification('window/logMessage', {
      type: 2,
      message
    })
  );
}

function logError(message) {
  process.stdout.write(
    buildNotification('window/showMessage', {
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
