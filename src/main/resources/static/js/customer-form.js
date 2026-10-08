/*
 * iWarehouse - customer form (MD-04, POS-05).
 * Walk-in customers pay at the counter: when the type is walk-in, credit limit and payment terms are
 * locked at 0 (read-only, so they are still sent and validated). The server applies the same rule.
 */
(function () {
    'use strict';

    const form = document.getElementById('customer-form');
    const type = form && form.querySelector('select[name="type"]');
    const note = form && form.querySelector('.walk-in-note');
    if (!type || !note) return; // terms not editable by this user

    const fields = Array.from(form.querySelectorAll('.credit-field input'));

    function render() {
        const walkIn = type.value === 'WALK_IN';
        note.hidden = !walkIn;
        fields.forEach(input => {
            input.readOnly = walkIn;
            input.closest('.credit-field').classList.toggle('is-locked', walkIn);
            if (walkIn) input.value = '0';
        });
    }

    type.addEventListener('change', render);
    render();
})();
