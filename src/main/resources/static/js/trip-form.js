/*
 * iWarehouse - plan a trip (FLT-05): choosing a driver chooses their usual vehicle, unless a vehicle was chosen by hand.
 * The server checks the papers and the vehicle again.
 */
(function () {
    'use strict';

    const form = document.querySelector('form.trip-form');
    if (!form) return;
    const driver = form.querySelector('select[name="driverId"]');
    const vehicle = form.querySelector('select[name="vehicleId"]');
    let chosenByHand = vehicle.value !== '';

    vehicle.addEventListener('change', function () { chosenByHand = vehicle.value !== ''; });
    driver.addEventListener('change', function () {
        const usual = driver.selectedOptions[0] ? driver.selectedOptions[0].dataset.vehicle : '';
        if (usual && !chosenByHand && vehicle.querySelector('option[value="' + usual + '"]')) {
            vehicle.value = usual;
        }
    });
})();
