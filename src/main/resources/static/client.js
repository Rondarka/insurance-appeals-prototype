// Кабинет клиента: новое обращение в три шага и переписка по своим обращениям.

// клиенту статусы называются по-своему: «нужен ваш ответ», а не «ждём клиента»
const CLIENT_STATUS = {
    PENDING_ROUTING: "Регистрируется",
    ROUTED: "Принято",
    IN_PROGRESS: "В работе",
    WAITING_CUSTOMER: "Нужен ваш ответ",
    RESOLVED: "Решено",
    CLOSED: "Закрыто"
};
const INPUT_TYPES = {TEXT: "text", DATE: "date", NUMBER: "number"};

const draft = {contractId: null, category: null, type: null, schema: {categories: []}};
let myAppeals = [];
let openedId = null;

const $ = selector => document.querySelector(selector);

startPage("/client.html", async () => {
    renderContracts();
    await selectContract(session.me.contracts[0]?.id);
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
        </button>`).join("") || `<p class="muted">Договоров не найдено</p>`;
}

/** Схема формы зависит от продукта договора: по КАСКО не спрашивают про ипотеку. */
async function selectContract(id) {
    const contract = session.me.contracts.find(item => item.id === id);
    if (!contract) return;
    draft.contractId = id;
    document.querySelectorAll("[data-contract]").forEach(item =>
        item.classList.toggle("selected", item.dataset.contract === id));
    draft.schema = await api(`/api/form-schema?productCode=${encodeURIComponent(contract.productCode)}`);
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
    // поля договора сервер подставит сам — клиент их не вводит
    const fields = (schemaType(draft.schema, draft.category, code)?.fields || []).filter(field => !field.contractAttribute);
    $("#fields").innerHTML = fields.map(field => `
        <label>${escapeHtml(field.label)}
            <input name="detail.${escapeHtml(field.code)}" type="${INPUT_TYPES[field.inputType] || "text"}"
                   placeholder="${escapeHtml(field.hint || "")}" ${field.required ? "required" : ""}>
        </label>`).join("");
}

$("#new-appeal").addEventListener("click", event => {
    const contract = event.target.closest("[data-contract]");
    if (contract) selectContract(contract.dataset.contract).catch(error => showToast(error.message, true));
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
});

// ---------- мои обращения ----------

async function loadMyAppeals() {
    myAppeals = await api("/api/appeals");
    renderMyAppeals();
}

function renderMyAppeals() {
    $("#my-appeals").innerHTML = myAppeals.length ? myAppeals.map(appeal => `
        <button type="button" class="side-item ${appeal.id === openedId ? "selected" : ""} ${appeal.status === "WAITING_CUSTOMER" ? "attention" : ""}"
                data-appeal="${appeal.id}">
            <strong>${escapeHtml(appeal.subject)}</strong>
            <span class="side-meta">${statusChip(appeal.status, CLIENT_STATUS)}<small>${formatDateTime(appeal.updatedAt)}</small></span>
        </button>`).join("") : `<p class="muted">Обращений пока нет</p>`;
}

$("#my-appeals").addEventListener("click", event => {
    const item = event.target.closest("[data-appeal]");
    if (item) openAppeal(item.dataset.appeal).catch(error => showToast(error.message, true));
});

async function openAppeal(id) {
    const appeal = await api(`/api/appeals/${id}`);
    openedId = id;
    $("#new-appeal").hidden = true;
    const view = $("#appeal-view");
    view.hidden = false;
    const answerBy = appeal.deadlineAt && !["RESOLVED", "CLOSED"].includes(appeal.status)
        ? ` · ответим до ${formatDateTime(appeal.deadlineAt)}` : "";
    view.innerHTML = `
        <div class="card-head">
            <div class="card-title">
                <small>${escapeHtml(appeal.publicNumber)} · ${escapeHtml(categoryLabel(appeal.category))} · ${escapeHtml(typeLabel(appeal.category, appeal.subcategory))}</small>
                <h2>${escapeHtml(appeal.subject)}</h2>
                <p class="muted">${appeal.department ? `Рассматривает отдел «${escapeHtml(appeal.department.name)}»` : "Обращение регистрируется"}${answerBy}</p>
            </div>
            <div class="card-badges">${statusChip(appeal.status, CLIENT_STATUS)}</div>
        </div>
        ${appeal.status === "WAITING_CUSTOMER" ? `<div class="notice">Сотрудник ждёт вашего ответа — напишите ниже.</div>` : ""}
        <div class="card-body">
            <div class="main-col">
                ${renderThread(appeal, "CLIENT")}
                ${appeal.status === "CLOSED" ? `<p class="muted">Обращение закрыто. Если вопрос остался, создайте новое.</p>` : `
                <form class="reply" id="client-reply">
                    <textarea name="body" rows="3" maxlength="5000" required placeholder="Ваше сообщение"></textarea>
                    <div class="reply-actions"><button class="btn primary" type="submit">Отправить</button></div>
                </form>`}
            </div>
            <aside class="side-col">
                <h4>Договор</h4>${renderContract(appeal.contract)}
                <h4>Данные обращения</h4>${renderDetails(appeal)}
                <h4>Файлы</h4>${renderAttachments(appeal)}
            </aside>
        </div>`;
    renderMyAppeals();
}

$("#appeal-view").addEventListener("submit", async event => {
    event.preventDefault();
    const body = new FormData(event.target).get("body");
    try {
        await api(`/api/appeals/${openedId}/messages`, {method: "POST", body: JSON.stringify({body})});
        await openAppeal(openedId);
        await loadMyAppeals();
    } catch (error) {
        showToast(error.message, true);
    }
});

/** Обновление в фоне — не трогая начатое сообщение. */
async function refresh() {
    await loadMyAppeals().catch(() => {});
    const typing = $("#client-reply textarea")?.value;
    if (openedId && !typing) await openAppeal(openedId).catch(() => {});
}
