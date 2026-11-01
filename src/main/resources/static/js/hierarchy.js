document.addEventListener('DOMContentLoaded', function () {
    const renameForm = document.getElementById('rename-form');
    const deleteForm = document.getElementById('delete-form');

    function openModal(id) {
        const modal = document.getElementById(id);
        if (modal) modal.classList.add('open');
    }

    function rowData(button) {
        const row = button.closest('tr');
        return row ? row.dataset : null;
    }

    if (renameForm) {
        const tier = document.getElementById('rename-tier');
        const id = document.getElementById('rename-id');
        const name = document.getElementById('rename-name');
        const path = document.getElementById('rename-path');

        document.querySelectorAll('.js-hierarchy-rename').forEach(function (button) {
            button.addEventListener('click', function () {
                const data = rowData(button);
                if (!data) return;
                tier.value = data.tier || '';
                id.value = data.id || '';
                name.value = data.name || '';
                path.textContent = data.path || '';
                openModal('rename-modal');
                setTimeout(function () {
                    name.focus();
                    name.select();
                }, 50);
            });
        });
    }

    if (deleteForm) {
        const tier = document.getElementById('delete-tier');
        const id = document.getElementById('delete-id');
        const name = document.getElementById('delete-name');
        const code = document.getElementById('delete-code');
        const descendants = document.getElementById('delete-descendants');
        const confirm = document.getElementById('delete-confirm');
        const submit = document.getElementById('delete-submit');
        let expectedCode = '';

        function updateSubmit() {
            submit.disabled = expectedCode === '' || confirm.value.trim() !== expectedCode;
        }

        document.querySelectorAll('.js-hierarchy-delete').forEach(function (button) {
            button.addEventListener('click', function () {
                const data = rowData(button);
                if (!data) return;
                expectedCode = data.code || '';
                tier.value = data.tier || '';
                id.value = data.id || '';
                name.textContent = data.name || '';
                code.textContent = expectedCode;
                descendants.textContent = data.descendants || '0';
                confirm.value = '';
                updateSubmit();
                openModal('delete-modal');
                setTimeout(function () {
                    confirm.focus();
                }, 50);
            });
        });

        confirm.addEventListener('input', updateSubmit);
        deleteForm.addEventListener('submit', function (event) {
            if (expectedCode === '' || confirm.value.trim() !== expectedCode) {
                event.preventDefault();
                updateSubmit();
            }
        });
    }
});
