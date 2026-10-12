import test from 'node:test';
import assert from 'node:assert/strict';
import { mkdtemp, readFile, rm } from 'node:fs/promises';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { inferGoalMode, parsePrTarget, preparePrReview, savePrDesign } from './pr-review.js';
test('review requires one explicit PR, including nested notes', () => {
  assert.deepEqual(parsePrTarget({children:[{content:'https://github.com/org/repo/pull/12'}]}), {owner:'org',repo:'repo',number:'12'});
  assert.throws(() => parsePrTarget({content:'https://github.com/org/repo'}));
  assert.throws(() => parsePrTarget({content:'https://github.com/org/repo/pull/1 https://github.com/org/repo/pull/2'}));
});
test('pins fork head and merge base and validates full PR anchors', async () => {
  const workspace = await mkdtemp(join(tmpdir(), 'shoaku-pr-test-'));
  const head='a'.repeat(40), tip='b'.repeat(40), base='c'.repeat(40), commands=[];
  const run = async (name,args) => {
    commands.push([name,args]);
    if(name==='gh') return JSON.stringify({title:'Change',html_url:'https://github.com/org/repo/pull/1',base:{sha:tip},head:{sha:head,repo:{full_name:'fork/repo'}}});
    if(args.includes('merge-base')) return base;
    if(args.includes('--name-only')) {assert(args.includes(base));assert(args.includes(head));return 'source.js\0';}
    if(args.includes('cat-file')) return '20';
    if(args.includes('show')) return 'before\nafter\n';
    return '';
  };
  try {
    const snapshot=await preparePrReview(workspace,{content:'https://github.com/org/repo/pull/1'},run);
    assert.equal(snapshot.base,base);assert.equal(snapshot.head,head);
    assert(commands.some(([,args])=>args.includes('https://github.com/fork/repo.git')&&args.includes(head)));
    assert(!commands.some(([,args])=>args.includes('checkout')||args.includes('commit')));
    const map={schemaVersion:1,commit:head,problem:'problem',before:'before',after:'after',steps:[{id:'decision',title:'Decision',why:'why',change:'change',verification:'unverified',anchors:[{path:'source.js',side:'before',startLine:1,endLine:2,label:'code'}],links:[]}],excludedFiles:[]};
    const response={overviewMd:'# Overview',designMd:'# Decision',reviewMapJson:JSON.stringify(map)};
    await savePrDesign(workspace,snapshot,response,run);
    assert.equal(await readFile(join(workspace,'.shoaku/RESULTS.md'),'utf8'),'# Overview');
    map.steps[0].anchors[0].endLine=99;
    await assert.rejects(savePrDesign(workspace,snapshot,{...response,designMd:'# Decision changed',reviewMapJson:JSON.stringify(map)},run),/does not exist/);
    assert.equal(await readFile(join(workspace,'.shoaku/commits',`${head}.md`),'utf8'), response.designMd);
    assert.equal(await readFile(join(workspace,'.shoaku/commits',`${head}.review.json`),'utf8'), response.reviewMapJson);
  } finally {await rm(workspace,{recursive:true,force:true});}
});

test('infers existing PR explanation from context without falling back to implementation', () => {
  assert.equal(inferGoalMode({content:'Implement pagination'}), 'implementation');
  assert.equal(inferGoalMode({content:'Explain',children:[{content:'https://github.com/org/repo/pull/12'}]}), 'review');
  const ambiguous = {content:'https://github.com/org/repo/pull/1 https://github.com/org/repo/pull/2'};
  assert.equal(inferGoalMode(ambiguous), 'review');
  assert.throws(() => parsePrTarget(ambiguous));
  const malformed = {content:'https://github.com/org/repo/pull/abc'};
  assert.equal(inferGoalMode(malformed), 'review');
  assert.throws(() => parsePrTarget(malformed));
});
