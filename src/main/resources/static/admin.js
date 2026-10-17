// Администрирование: сводка по отделам, все обращения с фильтрами, карточка целиком
// (только просмотр — с обращениями работают отделы), журнал событий.

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
}

async function loadAll() {
    adm.appeals = await api("/api/appeals");
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
        </button>`).join("") : `<p class="empty">Ничего не найдено</p>`;
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
                    <dt>Клиент</dt><dd>${escapeHtml(appeal.customerName)}<br><small class="muted">${escapeHtml(appeal.customerEmail)}</small></dd>
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
            </aside>
        </div>`;
    loadRelated(id, $(`#related-${id}`));
    renderList();
}
