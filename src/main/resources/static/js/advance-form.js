/*
 * iWarehouse - staff advances (CMP 08): the request form shows a journey's fields (per diem category, destination,
 * days, fares) or another advance's amount by its kind; paying and retiring show the petty cash fund only when the
 * money comes from or goes to petty cash. The server checks everything again.
 */
(function () {
    'use strict';

    function toggleKind() {
        const kind = document.querySelector('select[data-kind-select]');
        if (!kind) return;
        document.querySelectorAll('[data-kind]').forEach(el => {
            el.hidden = el.dataset.kind !== kind.value;
        });
    }

    function toggleFunds() {
        document.querySelectorAll('select[data-method-select]').forEach(select => {
            const box = document.querySelector('[data-fund-for="' + select.name + '"]');
            if (box) box.hidden = select.value !== 'PETTY_CASH';
            const ref = document.querySelector('[data-reference-for="' + select.name + '"]');
            if (ref) ref.hidden = !(select.value === 'BANK' || select.value === 'MOBILE_MONEY');
        });
    }

    document.addEventListener('change', (e) => {
        if (e.target.matches('select[data-kind-select]')) toggleKind();
        if (e.target.matches('select[data-method-select]')) toggleFunds();
    });
    toggleKind();
    toggleFunds();
})();
