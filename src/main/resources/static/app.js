const state = {
    clientAppeals: [],
    employeeAppeals: [],
    departments: [],
    clients: [],
    employees: [],
    transferRequests: [],
    activeClientId: null,
    activeEmployeeId: null,
    activeAppealId: null,
    schema: {categories: []},
    formSchema: {categories: []}
};

// Что спрашивать у клиента, задаёт схема формы с сервера (GET /api/form-schema, решение 24).
// Здесь только то, как это показать: элемент ввода, ширина поля и демонстрационные значения.
const INPUT_TYPES = {TEXT: "text", DATE: "date", NUMBER: "number"};
const WIDE_FIELDS = ["incidentPlace", "desiredOutcome", "errorText"];
const DEMO_VALUES = {
    paymentDate: "2026-06-10",
    paymentAmount: "15000",
    incidentDate: "2026-06-11",
    incidentPlace: "г. Москва",
    systemSection: "Личный кабинет",
    device: "Windows 10, Chrome"
};

const labels = {
    statuses: {
        PENDING_ROUTING: "Ожидает маршрутизации",
        ROUTED: "Назначено отделу",
        IN_PROGRESS: "В работе",
        WAITING_CUSTOMER: "Ожидает клиента",
        RESOLVED: "Решено",
        CLOSED: "Закрыто"
    },
    priorities: {
        LOW: "Низкий",
        NORMAL: "Обычный",
        HIGH: "Высокий",
        CRITICAL: "Критический"
    }
};

async function api(path, options = {}) {
    const isFormData = options.body instanceof FormData;
    const response = await fetch(path, {
        headers: {
            ...(isFormData ? {} : {"Content-Type": "application/json"}),
            ...(options.headers || {})
        },
        ...options
    });
    if (!response.ok) {
        const error = await response.json().catch(() => ({message: "Ошибка запроса"}));
        throw new Error(error.message || "Ошибка запроса");
    }
    return response.json();
}

function schemaCategory(schema, category) {
    return schema.categories.find(item => item.code === category);
}

function schemaType(schema, category, type) {
    return schemaCategory(schema, category)?.types.find(item => item.code === type);
}

/** Типы обращений зависят от продукта договора: клиент с КАСКО не видит ипотечных. */
async function loadFormSchema() {
    const contract = selectedContract();
    state.formSchema = contract
        ? await api(`/api/form-schema?productCode=${encodeURIComponent(contract.productCode)}`)
        : {categories: []};
    renderCategories();
}

function renderCategories() {
    document.querySelector("#appeal-category").innerHTML = state.formSchema.categories
        .map(item => `<option value="${escapeHtml(item.code)}">${escapeHtml(item.label)}</option>`)
        .join("");
    renderSubcategories();
}

function renderSubcategories() {
    const category = document.querySelector("#appeal-category").value;
    const types = schemaCategory(state.formSchema, category)?.types || [];
    document.querySelector("#appeal-subcategory").innerHTML = types
        .map(item => `<option value="${escapeHtml(item.code)}">${escapeHtml(item.label)}</option>`)
        .join("");
    renderDynamicFields();
}

function ensureFormCategoryConsistency() {
    const category = document.querySelector("#appeal-category").value;
    const subcategory = document.querySelector("#appeal-subcategory").value;
    if (!schemaType(state.formSchema, category, subcategory)) {
        renderSubcategories();
        return false;
    }
    return true;
}

function renderDynamicFields() {
    const category = document.querySelector("#appeal-category").value;
    const subcategory = document.querySelector("#appeal-subcategory").value;
    // поля договора, например номер полиса, сервер подставляет сам — клиент их не вводит
    const fields = (schemaType(state.formSchema, category, subcategory)?.fields || [])
        .filter(field => !field.contractAttribute);
    document.querySelector("#dynamic-fields").innerHTML = fields.map(field => `
            <label class="${WIDE_FIELDS.includes(field.code) ? "wide-field" : ""}">
                ${escapeHtml(field.label)}
                <input name="detail.${escapeHtml(field.code)}" type="${INPUT_TYPES[field.inputType] || "text"}"
                       placeholder="${escapeHtml(field.hint || "")}"
                       value="${escapeHtml(DEMO_VALUES[field.code] || "")}" ${field.required ? "required" : ""}>
            </label>
        `).join("");
}

function categoryLabel(category) {
    return schemaCategory(state.schema, category)?.label || category;
}

function subcategoryLabel(category, subcategory) {
    return schemaType(state.schema, category, subcategory)?.label || "Общий запрос";
}

function fieldLabel(key) {
    // insuredObject — атрибут обращений, созданных до схемы формы
    if (key === "insuredObject") return "Объект страхования по договору";
    const field = state.schema.categories
        .flatMap(category => category.types)
        .flatMap(type => type.fields)
        .find(item => item.code === key);
    return field?.label || key;
}

function formatFileSize(bytes) {
    if (bytes < 1024 * 1024) return `${Math.ceil(bytes / 1024)} КБ`;
    return `${(bytes / (1024 * 1024)).toFixed(1)} МБ`;
}

function renderSelectedFiles() {
    const input = document.querySelector("#appeal-files");
    const files = [...input.files];
    const target = document.querySelector("#selected-files");
    if (files.length > 5) {
        input.value = "";
        target.innerHTML = "";
        showToast("Можно выбрать не более 5 файлов", true);
        return;
    }
    if (files.some(file => file.size > 10 * 1024 * 1024)) {
        input.value = "";
        target.innerHTML = "";
        showToast("Размер каждого файла не должен превышать 10 МБ", true);
        return;
    }
    target.innerHTML = files.map(file => `
        <div class="selected-file">
            <span>${escapeHtml(file.name)}</span>
            <small>${formatFileSize(file.size)}</small>
        </div>
    `).join("");
}

function escapeHtml(value) {
    return String(value ?? "")
        .replaceAll("&", "&amp;")
        .replaceAll("<", "&lt;")
        .replaceAll(">", "&gt;")
        .replaceAll('"', "&quot;")
        .replaceAll("'", "&#039;");
}

const CONTRACT_STATUS_LABELS = {
    NOT_STARTED: "Не вступил в силу",
    ACTIVE: "Действует",
    EXPIRED: "Истёк",
    TERMINATED: "Прекращён досрочно"
};

function contractStatusLabel(status) {
    return CONTRACT_STATUS_LABELS[status] || status;
}

function formatDeadline(value, status) {
    if (!value) return "Не назначено";
    const date = formatDate(value);
    if (status === "RESOLVED" || status === "CLOSED") {
        return `<span class="sla done">${date}</span>`;
    }
    const left = new Date(value).getTime() - Date.now();
    const hours = Math.floor(Math.abs(left) / 3600000);
    const minutes = Math.floor((Math.abs(left) % 3600000) / 60000);
    if (left < 0) {
        return `<span class="sla overdue">Просрочено на ${hours} ч ${minutes} мин</span><small>${date}</small>`;
    }
    const urgency = left < 4 * 3600000 ? "soon" : "ok";
    return `<span class="sla ${urgency}">Осталось ${hours} ч ${minutes} мин</span><small>${date}</small>`;
}

function formatDate(value) {
    if (!value) return "Не назначено";
    return new Intl.DateTimeFormat("ru-RU", {
        day: "2-digit",
        month: "2-digit",
        year: "numeric",
        hour: "2-digit",
        minute: "2-digit"
    }).format(new Date(value));
}

function formatShortDate(value) {
    return new Intl.DateTimeFormat("ru-RU").format(new Date(`${value}T00:00:00`));
}

function initials(name) {
    return name.split(/\s+/).slice(0, 2).map(part => part[0]).join("").toUpperCase();
}

function showToast(message, error = false) {
    const toast = document.querySelector("#toast");
    toast.textContent = message;
    toast.className = `toast visible${error ? " error" : ""}`;
    window.clearTimeout(showToast.timer);
    showToast.timer = window.setTimeout(() => toast.className = "toast", 3500);
}

function setView(view) {
    document.querySelectorAll(".view").forEach(item => item.classList.remove("active"));
    document.querySelector(`#${view}-view`).classList.add("active");
    document.querySelectorAll(".nav-button").forEach(button =>
        button.classList.toggle("active", button.dataset.view === view)
    );
    if (view === "employee") loadEmployeeWorkspace();
    if (view === "events") loadEvents();
}

function activeClient() {
    return state.clients.find(item => item.id === state.activeClientId);
}

function activeEmployee() {
    return state.employees.find(item => item.id === state.activeEmployeeId);
}

function selectedContract() {
    const client = activeClient();
    const contractId = document.querySelector("#contract-select")?.value;
    return client?.contracts.find(item => item.id === contractId);
}

async function loadDirectories() {
    [state.departments, state.clients, state.employees, state.schema] = await Promise.all([
        api("/api/departments"),
        api("/api/clients"),
        api("/api/employees"),
        api("/api/form-schema")
    ]);

    state.activeClientId = state.clients[0]?.id || null;
    state.activeEmployeeId =
        state.employees.find(item => item.fullName === "Елена Соколова")?.id
        || state.employees[0]?.id
        || null;

    renderClientIdentity();
    renderEmployeeSelector();
    await loadFormSchema();
}

function renderClientIdentity() {
    const client = activeClient();
    if (!client) return;
    document.querySelector("#client-profile").innerHTML = `
        <span class="identity-avatar">${escapeHtml(initials(client.fullName))}</span>
        <div>
            <strong>${escapeHtml(client.fullName)}</strong>
            <small>${escapeHtml(client.email)} · ${escapeHtml(client.phone)}</small>
        </div>
        <span class="verified-badge">Профиль подтверждён</span>
    `;
    const select = document.querySelector("#contract-select");
    select.innerHTML = client.contracts.map(contract => `
        <option value="${contract.id}">${escapeHtml(contract.policyNumber)} · ${escapeHtml(contract.insuranceType)}</option>
    `).join("");
    renderContractSummary();
}

function renderContractSummary() {
    const contract = selectedContract();
    const target = document.querySelector("#contract-summary");
    if (!contract) {
        target.innerHTML = "";
        return;
    }
    target.innerHTML = `
        <div><small>Полис</small><strong>${escapeHtml(contract.policyNumber)}</strong></div>
        <div><small>Вид страхования</small><strong>${escapeHtml(contract.insuranceType)}</strong></div>
        <div class="wide"><small>Объект</small><strong>${escapeHtml(contract.insuredObject)}</strong></div>
        <div><small>Срок действия</small><strong>${formatShortDate(contract.validFrom)} – ${formatShortDate(contract.validTo)}</strong></div>
        <div><small>Статус</small><strong class="${contract.status === "ACTIVE" ? "contract-active" : ""}">${escapeHtml(contractStatusLabel(contract.status))}</strong></div>
    `;
}

function renderEmployeeSelector() {
    const select = document.querySelector("#employee-selector");
    select.innerHTML = state.employees.map(employee => `
        <option value="${employee.id}" ${employee.id === state.activeEmployeeId ? "selected" : ""}>
            ${escapeHtml(employee.fullName)} · ${employee.role === "SUPERVISOR" ? "руководитель" : "специалист"} · ${escapeHtml(employee.department.name)}
        </option>
    `).join("");
    renderEmployeeIdentity();
}

function renderEmployeeIdentity() {
    const employee = activeEmployee();
    if (!employee) return;
    document.querySelector("#employee-chip").innerHTML = `
        <span>${escapeHtml(initials(employee.fullName))}</span>
        <div>
            <strong>${escapeHtml(employee.fullName)}</strong>
            <small>${employee.role === "SUPERVISOR" ? "Руководитель" : "Специалист"} · ${escapeHtml(employee.department.name)}</small>
        </div>
    `;
    document.querySelector("#employee-department").textContent = employee.department.name;
    document.querySelector("#approval-panel").hidden = employee.role !== "SUPERVISOR";
}

async function loadClientAppeals() {
    if (!state.activeClientId) return;
    state.clientAppeals = await api(`/api/appeals?clientId=${state.activeClientId}`);
    renderClientAppeals();
}

async function loadEmployeeWorkspace() {
    const employee = activeEmployee();
    if (!employee) return;
    const query = new URLSearchParams({departmentCode: employee.department.code});
    const status = document.querySelector("#status-filter")?.value || "";
    if (status) query.set("status", status);
    state.employeeAppeals = await api(`/api/appeals?${query}`);
    state.transferRequests = employee.role === "SUPERVISOR"
        ? await api(`/api/transfer-requests?targetDepartmentCode=${employee.department.code}`)
        : [];
    renderEmployeeAppeals();
    renderMetrics();
    renderTransferApprovals();
}

async function loadAllAppeals() {
    await Promise.all([loadClientAppeals(), loadEmployeeWorkspace()]);
}

function renderClientAppeals() {
    const target = document.querySelector("#client-appeals");
    if (!state.clientAppeals.length) {
        target.className = "appeal-list empty-state";
        target.innerHTML = "<div><p>Пока обращений нет</p><small>Созданные обращения появятся здесь</small></div>";
        return;
    }

    target.className = "appeal-list";
    target.innerHTML = state.clientAppeals.slice(0, 6).map(appeal => `
        <article class="appeal-card">
            <button data-open-appeal="${appeal.id}">
                <div class="appeal-card-top">
                    <span class="appeal-number">${escapeHtml(appeal.publicNumber)}</span>
                    <span class="status ${appeal.status}">${labels.statuses[appeal.status]}</span>
                </div>
                <h3>${escapeHtml(appeal.subject)}</h3>
                <div class="appeal-card-meta">
                    <span>${appeal.department ? escapeHtml(appeal.department.name) : "Маршрутизация..."}</span>
                    <span>${formatDate(appeal.createdAt)}</span>
                </div>
            </button>
        </article>
    `).join("");
}

function renderEmployeeAppeals() {
    const target = document.querySelector("#employee-appeals");
    if (!state.employeeAppeals.length) {
        target.innerHTML = '<div class="empty-state"><p>В очереди нет обращений</p></div>';
        return;
    }

    target.innerHTML = `
        <table>
            <thead>
            <tr>
                <th>Номер</th>
                <th>Клиент и тема</th>
                <th>Отдел</th>
                <th>Приоритет</th>
                <th>Статус</th>
                <th>Срок</th>
            </tr>
            </thead>
            <tbody>
            ${state.employeeAppeals.map(appeal => `
                <tr data-open-appeal="${appeal.id}">
                    <td><span class="appeal-number">${escapeHtml(appeal.publicNumber)}</span></td>
                    <td><strong>${escapeHtml(appeal.subject)}</strong><br><small>${escapeHtml(appeal.customerName)}</small></td>
                    <td>${appeal.department ? escapeHtml(appeal.department.name) : "Маршрутизация..."}</td>
                    <td><span class="priority ${appeal.priority}">${labels.priorities[appeal.priority]}</span></td>
                    <td><span class="status ${appeal.status}">${labels.statuses[appeal.status]}</span></td>
                    <td class="deadline-cell">${formatDeadline(appeal.deadlineAt, appeal.status)}</td>
                </tr>
            `).join("")}
            </tbody>
        </table>
    `;
}

function renderMetrics() {
    document.querySelector("#metric-total").textContent = state.employeeAppeals.length;
    document.querySelector("#metric-high").textContent =
        state.employeeAppeals.filter(item => ["HIGH", "CRITICAL"].includes(item.priority)).length;
    document.querySelector("#metric-progress").textContent =
        state.employeeAppeals.filter(item => item.status === "IN_PROGRESS").length;
    document.querySelector("#metric-waiting").textContent =
        state.employeeAppeals.filter(item => item.status === "WAITING_CUSTOMER").length;
}

function renderTransferApprovals() {
    const employee = activeEmployee();
    const panel = document.querySelector("#approval-panel");
    if (!employee || employee.role !== "SUPERVISOR") {
        panel.hidden = true;
        return;
    }
    panel.hidden = false;
    document.querySelector("#approval-count").textContent = state.transferRequests.length;
    const target = document.querySelector("#transfer-approvals");
    if (!state.transferRequests.length) {
        target.innerHTML = '<div class="empty-state compact"><p>Новых запросов на передачу нет</p></div>';
        return;
    }
    target.innerHTML = state.transferRequests.map(item => `
        <article class="approval-card">
            <button class="approval-main" data-open-appeal="${item.appealId}">
                <span class="appeal-number">${escapeHtml(item.appealPublicNumber)}</span>
                <h3>${escapeHtml(item.appealSubject)}</h3>
                <p>${escapeHtml(item.customerName)} · из отдела «${escapeHtml(item.sourceDepartment.name)}»</p>
                <blockquote>${escapeHtml(item.reason)}</blockquote>
                <small>Запросил: ${escapeHtml(item.requestedBy.fullName)} · ${formatDate(item.createdAt)}</small>
            </button>
            <form class="approval-actions" data-review-transfer="${item.id}">
                <input name="comment" value="Передача согласована, принимаем в работу" maxlength="500" required>
                <button class="secondary-button approve-button" name="approved" value="true">Принять</button>
                <button class="secondary-button reject-button" name="approved" value="false">Отклонить</button>
            </form>
        </article>
    `).join("");
}

async function loadEvents() {
    const events = await api("/api/events");
    const target = document.querySelector("#event-list");
    if (!events.length) {
        target.className = "event-list empty-state";
        target.innerHTML = "<p>Событий пока нет</p>";
        return;
    }
    target.className = "event-list";
    target.innerHTML = events.map(event => `
        <div class="event-row">
            <span class="event-type">${escapeHtml(event.eventType)}</span>
            <span class="event-key">${escapeHtml(event.routingKey)}</span>
            <span>${event.appealId ? escapeHtml(event.appealId) : "—"}</span>
            <span>${formatDate(event.receivedAt)}</span>
        </div>
    `).join("");
}

async function openAppeal(id) {
    const appeal = await api(`/api/appeals/${id}`);
    state.activeAppealId = id;
    const modal = document.querySelector("#appeal-modal");
    document.querySelector("#modal-content").innerHTML = detailTemplate(appeal);
    modal.classList.add("open");
    modal.setAttribute("aria-hidden", "false");
}

function detailTemplate(appeal) {
    const employee = activeEmployee();
    const employeeMode = document.querySelector("#employee-view").classList.contains("active");
    const canWork = employeeMode
        && employee
        && appeal.department?.code === employee.department.code;
    const pendingTransfer = appeal.transfers.find(item => item.status === "PENDING");
    const departments = state.departments
        .filter(item => item.code !== appeal.department?.code)
        .map(item => `<option value="${escapeHtml(item.code)}">${escapeHtml(item.name)}</option>`)
        .join("");
    const attributes = Object.entries(appeal.details || {});

    return `
        <header class="detail-head">
            <span class="appeal-number">${escapeHtml(appeal.publicNumber)}</span>
            <h2>${escapeHtml(appeal.subject)}</h2>
            <p>${escapeHtml(appeal.description)}</p>
            <div class="detail-badges">
                <span class="status ${appeal.status}">${labels.statuses[appeal.status]}</span>
                <span class="priority ${appeal.priority}">${labels.priorities[appeal.priority]}</span>
            </div>
        </header>

        <div class="detail-grid">
            <div><small>Клиент</small><strong>${escapeHtml(appeal.customerName)}</strong></div>
            <div><small>Полис</small><strong>${escapeHtml(appeal.contract.policyNumber)}</strong></div>
            <div><small>Категория</small><strong>${escapeHtml(categoryLabel(appeal.category))}</strong></div>
            <div><small>Тип обращения</small><strong>${escapeHtml(subcategoryLabel(appeal.category, appeal.subcategory))}</strong></div>
            <div><small>Отдел</small><strong>${appeal.department ? escapeHtml(appeal.department.name) : "Ожидает маршрутизации"}</strong></div>
            <div><small>Исполнитель</small><strong>${escapeHtml(appeal.assignedEmployee || "Не назначен")}</strong></div>
            <div><small>Создано</small><strong>${formatDate(appeal.createdAt)}</strong></div>
            <div class="deadline-cell"><small>Срок ответа</small>${formatDeadline(appeal.deadlineAt, appeal.status)}</div>
        </div>

        <section class="detail-section contract-detail">
            <h3>Данные из договора</h3>
            <div class="detail-attributes">
                <div class="detail-attribute"><small>Вид страхования</small><strong>${escapeHtml(appeal.contract.insuranceType)}</strong></div>
                <div class="detail-attribute"><small>Срок действия</small><strong>${formatShortDate(appeal.contract.validFrom)} – ${formatShortDate(appeal.contract.validTo)}</strong></div>
                <div class="detail-attribute wide"><small>Объект страхования</small><strong>${escapeHtml(appeal.contract.insuredObject)}</strong></div>
            </div>
            <small class="source-note">Источник: учётная система договоров. Клиент не изменял эти данные в обращении.</small>
        </section>

        ${attributes.length ? `
            <section class="detail-section">
                <h3>Данные обращения</h3>
                <div class="detail-attributes">
                    ${attributes.map(([key, value]) => `
                        <div class="detail-attribute">
                            <small>${escapeHtml(fieldLabel(key))}</small>
                            <strong>${escapeHtml(value)}</strong>
                        </div>
                    `).join("")}
                </div>
            </section>
        ` : ""}

        <section class="detail-section">
            <h3>Вложения</h3>
            ${appeal.attachments.length ? appeal.attachments.map(attachment => `
                <a class="attachment-link" href="${attachment.downloadUrl}" target="_blank">
                    <span>${escapeHtml(attachment.originalName)}</span>
                    <small>${formatFileSize(attachment.sizeBytes)} · скачать</small>
                </a>
            `).join("") : "<p>К обращению файлы не приложены.</p>"}
        </section>

        ${canWork ? `<section class="detail-section">
            <h3>Действия сотрудника</h3>
            <div class="action-grid">
                <form class="action-box" data-status-form>
                    <h3>Изменить статус</h3>
                    <label>Новый статус
                        <select name="status">
                            <option value="IN_PROGRESS">Взять в работу</option>
                            <option value="WAITING_CUSTOMER">Ожидает клиента</option>
                            <option value="RESOLVED">Решено</option>
                            <option value="CLOSED">Закрыто</option>
                        </select>
                    </label>
                    <button class="secondary-button" type="submit">Сохранить статус</button>
                </form>

                <form class="action-box" data-transfer-form>
                    <h3>Запросить передачу</h3>
                    ${pendingTransfer ? `
                        <p class="pending-note">Уже ожидается решение руководителя отдела «${escapeHtml(pendingTransfer.targetDepartment.name)}».</p>
                    ` : `
                    <label>Новый отдел
                        <select name="departmentCode">${departments}</select>
                    </label>
                    <label>Причина
                        <input name="reason" value="Требуется компетенция другого отдела" required>
                    </label>
                    <button class="secondary-button" type="submit">Отправить руководителю</button>
                    `}
                </form>
            </div>
        </section>` : ""}

        <section class="detail-section">
            <h3>Переписка</h3>
            ${canWork ? `<form class="action-box" data-message-form>
                <label>Ответ
                    <textarea name="body" rows="3" placeholder="Напишите сообщение клиенту..." required></textarea>
                </label>
                <button class="secondary-button" type="submit">Отправить ответ</button>
            </form>` : ""}
            <div class="detail-section">
                ${appeal.messages.length ? appeal.messages.map(message => `
                    <div class="message-item">
                        <strong>${escapeHtml(message.authorName)} · ${message.authorType === "CLIENT" ? "Клиент" : "Сотрудник"}</strong>
                        <p>${escapeHtml(message.body)}</p>
                        <small>${formatDate(message.createdAt)}</small>
                    </div>
                `).join("") : "<p>Сообщений пока нет.</p>"}
            </div>
        </section>

        ${appeal.transfers.length ? `
            <section class="detail-section">
                <h3>Согласование передач</h3>
                ${appeal.transfers.map(item => `
                    <div class="transfer-history ${item.status}">
                        <strong>${escapeHtml(item.sourceDepartment.name)} → ${escapeHtml(item.targetDepartment.name)}</strong>
                        <span class="status">${item.status === "PENDING" ? "Ожидает решения" : item.status === "APPROVED" ? "Принято" : "Отклонено"}</span>
                        <p>${escapeHtml(item.reason)}</p>
                        <small>Запросил ${escapeHtml(item.requestedBy.fullName)} · ${formatDate(item.createdAt)}</small>
                        ${item.reviewedBy ? `<small>Решение: ${escapeHtml(item.reviewedBy.fullName)} · ${escapeHtml(item.reviewComment)}</small>` : ""}
                    </div>
                `).join("")}
            </section>
        ` : ""}

        <section class="detail-section">
            <h3>История обработки</h3>
            ${appeal.history.map(item => `
                <div class="timeline-item">
                    <strong>${escapeHtml(item.actor)}</strong>
                    <p>${escapeHtml(item.description)}</p>
                    <small>${formatDate(item.createdAt)}</small>
                </div>
            `).join("")}
        </section>
    `;
}

async function refreshModal() {
    if (state.activeAppealId) await openAppeal(state.activeAppealId);
    await loadAllAppeals();
}

document.addEventListener("click", async event => {
    const viewButton = event.target.closest("[data-view]");
    if (viewButton) {
        event.preventDefault();
        setView(viewButton.dataset.view);
    }

    const appealButton = event.target.closest("[data-open-appeal]");
    if (appealButton) {
        try {
            await openAppeal(appealButton.dataset.openAppeal);
        } catch (error) {
            showToast(error.message, true);
        }
    }

    if (event.target.closest("[data-close-modal]")) {
        document.querySelector("#appeal-modal").classList.remove("open");
        state.activeAppealId = null;
    }
});

document.querySelector("#appeal-form").addEventListener("submit", async event => {
    event.preventDefault();
    const formElement = event.currentTarget;
    if (!ensureFormCategoryConsistency()) {
        showToast("Тип обращения обновлён после смены категории. Проверьте поля и отправьте форму ещё раз.", true);
        return;
    }
    const form = new FormData(formElement);
    const details = {};
    formElement.querySelectorAll('[name^="detail."]').forEach(input => {
        details[input.name.substring("detail.".length)] = input.value;
    });
    const payload = {
        clientId: state.activeClientId,
        contractId: form.get("contractId"),
        category: form.get("category"),
        subcategory: form.get("subcategory"),
        details,
        subject: form.get("subject"),
        description: form.get("description")
    };
    try {
        const created = await api("/api/appeals", {
            method: "POST",
            body: JSON.stringify(payload)
        });

        const files = [...document.querySelector("#appeal-files").files];
        if (files.length) {
            const upload = new FormData();
            files.forEach(file => upload.append("files", file));
            try {
                await api(`/api/appeals/${created.id}/attachments`, {
                    method: "POST",
                    body: upload
                });
            } catch (uploadError) {
                showToast(`Обращение создано, но вложения не загружены: ${uploadError.message}`, true);
                await loadAllAppeals();
                await openAppeal(created.id);
                return;
            }
        }

        showToast(`Обращение ${created.publicNumber} зарегистрировано${files.length ? " с вложениями" : ""}`);
        await loadAllAppeals();
        await openAppeal(created.id);
        window.setTimeout(refreshModal, 900);
    } catch (error) {
        showToast(error.message, true);
    }
});

document.querySelector("#modal-content").addEventListener("submit", async event => {
    event.preventDefault();
    const form = new FormData(event.target);
    const employee = activeEmployee();
    if (!employee) return;
    try {
        if (event.target.matches("[data-status-form]")) {
            await api(`/api/appeals/${state.activeAppealId}/status`, {
                method: "POST",
                body: JSON.stringify({
                    status: form.get("status"),
                    employeeName: employee.fullName
                })
            });
            showToast("Статус обновлён");
        }
        if (event.target.matches("[data-transfer-form]")) {
            await api(`/api/appeals/${state.activeAppealId}/transfer-requests`, {
                method: "POST",
                body: JSON.stringify({
                    departmentCode: form.get("departmentCode"),
                    reason: form.get("reason"),
                    employeeId: employee.id
                })
            });
            showToast("Запрос передачи отправлен руководителю целевого отдела");
        }
        if (event.target.matches("[data-message-form]")) {
            await api(`/api/appeals/${state.activeAppealId}/messages`, {
                method: "POST",
                body: JSON.stringify({
                    authorType: "EMPLOYEE",
                    authorName: employee.fullName,
                    body: form.get("body")
                })
            });
            showToast("Ответ добавлен");
        }
        await refreshModal();
    } catch (error) {
        showToast(error.message, true);
    }
});

document.querySelector("#transfer-approvals").addEventListener("submit", async event => {
    const formElement = event.target.closest("[data-review-transfer]");
    if (!formElement) return;
    event.preventDefault();
    const employee = activeEmployee();
    const submitter = event.submitter;
    const form = new FormData(formElement);
    try {
        const approved = submitter?.value === "true";
        await api(`/api/transfer-requests/${formElement.dataset.reviewTransfer}/review`, {
            method: "POST",
            body: JSON.stringify({
                employeeId: employee.id,
                approved,
                comment: form.get("comment")
            })
        });
        showToast(approved ? "Передача согласована: обращение принято отделом" : "Передача отклонена");
        await loadAllAppeals();
        if (state.activeAppealId) await openAppeal(state.activeAppealId);
    } catch (error) {
        showToast(error.message, true);
    }
});

document.querySelector("#refresh-client").addEventListener("click", loadClientAppeals);
document.querySelector("#refresh-employee").addEventListener("click", loadEmployeeWorkspace);
document.querySelector("#refresh-events").addEventListener("click", loadEvents);
document.querySelector("#status-filter").addEventListener("change", loadEmployeeWorkspace);
document.querySelector("#employee-selector").addEventListener("change", event => {
    state.activeEmployeeId = event.target.value;
    renderEmployeeIdentity();
    loadEmployeeWorkspace().catch(error => showToast(error.message, true));
});
document.querySelector("#contract-select").addEventListener("change", () => {
    renderContractSummary();
    loadFormSchema().catch(error => showToast(error.message, true));
});
document.querySelector("#appeal-category").addEventListener("change", renderSubcategories);
document.querySelector("#appeal-subcategory").addEventListener("change", renderDynamicFields);
document.querySelector("#appeal-files").addEventListener("change", renderSelectedFiles);

window.addEventListener("pageshow", ensureFormCategoryConsistency);

loadDirectories()
    .then(loadAllAppeals)
    .catch(error => showToast(`Backend недоступен: ${error.message}`, true));

window.setInterval(() => {
    if (!document.querySelector("#appeal-modal").classList.contains("open")) {
        loadAllAppeals().catch(() => {});
    }
}, 5000);
