/*
 * iWarehouse - the cash counted note by note (F-13): each row's amount and the total as the cashier types the counts.
 * The server works the total out again from the counts; this only shows it.
 */
document.addEventListener('DOMContentLoaded', function () {
    const table = document.querySelector('.cash-count');
    if (!table) return;
    const format = function (n) {
        return n.toLocaleString('en-US');
    };
    const update = function () {
        let total = 0;
        table.querySelectorAll('input[data-value]').forEach(function (input) {
            const pieces = parseInt(input.value, 10);
            const amount = isNaN(pieces) || pieces < 0 ? 0 : pieces * parseInt(input.dataset.value, 10);
            total += amount;
            const cell = input.closest('tr').querySelector('.count-amount');
            if (cell) cell.textContent = format(amount);
        });
        const out = table.querySelector('.count-total');
        if (out) out.textContent = format(total);
    };
    table.addEventListener('input', update);
    update();
});
