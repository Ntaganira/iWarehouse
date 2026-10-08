/*
 * iWarehouse - exchange rate form (ACC-02).
 * Shows "1 USD = 1,450.25 RWF" while typing, and the latest rate of the default source with the
 * change in percent, so a slip such as 14502 instead of 1450.2 is seen before saving.
 * The server checks the same 10% rule; this is only a preview.
 */
(function () {
    'use strict';

    const form = document.getElementById('rate-form');
    const preview = document.getElementById('rate-preview');
    const previous = document.getElementById('rate-previous');
    if (!form || !preview) return;

    let latest = [];
    try { latest = JSON.parse(document.getElementById('latest-rates').textContent || '[]'); } catch (e) { latest = []; }
    const template = (document.getElementById('rate-msg-previous') || {}).textContent || '';
    const defaultSource = form.dataset.defaultSource;

    const field = name => form.querySelector('[name="' + name + '"]:not([type="hidden"])') || form.querySelector('[name="' + name + '"]');
    const fmt = (n, min, max) => n.toLocaleString('en-US', { minimumFractionDigits: min, maximumFractionDigits: max });

    function render() {
        const code = (field('currencyCode').value || '').toUpperCase();
        const rate = parseFloat(field('rate').value);
        const source = field('source').value;
        preview.textContent = '1 ' + (code || '?') + ' = ' + (rate > 0 ? fmt(rate, 2, 6) : '?') + ' RWF';

        const last = latest.find(l => l.code === code);
        if (!previous) return;
        if (!last || source !== defaultSource) { previous.textContent = ''; previous.className = 'rate-previous'; return; }
        const lastRate = parseFloat(last.rate);
        const change = rate > 0 ? (rate - lastRate) / lastRate * 100 : null;
        // No rate typed yet: drop the "(change %)" part of the message
        const text = change === null ? template.replace(/\s*\(\{change\}[^)]*\)/, '') : template;
        previous.textContent = text
            .replace('{source}', source)
            .replace('{rate}', fmt(lastRate, 2, 6))
            .replace('{date}', last.date)
            .replace('{change}', change === null ? '?' : (change > 0 ? '+' : '') + change.toFixed(2));
        previous.className = 'rate-previous' + (change !== null && Math.abs(change) > 10 ? ' is-large' : '');
    }

    form.addEventListener('input', render);
    form.addEventListener('change', render);
    render();
})();
