package com.fcaronte.aabrowser.utils

object DaznManager {
    fun getAuthProxyScript(): String = AUTH_PROXY_JS
    fun getClickInterceptorScript(): String = DAZN_CLICK_INTERCEPTOR_JS

    private const val AUTH_PROXY_JS = """
        (function() {
            if (window.aabAuthProxyPatched) return;
            window.aabAuthProxyPatched = true;

            const origOpen = XMLHttpRequest.prototype.open;
            const origSend = XMLHttpRequest.prototype.send;
            const origSetRequestHeader = XMLHttpRequest.prototype.setRequestHeader;

            function shouldProxyDaznApiRequest(url) {
                try {
                    const parsedUrl = new URL(url, window.location.href);
                    const host = parsedUrl.hostname.toLowerCase();
                    const path = parsedUrl.pathname.toLowerCase();
                    const isAuthenticationRequest = host.includes('authentication-prod') ||
                        host.includes('auth') ||
                        path.includes('getpartneruserdetails') ||
                        path.includes('authentication') ||
                        path.includes('auth');
                    const isPlaybackApiRequest =
                        host.includes('indazn.com') &&
                        (path.includes('playback') || path.includes('token') || path.includes('session'));
                    return isAuthenticationRequest || isPlaybackApiRequest;
                } catch (e) {
                    return false;
                }
            }

            XMLHttpRequest.prototype.open = function(method, url, async, user, password) {
                this._aabMethod = method || 'GET';
                this._aabUrl = url ? new URL(url.toString(), window.location.href).href : '';
                this._aabHeaders = {};
                return origOpen.apply(this, arguments);
            };

            XMLHttpRequest.prototype.setRequestHeader = function(header, value) {
                if (String(header).toLowerCase() === 'user-agent') {
                    return;
                }
                if (this._aabHeaders) {
                    this._aabHeaders[header] = value;
                }
                return origSetRequestHeader.apply(this, arguments);
            };

            XMLHttpRequest.prototype.send = function(body) {
                if (this._aabUrl && shouldProxyDaznApiRequest(this._aabUrl) && window.AndroidBridge && window.AndroidBridge.proxyFetch) {
                    const xhr = this;
                    const method = xhr._aabMethod || 'GET';
                    const url = xhr._aabUrl;
                    const headersJson = JSON.stringify(xhr._aabHeaders || {});
                    const bodyStr = (typeof body === 'string') ? body : (body ? JSON.stringify(body) : '');

                    setTimeout(function() {
                        try {
                            const resJsonStr = window.AndroidBridge.proxyFetch(
                                url,
                                method,
                                headersJson,
                                bodyStr,
                                navigator.userAgent || ''
                            );
                            if (resJsonStr) {
                                const res = JSON.parse(resJsonStr);
                                const responseText = res.text || '';
                                const responseHeaders = res.headers || {};
                                const responseType = xhr.responseType || '';
                                let responseValue = responseText;
                                if (responseType === 'json') {
                                    try {
                                        responseValue = JSON.parse(responseText);
                                    } catch(e) {
                                        responseValue = null;
                                    }
                                }
                                const headerText = Object.keys(responseHeaders)
                                    .map(function(name) {
                                        return name + ': ' + responseHeaders[name];
                                    })
                                    .join('\r\n');
                                const responseProperties = {
                                    status: { value: Number(res.status), writable: true, configurable: true },
                                    statusText: { value: res.statusText || '', writable: true, configurable: true },
                                    responseURL: { value: url, configurable: true },
                                    response: { value: responseValue, configurable: true },
                                    readyState: { value: 1, writable: true, configurable: true },
                                    getAllResponseHeaders: {
                                        value: function() { return headerText; },
                                        configurable: true
                                    },
                                    getResponseHeader: {
                                        value: function(name) {
                                            const key = Object.keys(responseHeaders).find(function(headerName) {
                                                return headerName.toLowerCase() === String(name).toLowerCase();
                                            });
                                            return key ? responseHeaders[key] : null;
                                        },
                                        configurable: true
                                    }
                                };
                                if (responseType === '' || responseType === 'text') {
                                    responseProperties.responseText = {
                                        value: responseText,
                                        configurable: true
                                    };
                                }
                                Object.defineProperties(xhr, responseProperties);

                                xhr.dispatchEvent(new Event('loadstart'));
                                xhr.readyState = 2;
                                xhr.dispatchEvent(new Event('readystatechange'));
                                xhr.readyState = 3;
                                xhr.dispatchEvent(new Event('readystatechange'));
                                xhr.readyState = 4;
                                xhr.dispatchEvent(new Event('readystatechange'));
                                xhr.dispatchEvent(new Event('load'));
                                xhr.dispatchEvent(new Event('loadend'));
                                return;
                            }
                        } catch (e) {
                            console.error('proxyFetch XHR error:', e);
                        }
                        xhr.status = 0;
                        xhr.readyState = 4;
                        try {
                            xhr.dispatchEvent(new Event('readystatechange'));
                            xhr.dispatchEvent(new ProgressEvent('error'));
                            xhr.dispatchEvent(new ProgressEvent('loadend'));
                        } catch(e) {
                            console.error('proxyFetch XHR completion error:', e);
                        }
                    }, 10);
                    return;
                }
                return origSend.apply(this, arguments);
            };

            if (window.fetch) {
                const origFetch = window.fetch;
                window.fetch = async function(input, init) {
                    const originalArgs = arguments;
                    const receiver = this;
                    let request;
                    try {
                        request = new Request(input, init);
                    } catch (e) {
                        return origFetch.apply(receiver, originalArgs);
                    }

                    const url = request.url;
                    if (url && shouldProxyDaznApiRequest(url) && window.AndroidBridge && window.AndroidBridge.proxyFetch) {
                        try {
                            const headers = {};
                            request.headers.forEach(function(value, key) {
                                headers[key] = value;
                            });
                            const method = request.method || 'GET';
                            const body = (method === 'GET' || method === 'HEAD') ? '' : await request.clone().text();
                            const resJsonStr = window.AndroidBridge.proxyFetch(
                                url,
                                method,
                                JSON.stringify(headers),
                                body,
                                navigator.userAgent || ''
                            );
                            if (resJsonStr) {
                                const res = JSON.parse(resJsonStr);
                                const responseText = res.text || '';
                                const responseHeaders = res.headers || {};
                                return Promise.resolve(new Response(responseText, {
                                    status: res.status,
                                    statusText: res.statusText || 'OK',
                                    headers: responseHeaders
                                }));
                            }
                        } catch (e) {
                            console.error('proxyFetch fetch error:', e);
                        }
                    }
                    return origFetch.apply(receiver, originalArgs);
                };
            }
        })();
    """

    private const val DAZN_CLICK_INTERCEPTOR_JS = """
        (function() {
            if (window.aabDaznClickPatched) return;
            window.aabDaznClickPatched = true;

            document.addEventListener('click', function(e) {
                let target = e.target;
                while (target && target !== document.body) {
                    let href = target.href || target.getAttribute('data-href') || target.getAttribute('data-url');
                    if (!href && target.closest) {
                        let parentA = target.closest('a');
                        if (parentA) href = parentA.href;
                    }
                    
                    let hrefLower = (href || '').toLowerCase();
                    let isEventOrWatch = (hrefLower.includes('/watch/') || hrefLower.includes('/event/') || hrefLower.includes('/video/') || hrefLower.includes('/fixture/')) ||
                        (target.getAttribute && (target.getAttribute('data-testid')?.toLowerCase()?.includes('event') || target.getAttribute('data-testid')?.toLowerCase()?.includes('watch') || target.getAttribute('aria-label')?.toLowerCase()?.includes('watch') || target.getAttribute('aria-label')?.toLowerCase()?.includes('event')));

                    if (isEventOrWatch) {
                        if (window.AndroidBridge && window.AndroidBridge.onDaznEventClicked) {
                            AndroidBridge.onDaznEventClicked(href || window.location.href);
                        }
                        break;
                    }
                    target = target.parentElement;
                }
            }, true);
        })();
    """
}
