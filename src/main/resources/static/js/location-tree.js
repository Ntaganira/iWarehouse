/*
 * iWarehouse - location tree (MD-02).
 * Each row knows its parent (data-parent); a row's toggle hides or shows everything under it.
 * Expanding a row shows its children as they were left (a collapsed child stays collapsed).
 */
(function () {
    'use strict';

    const table = document.querySelector('table.location-tree');
    if (!table) return;

    const rows = Array.from(table.querySelectorAll('tbody tr[data-id]'));
    const children = new Map();
    rows.forEach(row => {
        const parent = row.dataset.parent;
        if (!parent) return;
        if (!children.has(parent)) children.set(parent, []);
        children.get(parent).push(row);
    });

    const isOpen = row => {
        const toggle = row.querySelector('.tree-toggle');
        return !toggle || toggle.getAttribute('aria-expanded') === 'true';
    };

    function showChildren(row, visible) {
        (children.get(row.dataset.id) || []).forEach(child => {
            child.hidden = !visible;
            showChildren(child, visible && isOpen(child));
        });
    }

    function setOpen(row, open) {
        const toggle = row.querySelector('.tree-toggle');
        if (!toggle) return;
        toggle.setAttribute('aria-expanded', String(open));
        row.classList.toggle('is-collapsed', !open);
        showChildren(row, open && !row.hidden);
    }

    table.addEventListener('click', e => {
        const toggle = e.target.closest('.tree-toggle');
        if (!toggle) return;
        const row = toggle.closest('tr');
        setOpen(row, !isOpen(row));
    });

    document.querySelectorAll('[data-tree]').forEach(button => button.addEventListener('click', () => {
        const open = button.dataset.tree === 'expand';
        rows.forEach(row => {
            const toggle = row.querySelector('.tree-toggle');
            if (toggle) {
                toggle.setAttribute('aria-expanded', String(open));
                row.classList.toggle('is-collapsed', !open);
            }
            // Roots (no parent row on the page) stay visible
            row.hidden = !open && !!row.dataset.parent && !!table.querySelector('tr[data-id="' + row.dataset.parent + '"]');
        });
    }));
})();
