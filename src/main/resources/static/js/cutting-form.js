/*
 * iWarehouse - cutting job forms (PRD-01..08): the pieces wanted on a job, and recording a cut.
 * Add and remove rows in each table (tbody.line-body[data-prefix]), keep their numbers continuous
 * (leftovers[0], leftovers[1]...) and drop empty rows before sending.
 * Job form: m² per size and in total; the customer fields only for pieces cut for a customer.
 * Cut form: m² per row, whether a leftover becomes an off-cut or cullet (PRD-04, PRD-05) and the area
 * check (PRD-06). The server checks everything again; these figures are only a preview.
 */
(function () {
    'use strict';

    const form = document.querySelector('form.cutting-form, form.cut-form');
    if (!form) return;

    const isCut = form.classList.contains('cut-form');
    const fmt = (value, min, max) => new Intl.NumberFormat('en-US', {
        minimumFractionDigits: min, maximumFractionDigits: max
    }).format(value);
    const num = (input) => {
        const v = input ? parseFloat(input.value) : NaN;
        return isNaN(v) ? null : v;
    };
    const field = (row, name) => row.querySelector('[name$="].' + name + '"]');
    // m² of one piece, rounded to 4 decimals like Pricing.areaM2
    const pieceArea = (w, h) => Math.round(w * h / 100) / 10000;

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
        });
    }

    function isBlank(row, prefix) {
        const sizeBlank = !field(row, 'widthMm').value && !field(row, 'heightMm').value;
        if (prefix === 'lines') {
            return sizeBlank && !field(row, 'mark').value.trim() && !row.querySelector('input[type=checkbox]:checked');
        }
        if (prefix === 'broken') {
            return sizeBlank && !field(row, 'reason').value && !field(row, 'note').value.trim();
        }
        return sizeBlank;
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
        const row = fragment.querySelector('tr');
        body.appendChild(fragment);
        recalc();
        const first = row.querySelector('input:not([type=hidden]), select');
        if (first) first.focus();
    }

    // ---------------------------------------------------------------- job form

    function recalcJob() {
        let pieces = 0, area = 0;
        rowsOf(bodyOf('lines')).forEach(row => {
            const w = num(field(row, 'widthMm')), h = num(field(row, 'heightMm')), q = num(field(row, 'quantity'));
            const cell = row.querySelector('.line-area');
            if (w && h && q) {
                const a = pieceArea(w, h) * q;
                cell.textContent = fmt(a, 4, 4);
                pieces += q;
                area += a;
            } else {
                cell.textContent = '—';
            }
        });
        document.getElementById('cut-total-pieces').textContent = fmt(pieces, 0, 0);
        document.getElementById('cut-total-area').textContent = fmt(area, 4, 4);
    }

    const purpose = form.querySelector('select[name="purpose"]');
    function onPurpose() {
        form.classList.toggle('for-stock', purpose.value !== 'CUSTOMER');
    }

    // ---------------------------------------------------------------- cut form

    const source = {
        area: parseFloat(form.dataset.sourceArea),
        w: parseInt(form.dataset.sourceW, 10),
        h: parseInt(form.dataset.sourceH, 10)
    };
    const threshold = { area: parseFloat(form.dataset.minArea), side: parseInt(form.dataset.minSide, 10) };
    const fits = (w, h) => (w <= source.w && h <= source.h) || (w <= source.h && h <= source.w);
    const isOffcut = (w, h) => Math.min(w, h) >= threshold.side && pieceArea(w, h) >= threshold.area;

    function setText(id, value) {
        const el = document.getElementById(id);
        if (el) el.textContent = value;
    }

    function recalcCut() {
        let pieces = 0, offcuts = 0, small = 0, broken = 0;
        form.querySelectorAll('tr.piece-row').forEach(row => {
            const q = num(row.querySelector('input.input-qty')) || 0;
            pieces += parseFloat(row.dataset.area) * q;
        });
        rowsOf(bodyOf('leftovers')).forEach(row => {
            const w = num(field(row, 'widthMm')), h = num(field(row, 'heightMm')), q = num(field(row, 'quantity')) || 0;
            const areaCell = row.querySelector('.line-area'), kindCell = row.querySelector('.line-kind');
            kindCell.className = 'line-kind';
            if (w && h && q) {
                const a = pieceArea(w, h) * q;
                areaCell.textContent = fmt(a, 4, 4);
                if (!fits(w, h)) {
                    kindCell.textContent = form.dataset.labelToobig;
                    kindCell.classList.add('is-over');
                } else if (isOffcut(w, h)) {
                    kindCell.textContent = form.dataset.labelOffcut;
                    kindCell.classList.add('is-offcut');
                    offcuts += a;
                } else {
                    kindCell.textContent = form.dataset.labelCullet;
                    kindCell.classList.add('is-cullet');
                    small += a;
                }
            } else {
                areaCell.textContent = '—';
                kindCell.textContent = '—';
            }
        });
        rowsOf(bodyOf('broken')).forEach(row => {
            const w = num(field(row, 'widthMm')), h = num(field(row, 'heightMm')), q = num(field(row, 'quantity')) || 0;
            const cell = row.querySelector('.line-area');
            if (w && h && q) {
                const a = pieceArea(w, h) * q;
                cell.textContent = fmt(a, 4, 4);
                broken += a;
            } else {
                cell.textContent = '—';
            }
        });
        const recorded = pieces + offcuts + small + broken;
        const trim = Math.max(0, source.area - recorded);
        const excess = Math.max(0, recorded - source.area);
        setText('bal-pieces', fmt(pieces, 4, 4));
        setText('bal-offcuts', fmt(offcuts, 4, 4));
        setText('bal-small', fmt(small, 4, 4));
        setText('bal-broken', fmt(broken, 4, 4));
        setText('bal-trim', fmt(trim, 4, 4));
        setText('bal-yield', fmt(Math.min(100, (pieces + offcuts) * 100 / source.area), 1, 1) + ' %');
        const status = document.getElementById('bal-status');
        const over = excess > source.area * 0.01 + 1e-9;
        status.textContent = over
            ? status.dataset.over.replace('{0}', fmt(excess, 4, 4))
            : status.dataset.ok.replace('{0}', fmt(trim + small, 4, 4));
        status.classList.toggle('is-over', over);
        status.classList.toggle('is-ok', !over);
    }

    // ---------------------------------------------------------------- shared

    function recalc() {
        if (isCut) recalcCut(); else recalcJob();
    }

    form.querySelectorAll('.line-add').forEach(button => {
        button.addEventListener('click', () => addRow(button.dataset.for));
    });
    bodies.forEach(body => {
        body.addEventListener('click', (e) => {
            const remove = e.target.closest('.line-remove');
            if (!remove) return;
            remove.closest('tr').remove();
            if (rowsOf(body).length === 0) addRow(body.dataset.prefix);
            renumber(body);
            recalc();
        });
    });
    form.addEventListener('input', recalc);
    form.addEventListener('change', recalc);
    form.addEventListener('submit', () => {
        bodies.forEach(body => {
            const prefix = body.dataset.prefix;
            rowsOf(body).forEach(row => {
                // The job keeps one row (the server then says a size is needed); the cut's lists may be empty.
                if (isBlank(row, prefix) && (prefix !== 'lines' || rowsOf(body).length > 1)) row.remove();
            });
            renumber(body);
        });
    });

    if (purpose) {
        purpose.addEventListener('change', onPurpose);
        onPurpose();
    }
    recalc();
})();
