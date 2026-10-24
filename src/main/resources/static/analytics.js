// Аналитика обращений (решение 23): показатели по итоговому типу обращения, у каждого — какое
// решение он поддерживает. Графики — SVG и CSS без библиотек; под каждым графиком те же числа таблицей.

const an = {days: 180, product: "", data: null, products: [], loaded: false};

/** Цвет закреплён за видом страхования, а не за местом в списке: фильтр не перекрашивает линии. */
const PRODUCT_COLORS = {CASCO: "#2a78d6", OSAGO: "#eb6834", MORTGAGE: "#1baf7a", PROPERTY: "#eda100", LIFE: "#e87ba4"};
const NO_PRODUCT_COLOR = "#8a8f99";
const SERIES_COLOR = "#2a78d6";
const OTHER_COLOR = "#a9b1c0";
const productColor = code => PRODUCT_COLORS[code] || NO_PRODUCT_COLOR;

const percent = rate => rate.percent == null ? "—" : `${rate.percent.toLocaleString("ru-RU")} %`;
const ofBase = rate => `${rate.count} из ${rate.of}`;
const shortDay = value => new Intl.DateTimeFormat("ru-RU", {day: "2-digit", month: "2-digit"})
    .format(new Date(`${value}T00:00:00`));

function plural(n, one, few, many) {
    const mod10 = n % 10, mod100 = n % 100;
    if (mod10 === 1 && mod100 !== 11) return one;
    if (mod10 >= 2 && mod10 <= 4 && (mod100 < 12 || mod100 > 14)) return few;
    return many;
}

async function loadAnalytics() {
    if (!an.loaded) {
        an.products = await api("/api/products");
        $("#analytics-product").innerHTML = `<option value="">Все виды страхования</option>`
            + an.products.map(item => `<option value="${item.code}">${escapeHtml(item.label)}</option>`).join("");
        an.loaded = true;
    }
    const body = $("#analytics-body");
    // при обновлении прежние графики остаются, только бледнеют — без мигания и скачков
    body.classList.add("loading");
    try {
        const query = new URLSearchParams({days: an.days});
        if (an.product) query.set("product", an.product);
        an.data = await api(`/api/admin/analytics?${query}`);
        renderAnalytics();
    } finally {
        body.classList.remove("loading");
    }
}

function selectProduct(code) {
    an.product = code;
    $("#analytics-product").value = code;
    loadAnalytics().catch(error => showToast(error.message, true));
}

$("#analytics-period").addEventListener("click", event => {
    const button = event.target.closest("[data-days]");
    if (!button) return;
    an.days = Number(button.dataset.days);
    document.querySelectorAll("#analytics-period [data-days]")
        .forEach(item => item.classList.toggle("selected", item === button));
    loadAnalytics().catch(error => showToast(error.message, true));
});
$("#analytics-product").addEventListener("change", event => selectProduct(event.target.value));
$("#refresh-analytics").addEventListener("click", () => loadAnalytics().catch(error => showToast(error.message, true)));
$("#analytics-products").addEventListener("click", event => {
    const row = event.target.closest("[data-product]");
    if (row) selectProduct(row.dataset.product === an.product ? "" : row.dataset.product);
});

let resizeTimer;
window.addEventListener("resize", () => {
    window.clearTimeout(resizeTimer);
    resizeTimer = window.setTimeout(() => {
        if (an.data && $("#analytics-view").classList.contains("active")) renderWeekly();
    }, 150);
});

function renderAnalytics() {
    const data = an.data;
    const summary = data.summary;
    const selected = an.products.find(item => item.code === data.product);
    $("#analytics-scope").textContent = `${selected ? selected.label : "Все виды страхования"} · с ${formatDate(data.from)}`
        + ` по ${formatDate(data.to)} · ${summary.received} ${plural(summary.received, "обращение", "обращения", "обращений")}`
        + (summary.demo ? `, из них ${summary.demo} демонстрационных — от генератора стенда` : "");
    const empty = summary.received === 0;
    $("#analytics-empty").hidden = !empty;
    $("#analytics-panels").hidden = empty;
    renderKpi(summary);
    if (empty) return;
    renderProducts(data.products);
    renderTypes(data.types, selected);
    renderWeekly();
    renderDepartments(data.departments);
    renderTransfers(data.transfers, summary);
    renderCorrections(data.corrections, summary);
    renderSignals(summary);
    renderAssistantUsage(data.assistant);
}

// ---------- плитки ----------

function renderKpi(summary) {
    const late = rate => rate.of - rate.count;
    const onTime = summary.resolvedOnTime;
    const pretensions = summary.pretensionsOnTime;
    const tiles = [
        ["Поступило", summary.received, "за период"],
        ["Решено", summary.resolved, "ответ по существу дан"],
        ["Решено в срок по закону", percent(onTime),
            late(onTime) ? `⚠ ${late(onTime)} из ${onTime.of} — позже срока` : `${onTime.of} из ${onTime.of}`,
            late(onTime) ? "late" : ""],
        ["Среднее время решения", summary.averageResolutionDays == null ? "—"
            : `${summary.averageResolutionDays.toLocaleString("ru-RU")} дн.`, "от регистрации до ответа"],
        ["Просрочено сейчас", summary.overdueOpen,
            summary.overdueOpen ? "⚠ открыты после срока по закону" : "открытых после срока нет", summary.overdueOpen ? "late" : ""],
        ["Претензии в срок", percent(pretensions),
            `${summary.pretensions} ${plural(summary.pretensions, "претензия", "претензии", "претензий")}`
            + (late(pretensions) ? ` · ⚠ ${late(pretensions)} позже срока` : "")
            + (summary.pretensionsOverdue ? ` · ⚠ просрочено ${summary.pretensionsOverdue}` : ""),
            late(pretensions) || summary.pretensionsOverdue ? "late" : ""]
    ];
    $("#analytics-kpi").innerHTML = tiles.map(([label, value, note, tone]) => `
        <div class="metric ${tone || ""}"><small>${label}</small><strong>${escapeHtml(String(value))}</strong>
            <small>${escapeHtml(note)}</small></div>`).join("");
}

// ---------- доли продуктов ----------

function renderProducts(products) {
    const total = products.reduce((sum, item) => sum + item.rate.count, 0);
    const dimmed = code => an.product && code !== an.product ? " dimmed" : "";
    $("#analytics-products").innerHTML = `
        <div class="share-bar" role="img" aria-label="Доли обращений по видам страхования">${products.map(item => `
            <span class="share-seg${dimmed(item.code)}" data-tip="${escapeHtml(`${item.label}: ${item.rate.count} · ${percent(item.rate)}`)}"
                  style="flex-grow:${item.rate.count}; background:${productColor(item.code)}"></span>`).join("")}</div>
        <ul class="share-legend">${products.map(item => `
            <li><button type="button" class="share-row${dimmed(item.code)}${item.code === an.product ? " selected" : ""}"
                        data-product="${item.code || ""}" ${item.code ? "" : "disabled"}>
                <span class="swatch" style="background:${productColor(item.code)}"></span>
                <span class="share-label">${escapeHtml(item.label)}</span>
                <strong>${percent(item.rate)}</strong><span class="muted">${item.rate.count}</span>
            </button></li>`).join("")}</ul>
        <p class="muted small-text">Всего ${total}. Нажмите на вид страхования, чтобы смотреть все панели по нему.</p>`;
}

// ---------- типы проблем ----------

const TOP_TYPES = 8;

function renderTypes(types, selected) {
    $("#types-title").textContent = selected ? `О чём обращаются — ${selected.label}` : "О чём обращаются";
    let rows = types;
    if (types.length > TOP_TYPES + 1) {
        const rest = types.slice(TOP_TYPES);
        const count = rest.reduce((sum, item) => sum + item.rate.count, 0);
        const of = types[0].rate.of;
        rows = [...types.slice(0, TOP_TYPES), {code: "", label: `Остальные типы (${rest.length}) — вместе`,
            rate: {count, of, percent: of ? Math.round(count * 1000 / of) / 10 : null}, color: OTHER_COLOR}];
    }
    $("#analytics-types").innerHTML = bars(rows.map(item => ({
        label: item.label, value: item.rate.count, text: `${item.rate.count} · ${percent(item.rate)}`, color: item.color
    })), Math.max(...rows.map(item => item.rate.count)));
    $("#types-table").innerHTML = table(["Тип обращения (итоговый)", "Обращений", "Доля"],
        types.map(item => [item.label, item.rate.count, percent(item.rate)]));
}

/**
 * Горизонтальные полосы от общей базовой линии. Подпись и значение — строкой над полосой,
 * чтобы у всех полос была одна ширина дорожки и длины сравнивались честно.
 */
function bars(rows, max, note = () => "") {
    return `<div class="hbars">${rows.map(row => `
        <div class="hbar" tabindex="0" data-tip="${escapeHtml(`${row.label}: ${row.text}`)}">
            <span class="hbar-label">${escapeHtml(row.label)}${note(row)}</span>
            <span class="hbar-value">${escapeHtml(row.text)}</span>
            <span class="hbar-track"><span class="hbar-fill" style="width:${max ? row.value / max * 100 : 0}%;
                background:${row.color || SERIES_COLOR}"></span></span>
        </div>`).join("")}</div>`;
}

function table(head, rows) {
    return `<div class="table-scroll"><table class="table compact"><thead><tr>${head.map(cell => `<th>${escapeHtml(cell)}</th>`).join("")}
        </tr></thead><tbody>${rows.map(row => `<tr>${row.map(cell => `<td>${escapeHtml(String(cell))}</td>`).join("")}</tr>`).join("")}
        </tbody></table></div>`;
}

// ---------- динамика по неделям ----------

function niceStep(max, ticks = 4) {
    const raw = max / ticks;
    const power = 10 ** Math.floor(Math.log10(raw));
    const step = [1, 2, 5, 10].map(k => k * power).find(value => value >= raw);
    return Math.max(1, step);
}

/**
 * Линия на каждый вид страхования. Пять линий на одном графике сливаются, поэтому при всех
 * продуктах — отдельный малый график на каждый, с общей шкалой: высоты сравнимы между ними.
 * Текущая неделя ещё не закончилась — на графике её нет, иначе в конце ложный спад.
 */
function renderWeekly() {
    const {weeks, weekly, to} = an.data;
    const complete = new Date(`${to}T00:00:00`).getDay() === 0 ? weeks.length : weeks.length - 1;
    const shown = weeks.slice(0, complete);
    const series = weekly.map(item => ({...item, counts: item.counts.slice(0, complete)}));
    const peak = Math.max(1, ...series.flatMap(item => item.counts));
    const box = $("#analytics-weekly");
    $("#weekly-note").textContent = complete < weeks.length
        ? `Текущая неделя (с ${formatDate(weeks[weeks.length - 1])}) не показана — она ещё не закончилась; её числа есть в таблице.`
        : "";
    box.classList.toggle("multiples", series.length > 1);
    if (shown.length < 2) {
        box.innerHTML = `<p class="muted">Для динамики нужны хотя бы две полные недели — выберите период длиннее.</p>`;
    } else if (series.length === 1) {
        box.innerHTML = `<div class="multiple-plot"></div>`;
        lineChart(box.firstElementChild, shown, series[0], peak, 240, false);
    } else {
        box.innerHTML = series.map(item => `
            <div class="multiple">
                <p class="multiple-title"><span class="line-key" style="background:${productColor(item.code)}"></span>
                    ${escapeHtml(item.label)}<span class="muted">${item.counts.reduce((sum, value) => sum + value, 0)} за полные недели</span></p>
                <div class="multiple-plot"></div>
            </div>`).join("");
        box.querySelectorAll(".multiple-plot")
            .forEach((plot, i) => lineChart(plot, shown, series[i], peak, 150, true));
    }
    $("#weekly-table").innerHTML = table(["Неделя с", ...weekly.map(item => item.label)],
        weeks.map((week, i) => [formatDate(week) + (i >= complete ? " (не закончилась)" : ""),
            ...weekly.map(item => item.counts[i])]));
}

/** Одна линия: сетка, ось, линия, точка на конце; наведение и стрелки показывают значение недели. */
function lineChart(container, weeks, series, peak, height, compact) {
    const width = Math.max(220, container.clientWidth);
    const m = compact ? {top: 8, right: 10, bottom: 22, left: 28} : {top: 12, right: 20, bottom: 28, left: 40};
    const step = niceStep(peak, compact ? 2 : 4);
    const max = Math.ceil(peak / step) * step;
    const plotW = width - m.left - m.right;
    const last = weeks.length - 1;
    const x = i => m.left + i * plotW / last;
    const y = value => m.top + (height - m.top - m.bottom) * (1 - value / max);
    const color = productColor(series.code);
    const labelEvery = compact ? last : Math.max(1, Math.ceil(weeks.length / 7));

    const grid = [];
    for (let value = 0; value <= max; value += step) {
        grid.push(`<line class="grid${value === 0 ? " base" : ""}" x1="${m.left}" x2="${width - m.right}" y1="${y(value)}" y2="${y(value)}"/>
            <text class="tick" x="${m.left - 6}" y="${y(value) + 4}" text-anchor="end">${value}</text>`);
    }
    const xTicks = weeks.map((week, i) => i % labelEvery === 0 || i === last
        ? `<text class="tick" x="${x(i)}" y="${height - 6}" text-anchor="${i === 0 ? "start" : i === last ? "end" : "middle"}">${
            shortDay(week)}</text>` : "").join("");
    const points = series.counts.map((value, i) => `${x(i).toFixed(1)},${y(value).toFixed(1)}`).join(" ");
    container.innerHTML = `<svg class="line-chart" width="${width}" height="${height}" viewBox="0 0 ${width} ${height}"
            role="img" aria-label="${escapeHtml(`${series.label}: обращений по неделям`)}">
        ${grid.join("")}${xTicks}
        <polyline class="series" points="${points}" style="stroke:${color}"/>
        <circle class="end-dot" cx="${x(last)}" cy="${y(series.counts[last])}" r="4" style="fill:${color}"/>
        <line class="crosshair" x1="0" x2="0" y1="${m.top}" y2="${height - m.bottom}" visibility="hidden"/>
        <circle class="hover-dot" r="4" style="fill:${color}" visibility="hidden"/>
        <rect class="hit" x="${m.left}" y="${m.top}" width="${plotW}" height="${height - m.top - m.bottom}" tabindex="0"/>
    </svg>`;

    const svg = container.querySelector("svg");
    const crosshair = svg.querySelector(".crosshair");
    const dot = svg.querySelector(".hover-dot");
    let focused = last;
    const show = (i, anchor) => {
        focused = i;
        crosshair.setAttribute("x1", x(i));
        crosshair.setAttribute("x2", x(i));
        dot.setAttribute("cx", x(i));
        dot.setAttribute("cy", y(series.counts[i]));
        [crosshair, dot].forEach(item => item.setAttribute("visibility", "visible"));
        showTip(`Неделя с ${formatDate(weeks[i])}`, [[series.counts[i], series.label, color]], anchor);
    };
    const hide = () => {
        [crosshair, dot].forEach(item => item.setAttribute("visibility", "hidden"));
        hideTip();
    };
    const anchorAt = i => {
        const rect = svg.getBoundingClientRect();
        return {x: rect.left + x(i), y: rect.top + y(series.counts[i])};
    };
    const hit = svg.querySelector(".hit");
    hit.addEventListener("pointermove", event => {
        const rect = svg.getBoundingClientRect();
        const i = Math.round((event.clientX - rect.left - m.left) / (plotW / last));
        const at = Math.min(last, Math.max(0, i));
        show(at, anchorAt(at));
    });
    hit.addEventListener("pointerleave", hide);
    hit.addEventListener("blur", hide);
    hit.addEventListener("focus", () => show(focused, anchorAt(focused)));
    hit.addEventListener("keydown", event => {
        const move = {ArrowLeft: -1, ArrowRight: 1}[event.key];
        if (!move) return;
        event.preventDefault();
        const i = Math.min(last, Math.max(0, focused + move));
        show(i, anchorAt(i));
    });
}

// ---------- сроки по отделам ----------

/**
 * Доля решённых позже срока по закону. На шкале «в срок» 0–100 % отделы с 91 и 97 % выглядят
 * одинаково — разница видна только в доле нарушений. Тревогу несут плитки и отметки «просрочено», не цвет полос.
 */
function renderDepartments(departments) {
    const violation = item => item.onTime.of ? Math.round((item.onTime.of - item.onTime.count) * 1000 / item.onTime.of) / 10 : null;
    const sorted = [...departments].sort((a, b) => (violation(b) ?? -1) - (violation(a) ?? -1) || a.name.localeCompare(b.name));
    const rows = sorted.map(item => {
        const share = violation(item);
        const late = item.onTime.of - item.onTime.count;
        return {
            label: item.name, value: share ?? 0, open: item.open, overdue: item.overdueOpen,
            text: share == null ? "нет решённых" : late ? `${share.toLocaleString("ru-RU")} % · ${late} из ${item.onTime.of}` : "нарушений нет"
        };
    });
    // шкала — до ближайших 5 %, а не до худшего отдела: иначе 9 % выглядит как полная полоса
    const scale = Math.max(5, Math.ceil(Math.max(...rows.map(row => row.value), 0) / 5) * 5);
    $("#departments-scale").textContent = `Шкала полос — от 0 до ${scale} % решённых позже срока.`;
    $("#analytics-departments").innerHTML = rows.length
        ? bars(rows, scale, row => `<small class="hbar-note">открыто ${row.open}${row.overdue
            ? ` · <span class="badge late">⚠ просрочено ${row.overdue}</span>` : ""}</small>`)
        : `<p class="muted">Обращения ещё не распределены по отделам.</p>`;
    $("#departments-table").innerHTML = table(["Отдел", "Решено", "В срок", "Позже срока", "Открыто", "Просрочено сейчас"],
        sorted.map(item => [item.name, item.onTime.of, percent(item.onTime), item.onTime.of - item.onTime.count,
            item.open, item.overdueOpen]));
}

// ---------- передачи и итоговый тип ----------

function renderTransfers(transfers, summary) {
    $("#transfers-share").textContent = `Передано между отделами ${percent(summary.transferred)} обращений (${ofBase(summary.transferred)}).`;
    $("#analytics-transfers").innerHTML = transfers.length
        ? table(["Откуда", "Куда", "Передач"], transfers.slice(0, 10).map(item => [item.from, `→ ${item.to}`, item.count]))
        : `<p class="muted">Одобренных передач за период нет — правила маршрутизации не ошибались.</p>`;
}

function renderCorrections(corrections, summary) {
    $("#corrections-share").innerHTML = `<strong class="big-figure">${percent(summary.typeCorrected)}</strong>
        <span class="muted">решённых обращений — тип исправлен при ответе (${ofBase(summary.typeCorrected)})</span>`;
    $("#analytics-corrections").innerHTML = corrections.length
        ? table(["Клиент выбрал", "Итоговый тип", "Сколько"], corrections.slice(0, 8)
            .map(item => [item.initial, `→ ${item.finalType}`, item.count]))
        : `<p class="muted">Клиенты выбирали тип верно.</p>`;
}

// ---------- сигналы процессов ----------

function renderSignals(summary) {
    const signals = [
        ["Ручная классификация", summary.manuallyClassified,
            "Каких правил маршрутизации не хватает — добавьте их во вкладке «Правила»."],
        ["Приняты без договора", summary.acceptedWithoutContract,
            "Учётная система договоров была недоступна: показатель надёжности интеграции."],
        ["Повторные обращения", summary.repeated,
            "Тот же клиент, договор и тема в течение 30 дней — проблема не решена с первого раза."],
        ["Требование удовлетворено", summary.satisfied,
            "Полностью или частично, от обращений с итогом. Рост отказов — повод проверить продукт и разъяснения."]
    ];
    $("#analytics-signals").innerHTML = signals.map(([label, rate, hint]) => `
        <li><strong>${percent(rate)}</strong><span><b>${label}</b> <span class="muted">${ofBase(rate)}</span>
            <small class="muted">${hint}</small></span></li>`).join("");
}

// ---------- помощник сотрудника (решение 35) ----------

const SUGGESTION_KINDS = {TYPE_HINT: "Похоже на претензию", SIMILAR: "Как решали похожие", DRAFT: "Черновик ответа"};
const SUGGESTION_SOURCES = {RULES: "правила", LOCAL_MODEL: "локальная модель"};

function renderAssistantUsage(usage) {
    $("#analytics-assistant").innerHTML = usage.length ? bars(usage.map(item => ({
        label: `${SUGGESTION_KINDS[item.kind] || item.kind} · ${SUGGESTION_SOURCES[item.source] || item.source}`,
        value: item.accepted.percent ?? 0,
        text: `принято ${percent(item.accepted)} · ${item.accepted.count} из ${item.shown}${item.rejected ? ` · отклонено ${item.rejected}` : ""}`
    })), 100) : `<p class="muted">За период подсказок не показывали.</p>`;
}

// ---------- подсказка при наведении ----------

const tip = document.createElement("div");
tip.className = "viz-tip";
tip.setAttribute("role", "status");
tip.hidden = true;
document.body.append(tip);

/** Значение — крупно, подпись — следом; подписи вставляются как текст, не как разметка. */
function showTip(title, rows, anchor) {
    tip.replaceChildren();
    const head = document.createElement("div");
    head.className = "viz-tip-title";
    head.textContent = title;
    tip.append(head);
    rows.forEach(([value, label, color]) => {
        const row = document.createElement("div");
        row.className = "viz-tip-row";
        const key = document.createElement("span");
        key.className = "line-key";
        key.style.background = color;
        const strong = document.createElement("strong");
        strong.textContent = value;
        const name = document.createElement("span");
        name.textContent = label;
        row.append(key, strong, name);
        tip.append(row);
    });
    tip.hidden = false;
    const left = Math.min(anchor.x + 14, window.innerWidth - tip.offsetWidth - 8);
    tip.style.left = `${left}px`;
    tip.style.top = `${Math.max(8, anchor.y - tip.offsetHeight - 10)}px`;
}

function hideTip() {
    tip.hidden = true;
}

// у полос подсказка — у каждой своя: сама полоса и есть цель наведения
document.querySelector("#analytics-view").addEventListener("pointermove", event => {
    const mark = event.target.closest("[data-tip]");
    if (!mark) return;
    showTip(mark.dataset.tip, [], {x: event.clientX, y: event.clientY});
});
document.querySelector("#analytics-view").addEventListener("pointerout", event => {
    if (event.target.closest("[data-tip]") && !event.relatedTarget?.closest?.("[data-tip]")) hideTip();
});
