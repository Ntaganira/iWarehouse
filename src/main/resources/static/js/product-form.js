/*
 * iWarehouse - glass product form (MD-01).
 * While a new product is typed, shows its name ("Tinted Bronze 6 mm"), its weight per m² and the code
 * it will get if the code field is left empty. The code rule mirrors GlassProducts.suggestCode;
 * the server decides the real code.
 */
(function () {
    'use strict';

    const form = document.getElementById('product-form');
    // The edit form only has a hidden glassType input (same id): nothing to preview there
    const type = form && form.querySelector('select[name="glassType"]');
    if (!type) return;

    const variant = document.getElementById('variant');
    const thickness = document.getElementById('thicknessMm');
    const code = document.getElementById('code');
    const preview = document.getElementById('product-preview');
    const weight = document.getElementById('product-weight');
    const codeLine = document.getElementById('product-code');
    const density = parseFloat(form.dataset.density) || 2.5;

    const thicknessLabel = v => {
        const n = parseFloat(v);
        return n > 0 ? String(Math.round(n * 100) / 100) : '';
    };
    const normalizeVariant = v => {
        const s = (v || '').trim().replace(/\s+/g, ' ');
        return s ? s.charAt(0).toUpperCase() + s.slice(1) : '';
    };

    function render() {
        const option = type.options[type.selectedIndex];
        const prefix = option && option.dataset.prefix;
        const v = normalizeVariant(variant.value);
        const t = thicknessLabel(thickness.value);

        const name = prefix ? [option.textContent.trim(), v, t ? t + ' mm' : ''].filter(Boolean).join(' ') : '';
        preview.textContent = name || preview.dataset.empty;
        preview.classList.toggle('is-empty', !name);
        const kg = t ? Math.round((parseFloat(t) * density + Number.EPSILON) * 100) / 100 : null;
        weight.textContent = kg === null ? '' : kg.toFixed(2) + ' kg/m²';

        let suggestion = '';
        if (prefix) {
            const part = v.toUpperCase().replace(/[^A-Z0-9]/g, '').slice(0, 8);
            suggestion = prefix + (part ? '-' + part : '') + (t ? '-' + t : '');
        }
        code.placeholder = suggestion;
        // The line tells which code a blank field gives; a typed code replaces it
        codeLine.textContent = suggestion && !code.value.trim() ? codeLine.dataset.label + ' ' + suggestion : '';
    }

    form.addEventListener('input', render);
    form.addEventListener('change', render);
    render();
})();
