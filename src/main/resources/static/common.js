// Общее для трёх страниц: запросы с токеном, подписи, форматирование, переписка и карточка.
// Страница клиента, рабочее место и администрирование берут отсюда одно и то же.

const session = {me: null, schema: {categories: []}};

const STATUS_LABELS = {
    PENDING_ROUTING: "Маршрутизация",
    NEEDS_CLASSIFICATION: "Ручная классификация",
    ROUTED: "Новое",
    IN_PROGRESS: "В работе",
    WAITING_CUSTOMER: "Ждём клиента",
    RESOLVED: "Решено",
    CLOSED: "Закрыто"
};

const OUTCOME_LABELS = {
    SATISFIED: "Требование удовлетворено",
    PARTIALLY_SATISFIED: "Удовлетворено частично",
    REJECTED: "Отказано",
    EXPLAINED: "Даны разъяснения"
};

const PAYMENT_STATUS_LABELS = {PAID: "оплачена", INSTALLMENTS: "в рассрочку по графику", OVERDUE: "просрочена"};

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
    APPEAL_TRANSFER_REJECTED: "Передача отклонена",
    APPEAL_DEADLINE_EXTENDED: "Срок по закону продлён",
    APPEAL_CLASSIFICATION_REQUIRED: "Нужна ручная классификация",
    APPEAL_CONTRACT_LINKED: "Договор привязан",
    APPEAL_CONTRACT_CHOICE_REQUIRED: "Нужно выбрать договор",
    APPEAL_SLA_BREACHING: "Срок по закону истекает",
    APPEAL_SLA_BREACHED: "Срок по закону нарушен"
};

/** Эскалация по сроку закона — только для сотрудников, клиенту не показывается. */
const ESCALATION_LABELS = {AT_RISK: "срок по закону истекает", BREACHED: "срок по закону нарушен"};

const COUNTING_LABELS = {
    WORKING_DAYS: "рабочих дней",
    CALENDAR_DAYS: "календарных дней",
    CALENDAR_EXCEPT_HOLIDAYS: "календарных дней без праздников"
};

const ROLE_LABELS = {CLIENT: "Клиент", SPECIALIST: "Специалист", SUPERVISOR: "Руководитель", ADMIN: "Администратор",
    OPERATOR: "Оператор контакт-центра"};
/** Как поступило обращение (решение 38). */
const CHANNEL_LABELS = {CABINET: "через личный кабинет", PHONE: "по телефону", OFFICE: "в офисе"};

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
    // 204 — действие выполнено, отвечать нечем
    return response.status === 204 ? null : response.json();
}

/** Каждой роли — своя страница. Чужую страницу открыть можно, но она перенаправит. */
function homeFor(me) {
    if (me.roles.includes("ADMIN")) return "/admin.html";
    if (me.roles.includes("CLIENT")) return "/client.html";
    if (me.roles.includes("OPERATOR")) return "/operator.html";
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
        // страница не загрузилась: говорим об этом на самой странице, а не только всплывающим сообщением
        document.querySelector("main")?.insertAdjacentHTML("afterbegin",
            `<div class="page-error" role="alert">${errorState(`Не удалось загрузить данные: ${error.message}`, true)}</div>`);
    }
}

// ---------- состояния: пусто, загрузка, ошибка (одинаковые во всех интерфейсах) ----------

/** Пусто: что здесь будет и что сделать дальше. */
function emptyState(title, hint = "") {
    return `<div class="state"><p class="state-title">${escapeHtml(title)}</p>${
        hint ? `<p class="state-hint">${escapeHtml(hint)}</p>` : ""}</div>`;
}

function loadingState(text = "Загрузка") {
    return `<div class="state loading-state" role="status"><span class="spinner" aria-hidden="true"></span>${escapeHtml(text)}…</div>`;
}

/** Ошибка загрузки; reload — показать кнопку «Повторить», которая перезагружает страницу. */
function errorState(text, reload = false) {
    return `<div class="state error-state"><p class="state-title">${escapeHtml(text)}</p>${
        reload ? `<button type="button" class="btn" data-reload>Повторить</button>` : ""}</div>`;
}

document.addEventListener("click", event => {
    if (event.target.closest("[data-reload]")) window.location.reload();
});

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
    // через три дня часы уже не важны: «просрочено на 130 д», а не «на 130 д 2 ч»
    const days = Math.floor(hours / 24);
    const span = days >= 3 ? `${days} д` : hours >= 24 ? `${days} д ${hours % 24} ч` : hours ? `${hours} ч ${minutes} мин` : `${minutes} мин`;
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
    const tone = ["RESOLVED", "CLOSED"].includes(appeal.status) ? "done"
        : isLegallyOverdue(appeal) || appeal.escalationLevel === "BREACHED" ? "late"
        : appeal.escalationLevel === "AT_RISK" ? "soon" : "legal";
    return `<span class="pill ${tone}" title="Срок по закону">по закону до ${formatDate(appeal.legalDeadline)}</span>`;
}

function isEscalated(appeal) {
    return ESCALATION_LABELS[appeal.escalationLevel] && !["RESOLVED", "CLOSED"].includes(appeal.status);
}

function escalationPill(appeal) {
    if (!isEscalated(appeal)) return "";
    const tone = appeal.escalationLevel === "BREACHED" ? "late" : "soon";
    return `<span class="pill ${tone}" title="Срок по закону: до ${formatDate(appeal.legalDeadline)}">${ESCALATION_LABELS[appeal.escalationLevel]}</span>`;
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
            ${appeal.deadlineExtendedDays ? `<dt>Продлён</dt><dd>на ${appeal.deadlineExtendedDays} раб. дн.${appeal.deadlineExtensionReason
                ? `<br><small class="muted">${escapeHtml(appeal.deadlineExtensionReason)}</small>` : ""}</dd>` : ""}
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
    // первое сообщение — само обращение, с файлами из его формы
    const items = [
        {
            authorType: "CLIENT", authorName: appeal.customerName, body: appeal.description, createdAt: appeal.createdAt,
            attachments: appeal.attachments.filter(file => file.messageId == null)
        },
        ...appeal.messages
    ];
    return `<div class="thread">${items.map(item => {
        const own = item.authorType === viewer;
        const who = own ? "Вы" : item.authorType === "CLIENT" ? item.authorName : `${item.authorName} · сотрудник`;
        return `
            <div class="bubble ${own ? "own" : ""}">
                <small>${escapeHtml(who)} · ${formatDateTime(item.createdAt)}</small>
                ${item.body ? `<p>${escapeHtml(item.body)}</p>` : ""}
                ${item.attachments?.length ? renderFileList(item.attachments) : ""}
            </div>`;
    }).join("")}</div>`;
}

function renderFileList(files) {
    return `<div class="files">${files.map(file => `
        <button type="button" class="file" data-download="${file.id}" data-name="${escapeHtml(file.originalName)}">
            <span>${escapeHtml(file.originalName)}</span>
            <small>${formatFileSize(file.sizeBytes)}</small>
        </button>`).join("")}</div>`;
}

/** Все файлы обращения в одном месте: из формы и из переписки. */
function renderAttachments(appeal) {
    if (!appeal.attachments.length) return `<p class="muted">Файлов нет</p>`;
    return renderFileList(appeal.attachments);
}

// ---------- файлы в ответе ----------

const FILE_ACCEPT = ".pdf,.jpg,.jpeg,.png,application/pdf,image/jpeg,image/png";
const FILE_HINT = "PDF, JPG или PNG, до 10 МБ, не больше 5 за раз";

/** Кнопка «Прикрепить файлы» для формы ответа: выбранные файлы видны до отправки. */
function attachField() {
    return `
        <span class="attach">
            <label class="btn ghost attach-button">Прикрепить файлы
                <input type="file" name="files" multiple accept="${FILE_ACCEPT}">
            </label>
            <small class="muted" data-attach-list>${FILE_HINT}</small>
        </span>`;
}

document.addEventListener("change", event => {
    const input = event.target.closest('.attach input[type="file"]');
    if (!input) return;
    input.closest(".attach").querySelector("[data-attach-list]").textContent = input.files.length
        ? [...input.files].map(file => file.name).join(", ")
        : FILE_HINT;
});

/** Текст и файлы ответа одним запросом. null — если отправлять нечего. */
function replyFormData(form) {
    const data = new FormData(form);
    // пустое поле файла браузер всё равно кладёт в форму — убираем его
    const files = data.getAll("files").filter(file => file.size > 0);
    data.delete("files");
    files.forEach(file => data.append("files", file));
    return String(data.get("body") || "").trim() || files.length ? data : null;
}

/** Начатый ответ — текст или выбранные файлы: фоновое обновление его не стирает. */
function hasDraft(container) {
    if (!container) return false;
    return [...container.querySelectorAll("textarea")].some(item => item.value.trim())
        || [...container.querySelectorAll('input[type="file"]')].some(item => item.files.length);
}

function renderContract(contract) {
    if (!contract) return `<p class="muted">Договор ещё не привязан</p>`;
    return `
        <dl class="kv">
            <dt>Договор</dt><dd>${escapeHtml(contract.policyNumber)} · ${escapeHtml(contract.insuranceType)}</dd>
            <dt>Объект</dt><dd>${escapeHtml(contract.insuredObject)}</dd>
            <dt>Срок</dt><dd>${formatDate(contract.validFrom)} – ${formatDate(contract.validTo)}</dd>
            <dt>Статус</dt><dd>${escapeHtml(CONTRACT_STATUS_LABELS[contract.status] || contract.status)}</dd>
        </dl>`;
}

/** Проверки, которые подсистема сделала за сотрудника: покрытие, договор, повторность. */
function renderChecks(appeal) {
    if (!appeal.checks?.length) return "";
    return `<ul class="checks">${appeal.checks.map(check => `
        <li class="${check.level === "WARNING" ? "warning" : "ok"}">
            <span class="check-mark">${check.level === "WARNING" ? "!" : "✓"}</span>${escapeHtml(check.message)}
        </li>`).join("")}</ul>`;
}

const money = value => value == null ? "нет" : `${Number(value).toLocaleString("ru-RU")} ₽`;

/** Условия договора — из учётной системы в момент запроса. */
function renderTerms(terms) {
    return `
        <dl class="kv terms">
            <dt>Страховая сумма</dt><dd>${money(terms.insuredSum)}</dd>
            <dt>Франшиза</dt><dd>${money(terms.deductible)}</dd>
            <dt>Премия</dt><dd>${money(terms.premium)}, ${escapeHtml(PAYMENT_STATUS_LABELS[terms.paymentStatus] || "оплата неизвестна")}</dd>
            <dt>Статус сейчас</dt><dd>${escapeHtml(CONTRACT_STATUS_LABELS[terms.current.status] || terms.current.status)}</dd>
            <dt>Риски</dt><dd>${terms.coverages.map(item => escapeHtml(item.name)).join(", ") || "не указаны"}</dd>
        </dl>
        <small class="muted">Из учётной системы договоров, на ${formatDateTime(new Date())}</small>`;
}

function renderRelated(related) {
    if (!related.length) return `<p class="muted">Других обращений нет</p>`;
    return `<ul class="related">${related.map(item => `
        <li>
            ${item.canOpen
                ? `<button type="button" class="link" data-open-related="${item.id}">${escapeHtml(item.subject)}</button>`
                : `<span title="Обращение другого отдела">${escapeHtml(item.subject)}</span>`}
            <small class="muted">${escapeHtml(item.publicNumber)} · ${formatDateTime(item.createdAt)}
                ${item.sameContract ? " · этот договор" : ""}</small>
            <small>${statusChip(item.status)}${item.outcome ? ` ${escapeHtml(OUTCOME_LABELS[item.outcome])}` : ""}
                ${item.departmentName ? ` · ${escapeHtml(item.departmentName)}` : ""}</small>
        </li>`).join("")}</ul>`;
}

async function loadRelated(appealId, target) {
    try {
        const related = await api(`/api/appeals/${appealId}/related`);
        target.innerHTML = renderRelated(related);
        target.closest("[data-related-section]")?.querySelector("[data-related-count]")
            ?.replaceChildren(document.createTextNode(` · ${related.length}`));
    } catch (error) {
        target.innerHTML = errorState(`Не удалось загрузить историю: ${error.message}`);
    }
}

async function loadTerms(appealId, target) {
    target.innerHTML = loadingState("Запрашиваем учётную систему");
    try {
        target.innerHTML = renderTerms(await api(`/api/appeals/${appealId}/contract`));
    } catch (error) {
        target.innerHTML = `<p class="muted">${escapeHtml(error.message)}</p>`;
    }
}

/** Блоки «Договор» и «История клиента» — общие для сотрудника и администратора. */
// ---------- договор, привязанный позже (решение 29) ----------

const CONTRACT_LINK_TEXT = {
    PENDING: "Учётная система договоров была недоступна. Договор подберётся автоматически, когда она заработает.",
    NEEDS_CHOICE: "Подходит несколько договоров, клиенту предложено выбрать. Можно выбрать за него.",
    NOT_FOUND: "Договор указанного вида у клиента не найден. Выберите из его договоров."
};

function requestedContractText(appeal) {
    if (!appeal.requestedProduct) return "";
    return `<p class="muted small-text">Клиент указал: ${escapeHtml(appeal.requestedProduct.label)}${appeal.requestedPolicyNumber
        ? `, номер ${escapeHtml(appeal.requestedPolicyNumber)}` : ""}</p>`;
}

/** Договор ещё не привязан: что происходит и что может сделать сотрудник отдела. */
function renderPendingContract(appeal, canWork, titled = true) {
    const action = !canWork ? ""
        : appeal.contractLink === "PENDING"
            ? `<button type="button" class="btn ghost small" data-link-now="${appeal.id}">Привязать сейчас</button>`
            : `<button type="button" class="btn ghost small" data-load-candidates="${appeal.id}">Выбрать договор</button>`;
    return `
        ${titled ? "<h4>Договор</h4>" : ""}
        <p class="notice small">${CONTRACT_LINK_TEXT[appeal.contractLink] || "Договор не привязан"}</p>
        ${requestedContractText(appeal)}
        <div id="candidates-${appeal.id}"></div>
        ${action}`;
}

async function loadCandidates(appealId, target) {
    const contracts = await api(`/api/appeals/${appealId}/contract-candidates`);
    target.innerHTML = contracts.length ? `<div class="choice-grid compact">${contracts.map(contract => `
        <button type="button" class="choice" data-choose-contract="${contract.id}" data-for-appeal="${appealId}">
            <strong>${escapeHtml(contract.insuranceType)} · ${escapeHtml(contract.policyNumber)}</strong>
            <small>${escapeHtml(contract.insuredObject)}</small>
            <small>${formatDate(contract.validFrom)} – ${formatDate(contract.validTo)}</small>
        </button>`).join("")}</div>` : `<p class="muted">В учётной системе у клиента нет договоров</p>`;
}

// после привязки страница перерисовывает обращение сама — afterContractLinked у каждой своя
document.addEventListener("click", event => {
    const linkNow = event.target.closest("[data-link-now]");
    const load = event.target.closest("[data-load-candidates]");
    const choose = event.target.closest("[data-choose-contract]");
    (async () => {
        if (linkNow) {
            const appeal = await api(`/api/appeals/${linkNow.dataset.linkNow}/contract/link`, {method: "POST"});
            showToast(appeal.contract ? `Договор привязан: ${appeal.contract.policyNumber}` : "Подходит несколько договоров, выберите нужный");
            await afterContractLinked(appeal.id);
        }
        if (load) {
            await loadCandidates(load.dataset.loadCandidates, document.getElementById(`candidates-${load.dataset.loadCandidates}`));
            load.remove();
        }
        if (choose) {
            const appeal = await api(`/api/appeals/${choose.dataset.forAppeal}/contract`, {
                method: "POST", body: JSON.stringify({contractId: choose.dataset.chooseContract})
            });
            showToast(`Договор привязан: ${appeal.contract.policyNumber}`);
            await afterContractLinked(appeal.id);
        }
    })().catch(error => showToast(error.message, true));
});

function renderContractSection(appeal, canWork = false, titled = true) {
    if (!appeal.contract) return renderPendingContract(appeal, canWork, titled);
    return `
        ${titled ? "<h4>Договор</h4>" : ""}${renderContract(appeal.contract)}
        <div id="terms-${appeal.id}"></div>
        <button type="button" class="btn ghost small" data-load-terms="${appeal.id}">Условия договора из учётной системы</button>`;
}

function renderRelatedSection(appeal) {
    return `
        <div data-related-section>
            <h4>История клиента<span data-related-count></span></h4>
            <div id="related-${appeal.id}">${loadingState()}</div>
        </div>`;
}

document.addEventListener("click", event => {
    const terms = event.target.closest("[data-load-terms]");
    if (terms) {
        loadTerms(terms.dataset.loadTerms, document.querySelector(`#terms-${terms.dataset.loadTerms}`));
        terms.remove();
    }
});

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

/** Сколько осталось до срока по закону, в календарных днях, и тон подсветки. */
function legalDeadlineInfo(appeal) {
    if (!appeal.legalDeadline) return {text: "норматив не назначен", tone: "none"};
    if (["RESOLVED", "CLOSED"].includes(appeal.status)) return {text: "ответ дан", tone: "done"};
    const today = new Date();
    today.setHours(0, 0, 0, 0);
    const days = Math.round((new Date(`${appeal.legalDeadline}T00:00:00`) - today) / 86400000);
    if (days < 0) return {text: `нарушен на ${-days} дн.`, tone: "late"};
    if (days === 0) return {text: "сегодня последний день", tone: "late"};
    return {text: `через ${days} дн.`, tone: appeal.escalationLevel === "AT_RISK" || days <= 2 ? "soon" : "ok"};
}

/**
 * Главное об обращении одной полосой под заголовком: сроки, статус, исполнитель, клиент и
 * сигналы риска. Сотрудник видит, что горит, не листая правую колонку.
 */
function appealFacts(appeal, signals = []) {
    const legal = legalDeadlineInfo(appeal);
    const department = deadlineInfo(appeal);
    const risks = signals.filter(signal => signal.risk);
    const channel = appeal.channel && appeal.channel !== "CABINET"
        ? `принято ${CHANNEL_LABELS[appeal.channel]}` : appeal.customerEmail || "";
    return `
        <div class="facts">
            <div class="fact ${legal.tone}"><small>Срок по закону</small>
                <strong>${appeal.legalDeadline ? `до ${formatDate(appeal.legalDeadline)}` : "не назначен"}</strong>
                <span>${escapeHtml(legal.text)}</span></div>
            <div class="fact ${department.tone}"><small>Срок отдела</small>
                <strong>${appeal.deadlineAt ? formatDateTime(appeal.deadlineAt) : "не назначен"}</strong>
                <span>${escapeHtml(department.text)}</span></div>
            <div class="fact"><small>Статус</small>${statusChip(appeal.status)}</div>
            <div class="fact"><small>Исполнитель</small><strong>${escapeHtml(appeal.assignedEmployee || "не назначен")}</strong></div>
            <div class="fact"><small>Клиент</small><strong>${escapeHtml(appeal.customerName)}</strong>
                <span>${escapeHtml(channel)}</span></div>
        </div>
        ${risks.length ? `<div class="fact-signals">${risks.map(signal =>
            `<span class="badge late">⚠ ${escapeHtml(signal.label)}</span>`).join("")}</div>` : ""}`;
}

function appealHeading(appeal, signals = []) {
    return `
        <div class="card-head">
            <div class="card-title">
                <small>${escapeHtml(appeal.publicNumber)} · ${escapeHtml(categoryLabel(appeal.category))} · ${escapeHtml(typeLabel(appeal.category, appeal.subcategory))}</small>
                <h2>${escapeHtml(appeal.subject)}</h2>
            </div>
        </div>
        ${appealFacts(appeal, signals)}`;
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
        </table>` : emptyState("Событий пока нет", "Здесь появятся события, которые подсистема публикует в RabbitMQ");
}
