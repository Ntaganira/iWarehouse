/*
 * iWarehouse - budget lines and revisions (CMP 04): typing a line's year spreads it over the twelve months in whole RWF,
 * December taking the rest (as Budgets.spread does); typing a month adds the months up into the year. The footer adds
 * each month and the year over the lines. Rows are added from template.line-template and removed, their numbers kept
 * continuous (lines[0], lines[1]...). The server checks everything again.
 */
(function () {
    'use strict';

    const form = document.querySelector('form.budget-form, form.revise-form');
    if (!form) return;

    const fmt = (value) => new Intl.NumberFormat('en-US', { maximumFractionDigits: 0 }).format(value);
    const num = (el) => {
        const v = el ? parseFloat(el.value) : NaN;
        return isNaN(v) ? 0 : v;
    };
    const body = form.querySelector('tbody.line-body');
    const rows = () => Array.from(form.querySelectorAll('tr.line-row, .budget-row'));

    function spread(row) {
        const annual = Math.round(num(row.querySelector('.budget-annual')));
        const months = row.querySelectorAll('.budget-month');
        const month = Math.floor(annual / 12);
        months.forEach((m, i) => { m.value = i < 11 ? month : annual - month * 11; });
    }

    function addUp(row) {
        const annual = row.querySelector('.budget-annual');
        let total = 0;
        row.querySelectorAll('.budget-month').forEach(m => { total += num(m); });
        if (annual) annual.value = total;
    }

    function totals() {
        if (!form.querySelector('.budget-month')) return;                // read only: the page's own totals stand
        const monthTotals = new Array(12).fill(0);
        let year = 0;
        rows().forEach(row => {
            row.querySelectorAll('.budget-month').forEach((m, i) => { monthTotals[i] += num(m); });
            year += num(row.querySelector('.budget-annual'));
        });
        form.querySelectorAll('.budget-month-total').forEach((cell, i) => { cell.textContent = fmt(monthTotals[i]); });
        const total = form.querySelector('.budget-total');
        if (total) total.textContent = fmt(year);
    }

    function renumber() {
        if (!body) return;
        Array.from(body.querySelectorAll('tr.line-row')).forEach((row, i) => {
            row.querySelectorAll('[name]').forEach(el => { el.name = el.name.replace(/^lines\[\d+\]/, 'lines[' + i + ']'); });
        });
    }

    form.addEventListener('input', (e) => {
        const row = e.target.closest('tr.line-row, .budget-row');
        if (!row) return;
        if (e.target.classList.contains('budget-annual')) spread(row);
        if (e.target.classList.contains('budget-month')) addUp(row);
        totals();
    });

    form.addEventListener('click', (e) => {
        const add = e.target.closest('.line-add');
        if (add && body) {
            e.preventDefault();
            const template = form.querySelector('template.line-template');
            const index = body.querySelectorAll('tr.line-row').length;
            const fragment = template.content.cloneNode(true);
            fragment.querySelectorAll('[name]').forEach(el => { el.name = el.name.replace('{i}', String(index)); });
            body.appendChild(fragment);
            const select = body.querySelector('tr.line-row:last-child select');
            if (select) select.focus();
            return;
        }
        const remove = e.target.closest('.line-remove');
        if (remove) {
            e.preventDefault();
            remove.closest('tr.line-row').remove();
            renumber();
            totals();
        }
    });

    rows().forEach(row => {
        const annual = row.querySelector('.budget-annual');
        if (annual && annual.value === '') addUp(row);
    });
    totals();
})();
