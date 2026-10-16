/*
 * iWarehouse - fixed assets (C.08): choosing a category proposes its usual useful life; disposing shows the reference only
 * for money into the bank or mobile money, and the payment fields only when the asset fetched something. The server checks
 * everything again.
 */
(function () {
    'use strict';

    const category = document.querySelector('select[data-life-select]');
    const life = document.getElementById('usefulLifeMonths');
    if (category && life && !category.disabled) {
        category.addEventListener('change', () => {
            const option = category.options[category.selectedIndex];
            if (option && option.dataset.life && !life.readOnly) life.value = option.dataset.life;
        });
    }

    function toggleProceeds() {
        const proceeds = document.querySelector('input[data-proceeds]');
        if (!proceeds) return;
        const paid = Number(proceeds.value) > 0;
        document.querySelectorAll('[data-paid-only]').forEach(el => { el.hidden = !paid; });
        const method = document.querySelector('select[data-asset-method]');
        const ref = document.querySelector('[data-asset-reference]');
        if (ref) ref.hidden = !paid || !method || method.value === 'CASH_VAULT';
    }

    document.addEventListener('input', (e) => {
        if (e.target.matches('input[data-proceeds]')) toggleProceeds();
    });
    document.addEventListener('change', (e) => {
        if (e.target.matches('select[data-asset-method]')) toggleProceeds();
    });
    toggleProceeds();
})();
