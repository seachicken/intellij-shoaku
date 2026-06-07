import assert from 'node:assert';
import test from 'node:test';
import parser from './parser.js';

test('parse when root', (t) => {
  assert.deepStrictEqual(parser.parse(`
- [shoaku] a
`.trimStart()), [
      {
        type: 'text',
        content: 'a',
        line: 0,
        labelPosition: { line: 0, start: 2, end: 10 },
        children: []
      }
    ]
  );
});

test('parse when nested', (t) => {
  assert.deepStrictEqual(parser.parse(`
- a
  - [shoaku] b
`.trimStart()), [
      {
        type: 'text',
        content: 'b',
        line: 1,
        labelPosition: { line: 1, start: 4, end: 12 },
        children: []
      }
    ]
  );
});

test('parse when only labeled root', (t) => {
  assert.deepStrictEqual(parser.parse(`
- [shoaku] a
- b
`.trimStart()), [
      {
        type: 'text',
        content: 'a',
        line: 0,
        labelPosition: { line: 0, start: 2, end: 10 },
        children: []
      }
    ]
  );
});

test('not parse', (t) => {
  assert.deepStrictEqual(parser.parse(`
- a
`.trimStart()), [
    ]
  );
});

test('parse non-checkd item', (t) => {
  assert.deepStrictEqual(parser.parse(`
- [ ] [shoaku] a
`.trimStart()), [
      {
        type: 'text',
        content: 'a',
        line: 0,
        labelPosition: { line: 0, start: 6, end: 14 },
        checked: false,
        children: []
      }
    ]
  );
});

test('parse checkd item', (t) => {
  assert.deepStrictEqual(parser.parse(`
- [x] [shoaku] a
`.trimStart()), [
      {
        type: 'text',
        content: 'a',
        line: 0,
        labelPosition: { line: 0, start: 6, end: 14 },
        checked: true,
        children: []
      }
    ]
  );
});

test('parse nested nodes', (t) => {
  assert.deepStrictEqual(parser.parse(`
- [shoaku] a
  - a_a
`.trimStart()), [
      {
        type: 'text',
        content: 'a',
        line: 0,
        labelPosition: { line: 0, start: 2, end: 10 },
        children: [
          {
            type: 'text',
            content: 'a_a',
            line: 1,
            children: []
          }
        ]
      }
    ]
  );
});

test('parse leading shoaku label', (t) => {
  assert.deepStrictEqual(parser.parse(`
- [shoaku-aA1234] a
  - a_a
`.trimStart()), [
      {
        shoakuId: 'shoaku-aA1234',
        type: 'text',
        content: 'a',
        line: 0,
        labelPosition: { line: 0, start: 2, end: 17 },
        children: [
          {
            type: 'text',
            content: 'a_a',
            line: 1,
            children: []
          }
        ]
      }
    ]
  );
});

test('parse trailing shoaku label', (t) => {
  assert.deepStrictEqual(parser.parse(`
- a [shoaku-aA1234]
  - a_a
`.trimStart()), [
      {
        shoakuId: 'shoaku-aA1234',
        type: 'text',
        content: 'a',
        line: 0,
        labelPosition: { line: 0, start: 4, end: 19 },
        children: [
          {
            type: 'text',
            content: 'a_a',
            line: 1,
            children: []
          }
        ]
      }
    ]
  );
});
