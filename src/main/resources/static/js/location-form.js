(function () {
    'use strict';

    var LEVELS = ['PROVINCE', 'DISTRICT', 'SECTOR', 'CELL', 'VILLAGE'];

    document.addEventListener('DOMContentLoaded', function () {
        var form = document.querySelector('form[data-areas-url]');
        if (!form) {
            return;
        }
        var tier = document.getElementById('tier');
        if (!tier) {
            return;
        }
        var groups = LEVELS.map(function (level) {
            return form.querySelector('[data-area-level="' + level + '"]');
        });
        var selects = groups.map(function (group) {
            return group ? group.querySelector('[data-area-select]') : null;
        });
        var errorBox = document.getElementById('locationAreaError');

        selects.forEach(function (select) {
            if (select && !select.dataset.placeholder) {
                select.dataset.placeholder = select.options.length ? select.options[0].textContent : '';
            }
        });

        function visibleCount() {
            var index = LEVELS.indexOf(tier.value);
            return index < 0 ? 0 : index + 1;
        }

        function applyVisibility() {
            var count = visibleCount();
            groups.forEach(function (group, index) {
                if (group) {
                    group.hidden = index >= count;
                }
            });
        }

        function abort(select) {
            if (select && select._areaRequest) {
                select._areaRequest.abort();
                select._areaRequest = null;
            }
        }

        function resetOptions(select) {
            if (!select) {
                return;
            }
            abort(select);
            var placeholder = new Option(select.dataset.placeholder || '', '');
            select.replaceChildren(placeholder);
            select.value = '';
        }

        function clearLevel(index) {
            var select = selects[index];
            if (!select) {
                return;
            }
            resetOptions(select);
        }

        function clearFrom(index) {
            for (var i = index; i < LEVELS.length; i++) {
                clearLevel(i);
            }
        }

        function showError() {
            if (errorBox) {
                errorBox.hidden = false;
            }
        }

        function hideError() {
            if (errorBox) {
                errorBox.hidden = true;
            }
        }

        function optionsEmpty(select) {
            return !select || select.options.length <= 1;
        }

        async function load(index) {
            var select = selects[index];
            var group = groups[index];
            if (!select || !group || group.hidden) {
                return;
            }
            var parent = index === 0 ? null : selects[index - 1];
            var parentId = parent ? parent.value : '';
            if (index > 0 && !parentId) {
                resetOptions(select);
                return;
            }

            abort(select);
            var controller = new AbortController();
            select._areaRequest = controller;

            var url = new URL(form.dataset.areasUrl, window.location.origin);
            url.searchParams.set('tier', LEVELS[index]);
            if (parentId) {
                url.searchParams.set('parentId', parentId);
            }

            try {
                var response = await fetch(url.toString(), {
                    headers: { 'Accept': 'application/json' },
                    credentials: 'same-origin',
                    signal: controller.signal
                });
                if (!response.ok) {
                    throw new Error('HTTP ' + response.status);
                }
                var options = await response.json();
                if (select._areaRequest !== controller) {
                    return;
                }
                var current = select.value;
                var placeholder = new Option(select.dataset.placeholder || '', '');
                select.replaceChildren(placeholder);
                options.forEach(function (item) {
                    var option = new Option(item.label || (item.code + ' \u00b7 ' + item.name), item.id);
                    select.add(option);
                });
                if (Array.prototype.some.call(select.options, function (option) {
                    return option.value === current;
                })) {
                    select.value = current;
                } else {
                    select.value = '';
                }
                hideError();
            } catch (error) {
                if (error.name !== 'AbortError' && select._areaRequest === controller) {
                    showError();
                }
            } finally {
                if (select._areaRequest === controller) {
                    select._areaRequest = null;
                }
            }
        }

        async function loadMissingVisible() {
            if (optionsEmpty(selects[0])) {
                await load(0);
            }
            var count = visibleCount();
            for (var i = 1; i < count; i++) {
                var parent = selects[i - 1];
                if (parent && parent.value && optionsEmpty(selects[i])) {
                    await load(i);
                }
            }
        }

        tier.addEventListener('change', async function () {
            var count = visibleCount();
            applyVisibility();
            clearFrom(count);
            await loadMissingVisible();
        });

        selects.forEach(function (select, index) {
            if (!select || index === LEVELS.length - 1) {
                return;
            }
            select.addEventListener('change', async function () {
                clearFrom(index + 1);
                if (select.value) {
                    await load(index + 1);
                }
            });
        });

        applyVisibility();
        loadMissingVisible();
    });
})();
