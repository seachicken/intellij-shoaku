#!/usr/bin/env node

import { homedir } from 'node:os';
import { mkdir, readFile, rm, stat, watch, writeFile } from 'node:fs/promises';
import { createConnection } from 'node:net';
import { basename, dirname, join } from 'node:path';
import { promisify } from 'node:util';
import child_process, { spawn } from 'node:child_process';
import { tmpdir } from 'node:os';
import WebSocket from 'ws';
import YAML from 'yaml';
import AgentInputBuilder, { inputType } from './agent-input-builder.js';
import { renderSummary, renderSummaryDiff } from './diff-snippet.js';
import { cleanupStaleBranches } from './git.js';
import parser from './parser.js';
import { parsePlanResponse } from './plan-response.js';
import { randomBytes } from 'node:crypto';
import { createManagedWorktree } from './managed-worktree.js';
import { ensureExplorerBranch, watchGitRequests } from './git-bridge.js';
import { workflowStatus } from './workflow-status.js';
import { recordReviewBase, inspectCommitReview } from './commit-review.js';

const exec = promisify(child_process.exec);
const appServerSocketPath = join(tmpdir(), `shoaku-app-server-${process.pid}.sock`);
const appServer = spawn('codex', ['app-server', '--listen', `unix://${appServerSocketPath}`], {
  stdio: ['ignore', 'ignore', 'pipe'],
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
const startupWorkflowByGoal = new Map();
const gitBridgeByShoakuId = new Map();
const startupGoalKey = goal => `${goal.line}:${goal.content}`;



const explorerInstructions = [
  'Autonomously implement the user\'s TODOs.',
  '- Read .shoaku/PLANS.md, authored by Navigator, as the authoritative implementation plan. Maintain .shoaku/goals.md as your progress checklist, preserving every plan task\'s exact text, order, and nesting; change only its completion checkbox. Keep additional investigation notes outside checklist rows. Never rename, replace, or add plan tasks in goals.md. If the plan changes, synchronize the checklist to the current plan and retain completion only for matching tasks. Do not edit PLANS.md.',
  '- Implement, integrate, and verify the complete goal autonomously; human checkboxes are requirements, not prerequisites for starting work.',
  '- Source edits happen in this session’s linked worktree. Shoaku manages Git commits on your dedicated branch through a host bridge. Do not run git add, commit, reset, rebase, or branch mutations yourself. Request each coherent commit by atomically writing .shoaku/git-request.json with {id: a unique alphanumeric identifier, action: commit, files: an array of explicit relative source-file paths, message: the commit explanation}. Use valid JSON with quoted strings. Poll .shoaku/git-response.json until its id matches and status is complete or failed; use the returned full hash for commit documents. Never treat a pending request as a commit. If failed, preserve the work and report the error. Do not modify git-response.json.',
  '- Organize implementation as coherent commits via this bridge. The server attaches the managed checkout to its dedicated shoaku branch. Choose a logical sequence and keep each request independently understandable; never rewrite other branches or push.',
  '- Write .shoaku/RESULTS.md as a concise, visual design document: outcome, architecture, design decisions, alternatives, verification evidence, and remaining limitations. Include an ordered commit reading guide linking to commits/<full-sha>.md.',
  '- Start RESULTS.md with up to three outcome bullets and a compact before/after example. Include a Mermaid architecture or flow diagram showing the actual changed components and their relationships. Use a commit reading-guide table with short hash, purpose, and a link to the commit document in dependency order. Keep the overview to roughly one screen; move supporting details into commit documents.',
  '- Commit documents begin with a one-sentence purpose and a concrete behavior example, then explain the design and verification concisely. Include a small Mermaid flow/sequence diagram when interactions or control flow benefit from it, or a before/after table for simple behavior changes. Avoid long prose and raw command logs; show verification outcomes in a small table. Diagrams must agree with the implemented code.',
  '- If Git reports Operation not permitted for index.lock, report the permission failure and preserve the work. Never delete lock files to work around sandbox permissions. For an existing lock, do not remove it without establishing that its owner is no longer running.',
  '- For every final implementation commit, write .shoaku/commits/<full-sha>.md with its title, full hash, purpose, design rationale, affected files, dependencies on earlier commits, and verification. After rewriting history, regenerate these documents and the reading guide for the final hashes.',
  '- Also write .shoaku/commits/<full-sha>.review.json as a guided design-to-code map. Use this schema: {"schemaVersion":1,"commit":"<full-sha>","problem":"the concrete user problem","before":"a concrete behavior before this change","after":"the new behavior","steps":[{"id":"validate","title":"a short design decision","why":"why this choice is needed, including relevant tradeoffs","change":"what this step changes","verification":"actual evidence and outcome, or explicitly not verified","anchors":[{"path":"src/example.js","side":"after","startLine":10,"endLine":18,"label":"the function or responsibility at these lines"}],"links":[{"to":"persist","label":"validated request is passed to persistence"}]}],"excludedFiles":[{"path":"generated.bin","reason":"why no line-level explanation is provided"}]}. Steps must explain behavior/responsibilities, not simply list files. Organize their order to help a reader build context then inspect implementation and evidence. Link related steps only when the actual implementation supports that relationship. Every step needs at least one real code anchor; every changed file must be anchored or explicitly excluded with a reason. Use empty arrays when there are no links or exclusions. Use paths and 1-based inclusive line ranges in this exact commit (after) or its first parent (before), verified with git show; never use current workspace line numbers or invent correspondence. Before-side anchors explain removals. Keep each field concise. Include the same step titles and rationale in the Markdown explanation, with a Mermaid flow using these step IDs where a diagram is useful. These maps are required review artifacts, not evidence that the code is correct.',
  '- Keep result documents outside implementation commits to avoid circular hash references. Update them as work progresses, and mark unfinished work and unverified claims explicitly. Do not stage .shoaku artifacts.',
  '- Treat commit creation, cleanup, and documentation as required acceptance criteria. Do not mark the goal complete while implementation changes are uncommitted, commit explanations or valid design-to-code maps are missing, or RESULTS.md does not link to every final commit. Read the starting revision from .shoaku/base-revision; never edit it or the server-generated COMMITS.md. A genuine no-change goal may finish without commits, but explain why in RESULTS.md.',
  '- Preserve the workspace and report blockers when verification or history cleanup fails.',
].join('\n');

let appClient;
let config;
let initializeParams;
let lspInputBuilder;
let goalInputBuilder;
let lists = [];
let activeShoakuId;

function connectToAppServer() {
  return new Promise((resolve, reject) => {
    const deadline = Date.now() + 10_000;

    const attempt = () => {
      const client = new WebSocket('ws://localhost/rpc', {
        createConnection: () => createConnection({ path: appServerSocketPath }),
        perMessageDeflate: false
      });
    
      client.on('message', async (data) => {
        let message;
        try {
          message = JSON.parse(data.toString());
        } catch {
          logError(`Received non-JSON message from app server: ${data.toString()}`);
          return;
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
              status.navigatorFlags = message.params.status.activeFlags || [];
            } else {
              status.explorer = message.params.status.type;
              status.explorerFlags = message.params.status.activeFlags || [];
            }
    
            await syncShoakuLists(initializeParams.initializationOptions.filePath);
            break;
          }
    
          case 'thread/goal/updated': {
            const shoakuId = sessionToShoaku.get(message.params.threadId);
            const session = shoakuToSession.get(shoakuId);
            if (session?.explorerThreadId === message.params.threadId) {
              chatByShoakuId.get(shoakuId).status.goalStatus = message.params.goal.status;
              await syncShoakuLists(initializeParams.initializationOptions.filePath);
              const chat = chatByShoakuId.get(shoakuId);
              if (message.params.goal.status === 'complete' && chat.status.review?.ready === false && !chat.reviewRepairAttempted) {
                chat.reviewRepairAttempted = true;
                try {
                  const plansMd = await readFile(join(chat.temporaryWorkspace, '.shoaku', 'PLANS.md'), 'utf8');
                  await dispatchExplorerGoal(shoakuId, plansMd, chat.status.review.detail);
                } catch (error) {
                  logWarn(`Cannot dispatch commit-review repair for ${shoakuId}: ${error.message}`);
                }
              }
            }
            break;
          }
          case 'thread/goal/cleared': {
            const shoakuId = sessionToShoaku.get(message.params.threadId);
            if (shoakuToSession.get(shoakuId)?.explorerThreadId === message.params.threadId) {
              delete chatByShoakuId.get(shoakuId).status.goalStatus;
              await syncShoakuLists(initializeParams.initializationOptions.filePath);
            }
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
            if (message.params.item.type === 'userMessage') {
              const shoakuId = sessionToShoaku.get(message.params.threadId);
              const text = message.params.item.content?.filter(part => part.type === 'text').map(part => part.text).join('\n');
              if (shoakuId && shoakuToSession.get(shoakuId)?.navigatorThreadId === message.params.threadId && text && !text.startsWith('[Shoaku:')) {
                delete chatByShoakuId.get(shoakuId).status.requiredInput;
                goalInputBuilder?.ingest({ type: inputType.GOAL_HUMAN, content: { goal: findItemByShoakuId(lists, shoakuId)?.content, requirements: findItemByShoakuId(lists, shoakuId)?.children.map(item => item.content), userResponse: text } });
                await syncShoakuLists(initializeParams.initializationOptions.filePath);
              }
            }
            if (pendingTurns.get(message.params.threadId)?.has(message.params.turnId)) {
              const { id, callbacks } = pendingTurns.get(message.params.threadId).get(message.params.turnId);
              try {
                await callbacks?.onItemCompleted(id, message.params);
              } catch (error) {
                logError(`Failed to process completed item for thread ${message.params.threadId}: ${error.message}`);
              }
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
      });
    
      client.once('open', () => {
        appClient = client;
        resolve(client);
      });
    
      client.once('error', (err) => {
        client.close();

        if (Date.now() >= deadline) {
          reject(new Error('Failed to connect to app server within 10 seconds.'));
          return;
        }

        setTimeout(attempt, 1_000);
      });
    };

    attempt();
  });
}

async function startNewSession(goalItem) {
  const startupKey = startupGoalKey(goalItem);
  startupWorkflowByGoal.set(startupKey, { stage: 'planning', detail: 'Codex is preparing a linked worktree and checking the checkout.' });
  await syncShoakuLists(initializeParams.initializationOptions.filePath);
  let explorerNativeThreadId;
  try {
    explorerNativeThreadId = await createManagedWorktree(initializeParams.rootPath);
  } catch (error) {
    startupWorkflowByGoal.set(startupKey, { stage: 'error', detail: error.message });
    await syncShoakuLists(initializeParams.initializationOptions.filePath);
    throw error;
  }
  const nativeExplorer = await sendAppRequest('thread/read', { threadId: explorerNativeThreadId, includeTurns: false });
  const workDir = nativeExplorer.result.thread.cwd;
  const shoakuId = `shoaku-${randomBytes(6).toString('hex')}`;
  const explorerBranch = shoakuId.replace('-', '/');
  await ensureExplorerBranch(workDir, explorerBranch);
  activeShoakuId = shoakuId;
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
      logInfo(`Using Codex-managed linked worktree: ${workDir}`);

      const data = [
        `# ${goalItem.content}`,
      ].join('\n');
      const shoakuDir = join(workDir, '.shoaku');
      await mkdir(shoakuDir, { recursive: true });
      await recordReviewBase(workDir);
      const goalsPath = join(shoakuDir, 'goals.md');
      await writeFile(goalsPath, data, { flag: 'wx' }).catch((e) => {
        if (e.code !== 'EEXIST') {
          throw e;
        }
      });
      watchExplorerGoalsFileUpdates(goalsPath);

      return sendAppRequest('thread/resume', { threadId: explorerNativeThreadId })
    })()
  ]);
  startupWorkflowByGoal.delete(startupKey);
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
                '- Design and maintain the implementation plan proactively within the human goal. Explorer implements it autonomously; users do not need to implement tasks themselves.',
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
              text: explorerInstructions
            }
          ]
        }
      ]
    })
  ]);

  startGitBridge(shoakuId, workDir, explorerBranch);

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
      threadId: explorerThreadId,
      workspaceKind: 'codex-managed',
      workspace: workDir,
      branch: explorerBranch
    },
  };
  await writeFile(join(sessionDir, 'meta.json'), JSON.stringify(metaData, null, 2), { flag: 'wx' }).catch((e) => {
    if (e.code !== 'EEXIST') {
      throw e;
    }
  });

  startTurn({
    threadId: navigatorThreadId,
    sandboxPolicy: {
      type: 'readOnly',
      networkAccess: true
    },
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

  activeShoakuId = shoakuId;
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

  const explorerThread = await sendAppRequest('thread/read', { threadId: metaData.explorer.threadId, includeTurns: false });
  const explorerWorkspace = explorerThread.result.thread.cwd;
  await recordReviewBase(explorerWorkspace, { existing: true });

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
  const explorerBranch = metaData.explorer.branch || shoakuId.replace('-', '/');
  await ensureExplorerBranch(chat.temporaryWorkspace, explorerBranch);
  startGitBridge(shoakuId, chat.temporaryWorkspace, explorerBranch);

  await sendAppRequest('thread/inject_items', {
    threadId: explorerThreadId,
    items: [{ type: 'message', role: 'developer', content: [{ type: 'input_text', text: explorerInstructions }] }]
  });

  const goalsPath = join(chat.temporaryWorkspace, '.shoaku', 'goals.md');
  watchExplorerGoalsFileUpdates(goalsPath);

  const savedPlan = await readFile(join(chat.temporaryWorkspace, '.shoaku', 'PLANS.md'), 'utf8').catch((error) => {
    if (error.code !== 'ENOENT') throw error;
    return null;
  });
  const restoredGoal = await sendAppRequest('thread/goal/get', { threadId: explorerThreadId });
  chat.status.goalStatus = restoredGoal.result.goal?.status;
  if (savedPlan?.trim() && !['complete', 'paused'].includes(chat.status.goalStatus)) {
    await dispatchExplorerGoal(shoakuId, savedPlan);
  }
  await syncShoakuLists(initializeParams.initializationOptions.filePath);

  // Upgrade completed sessions to the new traceable review format without
  // reimplementing code or rewriting their existing commits.
  if (savedPlan?.trim() && chat.status.goalStatus === 'complete' && chat.status.review?.invalidMaps?.length &&
      chat.status.review.uncommitted.length === 0 && !chat.reviewRepairAttempted) {
    chat.reviewRepairAttempted = true;
    await dispatchExplorerGoal(shoakuId, savedPlan, chat.status.review.detail, true);
  }

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
      await syncShoakuLists(initializeParams.initializationOptions.filePath);
    });
  }
}

function startGitBridge(shoakuId, workspace, branch) {
  gitBridgeByShoakuId.get(shoakuId)?.();
  gitBridgeByShoakuId.set(shoakuId, watchGitRequests(workspace, branch, async result => {
    if (result.status === 'failed') logWarn(`Explorer commit failed for ${shoakuId}: ${result.error}`);
    else logInfo(`Explorer commit created for ${shoakuId}: ${result.hash}`);
    await syncShoakuLists(initializeParams.initializationOptions.filePath);
  }));
}

async function dispatchExplorerGoal(shoakuId, plansMd, reviewRepair = null, documentationOnly = false) {
  const used = (chatByShoakuId.get(shoakuId).tokenUsage.navigatorTokens || 0) - (chatByShoakuId.get(shoakuId).tokenUsage.explorerTokens || 0);
  const tokenBudget = chatByShoakuId.get(shoakuId).tokenUsage.maxTokens - used;
  if (tokenBudget <= 0) {
    logInfo(`Token budget exceeded for shoakuId ${shoakuId}. Used: ${used}, Max: ${chatByShoakuId.get(shoakuId).tokenUsage.maxTokens}`);
    return;
  }

  const activeGoalItem = findItemByShoakuId(lists, shoakuId);
  if (!activeGoalItem) return;
  const goalResponse = await sendAppRequest('thread/goal/set', {
    threadId: shoakuToSession.get(shoakuId).explorerThreadId,
    objective: [
    documentationOnly ? `Create design-to-code review artifacts for the existing implementation of: ${activeGoalItem.content}. Do not change source files, create implementation commits, or rewrite history.` : `Implement autonomously to achieve the user's goal: ${activeGoalItem.content}`,
    '',
    documentationOnly
      ? 'Read the authoritative .shoaku/PLANS.md as context for explaining the existing committed implementation.'
      : 'Read .shoaku/PLANS.md and synchronize .shoaku/goals.md with the authoritative plan. Implement independently of human checklist completion. Organize your implementation commits and document the result in .shoaku/RESULTS.md and .shoaku/commits/<full-sha>.md.',
    '',
    '<plan_md>',
    plansMd,
    '</plan_md>',
    '',
    documentationOnly
      ? 'Preserve source files, Git history, and the implementation checklist. Update only design-to-code review artifacts for the existing final hashes.'
      : 'Maintain progress in .shoaku/goals.md. Commit implementation changes on your dedicated session branch and organize only your own commits for readers. Keep the design document and commit reading guide current.',
    ...(reviewRepair ? ['', `Server acceptance check failed: ${reviewRepair}`, documentationOnly
      ? 'Read .shoaku/COMMITS.md and the immutable committed code. Generate .shoaku/commits/<full-sha>.review.json and update the matching Markdown design explanations and RESULTS.md for the existing final hashes only. Verify every line anchor against its commit or first parent. Do not redo implementation or create Git commit requests. If this cannot be achieved, report the concrete blocker instead of claiming complete.'
      : 'Finish committing and documenting the existing implementation; do not redo completed implementation work. Read .shoaku/COMMITS.md for the actual commit order. Commit outstanding source changes, create explanations and validated design-to-code maps for final hashes, and link them from RESULTS.md. If this cannot be achieved, report the concrete blocker and mark the goal blocked instead of complete.'] : [])
    ].join('\n'),
    tokenBudget
  });
  chatByShoakuId.get(shoakuId).status.goalStatus = goalResponse.result.goal?.status || 'active';
  await syncShoakuLists(initializeParams.initializationOptions.filePath);
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
    const chat = chatByShoakuId.get(goal.shoakuId);
    if (chat?.status.goalStatus === 'complete' && chat.temporaryWorkspace) {
      try {
        chat.status.review = await inspectCommitReview(chat.temporaryWorkspace);
      } catch (error) {
        chat.status.review = { ready: false, detail: `Cannot verify implementation commits: ${error.message}` };
      }
    }
    goal.workflow = startupWorkflowByGoal.get(startupGoalKey(goal)) || workflowStatus(chatByShoakuId.get(goal.shoakuId)?.status, !!goal.shoakuId);
    if (goal.shoakuId) {
      goal.sessionId = shoakuToSession.get(goal.shoakuId)?.navigatorThreadId;
      goal.appServerRemoteUrl = `unix://${appServerSocketPath}`;
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
          await connectToAppServer();

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
          lspInputBuilder.onAgentInput(async (input) => {
            if (!activeShoakuId || !shoakuToSession.has(activeShoakuId)) {
              return;
            }

            await sendAppRequest('thread/inject_items', {
              threadId: shoakuToSession.get(activeShoakuId).navigatorThreadId,
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
          });

          goalInputBuilder = new AgentInputBuilder(initializeParams.rootPath, 3000);
          goalInputBuilder.onAgentInput(async (input) => {
            if (!activeShoakuId || !shoakuToSession.has(activeShoakuId)) {
              return;
            }

            const shoakuId = activeShoakuId;
            await sendAppRequest('thread/inject_items', {
              threadId: shoakuToSession.get(shoakuId).navigatorThreadId,
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

            await startTurn({
              threadId: shoakuToSession.get(shoakuId).navigatorThreadId,
              sandboxPolicy: {
                type: 'readOnly',
                networkAccess: true
              },
              input: [
                {
                  type: 'text',
                  text: [
                    '[Shoaku:IGNORE]',
                    'Set requiredInput to a concise, specific question only when missing user information prevents safe implementation. Otherwise return an empty string. If blocked on user input, plansMd may be empty; do not dispatch implementation until the question is answered.',
                    'Return plansMd as a complete plain Markdown checklist in execution order for goal/set, with the human goal as its heading.',
                    'For plansMd, treat human tasks as requirements and constraints, not an exhaustive implementation plan. Use the available conversation and repository context to derive concrete, independently reviewable steps within the human goal\'s scope, including necessary investigation, implementation, integration, and acceptance verification. Split or combine tasks as needed; do not invent unresolved technical details.',
                    'Do not copy human completion states as proof that Explorer work is complete.',
                    'Generate plansMd when no previous plan exists. Otherwise update it only when the human goal, requirements, or constraints change. Changes only to completion states or Explorer tasks do not change the plan; preserve the previous plansMd exactly.'
                  ].join('\n')
                }
              ],
              outputSchema: {
                type: 'object',
                properties: {
                  plansMd: {
                    type: 'string'
                  },
                  requiredInput: { type: 'string' }
                },
                required: [ 'plansMd', 'requiredInput' ],
                additionalProperties: false
              }
            }, {
                onItemCompleted: async (id, params) => {
                  const response = parsePlanResponse(params.item);
                  if (!response) {
                    if (params.item.type === 'agentMessage' && params.item.phase !== 'commentary') {
                      logWarn('Navigator returned an invalid or empty plan. The previous plan and Explorer objective were preserved.');
                    }
                    return;
                  }
                  const activeGoalItem = findItemByShoakuId(lists, shoakuId);
                  if (!activeGoalItem) {
                    logWarn(`Ignoring plan for removed or unavailable goal ${shoakuId}.`);
                    return;
                  }
                  chatByShoakuId.get(shoakuId).status.requiredInput = response.requiredInput?.trim() || '';
                  if (response.requiredInput?.trim()) {
                    appendChatHistory(shoakuId, params.turnId, { ...response, text: response.requiredInput });
                    await syncShoakuLists(initializeParams.initializationOptions.filePath);
                    return;
                  }
                  const workspace = chatByShoakuId.get(shoakuId).temporaryWorkspace;
                  const plansPath = join(workspace, '.shoaku', 'PLANS.md');
                  const previousPlan = await readFile(plansPath, 'utf8').catch((error) => {
                    if (error.code !== 'ENOENT') throw error;
                    return null;
                  });
                  if (previousPlan !== response.plansMd) {
                    await writeFile(plansPath, response.plansMd, 'utf8');
                  }
                  appendChatHistory(sessionToShoaku.get(params.threadId), params.turnId, response);
                  await syncShoakuLists(initializeParams.initializationOptions.filePath);

                  // Progress updates must not restart an unchanged Explorer objective.
                  if (previousPlan === response.plansMd) return;

                  chatByShoakuId.get(shoakuId).reviewRepairAttempted = false;
                  await dispatchExplorerGoal(shoakuId, response.plansMd);
                }
              }
            );
          });

          await sendAppRequest('initialize', {
            clientInfo: {
              name: 'shoaku_intellij',
              title: 'Shoaku for IntelliJ',
              version: '0.1.0'
            },
            capabilities: {
              optOutNotificationMethods: ['item/agentMessage/delta']
            }
          });

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
            resumeSession(message.params.shoakuId).catch(e => {
              logWarn(`Failed to resume session for shoakuId ${message.params.shoakuId}: ${e}`);
            });
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
            sandboxPolicy: {
              type: 'readOnly',
              networkAccess: true
            },
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

        case 'shoaku/createDiff': {
          const navigatorThreadId = shoakuToSession.get(message.params.shoakuId).navigatorThreadId;
          const chat = chatByShoakuId.get(sessionToShoaku.get(navigatorThreadId));
          const diffPath = await createDiff(message.params.explorerTaskIndex, chat.temporaryWorkspace);
          process.stdout.write(buildResponse(message.id, {
            explorerTaskPath: diffPath
          }));
          break;
        }

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
  appClient?.close();
  appServer.kill();
});

async function createDiff(taskIndex, tempWorkspace) {
  const outputDir = join(tempWorkspace, '.shoaku', String(taskIndex));
  await rm(outputDir, { recursive: true, force: true });
  await exec(`GIT_INDEX_FILE=${tempWorkspace}/.shoaku/git-index git -C ${tempWorkspace} read-tree HEAD`);
  await exec(`GIT_INDEX_FILE=${tempWorkspace}/.shoaku/git-index git -C ${tempWorkspace} checkout-index --all --prefix=${outputDir}/`);
  for (let i = 0; i <= taskIndex; i++) {
    const patchPath = join(tempWorkspace, '.shoaku', 'task-patches', `${i}.patch`);
    if (await stat(patchPath).then((s) => s.isFile() && s.size > 0).catch(() => false)) {
      await exec(`git -C ${tempWorkspace} apply --directory=.shoaku/${taskIndex} ${patchPath}`);
    }
  }
  return outputDir;
}

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
      const activeGoalItem = findItemByShoakuId(lists, activeShoakuId);

      if (goalInputBuilder && activeGoalItem) {
        const { messages, tokenUsage, status, ...content } = activeGoalItem;
        goalInputBuilder.ingest({
          type: inputType.GOAL_HUMAN,
          content
        });
      }

      for (const item of lists) {
        if (item.checked === false && !item.shoakuId) {
          try {
            await startNewSession(item);
          } catch (error) {
            startupWorkflowByGoal.set(startupGoalKey(item), { stage: 'error', detail: error.message });
            await syncShoakuLists(initializeParams.initializationOptions.filePath);
            logError(`Cannot start Explorer for ${item.content}: ${error.message}`);
          }
        }
      }
    }
  } catch (err) {
    if (err.code !== 'ENOENT') {
      logError(`Error watching human goals file: ${err}`);
    }
  }
}

async function watchExplorerGoalsFileUpdates(filePath) {
  try {
    for await (const event of watch(filePath)) {
      const workspace = dirname(dirname(filePath));
      const explorerShoakuId = [...chatByShoakuId].find(([, chat]) => chat.temporaryWorkspace === workspace)?.[0];
      if (explorerShoakuId !== activeShoakuId) {
        continue;
      }

      try {
        const content = await readFile(filePath, { encoding: 'utf8' });
        const goal = parser.parse(content, { wholeFile: true });
        if (goalInputBuilder) {
          goalInputBuilder.ingest({
            type: inputType.GOAL_EXPLORER,
            content: goal
          });
        }
      } catch (e) {
        if (e.code !== 'ENOENT') {
          throw e;
        }
      }
    }
  } catch (err) {
    if (err.code !== 'ENOENT') {
      logError(`Error watching explorer goals file: ${err}`);
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
    taskComparison: item.taskComparison
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
    appClient?.send(JSON.stringify(req));
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
