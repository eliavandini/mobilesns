// Injected at document start into https://my.scientificnet.org pages only.
// Wraps fetch/XMLHttpRequest so that when the website's own code calls its REST APIs
// with an "Authorization: Bearer ..." header, the URL + token are handed to the app.
(function () {
  if (window.__snsCaptureInstalled) return;
  window.__snsCaptureInstalled = true;

  function send(url, auth) {
    try {
      if (!auth || !/^Bearer\s+/i.test(String(auth))) return;
      var abs = new URL(String(url), location.href).href;
      var msg = JSON.stringify({ url: abs, token: String(auth).replace(/^Bearer\s+/i, '') });
      if (window.SnsBridge && window.SnsBridge.postMessage) {
        window.SnsBridge.postMessage(msg);
      } else if (window.SnsBridgeLegacy && window.SnsBridgeLegacy.post) {
        window.SnsBridgeLegacy.post(msg);
      }
    } catch (e) { /* never break the page */ }
  }

  function authFrom(headers) {
    if (!headers) return null;
    try {
      if (typeof Headers !== 'undefined' && headers instanceof Headers) return headers.get('Authorization');
      if (Array.isArray(headers)) {
        for (var i = 0; i < headers.length; i++) {
          if (String(headers[i][0]).toLowerCase() === 'authorization') return headers[i][1];
        }
        return null;
      }
      for (var k in headers) {
        if (Object.prototype.hasOwnProperty.call(headers, k) && k.toLowerCase() === 'authorization') return headers[k];
      }
    } catch (e) {}
    return null;
  }

  var origFetch = window.fetch;
  if (origFetch) {
    window.fetch = function (input, init) {
      try {
        var url = typeof input === 'string' ? input : (input && input.url) || String(input);
        var auth = authFrom(init && init.headers);
        if (!auth && input && typeof input === 'object') auth = authFrom(input.headers);
        send(url, auth);
      } catch (e) {}
      return origFetch.apply(this, arguments);
    };
  }

  var proto = window.XMLHttpRequest && window.XMLHttpRequest.prototype;
  if (proto) {
    var origOpen = proto.open;
    var origSetHeader = proto.setRequestHeader;
    proto.open = function (method, url) {
      try { this.__snsUrl = url; } catch (e) {}
      return origOpen.apply(this, arguments);
    };
    proto.setRequestHeader = function (name, value) {
      try {
        if (String(name).toLowerCase() === 'authorization') send(this.__snsUrl, value);
      } catch (e) {}
      return origSetHeader.apply(this, arguments);
    };
  }
})();
