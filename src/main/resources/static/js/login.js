/* Login page: password visibility, Caps Lock hint, submit state and the highlights carousel. */
document.addEventListener('DOMContentLoaded', function () {
    const password = document.getElementById('password');

    const toggle = document.getElementById('password-toggle');
    if (password && toggle) {
        toggle.addEventListener('click', function () {
            const show = password.type === 'password';
            password.type = show ? 'text' : 'password';
            toggle.setAttribute('aria-pressed', show ? 'true' : 'false');
            toggle.setAttribute('aria-label', show ? toggle.dataset.labelHide : toggle.dataset.labelShow);
            password.focus();
        });
    }

    const caps = document.getElementById('caps-hint');
    if (password && caps) {
        const check = function (e) {
            if (typeof e.getModifierState === 'function') caps.hidden = !e.getModifierState('CapsLock');
        };
        password.addEventListener('keydown', check);
        password.addEventListener('keyup', check);
        password.addEventListener('blur', function () { caps.hidden = true; });
    }

    const form = document.getElementById('login-form');
    const submit = document.getElementById('login-submit');
    const label = submit ? submit.querySelector('.submit-label') : null;
    const idleText = label ? label.textContent : '';
    if (form && submit) {
        form.addEventListener('submit', function () {
            submit.classList.add('is-loading');
            submit.setAttribute('aria-busy', 'true');
            if (label && submit.dataset.loading) label.textContent = submit.dataset.loading;
        });
        // Coming back with the Back button restores the page from cache: clear the loading state.
        window.addEventListener('pageshow', function () {
            submit.classList.remove('is-loading');
            submit.removeAttribute('aria-busy');
            if (label) label.textContent = idleText;
        });
    }

    const slides = Array.prototype.slice.call(document.querySelectorAll('.auth-showcase .slide'));
    const dots = Array.prototype.slice.call(document.querySelectorAll('.auth-showcase .slide-dot'));
    const region = document.querySelector('.auth-showcase');
    if (!region || slides.length < 2) return;

    const reduceMotion = window.matchMedia('(prefers-reduced-motion: reduce)').matches;
    let current = 0;
    let timer = null;

    const show = function (index) {
        current = (index + slides.length) % slides.length;
        slides.forEach(function (s, n) {
            s.classList.toggle('is-active', n === current);
            s.setAttribute('aria-hidden', n === current ? 'false' : 'true');
        });
        dots.forEach(function (d, n) {
            d.classList.toggle('is-active', n === current);
            d.setAttribute('aria-current', n === current ? 'true' : 'false');
        });
    };
    const stop = function () {
        if (timer) { clearInterval(timer); timer = null; }
    };
    const start = function () {
        stop();
        if (!reduceMotion) timer = setInterval(function () { show(current + 1); }, 5500);
    };

    dots.forEach(function (d, n) {
        d.addEventListener('click', function () { show(n); });
    });
    // Pause while the visitor is looking at or interacting with the carousel.
    region.addEventListener('mouseenter', stop);
    region.addEventListener('mouseleave', start);
    region.addEventListener('focusin', stop);
    region.addEventListener('focusout', start);
    start();
});
