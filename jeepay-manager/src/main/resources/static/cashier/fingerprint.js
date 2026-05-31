/**
 * DYPAY Fingerprint Collector
 *
 * Tier 1: 浏览器原生 API（即时返回）
 * Tier 2: Canvas / WebGL / AudioContext（异步，~50ms）
 *
 * 设计：
 *  - 无外部依赖（不引 FingerprintJS）
 *  - 失败容忍：单项失败不影响整体，返回 null
 *  - 输出扁平 JSON，便于后端直接索引
 *
 * 与商业方案差距：
 *  - 本库 ~30 字段，FingerprintJS Pro ~70 字段 + 反作弊
 *  - 适合 PoC / 自研。后续若要 99% 唯一率，把本库换成 FingerprintJS 即可：
 *      window.DYPAY_FP = { collect: () => FingerprintJS.load().then(fp => fp.get()) };
 */
(function() {
  'use strict';

  function safe(fn, fallback) {
    try { return fn(); } catch (e) { return fallback === undefined ? null : fallback; }
  }

  // ===== Tier 1: 同步原生 API =====
  function collectBasic() {
    var nav = navigator || {};
    var scr = screen || {};
    return {
      // 浏览器
      userAgent: safe(function() { return nav.userAgent; }),
      language: safe(function() { return nav.language; }),
      languages: safe(function() { return (nav.languages || []).join(','); }),
      platform: safe(function() { return nav.platform; }),
      vendor: safe(function() { return nav.vendor; }),
      cookieEnabled: safe(function() { return nav.cookieEnabled; }),
      doNotTrack: safe(function() { return nav.doNotTrack; }),
      hardwareConcurrency: safe(function() { return nav.hardwareConcurrency; }),
      deviceMemory: safe(function() { return nav.deviceMemory; }),
      maxTouchPoints: safe(function() { return nav.maxTouchPoints; }),
      pdfViewerEnabled: safe(function() { return nav.pdfViewerEnabled; }),
      webdriver: safe(function() { return nav.webdriver; }),  // 关键：headless 检测

      // 屏幕
      screenWidth: safe(function() { return scr.width; }),
      screenHeight: safe(function() { return scr.height; }),
      screenColorDepth: safe(function() { return scr.colorDepth; }),
      screenAvailWidth: safe(function() { return scr.availWidth; }),
      screenAvailHeight: safe(function() { return scr.availHeight; }),
      devicePixelRatio: safe(function() { return window.devicePixelRatio; }),

      // 时区 / 语言区域
      timezone: safe(function() { return Intl.DateTimeFormat().resolvedOptions().timeZone; }),
      timezoneOffset: safe(function() { return new Date().getTimezoneOffset(); }),

      // 窗口
      windowWidth: safe(function() { return window.innerWidth; }),
      windowHeight: safe(function() { return window.innerHeight; }),

      // 存储
      localStorageAvailable: safe(function() {
        var x = '__t';
        localStorage.setItem(x, x); localStorage.removeItem(x);
        return true;
      }, false),
      sessionStorageAvailable: safe(function() {
        var x = '__t';
        sessionStorage.setItem(x, x); sessionStorage.removeItem(x);
        return true;
      }, false),

      // 插件（现代浏览器常返回空数组）
      plugins: safe(function() {
        var r = [];
        for (var i = 0; i < (nav.plugins || []).length; i++) r.push(nav.plugins[i].name);
        return r.join('|');
      }, ''),

      // 时间
      ts: Date.now()
    };
  }

  // ===== Tier 2: Canvas 指纹 =====
  function canvasFingerprint() {
    return safe(function() {
      var canvas = document.createElement('canvas');
      canvas.width = 280; canvas.height = 60;
      var ctx = canvas.getContext('2d');
      // 多颜色 + emoji + 阴影，确保不同 GPU 输出不同像素
      ctx.textBaseline = 'alphabetic';
      ctx.fillStyle = '#f60';
      ctx.fillRect(125, 1, 62, 20);
      ctx.fillStyle = '#069';
      ctx.font = '14px "Arial"';
      ctx.fillText('DYPAY Fingerprint 🔒 测试', 2, 15);
      ctx.fillStyle = 'rgba(102, 204, 0, 0.7)';
      ctx.font = '17px "Times New Roman"';
      ctx.fillText('0xDEAD@beef', 4, 45);
      // 摘要：取 dataURL 后 hash
      var dataUrl = canvas.toDataURL();
      return hashString(dataUrl);
    });
  }

  // ===== Tier 2: WebGL 指纹 =====
  function webglFingerprint() {
    return safe(function() {
      var canvas = document.createElement('canvas');
      var gl = canvas.getContext('webgl') || canvas.getContext('experimental-webgl');
      if (!gl) return null;
      var dbg = gl.getExtension('WEBGL_debug_renderer_info');
      var out = {
        vendor: gl.getParameter(gl.VENDOR),
        renderer: gl.getParameter(gl.RENDERER),
        version: gl.getParameter(gl.VERSION),
        shading: gl.getParameter(gl.SHADING_LANGUAGE_VERSION),
        unmaskedVendor: dbg ? gl.getParameter(dbg.UNMASKED_VENDOR_WEBGL) : null,
        unmaskedRenderer: dbg ? gl.getParameter(dbg.UNMASKED_RENDERER_WEBGL) : null,
        antialias: gl.getContextAttributes().antialias,
        maxTextureSize: gl.getParameter(gl.MAX_TEXTURE_SIZE)
      };
      return hashString(JSON.stringify(out));
    });
  }

  // ===== Tier 2: AudioContext 指纹 =====
  function audioFingerprint() {
    return new Promise(function(resolve) {
      try {
        var Ctx = window.OfflineAudioContext || window.webkitOfflineAudioContext;
        if (!Ctx) return resolve(null);
        var ctx = new Ctx(1, 44100, 44100);
        var osc = ctx.createOscillator();
        osc.type = 'triangle';
        osc.frequency.value = 10000;
        var comp = ctx.createDynamicsCompressor();
        comp.threshold.value = -50; comp.knee.value = 40;
        comp.ratio.value = 12; comp.attack.value = 0; comp.release.value = 0.25;
        osc.connect(comp); comp.connect(ctx.destination);
        osc.start(0); ctx.startRendering();
        ctx.oncomplete = function(e) {
          var sum = 0;
          var d = e.renderedBuffer.getChannelData(0);
          for (var i = 4500; i < 5000; i++) sum += Math.abs(d[i]);
          resolve(hashString(String(sum)));
        };
        // 超时兜底
        setTimeout(function() { resolve(null); }, 1500);
      } catch (e) { resolve(null); }
    });
  }

  // ===== Tier 2: 字体探测（轻量版，5 种主流字体） =====
  function fontFingerprint() {
    return safe(function() {
      var fonts = ['Arial', 'Times New Roman', 'Courier New', 'Georgia', 'Verdana',
                   'Comic Sans MS', 'Impact', 'Tahoma'];
      var base = ['monospace', 'sans-serif', 'serif'];
      var available = [];
      var testStr = 'mmmmmmmmmmlli';
      var size = '72px';

      var span = document.createElement('span');
      span.style.cssText = 'position:absolute;left:-9999px;top:0;font-size:' + size;
      span.textContent = testStr;
      document.body.appendChild(span);

      var baseWidths = {};
      base.forEach(function(b) {
        span.style.fontFamily = b;
        baseWidths[b] = span.offsetWidth;
      });

      fonts.forEach(function(f) {
        var detected = false;
        for (var i = 0; i < base.length; i++) {
          span.style.fontFamily = '"' + f + '",' + base[i];
          if (span.offsetWidth !== baseWidths[base[i]]) { detected = true; break; }
        }
        if (detected) available.push(f);
      });

      document.body.removeChild(span);
      return available.join('|');
    }, '');
  }

  // ===== 简单 hash（djb2 变种），无需 crypto =====
  function hashString(str) {
    if (!str) return null;
    var h = 5381;
    for (var i = 0; i < str.length; i++) {
      h = ((h << 5) + h) + str.charCodeAt(i);
      h = h & 0xffffffff;
    }
    // 转 hex（无符号）
    return (h >>> 0).toString(16);
  }

  // ===== 综合 visitorId（多源 hash） =====
  function buildVisitorId(parts) {
    var keyOrder = [
      'userAgent', 'language', 'platform', 'timezone',
      'screenWidth', 'screenHeight', 'screenColorDepth',
      'canvas', 'webgl', 'audio', 'fonts'
    ];
    var s = keyOrder.map(function(k) { return k + ':' + (parts[k] || ''); }).join('||');
    return hashString(s);
  }

  // ===== 主入口 =====
  function collect() {
    var basic = collectBasic();
    basic.canvas = canvasFingerprint();
    basic.webgl = webglFingerprint();
    basic.fonts = fontFingerprint();

    return audioFingerprint().then(function(audio) {
      basic.audio = audio;
      basic.visitorId = buildVisitorId(basic);
      return basic;
    });
  }

  window.DYPAY_FP = { collect: collect };
})();
