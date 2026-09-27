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

    private const val POPUP_INTERCEPTOR_JS = """
        (function() {
            if (window.aabWindowOpenIntercepted) return;
            window.aabWindowOpenIntercepted = true;

            const origOpen = window.open;
            window.open = function(url, target, features) {
                if (url) {
                    const low = url.toLowerCase();
                    const isGoogleAuth = low.includes('accounts.google.com') ||
                        (low.includes('google.com') && (low.includes('oauth') || low.includes('signin') || low.includes('account')));

                    if (isGoogleAuth) {
                        if (origOpen) {
                            return origOpen.call(this, url, target, features);
                        }
                        return null;
                    }

                    if (low.includes('oauth') || low.includes('login') || low.includes('signin') || low.includes('auth')) {
                        if (window.AndroidBridge && window.AndroidBridge.openPopup) {
                            AndroidBridge.openPopup(url);
                            return {
                                postMessage: function() {},
                                close: function() {},
                                closed: false
                            };
                        }
                    }
                    if (window.AndroidBridge && window.AndroidBridge.openInNewTab) {
                        AndroidBridge.openInNewTab(url);
                    } else if (origOpen) {
                        return origOpen.apply(this, arguments);
                    }
                }
                return null;
            };
        })();
    """
}
