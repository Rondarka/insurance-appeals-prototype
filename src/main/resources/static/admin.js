// Администрирование: сводка по отделам, все обращения с фильтрами, карточка целиком
// (только просмотр: с обращениями работают отделы), журнал событий.

const adm = {appeals: [], departments: [], openedId: null};
const $ = selector => document.querySelector(selector);

const isOverdue = appeal => appeal.deadlineAt
    && !["RESOLVED", "CLOSED"].includes(appeal.status)
    && new Date(appeal.deadlineAt) < new Date();

startPage("/admin.html", async () => {
    adm.departments = await api("/api/departments");
    $("#filter-department").innerHTML = `<option value="">Все отделы</option>`
        + adm.departments.map(item => `<option value="${item.code}">${escapeHtml(item.name)}</option>`).join("");
    $("#filter-status").innerHTML = `<option value="">Все статусы</option><option value="OVERDUE">Просроченные</option>`
        + Object.entries(STATUS_LABELS).map(([code, label]) => `<option value="${code}">${label}</option>`).join("");
    await loadAll();
});

document.querySelector(".tabs").addEventListener("click", event => {
    const tab = event.target.closest("[data-tab]");
    if (tab) showTab(tab.dataset.tab);
});

function showTab(name) {
    document.querySelectorAll(".tab").forEach(item => item.classList.toggle("active", item.dataset.tab === name));
    document.querySelectorAll(".view").forEach(item => item.classList.toggle("active", item.id === `${name}-view`));
    if (name === "events") loadEvents($("#events")).catch(error => showToast(error.message, true));
    if (name === "rules") loadSettings().catch(error => showToast(error.message, true));
    if (name === "analytics") loadAnalytics().catch(error => showToast(error.message, true));
}

const INTEGRATION_STATE = {
    CLOSED: ["ok", "работает"],
    OK: ["ok", "работает"],
    DELAYED: ["late", "события задерживаются: брокер недоступен, события ждут в outbox и уйдут, когда он вернётся"],
    HALF_OPEN: ["soon", "проверяется после сбоя"],
    OPEN: ["late", "недоступна: обращения к ней приостановлены, кабинет работает в резервном режиме"]
};

async function loadAll() {
    adm.appeals = await api("/api/appeals");
    const integrations = await api("/api/integrations").catch(() => []);
    $("#integrations").innerHTML = integrations.map(item => {
        // у модели помощника своё следствие недоступности: подсказки дают правила (решение 35)
        const [tone, text] = item.code === "ASSISTANT" && ["OPEN", "HALF_OPEN"].includes(item.state)
            ? ["late", "недоступна, подсказки сотрудникам дают правила"]
            : INTEGRATION_STATE[item.state] || ["", item.state];
        return `<p class="integration ${tone}"><span class="dot"></span>${escapeHtml(item.name)}: ${text}${
            item.failureRate >= 0 ? ` <small class="muted">· сбоев ${Math.round(item.failureRate)} %</small>` : ""}${
            item.pending ? ` <small class="muted">· ждут отправки: ${item.pending}</small>` : ""}</p>`;
    }).join("");
    renderOverview();
    renderList();
}

$("#refresh-overview").addEventListener("click", () => loadAll().catch(error => showToast(error.message, true)));
$("#refresh-events").addEventListener("click", () => loadEvents($("#events")).catch(error => showToast(error.message, true)));

// ---------- обзор ----------

function renderOverview() {
    const count = predicate => adm.appeals.filter(predicate).length;
    const metrics = [
        ["Всего", adm.appeals.length, ""],
        ["Новые", count(a => a.status === "ROUTED"), ""],
        ["В работе", count(a => a.status === "IN_PROGRESS"), ""],
        ["Ждут клиента", count(a => a.status === "WAITING_CUSTOMER"), ""],
        ["Просрочено отделом", count(isOverdue), count(isOverdue) ? "late" : ""],
        ["Срок по закону истекает", count(a => isEscalated(a) && a.escalationLevel === "AT_RISK"),
            count(a => isEscalated(a) && a.escalationLevel === "AT_RISK") ? "soon" : ""],
        ["Просрочено по закону", count(isLegallyOverdue), count(isLegallyOverdue) ? "late" : ""],
        ["Ждут классификации", count(a => a.status === "NEEDS_CLASSIFICATION"),
            count(a => a.status === "NEEDS_CLASSIFICATION") ? "late" : ""],
        ["Ждут договора", count(a => a.contractLink && a.contractLink !== "LINKED"),
            count(a => a.contractLink && a.contractLink !== "LINKED") ? "soon" : ""],
        ["Решено и закрыто", count(a => ["RESOLVED", "CLOSED"].includes(a.status)), ""]
    ];
    $("#metrics").innerHTML = metrics.map(([label, value, tone]) =>
        `<div class="metric ${tone}"><small>${label}</small><strong>${value}</strong></div>`).join("");

    const rows = adm.departments.map(department => {
        const own = adm.appeals.filter(a => a.department?.code === department.code);
        return {department, own};
    });
    const unrouted = adm.appeals.filter(a => !a.department);
    const cell = (list, predicate) => list.filter(predicate).length || "";
    $("#by-department").innerHTML = `
        <thead><tr><th>Отдел</th><th>Новые</th><th>В работе</th><th>Ждут клиента</th><th>Просрочено по закону</th><th>Решено</th><th>Всего</th></tr></thead>
        <tbody>
            ${rows.map(({department, own}) => `
                <tr class="clickable" data-department="${department.code}">
                    <td>${escapeHtml(department.name)}</td>
                    <td>${cell(own, a => a.status === "ROUTED")}</td>
                    <td>${cell(own, a => a.status === "IN_PROGRESS")}</td>
                    <td>${cell(own, a => a.status === "WAITING_CUSTOMER")}</td>
                    <td class="${own.some(isLegallyOverdue) ? "late-text" : ""}">${cell(own, isLegallyOverdue)}</td>
                    <td>${cell(own, a => ["RESOLVED", "CLOSED"].includes(a.status))}</td>
                    <td><strong>${own.length}</strong></td>
                </tr>`).join("")}
            ${unrouted.length ? `<tr><td>Ожидают маршрутизации</td><td colspan="5"></td><td><strong>${unrouted.length}</strong></td></tr>` : ""}
        </tbody>`;
}

$("#by-department").addEventListener("click", event => {
    const row = event.target.closest("[data-department]");
    if (!row) return;
    $("#filter-department").value = row.dataset.department;
    $("#filter-status").value = "";
    renderList();
    showTab("appeals");
});

// ---------- все обращения ----------

function renderList() {
    const department = $("#filter-department").value;
    const status = $("#filter-status").value;
    const text = $("#filter-text").value.trim().toLowerCase();
    const items = adm.appeals
        .filter(a => !department || a.department?.code === department)
        .filter(a => !status || (status === "OVERDUE" ? isOverdue(a) : a.status === status))
        .filter(a => !text || `${a.publicNumber} ${a.subject} ${a.customerName}`.toLowerCase().includes(text))
        .sort((a, b) => new Date(b.createdAt) - new Date(a.createdAt));

    $("#admin-list").innerHTML = items.length ? items.map(appeal => `
        <button type="button" class="queue-item ${appeal.id === adm.openedId ? "selected" : ""}" data-appeal="${appeal.id}">
            <span class="queue-row">${priorityMark(appeal.priority)}<strong>${escapeHtml(appeal.subject)}</strong></span>
            <span class="queue-row meta">
                <small>${escapeHtml(appeal.department?.name || "маршрутизация")} · ${escapeHtml(appeal.publicNumber)}</small>
                ${statusChip(appeal.status)}
            </span>
        </button>`).join("") : emptyState("Ничего не найдено", "Измените отдел, статус или запрос поиска");
}

["#filter-department", "#filter-status"].forEach(id => $(id).addEventListener("change", renderList));
$("#filter-text").addEventListener("input", renderList);

$("#admin-card").addEventListener("click", event => {
    const related = event.target.closest("[data-open-related]");
    if (related) openCard(related.dataset.openRelated).catch(error => showToast(error.message, true));
});

$("#admin-list").addEventListener("click", event => {
    const item = event.target.closest("[data-appeal]");
    if (item) openCard(item.dataset.appeal).catch(error => showToast(error.message, true));
});

async function afterContractLinked(id) {
    await openCard(id);
}

async function openCard(id) {
    const appeal = await api(`/api/appeals/${id}`);
    adm.openedId = id;
    $("#admin-card").innerHTML = `
        ${appealHeading(appeal)}
        <div class="card-body">
            <div class="main-col">
                ${renderChecks(appeal)}
                <h4>Переписка</h4>
                ${renderThread(appeal, "NOBODY")}
                <h4>Журнал обработки</h4>
                ${renderHistory(appeal)}
                ${appeal.transfers.length ? `<h4>Передачи между отделами</h4>${renderTransfers(appeal)}` : ""}
            </div>
            <aside class="side-col">
                <dl class="kv">
                    <dt>Клиент</dt><dd>${escapeHtml(appeal.customerName)}<br><small class="muted">${escapeHtml(appeal.customerEmail)}</small>
                        ${appeal.channel && appeal.channel !== "CABINET" ? `<br><small class="muted">принято ${CHANNEL_LABELS[appeal.channel]},
                            оператор ${escapeHtml(appeal.registeredBy || "")}</small>` : ""}</dd>
                    <dt>Отдел</dt><dd>${escapeHtml(appeal.department?.name || "ожидает маршрутизации")}</dd>
                    <dt>Исполнитель</dt><dd>${escapeHtml(appeal.assignedEmployee || "не назначен")}</dd>
                    <dt>Приоритет</dt><dd>${escapeHtml(PRIORITY_LABELS[appeal.priority])}</dd>
                    ${appeal.outcome ? `<dt>Итог</dt><dd>${escapeHtml(OUTCOME_LABELS[appeal.outcome])}</dd>` : ""}
                    <dt>Создано</dt><dd>${formatDateTime(appeal.createdAt)}</dd>
                    <dt>Срок отдела</dt><dd>${formatDateTime(appeal.deadlineAt)}</dd>
                </dl>
                <h4>Срок по закону</h4>${renderNorm(appeal)}
                ${renderContractSection(appeal)}
                <h4>Данные обращения</h4>${renderDetails(appeal)}
                <h4>Файлы</h4>${renderAttachments(appeal)}
                ${renderRelatedSection(appeal)}
                <h4>Кто открывал обращение</h4>
                <div id="access-${id}">${loadingState()}</div>
            </aside>
        </div>`;
    loadRelated(id, $(`#related-${id}`));
    loadAccessLog(id).catch(error => showToast(error.message, true));
    renderList();
}

/** Журнал доступа (решение 40): кто из сотрудников открывал карточку и файлы. */
async function loadAccessLog(id) {
    const entries = await api(`/api/admin/appeals/${id}/access-log`);
    const box = $(`#access-${id}`);
    if (!box) return;
    box.innerHTML = entries.length ? `<ul class="access-log">${entries.map(entry => `
        <li><span>${escapeHtml(entry.viewerName)}<small class="muted"> · ${escapeHtml(ROLE_LABELS[entry.viewerRole] || entry.viewerRole)}${
            entry.department ? `, ${escapeHtml(entry.department)}` : ""}</small></span>
            <small class="muted">${entry.action === "FILE_OPENED" ? `открыл файл «${escapeHtml(entry.file || "")}»` : "открыл карточку"},
                ${formatDateTime(entry.accessedAt)}</small></li>`).join("")}</ul>
        <p class="muted small-text">Повторный просмотр в течение 30 минут отдельно не записывается.</p>`
        : `<p class="muted small-text">Сотрудники обращение ещё не открывали.</p>`;
}

// ---------- правила: маршрутизация, нормативы, журнал (решение 34) ----------

const ACTION_LABELS = {CREATED: "добавлено", UPDATED: "изменено", DELETED: "удалено"};

function settingsReason() {
    const reason = $("#settings-reason").value.trim();
    if (reason.length < 5) {
        $("#settings-reason").focus();
        throw new Error("Укажите причину изменения: она попадёт в журнал");
    }
    return reason;
}

const options = (items, selected) => items.map(([value, label]) =>
    `<option value="${escapeHtml(value)}" ${value === selected ? "selected" : ""}>${escapeHtml(label)}</option>`).join("");

async function loadSettings() {
    const [routing, norms, audit] = await Promise.all([
        api("/api/admin/routing-rules"), api("/api/admin/norms"), api("/api/admin/settings-audit")]);
    renderRouting(routing);
    renderNorms(norms);
    renderAudit(audit);
    await loadAssistantSettings();
    renderRetention(await api("/api/admin/retention"));
}

// ---------- сроки хранения (решение 41) ----------

function renderRetention(retention) {
    $("#retention-settings").innerHTML = `
        <form id="retention-form" class="engine-save">
            <label>Хранить, лет после ответа
                <input name="years" type="number" min="1" max="75" required value="${retention.years}">
            </label>
            <label>Причина изменения, попадёт в журнал
                <input name="reason" maxlength="500" placeholder="Например: новая номенклатура дел">
            </label>
            <button class="btn" type="submit">Сохранить срок</button>
        </form>
        <p class="muted small-text">Обезличено обращений: ${retention.anonymized}. Ждут обезличивания при текущем сроке:
            ${retention.due}.</p>`;
}

$("#retention-settings").addEventListener("submit", async event => {
    event.preventDefault();
    const form = new FormData(event.target);
    try {
        const reason = form.get("reason").trim();
        if (reason.length < 5) {
            event.target.elements.reason.focus();
            throw new Error("Укажите причину изменения: она попадёт в журнал");
        }
        renderRetention(await api("/api/admin/retention", {
            method: "PUT", body: JSON.stringify({years: Number(form.get("years")), reason})
        }));
        showToast("Срок хранения сохранён");
        await loadSettings();
    } catch (error) {
        showToast(error.message, true);
    }
});

/** Подпись строки правила для экранного диктора: «Расчёты, все типы категории». */
const ruleName = rule => `${rule.categoryLabel}, ${rule.subcategory === "GENERAL" ? "все типы категории" : rule.typeLabel}`;

function renderRouting(routing) {
    const departments = adm.departments.map(item => [item.code, item.name]);
    const priorities = Object.entries(PRIORITY_LABELS);
    $("#routing-uncovered").innerHTML = routing.uncovered.length ? `
        <p class="notice small">Нет правила, обращения уйдут на ручную классификацию:
            ${routing.uncovered.map(item => escapeHtml(`${item.categoryLabel} / ${item.typeLabel}`)).join(", ")}</p>` : "";
    $("#routing-rules").innerHTML = `
        <thead><tr><th>Вид обращения</th><th>Отдел</th><th>Приоритет</th><th>Срок отдела, ч</th><th>Предел по закону</th><th></th></tr></thead>
        <tbody>${routing.rules.map(rule => `
            <tr data-rule="${rule.category}/${rule.subcategory}">
                <td><strong>${escapeHtml(rule.categoryLabel)}</strong><br>
                    <small class="muted">${rule.subcategory === "GENERAL" ? "все типы категории" : escapeHtml(rule.typeLabel)}</small></td>
                <td><select name="departmentCode" aria-label="${escapeHtml(`Отдел: ${ruleName(rule)}`)}">${options(departments, rule.departmentCode)}</select></td>
                <td><select name="priority" aria-label="${escapeHtml(`Приоритет: ${ruleName(rule)}`)}">${options(priorities, rule.priority)}</select></td>
                <td><input name="slaHours" type="number" min="1" max="${rule.maxSlaHours}" value="${rule.slaHours}"
                           aria-label="${escapeHtml(`Срок отдела в часах: ${ruleName(rule)}`)}"></td>
                <td><small title="${escapeHtml(rule.legalLimit)}">≤ ${rule.maxSlaHours} ч</small></td>
                <td class="row-actions">
                    <button type="button" class="btn small-btn" data-save-rule>Сохранить</button>
                    ${rule.subcategory === "GENERAL" ? "" : `<button type="button" class="btn ghost small-btn" data-delete-rule>Удалить</button>`}
                </td>
            </tr>`).join("")}</tbody>`;
    // свой срок можно задать типу, у которого нет своего правила
    const own = new Set(routing.rules.filter(rule => rule.subcategory !== "GENERAL").map(rule => `${rule.category}/${rule.subcategory}`));
    const types = session.schema.categories.flatMap(category => category.types
        .filter(type => !own.has(`${category.code}/${type.code}`))
        .map(type => [`${category.code}/${type.code}`, `${category.label} / ${type.label}`]));
    const form = $("#routing-add");
    form.type.innerHTML = options(types);
    form.departmentCode.innerHTML = options(departments);
    form.priority.innerHTML = options(priorities, "NORMAL");
}

async function saveRule(rule) {
    await api("/api/admin/routing-rules", {method: "PUT", body: JSON.stringify({...rule, reason: settingsReason()})});
    showToast("Правило сохранено");
    await loadSettings();
}

$("#routing-rules").addEventListener("click", event => {
    const row = event.target.closest("[data-rule]");
    if (!row) return;
    const [category, subcategory] = row.dataset.rule.split("/");
    (async () => {
        if (event.target.closest("[data-save-rule]")) {
            await saveRule({
                category, subcategory,
                departmentCode: row.querySelector('[name="departmentCode"]').value,
                priority: row.querySelector('[name="priority"]').value,
                slaHours: Number(row.querySelector('[name="slaHours"]').value)
            });
        }
        if (event.target.closest("[data-delete-rule]")) {
            const reason = settingsReason();
            if (!confirm("Удалить правило? Обращения этого типа пойдут по правилу категории.")) return;
            await api(`/api/admin/routing-rules/${category}/${subcategory}?reason=${encodeURIComponent(reason)}`, {method: "DELETE"});
            showToast("Правило удалено");
            await loadSettings();
        }
    })().catch(error => showToast(error.message, true));
});

$("#routing-add").addEventListener("submit", event => {
    event.preventDefault();
    const form = event.target;
    const [category, subcategory] = form.type.value.split("/");
    saveRule({category, subcategory, departmentCode: form.departmentCode.value, priority: form.priority.value,
        slaHours: Number(form.slaHours.value)}).catch(error => showToast(error.message, true));
});

function renderNorms(norms) {
    const today = new Date().toISOString().slice(0, 10);
    $("#norms").innerHTML = `
        <thead><tr><th>Норматив</th><th>Срок</th><th>Продление</th><th>Основание</th><th>Действует</th></tr></thead>
        <tbody>${norms.map(norm => {
            const current = norm.validFrom <= today && (!norm.validTo || norm.validTo >= today);
            return `<tr class="${current ? "" : "muted-row"}">
                <td><strong>${escapeHtml(norm.title)}</strong><br><small class="muted">${escapeHtml(norm.code)}</small></td>
                <td>${norm.termDays} ${escapeHtml(COUNTING_LABELS[norm.counting] || norm.counting)}</td>
                <td>${norm.maxExtensionDays ? `до ${norm.maxExtensionDays} раб. дн.` : "нет"}</td>
                <td>${escapeHtml(norm.legalBasis)}</td>
                <td>${formatDate(norm.validFrom)} – ${norm.validTo ? formatDate(norm.validTo) : "…"}${current ? "" : norm.validFrom > today ? "<br><small>будущая версия</small>" : ""}</td>
            </tr>`;
        }).join("")}</tbody>`;
    const form = $("#norm-add");
    const codes = [...new Map(norms.map(norm => [norm.code, norm.title])).entries()];
    form.code.innerHTML = options(codes);
    form.counting.innerHTML = options(Object.entries(COUNTING_LABELS));
    const tomorrow = new Date(Date.now() + 86400000).toISOString().slice(0, 10);
    form.validFrom.min = tomorrow;
}

$("#norm-add").addEventListener("submit", event => {
    event.preventDefault();
    const form = event.target;
    (async () => {
        await api("/api/admin/norms", {method: "POST", body: JSON.stringify({
            code: form.code.value, termDays: Number(form.termDays.value), counting: form.counting.value,
            maxExtensionDays: Number(form.maxExtensionDays.value), legalBasis: form.legalBasis.value,
            validFrom: form.validFrom.value, reason: settingsReason()
        })});
        showToast("Новая версия норматива добавлена");
        form.reset();
        await loadSettings();
    })().catch(error => showToast(error.message, true));
});

// ---------- помощник сотрудника (решение 35) ----------

const ASSISTANT_ENGINES = {
    RULES: ["Правила и поиск", "Сигналы в тексте, «похоже на претензию», похожие решённые обращения, шаблоны ответов"],
    LOCAL_MODEL: ["Локальная модель", "Те же подсказки и черновики пишет модель в контуре страховой; при сбое работают правила"]
};
const MODEL_STATE = {CLOSED: "отвечает", HALF_OPEN: "проверяется после сбоя", OPEN: "недоступна, работают правила"};

async function loadAssistantSettings() {
    renderAssistantSettings(await api("/api/admin/assistant"));
}

function renderAssistantSettings(settings) {
    $("#assistant-settings").innerHTML = `
        <form id="assistant-form" class="engine-choice">
            ${Object.entries(ASSISTANT_ENGINES).map(([code, [label, note]]) => `
                <label class="engine-option ${settings.engine === code ? "selected" : ""}">
                    <input type="radio" name="engine" value="${code}" ${settings.engine === code ? "checked" : ""}>
                    <span><strong>${label}</strong><small class="muted">${note}</small></span>
                </label>`).join("")}
            <div class="engine-save">
                <label>Причина изменения, попадёт в журнал
                    <input name="reason" maxlength="500" value="${escapeHtml($("#settings-reason").value)}"
                           placeholder="Например: пилот локальной модели в отделе убытков">
                </label>
                <button class="btn" type="submit">Сохранить выбор</button>
            </div>
        </form>
        <p class="muted small-text">Модель: <code>${escapeHtml(settings.modelName)}</code> по адресу <code>${escapeHtml(settings.modelUrl)}</code>${
            settings.engine === "LOCAL_MODEL" ? ` · ${MODEL_STATE[settings.modelState] || settings.modelState}` : ""}.
            Адрес и модель задаются при развёртывании (ASSISTANT_MODEL_URL, ASSISTANT_MODEL).</p>`;
}

// рамка у отмеченного варианта, а не у сохранённого
$("#assistant-settings").addEventListener("change", event => {
    if (event.target.name !== "engine") return;
    document.querySelectorAll(".engine-option").forEach(option =>
        option.classList.toggle("selected", option.contains(event.target)));
});

$("#assistant-settings").addEventListener("submit", async event => {
    event.preventDefault();
    try {
        const form = new FormData(event.target);
        const reason = form.get("reason").trim();
        if (reason.length < 5) {
            event.target.elements.reason.focus();
            throw new Error("Укажите причину изменения: она попадёт в журнал");
        }
        const engine = form.get("engine");
        renderAssistantSettings(await api("/api/admin/assistant", {method: "PUT", body: JSON.stringify({engine, reason})}));
        showToast(`Подсказки даёт: ${ASSISTANT_ENGINES[engine][0].toLowerCase()}`);
        await loadSettings();
    } catch (error) {
        showToast(error.message, true);
    }
});

function describeChange(entry) {
    const show = text => text ? Object.entries(JSON.parse(text)).map(([key, value]) => `${key}: ${value}`).join(", ") : "—";
    return entry.action === "UPDATED" ? `${show(entry.before)} → ${show(entry.after)}`
        : entry.action === "DELETED" ? `было: ${show(entry.before)}` : show(entry.after);
}

function renderAudit(entries) {
    $("#settings-audit").innerHTML = entries.length ? `
        <div class="table-scroll"><table class="table settings-table">
            <thead><tr><th>Когда</th><th>Кто</th><th>Что</th><th>Изменение</th><th>Причина</th></tr></thead>
            <tbody>${entries.map(entry => `<tr>
                <td>${formatDateTime(entry.changedAt)}</td>
                <td>${escapeHtml(entry.actorName)}</td>
                <td>${escapeHtml(entry.entityKey)}<br><small class="muted">${ACTION_LABELS[entry.action] || entry.action}</small></td>
                <td><small>${escapeHtml(describeChange(entry))}</small></td>
                <td>${escapeHtml(entry.reason)}</td>
            </tr>`).join("")}</tbody></table></div>` : emptyState("Изменений пока не было", "Каждое изменение правил и нормативов появится здесь с причиной");
}

