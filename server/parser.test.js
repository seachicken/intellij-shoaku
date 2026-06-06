import assert from 'node:assert';
import test from 'node:test';
import parser from './parser.js';

test('parse when root', (t) => {
  assert.deepStrictEqual(parser.parse(`
- [shoaku] a
`), [
      {
        type: 'text',
        content: 'a',
        line: 2,
        children: []
      }
    ]
  );
});

test('parse when nested', (t) => {
  assert.deepStrictEqual(parser.parse(`
- a
  - [shoaku] b
`), [
      {
        type: 'text',
        content: 'b',
        line: 3,
        children: []
      }
    ]
  );
});

test('parse when only labeled root', (t) => {
  assert.deepStrictEqual(parser.parse(`
- [shoaku] a
- b
`), [
      {
        type: 'text',
        content: 'a',
        line: 2,
        children: []
      }
    ]
  );
});

test('not parse', (t) => {
  assert.deepStrictEqual(parser.parse(`
- a
`), [
    ]
  );
});

test('parse non-checkd item', (t) => {
  assert.deepStrictEqual(parser.parse(`
- [ ] [shoaku] a
`), [
      {
        type: 'text',
        content: 'a',
        line: 2,
        checked: false,
        children: []
      }
    ]
  );
});

test('parse checkd item', (t) => {
  assert.deepStrictEqual(parser.parse(`
- [x] [shoaku] a
`), [
      {
        type: 'text',
        content: 'a',
        line: 2,
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
`), [
      {
        type: 'text',
        content: 'a',
        line: 2,
        children: [
          {
            type: 'text',
            content: 'a_a',
            line: 3,
            children: []
          }
        ]
      }
    ]
  );
});

test('parse linked nodes', {only: true}, (t) => {
  assert.deepStrictEqual(parser.parse(`
- [shoaku-aA1234] a
  - a_a
`), [
      {
        shoakuId: 'shoaku-aA1234',
        type: 'text',
        content: 'a',
        line: 2,
        children: [
          {
            type: 'text',
            content: 'a_a',
            line: 3,
            children: []
          }
        ]
      }
    ]
  );
});
