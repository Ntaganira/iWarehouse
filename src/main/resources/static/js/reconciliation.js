/*
 * iWarehouse - reconciling a statement (ACC-12): as lines are ticked, what is still to explain (the statement's balance
 * less the previous one, less the lines ticked). The button posts once it is 0; the server checks everything again.
 */
(function () {
    'use strict';

    const form = document.querySelector('form.reconcile-form');
    if (!form) return;

    const status = form.querySelector('.reconcile-status');
    const button = form.querySelector('button[type=submit]');
    const all = form.querySelector('[data-tick-all]');
    const ticks = Array.from(form.querySelectorAll('[data-tick]'));
    const fmt = (v) => new Intl.NumberFormat('en-US', { minimumFractionDigits: 0, maximumFractionDigits: 2 }).format(v);

    function update() {
        // Cents, so 0.1 + 0.2 still makes 0.3
        const toClear = Math.round(parseFloat(status.dataset.toClear) * 100);
        const ticked = ticks.filter(t => t.checked).reduce((sum, t) => sum + Math.round(parseFloat(t.dataset.net) * 100), 0);
        const left = toClear - ticked;
        status.classList.toggle('is-done', left === 0);
        status.querySelector('.pay-status-label').textContent = left === 0 ? status.dataset.doneLabel : status.dataset.leftLabel;
        status.querySelector('.pay-status-amount').textContent = left === 0 ? '' : fmt(left / 100) + ' RWF';
        button.disabled = left !== 0;
        if (all) all.checked = ticks.length > 0 && ticks.every(t => t.checked);
    }

    ticks.forEach(t => t.addEventListener('change', update));
    if (all) {
        all.addEventListener('change', () => {
            ticks.forEach(t => { t.checked = all.checked; });
            update();
        });
    }
    update();
})();
