/*
 * The damage report form (F-07 C): only the fields of who pays the loss back: a customer, a supplier or a driver by
 * record, an employee or an insurer by name, and the amount; nothing when nobody does.
 */
(function () {
    'use strict';
    const select = document.querySelector('[data-recover-from]');
    if (!select) return;
    function show() {
        const who = select.value;
        document.querySelectorAll('[data-recover]').forEach((group) => {
            group.hidden = !group.dataset.recover.split(' ').includes(who);
        });
    }
    select.addEventListener('change', show);
    show();
})();
