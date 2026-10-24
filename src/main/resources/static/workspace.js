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
    scope: null, search: "", transferForm: false, resolveForm: false, extendForm: false,
    // помощник (решение 35): подсказки по открытому обращению, текст для формы ответа, принятый тип
    advice: null, adviceKey: null, answerDraft: null, finalTypeHint: {},
    // какие разделы правой колонки карточки открыты: запоминается между перерисовками
    sections: {}
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
    announce(null);
}

// ---------- новое без перезагрузки ----------

const BASE_TITLE = document.title;

/** Обращения в зоне сотрудника: специалисту свои и ничьи, руководителю весь отдел. */
function scopedOpen() {
    const open = ws.appeals.filter(isOpen);
    return isSupervisor() ? open : open.filter(appeal => isMine(appeal) || !appeal.assignedSubject);
}

/** То же, что «Что сделать сейчас»: срочное сверху. */
function attentionTasks() {
    return scopedOpen()
        .map(appeal => ({appeal, reason: attentionReason(appeal)}))
        .filter(task => task.reason)
        .sort((a, b) => a.reason.rank - b.reason.rank
            || new Date(a.appeal.deadlineAt || 8.64e15) - new Date(b.appeal.deadlineAt || 8.64e15));
}

/**
 * Счётчик дел в заголовке вкладки браузера и на вкладке «Сводка»: сотрудник видит, что
 * появилось новое, даже когда работает в другой программе. О новом обращении и ответе
 * клиента подсказывает всплывающее сообщение; previous — очередь до обновления.
 */
function announce(previous) {
    const count = attentionTasks().length;
    document.title = count ? `(${count}) ${BASE_TITLE}` : BASE_TITLE;
    $("#attention-count").textContent = count || "";
    if (!previous) return;
    const before = new Map(previous.map(appeal => [appeal.id, appeal]));
    const scoped = scopedOpen();
    const fresh = scoped.filter(appeal => !before.has(appeal.id));
    const replied = scoped.filter(appeal => appeal.clientReplied && before.has(appeal.id) && !before.get(appeal.id).clientReplied);
    if (fresh.length) {
        showToast(fresh.length === 1 ? `Новое обращение: ${fresh[0].subject}` : `Новых обращений: ${fresh.length}`);
    } else if (replied.length) {
        showToast(replied.length === 1 ? `Клиент ответил: ${replied[0].subject}` : `Клиенты ответили: ${replied.length}`);
    }
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
    // затем — где клиент грозит жалобой в Банк России, судом или финансовым уполномоченным
    const urgency = appeal => ({BREACHED: 0, AT_RISK: 1}[appeal.escalationLevel] ?? (riskSignal(appeal) ? 2 : 3));
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
            ${isOpen(appeal) && appeal.signals?.some(signal => signal.risk) ? `<span class="queue-row signals">${appeal.signals
                .filter(signal => signal.risk).map(signal => `<span class="badge late">⚠ ${escapeHtml(signal.label)}</span>`).join("")}</span>` : ""}
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
    const risk = riskSignal(appeal);
    if (risk) return {rank: 2.5, tone: "late", text: `⚠ ${risk.label.charAt(0).toLowerCase()}${risk.label.slice(1)}`};
    if (appeal.clientReplied) return {rank: 3, tone: "reply", text: "клиент ответил"};
    if (appeal.status !== "WAITING_CUSTOMER" && appeal.deadlineAt && new Date(appeal.deadlineAt) < new Date()) {
        return {rank: 4, tone: "soon", text: "просрочен срок отдела"};
    }
    if (appeal.status === "ROUTED" && !appeal.assignedSubject) return {rank: 5, tone: "", text: "новое, без исполнителя"};
    return null;
}

/** Первый сигнал риска спора в тексте клиента (решение 35). */
function riskSignal(appeal) {
    return appeal.signals?.find(signal => signal.risk);
}

function greeting() {
    const hour = new Date().getHours();
    return hour < 12 ? "Доброе утро" : hour < 18 ? "Добрый день" : "Добрый вечер";
}

function renderSummary() {
    const box = $("#summary");
    if (!box || !session.me) return;
    const open = ws.appeals.filter(isOpen);
    const scoped = scopedOpen();
    const legallyLate = appeal => appeal.escalationLevel === "BREACHED" || isLegallyOverdue(appeal);
    const tiles = [
        ["Мои в работе", open.filter(a => isMine(a) && ["IN_PROGRESS", "WAITING_CUSTOMER"].includes(a.status)).length, "", "work", "mine"],
        ["Клиент ответил", scoped.filter(a => a.clientReplied).length, "reply", "work", isSupervisor() ? "all" : "mine"],
        ["Новые без исполнителя", open.filter(a => a.status === "ROUTED" && !a.assignedSubject).length, "", "new", "all"],
        ["Срок по закону истекает", scoped.filter(a => a.escalationLevel === "AT_RISK").length, "soon", "work", "all"],
        ["Нарушен срок по закону", scoped.filter(legallyLate).length, "late", "work", "all"]
    ];
    const tasks = attentionTasks();
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
                    ${tasks.length > 8 ? `<p class="muted small-text">И ещё ${tasks.length - 8} в очереди.</p>` : ""}`
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
    loadAssistant(appeal).catch(error => showToast(error.message, true));
    if (ws.answerDraft && $("[data-resolve] textarea")) {
        $("[data-resolve] textarea").value = ws.answerDraft;
        ws.answerDraft = null;
    }
    renderQueue();
}

/** Итоговый тип (решение 23): все типы по категориям, текущий выбран. */
function finalTypeOptions(appeal) {
    return session.schema.categories.map(category => `<optgroup label="${escapeHtml(category.label)}">${category.types
        .map(type => {
            const value = `${category.code}/${type.code}`;
            // сотрудник принял подсказку «похоже на претензию» — итоговый тип уже выбран
            const chosen = ws.finalTypeHint[appeal.id] || acceptedHintType(appeal) || `${appeal.category}/${appeal.subcategory}`;
            const selected = value === chosen ? " selected" : "";
            return `<option value="${value}"${selected}>${escapeHtml(type.label)}</option>`;
        }).join("")}</optgroup>`).join("");
}

function typeCorrected(appeal) {
    return appeal.finalCategory && (appeal.finalCategory !== appeal.category || appeal.finalSubcategory !== appeal.subcategory);
}

function actionLabel(target, current) {
    if (target === "IN_PROGRESS") return current === "ROUTED" ? "Взять в работу" : "Вернуть в работу";
    if (target === "RESOLVED") return "Решено: ответить по существу";
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
                маршрутизации. Выберите отдел, и обращение уйдёт туда с обычным сроком отдела.</p>
            <form class="transfer-form" data-classify>
                <label>Отдел
                    <select name="departmentCode">${ws.departments
                        .map(item => `<option value="${item.code}">${escapeHtml(item.name)}</option>`).join("")}</select>
                </label>
                <button class="btn primary" type="submit">Направить в отдел</button>
            </form>
        </div>` : `<p class="notice small">Обращение другого отдела, только просмотр.</p>`;

    const signals = ws.appeals.find(item => item.id === appeal.id)?.signals || [];
    return `
        ${appealHeading(appeal, signals)}
        <div class="card-body">
            <div class="main-col">
                ${renderChecks(appeal)}
                ${canWork && isOpen(appeal) ? `<section class="assistant" id="assistant-${appeal.id}">
                    <p class="muted small-text">Помощник смотрит обращение…</p></section>` : ""}
                ${renderThread(appeal, "EMPLOYEE")}
                ${appeal.outcome ? `<p class="notice small">Итог: ${escapeHtml(OUTCOME_LABELS[appeal.outcome])}${typeCorrected(appeal)
                    ? ` · тип уточнён: ${escapeHtml(categoryLabel(appeal.finalCategory))}, ${escapeHtml(typeLabel(appeal.finalCategory, appeal.finalSubcategory))}`
                    : ""}</p>` : ""}
                ${canWork && ws.resolveForm && appeal.nextStatuses.includes("RESOLVED") ? `
                <form class="reply resolve-form" data-resolve>
                    <h4>Ответ клиенту по существу</h4>
                    <p class="muted small-text">Закон требует обоснованный ответ: результат рассмотрения, на каком основании,
                        что сделано (Закон № 4015-1, ст. 6.2 п. 6). Сама выплата или перерасчёт выполняются в учётных системах
                        страховой, а здесь фиксируется результат.</p>
                    <div class="resolve-head">
                        <label>Итог
                            <select name="outcome">${Object.entries(OUTCOME_LABELS)
                                .map(([code, label]) => `<option value="${code}">${label}</option>`).join("")}</select>
                        </label>
                        <button class="btn small-btn" type="button" data-draft>${ws.advice?.data.engine === "LOCAL_MODEL"
                            ? "Черновик от локальной модели" : "Черновик по шаблону"}</button>
                    </div>
                    <div id="draft-preview"></div>
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
                ${sideSection("norm", "Срок по закону и продление",
                    renderNorm(appeal) + renderExtension(appeal, canWork), ws.extendForm)}
                ${canWork && isSupervisor() && isOpen(appeal)
                    ? sideSection("assignee", "Исполнитель", renderAssignee(appeal, canWork)) : ""}
                ${sideSection("client", "Клиент", `<p>${escapeHtml(appeal.customerName)}<br>
                    <small class="muted">${escapeHtml(appeal.customerEmail)}</small>
                    ${appeal.channel && appeal.channel !== "CABINET" ? `<br><small class="muted">Обращение принято
                        ${CHANNEL_LABELS[appeal.channel]}, оператор ${escapeHtml(appeal.registeredBy || "")}</small>` : ""}</p>`)}
                ${sideSection("contract", "Договор", renderContractSection(appeal, canWork, false))}
                ${sideSection("details", "Данные обращения", renderDetails(appeal))}
                ${sideSection("files", `Файлы · ${appeal.attachments.length}`, renderAttachments(appeal))}
                <details class="side-section" data-section="related" data-related-section ${sectionOpen("related") ? "open" : ""}>
                    <summary>История клиента<span data-related-count></span></summary>
                    <div class="section-body" id="related-${appeal.id}"><p class="muted">Загрузка…</p></div>
                </details>
                ${sideSection("journal", `Журнал обработки · ${appeal.history.length}`,
                    renderHistory(appeal) + renderTransfers(appeal))}
            </aside>
        </div>`;
}

// ---------- разделы правой колонки ----------

/** По умолчанию открыто то, что нужно для ответа; остальное раскрывается по клику и запоминается. */
const OPEN_SECTIONS = ["assignee", "contract", "details", "files"];

function sectionOpen(key) {
    return ws.sections[key] ?? OPEN_SECTIONS.includes(key);
}

function sideSection(key, title, body, forceOpen = false) {
    return `<details class="side-section" data-section="${key}" ${forceOpen || sectionOpen(key) ? "open" : ""}>
        <summary>${title}</summary><div class="section-body">${body}</div></details>`;
}

// событие toggle не всплывает, поэтому слушаем на погружении
$("#card").addEventListener("toggle", event => {
    if (event.target.matches?.("details[data-section]")) {
        ws.sections[event.target.dataset.section] = event.target.open;
    }
}, true);

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
            <label>Причина, её увидит клиент
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
                    ${escapeHtml(member.fullName)}${member.supervisor ? ", руководитель" : ""}</option>`).join("")}</select>
            <button class="btn" type="submit">Назначить</button>
        </form>`;
}

async function changeStatus(status) {
    await api(`/api/appeals/${ws.openedId}/status`, {method: "POST", body: JSON.stringify({status})});
}

$("#card").addEventListener("click", async event => {
    const assistantAction = event.target.closest("[data-assistant]");
    if (assistantAction) {
        assistantClick(assistantAction).catch(error => showToast(error.message, true));
        return;
    }
    const related = event.target.closest("[data-open-related]");
    if (related) {
        const appeal = ws.appeals.find(item => item.id === related.dataset.openRelated);
        if (appeal) ws.group = groupOf(appeal.status).key;
        openCard(related.dataset.openRelated).catch(error => showToast(error.message, true));
        return;
    }
    if (event.target.closest("[data-draft]")) {
        requestDraft().catch(error => {
            $("#draft-preview").innerHTML = "";
            showToast(error.message, true);
        });
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

// ---------- помощник сотрудника (решение 35) ----------

const ENGINE_LABELS = {RULES: "правила и поиск", LOCAL_MODEL: "локальная модель"};

/**
 * Подсказки по обращению. Пока обращение не изменилось, повторно не запрашиваются: локальная
 * модель думает секунды, а карточка перерисовывается при каждом действии.
 */
async function loadAssistant(appeal) {
    const box = $(`#assistant-${appeal.id}`);
    if (!box) return;
    const key = `${appeal.id}|${cardSignature(appeal)}`;
    if (ws.adviceKey !== key) {
        const data = await api(`/api/appeals/${appeal.id}/assistant`);
        ws.advice = {id: appeal.id, data};
        ws.adviceKey = key;
    }
    if (ws.openedId === appeal.id) renderAssistant(appeal, box);
}

function renderAssistant(appeal, box = $(`#assistant-${appeal.id}`)) {
    if (!box || ws.advice?.id !== appeal.id) return;
    const advice = ws.advice.data;
    const hint = advice.typeHint;
    const parts = [];
    if (advice.notice) parts.push(`<p class="notice small">${escapeHtml(advice.notice)}</p>`);
    if (advice.signals.length) {
        parts.push(`<ul class="signal-list-card">${advice.signals.map(signal => `
            <li class="${signal.risk ? "risk" : ""}">
                <strong>${signal.risk ? "⚠ " : ""}${escapeHtml(signal.label)}</strong>
                <q>${escapeHtml(signal.excerpt)}</q>
                <small class="muted">${escapeHtml(signal.hint)}</small>
            </li>`).join("")}</ul>`);
    }
    if (hint && advice.decisions.TYPE_HINT !== "REJECTED") {
        const earlier = hint.legalDeadline < appeal.legalDeadline;
        parts.push(`<div class="hint-card">
            <p><strong>Похоже на претензию</strong>, хотя клиент выбрал «${escapeHtml(typeLabel(appeal.category, appeal.subcategory))}».
                <small class="muted">${escapeHtml(ENGINE_LABELS[hint.source])}</small></p>
            ${hint.reasons.length ? `<ul class="quotes">${hint.reasons.map(reason => `<li><q>${escapeHtml(reason)}</q></li>`).join("")}</ul>` : ""}
            <p class="small-text">По претензии срок по закону: до ${formatDate(hint.legalDeadline)}
                (${escapeHtml(hint.normTitle.toLowerCase())}; ${escapeHtml(hint.legalBasis)})${hint.extensionAllowed ? "" : ", продлить его нельзя"}.
                Сейчас стоит ${formatDate(appeal.legalDeadline)}.</p>
            ${earlier ? `<p class="notice small warning-note">⚠ Ответить нужно раньше текущего срока: до ${formatDate(hint.legalDeadline)}.</p>` : ""}
            ${advice.decisions.TYPE_HINT === "ACCEPTED"
                ? `<p class="muted small-text">✓ Учтено: в форме ответа итоговый тип «${escapeHtml(hint.label)}».</p>`
                : `<div class="hint-actions">
                    <button type="button" class="btn small-btn" data-assistant="type-accept">Учесть при ответе</button>
                    <button type="button" class="btn ghost small-btn" data-assistant="type-reject">Не подходит</button>
                </div>`}
        </div>`);
    }
    if (advice.similar.length) {
        parts.push(`<details class="similar">
            <summary>Как решали похожие · ${advice.similar.length}</summary>
            ${advice.similar.map((item, i) => `<article>
                <p><strong>${escapeHtml(item.subject)}</strong><br>
                    <small class="muted">${escapeHtml(item.publicNumber)} · ${escapeHtml(OUTCOME_LABELS[item.outcome])}
                    · решено за ${item.resolutionDays} дн., ${formatDate(item.resolvedOn)}</small></p>
                ${item.answer ? `<p class="similar-answer">${escapeHtml(item.answer)}</p>
                    <button type="button" class="btn ghost small-btn" data-assistant="use-answer" data-index="${i}">Взять за основу ответа</button>` : ""}
            </article>`).join("")}
        </details>`);
    }
    box.innerHTML = parts.length ? `<header class="assistant-head"><h4>Помощник</h4>
            <small class="muted">${escapeHtml(ENGINE_LABELS[advice.engine])} · подсказка советует, решаете вы</small></header>${parts.join("")}`
        : `<p class="muted small-text">Помощник: сигналов в тексте клиента нет, похожих решённых обращений пока нет.</p>`;
}

/** Подсказку о типе приняли раньше, в другой сессии, — тип берётся из сохранённого решения. */
function acceptedHintType(appeal) {
    const advice = ws.advice?.id === appeal.id ? ws.advice.data : null;
    return advice?.typeHint && advice.decisions.TYPE_HINT === "ACCEPTED"
        ? `${advice.typeHint.category}/${advice.typeHint.subcategory}` : null;
}

async function assistantFeedback(kind, decision) {
    await api(`/api/appeals/${ws.openedId}/assistant/feedback`, {method: "POST", body: JSON.stringify({kind, decision})});
    ws.advice.data.decisions[kind] = decision;
}

/** Действия помощника меняют только свой блок — написанный ответ в форме не теряется. */
async function assistantClick(button) {
    const appeal = await api(`/api/appeals/${ws.openedId}`);
    const action = button.dataset.assistant;
    if (action === "type-accept" || action === "type-reject") {
        const hint = ws.advice.data.typeHint;
        await assistantFeedback("TYPE_HINT", action === "type-accept" ? "ACCEPTED" : "REJECTED");
        if (action === "type-accept") {
            ws.finalTypeHint[appeal.id] = `${hint.category}/${hint.subcategory}`;
            const select = $("[data-resolve] select[name=finalType]");
            if (select) select.value = ws.finalTypeHint[appeal.id];
            showToast(`При ответе итоговый тип: «${hint.label}»`);
        }
        renderAssistant(appeal);
    }
    if (action === "use-answer") {
        const item = ws.advice.data.similar[Number(button.dataset.index)];
        await assistantFeedback("SIMILAR", "ACCEPTED");
        const textarea = $("[data-resolve] textarea");
        if (textarea) {
            textarea.value = item.answer;
            textarea.focus();
        } else {
            ws.resolveForm = true;
            ws.answerDraft = item.answer;
            await openCard(ws.openedId);
        }
        showToast("Ответ взят за основу. Поправьте его под это обращение");
    }
    if (action === "draft-use" || action === "draft-reject") {
        await assistantFeedback("DRAFT", action === "draft-use" ? "ACCEPTED" : "REJECTED");
        if (action === "draft-use") {
            const textarea = $("[data-resolve] textarea");
            textarea.value = ws.draft;
            textarea.rows = Math.min(16, ws.draft.split("\n").length + 4);
            textarea.focus();
            showToast("Черновик вставлен. Допишите места в квадратных скобках");
        }
        $("#draft-preview").innerHTML = "";
    }
}

async function requestDraft() {
    const form = $("[data-resolve]");
    const preview = $("#draft-preview");
    preview.innerHTML = `<p class="muted small-text">Готовим черновик…</p>`;
    const query = new URLSearchParams({outcome: form.elements.outcome.value, type: form.elements.finalType.value});
    const draft = await api(`/api/appeals/${ws.openedId}/assistant/draft?${query}`);
    ws.draft = draft.text;
    preview.innerHTML = `<div class="draft-preview">
        <p class="small-text"><strong>${escapeHtml(draft.templateTitle)}</strong>
            <span class="muted">· ${escapeHtml(ENGINE_LABELS[draft.source])}</span></p>
        ${draft.notice ? `<p class="notice small">${escapeHtml(draft.notice)}</p>` : ""}
        <pre>${escapeHtml(draft.text)}</pre>
        <div class="hint-actions">
            <button type="button" class="btn small-btn" data-assistant="draft-use">Вставить в ответ</button>
            <button type="button" class="btn ghost small-btn" data-assistant="draft-reject">Не подходит</button>
        </div>
    </div>`;
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
    const previous = ws.appeals;
    const before = JSON.stringify(ws.appeals);
    ws.appeals = await api("/api/appeals").catch(() => ws.appeals);
    if (JSON.stringify(ws.appeals) !== before) {
        renderQueue();
        if ($("#summary-view").classList.contains("active")) renderSummary();
        announce(previous);
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
