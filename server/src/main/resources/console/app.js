/*
 * hrafnagud console.
 *
 * Plain fetch and plain DOM, no framework. The console reads the operator API
 * and renders one view per subsystem; a framework would be more code to
 * install than the code it replaces, and this file has to stay readable to
 * whoever is debugging an ingest problem at the time.
 *
 * ONE RULE THAT IS NOT STYLE: every string that came out of the API is
 * publisher-controlled. Article titles, teasers, source names, error
 * messages — all of it arrives from feeds the operator does not own. It goes
 * into the DOM through esc() or textContent, never raw into innerHTML, and
 * every href goes through safeUrl(). A news aggregator that renders a feed's
 * title as HTML is a stored-XSS delivery service with extra steps.
 */
'use strict';

// ── token ───────────────────────────────────────────────────────────────
//
// sessionStorage by default: the token grants full API access including
// DELETE, and the honest default for that is "gone when the tab closes".
// localStorage only on explicit request, which is the checkbox.

const TOKEN_KEY = 'hrafnagud.token';

const tokenStore = {
    get() {
        return sessionStorage.getItem(TOKEN_KEY) || localStorage.getItem(TOKEN_KEY) || '';
    },
    set(value, remember) {
        this.clear();
        (remember ? localStorage : sessionStorage).setItem(TOKEN_KEY, value);
    },
    remembered() {
        return localStorage.getItem(TOKEN_KEY) !== null;
    },
    clear() {
        sessionStorage.removeItem(TOKEN_KEY);
        localStorage.removeItem(TOKEN_KEY);
    },
};

// ── API ─────────────────────────────────────────────────────────────────

const API = '../api/v1';

async function api(path, params, method, body) {
    const url = new URL(API + path, window.location.href);
    for (const [key, value] of Object.entries(params || {})) {
        if (value !== null && value !== undefined && value !== '') {
            url.searchParams.set(key, value);
        }
    }

    const token = tokenStore.get();
    const headers = { Accept: 'application/json' };
    if (token) {
        headers.Authorization = 'Bearer ' + token;
    }
    if (body !== undefined) {
        headers['Content-Type'] = 'application/json';
    }

    let response;
    try {
        response = await fetch(url, {
            method: method || 'GET',
            headers,
            body: body === undefined ? undefined : JSON.stringify(body),
        });
    } catch (e) {
        // A network-level failure is not the same problem as a 4xx and must
        // not be reported as one: the service being down and the token being
        // wrong send an operator to opposite ends of the system.
        setConnection('unerreichbar', 'text-bg-danger');
        throw new Error('Server nicht erreichbar (' + e.message + ')');
    }

    if (response.status === 401) {
        setConnection('Token fehlt oder ist falsch', 'text-bg-warning');
        openTokenDialog();
        throw new Error('401 — die API verlangt einen gültigen Bearer-Token.');
    }
    if (!response.ok) {
        // The API's own errors carry {error, message}; anything else (a
        // proxy, a 500 page) does not, and inventing a message for it would
        // hide what actually answered.
        let detail = '';
        try {
            const body = await response.json();
            detail = body.message || body.error || '';
        } catch (ignored) {
            detail = '';
        }
        throw new Error('HTTP ' + response.status + (detail ? ' — ' + detail : ''));
    }

    setConnection('verbunden', 'text-bg-success');
    return response.json();
}

function setConnection(text, cls) {
    const el = document.getElementById('conn');
    el.textContent = text;
    el.className = 'badge ' + cls;
}

function showError(e) {
    const el = document.getElementById('alert');
    el.textContent = e.message || String(e);
    el.classList.remove('d-none');
}

function clearError() {
    const el = document.getElementById('alert');
    el.classList.add('d-none');
    el.classList.remove('alert-success');
    el.classList.add('alert-danger');
}

/** Same slot as the error, in green: one place where the page talks back. */
function showNote(text) {
    const el = document.getElementById('alert');
    el.textContent = text;
    el.classList.remove('d-none', 'alert-danger');
    el.classList.add('alert-success');
}

// ── rendering helpers ───────────────────────────────────────────────────

/** The only way a foreign string is allowed into an HTML template. */
function esc(value) {
    if (value === null || value === undefined) {
        return '';
    }
    return String(value)
        .replaceAll('&', '&amp;')
        .replaceAll('<', '&lt;')
        .replaceAll('>', '&gt;')
        .replaceAll('"', '&quot;')
        .replaceAll("'", '&#39;');
}

/**
 * An href we are willing to put in the DOM. Feed URLs are foreign input, and
 * `javascript:` in an href is a click away from executing with the token in
 * scope. Anything that is not http(s) becomes no link at all.
 */
function safeUrl(raw) {
    if (!raw) {
        return null;
    }
    try {
        const url = new URL(raw, window.location.href);
        return (url.protocol === 'http:' || url.protocol === 'https:') ? url.href : null;
    } catch (e) {
        return null;
    }
}

function link(raw, label) {
    const href = safeUrl(raw);
    const text = esc(label || raw);
    return href
        ? '<a href="' + esc(href) + '" target="_blank" rel="noopener noreferrer">' + text + '</a>'
        : text;
}

const DATE_FORMAT = new Intl.DateTimeFormat('de-DE', {
    dateStyle: 'short', timeStyle: 'medium',
});

function absolute(iso) {
    return iso ? DATE_FORMAT.format(new Date(iso)) : '—';
}

/** "vor 4 min" — the form that answers "is it still running" at a glance. */
function relative(iso, now) {
    if (!iso) {
        return '—';
    }
    const seconds = Math.round(((now || Date.now()) - new Date(iso).getTime()) / 1000);
    const future = seconds < 0;
    const abs = Math.abs(seconds);
    let text;
    if (abs < 60) {
        text = abs + ' s';
    } else if (abs < 3600) {
        text = Math.round(abs / 60) + ' min';
    } else if (abs < 86400) {
        text = Math.round(abs / 3600) + ' h';
    } else {
        text = Math.round(abs / 86400) + ' d';
    }
    return future ? 'in ' + text : 'vor ' + text;
}

function ago(iso, now) {
    return iso
        ? '<span title="' + esc(absolute(iso)) + '">' + esc(relative(iso, now)) + '</span>'
        : '—';
}

function num(value) {
    return typeof value === 'number' ? value.toLocaleString('de-DE') : '—';
}

const BADGE = {
    OK: 'text-bg-success', NOT_MODIFIED: 'text-bg-secondary',
    FETCH_ERROR: 'text-bg-danger', PARSE_ERROR: 'text-bg-danger',
    FETCHED: 'text-bg-success', DONE: 'text-bg-success',
    PENDING: 'text-bg-secondary', SKIPPED: 'text-bg-secondary',
    PAYWALL: 'text-bg-warning', BLOCKED: 'text-bg-warning',
    FAILED: 'text-bg-danger',
    // Category mappings. GUESSED is deliberately a warning: it looks decided
    // and is not, which is the one state a reader must not skim past.
    NEW: 'text-bg-secondary', GUESSED: 'text-bg-warning',
    RESOLVED: 'text-bg-success', CONFIRMED: 'text-bg-primary',
    NOT_A_TOPIC: 'text-bg-secondary', IS_PLACE: 'text-bg-info',
};

function badge(value) {
    return value
        ? '<span class="badge ' + (BADGE[value] || 'text-bg-secondary') + '">'
            + esc(value) + '</span>'
        : '—';
}

function countTable(table, map, empty) {
    const entries = Object.entries(map || {});
    if (!entries.length) {
        table.innerHTML = '<tbody><tr><td class="text-body-secondary">'
            + esc(empty) + '</td></tr></tbody>';
        return;
    }
    table.innerHTML = '<tbody>' + entries.map(([key, value]) =>
        '<tr><td>' + badge(key) + '</td><td class="text-end">' + esc(num(value))
        + '</td></tr>').join('') + '</tbody>';
}

// ── places ──────────────────────────────────────────────────────────────
//
// The containment table, fetched once. Articles carry ids (m49:142) because a
// display name depends on the reader's language and does not belong on the
// article; turning them back into words is the client's job, and 279 rows is
// cheaper to hold than a lookup per row shown.

const places = new Map();
const fetchProfiles = new Map();

async function loadFetchProfiles() {
    if (fetchProfiles.size) {
        return;
    }
    const byName = await api('/sources/fetch-profiles');
    for (const [name, profile] of Object.entries(byName)) {
        fetchProfiles.set(name, profile);
    }
}

/** "30 min – 12 h", so a name is not the only thing a profile says. */
function profileRange(name) {
    const profile = fetchProfiles.get(name || 'default');
    return profile ? isoDuration(profile.minInterval) + ' – '
            + isoDuration(profile.maxInterval) : '';
}

/** PT30M → "30 min". Enough of ISO-8601 for the two units these use. */
function isoDuration(iso) {
    if (!iso) {
        return '';
    }
    const match = /^PT?(?:(\d+)H)?(?:(\d+)M)?$/.exec(iso);
    if (!match) {
        return iso;
    }
    const hours = Number(match[1] || 0);
    const minutes = Number(match[2] || 0);
    if (hours >= 24 && hours % 24 === 0 && !minutes) {
        return (hours / 24) + ' d';
    }
    if (hours && minutes) {
        return hours + ' h ' + minutes + ' min';
    }
    return hours ? hours + ' h' : minutes + ' min';
}

async function loadPlaces() {
    if (places.size) {
        return;
    }
    for (const place of await api('/places')) {
        places.set(place.id, place);
    }
}

function placeName(id) {
    const place = places.get(id);
    return place ? place.name : id;
}

/** "World › Asia › South-Eastern Asia › Singapore" — the whole containment. */
function placePath(ids) {
    if (!ids || !ids.length) {
        return '—';
    }
    return ids.map(id => esc(placeName(id))).join(' <span class="text-body-secondary">›</span> ');
}

/** The country at the end of the path — what a table column has room for. */
function placeLeaf(ids) {
    return !ids || !ids.length ? '—' : esc(placeName(ids[ids.length - 1]));
}

/**
 * The filter's options, ordered as the hierarchy reads rather than
 * alphabetically: an operator picking "Asia" wants to see its sub-regions
 * under it, not scattered between Africa and Europe.
 */
function fillPlaceFilter(select) {
    if (!select || select.options.length > 1) {
        return;
    }
    const byParent = new Map();
    for (const place of places.values()) {
        const key = place.parentId || '';
        if (!byParent.has(key)) {
            byParent.set(key, []);
        }
        byParent.get(key).push(place);
    }

    const add = (place, depth) => {
        // Countries are the long tail: 249 of them would bury the regions that
        // make this filter worth having, and the source view is where a single
        // country is picked anyway.
        if (place.kind === 'COUNTRY') {
            return;
        }
        const option = document.createElement('option');
        option.value = place.id;
        option.textContent = '\u00a0'.repeat(depth * 3) + place.name;
        select.append(option);
        (byParent.get(place.id) || [])
            .sort((a, b) => a.name.localeCompare(b.name))
            .forEach(child => add(child, depth + 1));
    };
    (byParent.get('') || []).forEach(root => add(root, 0));
}

// ── overview ────────────────────────────────────────────────────────────

let statsTimer = null;

async function loadStats() {
    clearError();
    const stats = await api('/stats');
    // The server's own clock, not the browser's: a laptop with a skewed
    // clock would otherwise produce "letzter Artikel in 3 h".
    const now = stats.serverTime ? new Date(stats.serverTime).getTime() : Date.now();

    document.getElementById('stat-cards').innerHTML = [
        card('Quellen', num(stats.sourcesEnabled) + ' / ' + num(stats.sourcesTotal),
            'aktiv von insgesamt'),
        card('Fehlerhaft', num(stats.sourcesFailing),
            'Quellen mit fehlgeschlagenem Abruf',
            stats.sourcesFailing > 0 ? 'border-danger' : ''),
        card('Artikel', num(stats.articlesTotal), 'insgesamt im Archiv'),
        card('Letzte 24 h', num(stats.articlesLast24h), 'neu erfasst'),
        card('Neuester Artikel', relative(stats.newestArticleAt, now),
            absolute(stats.newestArticleAt)),
        card('Übersetzungs-Rückstand', num(stats.translationBacklog),
            num(stats.enrichmentsTotal) + ' Ergebnisse gespeichert',
            stats.translationBacklog > 0 ? 'border-warning' : ''),
    ].join('');

    countTable(document.getElementById('tbl-languages'), stats.articlesByLanguage,
        'keine Artikel');
    countTable(document.getElementById('tbl-content-status'), stats.articlesByContentStatus,
        'keine Artikel');
    countTable(document.getElementById('tbl-translation-status'),
        stats.articlesByTranslationStatus, 'keine Artikel');

    renderHealth(stats, now);
    document.getElementById('stats-age').textContent =
        'Stand ' + absolute(stats.serverTime);
}

function card(title, value, hint, extraClass) {
    return '<div class="col-6 col-lg-2"><div class="card h-100 ' + (extraClass || '') + '">'
        + '<div class="card-body">'
        + '<div class="text-body-secondary small">' + esc(title) + '</div>'
        + '<div class="fs-4">' + esc(value) + '</div>'
        + '<div class="text-body-secondary small">' + esc(hint) + '</div>'
        + '</div></div></div>';
}

/**
 * The verdict, above the numbers.
 *
 * <p>Reading six figures and deciding whether they are fine is exactly the
 * work this page should be doing instead of the operator. Each line is a
 * question with a known good answer, and links to the rows behind it.
 */
function renderHealth(stats, now) {
    const findings = [];

    const newestAgeMin = stats.newestArticleAt
        ? (now - new Date(stats.newestArticleAt).getTime()) / 60000
        : Infinity;

    if (!stats.sourcesEnabled) {
        findings.push(['danger', 'Keine aktive Quelle — es wird nichts gesammelt.', null]);
    } else if (newestAgeMin > 180) {
        findings.push(['danger',
            'Seit ' + relative(stats.newestArticleAt, now)
            + ' kein neuer Artikel. Bei aktiven Quellen ist das zu lange.', null]);
    } else if (!stats.articlesLast24h) {
        findings.push(['warning', 'In den letzten 24 h kein Artikel erfasst.', null]);
    }

    if (stats.sourcesFailing > 0) {
        const share = stats.sourcesEnabled
            ? Math.round((stats.sourcesFailing / stats.sourcesEnabled) * 100) : 0;
        findings.push([share >= 25 ? 'danger' : 'warning',
            num(stats.sourcesFailing) + ' Quellen scheitern beim Abruf (' + share
            + " % der aktiven).", 'failing']);
    }

    const failedContent = (stats.articlesByContentStatus || {}).FAILED || 0;
    const fetched = (stats.articlesByContentStatus || {}).FETCHED || 0;
    if (failedContent > 0 && failedContent > fetched) {
        findings.push(['warning',
            'Mehr fehlgeschlagene als erfolgreiche Volltext-Abrufe ('
            + num(failedContent) + ' zu ' + num(fetched) + ').', null]);
    }

    const unknownLanguage = (stats.articlesByLanguage || {}).und
        || (stats.articlesByLanguage || {})['null'] || 0;
    if (unknownLanguage > 0 && stats.articlesTotal
            && unknownLanguage / stats.articlesTotal > 0.2) {
        findings.push(['warning',
            'Bei über 20 % der Artikel ist die Sprache unbestimmt.', null]);
    }

    const el = document.getElementById('health');
    if (!findings.length) {
        el.innerHTML = '<div class="alert alert-success py-2 mb-0">'
            + 'Sammelt, letzter Artikel ' + esc(relative(stats.newestArticleAt, now))
            + '. Keine Auffälligkeiten.</div>';
        return;
    }
    el.innerHTML = findings.map(([level, text, action]) =>
        '<div class="alert alert-' + level + ' py-2 mb-2 d-flex align-items-center gap-3">'
        + '<span>' + esc(text) + '</span>'
        + (action === 'failing'
            ? '<button class="btn btn-sm btn-outline-light ms-auto" data-goto-failing>'
              + 'Quellen zeigen</button>'
            : '')
        + '</div>').join('');

    const button = el.querySelector('[data-goto-failing]');
    if (button) {
        button.addEventListener('click', () => {
            const form = document.getElementById('form-sources');
            form.reset();
            form.elements.failing.value = 'true';
            showView('sources');
            loadSources(0).catch(showError);
        });
    }
}

// ── sources ─────────────────────────────────────────────────────────────

let sourcesPage = 0;

async function loadSources(page) {
    clearError();
    await loadPlaces();
    sourcesPage = Math.max(page || 0, 0);
    const form = document.getElementById('form-sources');
    const size = 50;

    const result = await api('/sources', {
        q: form.elements.q.value.trim(),
        enabled: form.elements.enabled.value,
        failing: form.elements.failing.value,
        list: form.elements.list.value.trim(),
        page: sourcesPage,
        size: size,
    });

    const now = Date.now();
    const body = document.getElementById('tbody-sources');
    if (!result.items.length) {
        body.innerHTML = '<tr><td colspan="7" class="text-body-secondary py-4">'
            + 'Keine Quelle passt zum Filter.</td></tr>';
    } else {
        body.innerHTML = result.items.map(source => '<tr data-source="' + esc(source.name) + '">'
            + '<td><div>' + esc(source.title || source.name) + '</div>'
            + '<div class="small text-body-secondary">' + esc(source.name) + '</div></td>'
            + '<td>' + (source.enabled
                ? '<span class="badge text-bg-success">aktiv</span>'
                : '<span class="badge text-bg-secondary">inaktiv</span>') + '</td>'
            + '<td>' + badge(source.lastOutcome) + ' ' + ago(source.lastFetchAt, now) + '</td>'
            + '<td class="text-end ' + (source.consecutiveFailures ? 'text-danger' : '') + '">'
                + esc(num(source.consecutiveFailures)) + '</td>'
            + '<td class="text-end">' + esc(num(source.articleCount)) + '</td>'
            + '<td>' + ago(source.lastArticleAt, now) + '</td>'
            + '<td>' + ago(source.nextFetchAt, now) + '</td>'
            + '</tr>').join('');

        body.querySelectorAll('tr[data-source]').forEach(row =>
            row.addEventListener('click', () =>
                showSourceDetail(row.dataset.source).catch(showError)));
    }

    renderPager('pager-sources', sourcesPage, size, result.total, result.items.length,
        p => loadSources(p).catch(showError));
}

async function showSourceDetail(name) {
    const source = await api('/sources/' + encodeURIComponent(name));
    document.getElementById('detail-title').textContent = source.title || source.name;
    document.getElementById('detail-body').innerHTML =
        (source.lastError
            ? '<div class="alert alert-danger"><div class="fw-semibold">Letzter Fehler</div>'
              + esc(source.lastError) + '</div>'
            : '')
        + defList([
            ['Name', esc(source.name)],
            ['Feed', link(source.url)],
            ['Website', link(source.siteUrl)],
            ['Typ', esc(source.type)],
            ['Zustand', source.enabled ? 'aktiv' : 'inaktiv'],
            ['Sprache', esc(source.language || '—')],
            ['Land', source.country
                ? esc(placeName('iso:' + source.country)) + ' <span class="text-body-secondary'
                  + ' small">(' + esc(source.country) + ')</span>'
                : '—'],
            ['Kategorien', esc((source.categories || []).join(', ') || '—')],
            ['Herkunft', esc(source.origin)
                + (source.originListName ? ' — ' + esc(source.originListName) : '')],
            ['Gesperrte Felder', esc((source.lockedFields || []).join(', ') || '—')],
            ['Abruf-Intervall', esc(Math.round(source.fetchIntervalSeconds / 60) + ' min')],
            ['Letzter Abruf', esc(absolute(source.lastFetchAt))],
            ['Nächster Abruf', esc(absolute(source.nextFetchAt))],
            ['Fehler in Folge', esc(num(source.consecutiveFailures))],
            ['Artikel', esc(num(source.articleCount))],
            ['Letzter Artikel', esc(absolute(source.lastArticleAt))],
            ['Angelegt', esc(absolute(source.createdAt))],
        ])
        + '<button class="btn btn-sm btn-outline-primary" data-articles-of="'
            + esc(source.name) + '">Artikel dieser Quelle</button>';

    const button = document.querySelector('[data-articles-of]');
    button.addEventListener('click', () => {
        const form = document.getElementById('form-articles');
        form.reset();
        form.elements.source.value = button.dataset.articlesOf;
        form.elements.since.value = '';
        detailModal.hide();
        showView('articles');
        loadArticles(0).catch(showError);
    });
    detailModal.show();
}

// ── articles ────────────────────────────────────────────────────────────

let articlesPage = 0;

async function loadArticles(page, withCount) {
    clearError();
    await loadPlaces();
    fillPlaceFilter(document.querySelector('#form-articles select[name=originPlace]'));
    articlesPage = Math.max(page || 0, 0);
    const form = document.getElementById('form-articles');
    const size = 50;

    const sinceHours = form.elements.since.value;
    const since = sinceHours
        ? new Date(Date.now() - Number(sinceHours) * 3600_000).toISOString()
        : '';

    const result = await api('/articles', {
        q: form.elements.q.value.trim(),
        source: form.elements.source.value.trim(),
        language: form.elements.language.value.trim(),
        originPlace: form.elements.originPlace.value,
        contentStatus: form.elements.contentStatus.value,
        since: since,
        withTranslation: true,
        // Counting is a full scan over a multi-million-row collection, so it
        // is asked for by the button in the pager and never by a page turn.
        count: withCount ? 'true' : 'false',
        page: articlesPage,
        size: size,
    });

    const now = Date.now();
    const body = document.getElementById('tbody-articles');
    if (!result.items.length) {
        body.innerHTML = '<tr><td colspan="7" class="text-body-secondary py-4">'
            + 'Kein Artikel passt zum Filter.</td></tr>';
    } else {
        body.innerHTML = result.items.map(article => '<tr data-article="' + esc(article.id) + '">'
            + '<td><div>' + esc(article.title) + '</div>'
            + (article.translation
                ? '<div class="small text-body-secondary">↳ '
                  + esc(article.translation.title) + '</div>'
                : '')
            + '</td>'
            + '<td class="small">' + esc(article.firstSource)
            + ((article.sources || []).length > 1
                ? ' <span class="badge text-bg-secondary">+'
                  + (article.sources.length - 1) + '</span>'
                : '') + '</td>'
            // Origin, not subject: where the publisher sits. The full
            // containment is in the dialog; a column has room for the country.
            + '<td class="small origin" title="' + esc((article.originPlaceIds || [])
                    .map(placeName).join(' › ')) + '">'
                + placeLeaf(article.originPlaceIds) + '</td>'
            + '<td>' + esc(article.language || '—') + '</td>'
            + '<td>' + badge(article.contentStatus)
            + (article.contentWordCount
                ? ' <span class="small text-body-secondary">'
                  + esc(num(article.contentWordCount)) + ' W</span>'
                : '') + '</td>'
            + '<td>' + badge(article.translationStatus) + '</td>'
            + '<td>' + ago(article.firstSeenAt, now) + '</td>'
            + '</tr>').join('');

        body.querySelectorAll('tr[data-article]').forEach(row =>
            row.addEventListener('click', () =>
                showArticleDetail(row.dataset.article).catch(showError)));
    }

    renderPager('pager-articles', articlesPage, size, result.total, result.items.length,
        p => loadArticles(p).catch(showError),
        () => loadArticles(articlesPage, true).catch(showError));
}

async function showArticleDetail(id) {
    const article = await api('/articles/' + encodeURIComponent(id),
        { withTranslation: true });

    // The body is a separate resource and may legitimately not exist yet;
    // a 404 here is a state, not a failure, so it must not blank the dialog.
    let content = null;
    if (article.contentStatus === 'FETCHED') {
        try {
            content = await api('/articles/' + encodeURIComponent(id) + '/content');
        } catch (e) {
            content = null;
        }
    }

    document.getElementById('detail-title').textContent = article.title;
    document.getElementById('detail-body').innerHTML =
        (article.contentError
            ? '<div class="alert alert-warning"><div class="fw-semibold">Volltext-Fehler</div>'
              + esc(article.contentError) + '</div>'
            : '')
        + (article.translationError
            ? '<div class="alert alert-warning"><div class="fw-semibold">Übersetzungs-Fehler</div>'
              + esc(article.translationError) + '</div>'
            : '')
        + defList([
            ['URL', link(article.url)],
            ['Quellen', esc((article.sources || []).join(', '))],
            ['Herkunft', placePath(article.originPlaceIds)
                + (article.originCountry
                    ? ' <span class="text-body-secondary small">('
                      + esc(article.originCountry) + ')</span>'
                    : '')
                + '<div class="text-body-secondary small">Sitz des Verlags — '
                + 'nicht, worum es geht.</div>'],
            ['Autor', esc(article.author || '—')],
            ['Sprache', esc(article.language || '—')
                + ' <span class="text-body-secondary small">('
                + esc(article.languageSource) + ')</span>'],
            ['Kategorien', esc((article.categories || []).join(', ') || '—')],
            ['Veröffentlicht', esc(absolute(article.publishedAt))],
            ['Erfasst', esc(absolute(article.firstSeenAt))],
            ['Volltext', badge(article.contentStatus) + ' '
                + esc(num(article.contentWordCount)) + ' Wörter'],
            ['Übersetzung', badge(article.translationStatus)],
        ])
        + (article.summary
            ? '<h6 class="mt-3">Teaser</h6><p class="text-body-secondary">'
              + esc(article.summary) + '</p>'
            : '')
        + (article.translation
            ? '<h6 class="mt-3">Übersetzung</h6>'
              + '<p class="fw-semibold mb-1">' + esc(article.translation.title) + '</p>'
              + '<p class="text-body-secondary">' + esc(article.translation.summary || '')
              + '</p>'
            : '')
        + (content && content.text
            // Rendered as pre-wrapped text, not as HTML: this is extracted
            // publisher content and the whole point of looking at it is to
            // judge the extraction, not to re-render the page.
            ? '<h6 class="mt-3">Volltext</h6><pre class="article-body">'
              + esc(content.text) + '</pre>'
            : '');
    detailModal.show();
}


// ── categories ──────────────────────────────────────────────────────────
//
// The mapping table. Read-mostly, with one write: settling an entry by hand.
// That write is the reason this view exists — stage 1 guesses from string
// similarity and stage 2 asks a model, and both are wrong in ways that are
// obvious to a person and invisible to a query. Ordered by how many articles
// carry the category, because fixing the one used two thousand times is worth
// more than fixing the one used once.

const topics = new Map();
let categoriesPage = 0;

async function loadTopics() {
    if (topics.size) {
        return;
    }
    for (const topic of await api('/categories/topics')) {
        topics.set(topic.id, topic);
    }
}

function topicName(id) {
    const topic = topics.get(id);
    return topic ? topic.name : id;
}

/** "sport › competition discipline › cricket" — the whole containment. */
function topicTrail(ids) {
    if (!ids || !ids.length) {
        return '<span class="text-body-secondary">—</span>';
    }
    return ids.map(id => esc(topicName(id)))
        .join(' <span class="text-body-secondary">›</span> ');
}

async function loadCategories(page) {
    clearError();
    await loadTopics();
    categoriesPage = Math.max(page || 0, 0);
    const size = 50;
    const status = document.querySelector('#form-categories select[name=status]').value;

    const [result, summary] = await Promise.all([
        api('/categories', { status: status, page: categoriesPage, size: size }),
        api('/categories/summary'),
    ]);

    document.getElementById('category-summary').innerHTML =
        Object.entries(summary)
            .filter(([name, count]) => count > 0 || name === 'TOTAL')
            .map(([name, count]) => '<span class="badge '
                + (name === 'TOTAL' ? 'text-bg-dark' : (BADGE[name] || 'text-bg-secondary'))
                + '">' + esc(name) + ' ' + esc(num(count)) + '</span>')
            .join('');

    const now = Date.now();
    const body = document.getElementById('tbody-categories');
    if (!result.items.length) {
        body.innerHTML = '<tr><td colspan="6" class="text-body-secondary py-4">'
            + 'Noch keine Kategorie erfasst. Die Tabelle füllt sich beim Einlesen '
            + 'neuer Artikel — Bestandsartikel werden nicht nachgetragen.</td></tr>';
    } else {
        body.innerHTML = result.items.map(mapping =>
            '<tr data-category="' + esc(mapping.key) + '">'
            + '<td><div>' + esc(mapping.raw) + '</div>'
            + (mapping.raw.toLowerCase() !== mapping.key
                ? '<div class="small text-body-secondary">' + esc(mapping.key) + '</div>'
                : '')
            + '</td>'
            + '<td>' + badge(mapping.status)
            // The confidence belongs next to the status, not in a column of its
            // own: it only means anything for the two guessing states.
            + (mapping.confidence && mapping.confidence < 1
                ? ' <span class="small text-body-secondary">'
                  + mapping.confidence.toFixed(1) + '</span>'
                : '')
            + '</td>'
            + '<td class="small">' + (mapping.topicId
                ? esc(mapping.topicName || mapping.topicId)
                  + ' <span class="text-body-secondary">' + esc(mapping.topicId) + '</span>'
                : '<span class="text-body-secondary">—</span>') + '</td>'
            + '<td class="text-end">' + esc(num(mapping.useCount)) + '</td>'
            + '<td class="small">' + esc(mapping.decidedBy || '—') + '</td>'
            + '<td>' + ago(mapping.lastSeenAt, now) + '</td>'
            + '</tr>').join('');

        body.querySelectorAll('tr[data-category]').forEach(row =>
            row.addEventListener('click', () =>
                showCategoryDetail(row.dataset.category).catch(showError)));
    }

    renderPager('pager-categories', categoriesPage, size, result.total, result.items.length,
        p => loadCategories(p).catch(showError));
}

async function showCategoryDetail(key) {
    const mapping = await api('/categories/' + encodeURIComponent(key));

    // One datalist for 1,393 topics rather than a select per row: a browser
    // filters it as you type, and the alternative is a megabyte of DOM.
    const options = [...topics.values()]
        .map(topic => '<option value="' + esc(topic.id) + '">'
            + esc(topic.name) + '</option>')
        .join('');

    document.getElementById('detail-title').textContent = mapping.raw;
    document.getElementById('detail-body').innerHTML = defList([
        ['Original', '<code>' + esc(mapping.raw) + '</code>'],
        ['Schlüssel', '<code>' + esc(mapping.key) + '</code>'],
        ['Status', badge(mapping.status)],
        ['Thema', mapping.topicId
            ? esc(mapping.topicName || '') + ' <code>' + esc(mapping.topicId) + '</code>'
            : '—'],
        ['Themenpfad', topicTrail(mapping.topicPath)],
        ['Sicherheit', mapping.confidence ? mapping.confidence.toFixed(2) : '—'],
        ['Entschieden von', esc(mapping.decidedBy || '—')],
        ['Begründung', esc(mapping.note || '—')],
        ['Artikel', esc(num(mapping.useCount))],
        ['Versuche', esc(String(mapping.attempts || 0))],
        ['Letzter Fehler', esc(mapping.lastError || '—')],
    ])
        + '<hr>'
        + '<form id="form-confirm-category" class="row g-2 align-items-end">'
        + '<input type="hidden" name="key" value="' + esc(mapping.key) + '">'
        + '<div class="col-md-7"><label class="form-label small mb-1">'
        + 'Thema von Hand setzen</label>'
        + '<input class="form-control form-control-sm" name="topicId" list="topic-options" '
        + 'placeholder="medtop:… oder Name tippen" value="'
        + esc(mapping.topicId || '') + '">'
        + '<datalist id="topic-options">' + options + '</datalist></div>'
        + '<div class="col-md-5 d-flex gap-2">'
        + '<button class="btn btn-sm btn-primary" type="submit">Bestätigen</button>'
        + '<button class="btn btn-sm btn-outline-secondary" type="button" '
        + 'id="btn-not-a-topic">Kein Thema</button></div>'
        + '<div class="form-text">Beides ist endgültig: eine bestätigte Zuordnung '
        + 'wird nie wieder gefragt. Bereits gespeicherte Artikel behalten die '
        + 'Themen, mit denen sie geschrieben wurden.</div>'
        + '</form>';

    const form = document.getElementById('form-confirm-category');
    form.addEventListener('submit', event => {
        event.preventDefault();
        confirmCategory(mapping.key, form.elements.topicId.value.trim()).catch(showError);
    });
    document.getElementById('btn-not-a-topic').addEventListener('click', () =>
        confirmCategory(mapping.key, null).catch(showError));

    detailModal.show();
}

async function confirmCategory(key, topicId) {
    await api('/categories/' + encodeURIComponent(key) + '/confirm', null, 'POST',
        { topicId: topicId });
    detailModal.hide();
    // Reload first, note second: every load* starts with clearError(), which
    // hides the alert slot the note shares. Noting before the reload wiped it
    // before anyone saw it.
    await loadCategories(categoriesPage);
    showNote(topicId
        ? '„' + key + '" ist jetzt ' + topicName(topicId) + '.'
        : '„' + key + '" gilt als kein Thema.');
}


// ── filter rules ────────────────────────────────────────────────────────
//
// Full CRUD, unlike the rest of this console. A rule is cheap to write, cheap
// to switch off and cheap to delete, so the argument for keeping destructive
// operations out of reach of a mis-click does not apply — what a mis-click
// costs here is one crawl decision, not data.

const RULE_TYPE_HINT = {
    HOST: 'Domain der Artikel-URL, ohne www. Mit „endet mit" greift eine Regel auch '
        + 'für Subdomains — das ist der richtige Vergleich für Domains.',
    URL: 'Die ganze URL. Achtung: „enthält youtube.com" trifft auch eine fremde URL, '
        + 'die youtube.com nur im Query-String nennt. Dafür ist host da.',
    SOURCE: 'Quellen-Name. Ein Artikel kann aus mehreren Feeds kommen — es genügt, '
        + 'wenn eine Quelle passt.',
    LANGUAGE: 'Erkannte Artikelsprache als Zwei-Buchstaben-Code, z.B. de.',
    REGION: 'Sitz der Quelle, nicht das Thema des Artikels. Ort-Id wie iso:SG oder '
        + 'm49:142 — eine Region trifft alles darunter.',
    CATEGORY: 'Die Original-Kategorie des Publishers, unnormiert und in seiner Sprache. '
        + 'Funktioniert sofort.',
    TOPIC: 'Normiertes Thema wie medtop:15000000 (trifft auch alles darunter). Steht '
        + 'beim Einlesen meist noch nicht fest — solche Regeln wirken erst über '
        + '„Bestand neu bewerten".',
    PROFILE: 'Taktklasse der Quelle (news, blog) — wie oft gepollt wird, nicht welche '
        + 'Gattung. Beides fällt derzeit zufällig zusammen.',
};

async function loadFilterRules() {
    clearError();
    const pipeline = document.getElementById('filter-pipeline').value;
    const rules = await api('/filter/rules', { pipeline: pipeline });

    const body = document.getElementById('tbody-filter');
    if (!rules.length) {
        body.innerHTML = '<tr><td colspan="7" class="text-body-secondary py-4">'
            + 'Keine Regel. Ohne Regeln wird alles akzeptiert.</td></tr>';
        return;
    }
    body.innerHTML = rules.map(rule => '<tr' + (rule.enabled ? '' : ' class="opacity-50"') + '>'
        + '<td><div>' + esc(rule.name) + '</div>'
        + (rule.note ? '<div class="small text-body-secondary">' + esc(rule.note) + '</div>' : '')
        + '</td>'
        + '<td class="small">' + esc(rule.pipeline) + '</td>'
        + '<td><span class="badge ' + (rule.decision === 'ACCEPT'
            ? 'text-bg-success' : 'text-bg-danger') + '">'
            + esc(rule.decision) + '</span></td>'
        + '<td class="small">' + esc(rule.type) + '</td>'
        + '<td class="small"><code>' + esc(rule.matchType) + '</code> '
            + esc(rule.value) + '</td>'
        + '<td><div class="form-check form-switch mb-0">'
        + '<input class="form-check-input" type="checkbox" data-toggle-rule="'
            + esc(rule.name) + '"' + (rule.enabled ? ' checked' : '') + '>'
        + '</div></td>'
        + '<td class="text-end text-nowrap">'
        + '<button class="btn btn-sm btn-outline-secondary" data-edit-rule="'
            + esc(rule.name) + '">Ändern</button> '
        + '<button class="btn btn-sm btn-outline-danger" data-delete-rule="'
            + esc(rule.name) + '">Löschen</button>'
        + '</td></tr>').join('');

    body.querySelectorAll('[data-toggle-rule]').forEach(box =>
        box.addEventListener('change', () => toggleFilterRule(box).catch(showError)));
    body.querySelectorAll('[data-edit-rule]').forEach(button =>
        button.addEventListener('click', () =>
            openFilterRuleDialog(rules.find(r => r.name === button.dataset.editRule))));
    body.querySelectorAll('[data-delete-rule]').forEach(button =>
        button.addEventListener('click', () =>
            deleteFilterRule(button.dataset.deleteRule).catch(showError)));
}

function openFilterRuleDialog(rule) {
    const form = document.getElementById('form-filter-rule');
    document.getElementById('filter-rule-title').textContent =
        rule ? 'Regel ' + rule.name : 'Neue Filterregel';
    form.elements.editing.value = rule ? rule.name : '';
    form.elements.pipeline.value = rule ? rule.pipeline
        : (document.getElementById('filter-pipeline').value || 'TRANSLATION');
    form.elements.decision.value = rule ? rule.decision : 'DENY';
    form.elements.enabled.value = rule ? String(rule.enabled) : 'true';
    form.elements.type.value = rule ? rule.type : 'HOST';
    form.elements.matchType.value = rule ? rule.matchType : 'EXACT';
    form.elements.value.value = rule ? rule.value : '';
    form.elements.note.value = rule && rule.note ? rule.note : '';
    updateFilterRuleHint();
    filterRuleModal.show();
}

/** What the chosen type actually reads — the part that is easy to get wrong. */
function updateFilterRuleHint() {
    const form = document.getElementById('form-filter-rule');
    document.getElementById('filter-rule-hint').textContent =
        RULE_TYPE_HINT[form.elements.type.value] || '';
}

async function saveFilterRule() {
    const form = document.getElementById('form-filter-rule');
    const editing = form.elements.editing.value;
    const body = {
        pipeline: form.elements.pipeline.value,
        decision: form.elements.decision.value,
        type: form.elements.type.value,
        matchType: form.elements.matchType.value,
        value: form.elements.value.value.trim(),
        enabled: form.elements.enabled.value === 'true',
        note: form.elements.note.value.trim() || null,
    };
    // An invalid regex comes back as a 400 with the syntax error in it, which
    // is the whole reason the pattern is compiled server-side on save.
    await api(editing ? '/filter/rules/' + encodeURIComponent(editing) : '/filter/rules',
        null, editing ? 'PUT' : 'POST', body);
    filterRuleModal.hide();
    await loadFilterRules();
    showNote(editing ? 'Regel ' + editing + ' gespeichert.' : 'Regel angelegt.');
}

async function toggleFilterRule(box) {
    const name = box.dataset.toggleRule;
    try {
        await api('/filter/rules/' + encodeURIComponent(name) + '/enabled',
            { value: box.checked }, 'POST');
    } catch (e) {
        box.checked = !box.checked;
        throw e;
    }
    await loadFilterRules();
    showNote('Regel ' + name + (box.checked ? ' aktiv.' : ' abgeschaltet.'));
}

async function deleteFilterRule(name) {
    if (!window.confirm('Regel ' + name + ' löschen?')) {
        return;
    }
    await api('/filter/rules/' + encodeURIComponent(name), null, 'DELETE');
    await loadFilterRules();
    showNote('Regel ' + name + ' gelöscht. Artikel behalten den Namen als Begründung.');
}

async function reevaluateFilter(button) {
    const days = document.getElementById('filter-reevaluate-days').value;
    button.disabled = true;
    const label = button.textContent;
    button.textContent = 'läuft …';
    try {
        const report = await api('/filter/reevaluate', { days: days }, 'POST');
        showNote(num(report.examined) + ' Artikel geprüft, ' + num(report.changed)
            + ' neu entschieden — ' + num(report.denied) + ' aus einer Queue genommen, '
            + num(report.accepted) + ' wieder aufgenommen'
            + (report.capped ? '. Obergrenze erreicht, erneut ausführen für den Rest.' : '.'));
    } finally {
        button.disabled = false;
        button.textContent = label;
    }
}


// ── catalogs ────────────────────────────────────────────────────────────
//
// The one subsystem the console operates instead of only showing: switch a
// catalogue on or off, and re-read it now. Both are reversible with the same
// click, and neither destroys anything. Everything else stays in the API —
// no create, no delete, no editing the filter, and nothing at all on sources
// or articles, where a mis-click would cost data rather than a crawl.
//
// Switching one on is the point: catalogues ship disabled, so this view is
// where a fresh installation is told what to start collecting.

async function loadCatalogs() {
    clearError();
    await loadFetchProfiles();
    const catalogs = await api('/catalogs');
    const now = Date.now();
    const container = document.getElementById('catalog-cards');

    if (!catalogs.length) {
        container.innerHTML = '<div class="col"><div class="alert alert-secondary mb-0">'
            + 'Kein Katalog registriert. Ohne Katalog sammelt hrafnagud nur, was '
            + 'von Hand eingetragen wurde.</div></div>';
        return;
    }

    container.innerHTML = catalogs.map(catalog => {
        const report = catalog.lastReport || {};
        return '<div class="col-lg-6"><div class="card h-100">'
            + '<div class="card-header d-flex align-items-center gap-2">'
            + '<span>' + esc(catalog.title || catalog.name) + '</span>'
            + '<div class="form-check form-switch mb-0 ms-2">'
                + '<input class="form-check-input" type="checkbox" role="switch"'
                + ' id="on-' + esc(catalog.name) + '" data-toggle="' + esc(catalog.name) + '"'
                + (catalog.enabled ? ' checked' : '') + '>'
                + '<label class="form-check-label small" for="on-' + esc(catalog.name) + '">'
                + (catalog.enabled ? 'aktiv' : 'inaktiv') + '</label>'
            + '</div>'
            + '<button class="btn btn-sm btn-outline-primary ms-auto" data-refresh="'
                + esc(catalog.name) + '">Jetzt lesen</button>'
            + '</div>'
            + '<div class="card-body">'
            + (catalog.lastError
                ? '<div class="alert alert-danger py-2">' + esc(catalog.lastError) + '</div>'
                : '')
            + (catalog.enabled ? '' :
                '<div class="alert alert-secondary py-2">Inaktiv — dieser Katalog wird '
                + 'nicht von selbst gelesen und liefert keine neuen Quellenlisten. '
                + 'Einschalten startet ihn beim nächsten Lauf.</div>')
            + defList([
                ['Typ', esc(catalog.type)],
                ['Quelle', link(catalog.url)],
                ['Profil', profileSelect(catalog)],
                ['Auswahl', esc((catalog.include || []).join(', ') || 'alles')
                    + ((catalog.exclude || []).length
                        ? ' <span class="text-body-secondary">ohne '
                          + esc(catalog.exclude.join(', ')) + '</span>'
                        : '')],
                ['Listen', esc(num(catalog.listCount))],
                ['Letzter Lauf', badge(catalog.lastOutcome) + ' ' + ago(catalog.lastRefreshAt, now)],
                ['Nächster Lauf', ago(catalog.nextRefreshAt, now)],
                ['Letztes Ergebnis', report.outcome
                    ? esc(num(report.entriesFound)) + ' gefunden, '
                      + esc(num(report.entriesSelected)) + ' gewählt, '
                      + esc(num(report.created)) + ' neu, '
                      + esc(num(report.removed)) + ' entfernt'
                    : '—'],
            ])
            + '</div></div></div>';
    }).join('');

    container.querySelectorAll('[data-refresh]').forEach(button =>
        button.addEventListener('click', () => refreshCatalog(button)));
    container.querySelectorAll('[data-toggle]').forEach(box =>
        box.addEventListener('change', () => toggleCatalog(box)));
    container.querySelectorAll('[data-profile]').forEach(select =>
        select.addEventListener('change', () => setCatalogProfile(select)));
}

/**
 * The interval class this catalogue's sources belong to.
 *
 * <p>A select rather than a text box: the name is the one field that cannot be
 * guessed, and the classes are configured server-side. Changing it affects the
 * lists and sources imported from now on — what is already in the registry
 * keeps the class it was created with, which is why the card says so.
 */
function profileSelect(catalog) {
    const current = catalog.fetchProfile || 'default';
    const options = [...fetchProfiles.keys()].map(name =>
        '<option value="' + esc(name) + '"' + (name === current ? ' selected' : '') + '>'
        + esc(name) + ' (' + esc(profileRange(name)) + ')</option>').join('');
    return '<select class="form-select form-select-sm d-inline-block w-auto"'
        + ' data-profile="' + esc(catalog.name) + '">' + options + '</select>'
        + '<div class="text-body-secondary small">Gilt für neu importierte Quellen; '
        + 'bereits importierte behalten ihre Einstufung.</div>';
}

async function setCatalogProfile(select) {
    const name = select.dataset.profile;
    const profile = select.value;
    select.disabled = true;
    try {
        await api('/catalogs/' + encodeURIComponent(name), null, 'PUT',
            { fetchProfile: profile === 'default' ? '' : profile });
        await loadCatalogs();
        showNote(name + ': Profil ' + profile + ' (' + profileRange(profile) + ').');
    } catch (e) {
        showError(e);
        await loadCatalogs();
    } finally {
        select.disabled = false;
    }
}

/**
 * Switches a catalogue on or off.
 *
 * <p>Enabling only says "keep this in step by itself" — the first pass happens
 * on the next tick, which is why this reports what will happen rather than
 * pretending something already did.
 */
async function toggleCatalog(box) {
    const name = box.dataset.toggle;
    const enabled = box.checked;
    box.disabled = true;
    try {
        await api('/catalogs/' + encodeURIComponent(name), null, 'PUT', { enabled });
        await loadCatalogs();
        showNote(enabled
            ? name + ' ist aktiv — wird beim nächsten Lauf gelesen. "Jetzt lesen" '
              + 'startet ihn sofort.'
            : name + ' ist inaktiv — wird nicht mehr von selbst gelesen. Bereits '
              + 'importierte Listen und Quellen bleiben.');
    } catch (e) {
        // Put the switch back where it was: the server did not accept the
        // change, and a switch showing the opposite of the truth is worse than
        // an error message.
        box.checked = !enabled;
        showError(e);
    } finally {
        box.disabled = false;
    }
}

async function refreshCatalog(button) {
    const name = button.dataset.refresh;
    // A catalogue read is one or more calls to somebody else's server and can
    // take seconds; without this the only feedback is a button that does
    // nothing and a second click that reads it twice.
    button.disabled = true;
    const label = button.textContent;
    button.textContent = 'liest …';
    try {
        const report = await api('/catalogs/' + encodeURIComponent(name) + '/refresh',
            null, 'POST');
        await loadCatalogs();
        showNote(name + ': ' + report.outcome + ' — ' + report.entriesFound
            + ' gefunden, ' + report.created + ' neu, ' + report.removed + ' entfernt');
    } catch (e) {
        showError(e);
    } finally {
        button.disabled = false;
        button.textContent = label;
    }
}

// ── pager ───────────────────────────────────────────────────────────────

/**
 * Paging without a total, because the article endpoint deliberately does not
 * count unless asked. "Next" is offered when the page came back full — one
 * wasted request at the end beats a full collection scan on every turn.
 */
function renderPager(elementId, page, size, total, received, go, count) {
    const el = document.getElementById(elementId);
    const hasNext = total >= 0 ? (page + 1) * size < total : received === size;
    // received, not total: with counting switched off an empty page would
    // otherwise read "1–0".
    const range = received === 0 ? '0' : (page * size + 1) + '–' + (page * size + received);

    el.innerHTML = '<div class="d-flex align-items-center gap-2">'
        + '<button class="btn btn-sm btn-outline-secondary" data-prev '
            + (page === 0 ? 'disabled' : '') + '>Zurück</button>'
        + '<button class="btn btn-sm btn-outline-secondary" data-next '
            + (hasNext ? '' : 'disabled') + '>Weiter</button>'
        + '<span class="small text-body-secondary">' + esc(range)
        + (total >= 0 ? ' von ' + esc(num(total)) : '') + '</span>'
        + (count && total < 0
            ? '<button class="btn btn-sm btn-link" data-count>Gesamtzahl ermitteln</button>'
            : '')
        + '</div>';

    el.querySelector('[data-prev]').addEventListener('click', () => go(page - 1));
    el.querySelector('[data-next]').addEventListener('click', () => go(page + 1));
    const countButton = el.querySelector('[data-count]');
    if (countButton) {
        countButton.addEventListener('click', count);
    }
}

function defList(rows) {
    return '<dl class="row mb-0">' + rows.map(([term, value]) =>
        '<dt class="col-sm-3 text-body-secondary fw-normal">' + esc(term) + '</dt>'
        + '<dd class="col-sm-9">' + value + '</dd>').join('') + '</dl>';
}

// ── settings ────────────────────────────────────────────────────────────
//
// One row per declared setting, grouped by the section of its key. The value
// is edited in place: there is nothing to look at behind a row that the row
// does not already show, so a dialog would only add a click.
//
// Every string here — key, description, value — comes from the API and goes
// through esc(). The values are ours rather than a publisher's, but the rule
// does not get exceptions, because the next value that looks ours might not be.

async function loadSettings() {
    clearError();
    const settings = await api('/settings');
    const tbody = document.getElementById('tbody-settings');

    if (!settings.length) {
        tbody.innerHTML = '<tr><td colspan="5" class="text-body-secondary">'
            + 'Keine Einstellungen deklariert.</td></tr>';
        return;
    }

    let section = null;
    const rows = [];
    for (const setting of settings) {
        const current = sectionOf(setting.key);
        if (current !== section) {
            section = current;
            rows.push('<tr class="table-light"><th colspan="5" class="small">'
                + esc(section) + '</th></tr>');
        }
        rows.push(settingRow(setting));
    }
    tbody.innerHTML = rows.join('');

    tbody.querySelectorAll('[data-save]').forEach(button =>
        button.addEventListener('click', () =>
            saveSetting(button.dataset.save).catch(showError)));
    tbody.querySelectorAll('[data-reset]').forEach(button =>
        button.addEventListener('click', () =>
            resetSetting(button.dataset.reset).catch(showError)));
    tbody.querySelectorAll('[data-key]').forEach(input =>
        input.addEventListener('keydown', event => {
            if (event.key === 'Enter') {
                event.preventDefault();
                saveSetting(input.dataset.key).catch(showError);
            }
        }));
}

/** `munin.source-list.batchSize` → `munin.source-list`. */
function sectionOf(key) {
    const parts = key.split('.');
    return parts.slice(0, parts.length - 1).join('.');
}

function settingRow(setting) {
    const overridden = setting.source === 'DATABASE';
    return '<tr>'
        + '<td><code>' + esc(setting.key.split('.').pop()) + '</code>'
        + '<div class="small text-body-secondary">' + esc(setting.description) + '</div></td>'
        + '<td>' + settingInput(setting) + '</td>'
        + '<td class="small text-body-secondary"><code>'
            + esc(setting.defaultValue || '—') + '</code></td>'
        + '<td class="small">' + (overridden
            ? '<span class="badge text-bg-primary">geändert</span>'
              + (setting.updatedAt
                  ? ' <span class="text-body-secondary">' + esc(absolute(setting.updatedAt))
                    + '</span>'
                  : '')
            : '<span class="text-body-secondary">Konfiguration</span>') + '</td>'
        + '<td class="text-end text-nowrap">'
        + '<button class="btn btn-sm btn-outline-primary" data-save="' + esc(setting.key)
            + '">Speichern</button> '
        + (overridden
            ? '<button class="btn btn-sm btn-outline-secondary" data-reset="'
              + esc(setting.key) + '">Zurücksetzen</button>'
            : '')
        + '</td></tr>';
}

/**
 * A select for a switch, a text box for everything else.
 *
 * <p>Booleans are the values an operator comes here to change in a hurry —
 * "stop fetching bodies" — and a free-text field that accepts `ture` and then
 * silently keeps the old value would be the wrong thing to hand somebody in
 * that moment. The API refuses it either way; this makes it unavailable.
 */
function settingInput(setting) {
    const key = esc(setting.key);
    if (setting.type === 'BOOLEAN') {
        const on = setting.value === 'true';
        return '<select class="form-select form-select-sm" data-key="' + key + '">'
            + '<option value="true"' + (on ? ' selected' : '') + '>an</option>'
            + '<option value="false"' + (on ? '' : ' selected') + '>aus</option>'
            + '</select>';
    }
    return '<input class="form-control form-control-sm" data-key="' + key
        + '" value="' + esc(setting.value) + '">';
}

async function saveSetting(key) {
    const input = document.querySelector('[data-key="' + CSS.escape(key) + '"]');
    await api('/settings/' + encodeURIComponent(key), null, 'PUT', { value: input.value });
    showNote(key + ' gespeichert.');
    await loadSettings();
}

async function resetSetting(key) {
    await api('/settings/' + encodeURIComponent(key), null, 'DELETE');
    showNote(key + ' folgt wieder der Konfiguration.');
    await loadSettings();
}

// ── views ───────────────────────────────────────────────────────────────

function showView(name) {
    for (const view of ['overview', 'sources', 'articles', 'categories', 'filter',
            'catalogs', 'settings']) {
        document.getElementById('view-' + view).classList.toggle('d-none', view !== name);
    }
    document.querySelectorAll('#tabs .nav-link').forEach(tab =>
        tab.classList.toggle('active', tab.dataset.view === name));
    location.hash = name;
}

// ── token dialog ────────────────────────────────────────────────────────

let tokenModal;
let detailModal;
let filterRuleModal;

function openTokenDialog() {
    document.getElementById('input-token').value = tokenStore.get();
    document.getElementById('remember-token').checked = tokenStore.remembered();
    tokenModal.show();
}

// ── boot ────────────────────────────────────────────────────────────────

document.addEventListener('DOMContentLoaded', () => {
    tokenModal = new bootstrap.Modal(document.getElementById('modal-token'));
    detailModal = new bootstrap.Modal(document.getElementById('modal-detail'));
    filterRuleModal = new bootstrap.Modal(document.getElementById('modal-filter-rule'));

    for (const status of ['PENDING', 'FETCHED', 'PAYWALL', 'BLOCKED', 'FAILED', 'SKIPPED']) {
        const option = document.createElement('option');
        option.value = status;
        option.textContent = status;
        document.querySelector('#form-articles select[name=contentStatus]').append(option);
    }

    document.getElementById('btn-token').addEventListener('click', openTokenDialog);

    document.getElementById('form-token').addEventListener('submit', event => {
        event.preventDefault();
        const value = document.getElementById('input-token').value.trim();
        const remember = document.getElementById('remember-token').checked;
        if (value) {
            tokenStore.set(value, remember);
        } else {
            tokenStore.clear();
        }
        tokenModal.hide();
        reloadCurrentView();
    });

    document.getElementById('btn-forget-token').addEventListener('click', () => {
        tokenStore.clear();
        document.getElementById('input-token').value = '';
        setConnection('nicht verbunden', 'text-bg-secondary');
    });

    document.querySelectorAll('#tabs .nav-link').forEach(tab =>
        tab.addEventListener('click', event => {
            event.preventDefault();
            showView(tab.dataset.view);
            reloadCurrentView();
        }));

    document.getElementById('form-sources').addEventListener('submit', event => {
        event.preventDefault();
        loadSources(0).catch(showError);
    });
    document.getElementById('form-articles').addEventListener('submit', event => {
        event.preventDefault();
        loadArticles(0).catch(showError);
    });
    document.getElementById('form-categories').addEventListener('submit', event => {
        event.preventDefault();
        loadCategories(0).catch(showError);
    });

    document.getElementById('filter-pipeline').addEventListener('change', () =>
        loadFilterRules().catch(showError));
    document.getElementById('btn-filter-new').addEventListener('click', () =>
        openFilterRuleDialog(null));
    document.getElementById('form-filter-rule').elements.type.addEventListener('change',
        updateFilterRuleHint);
    document.getElementById('form-filter-rule').addEventListener('submit', event => {
        event.preventDefault();
        saveFilterRule().catch(showError);
    });
    document.getElementById('btn-filter-reevaluate').addEventListener('click', event =>
        reevaluateFilter(event.currentTarget).catch(showError));
    // A reset only clears the inputs; the table would keep showing the old
    // filter's rows until something reloads it.
    for (const id of ['form-sources', 'form-articles', 'form-categories']) {
        document.getElementById(id).addEventListener('reset', () =>
            setTimeout(() => reloadCurrentView(), 0));
    }

    document.getElementById('btn-reload-stats')
        .addEventListener('click', () => loadStats().catch(showError));

    document.getElementById('auto-refresh').addEventListener('change', event => {
        clearInterval(statsTimer);
        statsTimer = event.target.checked
            ? setInterval(() => loadStats().catch(showError), 30_000)
            : null;
    });

    // Back and forward move between the three views. Without this the hash
    // changes, the page does not, and the browser looks broken.
    window.addEventListener('hashchange', () => {
        const view = currentView();
        if (document.getElementById('view-' + view).classList.contains('d-none')) {
            showView(view);
            reloadCurrentView();
        }
    });

    showView(currentView());
    reloadCurrentView();
});

function currentView() {
    const view = location.hash.slice(1);
    return ['overview', 'sources', 'articles', 'categories', 'filter', 'catalogs', 'settings']
            .includes(view)
            ? view : 'overview';
}

function reloadCurrentView() {
    const view = currentView();
    const load = view === 'sources' ? () => loadSources(sourcesPage)
        : view === 'articles' ? () => loadArticles(articlesPage)
        : view === 'categories' ? () => loadCategories(categoriesPage)
        : view === 'filter' ? loadFilterRules
        : view === 'catalogs' ? loadCatalogs
        : view === 'settings' ? loadSettings
        : loadStats;
    load().catch(showError);
}
