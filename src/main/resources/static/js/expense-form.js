/*
 * iWarehouse - expense bill, requisition and advance retirement forms (CMP 01, 05, 06, 08).
 * Rows in each table (tbody.line-body[data-prefix]) are added from template.line-template[data-for] and removed;
 * their numbers stay continuous (lines[0], quotes[0]...). The chosen quote is a radio whose value is its row.
 * Live figures: a bill line's amount with VAT and the bill's totals (VAT at most 18 % of a line); a requisition
 * line's amount, the estimated total, the sourcing its value calls for and the cheapest quote; an advance's receipts
 * against what is still owed (the rest returned, or paid back to the employee). The server checks
 * everything again; these figures are only a preview.
 */
(function () {
    'use strict';

    const form = document.querySelector('form.expense-form, form.requisition-form, form.retire-form');
    if (!form) return;

    const isBill = form.classList.contains('expense-form');
    const isRetire = form.classList.contains('retire-form');
    const fmt = (value) => new Intl.NumberFormat('en-US', { minimumFractionDigits: 0, maximumFractionDigits: 2 }).format(value);
    const num = (el) => {
        const v = el ? parseFloat(el.value) : NaN;
        return isNaN(v) ? null : v;
    };
    const field = (row, name) => row.querySelector('[name$="].' + name + '"]');
    const bodies = Array.from(form.querySelectorAll('tbody.line-body[data-prefix]'));
    const rowsOf = (body) => Array.from(body.querySelectorAll('tr.line-row'));
    const bodyOf = (prefix) => bodies.find(b => b.dataset.prefix === prefix);

    function renumber(body) {
        const prefix = body.dataset.prefix;
        const pattern = new RegExp('^' + prefix + '\\[\\d+\\]');
        rowsOf(body).forEach((row, i) => {
            row.querySelectorAll('[name]').forEach(el => {
                el.name = el.name.replace(pattern, prefix + '[' + i + ']');
            });
            const choice = row.querySelector('input.quote-choice');
            if (choice) choice.value = String(i);
        });
    }

    function addRow(prefix) {
        const body = bodyOf(prefix);
        const template = form.querySelector('template.line-template[data-for="' + prefix + '"]');
        if (!body || !template) return;
        const index = rowsOf(body).length;
        const fragment = template.content.cloneNode(true);
        fragment.querySelectorAll('[name]').forEach(el => {
            el.name = el.name.replace('{i}', String(index));
        });
        const choice = fragment.querySelector('input.quote-choice');
        if (choice) choice.value = String(index);
        const row = fragment.querySelector('tr');
        body.appendChild(fragment);
        recalc();
        const first = row.querySelector('input:not([type=hidden]):not([type=radio]), select');
        if (first) first.focus();
    }

    // ---------------------------------------------------------------- an expense bill

    function recalcBill() {
        let net = 0, vat = 0;
        rowsOf(bodyOf('lines')).forEach(row => {
            const n = num(field(row, 'netAmount')), v = num(field(row, 'vatAmount')) || 0;
            const cell = row.querySelector('.line-amount');
            const vatInput = field(row, 'vatAmount');
            if (n) {
                cell.textContent = fmt(n + v);
                net += n;
                vat += v;
                // VAT is at most 18 % of the line (rounded up to the franc)
                vatInput.classList.toggle('is-invalid', v > Math.ceil(n * 0.18));
            } else {
                cell.textContent = '—';
                vatInput.classList.remove('is-invalid');
            }
        });
        const set = (sel, value) => { const el = form.querySelector(sel); if (el) el.textContent = fmt(value); };
        set('.bill-net', net);
        set('.bill-vat', vat);
        set('.bill-total', net + vat);
    }

    // ---------------------------------------------------------------- a requisition

    function recalcRequisition() {
        let total = 0;
        rowsOf(bodyOf('lines')).forEach(row => {
            const q = num(field(row, 'quantity')), c = num(field(row, 'unitCost'));
            const cell = row.querySelector('.line-amount');
            if (q && c !== null) {
                cell.textContent = fmt(q * c);
                total += q * c;
            } else {
                cell.textContent = '—';
            }
        });
        const totalCell = form.querySelector('.req-total');
        if (totalCell) totalCell.textContent = fmt(total);
        // The sourcing its value calls for (CMP 06), from the Settings' tiers on the form
        const hint = form.querySelector('.req-sourcing');
        if (hint) {
            const three = parseFloat(hint.dataset.tThree), rfq = parseFloat(hint.dataset.tRfq), tender = parseFloat(hint.dataset.tTender);
            const key = total >= tender ? 'lTender' : total >= rfq ? 'lRfq' : total >= three ? 'lThree' : 'lSingle';
            hint.textContent = hint.dataset[key];
        }
        // The cheapest quote
        let cheapest = null;
        const quotes = bodyOf('quotes') ? rowsOf(bodyOf('quotes')) : [];
        quotes.forEach(row => {
            const a = num(field(row, 'amount'));
            row.classList.remove('is-cheapest');
            if (a && (cheapest === null || a < cheapest.amount)) cheapest = { row, amount: a };
        });
        if (cheapest && quotes.length > 1) cheapest.row.classList.add('is-cheapest');
    }

    // ---------------------------------------------------------------- an advance retired (CMP 08)

    function recalcRetire() {
        let spent = 0;
        rowsOf(bodyOf('lines')).forEach(row => {
            const a = num(field(row, 'amount'));
            if (a) spent += a;
        });
        const owed = parseFloat(form.dataset.outstanding) || 0;
        const rest = Math.round((owed - spent) * 100) / 100;
        const set = (sel, text) => { const el = form.querySelector(sel); if (el) el.textContent = text; };
        set('.retire-spent', fmt(spent));
        const settle = form.querySelector('.retire-settle');
        if (settle) {
            settle.textContent = rest > 0 ? settle.dataset.returned.replace('{0}', fmt(rest))
                : rest < 0 ? settle.dataset.paidBack.replace('{0}', fmt(-rest)) : settle.dataset.even;
        }
        const method = form.querySelector('.retire-method');
        if (method) method.hidden = rest === 0;
    }

    function recalc() {
        if (isRetire) recalcRetire(); else if (isBill) recalcBill(); else recalcRequisition();
    }

    form.addEventListener('click', (e) => {
        const add = e.target.closest('.line-add');
        if (add) {
            e.preventDefault();
            addRow(add.dataset.for);
            return;
        }
        const remove = e.target.closest('.line-remove');
        if (remove) {
            e.preventDefault();
            const row = remove.closest('tr.line-row');
            const body = row.closest('tbody.line-body');
            row.remove();
            renumber(body);
            recalc();
        }
    });
    form.addEventListener('input', recalc);
    form.addEventListener('change', recalc);
    recalc();
})();
