// ===========================================================================
// iWarehouse session idle timeout (from iVura) (native JS, no dependencies)
// Redirects the browser to /login?timeout=true after 15 minutes of inactivity.
// Mirrors the server-side session timeout (server.servlet.session.timeout)
// so shared counter workstations are protected even if one clock drifts.
// Loaded from the global layout fragment (layout/sidebar.html).
// ===========================================================================
(function () {
    'use strict';

    // Only run on authenticated pages (the layout renders a .sidebar there).
    if (!document.querySelector('.sidebar')) return;

    var TIMEOUT_MS = 15 * 60 * 1000;   // 15 minutes of inactivity
    var loggedOut = false;
    var timer = null;

    function expire() {
        if (loggedOut) return;
        loggedOut = true;
        try {
            window.location.replace('/login?timeout=true');
        } catch (e) {
            window.location.href = '/login?timeout=true';
        }
    }

    function reset() {
        if (loggedOut) return;
        clearTimeout(timer);
        timer = setTimeout(expire, TIMEOUT_MS);
    }

    var events = ['mousemove', 'mousedown', 'keydown', 'click', 'scroll', 'touchstart'];
    events.forEach(function (eventName) {
        document.addEventListener(eventName, reset, { passive: true });
    });

    reset();
})();
