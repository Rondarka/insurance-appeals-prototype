// Кабинет клиента: новое обращение в три шага и переписка по своим обращениям.

// клиенту статусы называются по-своему: «нужен ваш ответ», а не «ждём клиента»
const CLIENT_STATUS = {
    PENDING_ROUTING: "Регистрируется",
    // отдел выбирает руководитель: для клиента обращение всё ещё регистрируется
    NEEDS_CLASSIFICATION: "Регистрируется",
    ROUTED: "Принято",
    IN_PROGRESS: "В работе",
    WAITING_CUSTOMER: "Нужен ваш ответ",
    RESOLVED: "Решено",
    CLOSED: "Закрыто"
};
const INPUT_TYPES = {TEXT: "text", DATE: "date", NUMBER: "number"};

const draft = {contractId: null, productCode: null, category: null, type: null, schema: {categories: []}};
let myAppeals = [];
let openedId = null;
// на телефоне список обращений короткий, иначе до формы не долистать
let showAllAppeals = false;
const PHONE_LIST = 3;
const isPhone = () => window.matchMedia("(max-width: 900px)").matches;

/** На телефоне колонки идут одна под другой: после выбора показываем то, что выбрали. */
function revealOnPhone(element) {
    if (isPhone()) element.scrollIntoView({behavior: "smooth", block: "start"});
}

const $ = selector => document.querySelector(selector);

startPage("/client.html", async () => {
    if (session.me.contractsUnavailable) {
        await renderProducts();
    } else {
        renderContracts();
        await selectContract(session.me.contracts[0]?.id);
    }
    await loadMyAppeals();
    window.setInterval(refresh, 10000);
});

// ---------- новое обращение ----------

function renderContracts() {
    $("#contracts").innerHTML = session.me.contracts.map(contract => `
        <button type="button" class="choice" data-contract="${contract.id}">
            <strong>${escapeHtml(contract.insuranceType)}</strong>
            <small>${escapeHtml(contract.policyNumber)}</small>
            <small>${escapeHtml(contract.insuredObject)}</small>
            <span class="chip contract-${contract.status}">${escapeHtml(CONTRACT_STATUS_LABELS[contract.status] || contract.status)}</span>
            ${contract.clientRole && contract.clientRole !== "POLICYHOLDER"
                ? `<span class="chip contract-role">Вы ${escapeHtml(roleLabel(contract.clientRole))}</span>` : ""}
        </button>`).join("") || `<p class="muted">Договоров не найдено</p>`;
}

/**
 * Учётная система договоров не ответила, но клиенту не отказываем: он выбирает вид страхования,
 * а договор подсистема подберёт сама, когда система заработает (решение 29).
 */
async function renderProducts() {
    const products = await api("/api/products");
    $("#contracts").insertAdjacentHTML("beforebegin", `
        <p class="notice small">Список договоров сейчас не загрузился. Выберите вид страхования, а договор мы подберём
            сами и покажем в обращении.</p>`);
    $("#contracts").innerHTML = products.map(product => `
        <button type="button" class="choice" data-product="${product.code}"><strong>${escapeHtml(product.label)}</strong></button>`).join("");
    $("#contracts").insertAdjacentHTML("afterend", `
        <label class="policy-field">Номер договора, если под рукой
            <input name="policyNumber" maxlength="80" placeholder="Например, КАСКО-2026-004821">
        </label>`);
    await selectProduct(products[0]?.code);
}

async function selectProduct(code) {
    if (!code) return;
    draft.contractId = null;
    draft.productCode = code;
    document.querySelectorAll("[data-product]").forEach(item =>
        item.classList.toggle("selected", item.dataset.product === code));
    draft.schema = await api(`/api/form-schema?productCode=${encodeURIComponent(code)}`);
    selectCategory(draft.schema.categories[0]?.code);
}

/** Схема формы зависит от продукта договора: по КАСКО не спрашивают про ипотеку. */
async function selectContract(id) {
    const contract = session.me.contracts.find(item => item.id === id);
    if (!contract) return;
    draft.contractId = id;
    // вид: на случай, если учётная система откажет в момент отправки
    draft.productCode = contract.productCode;
    document.querySelectorAll("[data-contract]").forEach(item =>
        item.classList.toggle("selected", item.dataset.contract === id));
    draft.schema = await api(formSchemaUrl(contract));
    $("#role-note").innerHTML = roleNote(contract, "вы");
    selectCategory(draft.schema.categories[0]?.code);
}

function selectCategory(code) {
    draft.category = code;
    $("#categories").innerHTML = draft.schema.categories.map(item => `
        <button type="button" class="chip-button ${item.code === code ? "selected" : ""}" data-category="${item.code}">
            ${escapeHtml(item.label)}</button>`).join("");
    const types = schemaCategory(draft.schema, code)?.types || [];
    $("#types").innerHTML = types.map(item => `
        <button type="button" class="choice" data-type="${item.code}"><strong>${escapeHtml(item.label)}</strong></button>`).join("");
    selectType(types[0]?.code);
}

function selectType(code) {
    draft.type = code;
    document.querySelectorAll("[data-type]").forEach(item => item.classList.toggle("selected", item.dataset.type === code));
    // поля договора сервер подставит сам: клиент их не вводит
    const fields = (schemaType(draft.schema, draft.category, code)?.fields || []).filter(field => !field.contractAttribute);
    $("#fields").innerHTML = fields.map(field => `
        <label>${escapeHtml(field.label)}
            <input name="detail.${escapeHtml(field.code)}" type="${INPUT_TYPES[field.inputType] || "text"}"
                   placeholder="${escapeHtml(field.hint || "")}" ${field.required ? "required" : ""}>
        </label>`).join("");
    $("#documents-hint").innerHTML = documentsHint(schemaType(draft.schema, draft.category, code), "Что приложить",
        "Если чего-то нет, отправляйте как есть: сотрудник попросит недостающее.");
}

$("#new-appeal").addEventListener("click", event => {
    const contract = event.target.closest("[data-contract]");
    if (contract) selectContract(contract.dataset.contract).catch(error => showToast(error.message, true));
    const product = event.target.closest("[data-product]");
    if (product) selectProduct(product.dataset.product).catch(error => showToast(error.message, true));
    const category = event.target.closest("[data-category]");
    if (category) selectCategory(category.dataset.category);
    const type = event.target.closest("[data-type]");
    if (type) selectType(type.dataset.type);
});

$("#new-appeal").addEventListener("submit", async event => {
    event.preventDefault();
    const formElement = event.currentTarget;
    if (!formElement.reportValidity()) return;
    const form = new FormData(formElement);
    const details = {};
    formElement.querySelectorAll('[name^="detail."]').forEach(input => {
        details[input.name.substring("detail.".length)] = input.value;
    });
    try {
        const created = await api("/api/appeals", {
            method: "POST",
            body: JSON.stringify({
                contractId: draft.contractId,
                productCode: draft.productCode,
                policyNumber: form.get("policyNumber") || null,
                category: draft.category,
                subcategory: draft.type,
                details,
                subject: form.get("subject"),
                description: form.get("description")
            })
        });
        const files = [...$("#files").files];
        if (files.length) {
            const upload = new FormData();
            files.forEach(file => upload.append("files", file));
            await api(`/api/appeals/${created.id}/attachments`, {method: "POST", body: upload})
                .catch(error => showToast(`Обращение отправлено, но файлы не загрузились: ${error.message}`, true));
        }
        formElement.reset();
        selectType(draft.type);
        showToast(`Обращение ${created.publicNumber} отправлено`);
        await loadMyAppeals();
        await openAppeal(created.id);
        // маршрутизация асинхронная: через пару секунд отдел и срок уже известны
        window.setTimeout(() => refresh(), 2000);
    } catch (error) {
        showToast(error.message, true);
    }
});

$("#new-appeal-button").addEventListener("click", () => {
    openedId = null;
    $("#appeal-view").hidden = true;
    $("#new-appeal").hidden = false;
    renderMyAppeals();
    revealOnPhone($("#new-appeal"));
});

// ---------- мои обращения ----------

async function loadMyAppeals() {
    myAppeals = await api("/api/appeals");
    renderMyAppeals();
}

function renderMyAppeals() {
    const shortList = isPhone() && !showAllAppeals && myAppeals.length > PHONE_LIST;
    const shown = shortList ? myAppeals.slice(0, PHONE_LIST) : myAppeals;
    $("#my-appeals").innerHTML = (myAppeals.length ? shown.map(appeal => `
        <button type="button" class="side-item ${appeal.id === openedId ? "selected" : ""} ${appeal.status === "WAITING_CUSTOMER" ? "attention" : ""}"
                data-appeal="${appeal.id}">
            <strong>${escapeHtml(appeal.subject)}</strong>
            <span class="side-meta">${["NEEDS_CHOICE", "NOT_FOUND"].includes(appeal.contractLink)
                ? `<span class="chip status-WAITING_CUSTOMER">уточните договор</span>`
                : statusChip(appeal.status, CLIENT_STATUS)}<small>${formatDateTime(appeal.updatedAt)}</small></span>
        </button>`).join("") : emptyState("Обращений пока нет", "Подать обращение можно кнопкой «Новое обращение»"))
        + (shortList ? `<button type="button" class="btn ghost wide" data-all-appeals>Все обращения · ${myAppeals.length}</button>` : "");
}

$("#my-appeals").addEventListener("click", event => {
    if (event.target.closest("[data-all-appeals]")) {
        showAllAppeals = true;
        renderMyAppeals();
        return;
    }
    const item = event.target.closest("[data-appeal]");
    if (item) {
        openAppeal(item.dataset.appeal)
            .then(() => revealOnPhone($("#appeal-view")))
            .catch(error => showToast(error.message, true));
    }
});

async function openAppeal(id) {
    const appeal = await api(`/api/appeals/${id}`);
    openedId = id;
    $("#new-appeal").hidden = true;
    const view = $("#appeal-view");
    view.hidden = false;
    // клиенту обещаем срок по закону, а не внутренний срок отдела
    const answerBy = appeal.legalDeadline && !["RESOLVED", "CLOSED"].includes(appeal.status)
        ? ` · ответим до ${formatDate(appeal.legalDeadline)}` : "";
    view.innerHTML = `
        <div class="card-head">
            <div class="card-title">
                <small>${escapeHtml(appeal.publicNumber)} · ${escapeHtml(categoryLabel(appeal.category))} · ${escapeHtml(typeLabel(appeal.category, appeal.subcategory))}</small>
                <h2>${escapeHtml(appeal.subject)}</h2>
                <p class="muted">${appeal.department ? `Рассматривает отдел «${escapeHtml(appeal.department.name)}»` : "Обращение регистрируется"}${answerBy}</p>
                ${appeal.outcome ? `<p class="notice small">Итог: ${escapeHtml(OUTCOME_LABELS[appeal.outcome])}</p>` : ""}
            </div>
            <div class="card-badges">${statusChip(appeal.status, CLIENT_STATUS)}</div>
        </div>
        ${statusTimeline(appeal)}
        ${pretensionNotice(appeal)}
        ${["NEEDS_CHOICE", "NOT_FOUND"].includes(appeal.contractLink) ? `
        <div class="notice">${appeal.contractLink === "NEEDS_CHOICE"
            ? "Уточните, по какому договору обращение: подходит несколько."
            : "Мы не нашли договор указанного вида. Выберите договор, к которому относится обращение."}</div>
        <div id="client-candidates" class="choose-contract"></div>` : ""}
        ${appeal.status === "WAITING_CUSTOMER" ? `<div class="notice">Сотрудник ждёт вашего ответа. Напишите ниже и приложите документы, если их запросили.</div>` : ""}
        <div class="card-body">
            <div class="main-col">
                ${renderThread(appeal, "CLIENT")}
                ${appeal.status === "CLOSED" ? `<p class="muted">Обращение закрыто. Если вопрос остался, создайте новое.</p>` : `
                <form class="reply" id="client-reply">
                    <textarea name="body" rows="3" maxlength="5000" placeholder="Ваше сообщение"></textarea>
                    <div class="reply-actions">
                        ${attachField()}
                        <button class="btn primary" type="submit">Отправить</button>
                    </div>
                </form>`}
            </div>
            <aside class="side-col">
                <h4>Срок ответа</h4>${renderNorm(appeal)}
                <h4>Договор</h4>${appeal.contract ? renderContract(appeal.contract, "Вы в договоре") : `
                    <p class="muted">${appeal.contractLink === "PENDING"
                        ? "Подбираем договор, он появится здесь автоматически"
                        : "Нужно уточнить договор: выберите его выше"}</p>${requestedContractText(appeal)}`}
                <h4>Данные обращения</h4>${renderDetails(appeal)}
                <h4>Файлы</h4>${renderAttachments(appeal)}
            </aside>
        </div>`;
    if (["NEEDS_CHOICE", "NOT_FOUND"].includes(appeal.contractLink)) {
        loadCandidates(id, $("#client-candidates")).catch(error => showToast(error.message, true));
    }
    renderMyAppeals();
}

/**
 * Лента статусов: что с обращением уже было и чего ждать, с датами. Даты берутся из журнала
 * обработки: каждая запись помнит статус после действия.
 */
function statusTimeline(appeal) {
    // журнал хранит момент времени, срок по закону хранится датой без времени
    const day = value => new Intl.DateTimeFormat("ru-RU", {day: "numeric", month: "long"})
        .format(new Date(/^\d{4}-\d{2}-\d{2}$/.test(value) ? `${value}T00:00:00` : value));
    const first = status => appeal.history.find(item => item.statusAfter === status)?.createdAt;
    const last = status => [...appeal.history].reverse().find(item => item.statusAfter === status)?.createdAt;
    const answered = ["RESOLVED", "CLOSED"].includes(appeal.status);
    const steps = [
        {label: "Зарегистрировано", at: appeal.createdAt},
        {label: "Принято отделом", at: first("ROUTED") || first("IN_PROGRESS")},
        {label: "В работе", at: first("IN_PROGRESS")}
    ];
    if (appeal.status === "WAITING_CUSTOMER") {
        steps.push({label: "Нужен ваш ответ", at: last("WAITING_CUSTOMER"), attention: true});
    }
    steps.push({label: "Ответ дан", at: answered ? appeal.resolvedAt || last("RESOLVED") : null,
        note: !answered && appeal.legalDeadline ? `не позднее ${day(appeal.legalDeadline)}` : ""});
    // текущий шаг: последний пройденный, а если ждём клиента, то этот шаг
    const current = steps.findIndex(step => step.attention) >= 0
        ? steps.findIndex(step => step.attention)
        : steps.reduce((index, step, i) => step.at ? i : index, 0);
    return `<ol class="status-line" aria-label="Ход рассмотрения">${steps.map((step, i) => {
        const state = step.attention ? "attention" : i < current || (i === current && answered) ? "done" : i === current ? "current" : "upcoming";
        return `<li class="${state}"${i === current ? ' aria-current="step"' : ""}>
            <span class="status-dot" aria-hidden="true"></span>
            <span class="status-step">${escapeHtml(step.label)}</span>
            <small>${step.at && state !== "upcoming" ? day(step.at) : escapeHtml(step.note || "")}</small>
        </li>`;
    }).join("")}</ol>`;
}

/**
 * У претензии клиенту объясняется его право: без ответа в срок или при несогласии он может
 * обратиться к финансовому уполномоченному (Федеральный закон от 04.06.2018 № 123-ФЗ, ст. 16).
 */
function pretensionNotice(appeal) {
    const category = appeal.finalCategory || appeal.category;
    const type = appeal.finalSubcategory || appeal.subcategory;
    if (category !== "COMPLAINT" || type !== "PRETENSION") return "";
    const answered = ["RESOLVED", "CLOSED"].includes(appeal.status);
    return `<div class="notice pretension-note">
        ${answered
            ? "Если вы не согласны с ответом на претензию, вы вправе обратиться к финансовому уполномоченному в сфере финансовых услуг."
            : "Если мы не ответим в срок или вы не согласитесь с ответом, вы вправе обратиться к финансовому уполномоченному в сфере финансовых услуг."}
        <small>Федеральный закон от 04.06.2018 № 123-ФЗ, статья 16. Обращение подаётся на сайте финансового
            уполномоченного: finombudsman.ru</small>
    </div>`;
}

async function afterContractLinked(id) {
    await loadMyAppeals();
    if (id === openedId) await openAppeal(id);
}

$("#appeal-view").addEventListener("submit", async event => {
    event.preventDefault();
    const data = replyFormData(event.target);
    if (!data) {
        showToast("Напишите сообщение или приложите файл", true);
        return;
    }
    event.submitter.disabled = true;
    try {
        await api(`/api/appeals/${openedId}/messages`, {method: "POST", body: data});
        await openAppeal(openedId);
        await loadMyAppeals();
    } catch (error) {
        event.submitter.disabled = false;
        showToast(error.message, true);
    }
});

/** Обновление в фоне: не трогая начатое сообщение. */
async function refresh() {
    await loadMyAppeals().catch(() => {});
    if (openedId && !hasDraft($("#client-reply"))) await openAppeal(openedId).catch(() => {});
}
