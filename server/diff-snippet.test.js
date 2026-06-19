import assert from 'node:assert';
import test from 'node:test';
import { renderSummary, renderSummaryDiff } from './diff-snippet.js';

test('insert summary when parent has no children',{only:true}, (t) => {
  assert.deepStrictEqual(renderSummary(`
- a [shoaku-aA1234]
`.trimStart(), 'shoaku-aA1234', '{\"summary\":[\"summary\"]}'), `
- a [shoaku-aA1234]
  - summary by AI
`.trimStart());
});

test('insert summary after existing children', (t) => {
  assert.deepStrictEqual(renderSummary(`
- a [shoaku-aA1234]
  - b
    - c
  - d
`.trimStart(), 'shoaku-aA1234', '{\"summary\":[\"summary\"]}'), `
- a [shoaku-aA1234]
  - b
    - c
  - d
  - summary by AI
`.trimStart());
});

test('insert multi-line summary', (t) => {
  assert.deepStrictEqual(renderSummary(`
- a [shoaku-aA1234]
`.trimStart(), 'shoaku-aA1234', '{\"summary\":[\"summary A\",\"summary B\"]}'), `
- a [shoaku-aA1234]
  - summary A by AI
  - summary B by AI
`.trimStart());
});

test('insert with tab indent', (t) => {
  assert.deepStrictEqual(renderSummary(`
- a [shoaku-aA1234]
	- b
`.trimStart(), 'shoaku-aA1234', '{\"summary\":[\"summary\"]}'), `
- a [shoaku-aA1234]
	- b
	- summary by AI
`.trimStart());
});

test('insert summary with unified lines', (t) => {
  assert.deepStrictEqual(renderSummaryDiff(`
- 0
- 1
- a [shoaku-aA1234]
- 3
- 4
`.trimStart(), 'shoaku-aA1234', '{\"summary\":[\"summary\"]}', { unified: 1 }), `
- 1
- a [shoaku-aA1234]
+  - summary by AI
- 3
`.trim());
});

