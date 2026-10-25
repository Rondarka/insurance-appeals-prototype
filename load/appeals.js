// Нагрузочный тест подсистемы (решение 47, docs/experiments.md).
//
//   docker compose --profile monitoring up -d
//   docker compose run --rm k6 run /scripts/appeals.js
//
// Клиенты открывают кабинет, подают страховой случай и ждут, пока обращение дойдёт до
// отдела. Сотрудники урегулирования убытков открывают очередь и карточки. Вход через клиент
// Keycloak appeals-experiments (только стенд). Итог пишется в load/results/.
import http from "k6/http";
import { check, sleep } from "k6";
import { Trend, Counter } from "k6/metrics";

const APP = __ENV.APP_URL || "http://app:8080";
const KEYCLOAK = __ENV.KEYCLOAK_URL || "http://keycloak:8080";
// пароль демонстрационных пользователей стенда, см. README
const PASSWORD = __ENV.DEMO_PASSWORD || "demo";
const CLIENT_VUS = Number(__ENV.CLIENT_VUS || 20);
const HOLD = __ENV.HOLD || "3m";

// КАСКО Петра в заглушке учётной системы: обращения Анны в демонстрации не засоряются
const CONTRACT = "29999999-9999-9999-9999-999999999999";

const routingLag = new Trend("routing_lag", true);
const notRouted = new Counter("appeals_not_routed");

export const options = {
    scenarios: {
        clients: {
            executor: "ramping-vus", exec: "client", startVUs: 0,
            stages: [{ duration: "1m", target: CLIENT_VUS }, { duration: HOLD, target: CLIENT_VUS }, { duration: "30s", target: 0 }],
        },
        employees: { executor: "constant-vus", exec: "employee", vus: 5, duration: "4m30s" },
    },
    thresholds: {
        http_req_failed: ["rate<0.01"],
        "http_req_duration{kind:read}": ["p(95)<500"],
        "http_req_duration{kind:write}": ["p(95)<800"],
        routing_lag: ["p(95)<5000"],
    },
};

// токен живёт 5 минут: каждый виртуальный пользователь берёт свой и обновляет раз в 4 минуты
const tokens = {};

function token(user) {
    const cached = tokens[user];
    if (cached && Date.now() - cached.at < 240000) return cached.value;
    const response = http.post(`${KEYCLOAK}/realms/insurer/protocol/openid-connect/token`, {
        client_id: "appeals-experiments", grant_type: "password", username: user, password: PASSWORD, scope: "openid",
    }, { tags: { kind: "login" } });
    check(response, { "вход выполнен": r => r.status === 200 });
    tokens[user] = { value: response.json("access_token"), at: Date.now() };
    return tokens[user].value;
}

function headers(user, kind) {
    return { headers: { Authorization: `Bearer ${token(user)}`, "Content-Type": "application/json" }, tags: { kind } };
}

export function client() {
    const me = http.get(`${APP}/api/me`, headers("petr", "read"));
    check(me, { "кабинет открылся": r => r.status === 200 });

    const created = http.post(`${APP}/api/appeals`, JSON.stringify({
        contractId: CONTRACT, category: "CLAIM", subcategory: "AUTO",
        details: { incidentDate: "2026-10-20", incidentPlace: "г. Москва, ул. Тверская, д. 1" },
        subject: "Нагрузочный тест: повреждение автомобиля",
        description: "Обращение создано нагрузочным тестом load/appeals.js.",
    }), headers("petr", "write"));
    if (!check(created, { "обращение принято": r => r.status === 201 })) return;

    // клиент ждёт, пока обращение назначат отделу: так видна задержка асинхронной маршрутизации
    const id = created.json("id");
    const started = Date.now();
    for (let attempt = 0; attempt < 20; attempt++) {
        sleep(0.25);
        const appeal = http.get(`${APP}/api/appeals/${id}`, headers("petr", "read"));
        if (appeal.status === 200 && appeal.json("status") !== "PENDING_ROUTING") {
            routingLag.add(Date.now() - started);
            sleep(1 + Math.random() * 2);
            return;
        }
    }
    notRouted.add(1);
}

export function employee() {
    const queue = http.get(`${APP}/api/appeals`, headers("lebedev", "read"));
    if (!check(queue, { "очередь открылась": r => r.status === 200 })) return;
    const items = queue.json();
    if (items.length) {
        const pick = items[Math.floor(Math.random() * Math.min(items.length, 20))];
        const card = http.get(`${APP}/api/appeals/${pick.id}`, headers("lebedev", "read"));
        check(card, { "карточка открылась": r => r.status === 200 });
    }
    sleep(2 + Math.random() * 3);
}

export function handleSummary(data) {
    const metric = (name, stat) => data.metrics[name] ? data.metrics[name].values[stat] : null;
    const ms = value => value == null ? "нет" : `${Math.round(value)} мс`;
    const stamp = new Date().toISOString().replace(/[:T]/g, "-").slice(0, 16);
    const text = [
        "Итог нагрузочного теста",
        `  запросов: ${metric("http_reqs", "count")}, в секунду: ${(metric("http_reqs", "rate") || 0).toFixed(1)}`,
        `  ошибок: ${((metric("http_req_failed", "rate") || 0) * 100).toFixed(2)} %`,
        `  чтение p95: ${ms(metric("http_req_duration{kind:read}", "p(95)"))}, запись p95: ${ms(metric("http_req_duration{kind:write}", "p(95)"))}`,
        `  маршрутизация p95: ${ms(metric("routing_lag", "p(95)"))}, не дошли до отдела: ${metric("appeals_not_routed", "count") || 0}`,
        "",
    ].join("\n");
    return { stdout: text, [`/scripts/results/summary-${stamp}.json`]: JSON.stringify(data, null, 2) };
}
