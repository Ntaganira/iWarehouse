/*
 * iWarehouse - recording a supplier invoice (ACC-09): adds up the goods receipts ticked, in the invoice's currency, to
 * compare with the total on the supplier's invoice. The server checks it again.
 */
(function () {
    'use strict';
    const form = document.querySelector('form.supplier-invoice-form');
    if (!form) return;
    const status = form.querySelector('.supplier-invoice-total');
    const label = status.querySelector('.pay-status-label');
    const amount = status.querySelector('.pay-status-amount');
    function update() {
        const total = [...form.querySelectorAll('[data-receipt-pick]:checked')].reduce((s, c) => s + (parseFloat(c.dataset.amount) || 0), 0);
        status.classList.toggle('is-done', total > 0);
        status.classList.toggle('is-due', total === 0);
        label.textContent = total > 0 ? status.dataset.totalLabel : status.dataset.noneLabel;
        amount.textContent = total > 0 ? total.toLocaleString('en-US', { minimumFractionDigits: 2, maximumFractionDigits: 2 }) + ' ' + status.dataset.currency : '';
    }
    form.addEventListener('change', update);
    update();
})();
