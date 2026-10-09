/*
 * iWarehouse - the return and cancel forms (POS-09). As units are ticked, or the pieces of a size given up are entered,
 * it shows the credit: per invoice line, the running share of its amount and its processing for those pieces (whole
 * RWF, half up, as the server credits it), and how much of it comes off the invoice's balance due. The server works it
 * out again.
 */
(function () {
    'use strict';

    const form = document.querySelector('form.credit-note-form');
    if (!form) return;
    const status = form.querySelector('.credit-note-total');
    const label = status.querySelector('.pay-status-label');
    const amount = status.querySelector('.pay-status-amount');
    const balanceDue = parseFloat(status.dataset.balanceDue) || 0;
    const format = (n) => n.toLocaleString('en-US', { maximumFractionDigits: 0 });
    const share = (a, n, pieces) => Math.round(a * pieces / n);

    function update() {
        const lines = new Map();
        form.querySelectorAll('tr[data-line]').forEach(row => {
            // A unit ticked (return), or the pieces given up of a size (cancel), never more than it can give up
            const tick = row.querySelector('[data-return-pick]');
            const qty = row.querySelector('[data-cancel-qty]');
            const picked = tick ? (tick.checked ? 1 : 0)
                : Math.min(Math.max(parseInt(qty.value, 10) || 0, 0), parseInt(qty.max, 10) || 0);
            if (!picked) return;
            const line = lines.get(row.dataset.line) || { row, picked: 0 };
            line.picked += picked;
            lines.set(row.dataset.line, line);
        });
        let total = 0;
        lines.forEach(({ row, picked }) => {
            const n = parseInt(row.dataset.quantity, 10);
            const before = parseInt(row.dataset.returned, 10);
            row.dataset.amounts.split(',').map(parseFloat).forEach(a => {
                total += share(a, n, before + picked) - share(a, n, before);
            });
        });
        status.classList.remove('is-done', 'is-due');
        if (total <= 0) {
            label.textContent = status.dataset.noneLabel;
            amount.textContent = '';
            status.classList.add('is-due');
            return;
        }
        const reduced = Math.min(balanceDue, total);
        label.textContent = reduced > 0
            ? status.dataset.reducesLabel.replace('{0}', format(reduced)).replace('{1}', format(total - reduced))
            : status.dataset.totalLabel;
        amount.textContent = format(total);
        status.classList.add('is-done');
    }

    form.addEventListener('change', update);
    form.addEventListener('input', update);
    update();
})();
