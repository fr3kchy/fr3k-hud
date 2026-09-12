package com.mcpintelligence.fr3k.integrations.browser

import org.json.JSONObject

/** Fixed page operations; caller text is JSON data, never executable source. */
object BrowserPageScript {
    fun build(action: String, args: Map<String, String>): String {
        val payload = JSONObject(args).put("action", action).toString()
        return """
            (() => {
              try {
                const a = $payload;
                const visible = e => !!(e.getClientRects().length) && getComputedStyle(e).visibility !== 'hidden';
                const selector = e => {
                  if (e.id) return '#' + CSS.escape(e.id);
                  const parts = [];
                  while (e && e.nodeType === 1) {
                    let n = 1, p = e.previousElementSibling;
                    while (p) { if (p.tagName === e.tagName) n++; p = p.previousElementSibling; }
                    parts.unshift(e.tagName.toLowerCase() + ':nth-of-type(' + n + ')');
                    e = e.parentElement;
                  }
                  return parts.join(' > ');
                };
                let detail = {};
                if (a.action === 'inspect' || a.action === 'text') {
                  detail.text = (document.body?.innerText || '').slice(0, 16000);
                  detail.truncated = (document.body?.innerText || '').length > 16000;
                  if (a.action === 'inspect') detail.elements = Array.from(document.querySelectorAll('a,button,input,textarea,select,form,[role="button"]'))
                    .filter(visible).slice(0, 100).map(e => ({selector: selector(e), tag: e.tagName.toLowerCase(),
                      type: e.type || '', label: (e.getAttribute('aria-label') || e.innerText || e.placeholder || e.name || '').slice(0,160),
                      href: e.href || '', disabled: !!e.disabled}));
                } else if (a.action === 'scroll') {
                  const x = Number(a.x || 0), y = Number(a.y || 0);
                  if (!Number.isFinite(x) || !Number.isFinite(y)) throw Error('Scroll x/y must be numbers');
                  window.scrollBy({left:x, top:y, behavior:'instant'});
                  detail = {x:window.scrollX, y:window.scrollY};
                } else {
                  if (!a.selector) throw Error('A CSS selector is required');
                  const matches = document.querySelectorAll(a.selector);
                  if (matches.length !== 1) throw Error('Selector matched ' + matches.length + ' elements; choose exactly one');
                  const e = matches[0];
                  if (!visible(e)) throw Error('Element is not visible');
                  if (e.disabled) throw Error('Element is disabled');
                  e.scrollIntoView({block:'center'});
                  if (a.action === 'click') {
                    e.click(); detail = {performed:'click', selector:a.selector};
                  } else if (a.action === 'type') {
                    if (!(e instanceof HTMLInputElement || e instanceof HTMLTextAreaElement || e instanceof HTMLSelectElement)) throw Error('Element does not accept text');
                    if (e.readOnly || e.type === 'file') throw Error('Element is read-only or a file picker');
                    e.focus();
                    const proto = e instanceof HTMLTextAreaElement ? HTMLTextAreaElement.prototype : e instanceof HTMLSelectElement ? HTMLSelectElement.prototype : HTMLInputElement.prototype;
                    Object.getOwnPropertyDescriptor(proto, 'value').set.call(e, a.text || '');
                    e.dispatchEvent(new Event('input', {bubbles:true}));
                    e.dispatchEvent(new Event('change', {bubbles:true}));
                    detail = {performed:'type', selector:a.selector, characters:(a.text || '').length};
                  } else if (a.action === 'submit') {
                    const form = e instanceof HTMLFormElement ? e : e.form || e.closest('form');
                    if (!form) throw Error('Element has no form');
                    if (!form.reportValidity()) throw Error('Form validation failed');
                    form.requestSubmit(); detail = {performed:'submit', selector:a.selector};
                  } else throw Error('Unsupported page action');
                }
                return {ok:true, url:location.href, title:document.title, readyState:document.readyState, ...detail};
              } catch (e) { return {ok:false, error:String(e.message || e)}; }
            })()
        """.trimIndent()
    }
}
