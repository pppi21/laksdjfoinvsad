(function() {
    'use strict';

    // ==================== Capture Built-ins ====================
    // Snapshot critical built-ins before any page script can tamper with them.
    var _defineProperty = Object.defineProperty;
    var _getOwnPropDesc = Object.getOwnPropertyDescriptor;
    var _setPrototypeOf = Object.setPrototypeOf;
    var _stringify = JSON.stringify;
    var _now = Date.now;
    var _Reflect = Reflect;
    var _WeakMap = WeakMap;

    // ==================== Buffer Setup ====================
    var buf = [];
    var dropped = 0;
    var MAX_BUF = 10000;
    var lastLog = {};           // api -> last timestamp (rate limiting)
    var RATE_LIMIT_MS = 100;
    var CAPTURE_STACKS = !!(typeof window.__nd4j_fp_stacks !== 'undefined' && window.__nd4j_fp_stacks);

    // Expose drain function — non-enumerable, closure-scoped access to buffer.
    _defineProperty(window, '__nd4j_fp_drain', {
        value: function() {
            var result = _stringify({ e: buf.slice(0), d: dropped });
            buf.length = 0;
            dropped = 0;
            return result;
        },
        configurable: false,
        enumerable: false,
        writable: false
    });

    // ==================== Log Function ====================
    function log(api) {
        var now = _now.call(Date);

        // Rate limit — max 1 entry per API per 100ms
        if (lastLog[api] && (now - lastLog[api]) < RATE_LIMIT_MS) {
            return;
        }
        lastLog[api] = now;

        // Buffer cap
        if (buf.length >= MAX_BUF) {
            dropped++;
            return;
        }

        var entry = { a: api, t: now };

        if (CAPTURE_STACKS) {
            try {
                var err = new Error();
                if (err.stack) {
                    var lines = err.stack.split('\n');
                    // Remove: "Error", "at log", "at wrapper/newGet" frames
                    entry.s = lines.slice(3).join('\n');
                }
            } catch(e) { /* ignore */ }
        }

        buf[buf.length] = entry;  // avoid push — we captured no reference
    }

    // ==================== toString Spoofing ====================
    // WeakMap: wrapped fn -> spoofed toString result
    var origToString = Function.prototype.toString;
    var spoofMap = new _WeakMap();

    // Override Function.prototype.toString globally
    Function.prototype.toString = function() {
        var s = spoofMap.get(this);
        return s !== undefined ? s : origToString.call(this);
    };
    spoofMap.set(Function.prototype.toString, 'function toString() { [native code] }');

    function spoofToString(fn, nativeString) {
        spoofMap.set(fn, nativeString);
    }

    // ==================== Wrapping Strategies ====================

    /**
     * Strategy 1: Wrap a getter on an object/prototype.
     * Preserves descriptor shape (enumerable, configurable).
     */
    function wrapGetter(obj, prop, apiName) {
        try {
            var desc = _getOwnPropDesc(obj, prop);
            if (!desc || !desc.get) return;
            if (!desc.configurable) return;

            var originalGet = desc.get;
            var originalToStr = origToString.call(originalGet);

            var newGet = function() {
                log(apiName);
                return originalGet.call(this);
            };
            spoofToString(newGet, originalToStr);

            _defineProperty(obj, prop, {
                get: newGet,
                set: desc.set,
                enumerable: desc.enumerable,
                configurable: desc.configurable
            });
        } catch(e) { /* property not wrappable — skip */ }
    }

    /**
     * Strategy 2: Wrap a method on an object/prototype.
     * Preserves name, length, descriptor attributes.
     */
    function wrapMethod(obj, method, apiName) {
        try {
            var desc = _getOwnPropDesc(obj, method);
            if (!desc) return;

            var original = desc.value;
            if (typeof original !== 'function') return;
            if (!desc.configurable) return;

            var wrapper = function() {
                log(apiName);
                return original.apply(this, arguments);
            };
            _defineProperty(wrapper, 'length', { value: original.length, configurable: true });
            _defineProperty(wrapper, 'name', { value: original.name, configurable: true });
            spoofToString(wrapper, 'function ' + method + '() { [native code] }');

            _defineProperty(obj, method, {
                value: wrapper,
                writable: desc.writable,
                configurable: desc.configurable,
                enumerable: desc.enumerable
            });
        } catch(e) { /* method not wrappable — skip */ }
    }

    /**
     * Strategy 3: Wrap a global constructor.
     * Preserves prototype chain, name, length.
     */
    function wrapConstructor(name, owner) {
        try {
            owner = owner || window;
            var Original = owner[name];
            if (!Original) return;

            var Wrapper = function() {
                log(name);
                if (new.target) {
                    return _Reflect.construct(Original, arguments, Original);
                }
                return Original.apply(this, arguments);
            };

            // Inherit static methods
            _setPrototypeOf(Wrapper, Original);

            // Preserve prototype — instances have the original prototype
            Wrapper.prototype = Original.prototype;
            Original.prototype.constructor = Wrapper;

            _defineProperty(Wrapper, 'length', { value: Original.length, configurable: true });
            _defineProperty(Wrapper, 'name', { value: name, configurable: true });
            spoofToString(Wrapper, 'function ' + name + '() { [native code] }');

            owner[name] = Wrapper;
        } catch(e) { /* constructor not wrappable — skip */ }
    }

    // ==================== Navigator Properties ====================
    if (typeof Navigator !== 'undefined') {
        var navProto = Navigator.prototype;
        wrapGetter(navProto, 'userAgent',           'navigator.userAgent');
        wrapGetter(navProto, 'platform',            'navigator.platform');
        wrapGetter(navProto, 'language',            'navigator.language');
        wrapGetter(navProto, 'languages',           'navigator.languages');
        wrapGetter(navProto, 'hardwareConcurrency', 'navigator.hardwareConcurrency');
        wrapGetter(navProto, 'deviceMemory',        'navigator.deviceMemory');
        wrapGetter(navProto, 'maxTouchPoints',      'navigator.maxTouchPoints');
        wrapGetter(navProto, 'vendor',              'navigator.vendor');
        wrapGetter(navProto, 'appVersion',          'navigator.appVersion');
        wrapGetter(navProto, 'plugins',             'navigator.plugins');
        wrapGetter(navProto, 'mimeTypes',           'navigator.mimeTypes');
        wrapGetter(navProto, 'doNotTrack',          'navigator.doNotTrack');
        wrapGetter(navProto, 'cookieEnabled',       'navigator.cookieEnabled');
        wrapGetter(navProto, 'webdriver',           'navigator.webdriver');
        wrapGetter(navProto, 'connection',          'navigator.connection');

        wrapMethod(navProto, 'getBattery',  'navigator.getBattery');
        wrapMethod(navProto, 'getGamepads', 'navigator.getGamepads');
    }

    // ==================== MediaDevices ====================
    if (typeof MediaDevices !== 'undefined') {
        wrapMethod(MediaDevices.prototype, 'enumerateDevices',
                   'navigator.mediaDevices.enumerateDevices');
    }

    // ==================== Screen Properties ====================
    if (typeof Screen !== 'undefined') {
        var scrProto = Screen.prototype;
        wrapGetter(scrProto, 'width',       'screen.width');
        wrapGetter(scrProto, 'height',      'screen.height');
        wrapGetter(scrProto, 'availWidth',  'screen.availWidth');
        wrapGetter(scrProto, 'availHeight', 'screen.availHeight');
        wrapGetter(scrProto, 'colorDepth',  'screen.colorDepth');
        wrapGetter(scrProto, 'pixelDepth',  'screen.pixelDepth');
        wrapGetter(scrProto, 'orientation', 'screen.orientation');
    }

    // ==================== Window Properties ====================
    wrapGetter(window, 'devicePixelRatio', 'window.devicePixelRatio');

    // ==================== Canvas ====================
    if (typeof HTMLCanvasElement !== 'undefined') {
        var canvasProto = HTMLCanvasElement.prototype;
        wrapMethod(canvasProto, 'toDataURL', 'HTMLCanvasElement.toDataURL');
        wrapMethod(canvasProto, 'toBlob',    'HTMLCanvasElement.toBlob');
        wrapMethod(canvasProto, 'getContext', 'HTMLCanvasElement.getContext');
    }

    if (typeof CanvasRenderingContext2D !== 'undefined') {
        var ctx2dProto = CanvasRenderingContext2D.prototype;
        wrapMethod(ctx2dProto, 'fillText',      'CanvasRenderingContext2D.fillText');
        wrapMethod(ctx2dProto, 'strokeText',    'CanvasRenderingContext2D.strokeText');
        wrapMethod(ctx2dProto, 'measureText',   'CanvasRenderingContext2D.measureText');
        wrapMethod(ctx2dProto, 'getImageData',  'CanvasRenderingContext2D.getImageData');
        wrapMethod(ctx2dProto, 'isPointInPath', 'CanvasRenderingContext2D.isPointInPath');
    }

    // ==================== WebGL ====================
    function wrapWebGL(proto, prefix) {
        if (!proto) return;
        wrapMethod(proto, 'getParameter',            prefix + '.getParameter');
        wrapMethod(proto, 'getExtension',            prefix + '.getExtension');
        wrapMethod(proto, 'getSupportedExtensions',  prefix + '.getSupportedExtensions');
        wrapMethod(proto, 'getShaderPrecisionFormat', prefix + '.getShaderPrecisionFormat');
    }

    if (typeof WebGLRenderingContext !== 'undefined') {
        wrapWebGL(WebGLRenderingContext.prototype, 'WebGLRenderingContext');
    }
    if (typeof WebGL2RenderingContext !== 'undefined') {
        wrapWebGL(WebGL2RenderingContext.prototype, 'WebGL2RenderingContext');
    }

    // ==================== Audio ====================
    wrapConstructor('AudioContext');
    wrapConstructor('OfflineAudioContext');
    // webkitAudioContext for legacy support
    if (window.webkitAudioContext && window.webkitAudioContext !== window.AudioContext) {
        wrapConstructor('webkitAudioContext');
    }

    if (typeof BaseAudioContext !== 'undefined') {
        var bac = BaseAudioContext.prototype;
        wrapMethod(bac, 'createOscillator',        'BaseAudioContext.createOscillator');
        wrapMethod(bac, 'createDynamicsCompressor', 'BaseAudioContext.createDynamicsCompressor');
        wrapMethod(bac, 'createAnalyser',           'BaseAudioContext.createAnalyser');
        wrapGetter(bac, 'sampleRate',               'BaseAudioContext.sampleRate');
        wrapGetter(bac, 'destination',              'BaseAudioContext.destination');
    }

    if (typeof AudioContext !== 'undefined') {
        wrapGetter(AudioContext.prototype, 'baseLatency',   'AudioContext.baseLatency');
        wrapGetter(AudioContext.prototype, 'outputLatency', 'AudioContext.outputLatency');
    }

    if (typeof AudioDestinationNode !== 'undefined') {
        wrapGetter(AudioDestinationNode.prototype, 'maxChannelCount',
                   'AudioDestinationNode.maxChannelCount');
    }

    // ==================== Fonts ====================
    if (typeof Document !== 'undefined') {
        wrapGetter(Document.prototype, 'fonts', 'document.fonts');
    }

    // ==================== Timezone ====================
    wrapConstructor('DateTimeFormat', typeof Intl !== 'undefined' ? Intl : undefined);

    if (typeof Date !== 'undefined') {
        wrapMethod(Date.prototype, 'getTimezoneOffset', 'Date.getTimezoneOffset');
    }

    // ==================== Storage ====================
    wrapGetter(window, 'localStorage',   'window.localStorage');
    wrapGetter(window, 'sessionStorage', 'window.sessionStorage');
    wrapGetter(window, 'indexedDB',      'window.indexedDB');

    // ==================== WebRTC ====================
    wrapConstructor('RTCPeerConnection');
    if (window.webkitRTCPeerConnection && window.webkitRTCPeerConnection !== window.RTCPeerConnection) {
        wrapConstructor('webkitRTCPeerConnection');
    }

    // ==================== Performance ====================
    if (typeof Performance !== 'undefined') {
        wrapMethod(Performance.prototype, 'now', 'Performance.now');
    }

    // ==================== CSS / Media Queries ====================
    wrapMethod(window, 'matchMedia', 'window.matchMedia');

})();
