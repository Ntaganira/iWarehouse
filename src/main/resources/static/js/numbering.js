/*
 * iWarehouse - numbering sequence form (MD-07).
 * Live preview of the next document number: PREFIX-BRANCH[-PERIOD]-SEQUENCE.
 * Mirrors DocumentNumbers.format on the server; the server's number is what counts.
 */
(function () {
    'use strict';

    const form = document.getElementById('numbering-form');
    const preview = document.getElementById('number-preview');
    if (!form || !preview) return;

    const year = form.dataset.year;
    const month = form.dataset.month;
    const field = name => form.querySelector('[name="' + name + '"]:not([type="hidden"])')
        || form.querySelector('[name="' + name + '"]');

    const docType = form.querySelector('select[name="docType"]');
    const prefix = field('prefix');

    function render() {
        const p = (prefix.value || '').trim().toUpperCase() || '?';
        const branch = (field('branchCode').value || '').trim().toUpperCase() || '?';
        const policy = field('resetPolicy').value;
        const padding = Math.min(Math.max(parseInt(field('padding').value, 10) || 6, 3), 10);
        const next = Math.max(parseInt(field('nextValue').value, 10) || 1, 1);

        let number = p + '-' + branch;
        if (policy === 'YEARLY') number += '-' + year;
        if (policy === 'MONTHLY') number += '-' + year + month;
        number += '-' + String(next).padStart(padding, '0');
        preview.textContent = number;
    }

    // A new sequence starts with the document type's usual prefix, until the user types their own.
    let prefixTouched = !!prefix.value;
    prefix.addEventListener('input', function () { prefixTouched = true; });
    if (docType) {
        docType.addEventListener('change', function () {
            const option = docType.selectedOptions[0];
            if (!prefixTouched && option && option.dataset.prefix) {
                prefix.value = option.dataset.prefix;
            }
            render();
        });
    }

    form.addEventListener('input', render);
    form.addEventListener('change', render);
    render();
})();
