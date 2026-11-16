/* LimingjieAssistant 黎明界只读探针（mb1）
 *
 * 只读当前视图：ViewManager.Instance → currentView 字段 → 视图类名 + CurrentViewId，
 * 以及 DialogArea 活动弹窗计数（供宿主决定"视图可短路 / 弹窗交视觉"）。
 * 零 hook、零写内存；类名/字段偏移全部在运行时经 il2cpp API 解析，不硬编码 RVA。
 *
 * 运行方式：frida-inject 注入游戏进程；本脚本回连 127.0.0.1:BRIDGE_PORT（经 adb reverse
 * 转发到宿主）。协议：一行一条 JSON（UTF-8，\n 结尾）。
 *   收到 {"id":N,"fn":"page","args":[]} → 回 {"id":N,"ok":true,"data":{...}} / {"id":N,"ok":false,"err":"..."}
 *   连上后先发 {"event":"hello","pid":...,"build":...}
 *
 * 继承自 PC 版探针实测的四条铁律（违反任意一条都会卡死游戏或静默失效）：
 *   1. Frida Socket.connect 只接受对象参数 {host, port} 且返回 Promise（要 await）；
 *      output.write 只吃 buffer-like（Uint8Array）。
 *   2. RPC 线程 ≠ 游戏主线程：runtime_invoke 前必须补齐 ART 线程上下文（withArt），
 *      否则 GC stop-the-world 等不到本线程到达安全点 → 游戏主线程死等 → 画面卡死。
 *   3. currentView / DialogArea 子对象可能刚被销毁：解引用只用 NativePointer 守卫式
 *      read*（非法访问抛 JS 异常、可 catch）；对半死对象的 invoke 会返回垃圾值而非崩溃
 *      （Unity 堆很少归还 OS），所有按 childCount 展开的地方一律先过 ≤512 的闸门。
 *   4. 类名读取不假设 Il2CppClass 布局（实测某国服客户端 name 不在 0x8）：初始化时用
 *      il2cpp_class_get_name 的结果在 klass 头部扫描校准 name 偏移；校准失败则停用类名
 *      读取、只回 viewId —— 上层据此走视觉降级。
 */

var BRIDGE_PORT = 27183; // 由宿主在推送前按会话端口重写此行
var PROBE_BUILD = 'mb1';
var MAX_CHILD_COUNT = 512; // 铁律 3：正常 UI 单节点子数不可能上百，垃圾值一律按 0 处理

// ── il2cpp 运行时 ─────────────────────────────────────────────────────────────
var g_il2cpp = null;
var g_classes = null;      // Map<名称, 类指针>，一次性扫描后缓存
var g_vm = null;           // { getInstance, currentViewOff, viewIdOff }
var g_nameOff = -1;        // Il2CppClass.name 的实测偏移（运行时校准，不假设布局）
var g_dialog = null;       // { find, goKlass, transform }（缓存的长驻 DialogArea Transform）
var g_memo = {};           // "klassPtr:name:argc" → MethodInfo（避免每拍走方法链）

function initIl2Cpp() {
  if (g_il2cpp) return true;
  var m = Process.findModuleByName('libil2cpp.so');
  if (!m) return false;
  var need = ['il2cpp_domain_get', 'il2cpp_domain_get_assemblies', 'il2cpp_assembly_get_image',
    'il2cpp_image_get_class_count', 'il2cpp_image_get_class', 'il2cpp_class_get_name',
    'il2cpp_class_get_fields', 'il2cpp_class_get_method_from_name', 'il2cpp_field_get_name',
    'il2cpp_field_get_offset', 'il2cpp_runtime_invoke', 'il2cpp_thread_attach', 'il2cpp_string_new'];
  var e = function (n) { return m.findExportByName(n); };
  for (var i = 0; i < need.length; i++) if (!e(need[i])) return false;
  var domainGet = new NativeFunction(e('il2cpp_domain_get'), 'pointer', []);
  var domain = domainGet();
  if (domain.isNull()) return false; // 映射 ≠ 初始化完成；domain 可用才算就绪
  g_il2cpp = {
    domain_get: domainGet,
    domain_get_assemblies: new NativeFunction(e('il2cpp_domain_get_assemblies'), 'pointer', ['pointer', 'pointer']),
    assembly_get_image: new NativeFunction(e('il2cpp_assembly_get_image'), 'pointer', ['pointer']),
    image_get_class_count: new NativeFunction(e('il2cpp_image_get_class_count'), 'int', ['pointer']),
    image_get_class: new NativeFunction(e('il2cpp_image_get_class'), 'pointer', ['pointer', 'int']),
    class_get_name: new NativeFunction(e('il2cpp_class_get_name'), 'pointer', ['pointer']),
    class_get_fields: new NativeFunction(e('il2cpp_class_get_fields'), 'pointer', ['pointer', 'pointer']),
    method_from_name: new NativeFunction(e('il2cpp_class_get_method_from_name'), 'pointer', ['pointer', 'pointer', 'int']),
    field_get_name: new NativeFunction(e('il2cpp_field_get_name'), 'pointer', ['pointer']),
    field_get_offset: new NativeFunction(e('il2cpp_field_get_offset'), 'ulong', ['pointer']),
    runtime_invoke: new NativeFunction(e('il2cpp_runtime_invoke'), 'pointer', ['pointer', 'pointer', 'pointer', 'pointer']),
    thread_attach: new NativeFunction(e('il2cpp_thread_attach'), 'pointer', ['pointer']),
    string_new: new NativeFunction(e('il2cpp_string_new'), 'pointer', ['pointer']),
  };
  return true;
}

function buildClassCache() {
  var map = {};
  var sizeOut = Memory.alloc(8); sizeOut.writePointer(ptr(0));
  var assemblies = g_il2cpp.domain_get_assemblies(g_il2cpp.domain_get(), sizeOut);
  var count = sizeOut.readS32();
  if (count <= 0 || count > 4096) return false;
  for (var a = 0; a < count; a++) {
    var image = g_il2cpp.assembly_get_image(assemblies.add(a * Process.pointerSize).readPointer());
    if (image.isNull()) continue;
    var n = g_il2cpp.image_get_class_count(image);
    for (var c = 0; c < n; c++) {
      var klass = g_il2cpp.image_get_class(image, c);
      if (klass.isNull()) continue;
      var name = g_il2cpp.class_get_name(klass).readCString();
      if (name && map[name] === undefined) map[name] = klass;
    }
  }
  g_classes = map;
  return true;
}

function findClass(name) { return (g_classes && g_classes[name]) || null; }

function findFieldOffset(klass, names, containsNeedle) {
  var iter = Memory.alloc(8); iter.writePointer(ptr(0));
  var field = g_il2cpp.class_get_fields(klass, iter);
  var bestContains = null;
  while (!field.isNull()) {
    var name;
    try { name = g_il2cpp.field_get_name(field).readCString(); } catch (e) { name = ''; }
    var off = g_il2cpp.field_get_offset(field);
    for (var i = 0; i < names.length; i++) if (name === names[i]) return off;
    if (containsNeedle && name.indexOf(containsNeedle) >= 0 &&
        name.toLowerCase().indexOf('viewid') < 0 && bestContains === null) bestContains = off;
    field = g_il2cpp.class_get_fields(klass, iter);
  }
  return bestContains;
}

// ── invoke 基元 ───────────────────────────────────────────────────────────────
function methodOf(klass, name, argc) {
  var n = argc === undefined ? 0 : argc;
  var key = klass.toString() + ':' + name + ':' + n;
  var method = g_memo[key];
  if (method === undefined) {
    method = g_il2cpp.method_from_name(klass, Memory.allocUtf8String(name), n);
    g_memo[key] = method;
  }
  return (!method || method.isNull()) ? null : method;
}

// 对象的类指针 = 对象头 +0x0（守卫式读）
function klassOf(obj) {
  try { return obj.readPointer(); } catch (e) { return null; }
}

// 零参方法调用。对象可能已死：invoke 返回垃圾指针而非崩溃（铁律 3）。
function invoke0(obj, name) {
  var klass = klassOf(obj);
  if (!klass || klass.isNull()) return null;
  var method = methodOf(klass, name);
  if (!method) return null;
  var ex = Memory.alloc(8); ex.writePointer(ptr(0));
  try { return g_il2cpp.runtime_invoke(method, obj, ptr(0), ex); } catch (e) { return null; }
}

// 静态零参方法调用（get_Instance / GameObject.Find 等）。
function invokeStatic(method) {
  var ex = Memory.alloc(8); ex.writePointer(ptr(0));
  try { return g_il2cpp.runtime_invoke(method, ptr(0), ptr(0), ex); } catch (e) { return null; }
}

// runtime_invoke 值类型参数：args[i] 指向**原始值**（不是装箱对象），PC 版 intArg 同款。
function intArg(value) {
  var params = Memory.alloc(Process.pointerSize);
  var slot = Memory.alloc(8); slot.writeS32(value);
  params.writePointer(slot);
  return params;
}

function invokeStaticString(method, text) {
  var strPtr = g_il2cpp.string_new(Memory.allocUtf8String(text));
  var params = Memory.alloc(Process.pointerSize); params.writePointer(strPtr);
  var ex = Memory.alloc(8); ex.writePointer(ptr(0));
  try { return g_il2cpp.runtime_invoke(method, ptr(0), params, ex); } catch (e) { return null; }
}

// Il2CppString 原始布局读取（守卫式；长度越界视为垃圾返回 null）。
function readIl2CppString(strPtr) {
  if (!strPtr || strPtr.isNull()) return null;
  try {
    var len = strPtr.add(0x10).readS32();
    if (len < 0 || len > 4096) return null;
    return strPtr.add(0x14).readUtf16String(len);
  } catch (e) { return null; }
}

// ── ART 线程上下文（铁律 2）───────────────────────────────────────────────────
function withArt(fn) {
  try {
    if (typeof Java !== 'undefined' && Java.available) { Java.perform(fn); return; }
  } catch (e) {
    // debug 版 ART 的 suspend 检查点 brk 会被 Java 桥报成 "breakpoint triggered"（良性，操作已完成）
    if (String(e).indexOf('breakpoint triggered') >= 0) return;
  }
  try { g_il2cpp.thread_attach(g_il2cpp.domain_get()); } catch (e2) { }
  fn();
}

// ── Il2CppClass.name 偏移运行时校准（铁律 4）──────────────────────────────────
// 不同 il2cpp 版本的类结构布局不同（实测某国服客户端 name 不在 0x8）。
// 校准法：拿 il2cpp_class_get_name 的 API 结果当答案，在 klass 头部 0x00..0x40
// 逐 8 字节扫描指向同一字符串的指针 → 得到本客户端的 name 偏移。全程守卫式读。
function calibrateNameOffset() {
  var probeKlass = findClass('ViewManager') || null;
  if (!probeKlass) probeKlass = findClass('GameObject');
  if (!probeKlass) return false;
  var apiName;
  try { apiName = g_il2cpp.class_get_name(probeKlass).readCString(); } catch (e) { return false; }
  if (!apiName) return false;
  for (var off = 0; off <= 0x40; off += 8) {
    try {
      var p = probeKlass.add(off).readPointer();
      if (p.isNull()) continue;
      if (p.readCString() === apiName) { g_nameOff = off; return true; }
    } catch (e2) { }
  }
  return false;
}

function classNameOf(klassPtr) {
  if (g_nameOff < 0 || !klassPtr || klassPtr.isNull()) return null;
  try { return klassPtr.add(g_nameOff).readPointer().readCString(); } catch (e) { return null; }
}

// ── ViewManager 锚点 ──────────────────────────────────────────────────────────
function ensureViewManager() {
  if (g_vm) return true;
  var klass = findClass('ViewManager');
  if (!klass) return false;
  var getInst = methodOf(klass, 'get_Instance') || methodOf(klass, 'GetInstance');
  if (!getInst) return false;
  g_vm = {
    getInstance: getInst,
    currentViewOff: findFieldOffset(klass, ['currentView', '_currentView', '<currentView>k__BackingField', 'mCurrentView', 'CurrentView'], 'currentView'),
    viewIdOff: findFieldOffset(klass, ['<CurrentViewId>k__BackingField', 'CurrentViewId'], 'CurrentViewId'),
  };
  return true;
}

// ── DialogArea 活动弹窗探测 ───────────────────────────────────────────────────
// DialogArea 是长驻对象（PC 版实测）；每拍校验名字，失效则重新 GameObject.Find。
// 有活动弹窗时宿主不短路、交给视觉做细判定 —— 这保证编队/商店/事件等弹窗页不被视图误判。
function ensureDialogAnchor() {
  if (g_dialog) return g_dialog;
  var goKlass = findClass('GameObject');
  if (!goKlass) return null;
  var find = methodOf(goKlass, 'Find', 1); // GameObject.Find(string)
  if (!find) return null;
  g_dialog = { find: find, goKlass: goKlass, transform: null };
  return g_dialog;
}

function dialogActiveCount() {
  var anchor = ensureDialogAnchor();
  if (!anchor) return -1;
  var tr = anchor.transform;
  if (tr !== null && readIl2CppString(invoke0(tr, 'get_name')) !== 'DialogArea') tr = null;
  if (tr === null) {
    var go = invokeStaticString(anchor.find, 'DialogArea');
    if (!go || go.isNull()) { anchor.transform = null; return 0; }
    var t = invoke0(go, 'get_transform');
    if (!t || t.isNull()) return 0;
    anchor.transform = t;
    tr = t;
  }
  var countBox = invoke0(tr, 'get_childCount');
  var total = 0;
  try {
    if (countBox && !countBox.isNull()) {
      var raw = countBox.add(0x10).readS32(); // 装箱 Int32 值 @0x10；垃圾值按 0 处理
      total = (raw >= 0 && raw <= MAX_CHILD_COUNT) ? raw : 0;
    }
  } catch (e) { return 0; }
  var active = 0;
  var getChild = methodOf(klassOf(tr), 'GetChild', 1) || methodOf(klassOf(tr), 'get_child', 1);
  if (!getChild) return -1;
  var ex = Memory.alloc(8); ex.writePointer(ptr(0));
  for (var i = 0; i < total; i++) {
    var child;
    try { child = g_il2cpp.runtime_invoke(getChild, tr, intArg(i), ex); } catch (e2) { continue; }
    if (!child || child.isNull()) continue;
    var on = invoke0(child, 'get_activeInHierarchy');
    try {
      if (on && !on.isNull() && on.add(0x10).readU8() !== 0) active++;
    } catch (e3) { }
  }
  return active;
}

// ── 页面读取 ──────────────────────────────────────────────────────────────────
// 只读；任何失败都以 err/字段缺省表达，绝不抛出到 RPC 边界之外。
function readPage() {
  if (!g_il2cpp || !g_classes) return { ok: false, err: 'not-ready' };
  if (!ensureViewManager()) return { ok: false, err: 'no-view-manager' };
  var inst = ptr(0);
  withArt(function () { inst = invokeStatic(g_vm.getInstance); });
  if (!inst || inst.isNull()) return { ok: false, err: 'no-instance' };
  var out = { ok: true };
  if (g_vm.viewIdOff !== null && g_vm.viewIdOff >= 0) {
    try { out.viewId = inst.add(g_vm.viewIdOff).readS32(); } catch (e) { out.viewIdErr = String(e); }
  }
  if (g_nameOff >= 0 && g_vm.currentViewOff !== null && g_vm.currentViewOff >= 0) {
    try {
      var view = inst.add(g_vm.currentViewOff).readPointer(); // 守卫式读（可能刚被销毁）
      if (!view.isNull()) {
        out.view = classNameOf(klassOf(view));                 // 对象头 +0x0 = 类指针（见铁律 4）
      }
    } catch (e) { out.viewErr = String(e); } // 对象死亡 → 丢弃本轮，上层走视觉
  } else {
    // 视图字段未解析成功：带上诊断信息，宿主可用 fields RPC 核对字段名。
    out.viewDiag = 'nameOff=' + g_nameOff + '/off=' + g_vm.currentViewOff;
  }
  try { out.dialogCount = dialogActiveCount(); } catch (e2) { out.dialogCount = -1; }
  return out;
}

// 字段名诊断：列出某类全部字段（name@offset）。供宿主核对字段候选名，不进入常规链路。
function readFields(className) {
  if (!g_il2cpp || !g_classes) return { ok: false, err: 'not-ready' };
  var klass = findClass(className);
  if (!klass) return { ok: false, err: 'no-class:' + className };
  var names = [];
  var iter = Memory.alloc(8); iter.writePointer(ptr(0));
  var field = g_il2cpp.class_get_fields(klass, iter);
  while (!field.isNull()) {
    var name = '';
    try { name = g_il2cpp.field_get_name(field).readCString(); } catch (e) { }
    var off = -1;
    try { off = Number(g_il2cpp.field_get_offset(field)); } catch (e2) { }
    names.push(name + '@0x' + off.toString(16));
    field = g_il2cpp.class_get_fields(klass, iter);
  }
  return { ok: true, fields: names };
}

// ── 回连桥 ────────────────────────────────────────────────────────────────────
function enc(text) {
  var out = [];
  for (var i = 0; i < text.length; i++) {
    var c = text.charCodeAt(i);
    if (c < 0x80) out.push(c);
    else if (c < 0x800) out.push(0xc0 | (c >> 6), 0x80 | (c & 0x3f));
    else out.push(0xe0 | (c >> 12), 0x80 | ((c >> 6) & 0x3f), 0x80 | (c & 0x3f));
  }
  return new Uint8Array(out);
}

function dec(buffer) {
  var u8 = new Uint8Array(buffer);
  var s = '';
  var i = 0;
  while (i < u8.length) {
    var b = u8[i++];
    if (b < 0x80) s += String.fromCharCode(b);
    else if (b >= 0xc0 && b < 0xe0 && i < u8.length) s += String.fromCharCode(((b & 0x1f) << 6) | (u8[i++] & 0x3f));
    else if (b >= 0xe0 && b < 0xf0 && i + 1 < u8.length) s += String.fromCharCode(((b & 0x0f) << 12) | ((u8[i++] & 0x3f) << 6) | (u8[i++] & 0x3f));
    else { i += 3; s += '?'; }
  }
  return s;
}

var handlers = {
  ready: function () { return { ready: !!(g_il2cpp && g_classes) }; },
  build: function () { return { build: PROBE_BUILD, pid: Process.id, arch: Process.arch }; },
  page: readPage,
  fields: readFields,
};

function dispatch(line) {
  var msg;
  try { msg = JSON.parse(line); } catch (e) { return null; }
  if (!msg || typeof msg.id !== 'number') return null;
  var handler = handlers[msg.fn];
  if (typeof handler !== 'function') return { id: msg.id, ok: false, err: 'no-fn:' + msg.fn };
  try { return { id: msg.id, ok: true, data: handler.apply(null, msg.args || []) }; }
  catch (e) { return { id: msg.id, ok: false, err: String(e) }; }
}

var CONNECT_RETRY_MS = 1000;
var POLL_MS = 20;
var READ_SIZE = 65536;
var IDLE_RECONNECT_MS = 6000; // 宿主每轮都发 page → 静默超时即对端已死

async function main() {
  for (;;) {
    var conn = null;
    try {
      conn = await Socket.connect({ host: '127.0.0.1', port: BRIDGE_PORT }); // 铁律 1
    } catch (e) {
      await new Promise(function (r) { setTimeout(r, CONNECT_RETRY_MS); });
      continue;
    }
    try {
      await conn.output.write(enc(JSON.stringify({
        event: 'hello', pid: Process.id, build: PROBE_BUILD, arch: Process.arch,
      }) + '\n'));
    } catch (e) { await new Promise(function (r) { setTimeout(r, 200); }); continue; }
    var buffer = '';
    var lastRxAt = Date.now();
    for (;;) {
      var chunk = null;
      try { chunk = await conn.input.read(READ_SIZE); }
      catch (e) { break; }
      if (!chunk) {
        if (Date.now() - lastRxAt > IDLE_RECONNECT_MS) break;
        await new Promise(function (r) { setTimeout(r, POLL_MS); });
        continue;
      }
      lastRxAt = Date.now();
      buffer += dec(chunk);
      var idx;
      while ((idx = buffer.indexOf('\n')) >= 0) {
        var line = buffer.slice(0, idx);
        buffer = buffer.slice(idx + 1);
        if (!line.trim()) continue;
        var reply = dispatch(line);
        if (reply) {
          try { await conn.output.write(enc(JSON.stringify(reply) + '\n')); } catch (e2) { break; }
        }
      }
    }
    try { conn.close(); } catch (e3) { }
    await new Promise(function (r) { setTimeout(r, 200); });
  }
}

// il2cpp 未就绪时每 250ms 重试初始化（domain 可用才继续），就绪后建类缓存并校准类名偏移。
var initTimer = setInterval(function () {
  if (initIl2Cpp()) {
    clearInterval(initTimer);
    if (buildClassCache()) calibrateNameOffset();
    else g_classes = null;
  }
}, 250);

main();
