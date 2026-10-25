#!/usr/bin/env node
// Pure renderer reproduction: actual local assets, DOM assignment stubbed; no browser/build/device/network.
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');

const root = path.resolve(__dirname, '../src/main/resources/rawfile/question-markdown');
const attributes = {};
const content = { innerHTML: '', style: {}, setAttribute(name, value) { attributes[name] = value; } };
const sandbox = {
  window: {
    markdownit: require(path.join(root, 'vendor/markdown-it.min.js')),
    texmath: require(path.join(root, 'vendor/texmath.js')),
    katex: require(path.join(root, 'vendor/katex.min.js')),
    scrollTo() {}
  },
  document: { getElementById: () => content, documentElement: { style: {} } }
};
vm.runInNewContext(fs.readFileSync(path.join(root, 'renderer.js'), 'utf8'), sandbox);
function render(markdown) {
  assert.equal(sandbox.window.BetterLearningQuestion.render(markdown, 16), true);
  assert.equal(attributes['data-render-state'], 'ready');
  return content.innerHTML;
}
function blocks(html) { return (html.match(/class="math-block/g) || []).length; }
let cases = 0;
function check(name, run) {
  try { run(); cases++; }
  catch (error) { throw new Error(name + ': ' + error.message, { cause: error }); }
}
for (const [name, markdown] of Object.entries({
  'list immediately after prose': '1. text  \n   \\[\n   x=1\n   \\]\n',
  'top immediately after prose': 'text\n\\[\nx=1\n\\]\n',
  'list after blank line': '1. text\n\n   \\[x=1\\]\n',
  'bullet list': '- text\n  \\[x=1\\]\n',
  'nested list': '1. outer\n   - inner\n     \\[x=1\\]\n',
  'blockquote': '> text\n> \\[\n> x=1\n> \\]\n',
  'three-space top block': 'text\n   \\[x=1\\]\n'
})) {
  check(name, () => {
    const html = render(markdown);
    assert.equal(blocks(html), 1); assert.equal(attributes['data-formula-errors'], '0');
    assert.ok(!html.includes('\\['));
  });
}
check('multiple blocks preserve list siblings and numbering', () => {
  const html = render('1. first\n   \\[x=1\\]\n   middle\n   \\[y=2\\]\n2. second\n');
  assert.equal(blocks(html), 2); assert.equal((html.match(/<li>/g) || []).length, 2);
  assert.ok(html.indexOf('middle') > html.indexOf('</section>'));
  assert.ok(html.indexOf('second') > html.lastIndexOf('</section>'));
});
check('equation number', () => { assert.ok(render('text\n\\[x=1\\] (A)\n').includes('equation-number">(A)')); });
for (const [name, markdown] of Object.entries({
  'fenced code': '```tex\n\\[x\\]\n```\n',
  'indented code': '    \\[x\\]\n',
  'list indented code': '1. text\n\n       \\[x\\]\n',
  'inline code': '`\\[x\\]`\n',
  'escaped opening': '\\\\[x\\\\]\n',
  'unclosed formula': 'text\n\\[\nx=1\n'
})) {
  check(name, () => { assert.equal(blocks(render(markdown)), 0); });
}
check('formula cannot cross a sibling list item', () => {
  const html = render('1. \\[\n   x\n2. y\n   \\]\n');
  assert.equal(blocks(html), 0); assert.equal((html.match(/<li>/g) || []).length, 2);
});
check('formula cannot cross blockquote lazy continuation', () => {
  assert.equal(blocks(render('> text\n> \\[\n> x\nplain\n\\]\n')), 0);
});
check('inline math and existing dollars', () => {
  const html = render('text \\(x=1\\) and $y=2$\n\n$$\nz=3\n$$\n');
  assert.equal((html.match(/<eq>/g) || []).length, 2); assert.equal(blocks(html), 1);
  assert.equal(attributes['data-formula-errors'], '0');
});
check('ordinary Markdown', () => {
  const html = render('1. **bold** and `code`\n2. second\n\n- bullet\n');
  assert.equal(blocks(html), 0); assert.equal((html.match(/<li>/g) || []).length, 3);
  assert.ok(html.includes('<strong>bold</strong>')); assert.ok(html.includes('<code>code</code>'));
});
check('invalid formula remains an explicit formula error', () => {
  assert.ok(render('text\n\\[\\frac{\\]\n').includes('class="formula-error"'));
  assert.equal(attributes['data-formula-errors'], '1');
});
let answerBlocks;
if (process.argv[2]) {
  // Only select turn.answerMarkdown; never print or copy the surrounding integration record.
  const markdown = JSON.parse(fs.readFileSync(process.argv[2], 'utf8')).turn.answerMarkdown;
  assert.equal(typeof markdown, 'string');
  check('provided real answer', () => {
    const expected = markdown.split('\\[').length - 1;
    const html = render(markdown); answerBlocks = blocks(html);
    assert.equal(answerBlocks, expected); assert.ok(!html.includes('\\['));
    assert.equal(attributes['data-formula-errors'], '0');
  });
}
console.log(JSON.stringify({ cases, result: 'PASS', answerBlocks,
  boundary: 'pure renderer with local assets; DOM assignment stubbed, browser layout/build/device not exercised' }));
