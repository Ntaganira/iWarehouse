// ===========================================================================
// iWarehouse session idle timeout (from iVura) (native JS, no dependencies)
// After 15 minutes without a key, click, touch or scroll in any open tab, signs
// the user out on the server (POST /logout?timeout=true), then shows
// /login?timeout=true. Requests a page makes by itself (the POS asking about
// approvals every few seconds) keep the server session alive, so only going to
// the login page would leave a shared counter workstation signed in (NFR-09).
// Tabs share the time of the last activity (localStorage), so a user working in
// one tab is not signed out by another left idle.
// Loaded from the global layout fragment (layout/sidebar.html).
// ===========================================================================
(function () {
    'use strict';

    // Only run on authenticated pages (the layout renders a .sidebar there).
    if (!document.querySelector('.sidebar')) return;

    var TIMEOUT_MS = 15 * 60 * 1000;   // 15 minutes of inactivity (server.servlet.session.timeout)
    var CHECK_MS = 15 * 1000;
    var SHARE_EVERY_MS = 5 * 1000;
    var KEY = 'iwarehouse-last-activity';
    var last = Date.now();
    var shared = 0;
    var signedOut = false;

    function readShared() {
        try {
            return parseInt(localStorage.getItem(KEY), 10) || 0;
        } catch (e) {
            return 0;   // storage blocked (private window): this tab counts alone
        }
    }

    function share(time) {
        try {
            localStorage.setItem(KEY, String(time));
        } catch (e) { /* storage blocked: this tab counts alone */ }
    }

    function activity() {
        if (signedOut) return;
        last = Date.now();
        if (last - shared >= SHARE_EVERY_MS) {
            shared = last;
            share(last);
        }
    }

    function showLogin() {
        try {
            window.location.replace('/login?timeout=true');
        } catch (e) {
            window.location.href = '/login?timeout=true';
        }
    }

    function signOut() {
        signedOut = true;
        var token = document.querySelector('input[name="_csrf"]');
        if (!token || !window.fetch) {
            showLogin();
            return;
        }
        // A session that has already ended answers with a redirect too: either way the login page follows
        fetch('/logout?timeout=true', {
            method: 'POST',
            credentials: 'same-origin',
            redirect: 'manual',
            headers: { 'Content-Type': 'application/x-www-form-urlencoded' },
            body: encodeURIComponent(token.name) + '=' + encodeURIComponent(token.value)
        }).then(showLogin, showLogin);
    }

    function check() {
        if (signedOut) return;
        if (Date.now() - Math.max(last, readShared()) >= TIMEOUT_MS) signOut();
    }

    var events = ['mousemove', 'mousedown', 'keydown', 'click', 'scroll', 'touchstart'];
    events.forEach(function (eventName) {
        document.addEventListener(eventName, activity, { passive: true });
    });
    // A sleeping laptop or a tab in the background checks as soon as it is looked at again
    document.addEventListener('visibilitychange', function () {
        if (!document.hidden) check();
    });

    activity();   // opening a page is activity
    setInterval(check, CHECK_MS);
})();
