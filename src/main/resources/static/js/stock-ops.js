/*
 * iWarehouse - stock operations forms (INV-07, INV-08).
 * Stock count: Enter in the scan field scans (scanners send Enter); pasted lists keep their lines.
 * Transfer: counts the label codes scanned or typed (one per line; spaces and commas also separate them);
 * a scanned rack or slot label chooses the destination.
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
        const to = transfer.querySelector('select[name="toLocationId"]');
        const scannedNote = document.getElementById('transfer-to-scanned');
        const places = new Map(Array.from(to.options).filter(o => o.dataset.code).map(o => [o.dataset.code, o]));
        const split = text => text.split(/[\s,;]+/).map(c => c.trim().toUpperCase()).filter(Boolean);
        const recount = () => { count.textContent = String(new Set(split(codes.value)).size); };
        // A rack or slot label scanned with the units (MD-02) chooses where they go: it leaves the list
        // and is selected. Only finished codes count: a scanner is still typing the last one.
        const takePlace = () => {
            const value = codes.value;
            const finished = /[\s,;]$/.test(value) ? value : value.replace(/[^\s,;]*$/, '');
            const code = split(finished).find(c => places.has(c));
            if (!code) return;
            to.value = places.get(code).value;
            const escaped = code.replace(/[-\\^$*+?.()|[\]{}]/g, '\\$&');
            codes.value = value.replace(new RegExp('(^|[\\s,;])' + escaped + '(?=[\\s,;]|$)', 'gi'), '$1')
                .replace(/^[\s,;]+/, '').replace(/\n{2,}/g, '\n');
            scannedNote.textContent = scannedNote.dataset.text.replace('{0}', code);
            scannedNote.hidden = false;
        };
        codes.addEventListener('input', () => { takePlace(); recount(); });
        to.addEventListener('change', () => { scannedNote.hidden = true; });
        takePlace();
        recount();
    }

    // ---------------------------------------------------------------- stock count (INV-08)
    // The scan field is a one-row textarea: Enter (what a scanner sends after a label) scans at once,
    // while a pasted list keeps its line breaks (an input would run the codes together).
    document.querySelectorAll('textarea[data-enter-submits]').forEach(function (area) {
        area.addEventListener('keydown', function (e) {
            if (e.key === 'Enter' && !e.shiftKey && !e.isComposing) {
                e.preventDefault();
                if (area.value.trim()) area.form.requestSubmit();
            }
        });
    });

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
