// Общее для трёх страниц: запросы с токеном, подписи, форматирование, переписка и карточка.
// Страница клиента, рабочее место и администрирование берут отсюда одно и то же.

const session = {me: null, schema: {categories: []}};

const STATUS_LABELS = {
    PENDING_ROUTING: "Маршрутизация",
    ROUTED: "Новое",
    IN_PROGRESS: "В работе",
    WAITING_CUSTOMER: "Ждём клиента",
    RESOLVED: "Решено",
    CLOSED: "Закрыто"
};

const PRIORITY_LABELS = {LOW: "Низкий", NORMAL: "Обычный", HIGH: "Высокий", CRITICAL: "Критический"};

const CONTRACT_STATUS_LABELS = {
    NOT_STARTED: "Не вступил в силу",
    ACTIVE: "Действует",
    EXPIRED: "Истёк",
    TERMINATED: "Прекращён досрочно"
};

const EVENT_LABELS = {
    APPEAL_CREATED: "Обращение зарегистрировано",
    APPEAL_ROUTED: "Назначен отдел",
    APPEAL_STATUS_CHANGED: "Сменился статус",
    APPEAL_MESSAGE_ADDED: "Новое сообщение",
    APPEAL_ATTACHMENTS_ADDED: "Приложены файлы",
    APPEAL_TRANSFER_REQUESTED: "Запрошена передача",
    APPEAL_TRANSFER_APPROVED: "Передача согласована",
    APPEAL_TRANSFER_REJECTED: "Передача отклонена"
};

const COUNTING_LABELS = {
    WORKING_DAYS: "рабочих дней",
    CALENDAR_DAYS: "календарных дней",
    CALENDAR_EXCEPT_HOLIDAYS: "календарных дней без праздников"
};

const ROLE_LABELS = {CLIENT: "Клиент", SPECIALIST: "Специалист", SUPERVISOR: "Руководитель", ADMIN: "Администратор"};

const hasRole = role => session.me?.roles.includes(role);

async function api(path, options = {}) {
    const isFormData = options.body instanceof FormData;
    const response = await fetch(path, {
        ...options,
        headers: {
            ...(isFormData ? {} : {"Content-Type": "application/json"}),
            Authorization: `Bearer ${auth.token()}`,
            ...(options.headers || {})
        }
    });
    if (response.status === 401) {
        return auth.login();
    }
    if (!response.ok) {
        const error = await response.json().catch(() => ({message: "Ошибка запроса"}));
        throw new Error(error.message || "Ошибка запроса");
    }
    return response.json();
}

/** Каждой роли — своя страница. Чужую страницу открыть можно, но она перенаправит. */
function homeFor(me) {
    if (me.roles.includes("ADMIN")) return "/admin.html";
    if (me.roles.includes("CLIENT")) return "/client.html";
    return "/workspace.html";
}

async function startPage(page, onReady) {
    try {
        await auth.init();
        session.me = await api("/api/me");
        if (homeFor(session.me) !== page) {
            window.location.replace(homeFor(session.me));
            return;
        }
        session.schema = await api("/api/form-schema");
        renderUserBox();
        await onReady();
    } catch (error) {
        showToast(`Не удалось загрузить данные: ${error.message}`, true);
    }
}

function renderUserBox() {
    const me = session.me;
    const role = me.roles.map(item => ROLE_LABELS[item]).filter(Boolean).join(", ");
    const place = me.department ? ` · ${me.department.name}` : "";
    document.querySelector("#user-box").innerHTML = `
        <span class="avatar">${escapeHtml(initials(me.name))}</span>
        <span class="user-text">
            <strong>${escapeHtml(me.name || "Пользователь")}</strong>
            <small>${escapeHtml(role + place)}</small>
        </span>
        <button class="btn ghost on-dark" type="button" id="logout-button">Выйти</button>
    `;
    document.querySelector("#logout-button").addEventListener("click", () => auth.logout());
}

// ---------- форматирование ----------

function escapeHtml(value) {
    return String(value ?? "")
        .replaceAll("&", "&amp;")
        .replaceAll("<", "&lt;")
        .replaceAll(">", "&gt;")
        .replaceAll('"', "&quot;")
        .replaceAll("'", "&#039;");
}

function initials(name) {
    return String(name || "?").split(/\s+/).slice(0, 2).map(part => part[0] || "").join("").toUpperCase();
}

function formatDateTime(value) {
    if (!value) return "—";
    return new Intl.DateTimeFormat("ru-RU", {
        day: "2-digit", month: "2-digit", year: "numeric", hour: "2-digit", minute: "2-digit"
    }).format(new Date(value));
}

function formatDate(value) {
    if (!value) return "—";
    return new Intl.DateTimeFormat("ru-RU").format(new Date(`${value}T00:00:00`));
}

function formatTime(value) {
    return new Intl.DateTimeFormat("ru-RU", {hour: "2-digit", minute: "2-digit"}).format(new Date(value));
}

function formatFileSize(bytes) {
    if (bytes < 1024 * 1024) return `${Math.ceil(bytes / 1024)} КБ`;
    return `${(bytes / (1024 * 1024)).toFixed(1)} МБ`;
}

/** Сколько осталось до срока ответа: текст и тон (ok / soon / late / done). */
function deadlineInfo(appeal) {
    if (["RESOLVED", "CLOSED"].includes(appeal.status)) return {text: "закрыт срок", tone: "done"};
    if (!appeal.deadlineAt) return {text: "срок не назначен", tone: "none"};
    const left = new Date(appeal.deadlineAt).getTime() - Date.now();
    const hours = Math.floor(Math.abs(left) / 3600000);
    const minutes = Math.floor((Math.abs(left) % 3600000) / 60000);
    const span = hours >= 24 ? `${Math.floor(hours / 24)} д ${hours % 24} ч` : hours ? `${hours} ч ${minutes} мин` : `${minutes} мин`;
    if (left < 0) return {text: `просрочено на ${span}`, tone: "late"};
    return {text: `осталось ${span}`, tone: left < 4 * 3600000 ? "soon" : "ok"};
}

function deadlinePill(appeal) {
    const info = deadlineInfo(appeal);
    return `<span class="pill ${info.tone}" title="Срок ответа: ${formatDateTime(appeal.deadlineAt)}">${escapeHtml(info.text)}</span>`;
}

/** Срок по закону — граница, а не целевой срок отдела (решение 20). */
function isLegallyOverdue(appeal) {
    if (!appeal.legalDeadline || ["RESOLVED", "CLOSED"].includes(appeal.status)) return false;
    return new Date(`${appeal.legalDeadline}T23:59:59`) < new Date();
}

function legalPill(appeal) {
    if (!appeal.legalDeadline) return "";
    const tone = ["RESOLVED", "CLOSED"].includes(appeal.status) ? "done" : isLegallyOverdue(appeal) ? "late" : "legal";
    return `<span class="pill ${tone}" title="Срок по закону">по закону до ${formatDate(appeal.legalDeadline)}</span>`;
}

function renderNorm(appeal) {
    if (!appeal.legalDeadline || !appeal.norm) {
        return `<p class="muted">Норматив не назначен: обращение создано до справочника сроков</p>`;
    }
    const norm = appeal.norm;
    return `
        <dl class="kv">
            <dt>Срок</dt><dd><strong>до ${formatDate(appeal.legalDeadline)}</strong>${isLegallyOverdue(appeal) ? ` <span class="late-text">просрочен</span>` : ""}</dd>
            <dt>Норматив</dt><dd>${escapeHtml(norm.title)}: ${norm.termDays} ${COUNTING_LABELS[norm.counting] || ""}</dd>
            <dt>Основание</dt><dd>${escapeHtml(norm.legalBasis)}</dd>
            ${appeal.deadlineExtendedDays ? `<dt>Продлён</dt><dd>на ${appeal.deadlineExtendedDays} раб. дн.</dd>` : ""}
        </dl>`;
}

function statusChip(status, labels = STATUS_LABELS) {
    return `<span class="chip status-${status}">${escapeHtml(labels[status] || status)}</span>`;
}

function priorityMark(priority) {
    return ["HIGH", "CRITICAL"].includes(priority)
        ? `<span class="priority-mark" title="${escapeHtml(PRIORITY_LABELS[priority])} приоритет"></span>`
        : "";
}

function showToast(message, error = false) {
    const toast = document.querySelector("#toast");
    toast.textContent = message;
    toast.className = `toast visible${error ? " error" : ""}`;
    window.clearTimeout(showToast.timer);
    showToast.timer = window.setTimeout(() => toast.className = "toast", 3500);
}

// ---------- схема формы: подписи категорий, типов и полей ----------

function schemaCategory(schema, category) {
    return schema.categories.find(item => item.code === category);
}

function schemaType(schema, category, type) {
    return schemaCategory(schema, category)?.types.find(item => item.code === type);
}

function categoryLabel(category) {
    return schemaCategory(session.schema, category)?.label || category;
}

function typeLabel(category, type) {
    return schemaType(session.schema, category, type)?.label || type;
}

function fieldLabel(key) {
    // insuredObject — атрибут обращений, созданных до схемы формы
    if (key === "insuredObject") return "Объект страхования";
    const field = session.schema.categories
        .flatMap(category => category.types)
        .flatMap(type => type.fields)
        .find(item => item.code === key);
    return field?.label || key;
}

function formatDetailValue(key, value) {
    return /^\d{4}-\d{2}-\d{2}$/.test(value) ? formatDate(value) : value;
}

// ---------- части карточки обращения ----------

/**
 * Переписка: описание обращения — первое сообщение клиента. Свои сообщения справа.
 * viewer — CLIENT или EMPLOYEE: с чьей стороны смотрим.
 */
function renderThread(appeal, viewer) {
    const items = [
        {authorType: "CLIENT", authorName: appeal.customerName, body: appeal.description, createdAt: appeal.createdAt},
        ...appeal.messages
    ];
    return `<div class="thread">${items.map(item => {
        const own = item.authorType === viewer;
        const who = own ? "Вы" : item.authorType === "CLIENT" ? item.authorName : `${item.authorName} · сотрудник`;
        return `
            <div class="bubble ${own ? "own" : ""}">
                <small>${escapeHtml(who)} · ${formatDateTime(item.createdAt)}</small>
                <p>${escapeHtml(item.body)}</p>
            </div>`;
    }).join("")}</div>`;
}

function renderAttachments(appeal) {
    if (!appeal.attachments.length) return `<p class="muted">Файлов нет</p>`;
    return `<div class="files">${appeal.attachments.map(file => `
        <button type="button" class="file" data-download="${file.id}" data-name="${escapeHtml(file.originalName)}">
            <span>${escapeHtml(file.originalName)}</span>
            <small>${formatFileSize(file.sizeBytes)}</small>
        </button>`).join("")}</div>`;
}

function renderContract(contract) {
    return `
        <dl class="kv">
            <dt>Договор</dt><dd>${escapeHtml(contract.policyNumber)} · ${escapeHtml(contract.insuranceType)}</dd>
            <dt>Объект</dt><dd>${escapeHtml(contract.insuredObject)}</dd>
            <dt>Срок</dt><dd>${formatDate(contract.validFrom)} – ${formatDate(contract.validTo)}</dd>
            <dt>Статус</dt><dd>${escapeHtml(CONTRACT_STATUS_LABELS[contract.status] || contract.status)}</dd>
        </dl>`;
}

function renderDetails(appeal) {
    const entries = Object.entries(appeal.details || {}).filter(([key]) => key !== "policyNumber");
    if (!entries.length) return `<p class="muted">Дополнительных данных нет</p>`;
    return `<dl class="kv">${entries.map(([key, value]) =>
        `<dt>${escapeHtml(fieldLabel(key))}</dt><dd>${escapeHtml(formatDetailValue(key, value))}</dd>`).join("")}</dl>`;
}

function renderHistory(appeal) {
    return `<ol class="journal">${appeal.history.map(item => `
        <li>
            <small>${formatDateTime(item.createdAt)} · ${escapeHtml(item.actor)}</small>
            <p>${escapeHtml(item.description)}</p>
        </li>`).join("")}</ol>`;
}

function renderTransfers(appeal) {
    if (!appeal.transfers.length) return "";
    const labels = {PENDING: "ждёт решения", APPROVED: "принята", REJECTED: "отклонена"};
    return `<ol class="journal">${appeal.transfers.map(item => `
        <li>
            <small>${formatDateTime(item.createdAt)} · ${escapeHtml(item.requestedBy.fullName)}</small>
            <p>${escapeHtml(item.sourceDepartment.name)} → ${escapeHtml(item.targetDepartment.name)}: ${labels[item.status]}.
               ${escapeHtml(item.reason)}${item.reviewComment ? ` Ответ: ${escapeHtml(item.reviewComment)}` : ""}</p>
        </li>`).join("")}</ol>`;
}

function appealHeading(appeal) {
    return `
        <div class="card-head">
            <div class="card-title">
                <small>${escapeHtml(appeal.publicNumber)} · ${escapeHtml(categoryLabel(appeal.category))} · ${escapeHtml(typeLabel(appeal.category, appeal.subcategory))}</small>
                <h2>${escapeHtml(appeal.subject)}</h2>
            </div>
            <div class="card-badges">${statusChip(appeal.status)}${deadlinePill(appeal)}${legalPill(appeal)}</div>
        </div>`;
}

/** Ссылка на файл не передаст токен, поэтому файл забирается запросом с токеном. */
async function downloadAttachment(id, name) {
    const response = await fetch(`/api/attachments/${id}`, {headers: {Authorization: `Bearer ${auth.token()}`}});
    if (!response.ok) throw new Error("Не удалось скачать файл");
    const url = URL.createObjectURL(await response.blob());
    Object.assign(document.createElement("a"), {href: url, download: name}).click();
    URL.revokeObjectURL(url);
}

document.addEventListener("click", event => {
    const file = event.target.closest("[data-download]");
    if (file) {
        downloadAttachment(file.dataset.download, file.dataset.name).catch(error => showToast(error.message, true));
    }
});

// ---------- журнал событий: для сотрудника и администратора ----------

async function loadEvents(target) {
    const events = await api("/api/events");
    target.innerHTML = events.length ? `
        <table class="table events">
            <thead><tr><th>Время</th><th>Событие</th><th>Ключ маршрутизации</th><th>Обращение</th></tr></thead>
            <tbody>${events.map(event => `
                <tr>
                    <td class="nowrap">${formatDateTime(event.receivedAt)}</td>
                    <td>${escapeHtml(EVENT_LABELS[event.eventType] || event.eventType)}</td>
                    <td><code title="${escapeHtml(event.routingKey)}">${escapeHtml(event.routingKey)}</code></td>
                    <td class="nowrap">${escapeHtml(event.appealNumber || "—")}</td>
                </tr>`).join("")}
            </tbody>
        </table>` : `<p class="empty">Событий пока нет</p>`;
}
