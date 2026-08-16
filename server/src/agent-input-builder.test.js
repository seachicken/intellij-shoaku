import assert from 'node:assert';
import { afterEach, beforeEach, describe, mock, test } from 'node:test';
import AgentInputBuilder, { inputType } from './agent-input-builder.js';

describe('AgentInputBuilder', () => {
  let builder;
  const fileChangeEvent = {
    jsonrpc: '2.0',
    method: 'textDocument/didChange',
    params: {
      textDocument: {
        version: 25,
        uri: 'file:///project/root/Main.java'
      },
      contentChanges: [
        {
          range: {
            start: { line: 0, character: 0 },
            end: { line: 0, character: 2}
          },
          text: ''
        }
      ]
    }
  };
  const goalChangeEvent = {
    type: 'text',
    content: 'Goal',
    line: 0,
    children: [
      {
        type: 'text',
        content: 'Task',
        line: 1,
        children: []
      }
    ],
    checked: false,
    shoakuId: 'shoaku-xxx',
    labelPosition: { line: 0, start: 10, end: 15 },
    messages: []
  }

  beforeEach(() => {
    builder = new AgentInputBuilder('/project/root', 1000);
    mock.timers.enable({ apis: ['setTimeout'] });
  });

  afterEach(() => {
    mock.timers.reset();
  });

  test('builds file change operations after debounce', (t, done) => {
    builder.onAgentInput((input) => {
      assert.strictEqual(
        input,
        [
          'Driver operations:',
          '- Changed Main.java around line 0'
        ].join('\n')
      );
      setImmediate(done);
    });

    builder.ingest({
      type: inputType.LSP,
      content: fileChangeEvent
    });

    mock.timers.tick(1000);
  });

  test('does not build file change operations before debounce period', (t) => {
    let callCnt = 0;
    builder.onAgentInput((input) => {
      callCnt++;
    });

    builder.ingest({
      type: inputType.LSP,
      content: fileChangeEvent
    });

    mock.timers.tick(999);

    assert.strictEqual(callCnt, 0);
  });

  test('builds goal change operations after debounce', (t, done) => {
    builder.onAgentInput((input) => {
      assert.deepStrictEqual(countChangeLines(input), { added: 19, removed: 0 });
      setImmediate(done);
    });

    builder.ingest({
      type: inputType.GOAL_HUMAN,
      content: goalChangeEvent
    });

    mock.timers.tick(1000);
  });

  test('does not build goal change operations before debounce period', (t) => {
    let callCnt = 0;
    builder.onAgentInput((input) => {
      callCnt++;
    });

    builder.ingest({
      type: inputType.GOAL_HUMAN,
      content: goalChangeEvent
    });

    mock.timers.tick(999);

    assert.strictEqual(callCnt, 0);
  });

  test('builds goal add operation', async () => {
    let resolveInput;
    const waitForInput = () => new Promise((resolve) => {
      resolveInput = resolve;
    });

    builder.onAgentInput((input) => {
      resolveInput(input);
    });

    builder.ingest({
      type: inputType.GOAL_HUMAN,
      content: goalChangeEvent
    });

    let wait = waitForInput();
    mock.timers.tick(1000);
    await wait;

    let content = structuredClone(goalChangeEvent);
    content.children.push(
      {
        type: 'text',
        content: 'Task 2',
        line: 2,
        children: []
      }
    );
    builder.ingest({
      type: inputType.GOAL_HUMAN,
      content
    });

    wait = waitForInput();
    mock.timers.tick(1000);
    const input = await wait;

    assert.deepStrictEqual(countChangeLines(input), { added: 6, removed: 0 });
  });

  test('does not build goal change operations when active goal has not changed', async () => {
    let callCnt = 0;
    let resolveInput;
    const waitForInput = () => new Promise((resolve) => {
      resolveInput = resolve;
    });

    builder.onAgentInput((input) => {
      callCnt++;
      resolveInput(input);
    });

    builder.ingest({
      type: inputType.GOAL_HUMAN,
      content: goalChangeEvent
    });

    let wait = waitForInput();
    mock.timers.tick(1000);
    await wait;

    builder.ingest({
      type: inputType.GOAL_HUMAN,
      content: goalChangeEvent
    });

    mock.timers.tick(1000);
    await Promise.resolve();

    assert.strictEqual(callCnt, 1);
  });
});

function countChangeLines(diff) {
  let result = {
    added: 0,
    removed: 0
  };
  const lines = diff.split('\n');
  for (const line of lines) {
    if (line.startsWith('+ ')) {
      result.added++;
    }
    if (line.startsWith('- ')) {
      result.removed++;
    }
  }
  return result;
}

