/* Dashboard activity chart (vendored Chart.js). Data comes from window.dashboardData (dashboard.html). */
document.addEventListener('DOMContentLoaded', function () {
    const data = window.dashboardData;
    const canvas = document.getElementById('activity-chart');
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

    let range = 7;
    const lastDays = function (values) { return values.slice(values.length - range); };

    const chart = new Chart(canvas, {
        type: 'bar',
        data: {
            labels: [],
            datasets: [
                { label: data.labels.actions, data: [], borderRadius: 6, maxBarThickness: 28 },
                { label: data.labels.changes, data: [], borderRadius: 6, maxBarThickness: 28 }
            ]
        },
        options: {
            responsive: true,
            maintainAspectRatio: false,
            datasets: { bar: { categoryPercentage: 0.62, barPercentage: 0.86 } },
            interaction: { mode: 'index', intersect: false },
            plugins: {
                legend: {
                    position: 'bottom',
                    labels: { usePointStyle: true, pointStyle: 'circle', boxWidth: 8, boxHeight: 8, padding: 18 }
                }
            },
            scales: {
                x: { grid: { display: false }, border: { display: false } },
                y: { beginAtZero: true, ticks: { precision: 0 }, border: { display: false } }
            }
        }
    });

    // Colours come from the theme tokens so the chart follows light/dark mode.
    const applyTheme = function () {
        const text = cssVar('--gray-500');
        chart.data.datasets[0].backgroundColor = cssVar('--primary');
        chart.data.datasets[1].backgroundColor = cssVar('--success');
        chart.options.plugins.legend.labels.color = cssVar('--gray-600');
        chart.options.scales.x.ticks.color = text;
        chart.options.scales.y.ticks.color = text;
        chart.options.scales.y.grid.color = cssVar('--gray-200');
    };

    const sub = document.getElementById('activity-chart-sub');
    const render = function () {
        chart.data.labels = lastDays(data.days).map(formatDay);
        chart.data.datasets[0].data = lastDays(data.actions);
        chart.data.datasets[1].data = lastDays(data.changes);
        if (sub) sub.textContent = sub.getAttribute('data-sub' + range) || sub.textContent;
        chart.update();
    };

    document.querySelectorAll('.segmented [data-range]').forEach(function (btn) {
        btn.addEventListener('click', function () {
            range = parseInt(btn.getAttribute('data-range'), 10);
            document.querySelectorAll('.segmented [data-range]').forEach(function (b) {
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
