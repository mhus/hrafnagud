/*
 * hrafnagud console.
 *
 * Plain fetch and plain DOM, no framework. The console reads three
 * endpoints and renders three tables; a framework would be more code to
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

async function api(path, params) {
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

    let response;
    try {
        response = await fetch(url, { headers });
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
    document.getElementById('alert').classList.add('d-none');
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
            ['Land', esc(source.country || '—')],
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
        body.innerHTML = '<tr><td colspan="6" class="text-body-secondary py-4">'
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

// ── views ───────────────────────────────────────────────────────────────

function showView(name) {
    for (const view of ['overview', 'sources', 'articles']) {
        document.getElementById('view-' + view).classList.toggle('d-none', view !== name);
    }
    document.querySelectorAll('#tabs .nav-link').forEach(tab =>
        tab.classList.toggle('active', tab.dataset.view === name));
    location.hash = name;
}

// ── token dialog ────────────────────────────────────────────────────────

let tokenModal;
let detailModal;

function openTokenDialog() {
    document.getElementById('input-token').value = tokenStore.get();
    document.getElementById('remember-token').checked = tokenStore.remembered();
    tokenModal.show();
}

// ── boot ────────────────────────────────────────────────────────────────

document.addEventListener('DOMContentLoaded', () => {
    tokenModal = new bootstrap.Modal(document.getElementById('modal-token'));
    detailModal = new bootstrap.Modal(document.getElementById('modal-detail'));

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
    // A reset only clears the inputs; the table would keep showing the old
    // filter's rows until something reloads it.
    for (const id of ['form-sources', 'form-articles']) {
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
    return ['overview', 'sources', 'articles'].includes(view) ? view : 'overview';
}

function reloadCurrentView() {
    const view = currentView();
    const load = view === 'sources' ? () => loadSources(sourcesPage)
        : view === 'articles' ? () => loadArticles(articlesPage)
        : loadStats;
    load().catch(showError);
}
