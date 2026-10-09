/*
 * iWarehouse - counter POS payment (POS-04).
 * Opening the payment dialog with nothing entered puts the whole total in cash (one tap for a cash sale);
 * as amounts are typed it shows what is still to pay, or the change to give back. The server checks
 * everything again.
 */
(function () {
    'use strict';

    const modal = document.getElementById('pay-modal');
    if (!modal) return;

    const total = parseFloat(modal.dataset.total) || 0;
    const inputs = Array.from(modal.querySelectorAll('input[data-pay]'));
    const cash = modal.querySelector('input[data-pay="cash"]');
    const status = modal.querySelector('.pay-status');
    const label = status.querySelector('.pay-status-label');
    const amount = status.querySelector('.pay-status-amount');
    const format = (n) => n.toLocaleString('en-US', { minimumFractionDigits: 0, maximumFractionDigits: 2 });
    const value = (input) => Math.max(parseFloat(input.value) || 0, 0);

    function update() {
        const paidCash = value(cash);
        const paidOther = inputs.filter(i => i !== cash).reduce((sum, i) => sum + value(i), 0);
        const left = Math.round((total - paidOther - paidCash) * 100) / 100;
        status.classList.remove('is-change', 'is-done', 'is-due');
        if (left > 0) {
            label.textContent = status.dataset.dueLabel;
            amount.textContent = format(left);
            status.classList.add('is-due');
        } else if (left < 0 && paidCash > 0 && paidOther <= total) {
            label.textContent = status.dataset.changeLabel;
            amount.textContent = format(-left);
            status.classList.add('is-change');
        } else {
            label.textContent = status.dataset.doneLabel;
            amount.textContent = left === 0 ? '' : format(left);
            status.classList.add(left === 0 ? 'is-done' : 'is-due');
        }
    }

    document.addEventListener('click', (e) => {
        if (!e.target.closest('[data-modal-open="pay-modal"]')) return;
        if (inputs.every(i => !i.value)) cash.value = String(total);
        update();
        setTimeout(() => { cash.focus(); cash.select(); }, 50);
    });
    modal.addEventListener('input', update);
    update();
})();
