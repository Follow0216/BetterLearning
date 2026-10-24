/* 固定 DOM 布局后平移捕获每个有界题面瓦片，不依赖当前阅读视口。 */
(function () {
  'use strict';
  var state = { ready: false, error: '', bounds: null, blocks: [], tileReady: -1 };
  var content = document.getElementById('question-content');
  var frame = document.getElementById('raster-frame');
  function twoFrames(callback) { requestAnimationFrame(function () { requestAnimationFrame(callback); }); }
  function measure() {
    var left = 0, top = 0, right = content.offsetWidth, bottom = content.offsetHeight;
    var blocks = [];
    Array.prototype.forEach.call(content.children, function (element, index) {
      var rect = element.getBoundingClientRect();
      var x = rect.left, y = rect.top, r = rect.right, b = rect.bottom;
      Array.prototype.forEach.call(element.querySelectorAll('*'), function (child) {
        if (child.closest('.katex-mathml')) { return; }
        var c = child.getBoundingClientRect();
        x = Math.min(x, c.left); y = Math.min(y, c.top); r = Math.max(r, c.right); b = Math.max(b, c.bottom);
      });
      left = Math.min(left, x); top = Math.min(top, y); right = Math.max(right, r); bottom = Math.max(bottom, b);
      blocks.push({ blockId: 'question_' + index, x: x, y: y, width: Math.max(1, r - x), height: Math.max(1, b - y) });
    });
    var x = Math.floor(left), y = Math.floor(top);
    state.bounds = { x: x, y: y, width: Math.ceil(right) - x, height: Math.ceil(bottom) - y };
    state.blocks = blocks;
    state.ready = true;
  }
  window.BetterLearningRaster = {
    prepare: function (markdown, width, fontSize) {
      state = { ready: false, error: '', bounds: null, blocks: [], tileReady: -1 };
      if (!window.BetterLearningQuestion.render(markdown, fontSize)) { state.error = '题面渲染失败'; return false; }
      content.style.width = width + 'px';
      content.style.transform = 'translate(0px,0px)';
      Promise.resolve(document.fonts.ready).then(function () { twoFrames(measure); }).catch(function () { state.error = '题面字体加载失败'; });
      return true;
    },
    tile: function (x, y, width, height, id) {
      state.tileReady = -1;
      frame.style.width = width + 'px'; frame.style.height = height + 'px';
      content.style.transform = 'translate(' + (-x) + 'px,' + (-y) + 'px)';
      twoFrames(function () { state.tileReady = id; });
      return true;
    },
    status: function () { return state; }
  };
}());
