/*
 * Recording a vehicle cost: shows the fields the choices need (litres for fuel, the service plan for maintenance work, the
 * reference for a bank or mobile-money payment, the hints of the driver's float and of a cost owed), keeps the trip and the
 * service plan to those of the chosen vehicle, and takes the vehicle of a trip chosen first. Fields hidden are emptied, so
 * nothing stale is sent.
 */
(function () {
    'use strict';
    var form = document.querySelector('.vehicle-cost-form');
    if (!form) return;
    var vehicle = form.querySelector('#vehicleId');
    var trip = form.querySelector('#tripId');
    var category = form.querySelector('#category');
    var payment = form.querySelector('#payment');
    var plan = form.querySelector('#servicePlanId');

    function selected(select) {
        return select.options[select.selectedIndex] || null;
    }

    function matches(el) {
        var wanted = el.getAttribute('data-show-for').split(' ');
        var cat = category.value;
        var service = selected(category) && selected(category).getAttribute('data-service') === 'true';
        return wanted.some(function (w) {
            return w === cat || w === payment.value || (w === 'service' && service);
        });
    }

    function toggle() {
        form.querySelectorAll('[data-show-for]').forEach(function (el) {
            var show = matches(el);
            el.hidden = !show;
            if (!show) {
                el.querySelectorAll('input, select').forEach(function (field) { field.value = ''; });
            }
        });
    }

    /** Only the options of the chosen vehicle (all of them while none is chosen). */
    function filter(select) {
        if (!select) return;
        var v = vehicle.value;
        Array.prototype.forEach.call(select.options, function (o) {
            var of = o.getAttribute('data-vehicle');
            o.hidden = !!(of && v && of !== v);
        });
        var current = selected(select);
        if (current && current.hidden) select.value = '';
    }

    vehicle.addEventListener('change', function () {
        filter(trip);
        filter(plan);
    });
    trip.addEventListener('change', function () {
        var of = selected(trip) && selected(trip).getAttribute('data-vehicle');
        if (of && vehicle.value !== of) {
            vehicle.value = of;
            filter(plan);
        }
    });
    category.addEventListener('change', toggle);
    payment.addEventListener('change', toggle);

    filter(trip);
    filter(plan);
    toggle();
})();
