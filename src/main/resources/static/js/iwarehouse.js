// WeCare Dashboard JS

document.addEventListener('DOMContentLoaded', function () {
    // Toasts (flash messages & JS-triggered notices)
    const dismissToast = function (toast) {
        if (!toast || toast.classList.contains('is-leaving')) return;
        toast.classList.add('is-leaving');
        setTimeout(() => toast.remove(), 300);
    };
    document.querySelectorAll('[data-toast]').forEach(toast => {
        const close = toast.querySelector('[data-toast-close]');
        if (close) close.addEventListener('click', function () { dismissToast(toast); });
        setTimeout(() => dismissToast(toast), 5000);
    });

    // Programmatic toasts: window.showToast(message, 'success'|'error'|'warning'|'info')
    const toastIconPaths = {
        success: '<polyline points="20 6 9 17 4 12"></polyline>',
        danger: '<circle cx="12" cy="12" r="10"></circle><line x1="15" y1="9" x2="9" y2="15"></line><line x1="9" y1="9" x2="15" y2="15"></line>',
        warning: '<path d="M10.29 3.86L1.82 18a2 2 0 0 0 1.71 3h16.94a2 2 0 0 0 1.71-3L13.71 3.86a2 2 0 0 0-3.42 0z"></path><line x1="12" y1="9" x2="12" y2="13"></line><line x1="12" y1="17" x2="12.01" y2="17"></line>',
        info: '<circle cx="12" cy="12" r="10"></circle><line x1="12" y1="16" x2="12" y2="12"></line><line x1="12" y1="8" x2="12.01" y2="8"></line>'
    };
    window.showToast = function (message, type) {
        type = type || 'info';
        const container = document.getElementById('toast-container');
        if (!container) return;
        const toast = document.createElement('div');
        toast.className = 'toast toast-' + type;
        toast.setAttribute('data-toast', '');
        toast.setAttribute('role', type === 'danger' ? 'alert' : 'status');
        toast.innerHTML =
            '<span class="toast-icon"><svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2.5" stroke-linecap="round" stroke-linejoin="round"></svg></span>' +
            '<div class="toast-body"><div class="toast-title"></div><div class="toast-msg"></div></div>' +
            '<button type="button" class="toast-close" data-toast-close aria-label="Close">&times;</button>';
        const icon = toast.querySelector('.toast-icon svg');
        if (icon) icon.innerHTML = toastIconPaths[type] || toastIconPaths.info;
        const titles = (window.appMessages && window.appMessages.toast)
            || { success: 'Success', error: 'Error', warning: 'Warning', info: 'Info' };
        toast.querySelector('.toast-title').textContent = titles[type] || titles.info;
        toast.querySelector('.toast-msg').textContent = message || '';
        const close = toast.querySelector('[data-toast-close]');
        if (close) close.addEventListener('click', function () { dismissToast(toast); });
        container.appendChild(toast);
        setTimeout(function () { dismissToast(toast); }, 5000);
    };

    // Dismissible error summary
    document.querySelectorAll('[data-error-close]').forEach(btn => {
        btn.addEventListener('click', function () {
            const box = this.closest('.error-summary');
            if (!box) return;
            box.classList.add('is-closing');
            setTimeout(() => box.remove(), 260);
        });
    });

    // Custom confirm dialog (replaces native confirm())
    const appMsg = (key, fallback) =>
        (window.appMessages && window.appMessages[key]) || fallback;

    const confirmDialog = (function () {
        let overlay = null;
        let onConfirm = null;

        function ensure() {
            if (overlay) return overlay;
            overlay = document.createElement('div');
            overlay.className = 'modal-overlay';
            overlay.innerHTML =
                '<div class="modal confirm-modal" role="dialog" aria-modal="true">' +
                '  <div class="modal-header">' +
                '    <h3 class="confirm-title"></h3>' +
                '    <button type="button" class="modal-close confirm-cancel" aria-label="Close">&times;</button>' +
                '  </div>' +
                '  <div class="modal-body">' +
                '    <div class="confirm-icon">' +
                '      <svg width="22" height="22" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round">' +
                '        <path d="m21.73 18-8-14a2 2 0 0 0-3.48 0l-8 14A2 2 0 0 0 4 21h16a2 2 0 0 0 1.73-3Z"></path>' +
                '        <path d="M12 9v4"></path><path d="M12 17h.01"></path>' +
                '      </svg>' +
                '    </div>' +
                '    <p class="confirm-message"></p>' +
                '  </div>' +
                '  <div class="modal-footer">' +
                '    <button type="button" class="btn btn-outline confirm-cancel"></button>' +
                '    <button type="button" class="btn confirm-ok"></button>' +
                '  </div>' +
                '</div>';
            document.body.appendChild(overlay);

            const close = function () {
                overlay.classList.remove('open');
                onConfirm = null;
            };
            overlay.querySelectorAll('.confirm-cancel').forEach(btn => {
                btn.addEventListener('click', close);
            });
            overlay.querySelector('.confirm-ok').addEventListener('click', function () {
                const cb = onConfirm;
                close();
                if (cb) cb();
            });
            overlay.addEventListener('click', function (e) {
                if (e.target === overlay) close();
            });
            document.addEventListener('keydown', function (e) {
                if (e.key === 'Escape' && overlay.classList.contains('open')) close();
            });
            return overlay;
        }

        return {
            open: function (opts) {
                const el = ensure();
                el.querySelector('.confirm-title').textContent =
                    opts.title || appMsg('confirmTitle', 'Are you sure?');
                el.querySelector('.confirm-message').textContent =
                    opts.message || appMsg('confirmDelete', 'Are you sure?');
                const cancelBtns = el.querySelectorAll('.confirm-cancel');
                cancelBtns[cancelBtns.length - 1].textContent =
                    opts.cancelText || appMsg('confirmCancel', 'Not now');
                const ok = el.querySelector('.confirm-ok');
                ok.textContent = opts.okText || appMsg('confirmOk', 'Yes, Continue');
                // Keep confirm-ok: open() looks the button up by it on every call.
                ok.className = 'btn confirm-ok ' + (opts.danger ? 'btn-danger' : 'btn-primary');
                el.dataset.danger = opts.danger ? 'true' : 'false';
                onConfirm = opts.onConfirm;
                el.classList.add('open');
            },
            close: function () {
                if (overlay) overlay.classList.remove('open');
                onConfirm = null;
            }
        };
    })();

    // Deletes (plain confirm message)
    const confirmDeleteMsg = appMsg('confirmDelete', 'Are you sure you want to delete this item?');
    document.querySelectorAll('.confirm-delete').forEach(link => {
        link.addEventListener('click', function (e) {
            e.preventDefault();
            const href = this.getAttribute('href');
            confirmDialog.open({
                message: this.getAttribute('data-confirm') || confirmDeleteMsg,
                danger: true,
                onConfirm: function () { showPageSkeleton(); window.location.href = href; }
            });
        });
    });

    // Actions with a custom message (data-confirm)
    document.querySelectorAll('.confirm-action').forEach(link => {
        link.addEventListener('click', function (e) {
            e.preventDefault();
            const href = this.getAttribute('href');
            const msg = this.getAttribute('data-confirm') || confirmDeleteMsg;
            const danger = this.classList.contains('delete');
            confirmDialog.open({
                message: msg,
                danger: danger,
                onConfirm: function () { showPageSkeleton(); window.location.href = href; }
            });
        });
    });

    // POST actions with a confirmation (data-confirm): <form class="confirm-submit [danger]">
    document.querySelectorAll('form.confirm-submit').forEach(form => {
        form.addEventListener('submit', function (e) {
            if (form.dataset.confirmed === 'true') return;
            e.preventDefault();
            confirmDialog.open({
                message: form.getAttribute('data-confirm') || confirmDeleteMsg,
                danger: form.classList.contains('danger'),
                onConfirm: function () {
                    form.dataset.confirmed = 'true';
                    showPageSkeleton();
                    form.submit(); // does not fire 'submit' again
                }
            });
        });
    });

    // Modals
    const modalOpen = function (id) {
        const overlay = document.getElementById(id);
        if (overlay) overlay.classList.add('open');
    };
    const modalClose = function (id) {
        const overlay = document.getElementById(id);
        if (overlay) overlay.classList.remove('open');
    };
    document.querySelectorAll('[data-modal-close]').forEach(btn => {
        btn.addEventListener('click', function () {
            modalClose(this.getAttribute('data-modal-close'));
        });
    });
    document.querySelectorAll('.modal-overlay').forEach(overlay => {
        overlay.addEventListener('click', function (e) {
            if (e.target === overlay) overlay.classList.remove('open');
        });
    });

    // Any modal opened by a button: <button data-modal-open="cancel-modal"> (focuses its first field)
    document.querySelectorAll('[data-modal-open]').forEach(btn => {
        btn.addEventListener('click', function () {
            const id = this.getAttribute('data-modal-open');
            modalOpen(id);
            const overlay = document.getElementById(id);
            const field = overlay && overlay.querySelector('input:not([type=hidden]), textarea, select');
            if (field) setTimeout(() => field.focus(), 50);
        });
    });

    // Reset password modal
    document.querySelectorAll('.open-reset-modal').forEach(btn => {
        btn.addEventListener('click', function () {
            const id = this.getAttribute('data-id');
            const name = this.getAttribute('data-name');
            const nameEl = document.getElementById('reset-user-name');
            const form = document.getElementById('reset-form');
            const pw = document.getElementById('reset-password');
            if (nameEl) nameEl.textContent = name;
            if (form) form.action = this.getAttribute('data-action') || ('/users/' + id + '/reset-password');
            if (pw) pw.value = '';
            modalOpen('reset-modal');
        });
    });

    // Select-all checkboxes in permission/page trees
    document.querySelectorAll('.perm-select-all').forEach(selectAll => {
        const updateSelectAll = function () {
            const group = selectAll.closest('.perm-group');
            const checks = group ? group.querySelectorAll('.perm-check') : [];
            let checked = 0;
            checks.forEach(c => { if (c.checked) checked++; });
            selectAll.checked = checks.length > 0 && checked === checks.length;
            selectAll.indeterminate = checked > 0 && checked < checks.length;
        };
        selectAll.addEventListener('change', function () {
            const group = selectAll.closest('.perm-group');
            const checks = group ? group.querySelectorAll('.perm-check') : [];
            checks.forEach(c => { c.checked = selectAll.checked; });
        });
        const group = selectAll.closest('.perm-group');
        if (group) {
            group.querySelectorAll('.perm-check').forEach(c => {
                c.addEventListener('change', updateSelectAll);
            });
        }
        updateSelectAll();
    });

    // Tabs
    document.querySelectorAll('.tabs').forEach(tabs => {
        tabs.querySelectorAll('.tab').forEach(tab => {
            tab.addEventListener('click', function () {
                tabs.querySelectorAll('.tab').forEach(t => t.classList.remove('active'));
                tab.classList.add('active');
                const target = document.getElementById(tab.getAttribute('data-tab'));
                const body = tab.closest('.section');
                if (body) body.querySelectorAll('.tab-content').forEach(c => c.classList.remove('active'));
                if (target) target.classList.add('active');
            });
        });
    });

    // Sidebar toggle
    const sidebarToggle = document.getElementById('sidebar-toggle');
    if (sidebarToggle) {
        sidebarToggle.addEventListener('click', function () {
            const collapsed = document.documentElement.classList.toggle('sidebar-collapsed');
            try { localStorage.setItem('iwarehouse-sidebar', collapsed ? 'collapsed' : 'expanded'); } catch (e) {}
        });
    }

    // Phones and tablets (768px and less): the sidebar is a drawer opened from the header
    const narrow = window.matchMedia('(max-width: 768px)');
    const navToggle = document.getElementById('nav-toggle');
    const navClose = document.getElementById('nav-close');
    const navBackdrop = document.getElementById('nav-backdrop');
    const mainContent = document.querySelector('.main-content');
    const setNav = function (open) {
        const root = document.documentElement;
        if (root.classList.contains('nav-open') === open) return;
        root.classList.toggle('nav-open', open);
        if (navToggle) navToggle.setAttribute('aria-expanded', String(open));
        if (navBackdrop) navBackdrop.hidden = !open;
        if (mainContent) mainContent.inert = open;
        if (open && navClose) navClose.focus();
        else if (!open && navToggle && narrow.matches) navToggle.focus();
    };
    if (navToggle) navToggle.addEventListener('click', function () { setNav(true); });
    if (navClose) navClose.addEventListener('click', function () { setNav(false); });
    if (navBackdrop) navBackdrop.addEventListener('click', function () { setNav(false); });
    document.addEventListener('keydown', function (e) {
        if (e.key === 'Escape' && document.documentElement.classList.contains('nav-open')) setNav(false);
    });
    document.querySelectorAll('.sidebar-menu a').forEach(function (a) {
        a.addEventListener('click', function () { if (narrow.matches) setNav(false); });
    });
    narrow.addEventListener('change', function (e) {
        if (!e.matches) setNav(false);
        // The narrow rail is a desktop choice: drop it in the drawer, bring it back on a wide screen
        let collapsed = false;
        try { collapsed = localStorage.getItem('iwarehouse-sidebar') === 'collapsed'; } catch (err) {}
        document.documentElement.classList.toggle('sidebar-collapsed', collapsed && !e.matches);
    });

    // Tables become cards on phones: each cell is labelled with its column header (CSS shows it
    // at 640px and less). Rows added later (line forms) are labelled as they appear.
    // Opt out with data-cards="off" on the table.
    const labelRows = function (table) {
        const head = table.tHead && table.tHead.rows[table.tHead.rows.length - 1];
        if (!head) return;
        const labels = [];
        Array.from(head.cells).forEach(function (th) {
            // A header only screen readers see (the actions column) labels nothing on a card
            const shown = th.cloneNode(true);
            shown.querySelectorAll('.sr-only').forEach(function (s) { s.remove(); });
            const text = shown.textContent.replace(/\s+/g, ' ').trim();
            for (let i = 0; i < th.colSpan; i++) labels.push(text);
        });
        const rows = [];
        Array.from(table.tBodies).forEach(function (body) { rows.push.apply(rows, body.rows); });
        if (table.tFoot) rows.push.apply(rows, table.tFoot.rows);
        rows.forEach(function (row) {
            let col = 0;
            Array.from(row.cells).forEach(function (cell) {
                const label = cell.colSpan === 1 ? labels[col] : '';
                if (label && !cell.hasAttribute('data-label')) cell.setAttribute('data-label', label);
                // A cell holding only hidden inputs (a line's id) has nothing to show on a card
                const hiddenOnly = cell.children.length > 0 && cell.textContent.trim() === ''
                    && Array.from(cell.children).every(function (c) { return c.matches('input[type=hidden]'); });
                cell.classList.toggle('cell-hidden-only', hiddenOnly);
                col += cell.colSpan;
            });
        });
    };
    document.querySelectorAll('.page-content table').forEach(function (table) {
        if (table.closest('[data-cards="off"]') || !table.tHead) return;
        labelRows(table);
        table.classList.add('as-cards');
        Array.from(table.tBodies).forEach(function (body) {
            new MutationObserver(function () { labelRows(table); }).observe(body, { childList: true });
        });
    });

    // Notifications bell dropdown
    const notifBtn = document.getElementById('notif-btn');
    const notifPanel = document.getElementById('notif-panel');
    if (notifBtn && notifPanel) {
        notifBtn.addEventListener('click', function (e) {
            e.stopPropagation();
            const open = notifPanel.classList.toggle('open');
            notifBtn.classList.toggle('active', open);
        });
        document.addEventListener('click', function (e) {
            if (!notifPanel.contains(e.target) && e.target !== notifBtn) {
                notifPanel.classList.remove('open');
                notifBtn.classList.remove('active');
            }
        });
        notifPanel.addEventListener('click', function (e) {
            e.stopPropagation();
        });
    }

    // Language dropdown
    const langBtn = document.getElementById('lang-btn');
    const langMenu = document.getElementById('lang-menu');
    if (langBtn && langMenu) {
        langBtn.addEventListener('click', function (e) {
            e.stopPropagation();
            const open = langMenu.classList.toggle('open');
            langBtn.classList.toggle('active', open);
            langBtn.setAttribute('aria-expanded', open ? 'true' : 'false');
        });
        document.addEventListener('click', function (e) {
            if (!langMenu.contains(e.target) && e.target !== langBtn) {
                langMenu.classList.remove('open');
                langBtn.classList.remove('active');
                langBtn.setAttribute('aria-expanded', 'false');
            }
        });
        langMenu.addEventListener('click', function (e) {
            e.stopPropagation();
        });
    }

    // Theme toggle
    const themeToggle = document.getElementById('theme-toggle');
    if (themeToggle) {
        const themeLabels = (window.appMessages && window.appMessages.theme)
            || { light: 'Light Mode', dark: 'Dark Mode' };
        const syncThemeUI = function () {
            const isDark = document.documentElement.getAttribute('data-theme') === 'dark';
            themeToggle.setAttribute('aria-label', isDark ? themeLabels.light : themeLabels.dark);
        };
        themeToggle.addEventListener('click', function () {
            const isDark = document.documentElement.getAttribute('data-theme') === 'dark';
            const next = isDark ? 'light' : 'dark';
            document.documentElement.setAttribute('data-theme', next);
            localStorage.setItem('iwarehouse-theme', next);
            syncThemeUI();
        });
        syncThemeUI();
    }

    // Live table search + status filter
    const toolbar = document.querySelector('.table-toolbar');
    const searchInput = toolbar ? toolbar.querySelector('.table-search') : null;
    const filterSelect = toolbar ? toolbar.querySelector('.toolbar-filter') : null;
    const table = toolbar ? toolbar.closest('.section').querySelector('table') : null;

    if (table) {
        const rows = Array.from(table.querySelectorAll('tbody tr'));
        const emptyState = rows.find(r => r.querySelector('.empty-state'));

        function applyFilters() {
            const term = (searchInput ? searchInput.value : '').toLowerCase().trim();
            const status = filterSelect ? filterSelect.value.toLowerCase() : '';
            let visible = 0;

            rows.forEach(row => {
                if (emptyState && row === emptyState) return;
                const text = row.textContent.toLowerCase();
                const rowStatus = row.dataset.status ? row.dataset.status.toLowerCase() : '';
                const matchTerm = !term || text.includes(term);
                const matchStatus = !status || rowStatus === status;
                const show = matchTerm && matchStatus;
                row.style.display = show ? '' : 'none';
                if (show) visible++;
            });

            if (emptyState) {
                emptyState.style.display = visible === 0 ? '' : 'none';
            }
        }

        if (searchInput) searchInput.addEventListener('input', applyFilters);
        if (filterSelect) filterSelect.addEventListener('change', applyFilters);
    }

    // Choices.js enhanced multi-selects
    if (typeof Choices !== 'undefined') {
        document.querySelectorAll('select[data-multiselect]').forEach(sel => {
            // No addItems: false here: Choices disables the whole select when it is false.
            new Choices(sel, {
                removeItemButton: true,
                allowHTML: false,
                searchEnabled: true,
                placeholderValue: sel.dataset.placeholder || null,
                searchPlaceholderValue: sel.dataset.placeholder || ' ',
                itemSelectText: ' ',
                noChoicesText: sel.dataset.noChoices || ' ',
                noResultsText: sel.dataset.noResults || ' ',
                classNames: {
                    containerOuter: 'choices multiselect-choices'
                }
            });
        });

        // Long single selects (countries): type to filter
        document.querySelectorAll('select[data-searchable]').forEach(sel => {
            // Opening the list puts the cursor in its search box, so typing filters at once. The box is
            // still hidden for a moment when Choices opens the list, so focus is retried until it takes.
            sel.addEventListener('showDropdown', () => {
                const search = sel.closest('.choices').querySelector('input.choices__input--cloned');
                if (!search) return;
                let tries = 0;
                const focus = () => {
                    search.focus();
                    if (document.activeElement !== search && ++tries < 10) setTimeout(focus, 30);
                };
                setTimeout(focus, 0);
            });
            new Choices(sel, {
                allowHTML: false,
                searchEnabled: true,
                shouldSort: false,
                itemSelectText: '',
                searchPlaceholderValue: sel.dataset.searchPlaceholder || '',
                noResultsText: sel.dataset.noResults || ' ',
                classNames: {
                    containerOuter: 'choices multiselect-choices single-choices'
                }
            });
        });
    }

    // Page-load skeleton on navigation
    window.showPageSkeleton = function () {
        const el = document.getElementById('page-skeleton');
        if (el) el.hidden = false;
    };

    const isPlainNavigation = function (a) {
        if (!a || !a.href) return false;
        if (a.target && a.target !== '_self') return false;
        if (a.hasAttribute('download')) return false;
        if (a.getAttribute('rel') === 'external') return false;
        if (a.closest('.confirm-delete, .confirm-action')) return false;
        if (a.getAttribute('href').charAt(0) === '#') return false;
        if (a.getAttribute('href').indexOf('/logout') !== -1) return false;
        return true;
    };

    document.addEventListener('click', function (e) {
        if (e.defaultPrevented || e.button !== 0 || e.metaKey || e.ctrlKey || e.shiftKey || e.altKey) return;
        const a = e.target.closest('a');
        if (!a || !isPlainNavigation(a)) return;
        showPageSkeleton();
    });

    document.addEventListener('submit', function (e) {
        if (e.defaultPrevented) return;
        const form = e.target;
        if (form && form.action) showPageSkeleton();
    });

    window.addEventListener('pageshow', function () {
        const el = document.getElementById('page-skeleton');
        if (el) el.hidden = true;
    });
});
