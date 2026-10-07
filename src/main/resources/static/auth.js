// Вход через поставщика идентификации страховой (решение 22): код авторизации + PKCE.
// Пароль вводится на странице IdP, интерфейс его не видит. Токен живёт только в памяти
// страницы: при перезагрузке вход повторяется, и IdP, помня сессию, возвращает сразу.
const auth = (() => {
    let config = null;
    let tokens = null;
    let refreshTimer = null;

    const base64Url = buffer => btoa(String.fromCharCode(...new Uint8Array(buffer)))
        .replace(/\+/g, "-").replace(/\//g, "_").replace(/=+$/, "");
    const randomString = () => base64Url(crypto.getRandomValues(new Uint8Array(32)));
    const redirectUri = () => `${window.location.origin}/`;
    const endpoint = name => `${config.issuer}/protocol/openid-connect/${name}`;

    async function login() {
        const verifier = randomString();
        const state = randomString();
        sessionStorage.setItem("pkce", JSON.stringify({verifier, state}));
        const challenge = base64Url(await crypto.subtle.digest("SHA-256", new TextEncoder().encode(verifier)));
        const params = new URLSearchParams({
            client_id: config.clientId,
            response_type: "code",
            scope: "openid",
            redirect_uri: redirectUri(),
            state,
            code_challenge: challenge,
            code_challenge_method: "S256"
        });
        window.location.assign(`${endpoint("auth")}?${params}`);
        return new Promise(() => {});
    }

    async function requestTokens(params) {
        const response = await fetch(endpoint("token"), {
            method: "POST",
            headers: {"Content-Type": "application/x-www-form-urlencoded"},
            body: new URLSearchParams({client_id: config.clientId, ...params})
        });
        if (!response.ok) {
            throw new Error("Не удалось войти через поставщика идентификации");
        }
        const data = await response.json();
        tokens = {
            access: data.access_token,
            refresh: data.refresh_token,
            idToken: data.id_token || tokens?.idToken
        };
        // обновить токен за 30 секунд до истечения; не вышло — войти заново
        window.clearTimeout(refreshTimer);
        refreshTimer = window.setTimeout(
            () => refresh().catch(login),
            Math.max(5000, data.expires_in * 1000 - 30000)
        );
    }

    function refresh() {
        return requestTokens({grant_type: "refresh_token", refresh_token: tokens.refresh});
    }

    async function init() {
        config = await (await fetch("/auth-config")).json();
        const query = new URLSearchParams(window.location.search);
        if (query.has("error")) {
            window.history.replaceState(null, "", "/");
            throw new Error(query.get("error_description") || "Вход отклонён");
        }
        const saved = JSON.parse(sessionStorage.getItem("pkce") || "null");
        if (query.has("code") && saved && query.get("state") === saved.state) {
            sessionStorage.removeItem("pkce");
            window.history.replaceState(null, "", "/");
            await requestTokens({
                grant_type: "authorization_code",
                code: query.get("code"),
                redirect_uri: redirectUri(),
                code_verifier: saved.verifier
            });
            return;
        }
        await login();
    }

    function logout() {
        const params = new URLSearchParams({client_id: config.clientId, post_logout_redirect_uri: redirectUri()});
        if (tokens?.idToken) params.set("id_token_hint", tokens.idToken);
        tokens = null;
        window.location.assign(`${endpoint("logout")}?${params}`);
    }

    return {init, login, logout, token: () => tokens?.access};
})();
