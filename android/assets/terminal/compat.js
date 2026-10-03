'use strict';
// Small DOM shims for stock Android 10 WebView. No network or runtime dependency loader.
for (const prototype of [Element.prototype, DocumentFragment.prototype]) {
  if (!prototype.replaceChildren) Object.defineProperty(prototype, 'replaceChildren', {
    configurable: true, writable: true,
    value: function(...nodes) {
      while (this.firstChild) this.removeChild(this.firstChild);
      for (const node of nodes) this.appendChild(node instanceof Node ? node : this.ownerDocument.createTextNode(String(node)));
    }
  });
}
if (!Promise.allSettled) Promise.allSettled = values => Promise.all(Array.from(values, value =>
  Promise.resolve(value).then(result => ({status:'fulfilled', value:result}), reason => ({status:'rejected', reason}))
));
if (typeof MediaQueryList !== 'undefined' && !MediaQueryList.prototype.addEventListener) {
  MediaQueryList.prototype.addEventListener = function(type, listener) { if (type === 'change') this.addListener(listener); };
  MediaQueryList.prototype.removeEventListener = function(type, listener) { if (type === 'change') this.removeListener(listener); };
}
