/*
 * iWarehouse - forms with editable rows: purchase order lines (PRC-01), goods receipt crates (PRC-02),
 * shipment bills (PRC-03) and quotation lines (POS-03: whole sheets have no processing, holes or mark).
 * Add and remove rows, keep the row numbers in the field names continuous (lines[0], lines[1]...), drop
 * empty rows before sending, and show live m² and amounts. The server checks everything again; these
 * figures are only a preview (the order total is rounded once, like the server does).
 */
(function () {
    'use strict';

    const form = document.querySelector('form.po-form, form.receipt-form, form.shipment-form, form.quote-form');
    if (!form) return;

    const isOrder = form.classList.contains('po-form');
    const isShipment = form.classList.contains('shipment-form');
    const isQuote = form.classList.contains('quote-form');
    const prefix = isOrder || isQuote ? 'lines' : (isShipment ? 'costs' : 'crates');
    const body = form.querySelector('tbody.line-body');
    const template = form.querySelector('template.line-template');
    const addButton = form.querySelector('.line-add');

    const fmt = (value, min, max) => new Intl.NumberFormat('en-US', {
        minimumFractionDigits: min, maximumFractionDigits: max
    }).format(value);
    const num = (input) => {
        const v = input ? parseFloat(input.value) : NaN;
        return isNaN(v) ? null : v;
    };
    const field = (row, name) => row.querySelector('[name$="].' + name + '"]');
    // m² of one sheet, rounded to 4 decimals like Pricing.areaM2
    const sheetArea = (w, h) => Math.round(w * h / 100) / 10000;

    function rows() {
        return Array.from(body.querySelectorAll('tr.line-row'));
    }

    function renumber() {
        const pattern = new RegExp('^' + prefix + '\\[\\d+\\]');
        rows().forEach((row, i) => {
            row.querySelectorAll('[name]').forEach(el => {
                el.name = el.name.replace(pattern, prefix + '[' + i + ']');
            });
        });
    }

    function isBlank(row) {
        if (isShipment) {
            // The currency is pre-filled, so it does not count.
            return ['costType', 'description', 'supplierId', 'invoiceRef', 'invoiceDate', 'amount']
                .every(n => !field(row, n).value.trim());
        }
        if (isOrder) {
            return !field(row, 'productId').value && ['widthMm', 'heightMm', 'quantity', 'pricePerM2']
                .every(n => !field(row, n).value);
        }
        if (isQuote) {
            // The quantity and the kind start filled, so they do not count
            return !field(row, 'productId').value && ['widthMm', 'heightMm', 'mark', 'discountPercent']
                .every(n => !field(row, n).value.trim()) && !row.querySelector('input[type=checkbox]:checked');
        }
        const broken = field(row, 'broken').value;
        return !field(row, 'poLineId').value && !field(row, 'batchNo').value.trim()
            && !field(row, 'sheets').value && (!broken || broken === '0') && !field(row, 'locationId').value;
    }

    // ---------------------------------------------------------------- purchase order

    const supplier = isOrder ? form.querySelector('select[name="supplierId"]') : null;
    const incoterm = isOrder ? form.querySelector('select[name="incoterm"]') : null;
    let lastSupplierIncoterm = null;

    function currency() {
        const option = supplier && supplier.selectedOptions[0];
        return {
            code: option && option.dataset.currency ? option.dataset.currency : '',
            decimals: option && option.dataset.decimals ? parseInt(option.dataset.decimals, 10) : 2
        };
    }

    function onSupplierChange() {
        const option = supplier.selectedOptions[0];
        const proposed = option && option.dataset.incoterm ? option.dataset.incoterm : '';
        // Follow the supplier's default unless the user picked another incoterm.
        if (incoterm && (incoterm.value === '' || incoterm.value === lastSupplierIncoterm)) {
            incoterm.value = proposed;
        }
        lastSupplierIncoterm = proposed;
        recalc();
    }

    function recalcOrder() {
        const cur = currency();
        form.querySelectorAll('.po-currency').forEach(el => { el.textContent = cur.code || '—'; });
        let sheets = 0, area = 0, amount = 0;
        rows().forEach(row => {
            const w = num(field(row, 'widthMm')), h = num(field(row, 'heightMm'));
            const q = num(field(row, 'quantity')), p = num(field(row, 'pricePerM2'));
            const areaCell = row.querySelector('.line-area'), amountCell = row.querySelector('.line-amount');
            if (w && h && q) {
                const a = sheetArea(w, h) * q;
                areaCell.textContent = fmt(a, 0, 4);
                sheets += q;
                area += a;
                if (p) {
                    amountCell.textContent = fmt(a * p, cur.decimals, cur.decimals);
                    amount += a * p;
                } else {
                    amountCell.textContent = '—';
                }
            } else {
                areaCell.textContent = '—';
                amountCell.textContent = '—';
            }
        });
        document.getElementById('po-total-sheets').textContent = fmt(sheets, 0, 0);
        document.getElementById('po-total-area').textContent = fmt(area, 0, 4);
        document.getElementById('po-total-amount').textContent =
            fmt(amount, cur.decimals, cur.decimals) + (cur.code ? ' ' + cur.code : '');
    }

    // ---------------------------------------------------------------- goods receipt

    function onLineChange(select) {
        const row = select.closest('tr');
        const option = select.selectedOptions[0];
        const w = field(row, 'widthMm'), h = field(row, 'heightMm');
        const previous = select.dataset.previous ? JSON.parse(select.dataset.previous) : null;
        // Take the ordered size unless the user typed another one.
        if (option && option.dataset.w) {
            if (!w.value || (previous && w.value === previous.w)) w.value = option.dataset.w;
            if (!h.value || (previous && h.value === previous.h)) h.value = option.dataset.h;
            select.dataset.previous = JSON.stringify({ w: option.dataset.w, h: option.dataset.h });
        }
        recalc();
    }

    function recalcReceipt() {
        let crates = 0, sheets = 0, broken = 0, area = 0;
        rows().forEach(row => {
            const w = num(field(row, 'widthMm')), h = num(field(row, 'heightMm'));
            const s = num(field(row, 'sheets')) || 0, b = num(field(row, 'broken')) || 0;
            const cell = row.querySelector('.line-area');
            if (!isBlank(row)) crates++;
            sheets += s;
            broken += b;
            if (w && h && s) {
                const a = sheetArea(w, h) * s;
                cell.textContent = fmt(a, 0, 4);
                area += a;
            } else {
                cell.textContent = '—';
            }
        });
        document.getElementById('receipt-total-crates').textContent = fmt(crates, 0, 0);
        document.getElementById('receipt-total-sheets').textContent = fmt(sheets, 0, 0);
        document.getElementById('receipt-total-broken').textContent = fmt(broken, 0, 0);
        document.getElementById('receipt-total-area').textContent = fmt(area, 0, 4);
    }

    // ---------------------------------------------------------------- quotation

    // Whole sheets come from stock as they are: no processing, holes or mark (disabled fields are not sent)
    function onKind(row) {
        const sheet = row.querySelector('[data-quote-kind]').value === 'SHEET';
        row.classList.toggle('is-sheet', sheet);
        row.querySelectorAll('input[type=checkbox]').forEach(c => { if (sheet) c.checked = false; c.disabled = sheet; });
        ['holes', 'mark'].forEach(n => { const f = field(row, n); if (sheet) f.value = ''; f.disabled = sheet; });
    }

    function recalcQuote() {
        let pieces = 0, area = 0;
        rows().forEach(row => {
            const w = num(field(row, 'widthMm')), h = num(field(row, 'heightMm')), q = num(field(row, 'quantity'));
            const cell = row.querySelector('.line-area');
            if (w && h && q) {
                const a = sheetArea(w, h) * q;
                cell.textContent = fmt(a, 0, 4);
                pieces += q;
                area += a;
            } else {
                cell.textContent = '—';
            }
        });
        document.getElementById('quote-total-pieces').textContent = fmt(pieces, 0, 0);
        document.getElementById('quote-total-area').textContent = fmt(area, 0, 4);
    }

    // ---------------------------------------------------------------- shared

    function recalc() {
        if (isShipment) return; // bills are in several currencies: the shipment page shows them in RWF
        if (isQuote) recalcQuote(); else if (isOrder) recalcOrder(); else recalcReceipt();
    }

    function addRow() {
        const index = rows().length;
        const fragment = template.content.cloneNode(true);
        fragment.querySelectorAll('[name]').forEach(el => {
            el.name = el.name.replace('{i}', String(index));
        });
        const row = fragment.querySelector('tr');
        body.appendChild(fragment);
        recalc();
        const first = row.querySelector('select, input:not([type=hidden])');
        if (first) first.focus();
    }

    body.addEventListener('input', recalc);
    body.addEventListener('change', (e) => {
        if (isQuote && e.target.matches('[data-quote-kind]')) {
            onKind(e.target.closest('tr'));
        } else if (!isOrder && !isShipment && !isQuote && e.target.matches('select[name$="].poLineId"]')) {
            onLineChange(e.target);
        } else {
            recalc();
        }
    });
    body.addEventListener('click', (e) => {
        const remove = e.target.closest('.line-remove');
        if (!remove) return;
        remove.closest('tr').remove();
        if (rows().length === 0) addRow();
        renumber();
        recalc();
    });
    if (addButton) addButton.addEventListener('click', addRow);
    form.addEventListener('submit', () => {
        rows().forEach(row => { if (isBlank(row) && rows().length > 1) row.remove(); });
        renumber();
    });

    if (isOrder && supplier) {
        const option = supplier.selectedOptions[0];
        lastSupplierIncoterm = option && option.dataset.incoterm ? option.dataset.incoterm : '';
        supplier.addEventListener('change', onSupplierChange);
    }
    if (isQuote) {
        rows().forEach(onKind);
    }
    if (!isOrder && !isShipment && !isQuote) {
        body.querySelectorAll('select[name$="].poLineId"]').forEach(select => {
            const option = select.selectedOptions[0];
            if (option && option.dataset.w) {
                select.dataset.previous = JSON.stringify({ w: option.dataset.w, h: option.dataset.h });
            }
        });
    }
    recalc();
})();
