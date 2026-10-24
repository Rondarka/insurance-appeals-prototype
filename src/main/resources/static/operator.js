// Контакт-центр (решение 38): оператор находит клиента и записывает обращение с его слов.
// Дальше обращение живёт как поданное из кабинета: та же схема формы, срок, маршрутизация.

const INPUT_TYPES = {TEXT: "text", DATE: "date", NUMBER: "number"};
const op = {client: null, channel: "PHONE", contractId: null, productCode: null, category: null, type: null,
    schema: {categories: []}};

const $ = selector => document.querySelector(selector);

startPage("/operator.html", async () => {
});

// ---------- клиент ----------

$("#client-search").addEventListener("submit", async event => {
    event.preventDefault();
    const policyNumber = new FormData(event.target).get("policyNumber").trim();
    if (!policyNumber) return;
    try {
        op.client = await api(`/api/operator/clients?policyNumber=${encodeURIComponent(policyNumber)}`);
        renderClientAppeals();
        renderContracts();
        $("#operator-empty").hidden = true;
        $("#operator-done").hidden = true;
        $("#operator-appeal").hidden = false;
        // договор, по которому нашли клиента, скорее всего тот, о котором он звонит
        const found = op.client.contracts.find(item => item.policyNumber === policyNumber) || op.client.contracts[0];
        await selectContract(found?.id);
    } catch (error) {
        showToast(error.message, true);
    }
});

function renderClientAppeals() {
    $("#client-appeals").innerHTML = op.client.appeals.length ? op.client.appeals.map(appeal => `
        <div class="side-item">
            <strong>${escapeHtml(appeal.subject)}</strong>
            <span class="side-meta">${statusChip(appeal.status)}<small>${escapeHtml(appeal.publicNumber)}</small></span>
            ${appeal.legalDeadline && !["RESOLVED", "CLOSED"].includes(appeal.status)
                ? `<small class="muted">ответ по закону до ${formatDate(appeal.legalDeadline)}</small>` : ""}
        </div>`).join("") : emptyState("Обращений у клиента нет");
}

function renderContracts() {
    $("#contracts").innerHTML = op.client.contracts.map(contract => `
        <button type="button" class="choice" data-contract="${contract.id}">
            <strong>${escapeHtml(contract.insuranceType)}</strong>
            <small>${escapeHtml(contract.policyNumber)}</small>
            <small>${escapeHtml(contract.insuredObject)}</small>
            <span class="chip contract-${contract.status}">${escapeHtml(CONTRACT_STATUS_LABELS[contract.status] || contract.status)}</span>
        </button>`).join("") || `<p class="muted">Договоров не найдено</p>`;
}

// ---------- форма: та же схема, что в кабинете клиента ----------

async function selectContract(id) {
    const contract = op.client.contracts.find(item => item.id === id);
    if (!contract) return;
    op.contractId = id;
    op.productCode = contract.productCode;
    document.querySelectorAll("[data-contract]").forEach(item =>
        item.classList.toggle("selected", item.dataset.contract === id));
    op.schema = await api(`/api/form-schema?productCode=${encodeURIComponent(contract.productCode)}`);
    selectCategory(op.schema.categories[0]?.code);
}

function selectCategory(code) {
    op.category = code;
    $("#categories").innerHTML = op.schema.categories.map(item => `
        <button type="button" class="chip-button ${item.code === code ? "selected" : ""}" data-category="${item.code}">
            ${escapeHtml(item.label)}</button>`).join("");
    const types = schemaCategory(op.schema, code)?.types || [];
    $("#types").innerHTML = types.map(item => `
        <button type="button" class="choice" data-type="${item.code}"><strong>${escapeHtml(item.label)}</strong></button>`).join("");
    selectType(types[0]?.code);
}

function selectType(code) {
    op.type = code;
    document.querySelectorAll("[data-type]").forEach(item => item.classList.toggle("selected", item.dataset.type === code));
    // поля договора сервер подставит сам
    const fields = (schemaType(op.schema, op.category, code)?.fields || []).filter(field => !field.contractAttribute);
    $("#fields").innerHTML = fields.map(field => `
        <label>${escapeHtml(field.label)}
            <input name="detail.${escapeHtml(field.code)}" type="${INPUT_TYPES[field.inputType] || "text"}"
                   placeholder="${escapeHtml(field.hint || "")}" ${field.required ? "required" : ""}>
        </label>`).join("");
}

$("#operator-appeal").addEventListener("click", event => {
    const channel = event.target.closest("[data-channel]");
    if (channel) {
        op.channel = channel.dataset.channel;
        document.querySelectorAll("[data-channel]").forEach(item => item.classList.toggle("selected", item === channel));
    }
    const contract = event.target.closest("[data-contract]");
    if (contract) selectContract(contract.dataset.contract).catch(error => showToast(error.message, true));
    const category = event.target.closest("[data-category]");
    if (category) selectCategory(category.dataset.category);
    const type = event.target.closest("[data-type]");
    if (type) selectType(type.dataset.type);
});

$("#operator-appeal").addEventListener("submit", async event => {
    event.preventDefault();
    const formElement = event.currentTarget;
    if (!formElement.reportValidity()) return;
    const form = new FormData(formElement);
    const details = {};
    formElement.querySelectorAll('[name^="detail."]').forEach(input => {
        details[input.name.substring("detail.".length)] = input.value;
    });
    try {
        const created = await api("/api/operator/appeals", {
            method: "POST",
            body: JSON.stringify({
                counterpartyId: op.client.counterpartyId,
                channel: op.channel,
                customerName: form.get("customerName"),
                appeal: {
                    contractId: op.contractId,
                    productCode: op.productCode,
                    policyNumber: null,
                    category: op.category,
                    subcategory: op.type,
                    details,
                    subject: form.get("subject"),
                    description: form.get("description")
                }
            })
        });
        formElement.reset();
        formElement.hidden = true;
        // номер и срок оператор сразу называет клиенту; письмо или SMS отправит сервис коммуникаций
        $("#operator-done").hidden = false;
        $("#operator-done").innerHTML = `
            <h2>Обращение ${escapeHtml(created.publicNumber)} зарегистрировано</h2>
            <p>Срок ответа по закону: до <strong>${formatDate(created.legalDeadline)}</strong>.</p>
            <p class="muted">Продиктуйте клиенту номер обращения. Подтверждение регистрации он получит через сервис
                коммуникаций, ответ появится в личном кабинете.</p>
            <div class="form-actions">
                <button class="btn primary" type="button" data-again>Принять ещё обращение этого клиента</button>
            </div>`;
        op.client.appeals.unshift(created);
        renderClientAppeals();
    } catch (error) {
        showToast(error.message, true);
    }
});

$("#operator-done").addEventListener("click", event => {
    if (!event.target.closest("[data-again]")) return;
    $("#operator-done").hidden = true;
    $("#operator-appeal").hidden = false;
    selectType(op.type);
});
