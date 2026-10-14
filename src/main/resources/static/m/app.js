/*
 * iWarehouse mobile POS (MPOS-01..05, SYNC-01..08, AUD-07).
 *
 * A driver signs in once on their phone (a token of its own), downloads their trip (the units on the vehicle, the
 * customers, the trip's frozen prices and a block of invoice numbers) and sells: by scanning a label or from the vehicle's
 * stock, at the trip's prices or lower within their limit, for cash and/or mobile money. Every sale is saved on the phone
 * first (IndexedDB) with its own UUID and invoice number, its receipt shown at once ("pending signature"), then sent to
 * the server when there is network, oldest first, retrying with backoff; the server takes it once, or keeps it for the
 * supervisor. The signed receipt (EBM) is fetched afterwards. At the end of the day the driver reports breakage on the
 * road (a photo, the reason, the place; queued offline like the sales) and declares the cash they hold (MPOS-06, MPOS-07). Amounts are worked out with integers exactly as the server
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
                const open = indexedDB.open(DB_NAME, 2);
                open.onupgradeneeded = () => {
                    const d = open.result;
                    if (!d.objectStoreNames.contains('kv')) d.createObjectStore('kv');
                    if (!d.objectStoreNames.contains('sales')) d.createObjectStore('sales', { keyPath: 'clientId' });
                    if (!d.objectStoreNames.contains('breakages')) d.createObjectStore('breakages', { keyPath: 'clientId' });
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
    const breakagesAll = () => tx('breakages', 'readonly', (s) => s.getAll()).then((all) => (all || []).sort((a, b) => a.seq - b.seq));
    const breakagePut = (b) => tx('breakages', 'readwrite', (s) => s.put(b));
    const breakagesClear = () => tx('breakages', 'readwrite', (s) => s.clear());

    // ================================================================ state

    const state = {
        session: null,          // { token, device, user: {username, fullName}, deviceKey }
        lang: 'en',
        texts: {},
        bundle: null,           // the trip download (SYNC-01)
        sales: [],              // every sale made on this phone for the trip, oldest first
        cart: emptyCart(),
        view: 'day',            // My day first; then sell, cart, receipt, sales, breakage, declare
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
        camera: null,
        breakages: [],          // the breakage reports made on this phone for the trip (MPOS-06)
        breakage: emptyBreakage(),
        declareAmount: ''
    };
    function emptyBreakage() {
        return { unitId: '', reason: '', place: '', photo: null };
    }
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
    /** Units reported broken on the road (FLT-08): by this phone (not refused or rejected) or known to the server. */
    function reportedBroken() {
        const broken = new Set(state.bundle && state.bundle.reportedBroken ? state.bundle.reportedBroken : []);
        state.breakages.filter((b) => b.status !== 'refused' && !(b.result && b.result.status === 'REJECTED')).forEach((b) => broken.add(b.unitId));
        return broken;
    }
    /** The units still on the vehicle and for sale as far as this phone knows (MPOS-02). */
    function unitsOnBoard() {
        if (!state.bundle) return [];
        const sold = soldHere();
        const broken = reportedBroken();
        return state.bundle.units.filter((u) => !sold.has(u.id) && !broken.has(u.id));
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
    const pendingBreakages = () => state.breakages.filter((b) => b.status === 'pending');
    /** Everything this phone still has to send: sales and breakage reports. */
    const pendingCount = () => pending().length + pendingBreakages().length;

    async function downloadTrip() {
        if (!navigator.onLine) return notice('warn', t('mobile.offline.needed'));
        state.busy = true; render();
        try {
            const bundle = await api('GET', '/trip');
            if (state.bundle && state.bundle.trip.id !== bundle.trip.id) {
                if (pendingCount()) {
                    notice('danger', t('mobile.trip.otherPending'));
                    return;
                }
                await salesClear();
                await breakagesClear();
                state.sales = [];
                state.breakages = [];
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
            for (const b of pendingBreakages()) {
                try {
                    b.result = await api('POST', '/breakages', {
                        clientId: b.clientId, tripId: b.tripId, unitId: b.unitId, reason: b.reason, place: b.place,
                        happenedAt: b.happenedAt, photo: b.photo, photoName: b.code + '.jpg'
                    });
                    b.status = 'sent';
                } catch (e) {
                    if (e.offline || e.unauthorized || !e.status || e.status >= 500) throw e;
                    b.status = 'refused';
                    b.error = e.message;
                }
                b.photo = null;                                         // sent or refused: the phone keeps no copy
                await breakagePut(b);
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
        // The supervisor's decision on the breakage reported (FLT-08)
        const deciding = state.breakages.filter((b) => b.status === 'sent' && b.result && b.result.status === 'REPORTED').slice(-30);
        for (const b of deciding) {
            b.result = await api('GET', '/breakages/' + b.clientId);
            await breakagePut(b);
        }
        if (waiting.length || deciding.length) render();
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
        if (reportedBroken().has(unit.id)) return notice('warn', t('mobile.sell.reportedBroken', code));
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

    // ================================================================ the end of the day (MPOS-06, MPOS-07)

    /** A photo shrunk to 1600 px at most, as a JPEG data URL: small enough to queue offline and send on a slow network. */
    function shrink(file) {
        return new Promise((resolve, reject) => {
            const url = URL.createObjectURL(file);
            const img = new Image();
            img.onload = () => {
                const scale = Math.min(1, 1600 / Math.max(img.naturalWidth, img.naturalHeight));
                const canvas = document.createElement('canvas');
                canvas.width = Math.round(img.naturalWidth * scale);
                canvas.height = Math.round(img.naturalHeight * scale);
                canvas.getContext('2d').drawImage(img, 0, 0, canvas.width, canvas.height);
                URL.revokeObjectURL(url);
                resolve(canvas.toDataURL('image/jpeg', 0.8));
            };
            img.onerror = () => { URL.revokeObjectURL(url); reject(new Error('photo')); };
            img.src = url;
        });
    }
    /** The phone's position added to the place, when it gives one. */
    function addPosition() {
        if (!navigator.geolocation) return notice('warn', t('mobile.breakage.noPosition'));
        navigator.geolocation.getCurrentPosition((pos) => {
            const at = pos.coords.latitude.toFixed(5) + ', ' + pos.coords.longitude.toFixed(5);
            state.breakage.place = (state.breakage.place.trim() ? state.breakage.place.trim() + ' ' : '') + '(' + at + ')';
            render();
        }, () => notice('warn', t('mobile.breakage.noPosition')), { enableHighAccuracy: true, timeout: 10000 });
    }
    /** Saves a breakage report on the phone (MPOS-06): it syncs when it can, like a sale. */
    async function saveBreakage() {
        const f = state.breakage;
        const errors = {};
        const unit = state.bundle.units.find((u) => u.id === f.unitId);
        if (!unit) errors.unit = t('mobile.breakage.unitNeeded');
        if (!f.reason.trim()) errors.reason = t('mobile.breakage.reasonNeeded');
        if (!f.place.trim()) errors.place = t('mobile.breakage.placeNeeded');
        if (!f.photo) errors.photo = t('mobile.breakage.photoNeeded');
        state.errors = errors;
        if (Object.keys(errors).length) return render();
        const b = {
            clientId: uuid(), seq: Date.now(), tripId: state.bundle.trip.id, unitId: unit.id, code: unit.code, productName: unit.productName,
            reason: f.reason.trim().slice(0, 255), place: f.place.trim().slice(0, 255), happenedAt: new Date().toISOString(), photo: f.photo,
            status: 'pending', result: null
        };
        await breakagePut(b);
        state.breakages.push(b);
        state.cart.lines = state.cart.lines.filter((l) => l.unitId !== unit.id);
        state.breakage = emptyBreakage();
        notice('ok', t('mobile.breakage.saved', unit.code));
        sync();
    }
    /** Declares the cash the driver holds (MPOS-07), once, with network. */
    async function declareCash() {
        const value = state.declareAmount.trim();
        if (!/^\d+$/.test(value)) {
            state.errors = { declare: t('mobile.declare.invalid') };
            return render();
        }
        if (!navigator.onLine) return notice('warn', t('mobile.offline.needed'));
        state.busy = true; render();
        try {
            const r = await api('POST', '/trip/declaration', { tripId: state.bundle.trip.id, amount: Number(value) });
            state.bundle.cashDeclared = r.cashDeclared;
            state.bundle.cashDeclaredAt = r.declaredAt;
            await kvSet('bundle', state.bundle);
            state.declareAmount = '';
            state.errors = {};
            notice('ok', t('mobile.declare.saved', money(scaled(r.cashDeclared, state.bundle.decimals))));
        } catch (e) {
            handleError(e);
        } finally {
            state.busy = false; render();
        }
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
            state.view = 'day';
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
        if (pendingCount()) return notice('danger', tn('mobile.close.pending', pendingCount(), pendingCount()));
        await salesClear();
        await breakagesClear();
        await kvDel('bundle');
        state.sales = [];
        state.breakages = [];
        state.bundle = null;
        state.cart = emptyCart();
        state.view = 'day';
        notice('ok', t('mobile.close.done'));
    }
    async function signOut() {
        if (pendingCount()) return notice('danger', tn('mobile.signOut.pending', pendingCount(), pendingCount()));
        try { await api('POST', '/auth/logout'); } catch (e) { /* offline or revoked already: the data goes anyway */ }
        await forget();
        notice('ok', t('mobile.signOut.done'));
    }
    async function forget() {
        await salesClear();
        await breakagesClear();
        await kvDel('bundle');
        await kvDel('session');
        state.session = null;
        state.bundle = null;
        state.sales = [];
        state.breakages = [];
        state.cart = emptyCart();
        state.view = 'day';
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
    // The client's design (claude.ai design "iWarehouse Mobile POS Redesign"), as the High Bytes POS app draws it
    // (mobile/lib/widgets.dart, art.dart): the blue header with its wave, the network pill and an illustration; cards,
    // notes with their icon, tiles, rows of 68 px with a round check, the sale's bar.

    const esc = (v) => String(v == null ? '' : v).replace(/[&<>"']/g, (c) => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' })[c]);
    const icon = (path) => '<svg viewBox="0 0 24 24" aria-hidden="true">' + path + '</svg>';
    const ic = (path, extra) => '<svg class="ic' + (extra ? ' ' + extra : '') + '" viewBox="0 0 24 24" aria-hidden="true">' + path + '</svg>';
    const ICONS = {
        menu: '<path d="M4 7h16M4 12h16M4 17h16"/>',
        back: '<path d="M15 18l-6-6 6-6"/>',
        camera: '<path d="M23 19a2 2 0 0 1-2 2H3a2 2 0 0 1-2-2V8a2 2 0 0 1 2-2h4l2-3h6l2 3h4a2 2 0 0 1 2 2z"/><circle cx="12" cy="13" r="4"/>',
        scan: '<path d="M4 8V5a1 1 0 0 1 1-1h3M16 4h3a1 1 0 0 1 1 1v3M20 16v3a1 1 0 0 1-1 1h-3M8 20H5a1 1 0 0 1-1-1v-3M7 12h10"/>',
        close: '<line x1="18" y1="6" x2="6" y2="18"/><line x1="6" y1="6" x2="18" y2="18"/>',
        check: '<path d="M5 12.5l4.5 4.5L19 7.5"/>',
        info: '<circle cx="12" cy="12" r="9"/><path d="M12 11v5M12 8h.01"/>',
        alert: '<circle cx="12" cy="12" r="9"/><path d="M12 7.5v5.5M12 16.5h.01"/>',
        xCircle: '<circle cx="12" cy="12" r="9"/><path d="M15 9l-6 6M9 9l6 6"/>',
        user: '<circle cx="12" cy="8" r="4"/><path d="M4 20c1.5-3.5 4.5-5 8-5s6.5 1.5 8 5"/>',
        lock: '<rect x="4" y="10" width="16" height="11" rx="2"/><path d="M8 10V7a4 4 0 0 1 8 0v3"/>',
        phone: '<rect x="6" y="2" width="12" height="20" rx="2.5"/><path d="M11 18h2"/>',
        globe: '<circle cx="12" cy="12" r="9"/><path d="M3 12h18M12 3c2.5 2.5 3.8 5.5 3.8 9s-1.3 6.5-3.8 9c-2.5-2.5-3.8-5.5-3.8-9S9.5 5.5 12 3z"/>',
        eye: '<path d="M2 12s3.5-7 10-7 10 7 10 7-3.5 7-10 7S2 12 2 12z"/><circle cx="12" cy="12" r="3"/>',
        eyeOff: '<path d="M3 3l18 18M10.6 5.1A10.9 10.9 0 0 1 12 5c6.5 0 10 7 10 7a17.6 17.6 0 0 1-3.2 4.2M6.6 6.6C3.9 8.4 2 12 2 12s3.5 7 10 7c1.8 0 3.4-.5 4.8-1.3"/><path d="M9.9 9.9a3 3 0 0 0 4.2 4.2"/>',
        chevronDown: '<path d="M6 9l6 6 6-6"/>',
        search: '<circle cx="11" cy="11" r="7"/><path d="M20 20l-3.5-3.5"/>',
        receipt: '<path d="M6 3h12v18l-3-2-3 2-3-2-3 2V3z"/><path d="M9 8h6M9 12h6"/>',
        cash: '<rect x="2.5" y="6" width="19" height="12" rx="2"/><circle cx="12" cy="12" r="2.5"/><path d="M6 9.5v5M18 9.5v5"/>',
        truck: '<path d="M3 6h11v10H3zM14 9h4l3 3v4h-7"/><circle cx="7" cy="17.5" r="1.8"/><circle cx="17" cy="17.5" r="1.8"/>',
        home: '<path d="M3 11l9-7 9 7"/><path d="M5 10v10h14V10"/>',
        broken: '<path d="M12 3l9 16H3z"/><path d="M12 10v4M12 17h.01"/>',
        sync: '<path d="M20 11a8 8 0 0 0-14.3-4.9L4 8M4 4v4h4M4 13a8 8 0 0 0 14.3 4.9L20 16M20 20v-4h-4"/>',
        box: '<path d="M3 7l9-4 9 4v10l-9 4-9-4z"/><path d="M3 7l9 4 9-4M12 11v10"/>',
        logout: '<path d="M15 4h4a1 1 0 0 1 1 1v14a1 1 0 0 1-1 1h-4M10 16l-4-4 4-4M6 12h10"/>',
        printer: '<path d="M7 8V3h10v5M7 17H4a1 1 0 0 1-1-1v-6a2 2 0 0 1 2-2h14a2 2 0 0 1 2 2v6a1 1 0 0 1-1 1h-3"/><rect x="7" y="14" width="10" height="7" rx="1"/>',
        sms: '<path d="M4 5h16v11H9l-5 4z"/><path d="M8 10h.01M12 10h.01M16 10h.01"/>',
        share: '<circle cx="18" cy="5" r="2.5"/><circle cx="6" cy="12" r="2.5"/><circle cx="18" cy="19" r="2.5"/><path d="M8.2 10.8l7.6-4.4M8.2 13.2l7.6 4.4"/>',
        target: '<circle cx="12" cy="12" r="7"/><circle cx="12" cy="12" r="2.5"/><path d="M12 2v3M12 19v3M2 12h3M19 12h3"/>'
    };

    // The design's illustrations (mobile/lib/art.dart): the truck carrying glass, the receipts, the banknotes; a cracked pane
    const TRUCK_BODY = '<rect x="106" y="86" width="166" height="13" rx="3" fill="#e3f1ff"/>'
        + '<path d="M34 108 L34 70 Q34 56 48 56 L80 56 Q90 56 95 65 L104 84 L104 108 Z" fill="#ffffff"/>'
        + '<path d="M46 64 L78 64 Q83 64 86 69 L94 84 L46 84 Z" fill="#8ec4f0"/>'
        + '<rect x="34" y="92" width="8" height="6" rx="2" fill="#f5a54f"/><rect x="30" y="98" width="246" height="12" rx="4" fill="#0d2f65"/>'
        + '<circle cx="70" cy="112" r="14" fill="#0d2f65"/><circle cx="70" cy="112" r="5.5" fill="#c7d9ea"/>'
        + '<circle cx="232" cy="112" r="14" fill="#0d2f65"/><circle cx="232" cy="112" r="5.5" fill="#c7d9ea"/>';
    const panes = (stroke, frame) => '<path d="M130 88 L162 30 L194 88" fill="none" stroke="#c7d9ea" stroke-width="' + frame + '" stroke-linejoin="round"/>'
        + '<rect x="120" y="40" width="34" height="48" rx="2" transform="rotate(-14 154 88)" fill="rgba(186,224,255,.78)" stroke="#fff" stroke-width="' + stroke + '"/>'
        + '<rect x="168" y="30" width="44" height="58" rx="2" transform="rotate(16 168 88)" fill="rgba(186,224,255,.70)" stroke="#fff" stroke-width="' + stroke + '"/>'
        + '<rect x="176" y="32" width="44" height="56" rx="2" transform="rotate(16 176 88)" fill="rgba(214,236,255,.82)" stroke="#fff" stroke-width="' + stroke + '"/>';
    const lastPane = (stroke) => '<rect x="186" y="34" width="44" height="54" rx="2" transform="rotate(16 186 88)" fill="rgba(160,208,250,.72)" stroke="#fff" stroke-width="' + stroke + '"/>';
    const ART = {
        truck: '<svg class="art" width="124" height="58" viewBox="0 0 300 140" aria-hidden="true">'
            + '<ellipse cx="150" cy="128" rx="132" ry="8" fill="rgba(0,20,60,.28)"/>'
            + '<path d="M284 30 v14 M277 37 h14" fill="none" stroke="#f5a54f" stroke-width="5" stroke-linecap="round"/>'
            + panes(2.5, 6) + lastPane(2.5) + TRUCK_BODY + '</svg>',
        receipt: '<svg class="art" width="112" height="64" viewBox="0 0 112 64" aria-hidden="true">'
            + '<ellipse cx="54" cy="61" rx="46" ry="3.5" fill="rgba(0,20,60,.28)"/>'
            + '<path d="M100 8 v10 M95 13 h10" fill="none" stroke="#f5a54f" stroke-width="2.4" stroke-linecap="round"/><circle cx="10" cy="20" r="3" fill="#8ec4f0"/>'
            + '<rect x="40" y="6" width="44" height="50" rx="4" transform="rotate(10 62 31)" fill="#d6ecff"/>'
            + '<path d="M30 4 H70 Q74 4 74 8 V56 L68 52 L62 56 L56 52 L50 56 L44 52 L38 56 L32 52 L26 56 V8 Q26 4 30 4 Z" fill="#ffffff"/>'
            + '<rect x="33" y="13" width="24" height="3.5" rx="1.75" fill="#c7d9ea"/><rect x="33" y="21" width="32" height="3.5" rx="1.75" fill="#c7d9ea"/>'
            + '<rect x="33" y="29" width="20" height="3.5" rx="1.75" fill="#c7d9ea"/><rect x="33" y="39" width="34" height="5" rx="2.5" fill="#0878d1"/>'
            + '<circle cx="76" cy="46" r="11" fill="#18a66a" stroke="#fff" stroke-width="2"/>'
            + '<path d="M71 46.2 l3.4 3.4 l6 -6.4" fill="none" stroke="#fff" stroke-width="2.4" stroke-linecap="round" stroke-linejoin="round"/></svg>',
        cash: '<svg class="art" width="118" height="64" viewBox="0 0 118 64" aria-hidden="true">'
            + '<ellipse cx="60" cy="61" rx="50" ry="3.5" fill="rgba(0,20,60,.28)"/>'
            + '<path d="M14 6 v10 M9 11 h10" fill="none" stroke="#f5a54f" stroke-width="2.4" stroke-linecap="round"/><circle cx="112" cy="14" r="3" fill="#8ec4f0"/>'
            + '<rect x="10" y="22" width="68" height="34" rx="4" transform="rotate(-9 44 39)" fill="#a8dfc4"/>'
            + '<rect x="18" y="18" width="68" height="34" rx="4" fill="#ddf6ea" stroke="#18a66a" stroke-width="1.6"/>'
            + '<rect x="23" y="23" width="58" height="24" rx="2.5" fill="none" stroke="#18a66a" stroke-width="1" opacity=".55"/>'
            + '<circle cx="52" cy="35" r="7" fill="none" stroke="#0e8553" stroke-width="1.8"/><path d="M52 31.5 v7" fill="none" stroke="#0e8553" stroke-width="1.8" stroke-linecap="round"/>'
            + '<circle cx="29" cy="35" r="2.2" fill="#18a66a"/><circle cx="75" cy="35" r="2.2" fill="#18a66a"/>'
            + '<rect x="86" y="44" width="26" height="12" fill="#e08a24"/><ellipse cx="99" cy="56" rx="13" ry="4" fill="#c97a1a"/><ellipse cx="99" cy="44" rx="13" ry="4" fill="#ffc56e"/>'
            + '<rect x="90" y="33" width="26" height="9" fill="#e08a24"/><ellipse cx="103" cy="42" rx="13" ry="4" fill="#c97a1a"/><ellipse cx="103" cy="33" rx="13" ry="4" fill="#ffd27f"/></svg>',
        breakage: '<svg class="art" width="112" height="64" viewBox="0 0 112 64" aria-hidden="true">'
            + '<ellipse cx="56" cy="61" rx="46" ry="3.5" fill="rgba(0,20,60,.28)"/>'
            + '<path d="M102 8 v10 M97 13 h10" fill="none" stroke="#f5a54f" stroke-width="2.4" stroke-linecap="round"/><circle cx="10" cy="22" r="3" fill="#8ec4f0"/>'
            + '<rect x="30" y="7" width="46" height="50" rx="2" transform="rotate(-8 53 32)" fill="rgba(186,224,255,.85)" stroke="#fff" stroke-width="1.6"/>'
            + '<path d="M52 29 L43 16 M52 29 L63 18 M52 29 L67 37 M52 29 L45 47 M52 29 L36 33" transform="rotate(-8 53 32)" fill="none" stroke="#fff" stroke-width="1.6" stroke-linecap="round"/>'
            + '<circle cx="52" cy="29" r="4.5" transform="rotate(-8 53 32)" fill="none" stroke="#fff" stroke-width="1.4"/>'
            + '<path d="M68 57 l6 -9 l6 9 z" fill="#d6ecff" stroke="#fff" stroke-width="1"/><path d="M20 57 l4 -6 l6 6 z" fill="#d6ecff" stroke="#fff" stroke-width="1"/>'
            + '<circle cx="80" cy="42" r="11" fill="#f28c28" stroke="#fff" stroke-width="2"/>'
            + '<path d="M80 36.5 v6.5" fill="none" stroke="#fff" stroke-width="2.6" stroke-linecap="round"/><circle cx="80" cy="47.4" r="1.6" fill="#fff"/></svg>'
    };
    /** The sign-in screen's truck with its badge. */
    function heroArt(badge) {
        return '<svg class="hero-art" width="300" height="140" viewBox="0 0 300 140" role="img" aria-label="">'
            + '<ellipse cx="150" cy="128" rx="132" ry="7" fill="rgba(0,20,60,.28)"/>'
            + '<path d="M30 34 v12 M24 40 h12" fill="none" stroke="#f5a54f" stroke-width="2.6" stroke-linecap="round"/>'
            + '<path d="M284 62 v10 M279 67 h10" fill="none" stroke="#fff" stroke-width="2.4" stroke-linecap="round"/>'
            + '<circle cx="112" cy="22" r="4" fill="#8ec4f0"/><circle cx="18" cy="92" r="3" fill="#f5a54f"/>'
            + panes(1.4, 3)
            + '<path d="M190 44 l10 -10 M196 54 l14 -14" transform="rotate(16 176 88)" fill="none" stroke="#fff" stroke-width="2" stroke-linecap="round" opacity=".9"/>'
            + lastPane(1.4) + TRUCK_BODY + '<path d="M70 64 L70 84" fill="none" stroke="#fff" stroke-width="2"/>'
            + '<rect x="214" y="0" width="82" height="26" rx="13" fill="#fff"/><circle cx="229" cy="13" r="7.5" fill="#18a66a"/>'
            + '<path d="M225.5 13.2 l2.4 2.4 l4.6 -4.8" fill="none" stroke="#fff" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"/>'
            + '<text x="242" y="17.5" font-family="Inter, Segoe UI, sans-serif" font-size="12" font-weight="700" fill="#0d2f65">' + esc(badge) + '</text></svg>';
    }
    const WAVE = '<svg class="wave" viewBox="0 0 390 40" preserveAspectRatio="none" aria-hidden="true"><path d="M0 24 C 70 0, 140 2, 210 18 C 280 34, 340 34, 390 12 L 390 40 L 0 40 Z"/></svg>';
    const WAVE_HERO = '<svg class="wave" viewBox="0 0 390 44" preserveAspectRatio="none" aria-hidden="true"><path d="M0 26 C 64 2, 132 0, 204 20 C 276 40, 338 40, 390 16 L 390 44 L 0 44 Z"/></svg>';
    const LOGO_TILE = '<span class="logo-tile"><img src="/m/logo-mark.png" alt=""></span>';

    function dateTime(iso) {
        const d = new Date(iso);
        const p = (n) => String(n).padStart(2, '0');
        return p(d.getDate()) + '/' + p(d.getMonth() + 1) + '/' + d.getFullYear() + ' ' + p(d.getHours()) + ':' + p(d.getMinutes());
    }
    function badge(sale) {
        return '<span class="badge ' + sale.status + '">' + esc(t('mobile.status.' + sale.status)) + '</span>';
    }
    const TONES = { pending: 'warn-tone', accepted: 'info-tone', signed: 'ok-tone', conflict: 'danger-tone' };
    /** A note with its icon; a code it starts with in mono ("U-WH-000002 added to the sale"). */
    function noteHtml(kind, textValue, role) {
        const lead = /^[A-Z]+-[A-Z]+-[0-9-]+/.exec(textValue || '');
        const body = lead ? '<span class="code">' + esc(lead[0]) + '</span>' + esc(textValue.slice(lead[0].length)) : esc(textValue);
        const mark = kind === 'ok' ? '<span class="mark">' + icon(ICONS.check) + '</span>'
            : ic(kind === 'warn' ? ICONS.alert : kind === 'danger' ? ICONS.xCircle : ICONS.info);
        return '<div class="note ' + kind + '" role="' + (role || 'status') + '">' + mark + '<span>' + body + '</span></div>';
    }
    function noticeHtml() {
        return state.notice ? noteHtml(state.notice.kind, state.notice.text) : '';
    }
    const amountHtml = (value, size) => '<div class="amount ' + (size || 'big') + '">' + value + '<small>RWF</small></div>';   // no space: a mono space is wide
    const tile = (label, value, kind) => '<div class="tile' + (kind ? ' ' + kind : '') + '"><span>' + esc(label) + '</span><b>' + esc(value) + '</b></div>';

    /** The header: Back or the logo tile, the titles, the menu; the network pill and the screen's illustration; the wave.
     *  o: { title, sub (escaped already), eyebrow, back, art, monoTitle, monoSub } */
    function header(o) {
        const lead = o.back ? '<button class="hdr-btn" data-action="go" data-view="' + o.back + '" aria-label="' + esc(t('mobile.back')) + '">' + icon(ICONS.back) + '</button>' : LOGO_TILE;
        const titles = '<div class="hdr-titles">' + (o.eyebrow ? '<span class="eyebrow">' + esc(o.eyebrow) + '</span>' : '')
            + '<h1' + (o.monoTitle ? ' class="mono"' : '') + '>' + esc(o.title) + '</h1>'
            + (o.sub ? '<div class="sub' + (o.monoSub ? ' mono' : '') + '">' + o.sub + '</div>' : '') + '</div>';
        const menu = state.session ? '<button class="hdr-btn" data-action="menu" aria-label="' + esc(t('mobile.menu')) + '">' + icon(ICONS.menu) + '</button>' : '';
        return '<header class="hdr"><div class="hdr-row' + (o.eyebrow ? ' top' : '') + '">' + lead + titles + menu + '</div>'
            + '<div class="hdr-foot">' + (state.session ? netPill() : '<span></span>') + (o.art ? ART[o.art] : '') + '</div>' + WAVE + '</header>';
    }
    /** "Online · everything synced" / "Offline · 3 waiting to sync" (SYNC-02) as a pill; tapping it sends what waits. */
    function netPill() {
        const waiting = pendingCount();
        const online = navigator.onLine;
        const kind = !online ? 'offline' : waiting ? 'pending' : 'online';
        const label = !online ? (waiting ? t('mobile.net.offlinePending', waiting) : t('mobile.net.offline'))
            : state.syncing ? t('mobile.net.syncing', waiting)
            : waiting ? t('mobile.net.pending', waiting) : t('mobile.net.online');
        const canSync = online && waiting && !state.syncing;
        const inner = '<span class="dot"></span><span>' + esc(label) + (canSync ? ' <span class="act">' + esc(t('mobile.net.syncNow')) + '</span>' : '') + '</span>';
        return canSync ? '<button class="pill ' + kind + '" data-action="sync" aria-live="polite">' + inner + '</button>'
            : '<span class="pill ' + kind + '" role="status">' + inner + '</span>';
    }

    /** A field of the sign-in screen: its label, an underline with an icon, an optional hint. */
    function uf(id, label, path, control, hint) {
        return '<div class="field"><label for="' + id + '">' + esc(label) + '</label><div class="uf">' + ic(path) + control + '</div>'
            + (hint ? '<span class="hint">' + esc(hint) + '</span>' : '') + '</div>';
    }
    function loginView() {
        const e = state.errors.login;
        return '<header class="hdr hero"><div class="hero-inner">'
            + '<div class="brand">' + LOGO_TILE + '<div><b>' + esc(t('mobile.brand').toUpperCase()) + '</b><span>' + esc(t('mobile.brand.motto').toUpperCase()) + '</span></div></div>'
            + heroArt(t('mobile.login.heroBadge'))
            + '<div><h1>' + esc(t('mobile.login.title')) + '</h1><p>' + esc(t('mobile.login.subtitle')) + '</p></div>'
            + '</div>' + WAVE_HERO + '</header>'
            + '<main class="screen form">' + noticeHtml()
            + '<form class="login" data-form="login" autocomplete="on" novalidate style="display:flex;flex-direction:column;gap:14px">'
            + uf('username', t('mobile.login.username'), ICONS.user, '<input id="username" name="username" autocomplete="username" autocapitalize="none" spellcheck="false" required placeholder="' + esc(t('mobile.login.usernamePh')) + '">')
            + uf('password', t('mobile.login.password'), ICONS.lock, '<input id="password" name="password" type="password" autocomplete="current-password" required placeholder="' + esc(t('mobile.login.passwordPh')) + '">'
                + '<button type="button" class="eye" data-action="pw-toggle" aria-label="' + esc(t('mobile.login.showPassword')) + '">' + ic(ICONS.eye) + '</button>')
            + uf('deviceName', t('mobile.login.deviceName'), ICONS.phone, '<input id="deviceName" name="deviceName" maxlength="60" placeholder="' + esc(t('mobile.login.deviceNamePh')) + '">', t('mobile.login.deviceHint'))
            + uf('lang', t('mobile.language'), ICONS.globe, languageSelect() + ic(ICONS.chevronDown, 'chev'))
            + (e ? '<p class="field error" role="alert">' + esc(e) + '</p>' : '')
            + '<button class="btn btn-primary btn-block" type="submit" style="margin-top:8px"' + (state.busy ? ' disabled' : '') + '>'
            + esc(state.busy ? t('mobile.wait') : t('mobile.login.submit')) + '</button>'
            + '</form></main>';
    }
    function languageSelect() {
        return '<select id="lang" data-action="lang">' + LANGUAGES.map((l) =>
            '<option value="' + l + '"' + (l === state.lang ? ' selected' : '') + '>' + esc(t('mobile.language.' + l)) + '</option>').join('') + '</select>';
    }

    function noTripView() {
        return header({ eyebrow: t('mobile.brand'), title: t('mobile.trip.title'), sub: esc(state.session.user.fullName), art: 'truck' })
            + '<main class="screen">' + noticeHtml()
            + '<div class="card roomy"><div class="lead"><span class="icon-circle info-tone">' + ic(ICONS.truck) + '</span><p>' + esc(t('mobile.trip.hint')) + '</p></div>'
            + '<button class="btn btn-primary btn-big btn-block" data-action="download"' + (state.busy ? ' disabled' : '') + '>'
            + esc(state.busy ? t('mobile.wait') : t('mobile.trip.download')) + '</button></div></main>';
    }

    function sellView() {
        const b = state.bundle;
        const customer = customerOf(state.cart.customerId);
        const term = state.search.trim().toUpperCase();
        const onBoard = unitsOnBoard();
        const units = onBoard.filter((u) => !term || u.code.includes(term) || u.productName.toUpperCase().includes(term) || u.productCode.includes(term));
        const p = priced();
        const rows = units.map((u) => {
            const price = priceFor(customer, u);
            const on = inCart(u.id);
            return '<button class="item' + (on ? ' in-cart' : '') + '" data-action="toggle" data-unit="' + u.id + '" aria-pressed="' + on + '">'
                + '<span class="check">' + (on ? icon(ICONS.check) : '') + '</span>'
                + '<span class="grow"><span class="code">' + esc(u.code) + '</span><span class="what">' + esc(u.productName) + ' · '
                + u.widthMm + ' × ' + u.heightMm + ' mm</span></span>'
                + (price ? '<span class="price">' + money(lineAmount(price.price, u, price.list)) + '<small>RWF</small></span>'
                    : '<span class="what">' + esc(t('mobile.sell.noPriceShort')) + '</span>') + '</button>';
        }).join('');
        return header({ eyebrow: t('mobile.sell.trip'), title: b.trip.number, monoTitle: true, sub: esc(b.trip.vehicle.plate + ' · ' + b.trip.area), back: 'day', art: 'truck' })
            + '<main class="screen">' + noticeHtml()
            + '<form class="card" data-form="scan" novalidate><label class="section-title" for="scan-code">' + esc(t('mobile.sell.scanTitle')) + '</label>'
            + '<div class="scan-row"><input class="input mono" id="scan-code" name="code" autocomplete="off" autocapitalize="characters" spellcheck="false" enterkeyhint="go"'
            + ' placeholder="' + esc(t('mobile.sell.scanPh')) + '">'
            + '<button class="btn btn-primary" type="submit">' + esc(t('mobile.sell.add')) + '</button>'
            + ('BarcodeDetector' in window ? '<button class="icon-btn" type="button" data-action="camera" aria-label="' + esc(t('mobile.camera.open')) + '">' + icon(ICONS.scan) + '</button>' : '')
            + '</div>'
            + (state.camera ? '<div class="camera"><video id="camera-video" playsinline muted></video>'
                + '<button class="btn btn-outline" type="button" data-action="camera-stop">' + esc(t('mobile.camera.close')) + '</button></div>' : '')
            + '</form>'
            + '<div class="board-head"><h2 class="section-title">' + esc(t('mobile.sell.onBoard')) + ' <span class="count-chip">' + onBoard.length + '</span></h2>'
            + '<span class="hint">' + esc(t('mobile.sell.tapHint')) + '</span></div>'
            + '<label class="search">' + ic(ICONS.search) + '<input id="search" type="search" data-action="search" value="' + esc(state.search) + '"'
            + ' aria-label="' + esc(t('mobile.sell.searchPh')) + '" placeholder="' + esc(t('mobile.sell.searchPh')) + '"></label>'
            + '<div class="list">' + (rows || '<div class="empty">' + esc(onBoard.length ? t('mobile.sell.noMatch') : t('mobile.sell.nothingLeft')) + '</div>') + '</div>'
            + '</main>'
            + (p.lines.length ? cartBar(t('mobile.sell.inCart', p.lines.length), money(p.total), '<button class="btn btn-primary" data-action="go" data-view="cart">' + esc(t('mobile.sell.pay')) + '</button>') : '');
    }
    function cartBar(count, amount, button) {
        return '<div class="cartbar"><div class="cartbar-inner"><div class="sum"><div class="count">' + esc(count) + '</div>'
            + '<div class="amount">' + amount + '<small>RWF</small></div></div>' + button + '</div></div>';
    }

    function cartView() {
        const p = priced();
        const e = state.errors;
        const options = customers().map((c) => '<option value="' + c.id + '"' + (p.customer && c.id === p.customer.id ? ' selected' : '') + '>'
            + esc(c.name + (c.code ? ' (' + c.code + ')' : '')) + '</option>').join('');
        const lines = p.lines.map((x) => {
            const id = x.unit.id;
            return '<div class="line"><div class="line-head"><div class="grow"><div class="code mono strong">' + esc(x.unit.code) + '</div>'
                + '<div class="muted" style="font-size:13px">' + esc(x.unit.productName) + ' · ' + x.unit.widthMm + ' × ' + x.unit.heightMm + ' mm</div></div>'
                + '<button class="icon-btn" data-action="remove" data-unit="' + id + '" aria-label="' + esc(t('mobile.pay.remove')) + '">' + icon(ICONS.close) + '</button></div>'
                + '<div class="row"><div class="field"><label for="price-' + id + '">' + esc(t('mobile.pay.price')) + '</label>'
                + '<input class="input' + (e['price-' + id] ? ' is-invalid' : '') + '" id="price-' + id + '" inputmode="decimal" data-action="price" data-unit="' + id + '" value="' + esc(x.line.pricePerM2) + '">'
                + (x.listPrice ? '<span class="hint">' + esc(t('mobile.pay.listPrice', grouped(x.listPrice, 2))) + '</span>' : '')
                + (e['price-' + id] ? '<span class="error">' + esc(e['price-' + id]) + '</span>' : '') + '</div>'
                + '<div class="field"><label>' + esc(t('mobile.pay.amount')) + '</label><div class="shown">' + (x.priceOk ? money(x.amount) : '—') + '</div></div></div>'
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
        const vat = grouped(text(vatOf(p.lines.filter((x) => x.priceOk).map((x) => ({ taxCode: x.unit.taxCode, vatRate: x.unit.vatRate, amount: text(x.amount, state.bundle.decimals) }))), 2), 2);
        return header({ title: t('mobile.pay.title'), sub: esc(state.bundle.trip.number), monoSub: true, back: 'sell', art: 'cash' })
            + '<main class="screen">' + noticeHtml()
            + (e.cart ? noteHtml('danger', e.cart, 'alert') : '')
            + '<div class="card"><h3>' + esc(t('mobile.pay.customer')) + '</h3>'
            + '<div class="field"><label for="customer">' + esc(t('mobile.pay.customer')) + '</label><select class="select" id="customer" data-action="customer">' + options + '</select>'
            + '<span class="hint">' + esc(t('mobile.pay.customerHint')) + '</span></div>'
            + '<div class="field"><label for="buyerName">' + esc(t('mobile.pay.buyerName')) + '</label><input class="input" id="buyerName" maxlength="100" data-action="cart-field" data-field="buyerName" value="' + esc(state.cart.buyerName) + '"></div>'
            + '<div class="field"><label for="buyerTin">' + esc(t('mobile.pay.buyerTin')) + '</label><input class="input' + (e.tin ? ' is-invalid' : '') + '" id="buyerTin" inputmode="numeric" maxlength="11" data-action="cart-field" data-field="buyerTin" value="' + esc(state.cart.buyerTin) + '">'
            + (e.tin ? '<span class="error">' + esc(e.tin) + '</span>' : '<span class="hint">' + esc(t('mobile.pay.buyerTinHint')) + '</span>') + '</div></div>'
            + '<div class="list">' + (lines || '<div class="empty">' + esc(t('mobile.pay.empty')) + '</div>') + '</div>'
            + '<div class="card"><div class="totals"><div class="t-row total"><span>' + esc(t('mobile.pay.total')) + '</span><span>' + money(p.total) + ' RWF</span></div>'
            + '<div class="t-row muted"><span>' + esc(t('mobile.pay.vatIncluded')) + '</span><span>' + vat + '</span></div></div></div>'
            + '<div class="card"><h3>' + esc(t('mobile.pay.payment')) + '</h3>'
            + '<div class="btn-group"><button class="btn btn-outline" type="button" data-action="all-cash">' + esc(t('mobile.pay.allCash')) + '</button>'
            + '<button class="btn btn-outline" type="button" data-action="all-momo">' + esc(t('mobile.pay.allMomo')) + '</button></div>'
            + '<div class="row"><div class="field"><label for="cash">' + esc(t('mobile.pay.cash')) + '</label><input class="input" id="cash" inputmode="decimal" data-action="cart-field" data-field="cash" value="' + esc(cash) + '"></div>'
            + '<div class="field"><label for="tendered">' + esc(t('mobile.pay.tendered')) + '</label><input class="input' + (e.tendered ? ' is-invalid' : '') + '" id="tendered" inputmode="decimal" data-action="cart-field" data-field="tendered" value="' + esc(state.cart.tendered) + '"></div></div>'
            + (e.tendered ? '<p class="field error">' + esc(e.tendered) + '</p>' : change ? '<p class="field hint strong">' + esc(change) + '</p>' : '')
            + '<div class="row"><div class="field"><label for="momo">' + esc(t('mobile.pay.momo')) + '</label><input class="input" id="momo" inputmode="decimal" data-action="cart-field" data-field="momo" value="' + esc(state.cart.momo) + '"></div>'
            + '<div class="field"><label for="momoRef">' + esc(t('mobile.pay.momoRefLabel')) + '</label><input class="input' + (e.momoRef ? ' is-invalid' : '') + '" id="momoRef" maxlength="60" autocapitalize="characters" data-action="cart-field" data-field="momoRef" value="' + esc(state.cart.momoRef) + '"></div></div>'
            + (e.momoRef ? '<p class="field error">' + esc(e.momoRef) + '</p>' : '')
            + (e.pay ? '<p class="field error" role="alert">' + esc(e.pay) + '</p>' : '')
            + '</div></main>'
            + cartBar(t('mobile.sell.inCart', p.lines.length), money(p.total),
                '<button class="btn btn-primary" data-action="complete"' + (p.lines.length ? '' : ' disabled') + '>' + esc(t('mobile.pay.complete')) + '</button>');
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
        // A mobile money reference on its own line: it is long
        const pays = sale.payments.map((p) => '<div class="r-row"><span>' + esc(t('mobile.method.' + p.method)) + '</span><span>' + money(scaled(p.amount, b.decimals)) + '</span></div>'
            + (p.reference ? '<div>&nbsp;&nbsp;' + esc(p.reference) + '</div>' : '')).join('');
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
            + (co.logo ? '<img class="r-logo" src="' + esc(co.logo) + '" alt="">' : '')
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
        const status = sale.status === 'conflict' ? noteHtml('danger', t('mobile.receipt.conflictNote', r && r.conflict ? r.conflict.reasonText : ''))
            : sale.status === 'pending' ? noteHtml('warn', t('mobile.receipt.pendingNote'))
            : sale.status === 'accepted' ? noteHtml('info', t('mobile.receipt.acceptedNote'))
            : noteHtml('ok', t('mobile.receipt.signedNote'));
        const text = encodeURIComponent(receiptText(sale));
        return header({ title: sale.number, monoTitle: true, sub: esc(dateTime(sale.createdAt)), back: 'sales', art: 'receipt' })
            + '<main class="screen">' + noticeHtml() + status + receiptHtml(sale)
            + '<div class="btn-group"><button class="btn btn-outline" data-action="print">' + icon(ICONS.printer) + esc(t('mobile.receipt.print')) + '</button>'
            + '<a class="btn btn-outline" href="sms:?&body=' + text + '">' + icon(ICONS.sms) + esc(t('mobile.receipt.sms')) + '</a>'
            + '<a class="btn btn-outline" href="https://wa.me/?text=' + text + '" target="_blank" rel="noopener">' + icon(ICONS.share) + esc(t('mobile.receipt.whatsapp')) + '</a></div>'
            + '<button class="btn btn-primary btn-big btn-block" data-action="go" data-view="sell">' + esc(t('mobile.receipt.newSale')) + '</button>'
            + '</main>';
    }

    function salesView() {
        const d = state.bundle.decimals;
        const rows = state.sales.slice().reverse().map((s) => '<button class="item" data-action="receipt" data-sale="' + s.clientId + '">'
            + '<span class="tile-ic ' + (TONES[s.status] || 'info-tone') + '">' + ic(ICONS.receipt) + '</span>'
            + '<span class="grow"><span class="code" style="font-size:13px">' + esc(s.number) + '</span><span class="what">' + esc(dateTime(s.createdAt)) + ' · '
            + esc(tn('mobile.sales.units', s.lines.length, s.lines.length)) + '</span></span>'
            + '<span class="price end">' + money(scaled(s.total, d)) + badge(s) + '</span></button>').join('');
        const total = state.sales.filter((s) => s.status !== 'conflict').reduce((sum, s) => sum + scaled(s.total, d), 0n);
        const waiting = pending().length;
        return header({ title: t('mobile.sales.title'), sub: esc(state.bundle.trip.number), monoSub: true, back: 'day', art: 'receipt' })
            + '<main class="screen">' + noticeHtml()
            + '<div class="card" style="padding:18px;gap:14px"><div><div class="eyebrow-label">' + esc(t('mobile.sales.totalPhone')) + '</div>' + amountHtml(money(total)) + '</div>'
            + '<div class="tiles">' + tile(t('mobile.day.sales'), String(state.sales.length)) + tile(t('mobile.sales.pending'), String(waiting), waiting ? 'warn' : '') + '</div></div>'
            + '<h2 class="section-title">' + esc(t('mobile.sales.count')) + '</h2>'
            + '<div class="list">' + (rows || '<div class="empty">' + esc(t('mobile.sales.none')) + '</div>') + '</div></main>';
    }

    /** Where a breakage report stands: waiting to sync, sent (the supervisor decides), accepted, rejected or refused. */
    function breakageBadge(b) {
        const status = b.status === 'pending' ? 'pending' : b.status === 'refused' ? 'refused'
            : b.result && b.result.status === 'ACCEPTED' ? 'accepted' : b.result && b.result.status === 'REJECTED' ? 'rejected' : 'sent';
        const css = { pending: 'pending', sent: 'accepted', accepted: 'signed', rejected: 'conflict', refused: 'conflict' }[status];
        return '<span class="badge ' + css + '">' + esc(t('mobile.breakage.status.' + status)) + '</span>';
    }
    /** Breakage on the road (MPOS-06): the unit, why, where, a photo; the phone keeps it until it syncs. */
    function breakageView() {
        const f = state.breakage;
        const e = state.errors;
        const units = unitsOnBoard();
        const options = '<option value="">' + esc(t('mobile.breakage.unitPick')) + '</option>' + units.map((u) => '<option value="' + u.id + '"'
            + (u.id === f.unitId ? ' selected' : '') + '>' + esc(u.code) + '</option>').join('');
        const chosen = units.find((u) => u.id === f.unitId);
        const rows = state.breakages.slice().reverse().map((b) => '<div class="item static"><div class="grow"><div class="code">' + esc(b.code) + '</div>'
            + '<div class="what">' + esc(b.reason) + ' · ' + esc(b.place) + '</div>'
            + (b.status === 'refused' && b.error ? '<div class="what error">' + esc(b.error) + '</div>' : '')
            + (b.result && b.result.decisionNote ? '<div class="what">' + esc(b.result.decisionNote) + '</div>' : '')
            + '</div><div>' + breakageBadge(b) + '</div></div>').join('');
        return header({ title: t('mobile.breakage.title'), sub: esc(state.bundle.trip.number), monoSub: true, back: 'day', art: 'breakage' })
            + '<main class="screen">' + noticeHtml()
            + '<form class="card" data-form="breakage" novalidate><p class="muted" style="font-size:14px">' + esc(t('mobile.breakage.hint')) + '</p>'
            + '<div class="field"><label for="b-unit">' + esc(t('mobile.breakage.unit')) + '</label><select class="select" id="b-unit" data-action="b-unit">' + options + '</select>'
            + (chosen ? '<span class="hint">' + esc(chosen.productName + ' · ' + chosen.widthMm + ' × ' + chosen.heightMm + ' mm') + '</span>' : '')
            + (e.unit ? '<span class="error">' + esc(e.unit) + '</span>' : '') + '</div>'
            + '<div class="field"><label for="b-reason">' + esc(t('mobile.breakage.reason')) + '</label><input class="input' + (e.reason ? ' is-invalid' : '') + '" id="b-reason" maxlength="255" data-action="b-field" data-field="reason" value="' + esc(f.reason) + '" placeholder="' + esc(t('mobile.breakage.reasonPh')) + '">'
            + (e.reason ? '<span class="error">' + esc(e.reason) + '</span>' : '') + '</div>'
            + '<div class="field"><label for="b-place">' + esc(t('mobile.breakage.place')) + '</label><input class="input' + (e.place ? ' is-invalid' : '') + '" id="b-place" maxlength="255" data-action="b-field" data-field="place" value="' + esc(f.place) + '" placeholder="' + esc(t('mobile.breakage.placePh')) + '">'
            + '<button class="btn btn-outline btn-block" type="button" data-action="b-position">' + icon(ICONS.target) + esc(t('mobile.breakage.position')) + '</button>'
            + (e.place ? '<span class="error">' + esc(e.place) + '</span>' : '') + '</div>'
            + '<div class="field"><span class="label">' + esc(t('mobile.breakage.photo')) + '</span>'
            + (f.photo ? '<img class="photo-preview" src="' + f.photo + '" alt="' + esc(t('mobile.breakage.photo')) + '">'
                + '<button class="btn btn-outline btn-block" type="button" data-action="b-photo-clear">' + esc(t('mobile.breakage.photoAgain')) + '</button>'
                : '<label class="btn btn-outline btn-block" for="b-photo">' + icon(ICONS.camera) + esc(t('mobile.breakage.photoTake')) + '</label>')
            + '<input class="file-input" type="file" id="b-photo" accept="image/*" capture="environment" data-action="b-photo">'
            + (e.photo ? '<span class="error">' + esc(e.photo) + '</span>' : '') + '</div>'
            + '<button class="btn btn-primary btn-block" type="submit">' + esc(t('mobile.breakage.save')) + '</button>'
            + '</form>'
            + '<h2 class="section-title">' + esc(t('mobile.breakage.list')) + '</h2>'
            + '<div class="list">' + (rows || '<div class="empty">' + esc(t('mobile.breakage.none')) + '</div>') + '</div>'
            + '</main>';
    }
    /** "45920" as "45,920" in the cash field, as it is typed. */
    const groupDigits = (digits) => String(digits || '').replace(/\D/g, '').replace(/\B(?=(\d{3})+(?!\d))/g, ',');
    /** The cash the driver declares at the end of the day (MPOS-07): once, with network; the amount on the button. */
    function declareView() {
        const b = state.bundle;
        const e = state.errors;
        const done = b.cashDeclared != null;
        const typed = state.declareAmount.trim();
        const valid = /^\d+$/.test(typed);
        const body = done
            ? noteHtml('ok', t('mobile.declare.done', money(scaled(b.cashDeclared, b.decimals)), b.cashDeclaredAt ? dateTime(b.cashDeclaredAt) : ''))
            : '<form class="card roomy" data-form="declare" novalidate>'
                + '<div class="lead"><span class="icon-circle ok-tone">' + ic(ICONS.cash) + '</span><p>' + esc(t('mobile.declare.hintCount')) + '</p></div>'
                + '<div class="field"><label for="declare-amount" style="font-size:14px">' + esc(t('mobile.declare.cashLabel')) + '</label>'
                + '<div class="cash-field' + (e.declare ? ' is-invalid' : '') + '"><input id="declare-amount" inputmode="numeric" autocomplete="off" data-action="declare-field" value="' + esc(groupDigits(state.declareAmount)) + '"'
                + ' aria-label="' + esc(t('mobile.declare.amount')) + '"><span>RWF</span></div>'
                + (e.declare ? '<span class="error">' + esc(e.declare) + '</span>' : '') + '</div>'
                + noteHtml('info', t('mobile.declare.once'))
                + (pendingCount() ? noteHtml('warn', tn('mobile.declare.pending', pendingCount(), pendingCount())) : '')
                + '<button class="btn btn-primary btn-big btn-block" type="button" data-action="ask-declare"' + (state.busy ? ' disabled' : '') + '>'
                + esc(state.busy ? t('mobile.wait') : valid ? t('mobile.declare.saveAmount', money(scaled(typed, b.decimals))) : t('mobile.declare.save')) + '</button></form>';
        return header({ title: t('mobile.declare.title'), sub: esc(b.trip.number), monoSub: true, back: 'day', art: 'cash' })
            + '<main class="screen">' + noticeHtml() + body + '</main>';
    }

    /** My day: the trip at a glance from what this phone holds, so it works offline. Money leaves out the sales kept for the
     *  supervisor, as the sales list does; the cash is what the sales took, before vehicle costs paid from the float. */
    function daySummary() {
        const b = state.bundle;
        const d = b.decimals;
        const kept = state.sales.filter((s) => s.status !== 'conflict');
        const sum = (values) => values.reduce((acc, v) => acc + v, 0n);
        const paid = (method) => sum(kept.flatMap((s) => s.payments.filter((p) => p.method === method).map((p) => scaled(p.amount, d))));
        return {
            sales: state.sales.length, total: sum(kept.map((s) => scaled(s.total, d))), cash: paid('CASH'), momo: paid('MOBILE_MONEY'),
            conflicts: state.sales.length - kept.length, sold: soldHere().size, broken: reportedBroken().size, onBoard: unitsOnBoard().length,
            signed: kept.filter((s) => s.status === 'signed').length, receipts: kept.length, waiting: pendingCount()
        };
    }
    function dayView() {
        const b = state.bundle;
        const s = daySummary();
        const date = b.trip.date ? b.trip.date.split('-').reverse().join('/') : '';
        const where = [b.trip.area, date].filter(Boolean).join(' · ');
        const cash = b.cashDeclared != null
            ? noteHtml('ok', t('mobile.declare.done', money(scaled(b.cashDeclared, b.decimals)), b.cashDeclaredAt ? dateTime(b.cashDeclaredAt) : ''))
            : '<div class="lead"><span class="icon-circle ok-tone">' + ic(ICONS.cash) + '</span><div><div class="muted" style="font-size:13px">' + esc(t('mobile.day.cashFromSales')) + '</div>'
                + amountHtml(money(s.cash), 'mid') + '</div></div>'
                + '<p class="muted" style="font-size:13px;line-height:19px">' + esc(t('mobile.day.cashHint')) + '</p>'
                + '<button class="btn btn-outline btn-block" data-action="go" data-view="declare">' + icon(ICONS.cash) + esc(t('mobile.menu.declare')) + '</button>';
        return header({ eyebrow: t('mobile.day.title'), title: state.session.user.fullName, sub: esc(b.trip.number + ' · ' + b.trip.vehicle.plate) + (where ? '<br>' + esc(where) : ''), art: 'truck' })
            + '<main class="screen">' + noticeHtml()
            + '<button class="btn btn-primary btn-big btn-block day-sell" data-action="go" data-view="sell">' + esc(t('mobile.menu.sell')) + '</button>'
            + '<div class="card" style="padding:18px;gap:14px"><div><div class="eyebrow-label">' + esc(t('mobile.day.sales')) + '</div>' + amountHtml(money(s.total))
            + '<div class="muted" style="font-size:13px">' + esc(tn('mobile.day.salesCount', s.sales, s.sales)) + '</div></div>'
            + '<div class="tiles">' + tile(t('mobile.method.CASH'), money(s.cash)) + tile(t('mobile.method.MOBILE_MONEY'), money(s.momo)) + '</div>'
            + (s.conflicts ? tile(t('mobile.day.conflicts'), String(s.conflicts), 'danger') : '')
            + '<button class="btn btn-outline btn-block" data-action="go" data-view="sales">' + icon(ICONS.receipt) + esc(t('mobile.menu.sales')) + '</button></div>'
            + '<div class="card"><h3>' + esc(t('mobile.day.glass')) + '</h3><div class="tiles">'
            + tile(t('mobile.day.sold'), String(s.sold), 'ok') + tile(t('mobile.day.broken'), String(s.broken), s.broken ? 'warn' : '') + tile(t('mobile.day.onBoard'), String(s.onBoard)) + '</div>'
            + '<button class="btn btn-outline btn-block" data-action="go" data-view="breakage">' + icon(ICONS.broken) + esc(t('mobile.menu.breakage')) + '</button></div>'
            + '<div class="card"><h3>' + esc(t('mobile.day.receipts')) + '</h3><div class="tiles">'
            + tile(t('mobile.day.signed'), t('mobile.day.ofTotal', s.signed, s.receipts), s.receipts && s.signed === s.receipts ? 'ok' : '')
            + tile(t('mobile.sales.pending'), String(s.waiting), s.waiting ? 'warn' : '') + '</div></div>'
            + '<div class="card"><h3>' + esc(t('mobile.day.cash')) + '</h3>' + cash + '</div>'
            + '</main>';
    }

    function menuHtml() {
        if (!state.menu) return '';
        const items = [
            ['go', 'day', t('mobile.menu.day'), ICONS.home], ['go', 'sell', t('mobile.menu.sell'), ICONS.scan], ['go', 'sales', t('mobile.menu.sales'), ICONS.receipt],
            ['go', 'breakage', t('mobile.menu.breakage'), ICONS.broken], ['go', 'declare', t('mobile.menu.declare'), ICONS.cash],
            ['download', '', t('mobile.menu.refresh'), ICONS.sync], ['lang-toggle', '', t('mobile.menu.language'), ICONS.globe],
            ['ask-close', '', t('mobile.menu.closeTrip'), ICONS.box], ['ask-signout', '', t('mobile.menu.signOut'), ICONS.logout]
        ].filter((i) => state.bundle || !['day', 'sell', 'sales', 'breakage', 'declare'].includes(i[1]) && i[0] !== 'ask-close');
        return '<div class="sheet-backdrop" data-action="menu-close"></div><div class="sheet" role="menu">'
            + items.map((i) => '<button class="item' + (i[0] === 'ask-signout' || i[0] === 'ask-close' ? ' danger' : '') + '" role="menuitem" data-action="' + i[0]
                + '" data-view="' + i[1] + '">' + icon(i[3]) + '<span>' + esc(i[2]) + '</span></button>').join('') + '</div>';
    }
    function confirmHtml() {
        if (!state.confirm) return '';
        return '<div class="sheet-backdrop" data-action="confirm-no"></div><div class="sheet" role="dialog" aria-modal="true" style="padding:16px">'
            + '<p style="margin-bottom:14px;color:var(--heading)">' + esc(state.confirm.text) + '</p><div class="btn-group">'
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
        else if (state.view === 'breakage') html = breakageView();
        else if (state.view === 'declare') html = declareView();
        else if (state.view === 'day') html = dayView();
        else html = sellView();
        app.className = 'app' + (state.session ? '' : ' on-card');   // the sign-in form sits on white, the rest on the page's ground
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
            case 'b-position': return addPosition();
            case 'pw-toggle': {   // in place: a render would empty what the driver typed
                const input = document.getElementById('password');
                const show = input.type === 'password';
                input.type = show ? 'text' : 'password';
                el.setAttribute('aria-label', t(show ? 'mobile.login.hidePassword' : 'mobile.login.showPassword'));
                el.innerHTML = ic(show ? ICONS.eyeOff : ICONS.eye);
                return undefined;
            }
            case 'b-photo-clear': state.breakage.photo = null; return render();
            case 'ask-declare': {
                const value = state.declareAmount.trim();
                if (!/^\d+$/.test(value)) { state.errors = { declare: t('mobile.declare.invalid') }; return render(); }
                state.confirm = { text: t('mobile.declare.confirm', money(scaled(value, state.bundle.decimals))), label: t('mobile.declare.save'), action: declareCash };
                return render();
            }
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
        if (form.dataset.form === 'breakage') return saveBreakage();
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
        if (action === 'b-field') { state.breakage[el.dataset.field] = el.value; delete state.errors[el.dataset.field]; return undefined; }
        // The digits alone; the field shows them grouped and the button the amount, as they are typed
        if (action === 'declare-field') { state.declareAmount = el.value.replace(/\D/g, ''); delete state.errors.declare; return render(); }
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
        if (el.dataset.action === 'b-unit') { state.breakage.unitId = el.value; delete state.errors.unit; return render(); }
        if (el.dataset.action === 'b-photo' && el.files && el.files[0]) {
            try {
                state.breakage.photo = await shrink(el.files[0]);
                delete state.errors.photo;
            } catch (e) {
                state.errors.photo = t('mobile.breakage.photoBad');
            }
            return render();
        }
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
        if (pendingCount() || state.sales.some((s) => s.status === 'accepted')
            || state.breakages.some((b) => b.status === 'sent' && b.result && b.result.status === 'REPORTED')) sync();
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
        state.breakages = await breakagesAll();
        render();
        if (state.session && navigator.onLine) {
            if (state.bundle) downloadTrip().then(sync); else downloadTrip();
        }
    }
    start();
})();
