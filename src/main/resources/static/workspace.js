// Рабочее место сотрудника: очередь своего отдела, карточка с перепиской,
// кнопки следующего шага (их список отдаёт сервер), передачи для руководителя.

const GROUPS = [
    {key: "new", label: "Новые", statuses: ["ROUTED"]},
    {key: "work", label: "В работе", statuses: ["IN_PROGRESS"]},
    {key: "waiting", label: "Ждут клиента", statuses: ["WAITING_CUSTOMER"]},
    {key: "done", label: "Решённые", statuses: ["RESOLVED", "CLOSED"]}
];

const ws = {appeals: [], group: "new", openedId: null, departments: [], transferForm: false, resolveForm: false};
const $ = selector => document.querySelector(selector);
const isSupervisor = () => hasRole("SUPERVISOR");

startPage("/workspace.html", async () => {
    ws.departments = await api("/api/departments");
    document.querySelector('[data-tab="transfers"]').hidden = !isSupervisor();
    await loadQueue();
    if (isSupervisor()) await loadTransfers();
    window.setInterval(refresh, 10000);
});

// ---------- вкладки ----------

document.querySelector(".tabs").addEventListener("click", event => {
    const tab = event.target.closest("[data-tab]");
    if (!tab) return;
    document.querySelectorAll(".tab").forEach(item => item.classList.toggle("active", item === tab));
    document.querySelectorAll(".view").forEach(item => item.classList.toggle("active", item.id === `${tab.dataset.tab}-view`));
    if (tab.dataset.tab === "events") loadEvents($("#events")).catch(error => showToast(error.message, true));
    if (tab.dataset.tab === "transfers") loadTransfers().catch(error => showToast(error.message, true));
});

$("#refresh-events").addEventListener("click", () => loadEvents($("#events")).catch(error => showToast(error.message, true)));

// ---------- очередь ----------

async function loadQueue() {
    ws.appeals = await api("/api/appeals");
    renderQueue();
}

function groupOf(status) {
    return GROUPS.find(group => group.statuses.includes(status));
}

function renderQueue() {
    $("#status-tabs").innerHTML = GROUPS.map(group => {
        const count = ws.appeals.filter(appeal => group.statuses.includes(appeal.status)).length;
        return `<button type="button" class="status-tab ${group.key === ws.group ? "active" : ""}" data-group="${group.key}">
            ${group.label}<span class="count">${count}</span></button>`;
    }).join("");

    const group = GROUPS.find(item => item.key === ws.group);
    // сверху то, что сгорит первым; решённые — свежие сверху
    const items = ws.appeals
        .filter(appeal => group.statuses.includes(appeal.status))
        .sort((a, b) => group.key === "done"
            ? new Date(b.updatedAt) - new Date(a.updatedAt)
            : new Date(a.deadlineAt || 8.64e15) - new Date(b.deadlineAt || 8.64e15));

    $("#queue").innerHTML = items.length ? items.map(appeal => `
        <button type="button" class="queue-item ${appeal.id === ws.openedId ? "selected" : ""}" data-appeal="${appeal.id}">
            <span class="queue-row">${priorityMark(appeal.priority)}<strong>${escapeHtml(appeal.subject)}</strong></span>
            <span class="queue-row meta"><small>${escapeHtml(appeal.customerName)} · ${escapeHtml(appeal.publicNumber)}</small>${deadlinePill(appeal)}</span>
        </button>`).join("") : `<p class="empty">Здесь пусто</p>`;
}

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
    }
    ws.openedId = id;
    $("#card").innerHTML = renderCard(appeal);
    renderQueue();
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
        </div>` : `<p class="notice small">Обращение другого отдела — только просмотр.</p>`;

    return `
        ${appealHeading(appeal)}
        <div class="card-body">
            <div class="main-col">
                ${renderThread(appeal, "EMPLOYEE")}
                ${appeal.outcome ? `<p class="notice small">Итог: ${escapeHtml(OUTCOME_LABELS[appeal.outcome])}</p>` : ""}
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
                    <textarea name="answer" rows="5" minlength="20" maxlength="5000" required
                              placeholder="Например: ущерб признан страховым случаем, выплата 85 000 ₽ направлена на ваш счёт 20.10.2026"></textarea>
                    <div class="reply-actions">
                        <button class="btn" type="button" data-toggle-resolve>Отмена</button>
                        <button class="btn primary" type="submit">Отправить ответ и решить</button>
                    </div>
                </form>` : canWork && appeal.status !== "CLOSED" ? `
                <form class="reply" data-reply>
                    <textarea name="body" rows="3" maxlength="5000" required placeholder="Ответ клиенту"></textarea>
                    <div class="reply-actions">
                        ${appeal.nextStatuses.includes("WAITING_CUSTOMER")
                            ? `<button class="btn" type="submit" name="wait" value="1">Отправить и ждать ответа</button>` : ""}
                        <button class="btn primary" type="submit">Отправить</button>
                    </div>
                </form>` : ""}
            </div>
            <aside class="side-col">
                ${actions}
                <h4>Срок по закону</h4>${renderNorm(appeal)}
                <h4>Клиент</h4>
                <p>${escapeHtml(appeal.customerName)}<br><small class="muted">${escapeHtml(appeal.customerEmail)}</small></p>
                ${appeal.assignedEmployee ? `<h4>Исполнитель</h4><p>${escapeHtml(appeal.assignedEmployee)}</p>` : ""}
                <h4>Договор</h4>${renderContract(appeal.contract)}
                <h4>Данные обращения</h4>${renderDetails(appeal)}
                <h4>Файлы</h4>${renderAttachments(appeal)}
                <details class="more">
                    <summary>Журнал обработки · ${appeal.history.length}</summary>
                    ${renderHistory(appeal)}${renderTransfers(appeal)}
                </details>
            </aside>
        </div>`;
}

async function changeStatus(status) {
    await api(`/api/appeals/${ws.openedId}/status`, {method: "POST", body: JSON.stringify({status})});
}

$("#card").addEventListener("click", async event => {
    const step = event.target.closest("[data-status]");
    const toggle = event.target.closest("[data-toggle-transfer]");
    const toggleResolve = event.target.closest("[data-toggle-resolve]");
    try {
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
            await api(`/api/appeals/${ws.openedId}/messages`, {
                method: "POST", body: JSON.stringify({body: form.get("body")})
            });
            if (event.submitter?.name === "wait") await changeStatus("WAITING_CUSTOMER");
            showToast(event.submitter?.name === "wait" ? "Ответ отправлен, ждём клиента" : "Ответ отправлен");
        }
        if (event.target.matches("[data-resolve]")) {
            await api(`/api/appeals/${ws.openedId}/resolve`, {
                method: "POST",
                body: JSON.stringify({outcome: form.get("outcome"), answer: form.get("answer")})
            });
            ws.resolveForm = false;
            showToast("Ответ отправлен клиенту, обращение решено");
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

async function refreshOpened() {
    await loadQueue();
    const opened = ws.appeals.find(appeal => appeal.id === ws.openedId);
    // обращение ушло в другую группу — переключаемся вслед за ним
    if (opened) ws.group = groupOf(opened.status).key;
    await openCard(ws.openedId);
}

// ---------- передачи (руководитель) ----------

async function loadTransfers() {
    const transfers = await api("/api/transfer-requests");
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
async function refresh() {
    await loadQueue().catch(() => {});
    // открытую вкладку передач не перерисовываем: там может быть начатый комментарий
    if (isSupervisor() && !$("#transfers-view").classList.contains("active")) {
        await loadTransfers().catch(() => {});
    }
    const typing = document.querySelector("#card textarea")?.value || document.querySelector("#card [data-transfer]");
    if (ws.openedId && !typing) await openCard(ws.openedId).catch(() => {});
}
