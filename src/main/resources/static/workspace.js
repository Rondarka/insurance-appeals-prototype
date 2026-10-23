// Рабочее место сотрудника: очередь своего отдела, карточка с перепиской,
// кнопки следующего шага (их список отдаёт сервер), передачи для руководителя.

const GROUPS = [
    {key: "new", label: "Новые", statuses: ["ROUTED"]},
    {key: "work", label: "В работе", statuses: ["IN_PROGRESS"]},
    {key: "waiting", label: "Ждут клиента", statuses: ["WAITING_CUSTOMER"]},
    {key: "done", label: "Решённые", statuses: ["RESOLVED", "CLOSED"]},
    // правила маршрутизации нет — отдел выбирает руководитель (решение 31)
    {key: "classify", label: "Классификация", statuses: ["NEEDS_CLASSIFICATION"], supervisorOnly: true}
];

const ws = {
    appeals: [], group: "new", openedId: null, departments: [], staff: [], pendingTransfers: 0,
    scope: null, search: "", transferForm: false, resolveForm: false, extendForm: false
};
const $ = selector => document.querySelector(selector);
const isSupervisor = () => hasRole("SUPERVISOR");
const isOpen = appeal => !["RESOLVED", "CLOSED"].includes(appeal.status);
/** «Моё» — по идентификатору в IdP, а не по имени (решение 32). */
const isMine = appeal => appeal.assignedSubject === session.me.subject;

startPage("/workspace.html", async () => {
    ws.departments = await api("/api/departments");
    ws.staff = await api("/api/staff").catch(() => []);
    // специалисту — свои и новые, руководителю — весь отдел
    ws.scope = isSupervisor() ? "all" : "mine";
    document.querySelector('[data-tab="transfers"]').hidden = !isSupervisor();
    await loadQueue();
    if (isSupervisor()) await loadTransfers();
    renderSummary();
    window.setInterval(refresh, 10000);
});

// ---------- вкладки ----------

document.querySelector(".tabs").addEventListener("click", event => {
    const tab = event.target.closest("[data-tab]");
    if (tab) showTab(tab.dataset.tab);
});

function showTab(name) {
    document.querySelectorAll(".tab").forEach(item => item.classList.toggle("active", item.dataset.tab === name));
    document.querySelectorAll(".view").forEach(item => item.classList.toggle("active", item.id === `${name}-view`));
    if (name === "events") loadEvents($("#events")).catch(error => showToast(error.message, true));
    if (name === "transfers") loadTransfers().catch(error => showToast(error.message, true));
    if (name === "calendar") loadCalendar().catch(error => showToast(error.message, true));
    if (name === "summary") renderSummary();
}

$("#refresh-events").addEventListener("click", () => loadEvents($("#events")).catch(error => showToast(error.message, true)));

// ---------- очередь ----------

async function loadQueue() {
    ws.appeals = await api("/api/appeals");
    renderQueue();
}

/** Отпечаток обращения: изменился статус, переписка или история — значит, есть что показать. */
const cardSignature = appeal => `${appeal.status}|${appeal.updatedAt}|${appeal.messages.length}|${appeal.history.length}|${appeal.attachments.length}`;

function groupOf(status) {
    return GROUPS.find(group => group.statuses.includes(status));
}

/** Что видно в очереди: «Мои» — назначенные мне и новые без исполнителя; поиск — по номеру, теме, клиенту. */
function visibleAppeals() {
    const text = ws.search.trim().toLowerCase();
    return ws.appeals
        .filter(appeal => ws.scope !== "mine" || isMine(appeal) || !appeal.assignedSubject)
        .filter(appeal => !text || `${appeal.publicNumber} ${appeal.subject} ${appeal.customerName}`.toLowerCase().includes(text));
}

function assigneeLabel(appeal) {
    if (isMine(appeal)) return "вы";
    if (!appeal.assignedEmployee) return "без исполнителя";
    const [last, first] = appeal.assignedEmployee.split(" ").reverse();
    return first ? `${first[0]}. ${last}` : appeal.assignedEmployee;
}

function renderQueue() {
    document.querySelectorAll("[data-queue-scope]").forEach(item =>
        item.classList.toggle("active", item.dataset.queueScope === ws.scope));
    const visible = visibleAppeals();
    $("#status-tabs").innerHTML = GROUPS.filter(group => !group.supervisorOnly || isSupervisor()).map(group => {
        const count = visible.filter(appeal => group.statuses.includes(appeal.status)).length;
        return `<button type="button" class="status-tab ${group.key === ws.group ? "active" : ""}" data-group="${group.key}">
            ${group.label}<span class="count">${count}</span></button>`;
    }).join("");

    const group = GROUPS.find(item => item.key === ws.group);
    // сверху то, что сгорит первым: сначала эскалации по сроку закона; решённые — свежие сверху
    const urgency = appeal => ({BREACHED: 0, AT_RISK: 1}[appeal.escalationLevel] ?? 2);
    const items = visible
        .filter(appeal => group.statuses.includes(appeal.status))
        .sort((a, b) => group.key === "done"
            ? new Date(b.updatedAt) - new Date(a.updatedAt)
            : urgency(a) - urgency(b) || new Date(a.deadlineAt || 8.64e15) - new Date(b.deadlineAt || 8.64e15));

    $("#queue").innerHTML = items.length ? items.map(appeal => `
        <button type="button" class="queue-item ${appeal.id === ws.openedId ? "selected" : ""}" data-appeal="${appeal.id}">
            <span class="queue-row">${priorityMark(appeal.priority)}<strong>${escapeHtml(appeal.subject)}</strong>
                ${appeal.clientReplied && isOpen(appeal) ? `<span class="badge reply">клиент ответил</span>` : ""}</span>
            <span class="queue-row sub"><small>${escapeHtml(appeal.publicNumber)} · ${escapeHtml(typeLabel(appeal.category, appeal.subcategory))}</small></span>
            <span class="queue-row meta"><small>${escapeHtml(appeal.customerName)} · <span class="${appeal.assignedSubject ? "" : "unassigned"}">${escapeHtml(assigneeLabel(appeal))}</span></small>${isEscalated(appeal) ? escalationPill(appeal) : deadlinePill(appeal)}</span>
        </button>`).join("") : `<p class="empty">${ws.search ? "Ничего не найдено" : "Здесь пусто"}</p>`;
}

$("#queue-scope").addEventListener("click", event => {
    const button = event.target.closest("[data-queue-scope]");
    if (!button) return;
    ws.scope = button.dataset.queueScope;
    renderQueue();
});

$("#queue-search").addEventListener("input", event => {
    ws.search = event.target.value;
    renderQueue();
});

/** Перейти в очередь к нужной группе — из сводки. */
async function jumpToQueue(group, scope, appealId) {
    ws.group = group;
    if (scope) ws.scope = scope;
    ws.search = "";
    $("#queue-search").value = "";
    showTab("queue");
    renderQueue();
    if (appealId) await openCard(appealId);
}

// ---------- сводка «Мой день» ----------

/** Почему обращение требует внимания сейчас — по убыванию срочности. */
function attentionReason(appeal) {
    if (appeal.status === "NEEDS_CLASSIFICATION") return {rank: 0, tone: "late", text: "нужно выбрать отдел"};
    if (appeal.escalationLevel === "BREACHED" || isLegallyOverdue(appeal)) return {rank: 1, tone: "late", text: "нарушен срок по закону"};
    if (appeal.escalationLevel === "AT_RISK") return {rank: 2, tone: "soon", text: `по закону до ${formatDate(appeal.legalDeadline)}`};
    if (appeal.clientReplied) return {rank: 3, tone: "reply", text: "клиент ответил"};
    if (appeal.status !== "WAITING_CUSTOMER" && appeal.deadlineAt && new Date(appeal.deadlineAt) < new Date()) {
        return {rank: 4, tone: "soon", text: "просрочен срок отдела"};
    }
    if (appeal.status === "ROUTED" && !appeal.assignedSubject) return {rank: 5, tone: "", text: "новое, без исполнителя"};
    return null;
}

function greeting() {
    const hour = new Date().getHours();
    return hour < 12 ? "Доброе утро" : hour < 18 ? "Добрый день" : "Добрый вечер";
}

function renderSummary() {
    const box = $("#summary");
    if (!box || !session.me) return;
    const open = ws.appeals.filter(isOpen);
    // специалисту — свои и ничьи, руководителю — весь отдел
    const scoped = isSupervisor() ? open : open.filter(appeal => isMine(appeal) || !appeal.assignedSubject);
    const legallyLate = appeal => appeal.escalationLevel === "BREACHED" || isLegallyOverdue(appeal);
    const tiles = [
        ["Мои в работе", open.filter(a => isMine(a) && ["IN_PROGRESS", "WAITING_CUSTOMER"].includes(a.status)).length, "", "work", "mine"],
        ["Клиент ответил", scoped.filter(a => a.clientReplied).length, "reply", "work", isSupervisor() ? "all" : "mine"],
        ["Новые без исполнителя", open.filter(a => a.status === "ROUTED" && !a.assignedSubject).length, "", "new", "all"],
        ["Срок по закону истекает", scoped.filter(a => a.escalationLevel === "AT_RISK").length, "soon", "work", "all"],
        ["Нарушен срок по закону", scoped.filter(legallyLate).length, "late", "work", "all"]
    ];
    const tasks = scoped
        .map(appeal => ({appeal, reason: attentionReason(appeal)}))
        .filter(task => task.reason)
        .sort((a, b) => a.reason.rank - b.reason.rank
            || new Date(a.appeal.deadlineAt || 8.64e15) - new Date(b.appeal.deadlineAt || 8.64e15));
    const firstName = (session.me.name || "").split(" ")[0];
    const today = new Date().toLocaleDateString("ru-RU", {weekday: "long", day: "numeric", month: "long"});

    box.innerHTML = `
        <div class="view-head">
            <div>
                <h1>${greeting()}${firstName ? `, ${escapeHtml(firstName)}` : ""}</h1>
                <p class="muted">${escapeHtml(today)} · ${escapeHtml(session.me.department?.name || "")}</p>
            </div>
            <button class="btn" type="button" data-jump-group="new" data-jump-scope="${ws.scope}">Открыть очередь</button>
        </div>
        <div class="metrics summary-metrics">${tiles.map(([label, value, tone, group, scope]) => `
            <button type="button" class="metric clickable ${value && tone ? tone : ""}" data-jump-group="${group}" data-jump-scope="${scope}">
                <small>${label}</small><strong>${value}</strong></button>`).join("")}</div>
        <div class="summary-grid">
            <section class="panel">
                <h2>Что сделать сейчас</h2>
                ${tasks.length ? `<ul class="task-list">${tasks.slice(0, 8).map(({appeal, reason}) => `
                    <li><button type="button" class="task" data-jump-appeal="${appeal.id}">
                        <span class="task-main"><strong>${escapeHtml(appeal.subject)}</strong>
                            <small class="muted">${escapeHtml(appeal.publicNumber)} · ${escapeHtml(typeLabel(appeal.category, appeal.subcategory))} · ${escapeHtml(assigneeLabel(appeal))}</small></span>
                        <span class="badge ${reason.tone}">${escapeHtml(reason.text)}</span>
                    </button></li>`).join("")}</ul>
                    ${tasks.length > 8 ? `<p class="muted small-text">И ещё ${tasks.length - 8} — в очереди.</p>` : ""}`
                    : `<p class="muted">Срочного нет. Можно разобрать новые обращения.</p>`}
            </section>
            ${isSupervisor() ? renderDepartmentLoad(open) : renderMyStats()}
        </div>`;
}

/** Руководителю — кто чем занят и что ждёт его решения. */
function renderDepartmentLoad(open) {
    const rows = new Map();
    ws.staff.forEach(member => rows.set(member.id, {name: member.fullName, items: []}));
    const unassigned = {name: "Без исполнителя", items: []};
    open.filter(appeal => appeal.department?.code === session.me.department?.code).forEach(appeal => {
        if (!appeal.assignedSubject) return unassigned.items.push(appeal);
        if (!rows.has(appeal.assignedSubject)) rows.set(appeal.assignedSubject, {name: appeal.assignedEmployee, items: []});
        rows.get(appeal.assignedSubject).items.push(appeal);
    });
    const count = (items, predicate) => items.filter(predicate).length || "";
    const atRisk = appeal => isEscalated(appeal) || isLegallyOverdue(appeal);
    const line = ({name, items}) => `
        <tr><td>${escapeHtml(name)}</td>
            <td>${count(items, a => a.status === "ROUTED")}</td>
            <td>${count(items, a => a.status === "IN_PROGRESS")}</td>
            <td>${count(items, a => a.status === "WAITING_CUSTOMER")}</td>
            <td>${count(items, a => a.clientReplied)}</td>
            <td class="${items.some(atRisk) ? "late-text" : ""}">${count(items, atRisk)}</td></tr>`;
    const classification = open.filter(appeal => appeal.status === "NEEDS_CLASSIFICATION").length;
    return `
        <section class="panel">
            <h2>Отдел</h2>
            <table class="table">
                <thead><tr><th>Сотрудник</th><th>Новые</th><th>В работе</th><th>Ждут клиента</th><th>Клиент ответил</th><th>Срок по закону</th></tr></thead>
                <tbody>${[...rows.values()].map(line).join("")}${unassigned.items.length ? line(unassigned) : ""}</tbody>
            </table>
            <div class="summary-links">
                <button type="button" class="link" data-tab-link="transfers">Входящие передачи: ${ws.pendingTransfers}</button>
                ${classification ? `<button type="button" class="link late-text" data-jump-group="classify" data-jump-scope="all">Ждут классификации: ${classification}</button>` : ""}
            </div>
        </section>`;
}

/** Специалисту — его обращения. */
function renderMyStats() {
    const mine = ws.appeals.filter(isMine);
    const stat = (label, value) => `<li><span>${label}</span><strong>${value}</strong></li>`;
    return `
        <section class="panel">
            <h2>Мои обращения</h2>
            <ul class="stat-list">
                ${stat("В работе", mine.filter(a => a.status === "IN_PROGRESS").length)}
                ${stat("Ждут ответа клиента", mine.filter(a => a.status === "WAITING_CUSTOMER").length)}
                ${stat("Клиент ответил", mine.filter(a => isOpen(a) && a.clientReplied).length)}
                ${stat("Решено", mine.filter(a => !isOpen(a)).length)}
            </ul>
        </section>`;
}

$("#summary").addEventListener("click", event => {
    const jump = event.target.closest("[data-jump-group]");
    const task = event.target.closest("[data-jump-appeal]");
    const tab = event.target.closest("[data-tab-link]");
    if (tab) showTab(tab.dataset.tabLink);
    if (jump) jumpToQueue(jump.dataset.jumpGroup, jump.dataset.jumpScope).catch(error => showToast(error.message, true));
    if (task) {
        const appeal = ws.appeals.find(item => item.id === task.dataset.jumpAppeal);
        // в очереди обращение должно быть видно: включаем «Все», если оно чужое
        const scope = appeal && (isMine(appeal) || !appeal.assignedSubject) ? ws.scope : "all";
        jumpToQueue(groupOf(appeal.status).key, scope, appeal.id).catch(error => showToast(error.message, true));
    }
});

$("#status-tabs").addEventListener("click", event => {
    const tab = event.target.closest("[data-group]");
    if (!tab) return;
    ws.group = tab.dataset.group;
    renderQueue();
});

$("#queue").addEventListener("click", event => {
    const item = event.target.closest("[data-appeal]");
    if (item) openCard(item.dataset.appeal).catch(error => showToast(error.message, true));
});

// ---------- карточка ----------

async function openCard(id) {
    const appeal = await api(`/api/appeals/${id}`);
    if (ws.openedId !== id) {
        ws.transferForm = false;
        ws.resolveForm = false;
        ws.extendForm = false;
    }
    ws.openedId = id;
    ws.openedSignature = cardSignature(appeal);
    $("#card").innerHTML = renderCard(appeal);
    loadRelated(id, $(`#related-${id}`));
    renderQueue();
}

/** Итоговый тип (решение 23): все типы по категориям, текущий выбран. */
function finalTypeOptions(appeal) {
    return session.schema.categories.map(category => `<optgroup label="${escapeHtml(category.label)}">${category.types
        .map(type => {
            const value = `${category.code}/${type.code}`;
            const selected = category.code === appeal.category && type.code === appeal.subcategory ? " selected" : "";
            return `<option value="${value}"${selected}>${escapeHtml(type.label)}</option>`;
        }).join("")}</optgroup>`).join("");
}

function typeCorrected(appeal) {
    return appeal.finalCategory && (appeal.finalCategory !== appeal.category || appeal.finalSubcategory !== appeal.subcategory);
}

function actionLabel(target, current) {
    if (target === "IN_PROGRESS") return current === "ROUTED" ? "Взять в работу" : "Вернуть в работу";
    if (target === "RESOLVED") return "Решено — ответить по существу";
    if (target === "CLOSED") return "Закрыть обращение";
    return STATUS_LABELS[target];
}

function renderCard(appeal) {
    const canWork = appeal.department?.code === session.me.department?.code;
    const pending = appeal.transfers.find(item => item.status === "PENDING");
    // «ждём клиента» — не отдельная кнопка, а способ отправить ответ
    const steps = appeal.nextStatuses.filter(status => status !== "WAITING_CUSTOMER");
    const canTransfer = canWork && !pending && ["ROUTED", "IN_PROGRESS", "WAITING_CUSTOMER"].includes(appeal.status);

    const actions = canWork ? `
        <div class="actions">
            <h4>Следующий шаг</h4>
            ${steps.map(status => status === "RESOLVED"
                ? `<button type="button" class="btn primary wide" data-toggle-resolve>${actionLabel(status, appeal.status)}</button>`
                : `<button type="button" class="btn wide" data-status="${status}">${actionLabel(status, appeal.status)}</button>`).join("")}
            ${canTransfer ? `<button type="button" class="btn ghost wide" data-toggle-transfer>Передать в другой отдел</button>` : ""}
            ${pending ? `<p class="notice small">Ждёт решения руководителя отдела «${escapeHtml(pending.targetDepartment.name)}»</p>` : ""}
            ${ws.transferForm && canTransfer ? `
                <form class="transfer-form" data-transfer>
                    <label>Куда
                        <select name="departmentCode">${ws.departments
                            .filter(item => item.code !== appeal.department.code)
                            .map(item => `<option value="${item.code}">${escapeHtml(item.name)}</option>`).join("")}</select>
                    </label>
                    <label>Почему
                        <input name="reason" required maxlength="500" placeholder="Нужна компетенция другого отдела">
                    </label>
                    <button class="btn primary" type="submit">Отправить руководителю</button>
                </form>` : ""}
        </div>` : appeal.status === "NEEDS_CLASSIFICATION" && isSupervisor() ? `
        <div class="actions">
            <h4>Ручная классификация</h4>
            <p class="notice small">Для типа «${escapeHtml(typeLabel(appeal.category, appeal.subcategory))}» нет правила
                маршрутизации. Выберите отдел — обращение уйдёт туда с обычным сроком отдела.</p>
            <form class="transfer-form" data-classify>
                <label>Отдел
                    <select name="departmentCode">${ws.departments
                        .map(item => `<option value="${item.code}">${escapeHtml(item.name)}</option>`).join("")}</select>
                </label>
                <button class="btn primary" type="submit">Направить в отдел</button>
            </form>
        </div>` : `<p class="notice small">Обращение другого отдела — только просмотр.</p>`;

    return `
        ${appealHeading(appeal)}
        <div class="card-body">
            <div class="main-col">
                ${renderChecks(appeal)}
                ${renderThread(appeal, "EMPLOYEE")}
                ${appeal.outcome ? `<p class="notice small">Итог: ${escapeHtml(OUTCOME_LABELS[appeal.outcome])}${typeCorrected(appeal)
                    ? ` · тип уточнён: ${escapeHtml(categoryLabel(appeal.finalCategory))} — ${escapeHtml(typeLabel(appeal.finalCategory, appeal.finalSubcategory))}`
                    : ""}</p>` : ""}
                ${canWork && ws.resolveForm && appeal.nextStatuses.includes("RESOLVED") ? `
                <form class="reply resolve-form" data-resolve>
                    <h4>Ответ клиенту по существу</h4>
                    <p class="muted small-text">Закон требует обоснованный ответ: результат рассмотрения, на каком основании,
                        что сделано (Закон № 4015-1, ст. 6.2 п. 6). Сама выплата или перерасчёт выполняются в учётных системах
                        страховой — здесь фиксируется результат.</p>
                    <label>Итог
                        <select name="outcome">${Object.entries(OUTCOME_LABELS)
                            .map(([code, label]) => `<option value="${code}">${label}</option>`).join("")}</select>
                    </label>
                    <label>Тип обращения по итогам рассмотрения
                        <select name="finalType">${finalTypeOptions(appeal)}</select>
                        <small class="muted">Измените, если клиент выбрал не тот тип: по итоговому типу строится аналитика.</small>
                    </label>
                    <textarea name="answer" rows="5" minlength="20" maxlength="5000" required
                              placeholder="Например: ущерб признан страховым случаем, выплата 85 000 ₽ направлена на ваш счёт 20.10.2026"></textarea>
                    <div class="reply-actions">
                        <button class="btn" type="button" data-toggle-resolve>Отмена</button>
                        <button class="btn primary" type="submit">Отправить ответ и решить</button>
                    </div>
                </form>` : canWork && appeal.status !== "CLOSED" ? `
                <form class="reply" data-reply>
                    <textarea name="body" rows="3" maxlength="5000" placeholder="Ответ клиенту"></textarea>
                    <div class="reply-actions">
                        ${attachField()}
                        ${appeal.nextStatuses.includes("WAITING_CUSTOMER")
                            ? `<button class="btn" type="submit" name="wait" value="1">Отправить и ждать ответа</button>` : ""}
                        <button class="btn primary" type="submit">Отправить</button>
                    </div>
                </form>` : ""}
            </div>
            <aside class="side-col">
                ${actions}
                <h4>Срок по закону</h4>${renderNorm(appeal)}${renderExtension(appeal, canWork)}
                <h4>Клиент</h4>
                <p>${escapeHtml(appeal.customerName)}<br><small class="muted">${escapeHtml(appeal.customerEmail)}</small></p>
                <h4>Исполнитель</h4>${renderAssignee(appeal, canWork)}
                ${renderContractSection(appeal, canWork)}
                <h4>Данные обращения</h4>${renderDetails(appeal)}
                <h4>Файлы</h4>${renderAttachments(appeal)}
                ${renderRelatedSection(appeal)}
                <details class="more">
                    <summary>Журнал обработки · ${appeal.history.length}</summary>
                    ${renderHistory(appeal)}${renderTransfers(appeal)}
                </details>
            </aside>
        </div>`;
}

/** Продлить срок по закону может руководитель отдела — один раз и пока срок не истёк. */
const canExtend = appeal => appeal.norm?.maxExtensionDays > 0 && !appeal.deadlineExtendedDays
    && !["RESOLVED", "CLOSED"].includes(appeal.status) && !isLegallyOverdue(appeal);

function renderExtension(appeal, canWork) {
    if (!canWork || !canExtend(appeal)) return "";
    const max = appeal.norm.maxExtensionDays;
    if (!hasRole("SUPERVISOR")) {
        return `<p class="muted small-text">Не успеваете из-за документов? Руководитель отдела может продлить срок
            до ${max} раб. дн.</p>`;
    }
    if (!ws.extendForm) {
        return `<button type="button" class="btn ghost wide" data-toggle-extend>Продлить срок</button>`;
    }
    return `
        <form class="transfer-form" data-extend>
            <label>На сколько рабочих дней
                <select name="days">${Array.from({length: max}, (_, index) => max - index)
                    .map(days => `<option value="${days}">${days}</option>`).join("")}</select>
            </label>
            <label>Причина — её увидит клиент
                <textarea name="reason" rows="3" minlength="10" maxlength="1000" required
                          placeholder="Какие документы нужны, у кого запрошены и когда ждём ответ"></textarea>
            </label>
            <p class="muted small-text">Закон допускает продление до ${max} рабочих дней, если нужны
                дополнительные документы (Закон № 4015-1, ст. 6.2 п. 5).</p>
            <div class="reply-actions">
                <button class="btn" type="button" data-toggle-extend>Отмена</button>
                <button class="btn primary" type="submit">Продлить и уведомить</button>
            </div>
        </form>`;
}

/** Кто ведёт обращение; руководитель отдела назначает исполнителя (решение 32). */
function renderAssignee(appeal, canWork) {
    const current = appeal.assignedEmployee
        ? `<p>${escapeHtml(appeal.assignedEmployee)}${appeal.assignedSubject === session.me.subject ? " <small class=\"muted\">(вы)</small>" : ""}</p>`
        : `<p class="muted">Не назначен</p>`;
    if (!canWork || !isSupervisor() || !isOpen(appeal) || !ws.staff.length) return current;
    return `${current}
        <form class="assign-form" data-assign>
            <select name="subject" aria-label="Исполнитель">${ws.staff.map(member => `
                <option value="${escapeHtml(member.id)}" ${member.id === appeal.assignedSubject ? "selected" : ""}>
                    ${escapeHtml(member.fullName)}${member.supervisor ? " — руководитель" : ""}</option>`).join("")}</select>
            <button class="btn" type="submit">Назначить</button>
        </form>`;
}

async function changeStatus(status) {
    await api(`/api/appeals/${ws.openedId}/status`, {method: "POST", body: JSON.stringify({status})});
}

$("#card").addEventListener("click", async event => {
    const related = event.target.closest("[data-open-related]");
    if (related) {
        const appeal = ws.appeals.find(item => item.id === related.dataset.openRelated);
        if (appeal) ws.group = groupOf(appeal.status).key;
        openCard(related.dataset.openRelated).catch(error => showToast(error.message, true));
        return;
    }
    const step = event.target.closest("[data-status]");
    const toggle = event.target.closest("[data-toggle-transfer]");
    const toggleResolve = event.target.closest("[data-toggle-resolve]");
    const toggleExtend = event.target.closest("[data-toggle-extend]");
    try {
        if (toggleExtend) {
            ws.extendForm = !ws.extendForm;
            await openCard(ws.openedId);
        }
        if (toggleResolve) {
            ws.resolveForm = !ws.resolveForm;
            await openCard(ws.openedId);
        }
        if (step) {
            await changeStatus(step.dataset.status);
            showToast(`Статус: ${STATUS_LABELS[step.dataset.status]}`);
            await refreshOpened();
        }
        if (toggle) {
            ws.transferForm = !ws.transferForm;
            await openCard(ws.openedId);
        }
    } catch (error) {
        showToast(error.message, true);
    }
});

$("#card").addEventListener("submit", async event => {
    event.preventDefault();
    const form = new FormData(event.target);
    try {
        if (event.target.matches("[data-reply]")) {
            const data = replyFormData(event.target);
            if (!data) {
                showToast("Напишите ответ или приложите файл", true);
                return;
            }
            await api(`/api/appeals/${ws.openedId}/messages`, {method: "POST", body: data});
            if (event.submitter?.name === "wait") await changeStatus("WAITING_CUSTOMER");
            showToast(event.submitter?.name === "wait" ? "Ответ отправлен, ждём клиента" : "Ответ отправлен");
        }
        if (event.target.matches("[data-resolve]")) {
            await api(`/api/appeals/${ws.openedId}/resolve`, {
                method: "POST",
                body: JSON.stringify({outcome: form.get("outcome"), answer: form.get("answer"),
                    finalCategory: form.get("finalType").split("/")[0], finalSubcategory: form.get("finalType").split("/")[1]})
            });
            ws.resolveForm = false;
            showToast("Ответ отправлен клиенту, обращение решено");
        }
        if (event.target.matches("[data-assign]")) {
            const updated = await api(`/api/appeals/${ws.openedId}/assign`, {
                method: "POST", body: JSON.stringify({subject: form.get("subject")})
            });
            showToast(`Исполнитель: ${updated.assignedEmployee}`);
        }
        if (event.target.matches("[data-classify]")) {
            const result = await api(`/api/appeals/${ws.openedId}/classify`, {
                method: "POST", body: JSON.stringify({departmentCode: form.get("departmentCode")})
            });
            showToast(`Направлено в отдел «${result.departmentName}»`);
            if (result.departmentCode !== session.me.department?.code) {
                // обращение ушло в чужой отдел — в своей очереди его больше нет
                ws.openedId = null;
                $("#card").innerHTML = `<p class="empty">Выберите обращение в списке слева</p>`;
                await loadQueue();
                return;
            }
        }
        if (event.target.matches("[data-extend]")) {
            const updated = await api(`/api/appeals/${ws.openedId}/extend-deadline`, {
                method: "POST",
                body: JSON.stringify({days: Number(form.get("days")), reason: form.get("reason")})
            });
            ws.extendForm = false;
            showToast(`Срок продлён до ${formatDate(updated.legalDeadline)}, клиент уведомлён`);
        }
        if (event.target.matches("[data-transfer]")) {
            await api(`/api/appeals/${ws.openedId}/transfer-requests`, {
                method: "POST",
                body: JSON.stringify({departmentCode: form.get("departmentCode"), reason: form.get("reason")})
            });
            ws.transferForm = false;
            showToast("Запрос отправлен руководителю принимающего отдела");
        }
        await refreshOpened();
    } catch (error) {
        showToast(error.message, true);
    }
});

async function afterContractLinked(id) {
    if (id === ws.openedId) await refreshOpened();
}

async function refreshOpened() {
    await loadQueue();
    const opened = ws.appeals.find(appeal => appeal.id === ws.openedId);
    // обращение ушло в другую группу — переключаемся вслед за ним
    if (opened) ws.group = groupOf(opened.status).key;
    await openCard(ws.openedId);
}

// ---------- календарь сроков ----------

const MONTHS = ["Январь", "Февраль", "Март", "Апрель", "Май", "Июнь",
    "Июль", "Август", "Сентябрь", "Октябрь", "Ноябрь", "Декабрь"];
const WEEKDAYS = ["Пн", "Вт", "Ср", "Чт", "Пт", "Сб", "Вс"];
const cal = {month: null, scope: null};

const isoDay = date => `${date.getFullYear()}-${String(date.getMonth() + 1).padStart(2, "0")}-${String(date.getDate()).padStart(2, "0")}`;
const addDays = (date, days) => new Date(date.getFullYear(), date.getMonth(), date.getDate() + days);

/** Последний день срока: по закону, а у старых обращений без норматива — срок отдела. */
function dueDay(appeal) {
    if (appeal.legalDeadline) return appeal.legalDeadline;
    return appeal.deadlineAt ? isoDay(new Date(appeal.deadlineAt)) : null;
}

function dueTone(day, today) {
    if (day < today) return "late";
    return day <= isoDay(addDays(new Date(`${today}T00:00:00`), 2)) ? "soon" : "ok";
}

async function loadCalendar() {
    const now = new Date();
    if (!cal.month) cal.month = new Date(now.getFullYear(), now.getMonth(), 1);
    // специалисту — свои обращения, руководителю — весь отдел
    if (!cal.scope) cal.scope = isSupervisor() ? "department" : "mine";
    document.querySelectorAll("[data-scope]").forEach(item => item.classList.toggle("active", item.dataset.scope === cal.scope));
    $("#calendar-title").textContent = `${MONTHS[cal.month.getMonth()]} ${cal.month.getFullYear()}`;

    const first = addDays(cal.month, -((cal.month.getDay() + 6) % 7));
    const last = addDays(first, 41);
    const days = await api(`/api/calendar?from=${isoDay(first)}&to=${isoDay(last)}`);
    renderCalendar(days);
}

function renderCalendar(days) {
    const today = isoDay(new Date());
    const open = ws.appeals.filter(appeal => !["RESOLVED", "CLOSED"].includes(appeal.status) && dueDay(appeal));
    const scoped = cal.scope === "mine" ? open.filter(isMine) : open;
    const byDay = {};
    scoped.forEach(appeal => (byDay[dueDay(appeal)] ||= []).push(appeal));

    const weekEnd = isoDay(addDays(new Date(`${today}T00:00:00`), 7 - ((new Date().getDay() + 6) % 7) - 1));
    const summary = [
        ["Просрочено", scoped.filter(a => dueDay(a) < today).length, "late"],
        ["Сегодня последний день", scoped.filter(a => dueDay(a) === today).length, "soon"],
        ["До конца недели", scoped.filter(a => dueDay(a) > today && dueDay(a) <= weekEnd).length, ""],
        ["Всего открытых", scoped.length, ""]
    ];
    $("#calendar-summary").innerHTML = summary.map(([label, value, tone]) =>
        `<div class="metric ${value && tone ? tone : ""}"><small>${label}</small><strong>${value}</strong></div>`).join("");

    const month = cal.month.getMonth();
    $("#calendar-grid").innerHTML = WEEKDAYS.map(name => `<div class="calendar-head">${name}</div>`).join("")
        + days.map(day => {
            const date = new Date(`${day.date}T00:00:00`);
            const items = (byDay[day.date] || []).sort((a, b) => a.publicNumber.localeCompare(b.publicNumber));
            const classes = ["calendar-day", day.kind.toLowerCase(),
                date.getMonth() !== month ? "other-month" : "", day.date === today ? "today" : ""].join(" ");
            return `
                <div class="${classes}">
                    <div class="calendar-date">${date.getDate()}${day.kind === "HOLIDAY" ? `<small>праздник</small>` : ""}</div>
                    ${items.slice(0, 3).map(appeal => `
                        <button type="button" class="calendar-item ${dueTone(day.date, today)}" data-calendar-appeal="${appeal.id}"
                                title="${escapeHtml(appeal.publicNumber)} · ${escapeHtml(appeal.subject)} · ${escapeHtml(appeal.customerName)}">
                            ${escapeHtml(appeal.subject)}
                        </button>`).join("")}
                    ${items.length > 3 ? `<small class="muted">ещё ${items.length - 3}</small>` : ""}
                </div>`;
        }).join("");
}

$("#calendar-view").addEventListener("click", event => {
    const scope = event.target.closest("[data-scope]");
    const item = event.target.closest("[data-calendar-appeal]");
    if (scope) cal.scope = scope.dataset.scope;
    if (event.target.closest("#calendar-prev")) cal.month = new Date(cal.month.getFullYear(), cal.month.getMonth() - 1, 1);
    if (event.target.closest("#calendar-next")) cal.month = new Date(cal.month.getFullYear(), cal.month.getMonth() + 1, 1);
    if (event.target.closest("#calendar-today")) cal.month = null;
    if (item) {
        const appeal = ws.appeals.find(candidate => candidate.id === item.dataset.calendarAppeal);
        ws.group = groupOf(appeal.status).key;
        showTab("queue");
        openCard(appeal.id).catch(error => showToast(error.message, true));
        return;
    }
    if (scope || event.target.closest("#calendar-prev, #calendar-next, #calendar-today")) {
        loadCalendar().catch(error => showToast(error.message, true));
    }
});

// ---------- передачи (руководитель) ----------

async function loadTransfers() {
    const transfers = await api("/api/transfer-requests");
    ws.pendingTransfers = transfers.length;
    $("#transfer-count").textContent = transfers.length || "";
    $("#transfers").innerHTML = transfers.length ? transfers.map(item => `
        <article class="panel transfer">
            <div class="transfer-head">
                <div>
                    <small class="muted">${escapeHtml(item.appealPublicNumber)} · из отдела «${escapeHtml(item.sourceDepartment.name)}» · ${formatDateTime(item.createdAt)}</small>
                    <h3>${escapeHtml(item.appealSubject)}</h3>
                    <p>Клиент: ${escapeHtml(item.customerName)}. Запросил: ${escapeHtml(item.requestedBy.fullName)}.</p>
                    <blockquote>${escapeHtml(item.reason)}</blockquote>
                </div>
                <button type="button" class="btn ghost" data-preview="${item.appealId}">Показать обращение</button>
            </div>
            <div class="preview" id="preview-${item.appealId}"></div>
            <form class="review" data-review="${item.id}">
                <input name="comment" maxlength="500" placeholder="Комментарий для отдела «${escapeHtml(item.sourceDepartment.name)}»">
                <button class="btn" type="submit" name="approved" value="false">Отклонить</button>
                <button class="btn primary" type="submit" name="approved" value="true">Принять</button>
            </form>
        </article>`).join("") : `<p class="empty">Входящих передач нет</p>`;
}

$("#transfers").addEventListener("click", async event => {
    const button = event.target.closest("[data-preview]");
    if (!button) return;
    const target = $(`#preview-${button.dataset.preview}`);
    if (target.innerHTML) {
        target.innerHTML = "";
        return;
    }
    try {
        const appeal = await api(`/api/appeals/${button.dataset.preview}`);
        target.innerHTML = `${renderThread(appeal, "EMPLOYEE")}${renderDetails(appeal)}`;
    } catch (error) {
        showToast(error.message, true);
    }
});

$("#transfers").addEventListener("submit", async event => {
    event.preventDefault();
    const approved = event.submitter?.value === "true";
    const comment = new FormData(event.target).get("comment").trim()
        || (approved ? "Принимаем в работу" : "Не профиль нашего отдела");
    try {
        await api(`/api/transfer-requests/${event.target.dataset.review}/review`, {
            method: "POST", body: JSON.stringify({approved, comment})
        });
        showToast(approved ? "Передача принята: обращение в вашей очереди" : "Передача отклонена");
        await Promise.all([loadTransfers(), loadQueue()]);
    } catch (error) {
        showToast(error.message, true);
    }
});

/** Обновление в фоне — не трогая начатый ответ. */
/**
 * Обновление в фоне. Перерисовывает только изменившееся: иначе раз в 10 секунд пропадали бы
 * раскрытые условия договора, а клик в момент перерисовки терялся.
 */
async function refresh() {
    const before = JSON.stringify(ws.appeals);
    ws.appeals = await api("/api/appeals").catch(() => ws.appeals);
    if (JSON.stringify(ws.appeals) !== before) {
        renderQueue();
        if ($("#summary-view").classList.contains("active")) renderSummary();
    }
    // открытую вкладку передач не перерисовываем: там может быть начатый комментарий
    if (isSupervisor() && !$("#transfers-view").classList.contains("active")) {
        await loadTransfers().catch(() => {});
    }
    const typing = hasDraft($("#card")) || document.querySelector("#card [data-transfer], #card [data-extend]");
    if (!ws.openedId || typing) return;
    const appeal = await api(`/api/appeals/${ws.openedId}`).catch(() => null);
    if (appeal && cardSignature(appeal) !== ws.openedSignature) await openCard(ws.openedId).catch(() => {});
}
