import assert from 'node:assert';
import { afterEach, beforeEach, describe, mock, test } from 'node:test';
import AgentInputBuilder, { inputType } from './agent-input-builder.js';

describe('AgentInputBuilder', () => {
  let builder;
  const changeEvent = {
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

  beforeEach(() => {
    builder = new AgentInputBuilder('/project/root', 1000);
    mock.timers.enable({ apis: ['setTimeout'] });
  });

  afterEach(() => {
    mock.timers.reset();
  });

  test('builds change operations after debounce', (t, done) => {
    builder.onAgentInput((input) => {
      assert.strictEqual(
        input,
        `
Driver operations:
- Changed Main.java around line 0
        `.trim()
      );
      setImmediate(done);
    });

    builder.ingest({
      type: inputType.LSP,
      content: changeEvent
    });

    mock.timers.tick(1000);
  });

  test('does not build change operations before debounce period', (t) => {
    let callCnt = 0;

    builder.onAgentInput((input) => {
      callCnt++;
    });

    builder.ingest({
      type: inputType.LSP,
      content: changeEvent
    });

    mock.timers.tick(999);

    assert.strictEqual(callCnt, 0);
  });
});

