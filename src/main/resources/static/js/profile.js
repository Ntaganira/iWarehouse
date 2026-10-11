/*
 * My profile (/profile): the photo is cut square around its centre and shrunk to 512 px in the browser before it is sent
 * (phone photos are turned upright and a few MB become a few dozen KB); the server squares and redraws it again (Avatars).
 * The save button shows once a photo is chosen. The password section opens in view when asked (?tab=password) or refused.
 */
(function () {
    'use strict';

    const SIDE = 512;
    const input = document.getElementById('photo-file');
    const save = document.getElementById('photo-save');
    const img = document.getElementById('profile-photo-img');
    const initials = document.getElementById('profile-photo-initials');
    const label = document.querySelector('label[for="photo-file"]');

    if (input && save) {
        save.hidden = true;
        if (label) {
            label.addEventListener('keydown', function (e) {
                if (e.key === 'Enter' || e.key === ' ') { e.preventDefault(); input.click(); }
            });
        }
        input.addEventListener('change', async function () {
            const file = input.files && input.files[0];
            if (!file) { save.hidden = true; return; }
            try {
                const square = await squared(file);
                if (square) {
                    const dt = new DataTransfer();
                    dt.items.add(new File([square], 'photo.jpg', { type: 'image/jpeg' }));
                    input.files = dt.files;
                }
            } catch (e) {
                // Not a picture the browser can read: sent as chosen, the server says what is wrong
            }
            const shown = input.files[0];
            if (img && /^image\//.test(shown.type)) {
                img.src = URL.createObjectURL(shown);
                img.hidden = false;
                if (initials) initials.hidden = true;
            }
            save.hidden = false;
            save.focus();
        });
    }

    /** The picture cut square around its centre, at most SIDE px, as a JPEG blob (null when the browser cannot draw it). */
    async function squared(file) {
        if (!window.createImageBitmap || !/^image\//.test(file.type)) return null;
        const bitmap = await createImageBitmap(file, { imageOrientation: 'from-image' });
        const side = Math.min(bitmap.width, bitmap.height);
        const out = Math.min(SIDE, side);
        const canvas = document.createElement('canvas');
        canvas.width = out;
        canvas.height = out;
        const ctx = canvas.getContext('2d');
        ctx.fillStyle = '#ffffff';
        ctx.fillRect(0, 0, out, out);
        ctx.imageSmoothingQuality = 'high';
        ctx.drawImage(bitmap, (bitmap.width - side) / 2, (bitmap.height - side) / 2, side, side, 0, 0, out, out);
        bitmap.close();
        return new Promise(function (resolve) { canvas.toBlob(resolve, 'image/jpeg', 0.9); });
    }

    // The password section: asked for from the header's menu, or a refusal to show
    const password = document.getElementById('password');
    if (password && (password.dataset.open === 'true' || password.querySelector('.is-invalid, .error-summary'))) {
        password.scrollIntoView({ block: 'start' });
        const first = password.querySelector('.is-invalid') || document.getElementById('currentPassword');
        if (first) first.focus({ preventScroll: true });
    }
})();
