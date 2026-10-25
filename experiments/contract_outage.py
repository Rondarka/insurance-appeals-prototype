"""
Эксперимент: отказ учётной системы договоров (решения 29 и 30, docs/experiments.md).

Запускается на поднятом стенде (docker compose up), нужен только Python 3 без пакетов:

    python experiments/contract_outage.py

Два вида отказа: учётная система отвечает медленно (в заглушке включается задержка 5 с)
и учётная система выключена (контейнер заглушки остановлен). В каждом меряется, сколько
клиент ждёт кабинет до и после размыкания цепи, принимается ли обращение, доходит ли оно
до отдела и через сколько договор привязывается после восстановления.

Обращения создаются от имени Петра, чтобы не засорять демонстрационный кабинет Анны.
Результаты печатаются и сохраняются в experiments/results/.
"""
import json
import statistics
import urllib.request

from stand import STUB, appeal_status, call, claim, compose, save, wait_until


def stub_delay(ms):
    """Задержка всех ответов заглушки учётной системы (настройка WireMock)."""
    request = urllib.request.Request(STUB + "/__admin/settings", method="POST",
                                     data=json.dumps({"fixedDelay": ms}).encode(),
                                     headers={"Content-Type": "application/json"})
    urllib.request.urlopen(request).close()


def cabinet_series(count):
    """Клиент открывает кабинет count раз подряд: время ответа и признак резервного режима."""
    series = []
    for _ in range(count):
        status, me, ms = call("GET", "/api/me")
        series.append({"ms": ms, "status": status, "contractsUnavailable": me.get("contractsUnavailable")})
    return series


def register(count, degraded, label):
    created = []
    for _ in range(count):
        status, body, ms = call("POST", "/api/appeals", claim(label, degraded))
        created.append({"ms": ms, "status": status, "id": body.get("id"), "contractLink": body.get("contractLink"),
                        "message": body.get("message")})
    return created


def linked(ids):
    return all(call("GET", f"/api/appeals/{i}")[1].get("contractLink") == "LINKED" for i in ids)


def cabinet_recovered():
    return call("GET", "/api/me")[1].get("contractsUnavailable") is False


def outage(kind, start, stop):
    """Один вид отказа: включить, замерить, выключить, замерить восстановление."""
    start()
    try:
        cabinet = cabinet_series(12)
        accepted = register(3, degraded=True, label=f"{kind}, резервный режим")
        ids = [item["id"] for item in accepted if item["id"]]
        routed_after = wait_until(lambda: all(appeal_status(i) != "PENDING_ROUTING" for i in ids), 60, step_s=2)
    finally:
        stop()
    cabinet_back = wait_until(cabinet_recovered, 120, step_s=2)
    linked_after = wait_until(lambda: linked(ids), 600, step_s=5)
    return {
        "cabinet": cabinet,
        "registered": accepted,
        "routedWithinS": routed_after,
        "cabinetRecoveredAfterS": cabinet_back,
        # от восстановления учётной системы: сначала ждали кабинет, потом привязку
        "contractsLinkedAfterS": None if linked_after is None else round(linked_after + (cabinet_back or 0), 1),
    }


def summary(series):
    values = [item["ms"] for item in series]
    return {"median": statistics.median(values), "max": max(values), "min": min(values)}


def main():
    stub_delay(0)
    result = {}

    print("1. Норма: учётная система отвечает")
    result["baseline"] = {"cabinet": cabinet_series(10), "registered": register(3, False, "норма")}

    print("2. Учётная система отвечает медленно (5 с)")
    result["slow"] = outage("медленная учётная система", lambda: stub_delay(5000), lambda: stub_delay(0))

    print("3. Учётная система выключена")
    result["down"] = outage("учётная система выключена",
                            lambda: compose("stop", "contracts-stub"),
                            lambda: compose("start", "contracts-stub"))

    path = save("contract-outage", result)
    for name in ["baseline", "slow", "down"]:
        part = result[name]
        print(f"\n[{name}] кабинет, мс: {summary(part['cabinet'])}")
        print("  по порядку:", [(item["ms"], item["contractsUnavailable"]) for item in part["cabinet"]])
        print("  регистрация:", [(item["status"], item["ms"], item["contractLink"]) for item in part["registered"]])
        for key in ["routedWithinS", "cabinetRecoveredAfterS", "contractsLinkedAfterS"]:
            if key in part:
                print(f"  {key}: {part[key]}")
    print("\nСохранено:", path)


if __name__ == "__main__":
    main()
