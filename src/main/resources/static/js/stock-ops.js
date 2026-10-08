/*
 * iWarehouse - stock operations forms (INV-07).
 * Transfer: counts the label codes scanned or typed (one per line; spaces and commas also separate them).
 * Adjustment: add and remove change rows (tbody.line-body[data-prefix=lines]) keeping their numbers
 * continuous, and show only the fields a row's kind needs (cells carry data-kinds="WRITE_OFF RESIZE"...);
 * hidden fields are disabled so they are not sent. Empty rows are dropped before sending. The server checks
 * everything again.
 */
(function () {
    'use strict';

    // ---------------------------------------------------------------- transfer
    const transfer = document.querySelector('form.transfer-form');
    if (transfer) {
        const codes = transfer.querySelector('textarea[name="codes"]');
        const count = document.getElementById('transfer-count');
        const recount = () => {
            const list = codes.value.split(/[\s,;]+/).map(c => c.trim().toUpperCase()).filter(Boolean);
            count.textContent = String(new Set(list).size);
        };
        codes.addEventListener('input', recount);
        recount();
    }

    // ---------------------------------------------------------------- adjustment
    const form = document.querySelector('form.adjustment-form');
    if (!form) return;

    const body = form.querySelector('tbody.line-body[data-prefix="lines"]');
    const template = form.querySelector('template.line-template[data-for="lines"]');
    const rows = () => Array.from(body.querySelectorAll('tr.line-row'));
    const field = (row, name) => row.querySelector('[name$="].' + name + '"]');

    function renumber() {
        rows().forEach((row, i) => {
            row.querySelectorAll('[name]').forEach(el => {
                el.name = el.name.replace(/^lines\[\d+\]/, 'lines[' + i + ']');
            });
        });
    }

    function applyKind(row) {
        const kind = row.querySelector('select.adjust-kind').value;
        row.querySelectorAll('td[data-kinds]').forEach(cell => {
            const on = cell.dataset.kinds.split(' ').includes(kind);
            cell.classList.toggle('field-off', !on);
            cell.querySelectorAll('input, select').forEach(el => { el.disabled = !on; });
        });
    }

    function isBlank(row) {
        return ['unitCode', 'widthMm', 'heightMm'].every(n => !field(row, n).value.trim())
            && ['productId', 'locationId'].every(n => !field(row, n).value);
    }

    function addRow() {
        const fragment = template.content.cloneNode(true);
        fragment.querySelectorAll('[name]').forEach(el => { el.name = el.name.replace('{i}', String(rows().length)); });
        const row = fragment.querySelector('tr');
        body.appendChild(fragment);
        applyKind(row);
        const first = row.querySelector('select, input');
        if (first) first.focus();
    }

    form.querySelector('.line-add').addEventListener('click', addRow);
    body.addEventListener('click', (e) => {
        const remove = e.target.closest('.line-remove');
        if (!remove) return;
        remove.closest('tr').remove();
        if (rows().length === 0) addRow();
        renumber();
    });
    body.addEventListener('change', (e) => {
        if (e.target.matches('select.adjust-kind')) applyKind(e.target.closest('tr'));
    });
    form.addEventListener('submit', () => {
        rows().forEach(row => { if (isBlank(row) && rows().length > 1) row.remove(); });
        renumber();
    });
    rows().forEach(applyKind);
})();
