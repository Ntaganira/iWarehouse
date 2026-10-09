/*
 * iWarehouse - counter POS payment (POS-04), price changes and approvals (POS-05, POS-06), deposits (POS-08).
 * Opening a payment dialog (a sale, or an order's balance) with nothing entered puts the whole amount in cash
 * (one tap for a cash sale); as amounts are typed it shows what is still to pay, or the change to give back.
 * With "a deposit" ticked, the cash is the deposit: it shows what the deposit still lacks, then the balance
 * left for collection. A line's price dialog turns a discount into a price and back, and says whether a
 * manager must approve it. While requests wait, the page asks every few seconds where they stand and reloads
 * once one is decided. The server checks everything again.
 */
(function () {
    'use strict';

    const format = (n) => n.toLocaleString('en-US', { minimumFractionDigits: 0, maximumFractionDigits: 2 });
    const value = (input) => Math.max(parseFloat(input.value) || 0, 0);
    const round2 = (n) => Math.round(n * 100) / 100;

    document.querySelectorAll('.modal-overlay[data-total]').forEach(modal => {
        if (!modal.querySelector('form.pos-pay')) return;
        const total = parseFloat(modal.dataset.total) || 0;
        const depositMin = parseFloat(modal.dataset.depositMin) || 0;
        const inputs = Array.from(modal.querySelectorAll('input[data-pay]'));
        const cash = modal.querySelector('input[data-pay="cash"]');
        const deposit = modal.querySelector('input[data-pay-deposit]');
        const status = modal.querySelector('.pay-status');
        const label = status.querySelector('.pay-status-label');
        const amount = status.querySelector('.pay-status-amount');

        function show(text, sum, state) {
            status.classList.remove('is-change', 'is-done', 'is-due');
            label.textContent = text;
            amount.textContent = sum;
            status.classList.add(state);
        }

        function update() {
            const paidCash = value(cash);
            const paidOther = inputs.filter(i => i !== cash).reduce((sum, i) => sum + value(i), 0);
            const left = round2(total - paidOther - paidCash);
            if (left > 0 && deposit && deposit.checked) {
                const short = round2(depositMin - paidOther - paidCash);
                if (short > 0) show(status.dataset.depositDueLabel, format(short), 'is-due');
                else show(status.dataset.balanceLabel, format(left), 'is-done');
            } else if (left > 0) {
                show(status.dataset.dueLabel, format(left), 'is-due');
            } else if (left < 0 && paidCash > 0 && paidOther <= total) {
                show(status.dataset.changeLabel, format(-left), 'is-change');
            } else {
                show(status.dataset.doneLabel, left === 0 ? '' : format(left), left === 0 ? 'is-done' : 'is-due');
            }
        }

        document.addEventListener('click', (e) => {
            if (!e.target.closest(`[data-modal-open="${modal.id}"]`)) return;
            if (inputs.every(i => !i.value)) cash.value = String(deposit && deposit.checked ? depositMin : total);
            update();
            setTimeout(() => { cash.focus(); cash.select(); }, 50);
        });
        // Ticking "a deposit" on a dialog still at the full cash amount offers the smallest deposit instead
        if (deposit) {
            deposit.addEventListener('change', () => {
                const others = inputs.filter(i => i !== cash).every(i => !i.value);
                if (others && deposit.checked && value(cash) === total) cash.value = String(depositMin);
                else if (others && !deposit.checked && value(cash) === depositMin) cash.value = String(total);
                update();
            });
        }
        modal.addEventListener('input', update);
        update();
    });
})();

(function () {
    'use strict';

    // A line's price: the discount gives the price and the price the discount (2 decimals, as the server rounds)
    const round2 = (n) => Math.round(n * 100) / 100;
    document.querySelectorAll('form.pos-price').forEach(form => {
        const list = parseFloat(form.dataset.listPrice) || 0;
        const limit = parseFloat(form.dataset.limit) || 0;
        const discount = form.querySelector('[data-price-discount]');
        const price = form.querySelector('[data-price-value]');
        const status = form.querySelector('.price-status');

        function show() {
            const p = parseFloat(price.value);
            const d = list > 0 && p > 0 ? round2((list - p) * 100 / list) : 0;
            let text = form.dataset.withinLabel;
            if (!(p > 0) || p === list) text = form.dataset.listLabel;
            else if (d < 0) text = form.dataset.raisedLabel;
            else if (d > limit) text = form.dataset.approvalLabel;
            status.textContent = text.replace('{0}', d.toLocaleString('en-US', { maximumFractionDigits: 2 }));
            status.classList.toggle('is-warning', d > limit && p !== list);
        }
        discount.addEventListener('input', () => {
            const d = parseFloat(discount.value);
            price.value = isNaN(d) ? String(list) : String(round2(list * (100 - d) / 100));
            show();
        });
        price.addEventListener('input', () => {
            const p = parseFloat(price.value);
            discount.value = p > 0 && list > 0 && p < list ? String(round2((list - p) * 100 / list)) : '';
            show();
        });
        const restore = form.querySelector('[data-price-restore]');
        if (restore) {
            restore.addEventListener('click', () => {
                price.value = String(list);
                discount.value = '';
                form.submit();
            });
        }
        show();
    });

    // Requests waiting for a manager: reload once one is decided, unless the cashier is in a dialog or typing
    const waiting = document.querySelector('[data-approvals-state]');
    if (!waiting) return;
    const state = waiting.dataset.approvalsState;
    const busy = () => document.querySelector('.modal-overlay.open')
        || Array.from(document.querySelectorAll('.page-content input[type=text], .page-content textarea'))
            .some(f => f.value.trim() !== '' && f === document.activeElement);
    let changed = false;
    async function check() {
        try {
            if (!changed) {
                const res = await fetch('/pos/approvals/state', { headers: { 'Accept': 'application/json' }, cache: 'no-store' });
                if (res.ok && res.headers.get('content-type')?.includes('json')) {
                    changed = (await res.json()).state !== state;
                }
            }
            if (changed && !busy()) {
                location.reload();
                return;
            }
        } catch (e) {
            // offline for a moment: try again next time
        }
        setTimeout(check, 8000);
    }
    setTimeout(check, 8000);
})();
