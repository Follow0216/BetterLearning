/* M1 本地渲染适配。依赖只从获批发行资源加载，不读取账号或访问网络。 */
(function () {
  'use strict';
  var content = document.getElementById('question-content');
  var parser = null;
  var formulaErrors = 0;

  function renderMath(tokens, index, displayMode, numbered, inline) {
    var token = tokens[index];
    var html;
    try {
      // 每次使用独立 macros，避免一道题的全局宏影响后续题面。
      html = window.katex.renderToString(token.content, {
        displayMode: displayMode,
        throwOnError: true,
        trust: false,
        strict: 'ignore',
        maxSize: 10,
        maxExpand: 1000,
        macros: {},
        output: 'htmlAndMathml'
      });
    } catch (error) {
      formulaErrors++;
      html = '<span class="formula-error" role="note">公式格式有误：<code>' +
        parser.utils.escapeHtml(token.content) + '</code></span>';
    }
    if (inline) {
      return '<eq>' + html + '</eq>';
    }
    var number = numbered && token.info ?
      '<span class="equation-number">(' + parser.utils.escapeHtml(token.info) + ')</span>' : '';
    return '<section class="math-block' + (number ? ' has-number' : '') + '">' + html + number + '</section>';
  }

  function bracketBlock(rule) {
    var parse = window.texmath.block(rule);
    return function (state, startLine, endLine, silent) {
      // 段落探测也会经过此处；不要把缩进代码或相邻列表项/引用里的闭符吞进公式。
      if (state.sCount[startLine] - state.blkIndent >= 4) { return false; }
      var pos = state.bMarks[startLine] + state.tShift[startLine];
      if (!state.src.startsWith(rule.tag, pos)) { return false; }
      rule.rex.lastIndex = pos;
      if (!rule.rex.exec(state.src)) { return false; }
      var last = rule.rex.lastIndex - 1;
      for (var line = startLine; line < endLine; line++) {
        if (!state.isEmpty(line) && state.sCount[line] < state.blkIndent) { return false; }
        if (last <= state.eMarks[line]) { return parse(state, startLine, endLine, silent); }
      }
      return false;
    };
  }

  function createParser() {
    if (typeof window.markdownit !== 'function' || typeof window.texmath !== 'function' ||
        !window.katex || typeof window.katex.renderToString !== 'function') {
      return null;
    }
    var md = window.markdownit({ html: false, linkify: false, typographer: false, breaks: true });
    md.use(window.texmath, { engine: window.katex, delimiters: ['dollars'] });
    // texmath 1.0.0 的块规则未声明 paragraph 中断；紧接文字的块公式会被当成普通段落。
    // 单独注册 brackets，避免与 dollars 的同名规则冲突，token 名仍沿用现有 renderer。
    window.texmath.rules.brackets.inline.forEach(function (rule) {
      md.inline.ruler.before('escape', 'brackets_' + rule.name, window.texmath.inline(rule));
    });
    window.texmath.rules.brackets.block.forEach(function (rule) {
      md.block.ruler.before('fence', 'brackets_' + rule.name, bracketBlock(rule), { alt: ['paragraph'] });
    });
    // texmath 负责识别定界符；此处将错误公式显示为明确提示，避免把原 LaTeX 当渲染结果。
    md.renderer.rules.math_inline = function (tokens, index) { return renderMath(tokens, index, false, false, true); };
    md.renderer.rules.math_inline_double = function (tokens, index) { return renderMath(tokens, index, true, false, true); };
    md.renderer.rules.math_block = function (tokens, index) { return renderMath(tokens, index, true, false, false); };
    md.renderer.rules.math_block_eqno = function (tokens, index) { return renderMath(tokens, index, true, true, false); };
    // M1 只读题面不打开链接或加载外部图片，保留安全转义后的可读文字。
    md.renderer.rules.link_open = function () { return '<span class="markdown-link">'; };
    md.renderer.rules.link_close = function () { return '</span>'; };
    md.renderer.rules.image = function (tokens, index) {
      var description = md.utils.escapeHtml(tokens[index].content || '图片');
      return '<span class="image-description">[图片：' + description + ']</span>';
    };
    return md;
  }

  window.BetterLearningQuestion = {
    render: function (markdown, fontSize) {
      if (!content || typeof markdown !== 'string' || markdown.length > 20000) {
        return false;
      }
      try {
        if (!parser) {
          parser = createParser();
        }
        if (!parser) {
          content.textContent = '题面渲染资源暂时不可用';
          return false;
        }
        formulaErrors = 0;
        var html = parser.render(markdown);
        document.documentElement.style.fontSize =
          (Number.isFinite(fontSize) ? Math.max(12, Math.min(32, fontSize)) : 16) + 'px';
        content.innerHTML = html;
        content.setAttribute('data-formula-errors', String(formulaErrors));
        content.setAttribute('data-render-state', 'ready');
        window.scrollTo(0, 0);
        return true;
      } catch (error) {
        content.textContent = '题面暂时无法显示，请重试';
        content.setAttribute('data-render-state', 'error');
        return false;
      }
    }
  };
}());
