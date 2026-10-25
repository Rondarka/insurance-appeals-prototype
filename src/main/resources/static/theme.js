// Тема оформления (решение 49). Подключается в <head> до стилей, чтобы страница сразу
// открылась в выбранной теме, без вспышки светлого фона. Выбор хранится в браузере:
// это настройка рабочего места, а не данные подсистемы.
(function () {
    const KEY = "appeals.theme";
    const THEMES = [
        ["system", "Как в системе", "linear-gradient(135deg, #ffffff 50%, #161c27 50%)"],
        ["light", "Светлая", "#eef2f7"],
        ["dark", "Тёмная", "#161c27"],
        ["contrast", "Контрастная", "linear-gradient(135deg, #ffffff 50%, #000000 50%)"],
        ["warm", "Тёплая", "#f2ebdf"]
    ];
    const darkSystem = window.matchMedia("(prefers-color-scheme: dark)");

    function stored() {
        try {
            const value = localStorage.getItem(KEY);
            return THEMES.some(([code]) => code === value) ? value : "system";
        } catch (error) {
            // хранилище недоступно (приватное окно): тема просто не запоминается
            return "system";
        }
    }

    function apply(choice) {
        document.documentElement.dataset.theme = choice === "system" ? (darkSystem.matches ? "dark" : "light") : choice;
    }

    apply(stored());
    darkSystem.addEventListener("change", () => {
        if (stored() === "system") apply("system");
    });

    window.appTheme = {
        THEMES,
        current: stored,
        set(choice) {
            try {
                localStorage.setItem(KEY, choice);
            } catch (error) {
                // не запомнится, но на этой странице тема сменится
            }
            apply(choice);
        }
    };
})();
