/*
 * iWarehouse mobile POS (MPOS-01..05, SYNC-01..08, AUD-07).
 *
 * A driver signs in once on their phone (a token of its own), downloads their trip (the units on the vehicle, the
 * customers, the trip's frozen prices and a block of invoice numbers) and sells: by scanning a label or from the vehicle's
 * stock, at the trip's prices or lower within their limit, for cash and/or mobile money. Every sale is saved on the phone
 * first (IndexedDB) with its own UUID and invoice number, its receipt shown at once ("pending signature"), then sent to
 * the server when there is network, oldest first, retrying with backoff; the server takes it once, or keeps it for the
 * supervisor. The signed receipt (EBM) is fetched afterwards. Amounts are worked out with integers exactly as the server
 * does (MobileSales, Vat.lineAmount, Vat.totals), so both always agree. Every text comes from the server's mobile.* keys.
 */
(function () {
    'use strict';

    const API = '/api/v1';
    const DB_NAME = 'iwarehouse-pos';
    const SYNC_EVERY = 30000;
    const RETRY_MAX = 300000;
    const LANGUAGES = ['en', 'fr'];

    // ================================================================ storage (IndexedDB)

    let dbPromise = null;
    function db() {
        if (!dbPromise) {
            dbPromise = new Promise((resolve, reject) => {
                const open = indexedDB.open(DB_NAME, 1);
                open.onupgradeneeded = () => {
                    const d = open.result;
                    d.createObjectStore('kv');
                    d.createObjectStore('sales', { keyPath: 'clientId' });
                };
                open.onsuccess = () => resolve(open.result);
                open.onerror = () => reject(open.error);
            });
        }
        return dbPromise;
    }
    function tx(store, mode, work) {
        return db().then((d) => new Promise((resolve, reject) => {
            const t = d.transaction(store, mode);
            const result = work(t.objectStore(store));
            t.oncomplete = () => resolve(result && 'result' in result ? result.result : undefined);
            t.onerror = () => reject(t.error);
        }));
    }
    const kvGet = (key) => tx('kv', 'readonly', (s) => s.get(key));
    const kvSet = (key, value) => tx('kv', 'readwrite', (s) => s.put(value, key));
    const kvDel = (key) => tx('kv', 'readwrite', (s) => s.delete(key));
    const salesAll = () => tx('sales', 'readonly', (s) => s.getAll()).then((all) => (all || []).sort((a, b) => a.seq - b.seq));
    const salePut = (sale) => tx('sales', 'readwrite', (s) => s.put(sale));
    const salesClear = () => tx('sales', 'readwrite', (s) => s.clear());

    // ================================================================ state

    const state = {
        session: null,          // { token, device, user: {username, fullName}, deviceKey }
        lang: 'en',
        texts: {},
        bundle: null,           // the trip download (SYNC-01)
        sales: [],              // every sale made on this phone for the trip, oldest first
        cart: emptyCart(),
        view: 'sell',
        receiptId: null,
        search: '',
        notice: null,           // { kind: info|ok|warn|danger, text }
        errors: {},
        menu: false,
        confirm: null,          // { text, label, action }
        busy: false,
        syncing: false,
        retryDelay: 2000,
        retryTimer: null,
        camera: null
    };
    function emptyCart() {
        return { lines: [], customerId: null, buyerName: '', buyerTin: '', cash: '', tendered: '', momo: '', momoRef: '' };
    }

    // ================================================================ texts (mobile.* keys of the message bundles)

    function t(key) {
        let text = state.texts[key] || key;
        for (let i = 1; i < arguments.length; i++) {
            text = text.split('{' + (i - 1) + '}').join(arguments[i]);
        }
        return text;
    }
    /** A text with a count: the key's ".one" form when the count is 1 (the texts are served raw, without choice formats). */
    function tn(key, count) {
        const one = count === 1 && state.texts[key + '.one'] ? key + '.one' : key;
        return t.apply(null, [one].concat(Array.prototype.slice.call(arguments, 2)));
    }
    async function loadTexts(lang) {
        const cached = await kvGet('texts-' + lang);
        if (cached) state.texts = cached;
        try {
            const response = await fetch(API + '/messages?lang=' + encodeURIComponent(lang));
            if (response.ok) {
                state.texts = await response.json();
                await kvSet('texts-' + lang, state.texts);
            }
        } catch (e) { /* offline: keep what the phone has */ }
        document.documentElement.lang = lang;
    }

    // ================================================================ the server

    function uuid() {
        if (crypto.randomUUID) return crypto.randomUUID();
        const b = crypto.getRandomValues(new Uint8Array(16));
        b[6] = (b[6] & 0x0f) | 0x40; b[8] = (b[8] & 0x3f) | 0x80;
        const h = Array.from(b, (x) => x.toString(16).padStart(2, '0')).join('');
        return h.slice(0, 8) + '-' + h.slice(8, 12) + '-' + h.slice(12, 16) + '-' + h.slice(16, 20) + '-' + h.slice(20);
    }
    async function deviceKey() {
        let key = await kvGet('device-key');
        if (!key) {
            key = uuid();
            await kvSet('device-key', key);
        }
        return key;
    }
    /** Calls the API with the phone's token and the audit headers (AUD-07); throws {offline|unauthorized|status,error,message}. */
    async function api(method, path, body) {
        const headers = {
            'Accept': 'application/json',
            'Accept-Language': state.lang,
            'X-Device-Id': await deviceKey(),
            'X-Client-Time': new Date().toISOString(),
            'X-Request-Id': uuid()
        };
        if (state.session) headers['Authorization'] = 'Bearer ' + state.session.token;
        if (body !== undefined) headers['Content-Type'] = 'application/json';
        let response;
        try {
            response = await fetch(API + path, { method, headers, body: body === undefined ? undefined : JSON.stringify(body) });
        } catch (e) {
            throw { offline: true };
        }
        if (response.status === 401) throw { unauthorized: true };
        let json = null;
        try { json = await response.json(); } catch (e) { /* no body */ }
        if (!response.ok) {
            throw { status: response.status, error: json && json.error, message: (json && json.message) || t('mobile.error.server') };
        }
        return json;
    }

    // ================================================================ money (the server's rules, with integers)

    /** "27000.5" or 27000.5 as an integer with `scale` decimals, rounded half up. */
    function scaled(value, scale) {
        const text = String(value == null || value === '' ? '0' : value).trim();
        const negative = text.startsWith('-');
        const [whole, frac = ''] = text.replace('-', '').split('.');
        const digits = (frac + '0'.repeat(scale + 1)).slice(0, scale + 1);
        let n = BigInt(whole || '0') * 10n ** BigInt(scale) + BigInt(digits.slice(0, scale) || '0');
        if (Number(digits.charAt(scale)) >= 5) n += 1n;
        return negative ? -n : n;
    }
    /** a / b rounded half up, for a >= 0 and b > 0. */
    function divUp(a, b) {
        return (a * 2n + b) / (b * 2n);
    }
    /** An integer with `scale` decimals as text: "27000.50". */
    function text(n, scale) {
        if (scale === 0) return n.toString();
        const negative = n < 0n;
        const s = (negative ? -n : n).toString().padStart(scale + 1, '0');
        return (negative ? '-' : '') + s.slice(0, -scale) + '.' + s.slice(-scale);
    }
    function money(n) {
        // n in the currency's smallest unit (whole RWF)
        const d = state.bundle ? state.bundle.decimals : 0;
        const [w, f] = text(n, d).split('.');
        return w.replace(/\B(?=(\d{3})+(?!\d))/g, ',') + (f && Number(f) !== 0 ? '.' + f : '');
    }
    /** A decimal amount as text, grouped: "23000.5" -> "23,000.50", "23000" -> "23,000" (prices per m², VAT). */
    function grouped(value, scale) {
        const [w, f] = text(scaled(value, scale), scale).split('.');
        return w.replace(/\B(?=(\d{3})+(?!\d))/g, ',') + (f && Number(f) !== 0 ? '.' + f : '');
    }
    /** A unit's amount (MobileSales.lineAmount): price x chargeable area, VAT added when the list excludes it, rounded. */
    function lineAmount(pricePerM2, unit, list) {
        const decimals = state.bundle.decimals;
        const price = scaled(pricePerM2, 2);
        const area = divUp(BigInt(unit.widthMm) * BigInt(unit.heightMm), 100n);   // m², 4 decimals
        const min = scaled(list.minChargeableM2, 4);
        const chargeable = area > min ? area : min;
        const x = price * chargeable;                                               // RWF, 6 decimals
        const rate = scaled(unit.vatRate, 2);
        if (!list.pricesIncludeVat && rate > 0n) {
            return divUp(x * (10000n + rate), 10n ** BigInt(10 - decimals));        // 10 decimals, then the currency's
        }
        return divUp(x, 10n ** BigInt(6 - decimals));
    }
    /** VAT in a total per tax letter (Vat.totals): gross x rate / (100 + rate), 2 decimals. */
    function vatOf(lines) {
        const decimals = state.bundle.decimals;
        const groups = {};
        lines.forEach((l) => {
            const g = groups[l.taxCode] || (groups[l.taxCode] = { rate: scaled(l.vatRate, 2), gross: 0n });
            g.gross += scaled(l.amount, decimals) * 10n ** BigInt(2 - Math.min(decimals, 2));
        });
        let vat = 0n;
        Object.keys(groups).forEach((k) => {
            const g = groups[k];
            if (g.rate > 0n) vat += divUp(g.gross * g.rate, 10000n + g.rate);       // cents
        });
        return vat;
    }
    /** A price the driver may charge (MobileSales.withinLimit): the list price or more, or less within the limit. */
    function withinLimit(listPrice, price) {
        const list = scaled(listPrice, 2);
        const p = scaled(price, 2);
        if (p < 0n) return false;
        if (p >= list) return true;
        const percent = divUp((list - p) * 10000n, list);                          // 2 decimals
        return percent <= scaled(state.bundle.discountLimit, 2);
    }
    /** The lowest price within the limit, for the hint. */
    function lowestPrice(listPrice) {
        const list = scaled(listPrice, 2);
        const limit = scaled(state.bundle.discountLimit, 2);
        let low = list - (list * limit) / 10000n;
        while (low > 0n && !withinLimit(listPrice, text(low, 2))) low += 1n;
        return text(low, 2);
    }

    // ================================================================ the trip on the phone

    const customers = () => (state.bundle ? state.bundle.customers : []);
    const defaultCustomer = () => customers().find((c) => c.defaultCustomer) || customers()[0];
    const customerOf = (id) => customers().find((c) => c.id === id) || defaultCustomer();
    const soldHere = () => new Set(state.sales.flatMap((s) => s.lines.map((l) => l.unitId)));
    /** The units still on the vehicle as far as this phone knows (MPOS-02). */
    function unitsOnBoard() {
        if (!state.bundle) return [];
        const sold = soldHere();
        return state.bundle.units.filter((u) => !sold.has(u.id));
    }
    /** The glass's price for a customer (MobileSales.Snapshot.resolve): their list, then the default list. */
    function priceFor(customer, unit) {
        const lists = state.bundle.priceLists;
        const defaultList = lists.find((l) => l.defaultList);
        const own = lists.find((l) => customer && l.id === customer.priceListId) || defaultList;
        const find = (list) => list && state.bundle.prices.find((p) => p.listId === list.id && p.productId === unit.productId);
        let price = find(own);
        let list = own;
        if (!price && own !== defaultList) {
            price = find(defaultList);
            list = defaultList;
        }
        return price ? { list, price: String(price.pricePerM2) } : null;
    }
    function nextNumber() {
        const used = new Set(state.sales.map((s) => s.number));
        return (state.bundle ? state.bundle.numbers : []).find((n) => !used.has(n)) || null;
    }
    const pending = () => state.sales.filter((s) => s.status === 'pending');

    async function downloadTrip() {
        if (!navigator.onLine) return notice('warn', t('mobile.offline.needed'));
        state.busy = true; render();
        try {
            const bundle = await api('GET', '/trip');
            if (state.bundle && state.bundle.trip.id !== bundle.trip.id) {
                if (pending().length) {
                    notice('danger', t('mobile.trip.otherPending'));
                    return;
                }
                await salesClear();
                state.sales = [];
            }
            state.bundle = bundle;
            await kvSet('bundle', bundle);
            state.cart = emptyCart();
            notice('ok', tn('mobile.trip.downloaded', bundle.units.length, bundle.trip.number, bundle.units.length));
        } catch (e) {
            handleError(e);
        } finally {
            state.busy = false; render();
        }
    }

    // ================================================================ syncing (SYNC-02..06)

    function payload(sale) {
        return {
            clientId: sale.clientId, number: sale.number, tripId: sale.tripId, customerId: sale.customerId,
            buyerName: sale.buyerName || null, buyerTin: sale.buyerTin || null, createdAt: sale.createdAt,
            lines: sale.lines.map((l) => ({ unitId: l.unitId, pricePerM2: Number(l.pricePerM2), priceReason: l.priceReason || null, amount: Number(l.amount) })),
            payments: sale.payments.map((p) => ({ method: p.method, amount: Number(p.amount), reference: p.reference || null })),
            total: Number(sale.total), cashTendered: sale.cashTendered ? Number(sale.cashTendered) : null
        };
    }
    function applyResult(sale, result) {
        sale.result = result;
        sale.status = result.outcome === 'ACCEPTED' ? (result.ebm && result.ebm.signed ? 'signed' : 'accepted') : 'conflict';
        sale.syncedAt = sale.syncedAt || new Date().toISOString();
    }
    /** Sends the pending sales, oldest first; stops at the first that cannot reach the server and tries again later. */
    async function sync() {
        if (state.syncing || !state.session || !navigator.onLine) return;
        state.syncing = true; render();
        try {
            for (const sale of pending()) {
                const result = await api('POST', '/sales', payload(sale));
                applyResult(sale, result);
                await salePut(sale);
                render();
            }
            state.retryDelay = 2000;
            await refreshSignatures();
        } catch (e) {
            if (e.unauthorized) return signedOutByServer();
            if (e.status && e.status < 500) notice('danger', e.message);
            clearTimeout(state.retryTimer);
            state.retryTimer = setTimeout(sync, state.retryDelay);
            state.retryDelay = Math.min(state.retryDelay * 2, RETRY_MAX);
        } finally {
            state.syncing = false; render();
        }
    }
    /** Fetches the EBM signature of sales taken but not signed yet (SYNC-06). */
    async function refreshSignatures() {
        const waiting = state.sales.filter((s) => s.status === 'accepted').slice(-30);
        for (const sale of waiting) {
            const result = await api('GET', '/sales/' + sale.clientId);
            applyResult(sale, result);
            await salePut(sale);
        }
        if (waiting.length) render();
    }

    // ================================================================ selling (MPOS-02..05)

    function inCart(unitId) {
        return state.cart.lines.some((l) => l.unitId === unitId);
    }
    function addUnit(unit) {
        if (inCart(unit.id)) return removeLine(unit.id);
        const p = priceFor(customerOf(state.cart.customerId), unit);
        if (!p) return notice('danger', t('mobile.sell.noPrice', unit.code));
        state.cart.lines.push({ unitId: unit.id, pricePerM2: p.price, priceReason: '' });
        notice('ok', t('mobile.sell.added', unit.code));
    }
    function removeLine(unitId) {
        state.cart.lines = state.cart.lines.filter((l) => l.unitId !== unitId);
        render();
    }
    /** A label scanned or typed (MPOS-03): only units on this vehicle, not sold yet (MPOS-02). */
    function addByCode(raw) {
        const code = String(raw || '').trim().toUpperCase();
        if (!code) return;
        const unit = state.bundle.units.find((u) => u.code === code);
        if (!unit) return notice('danger', t('mobile.sell.notOnVehicle', code));
        if (soldHere().has(unit.id)) return notice('warn', t('mobile.sell.alreadySold', code));
        if (inCart(unit.id)) return notice('info', t('mobile.sell.alreadyInCart', code));
        addUnit(unit);
        render();
    }
    /** The cart priced for its customer: each line's unit, list price, amount; the total and VAT. */
    function priced() {
        const customer = customerOf(state.cart.customerId);
        const lines = state.cart.lines.map((l) => {
            const unit = state.bundle.units.find((u) => u.id === l.unitId);
            const p = priceFor(customer, unit);
            const priceOk = p && /^\d+(\.\d{1,2})?$/.test(String(l.pricePerM2).trim()) && withinLimit(p.price, l.pricePerM2);
            const amount = p && priceOk ? lineAmount(l.pricePerM2, unit, p.list) : 0n;
            return { line: l, unit, list: p && p.list, listPrice: p && p.price, priceOk, changed: p && scaled(l.pricePerM2, 2) !== scaled(p.price, 2), amount };
        });
        const total = lines.reduce((sum, x) => sum + x.amount, 0n);
        return { customer, lines, total };
    }
    function setCustomer(id) {
        state.cart.customerId = id || null;
        const customer = customerOf(state.cart.customerId);
        state.cart.lines.forEach((l) => {
            const unit = state.bundle.units.find((u) => u.id === l.unitId);
            const p = priceFor(customer, unit);
            if (p) { l.pricePerM2 = p.price; l.priceReason = ''; }
        });
        if (customer && !customer.defaultCustomer) {
            state.cart.buyerName = state.cart.buyerName || '';
            state.cart.buyerTin = customer.tin || state.cart.buyerTin;
        }
    }
    /** Checks the cart and saves the sale on the phone with its number; it syncs when it can. */
    async function completeSale() {
        const decimals = state.bundle.decimals;
        const p = priced();
        const errors = {};
        if (!p.lines.length) errors.cart = t('mobile.pay.empty');
        p.lines.forEach((x) => {
            if (!x.list) errors['price-' + x.unit.id] = t('mobile.sell.noPrice', x.unit.code);
            else if (!x.priceOk) errors['price-' + x.unit.id] = t('mobile.pay.priceLimit', grouped(lowestPrice(x.listPrice), 2));
            else if (x.changed && !x.line.priceReason.trim()) errors['reason-' + x.unit.id] = t('mobile.pay.reasonNeeded');
        });
        const tin = state.cart.buyerTin.replace(/[\s-]/g, '');
        if (tin && !/^\d{9}$/.test(tin)) errors.tin = t('mobile.pay.tin');
        // Both left empty: the whole total in cash, as the form shows it
        const cashText = state.cart.cash.trim() === '' && state.cart.momo.trim() === '' ? text(p.total, decimals) : state.cart.cash;
        const cash = cashText.trim() === '' ? 0n : scaled(cashText, decimals);
        const momo = state.cart.momo.trim() === '' ? 0n : scaled(state.cart.momo, decimals);
        if (cash < 0n || momo < 0n) errors.pay = t('mobile.pay.negative');
        else if (cash + momo !== p.total) errors.pay = t('mobile.pay.mustAddUp', money(cash + momo), money(p.total));
        if (momo > 0n && !state.cart.momoRef.trim()) errors.momoRef = t('mobile.pay.momoRef');
        let tendered = null;
        if (cash > 0n && state.cart.tendered.trim() !== '') {
            tendered = scaled(state.cart.tendered, decimals);
            if (tendered < cash) errors.tendered = t('mobile.pay.tenderedLow', money(cash));
        }
        const number = nextNumber();
        if (!number) errors.cart = t('mobile.pay.noNumber');
        state.errors = errors;
        if (Object.keys(errors).length) return render();

        const payments = [];
        if (cash > 0n) payments.push({ method: 'CASH', amount: text(cash, decimals) });
        if (momo > 0n) payments.push({ method: 'MOBILE_MONEY', amount: text(momo, decimals), reference: state.cart.momoRef.trim() });
        const sale = {
            clientId: uuid(), seq: Date.now(), number, tripId: state.bundle.trip.id,
            customerId: p.customer ? p.customer.id : null, customerName: p.customer ? p.customer.name : '',
            buyerName: state.cart.buyerName.trim(), buyerTin: tin, createdAt: new Date().toISOString(),
            lines: p.lines.map((x) => ({
                unitId: x.unit.id, code: x.unit.code, productName: x.unit.productName, widthMm: x.unit.widthMm, heightMm: x.unit.heightMm,
                pricePerM2: String(x.line.pricePerM2).trim(), listPrice: x.listPrice, priceReason: x.changed ? x.line.priceReason.trim() : '',
                amount: text(x.amount, decimals), taxCode: x.unit.taxCode, vatRate: String(x.unit.vatRate)
            })),
            payments, total: text(p.total, decimals), cashTendered: tendered == null ? null : text(tendered, decimals),
            change: tendered == null ? null : text(tendered - cash, decimals),
            status: 'pending', result: null
        };
        await salePut(sale);
        state.sales.push(sale);
        state.cart = emptyCart();
        state.errors = {};
        state.receiptId = sale.clientId;
        state.view = 'receipt';
        notice('ok', t('mobile.pay.saved', number));
        sync();
    }

    // ================================================================ camera (labels are QR codes)

    async function startCamera() {
        if (!('BarcodeDetector' in window) || !navigator.mediaDevices) return notice('warn', t('mobile.camera.unsupported'));
        try {
            const stream = await navigator.mediaDevices.getUserMedia({ video: { facingMode: 'environment' } });
            const detector = new window.BarcodeDetector({ formats: ['qr_code', 'code_128'] });
            state.camera = { stream, detector, timer: null };
            render();
            const video = document.getElementById('camera-video');
            video.srcObject = stream;
            await video.play();
            state.camera.timer = setInterval(async () => {
                try {
                    const codes = await detector.detect(video);
                    if (codes.length) {
                        stopCamera();
                        addByCode(codes[0].rawValue);
                    }
                } catch (e) { /* next frame */ }
            }, 300);
        } catch (e) {
            notice('warn', t('mobile.camera.denied'));
        }
    }
    function stopCamera() {
        if (!state.camera) return;
        clearInterval(state.camera.timer);
        state.camera.stream.getTracks().forEach((track) => track.stop());
        state.camera = null;
        render();
    }

    // ================================================================ signing in and out, closing the trip (SYNC-07, SYNC-08)

    async function signIn(form) {
        const username = form.username.value.trim();
        const password = form.password.value;
        const name = form.deviceName.value.trim();
        if (!navigator.onLine) return notice('warn', t('mobile.offline.needed'));
        if (!username || !password) {
            state.errors = { login: t('mobile.login.missing') };
            return render();
        }
        state.busy = true; render();
        try {
            const response = await api('POST', '/auth/login', { username, password, deviceId: await deviceKey(), deviceName: name });
            state.session = { token: response.token, device: response.device, user: response.user };
            await kvSet('session', state.session);
            state.errors = {};
            state.view = 'sell';
            state.busy = false;
            await downloadTrip();
            sync();
        } catch (e) {
            state.errors = { login: e.message || t('mobile.error.server') };
        } finally {
            state.busy = false; render();
        }
    }
    /** The phone's data for the trip is cleared, once nothing waits to sync (SYNC-07, SYNC-08). */
    async function closeTrip() {
        if (pending().length) return notice('danger', tn('mobile.close.pending', pending().length, pending().length));
        await salesClear();
        await kvDel('bundle');
        state.sales = [];
        state.bundle = null;
        state.cart = emptyCart();
        state.view = 'sell';
        notice('ok', t('mobile.close.done'));
    }
    async function signOut() {
        if (pending().length) return notice('danger', tn('mobile.signOut.pending', pending().length, pending().length));
        try { await api('POST', '/auth/logout'); } catch (e) { /* offline or revoked already: the data goes anyway */ }
        await forget();
        notice('ok', t('mobile.signOut.done'));
    }
    async function forget() {
        await salesClear();
        await kvDel('bundle');
        await kvDel('session');
        state.session = null;
        state.bundle = null;
        state.sales = [];
        state.cart = emptyCart();
        state.view = 'sell';
    }
    async function signedOutByServer() {
        // The token was revoked or the user disabled: sales waiting stay until someone signs in again
        state.session = null;
        await kvDel('session');
        notice('danger', t('mobile.login.expired'));
    }

    // ================================================================ notices and errors

    function notice(kind, textValue) {
        state.notice = { kind, text: textValue };
        render();
    }
    function handleError(e) {
        if (e.offline) return notice('warn', t('mobile.offline.needed'));
        if (e.unauthorized) return signedOutByServer();
        notice('danger', e.message || t('mobile.error.server'));
    }

    // ================================================================ rendering

    const esc = (v) => String(v == null ? '' : v).replace(/[&<>"']/g, (c) => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' })[c]);
    const icon = (path) => '<svg viewBox="0 0 24 24" aria-hidden="true">' + path + '</svg>';
    const ICONS = {
        menu: '<line x1="3" y1="6" x2="21" y2="6"/><line x1="3" y1="12" x2="21" y2="12"/><line x1="3" y1="18" x2="21" y2="18"/>',
        back: '<polyline points="15 18 9 12 15 6"/>',
        camera: '<path d="M23 19a2 2 0 0 1-2 2H3a2 2 0 0 1-2-2V8a2 2 0 0 1 2-2h4l2-3h6l2 3h4a2 2 0 0 1 2 2z"/><circle cx="12" cy="13" r="4"/>',
        close: '<line x1="18" y1="6" x2="6" y2="18"/><line x1="6" y1="6" x2="18" y2="18"/>'
    };
    function dateTime(iso) {
        const d = new Date(iso);
        const p = (n) => String(n).padStart(2, '0');
        return p(d.getDate()) + '/' + p(d.getMonth() + 1) + '/' + d.getFullYear() + ' ' + p(d.getHours()) + ':' + p(d.getMinutes());
    }
    function badge(sale) {
        return '<span class="badge ' + sale.status + '">' + esc(t('mobile.status.' + sale.status)) + '</span>';
    }
    function noticeHtml() {
        if (!state.notice) return '';
        return '<div class="note ' + state.notice.kind + '" role="status">' + esc(state.notice.text) + '</div>';
    }

    function topbar(title, sub, back) {
        return '<header class="topbar">'
            + (back ? '<button class="icon-btn" data-action="go" data-view="' + back + '" aria-label="' + esc(t('mobile.back')) + '">' + icon(ICONS.back) + '</button>' : '')
            + '<div class="topbar-title"><h1>' + esc(title) + '</h1>' + (sub ? '<div class="sub">' + esc(sub) + '</div>' : '') + '</div>'
            + (state.session ? '<button class="icon-btn" data-action="menu" aria-label="' + esc(t('mobile.menu')) + '">' + icon(ICONS.menu) + '</button>' : '')
            + '</header>' + netbar();
    }
    /** "Online · all synced" / "Offline · 3 pending" (SYNC-02). */
    function netbar() {
        if (!state.session) return '';
        const waiting = pending().length;
        const online = navigator.onLine;
        const kind = !online ? 'offline' : waiting ? 'pending' : 'online';
        const label = !online ? (waiting ? t('mobile.net.offlinePending', waiting) : t('mobile.net.offline'))
            : state.syncing ? t('mobile.net.syncing', waiting)
            : waiting ? t('mobile.net.pending', waiting) : t('mobile.net.online');
        const action = online && waiting && !state.syncing ? '<button class="netbar-action" data-action="sync">' + esc(t('mobile.net.syncNow')) + '</button>' : '';
        return '<div class="netbar ' + kind + '" role="status"><span class="dot"></span><span>' + esc(label) + '</span>' + action + '</div>';
    }

    function loginView() {
        const e = state.errors.login;
        return topbar(t('mobile.app'), t('mobile.login.subtitle'))
            + '<main class="screen">' + noticeHtml()
            + '<form class="card" data-form="login" autocomplete="on" novalidate>'
            + '<h2>' + esc(t('mobile.login.title')) + '</h2>'
            + '<div class="field"><label for="username">' + esc(t('mobile.login.username')) + '</label>'
            + '<input class="input" id="username" name="username" autocomplete="username" autocapitalize="none" spellcheck="false" required></div>'
            + '<div class="field"><label for="password">' + esc(t('mobile.login.password')) + '</label>'
            + '<input class="input" id="password" name="password" type="password" autocomplete="current-password" required></div>'
            + '<div class="field"><label for="deviceName">' + esc(t('mobile.login.deviceName')) + '</label>'
            + '<input class="input" id="deviceName" name="deviceName" maxlength="60" placeholder="' + esc(t('mobile.login.deviceNamePh')) + '">'
            + '<span class="hint">' + esc(t('mobile.login.deviceHint')) + '</span></div>'
            + '<div class="field"><label for="lang">' + esc(t('mobile.language')) + '</label>' + languageSelect() + '</div>'
            + (e ? '<p class="field error" role="alert">' + esc(e) + '</p>' : '')
            + '<div class="field"><button class="btn btn-primary btn-block" type="submit"' + (state.busy ? ' disabled' : '') + '>'
            + esc(state.busy ? t('mobile.wait') : t('mobile.login.submit')) + '</button></div>'
            + '</form></main>';
    }
    function languageSelect() {
        return '<select class="select" id="lang" data-action="lang">' + LANGUAGES.map((l) =>
            '<option value="' + l + '"' + (l === state.lang ? ' selected' : '') + '>' + esc(t('mobile.language.' + l)) + '</option>').join('') + '</select>';
    }

    function noTripView() {
        return topbar(t('mobile.app'), state.session.user.fullName)
            + '<main class="screen">' + noticeHtml()
            + '<div class="card"><h2>' + esc(t('mobile.trip.title')) + '</h2>'
            + '<p class="muted" style="margin-bottom:14px">' + esc(t('mobile.trip.hint')) + '</p>'
            + '<button class="btn btn-primary btn-block" data-action="download"' + (state.busy ? ' disabled' : '') + '>'
            + esc(state.busy ? t('mobile.wait') : t('mobile.trip.download')) + '</button></div></main>';
    }

    function sellView() {
        const b = state.bundle;
        const customer = customerOf(state.cart.customerId);
        const term = state.search.trim().toUpperCase();
        const units = unitsOnBoard().filter((u) => !term || u.code.includes(term) || u.productName.toUpperCase().includes(term) || u.productCode.includes(term));
        const p = priced();
        const rows = units.map((u) => {
            const price = priceFor(customer, u);
            const amount = price ? money(lineAmount(price.price, u, price.list)) + ' RWF' : t('mobile.sell.noPriceShort');
            return '<button class="item' + (inCart(u.id) ? ' in-cart' : '') + '" data-action="toggle" data-unit="' + u.id + '">'
                + '<div class="grow"><div class="code">' + esc(u.code) + '</div><div class="what">' + esc(u.productName) + ' · '
                + u.widthMm + ' × ' + u.heightMm + ' mm</div></div><div class="price">' + esc(amount) + '</div></button>';
        }).join('');
        return topbar(b.trip.number, b.trip.vehicle.plate + ' · ' + b.trip.area)
            + '<main class="screen">' + noticeHtml()
            + '<form class="card" data-form="scan" novalidate><h3>' + esc(t('mobile.sell.scanTitle')) + '</h3>'
            + '<div class="scan-row"><input class="input" id="scan-code" name="code" autocomplete="off" autocapitalize="characters" spellcheck="false" enterkeyhint="go"'
            + ' placeholder="' + esc(t('mobile.sell.scanPh')) + '" aria-label="' + esc(t('mobile.sell.scanTitle')) + '">'
            + '<button class="btn btn-primary" type="submit">' + esc(t('mobile.sell.add')) + '</button>'
            + ('BarcodeDetector' in window ? '<button class="icon-btn" type="button" data-action="camera" aria-label="' + esc(t('mobile.camera.open')) + '">' + icon(ICONS.camera) + '</button>' : '')
            + '</div>'
            + (state.camera ? '<div class="camera" style="margin-top:12px"><video id="camera-video" playsinline muted></video>'
                + '<button class="btn btn-outline" type="button" data-action="camera-stop">' + esc(t('mobile.camera.close')) + '</button></div>' : '')
            + '</form>'
            + '<div class="field"><label for="search">' + esc(t('mobile.sell.stock', unitsOnBoard().length)) + '</label>'
            + '<input class="input" id="search" type="search" data-action="search" value="' + esc(state.search) + '" placeholder="' + esc(t('mobile.sell.searchPh')) + '"></div>'
            + '<div class="list">' + (rows || '<div class="empty">' + esc(unitsOnBoard().length ? t('mobile.sell.noMatch') : t('mobile.sell.nothingLeft')) + '</div>') + '</div>'
            + '</main>'
            + (p.lines.length ? '<div class="cartbar"><div class="cartbar-inner"><div class="sum"><div class="count">' + esc(t('mobile.sell.inCart', p.lines.length))
                + '</div><div class="amount">' + money(p.total) + ' RWF</div></div>'
                + '<button class="btn btn-primary" data-action="go" data-view="cart">' + esc(t('mobile.sell.pay')) + '</button></div></div>' : '');
    }

    function cartView() {
        const p = priced();
        const e = state.errors;
        const options = customers().map((c) => '<option value="' + c.id + '"' + (p.customer && c.id === p.customer.id ? ' selected' : '') + '>'
            + esc(c.name + (c.code ? ' (' + c.code + ')' : '')) + '</option>').join('');
        const lines = p.lines.map((x) => {
            const id = x.unit.id;
            return '<div class="line"><div class="line-head"><div class="grow"><div class="code mono strong">' + esc(x.unit.code) + '</div>'
                + '<div class="muted">' + esc(x.unit.productName) + ' · ' + x.unit.widthMm + ' × ' + x.unit.heightMm + ' mm</div></div>'
                + '<button class="icon-btn" data-action="remove" data-unit="' + id + '" aria-label="' + esc(t('mobile.pay.remove')) + '">' + icon(ICONS.close) + '</button></div>'
                + '<div class="row"><div class="field"><label for="price-' + id + '">' + esc(t('mobile.pay.price')) + '</label>'
                + '<input class="input' + (e['price-' + id] ? ' is-invalid' : '') + '" id="price-' + id + '" inputmode="decimal" data-action="price" data-unit="' + id + '" value="' + esc(x.line.pricePerM2) + '">'
                + (x.listPrice ? '<span class="hint">' + esc(t('mobile.pay.listPrice', grouped(x.listPrice, 2))) + '</span>' : '')
                + (e['price-' + id] ? '<span class="error">' + esc(e['price-' + id]) + '</span>' : '') + '</div>'
                + '<div class="field"><label>' + esc(t('mobile.pay.amount')) + '</label><div class="input num strong" style="display:flex;align-items:center;justify-content:flex-end">'
                + (x.priceOk ? money(x.amount) : '—') + '</div></div></div>'
                + (x.changed ? '<div class="field"><label for="reason-' + id + '">' + esc(t('mobile.pay.reason')) + '</label>'
                    + '<input class="input' + (e['reason-' + id] ? ' is-invalid' : '') + '" id="reason-' + id + '" maxlength="200" data-action="reason" data-unit="' + id + '" value="' + esc(x.line.priceReason) + '">'
                    + (e['reason-' + id] ? '<span class="error">' + esc(e['reason-' + id]) + '</span>' : '') + '</div>' : '')
                + '</div>';
        }).join('');
        const cash = state.cart.cash === '' && state.cart.momo === '' ? text(p.total, state.bundle.decimals) : state.cart.cash;
        let change = '';
        if (state.cart.tendered.trim() !== '' && cash.trim() !== '') {
            const c = scaled(state.cart.tendered, state.bundle.decimals) - scaled(cash, state.bundle.decimals);
            if (c >= 0n) change = t('mobile.pay.change', money(c));
        }
        return topbar(t('mobile.pay.title'), state.bundle.trip.number, 'sell')
            + '<main class="screen">' + noticeHtml()
            + (e.cart ? '<div class="note danger" role="alert">' + esc(e.cart) + '</div>' : '')
            + '<div class="card"><h3>' + esc(t('mobile.pay.customer')) + '</h3>'
            + '<div class="field"><label for="customer">' + esc(t('mobile.pay.customer')) + '</label><select class="select" id="customer" data-action="customer">' + options + '</select>'
            + '<span class="hint">' + esc(t('mobile.pay.customerHint')) + '</span></div>'
            + '<div class="field"><label for="buyerName">' + esc(t('mobile.pay.buyerName')) + '</label><input class="input" id="buyerName" maxlength="100" data-action="cart-field" data-field="buyerName" value="' + esc(state.cart.buyerName) + '"></div>'
            + '<div class="field"><label for="buyerTin">' + esc(t('mobile.pay.buyerTin')) + '</label><input class="input' + (e.tin ? ' is-invalid' : '') + '" id="buyerTin" inputmode="numeric" maxlength="11" data-action="cart-field" data-field="buyerTin" value="' + esc(state.cart.buyerTin) + '">'
            + (e.tin ? '<span class="error">' + esc(e.tin) + '</span>' : '<span class="hint">' + esc(t('mobile.pay.buyerTinHint')) + '</span>') + '</div></div>'
            + '<div class="list">' + (lines || '<div class="empty">' + esc(t('mobile.pay.empty')) + '</div>') + '</div>'
            + '<div class="card"><div class="totals"><div class="t-row total"><span>' + esc(t('mobile.pay.total')) + '</span><span>' + money(p.total) + ' RWF</span></div>'
            + '<div class="t-row muted"><span>' + esc(t('mobile.pay.vatIncluded')) + '</span><span>' + grouped(text(vatOf(p.lines.filter((x) => x.priceOk).map((x) => ({ taxCode: x.unit.taxCode, vatRate: x.unit.vatRate, amount: text(x.amount, state.bundle.decimals) }))), 2), 2) + '</span></div></div></div>'
            + '<div class="card"><h3>' + esc(t('mobile.pay.payment')) + '</h3>'
            + '<div class="btn-group" style="margin-bottom:12px"><button class="btn btn-outline" type="button" data-action="all-cash">' + esc(t('mobile.pay.allCash')) + '</button>'
            + '<button class="btn btn-outline" type="button" data-action="all-momo">' + esc(t('mobile.pay.allMomo')) + '</button></div>'
            + '<div class="row"><div class="field"><label for="cash">' + esc(t('mobile.pay.cash')) + '</label><input class="input" id="cash" inputmode="decimal" data-action="cart-field" data-field="cash" value="' + esc(cash) + '"></div>'
            + '<div class="field"><label for="tendered">' + esc(t('mobile.pay.tendered')) + '</label><input class="input' + (e.tendered ? ' is-invalid' : '') + '" id="tendered" inputmode="decimal" data-action="cart-field" data-field="tendered" value="' + esc(state.cart.tendered) + '"></div></div>'
            + (e.tendered ? '<p class="field error">' + esc(e.tendered) + '</p>' : change ? '<p class="field hint strong">' + esc(change) + '</p>' : '')
            + '<div class="row" style="margin-top:12px"><div class="field"><label for="momo">' + esc(t('mobile.pay.momo')) + '</label><input class="input" id="momo" inputmode="decimal" data-action="cart-field" data-field="momo" value="' + esc(state.cart.momo) + '"></div>'
            + '<div class="field"><label for="momoRef">' + esc(t('mobile.pay.momoRefLabel')) + '</label><input class="input' + (e.momoRef ? ' is-invalid' : '') + '" id="momoRef" maxlength="60" autocapitalize="characters" data-action="cart-field" data-field="momoRef" value="' + esc(state.cart.momoRef) + '"></div></div>'
            + (e.momoRef ? '<p class="field error">' + esc(e.momoRef) + '</p>' : '')
            + (e.pay ? '<p class="field error" role="alert">' + esc(e.pay) + '</p>' : '')
            + '</div></main>'
            + '<div class="cartbar"><div class="cartbar-inner"><div class="sum"><div class="count">' + esc(t('mobile.sell.inCart', p.lines.length))
            + '</div><div class="amount">' + money(p.total) + ' RWF</div></div>'
            + '<button class="btn btn-primary" data-action="complete"' + (p.lines.length ? '' : ' disabled') + '>' + esc(t('mobile.pay.complete')) + '</button></div></div>';
    }

    /** The receipt (MPOS-05): printed or shared; "pending signature" until EBM signs it, then the SDC block (SYNC-06). */
    function receiptHtml(sale) {
        const b = state.bundle;
        const co = b ? b.company : {};
        const r = sale.result || {};
        const ebm = r.ebm;
        const rows = sale.lines.map((l) => '<div>' + esc(l.code) + ' ' + esc(l.productName) + '</div>'
            + '<div class="r-row"><span>' + l.widthMm + '×' + l.heightMm + ' @ ' + esc(grouped(l.pricePerM2, 2)) + '</span><span>' + money(scaled(l.amount, b.decimals)) + '</span></div>'
            + (l.priceReason ? '<div class="muted">' + esc(t('mobile.receipt.listPrice', grouped(l.listPrice, 2))) + '</div>' : '')).join('');
        const vat = vatOf(sale.lines);
        const pays = sale.payments.map((p) => '<div class="r-row"><span>' + esc(t('mobile.method.' + p.method)) + (p.reference ? ' ' + esc(p.reference) : '')
            + '</span><span>' + money(scaled(p.amount, b.decimals)) + '</span></div>').join('');
        let fiscal;
        if (ebm && ebm.signed) {
            fiscal = '<div class="r-rule"></div><div class="r-center r-strong">' + esc(t('mobile.receipt.sdc')) + '</div>'
                + '<div class="r-row"><span>' + esc(t('mobile.receipt.date')) + '</span><span>' + esc(ebm.signedAt) + '</span></div>'
                + '<div class="r-row"><span>SDC ID</span><span>' + esc(ebm.sdcId) + '</span></div>'
                + '<div class="r-row"><span>' + esc(t('mobile.receipt.number')) + '</span><span>' + esc(ebm.receiptLabel) + '</span></div>'
                + '<div>' + esc(t('mobile.receipt.internalData')) + '</div><div class="r-center r-break">' + esc(ebm.internalData) + '</div>'
                + '<div>' + esc(t('mobile.receipt.signature')) + '</div><div class="r-center r-break">' + esc(ebm.signature) + '</div>'
                + '<div class="r-row"><span>MRC</span><span>' + esc(ebm.mrcNo) + '</span></div>'
                + '<div class="r-qr">' + (ebm.qrSvg || '') + '</div>'
                + '<div class="r-center">' + esc(t('mobile.receipt.endLegal')) + '</div>'
                + (ebm.simulated ? '<div class="r-center r-strong">' + esc(t('mobile.receipt.simulated')) + '</div>' : '');
        } else if (sale.status === 'conflict') {
            fiscal = '<div class="r-rule"></div><div class="r-center r-strong">' + esc(t('mobile.receipt.conflict')) + '</div>';
        } else {
            fiscal = '<div class="r-rule"></div><div class="r-center r-strong">' + esc(t('mobile.receipt.pending')) + '</div>';
        }
        return '<div class="receipt" id="receipt">'
            + '<div class="r-center r-title">' + esc(co.name || 'iWarehouse') + '</div>'
            + (co.address ? '<div class="r-center">' + esc(co.address) + '</div>' : '')
            + (co.phone ? '<div class="r-center">' + esc(co.phone) + '</div>' : '')
            + (co.tin ? '<div class="r-center">TIN ' + esc(co.tin) + '</div>' : '')
            + '<div class="r-rule"></div>'
            + '<div class="r-row"><span>' + esc(t('mobile.receipt.invoice')) + '</span><span class="r-strong">' + esc(sale.number) + '</span></div>'
            + '<div class="r-row"><span>' + esc(t('mobile.receipt.date')) + '</span><span>' + esc(dateTime(sale.createdAt)) + '</span></div>'
            + '<div class="r-row"><span>' + esc(t('mobile.receipt.vehicle')) + '</span><span>' + esc(b.trip.vehicle.plate) + '</span></div>'
            + '<div class="r-row"><span>' + esc(t('mobile.receipt.seller')) + '</span><span>' + esc(b.trip.driver) + '</span></div>'
            + '<div class="r-row"><span>' + esc(t('mobile.receipt.customer')) + '</span><span>' + esc(sale.buyerName || sale.customerName) + '</span></div>'
            + (sale.buyerTin ? '<div class="r-row"><span>' + esc(t('mobile.receipt.tin')) + '</span><span>' + esc(sale.buyerTin) + '</span></div>' : '')
            + '<div class="r-rule"></div>' + rows + '<div class="r-rule"></div>'
            + '<div class="r-row r-big"><span>' + esc(t('mobile.receipt.total')) + '</span><span>' + money(scaled(sale.total, b.decimals)) + '</span></div>'
            + '<div class="r-row"><span>' + esc(t('mobile.receipt.vat')) + '</span><span>' + grouped(text(vat, 2), 2) + '</span></div>'
            + pays
            + (sale.change ? '<div class="r-row"><span>' + esc(t('mobile.receipt.change')) + '</span><span>' + money(scaled(sale.change, b.decimals)) + '</span></div>' : '')
            + fiscal + '</div>';
    }
    /** The receipt as text, for SMS and WhatsApp. */
    function receiptText(sale) {
        const b = state.bundle;
        const r = sale.result || {};
        const lines = [b.company.name || 'iWarehouse', t('mobile.receipt.invoice') + ' ' + sale.number, dateTime(sale.createdAt)];
        sale.lines.forEach((l) => lines.push(l.code + ' ' + l.productName + ': ' + money(scaled(l.amount, b.decimals)) + ' RWF'));
        lines.push(t('mobile.receipt.total') + ': ' + money(scaled(sale.total, b.decimals)) + ' RWF');
        lines.push(r.ebm && r.ebm.signed ? 'EBM ' + r.ebm.receiptLabel + ' · SDC ' + r.ebm.sdcId : t('mobile.receipt.pending'));
        return lines.join('\n');
    }
    function receiptView() {
        const sale = state.sales.find((s) => s.clientId === state.receiptId);
        if (!sale) { state.view = 'sales'; return salesView(); }
        const r = sale.result;
        const status = sale.status === 'conflict'
            ? '<div class="note danger">' + esc(t('mobile.receipt.conflictNote', r && r.conflict ? r.conflict.reasonText : '')) + '</div>'
            : sale.status === 'pending' ? '<div class="note warn">' + esc(t('mobile.receipt.pendingNote')) + '</div>'
            : sale.status === 'accepted' ? '<div class="note info">' + esc(t('mobile.receipt.acceptedNote')) + '</div>'
            : '<div class="note ok">' + esc(t('mobile.receipt.signedNote')) + '</div>';
        const text = encodeURIComponent(receiptText(sale));
        return topbar(sale.number, dateTime(sale.createdAt), 'sales')
            + '<main class="screen">' + noticeHtml() + status + receiptHtml(sale)
            + '<div class="btn-group"><button class="btn btn-outline" data-action="print">' + esc(t('mobile.receipt.print')) + '</button>'
            + '<a class="btn btn-outline" href="sms:?&body=' + text + '">' + esc(t('mobile.receipt.sms')) + '</a>'
            + '<a class="btn btn-outline" href="https://wa.me/?text=' + text + '" target="_blank" rel="noopener">' + esc(t('mobile.receipt.whatsapp')) + '</a></div>'
            + '<button class="btn btn-primary btn-block" data-action="go" data-view="sell">' + esc(t('mobile.receipt.newSale')) + '</button>'
            + '</main>';
    }

    function salesView() {
        const rows = state.sales.slice().reverse().map((s) => '<button class="item" data-action="receipt" data-sale="' + s.clientId + '">'
            + '<div class="grow"><div class="code">' + esc(s.number) + '</div><div class="what">' + esc(dateTime(s.createdAt)) + ' · '
            + esc(tn('mobile.sales.units', s.lines.length, s.lines.length)) + '</div></div><div style="text-align:right"><div class="price">' + money(scaled(s.total, state.bundle.decimals))
            + '</div>' + badge(s) + '</div></button>').join('');
        const total = state.sales.filter((s) => s.status !== 'conflict').reduce((sum, s) => sum + scaled(s.total, state.bundle.decimals), 0n);
        return topbar(t('mobile.sales.title'), state.bundle.trip.number, 'sell')
            + '<main class="screen">' + noticeHtml()
            + '<div class="card"><div class="totals"><div class="t-row"><span>' + esc(t('mobile.sales.count')) + '</span><span class="strong">' + state.sales.length + '</span></div>'
            + '<div class="t-row"><span>' + esc(t('mobile.sales.pending')) + '</span><span class="strong">' + pending().length + '</span></div>'
            + '<div class="t-row total"><span>' + esc(t('mobile.sales.total')) + '</span><span>' + money(total) + ' RWF</span></div></div></div>'
            + '<div class="list">' + (rows || '<div class="empty">' + esc(t('mobile.sales.none')) + '</div>') + '</div></main>';
    }

    function menuHtml() {
        if (!state.menu) return '';
        const items = [
            ['go', 'sell', t('mobile.menu.sell')], ['go', 'sales', t('mobile.menu.sales')], ['download', '', t('mobile.menu.refresh')],
            ['lang-toggle', '', t('mobile.menu.language')], ['ask-close', '', t('mobile.menu.closeTrip')], ['ask-signout', '', t('mobile.menu.signOut')]
        ].filter((i) => state.bundle || !['sell', 'sales'].includes(i[1]) && i[0] !== 'ask-close');
        return '<div class="sheet-backdrop" data-action="menu-close"></div><div class="sheet" role="menu">'
            + items.map((i) => '<button class="item' + (i[0] === 'ask-signout' || i[0] === 'ask-close' ? ' danger' : '') + '" role="menuitem" data-action="' + i[0]
                + '" data-view="' + i[1] + '">' + esc(i[2]) + '</button>').join('') + '</div>';
    }
    function confirmHtml() {
        if (!state.confirm) return '';
        return '<div class="sheet-backdrop" data-action="confirm-no"></div><div class="sheet" role="dialog" aria-modal="true" style="padding:16px">'
            + '<p style="margin-bottom:14px">' + esc(state.confirm.text) + '</p><div class="btn-group">'
            + '<button class="btn btn-outline" data-action="confirm-no">' + esc(t('mobile.notNow')) + '</button>'
            + '<button class="btn btn-danger" data-action="confirm-yes">' + esc(state.confirm.label) + '</button></div></div>';
    }

    function render() {
        const app = document.getElementById('app');
        const focus = document.activeElement && document.activeElement.id;
        let html;
        if (!state.session) html = loginView();
        else if (!state.bundle) html = noTripView();
        else if (state.view === 'cart') html = cartView();
        else if (state.view === 'receipt') html = receiptView();
        else if (state.view === 'sales') html = salesView();
        else html = sellView();
        app.innerHTML = html + menuHtml() + confirmHtml();
        if (state.camera) {
            const video = document.getElementById('camera-video');
            if (video && !video.srcObject) { video.srcObject = state.camera.stream; video.play().catch(() => {}); }
        }
        if (focus) {
            const el = document.getElementById(focus);
            if (el && el.focus) { el.focus(); if (el.setSelectionRange && el.type !== 'number' && typeof el.value === 'string') el.setSelectionRange(el.value.length, el.value.length); }
        } else if (state.session && state.bundle && state.view === 'sell' && !state.menu && !state.confirm) {
            const scan = document.getElementById('scan-code');
            if (scan && window.matchMedia('(pointer: fine)').matches) scan.focus();
        }
    }

    // ================================================================ events

    const app = document.getElementById('app');
    app.addEventListener('click', async (event) => {
        const el = event.target.closest('[data-action]');
        if (!el || el.tagName === 'INPUT' || el.tagName === 'SELECT') return;
        const action = el.dataset.action;
        if (action !== 'menu' && action !== 'toggle') state.notice = null;
        switch (action) {
            case 'menu': state.menu = true; return render();
            case 'menu-close': state.menu = false; return render();
            case 'go': state.menu = false; state.view = el.dataset.view; state.errors = {}; return render();
            case 'download': state.menu = false; return downloadTrip();
            case 'sync': state.retryDelay = 2000; return sync();
            case 'toggle': {
                const unit = state.bundle.units.find((u) => u.id === el.dataset.unit);
                if (unit) addUnit(unit);
                return render();
            }
            case 'remove': return removeLine(el.dataset.unit);
            case 'complete': return completeSale();
            case 'receipt': state.receiptId = el.dataset.sale; state.view = 'receipt'; render();
                if (navigator.onLine) refreshSignatures().catch(() => {});
                return;
            case 'print': return window.print();
            case 'camera': return startCamera();
            case 'camera-stop': return stopCamera();
            case 'all-cash': state.cart.cash = text(priced().total, state.bundle.decimals); state.cart.momo = ''; return render();
            case 'all-momo': state.cart.momo = text(priced().total, state.bundle.decimals); state.cart.cash = '0'; state.cart.tendered = ''; return render();
            case 'lang-toggle': {
                state.menu = false;
                state.lang = state.lang === 'en' ? 'fr' : 'en';
                await kvSet('lang', state.lang);
                await loadTexts(state.lang);
                return render();
            }
            case 'ask-close': state.menu = false;
                state.confirm = { text: t('mobile.close.confirm'), label: t('mobile.menu.closeTrip'), action: closeTrip }; return render();
            case 'ask-signout': state.menu = false;
                state.confirm = { text: t('mobile.signOut.confirm'), label: t('mobile.menu.signOut'), action: signOut }; return render();
            case 'confirm-no': state.confirm = null; return render();
            case 'confirm-yes': { const run = state.confirm.action; state.confirm = null; return run(); }
            default: return undefined;
        }
    });
    app.addEventListener('submit', (event) => {
        event.preventDefault();
        const form = event.target;
        if (form.dataset.form === 'login') return signIn(form);
        if (form.dataset.form === 'scan') {
            const code = form.code.value;
            form.code.value = '';
            state.notice = null;
            return addByCode(code);
        }
        return undefined;
    });
    app.addEventListener('input', (event) => {
        const el = event.target;
        const action = el.dataset.action;
        if (action === 'search') { state.search = el.value; return render(); }
        // A field being corrected drops its error, so what it shows (the change, the amount) is current
        if (action === 'cart-field') {
            state.cart[el.dataset.field] = el.value;
            delete state.errors[FIELD_ERRORS[el.dataset.field] || el.dataset.field];
            return renderSoon();
        }
        const line = state.cart.lines.find((l) => l.unitId === el.dataset.unit);
        if (action === 'price' && line) { line.pricePerM2 = el.value; delete state.errors['price-' + line.unitId]; return renderSoon(); }
        if (action === 'reason' && line) { line.priceReason = el.value; delete state.errors['reason-' + line.unitId]; return undefined; }
        return undefined;
    });
    app.addEventListener('change', async (event) => {
        const el = event.target;
        if (el.dataset.action === 'customer') { setCustomer(el.value); return render(); }
        if (el.dataset.action === 'lang') {
            state.lang = el.value;
            await kvSet('lang', state.lang);
            await loadTexts(state.lang);
            return render();
        }
        return undefined;
    });
    /** The error each payment field clears when changed (cash and mobile money share "must add up"). */
    const FIELD_ERRORS = { buyerTin: 'tin', cash: 'pay', momo: 'pay' };
    let renderTimer = null;
    function renderSoon() {
        clearTimeout(renderTimer);
        renderTimer = setTimeout(render, 350);
    }
    window.addEventListener('online', () => { state.retryDelay = 2000; render(); sync(); });
    window.addEventListener('offline', render);
    document.addEventListener('visibilitychange', () => { if (document.visibilityState === 'visible') sync(); });
    setInterval(() => {
        if (pending().length || state.sales.some((s) => s.status === 'accepted')) sync();
    }, SYNC_EVERY);

    // ================================================================ start

    async function start() {
        if ('serviceWorker' in navigator) navigator.serviceWorker.register('/m/sw.js', { scope: '/m/' }).catch(() => {});
        const savedLang = await kvGet('lang');
        state.lang = LANGUAGES.includes(savedLang) ? savedLang : ((navigator.language || 'en').slice(0, 2) === 'fr' ? 'fr' : 'en');
        await loadTexts(state.lang);
        state.session = (await kvGet('session')) || null;
        state.bundle = (await kvGet('bundle')) || null;
        state.sales = await salesAll();
        render();
        if (state.session && navigator.onLine) {
            if (state.bundle) downloadTrip().then(sync); else downloadTrip();
        }
    }
    start();
})();
