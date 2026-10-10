/* Owner dashboard: net sales of the last 7 or 30 days (vendored Chart.js). Data comes from window.salesChartData (dashboard.html). */
document.addEventListener('DOMContentLoaded', function () {
    const data = window.salesChartData;
    const canvas = document.getElementById('sales-chart');
    if (!data || !canvas || typeof Chart === 'undefined') return;

    const cssVar = function (name) {
        return getComputedStyle(document.documentElement).getPropertyValue(name).trim();
    };
    const lang = document.documentElement.lang || 'en';
    const formatDay = function (iso) {
        const d = new Date(iso + 'T00:00:00');
        try {
            return d.toLocaleDateString(lang, { month: 'short', day: 'numeric' });
        } catch (e) {
            return iso.slice(5);
        }
    };
    const money = function (v) {
        return Math.round(v).toString().replace(/\B(?=(\d{3})+(?!\d))/g, ',');
    };

    let range = 7;
    const lastDays = function (values) { return values.slice(values.length - range); };

    const chart = new Chart(canvas, {
        type: 'bar',
        data: { labels: [], datasets: [{ label: data.label, data: [], borderRadius: 6, maxBarThickness: 28 }] },
        options: {
            responsive: true,
            maintainAspectRatio: false,
            plugins: {
                legend: { display: false },
                tooltip: { callbacks: { label: function (ctx) { return money(ctx.parsed.y) + ' RWF'; } } }
            },
            scales: {
                x: { grid: { display: false }, border: { display: false } },
                y: { beginAtZero: true, border: { display: false }, ticks: { callback: function (v) { return money(v); } } }
            }
        }
    });

    // Colours come from the theme tokens so the chart follows light/dark mode.
    const applyTheme = function () {
        const text = cssVar('--gray-500');
        chart.data.datasets[0].backgroundColor = cssVar('--success');
        chart.options.scales.x.ticks.color = text;
        chart.options.scales.y.ticks.color = text;
        chart.options.scales.y.grid.color = cssVar('--gray-200');
    };

    const sub = document.getElementById('sales-chart-sub');
    const render = function () {
        chart.data.labels = lastDays(data.days).map(formatDay);
        chart.data.datasets[0].data = lastDays(data.net).map(Number);
        if (sub) sub.textContent = sub.getAttribute('data-sub' + range) || sub.textContent;
        chart.update();
    };

    document.querySelectorAll('[data-sales-range]').forEach(function (btn) {
        btn.addEventListener('click', function () {
            range = parseInt(btn.getAttribute('data-sales-range'), 10);
            document.querySelectorAll('[data-sales-range]').forEach(function (b) {
                b.classList.toggle('active', b === btn);
                b.setAttribute('aria-pressed', b === btn ? 'true' : 'false');
            });
            render();
        });
    });

    new MutationObserver(function () { applyTheme(); chart.update(); })
        .observe(document.documentElement, { attributes: true, attributeFilter: ['data-theme'] });

    applyTheme();
    render();
});
