const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');
const assert = require('node:assert/strict');
const root = process.argv[2];
const loader = '/Applications/DevEco-Studio.app/Contents/sdk/default/openharmony/ets/build-tools/ets-loader';
const ts = require(path.join(loader, 'node_modules/typescript'));
const options = JSON.parse(fs.readFileSync(path.join(loader, 'tsconfig.json'), 'utf8')).compilerOptions;
const base = path.join(root, 'client/apps/phone/src/main/ets');
const parsed = new Map();
for (const file of ['components/PhoneApp.ets', 'components/ManualEntry.ets', 'components/QuestionLibrary.ets', 'components/AccountPage.ets', 'theme/PhoneTheme.ets']) {
  const ast = ts.createSourceFile(file, fs.readFileSync(path.join(base, file), 'utf8'), ts.ScriptTarget.Latest, true, ts.ScriptKind.ETS, options);
  assert.equal(ast.parseDiagnostics.length, 0, file);
  parsed.set(file, ast);
}
function harness(file, name) {
  const ast = parsed.get(file);
  const component = ast.statements.find(node => node.kind === ts.SyntaxKind.StructDeclaration);
  const keep = component.members.filter(member => member.name && member.name.getText(ast) !== 'build' &&
    !(ts.getDecorators(member) || []).some(d => d.expression.getText(ast) === 'Builder'));
  const source = ast.statements.filter(ts.isEnumDeclaration).map(n => n.getText(ast)).join('\n') +
    '\nclass ' + name + ' {\n' + keep.map(n => n.getText(ast).replace(/@(State|Prop)\s*/g, '')
      .replace(/@Watch\([^)]*\)\s*/g, '').replace(/@StorageProp\([^)]*\)\s*/g, '')).join('\n') + '\n}\nglobalThis.Result = '+name+';';
  const js = ts.transpileModule(source, {compilerOptions: {target: ts.ScriptTarget.ES2020}}).outputText;
  const context = {PhoneBackController: class {handleBack = () => false;},
    ToastStore: class {onScopeChange() {} captureScope() {return 0;} clearScope() {} show() {} dispose() {}},
    phone_v2_clear_all_intents: () => {}, citationFocusController: {clear: () => {},restore: () => false},
    LearningSubject: {HIGHER_MATHEMATICS: 'math'}, LearningApi: {create_default: () => ({})},
    OcrApi: {create_default: () => ({})}, TextAreaController: class {stopEditing() {}},
    KeyboardAvoidMode: {OFFSET: 0, RESIZE: 1}, authSession: {read_access_token: () => 'test', read_user: () => ({accountId:'test'})}};
  vm.createContext(context); vm.runInContext(js, context);
  return new context.Result();
}
let checks = 0;
function check(name, fn) {fn(); checks++; console.log('PASS '+name);}
const app = harness('components/PhoneApp.ets', 'App');
app.isAuthenticated = true;
check('登录后仍默认AI首页', () => {assert.equal(app.activeTab, 'home'); assert(app.show_bottom_nav());});
check('首页输入与阅读隐藏导航，其他根页不受隐藏首页旧状态影响', () => {
  app.homeComposing = true; assert(!app.show_bottom_nav());
  app.homeComposing = false; app.homeFullscreen = true; assert(!app.show_bottom_nav());
  app.select_tab('library'); assert(app.show_bottom_nav());
  app.select_tab('account'); app.homeComposing = true; assert(app.show_bottom_nav());
});
check('打开录题不复用旧SOURCE标志，入口只在收到当前状态后显示导航', () => {
  app.entryNavigationVisible = true; app.open_entry();
  assert(app.entryVisible); assert(!app.entryNavigationVisible); assert(!app.show_bottom_nav());
  app.entryNavigationVisible = true; assert(app.show_bottom_nav());
});
check('录题切其他根页及返回AI保留题目会话身份', () => {
  app.homeQuestionId = 'question-existing'; app.homeRequestSerial = 3;
  app.select_tab('library'); assert(!app.entryVisible); assert(!app.entryNavigationVisible);
  app.open_entry(); app.entryNavigationVisible = true; app.return_home();
  assert.equal(app.activeTab, 'home'); assert(!app.entryVisible); assert.equal(app.homeQuestionId, 'question-existing'); assert.equal(app.homeRequestSerial, 3);
});
check('系统返回录题交给内部层级处理，不绕过忙时保护', () => {
  app.open_entry(); let delegated = 0; app.entryNavigation.handleBack = () => {delegated++; return true;};
  assert(app.handle_back()); assert.equal(delegated, 1); assert(app.entryVisible);
  app.return_home();
});
check('作答与详情继续隐藏底栏且逐层返回', () => {
  app.select_tab('library'); app.detailQuestionId = 'question-existing'; assert(!app.show_bottom_nav());
  app.answerQuestionId = 'question-existing'; app.answerTurnId = 'turn-existing'; assert(!app.show_bottom_nav());
  assert(app.handle_back()); assert.equal(app.answerQuestionId, ''); assert.equal(app.answerTurnId, ''); assert.equal(app.detailQuestionId, 'question-existing');
  assert(app.handle_back()); assert.equal(app.detailQuestionId, ''); assert.equal(app.activeTab, 'library');
  assert(app.handle_back()); assert.equal(app.activeTab, 'home');
});
check('AI内部返回继续优先处理，根首页才交给系统', () => {
  app.homeNavigation.handleBack = () => true; assert(app.handle_back());
  app.homeNavigation.handleBack = () => false; assert.equal(app.handle_back(), false);
});
check('P5笔记及作答覆盖层保持首页v4导航并逐层返回', () => {
  app.activeTab = 'home'; app.homeComposing = false; app.homeFullscreen = false;
  app.notesOpen = true; app.notesBackToken = 0;
  assert(!app.show_bottom_nav()); assert(app.handle_back());
  assert.equal(app.notesBackToken, 1); assert.equal(app.notesOpen, true);
  app.answerQuestionId = '11'; app.answerTurnId = 'run-1';
  assert(!app.show_bottom_nav()); assert(app.handle_back());
  assert.equal(app.answerQuestionId, ''); assert.equal(app.answerTurnId, ''); assert.equal(app.notesOpen, true);
  app.notesOpen = false; assert(app.show_bottom_nav());
});
const entry = harness('components/ManualEntry.ets', 'Entry');
const reports = []; entry.onNavigationVisibilityChanged = value => reports.push(value);
check('隐藏或销毁的录题实例不能报告导航', () => {
  entry.active = false; entry.report_navigation_visibility(); assert.equal(reports.length, 0);
  entry.active = true; entry.alive = false; entry.report_navigation_visibility(); assert.equal(reports.length, 0);
  entry.alive = true; entry.report_navigation_visibility(); assert.equal(reports.pop(), true);
});
check('SOURCE遮罩/编辑/选图/保存每项都抑制全局导航', () => {
  for (const flag of ['editorFocused','sourcePicker','discardPrompt','imageLoading','submitting']) {
    entry[flag] = true; entry.report_navigation_visibility(); assert.equal(reports.pop(), false, flag);
    entry[flag] = false; entry.report_navigation_visibility(); assert.equal(reports.pop(), true, flag);
  }
});
check('批量和手动校对层级隐藏导航，回到SOURCE重新显示', () => {
  for (const screen of ['BATCH','REVIEW']) {entry.screen = screen; entry.report_navigation_visibility(); assert.equal(reports.pop(), false);}
  entry.screen = 'SOURCE'; entry.report_navigation_visibility(); assert.equal(reports.pop(), true);
});
check('暂离清编辑状态但保留录题正文和未决保存身份', () => {
  entry.active = false; entry.editorFocused = true; entry.bodyMarkdown = '本轮未保存内容'; entry.pendingInput = {requestId: 'same-request'};
  const count = reports.length; entry.on_active_changed();
  assert.equal(entry.editorFocused, false); assert.equal(entry.bodyMarkdown, '本轮未保存内容'); assert.equal(entry.pendingInput.requestId, 'same-request'); assert.equal(reports.length, count);
});
console.log(JSON.stringify({result:'PASS',scenarios:checks,syntaxFiles:parsed.size,limits:'Actual source methods with isolated state; no ArkUI Watch scheduling, UI geometry or native device simulation.'}));
