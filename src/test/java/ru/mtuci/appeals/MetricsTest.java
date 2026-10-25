package ru.mtuci.appeals;

import org.junit.jupiter.api.Test;
import ru.mtuci.appeals.api.ApiModels.AppealDetailResponse;
import ru.mtuci.appeals.api.ApiModels.ChangeStatusRequest;
import ru.mtuci.appeals.api.ApiModels.ResolveAppealRequest;
import ru.mtuci.appeals.domain.AppealOutcome;
import ru.mtuci.appeals.domain.AppealStatus;

import java.time.Duration;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * Метрики для Prometheus (решение 47): кроме технических, подсистема отдаёт показатели
 * обращений. Их видно в том же формате, в каком их забирает Prometheus.
 */
class MetricsTest extends IntegrationTest {

    @Test
    void appealLifecycleIsVisibleInPrometheusMetrics() {
        UUID appeal = createAppeal(cascoClaim()).id();
        awaitRouted(appeal);
        as(CLAIMS_SPECIALIST).postForObject("/api/appeals/{id}/status",
                new ChangeStatusRequest(AppealStatus.IN_PROGRESS), AppealDetailResponse.class, appeal);
        as(CLAIMS_SPECIALIST).postForObject("/api/appeals/{id}/resolve",
                new ResolveAppealRequest(AppealOutcome.SATISFIED,
                        "Ремонт крыла согласован, направление на СТОА отправлено на вашу почту.", null, null),
                AppealDetailResponse.class, appeal);

        // счётчики пишутся после фиксации транзакции, маршрутизация асинхронная: ждём
        await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> assertThat(metrics())
                .contains("appeals_registered_total{", "channel=\"CABINET\"", "contract=\"linked\"")
                .contains("appeals_routing_lag_seconds_count{", "mode=\"auto\"")
                .contains("appeals_resolved_total{", "in_time=\"true\"", "outcome=\"SATISFIED\""));
        assertThat(metrics())
                .contains("appeals_outbox_pending", "appeals_contracts_circuit_state", "appeals_open_legal_overdue")
                // время ответа API с гистограммой: из неё Prometheus считает p95
                .contains("http_server_requests_seconds_bucket{");
    }

    private String metrics() {
        return rest.getForObject("/actuator/prometheus", String.class);
    }
}
