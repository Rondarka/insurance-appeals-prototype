"""
Эксперимент: отказ брокера и потребителя (решения 3, 4 и 5, docs/experiments.md).

    python experiments/broker_outage.py

1. Брокер выключен. Обращения регистрируются, события ждут в outbox; после включения
   брокера каждое обращение доходит до отдела ровно один раз.
2. Ядовитое сообщение. Событие, которое потребитель не может разобрать, после трёх
   попыток уходит в очередь отказов и не мешает остальным; обычное обращение после него
   маршрутизируется как обычно.
"""
import json
import time
import uuid

from stand import call, claim, compose, rabbit, save, sql, wait_until, appeal_status

DEAD = "/queues/appeals/appeal.events.dead"


def dead_count():
    """Сообщений в очереди отказов. Счётчики RabbitMQ обновляются раз в несколько секунд,
    сразу после перезапуска брокера их ещё нет."""
    for _ in range(30):
        queue = rabbit("GET", DEAD)
        if "messages" in queue:
            return queue["messages"]
        time.sleep(1)
    return 0


def broker_down():
    compose("stop", "rabbitmq")
    try:
        registered = []
        for _ in range(5):
            status, body, ms = call("POST", "/api/appeals", claim("брокер выключен"))
            registered.append({"status": status, "ms": ms, "id": body.get("id"), "appealStatus": body.get("status")})
        ids = [item["id"] for item in registered if item["id"]]
        quoted = ",".join(f"'{i}'" for i in ids)
        waiting = int(sql(f"select count(*) from outbox_event where published_at is null "
                          f"and appeal_id in ({quoted})"))
    finally:
        compose("start", "rabbitmq")
    routed_after = wait_until(lambda: all(appeal_status(i) != "PENDING_ROUTING" for i in ids), 180)
    # ровно один раз: у каждого обращения одна запись о маршрутизации
    routed_once = sql(f"select count(*) from appeal_history where action = 'ROUTED' and appeal_id in ({quoted})")
    return {"registered": registered, "outboxWaiting": waiting, "routedAfterBrokerStartS": routed_after,
            "routedEntries": int(routed_once), "appeals": len(ids)}


def poison_message():
    before = dead_count()
    payload = {"eventId": "не-идентификатор", "eventType": "APPEAL_CREATED", "appealId": str(uuid.uuid4())}
    rabbit("POST", "/exchanges/appeals/appeal.events/publish", {
        "properties": {"content_type": "application/json"},
        "routing_key": "appeal.created.CLAIM.AUTO",
        "payload": json.dumps(payload, ensure_ascii=False), "payload_encoding": "string"})
    published = time.perf_counter()
    in_dead_after = wait_until(lambda: rabbit("GET", DEAD).get("messages", 0) > before, 30, step_s=0.5)
    after = dead_count()
    # обычное обращение после ядовитого события
    status, body, _ = call("POST", "/api/appeals", claim("после ядовитого события"))
    normal_routed_after = wait_until(lambda: appeal_status(body["id"]) != "PENDING_ROUTING", 60, step_s=0.5)
    dead = rabbit("POST", DEAD + "/get", {"count": 10, "ackmode": "ack_requeue_true", "encoding": "auto"})
    reasons = sorted({message["routing_key"] for message in dead})
    # очередь отказов чистим: в стенде это результат эксперимента, а не настоящий сбой
    rabbit("DELETE", DEAD + "/contents")
    return {"deadBefore": before, "deadAfter": after, "inDeadAfterS": in_dead_after,
            "deadRoutingKeys": reasons, "normalAppealStatus": status, "normalRoutedAfterS": normal_routed_after,
            "elapsedS": round(time.perf_counter() - published, 1)}


def main():
    result = {"brokerDown": broker_down()}
    print(json.dumps(result, ensure_ascii=False, indent=2))
    result["poisonMessage"] = poison_message()
    print(json.dumps(result, ensure_ascii=False, indent=2))
    print("Сохранено:", save("broker-outage", result))


if __name__ == "__main__":
    main()
