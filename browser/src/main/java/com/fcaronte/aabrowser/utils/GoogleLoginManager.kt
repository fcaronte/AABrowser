package com.fcaronte.aabrowser.utils

object GoogleLoginManager {
    fun getGoogleOauthFixScript(): String = GOOGLE_OAUTH_FIX_JS
    fun getPopupInterceptorScript(): String = POPUP_INTERCEPTOR_JS

    private const val GOOGLE_OAUTH_FIX_JS = """
        (function() {
            if (window.aabGoogleOauthPatched) return;
            window.aabGoogleOauthPatched = true;
            
            const origAddEventListener = window.addEventListener;
            window.addEventListener = function(type, listener, options) {
                if (type === 'message') {
                    const wrappedListener = function(event) {
                        try {
                            let data = event.data;
                            if (typeof data === 'string') {
                                try { data = JSON.parse(data); } catch(e) {}
                            }
                            const isAuthResult = data && (
                                data.type === 'authResult' || 
                                (data.params && data.params.type === 'authResult') ||
                                data.authResult
                            );
                            if (isAuthResult) {
                                const payload = (data && data.params) ? Object.assign({}, data.params, data) : data;
                                
                                const syntheticEvent = new MessageEvent('message', {
                                    data: payload,
                                    source: event.source
                                });
                                Object.defineProperty(syntheticEvent, 'origin', {
                                    get: function() { return 'https://accounts.google.com'; },
                                    configurable: true,
                                    enumerable: true
                                });
                                
                                try {
                                    listener.call(this, syntheticEvent);
                                } catch(e) {}

                                try {
                                    const stringPayload = typeof event.data === 'string' ? event.data : JSON.stringify(payload);
                                    const stringEvent = new MessageEvent('message', {
                                        data: stringPayload,
                                        source: event.source
                                    });
                                    Object.defineProperty(stringEvent, 'origin', {
                                        get: function() { return 'https://accounts.google.com'; },
                                        configurable: true,
                                        enumerable: true
                                    });
                                    listener.call(this, stringEvent);
                                } catch(e) {}
                                return;
                            }
                        } catch (e) {}
                        return listener.call(this, event);
                    };
                    return origAddEventListener.call(this, type, wrappedListener, options);
                }
                return origAddEventListener.call(this, type, listener, options);
            };
        })();
    """

    // window.open delle pagine: i login di Google come sono, gli altri accessi nel popup dell'app, il resto in una scheda
    // (nella Wide al posto della pagina). Le pubblicità che si aprono a ogni tocco no: l'app apre solo verso il sito
    // della pagina, quello del link toccato o un accesso (PopupGuard); a quelle bloccate una finestra finta, perché
    // con null molti script ripiegano spostando la pagina stessa sulla pubblicità
    private const val POPUP_INTERCEPTOR_JS = """
        (function() {
            if (window.aabWindowOpenIntercepted) return;
            window.aabWindowOpenIntercepted = true;

            // Il link sotto l'ultimo tocco vero dell'utente (vuoto se ha toccato altro)
            var tap = { link: '', at: 0 };
            function onTap(e) {
                if (!e.isTrusted) return;
                var a = e.target && e.target.closest ? e.target.closest('a[href]') : null;
                tap = { link: a ? String(a.href) : '', at: Date.now() };
            }
            ['pointerdown', 'touchstart', 'mousedown', 'click'].forEach(function(type) {
                window.addEventListener(type, onTap, true);
            });
            function tappedLink() {
                return Date.now() - tap.at < 10000 ? tap.link : '';
            }
            function allowed(url, link) {
                try {
                    if (!window.AndroidBridge || !window.AndroidBridge.allowsPopup) return true;
                    return AndroidBridge.allowsPopup(String(url), link === undefined ? tappedLink() : link);
                } catch (e) {
                    return true;
                }
            }

            // Link invisibile grande come il riquadro, messo sopra il lettore da alcuni circuiti pubblicitari: senza
            // testo né immagini, trasparente, su almeno metà di quello che si vede
            function isOverlayLink(a) {
                if (a.textContent.trim() || a.querySelector('img,picture,svg,canvas,video')) return false;
                var r = a.getBoundingClientRect();
                if (r.width * r.height < innerWidth * innerHeight * 0.5) return false;
                var c = getComputedStyle(a);
                if (c.position !== 'fixed' && c.position !== 'absolute') return false;
                var clear = c.backgroundImage === 'none' &&
                    (c.backgroundColor === 'transparent' || /rgba\(.*,\s*0\)/.test(c.backgroundColor));
                return clear || parseFloat(c.opacity) < 0.05;
            }

            // Si toglie il link e il tocco arriva a quello che c'è sotto (il lettore). Vale solo se l'app non lo
            // lascerebbe aprire: con lo scudo spento o verso lo stesso sito non si tocca niente
            function removeOverlay(a, e) {
                e.preventDefault();
                e.stopImmediatePropagation();
                a.remove();
                var below = document.elementFromPoint(e.clientX, e.clientY);
                if (below) {
                    origDispatch.call(below, new MouseEvent('click', {
                        bubbles: true, cancelable: true, view: window, clientX: e.clientX, clientY: e.clientY
                    }));
                }
            }

            window.addEventListener('click', function(e) {
                if (!e.isTrusted) return;
                var a = e.target && e.target.closest ? e.target.closest('a[href]') : null;
                if (!a || !/^https?:/i.test(a.href)) return;
                if (isOverlayLink(a) && !allowed(a.href, '')) {
                    removeOverlay(a, e);
                    return;
                }
                // Senza finestre multiple un link _blank dentro un riquadro sostituisce tutta la pagina (Blink lo apre
                // nel frame principale): si apre come le altre finestre, e la pagina resta
                if (window !== window.top && a.target.toLowerCase() === '_blank' && !a.hasAttribute('download') &&
                        window.AndroidBridge && window.AndroidBridge.openInNewTab) {
                    e.preventDefault();
                    e.stopImmediatePropagation();
                    AndroidBridge.openInNewTab(a.href, a.href);
                }
            }, true);
            function fakeWindow() {
                var noop = function() {};
                return {
                    closed: false, opener: null, close: noop, focus: noop, blur: noop, postMessage: noop,
                    document: { write: noop, writeln: noop, open: noop, close: noop },
                    location: { href: 'about:blank', assign: noop, replace: noop }
                };
            }

            const origOpen = window.open;
            window.open = function(url, target, features) {
                // Niente controllo di navigator.userActivation qui: i login aprono il popup dopo un'attesa (DAZN con
                // Google) e con null il sito ripiegava sulla pagina intera, senza ritorno. Il tocco recente lo
                // controlla l'app nei metodi del ponte (openPopup, openInNewTab)
                if (url) {
                    var full = String(url);
                    var host = '', path = '';
                    try {
                        var parsed = new URL(full, location.href);
                        full = parsed.href;
                        host = parsed.hostname.toLowerCase();
                        path = parsed.pathname.toLowerCase();
                    } catch (e) {}
                    const isGoogleAuth = host === 'accounts.google.com' ||
                        ((host === 'google.com' || host.endsWith('.google.com')) &&
                            (path.includes('oauth') || path.includes('signin') || path.includes('account')));

                    if (isGoogleAuth) {
                        if (origOpen) {
                            return origOpen.call(this, url, target, features);
                        }
                        return null;
                    }

                    var link = tappedLink();
                    if (!allowed(full)) return fakeWindow();

                    const low = full.toLowerCase();
                    if (low.includes('oauth') || low.includes('login') || low.includes('signin') || low.includes('auth')) {
                        if (window.AndroidBridge && window.AndroidBridge.openPopup) {
                            AndroidBridge.openPopup(full, link);
                            return {
                                postMessage: function() {},
                                close: function() {},
                                closed: false
                            };
                        }
                    }
                    if (window.AndroidBridge && window.AndroidBridge.openInNewTab) {
                        AndroidBridge.openInNewTab(full, link);
                    } else if (origOpen) {
                        return origOpen.apply(this, arguments);
                    }
                }
                return null;
            };

            // Link aperti da uno script senza che l'utente li tocchi (a.click() di un link creato al volo)
            const origClick = HTMLAnchorElement.prototype.click;
            HTMLAnchorElement.prototype.click = function() {
                if (this.href && !allowed(this.href)) return;
                return origClick.apply(this, arguments);
            };
            const origDispatch = EventTarget.prototype.dispatchEvent;
            EventTarget.prototype.dispatchEvent = function(event) {
                if (event && event.type === 'click' && !event.isTrusted && this instanceof HTMLAnchorElement &&
                        this.href && !allowed(this.href)) {
                    return false;
                }
                return origDispatch.apply(this, arguments);
            };
        })();
    """
}
