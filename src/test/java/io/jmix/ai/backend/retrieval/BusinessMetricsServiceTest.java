package io.jmix.ai.backend.retrieval;

import io.jmix.ai.backend.entity.VectorStoreEntity;
import io.jmix.ai.backend.vectorstore.VectorStoreRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.vectorstore.filter.Filter;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class BusinessMetricsServiceTest {

    private VectorStoreRepository vectorStoreRepository;
    private BusinessMetricsService service;

    @BeforeEach
    void setUp() {
        vectorStoreRepository = mock(VectorStoreRepository.class);
        service = new BusinessMetricsService(vectorStoreRepository);
        when(vectorStoreRepository.loadList(any(Filter.Expression.class), eq(0), eq(0)))
                .thenReturn(buildOrderEntities());
    }

    @Test
    void analyze_returnsSpecificClientTotal() {
        var result = service.analyze("Какова общая сумма заказов клиента ООО \"Альфа Трейд\"?");

        assertThat(result).isNotNull();
        assertThat(result.answer()).isEqualTo("Общая сумма заказов клиента ООО \"Альфа Трейд\": 365000 RUB.");
    }

    @Test
    void analyze_returnsTotalsForAllClientsWhenQueryIsBroad() {
        var result = service.analyze("дай сумму заказов клиентов");

        assertThat(result).isNotNull();
        assertThat(result.answer()).contains("ООО \"Альфа Трейд\": 365000 RUB.");
        assertThat(result.answer()).contains("ООО \"Бета Сервис\": 140000 RUB.");
        assertThat(result.answer()).contains("Итого по всем клиентам: 505000 RUB.");
    }

    @Test
    void analyze_returnsLargestOrderForClient() {
        var result = service.analyze("Какой номер самого крупного заказа клиента ООО \"Альфа Трейд\"?");

        assertThat(result).isNotNull();
        assertThat(result.answer()).isEqualTo("Самый крупный заказ клиента ООО \"Альфа Трейд\": AT-003, сумма 160000 RUB.");
    }

    @Test
    void analyze_returnsOrderCountForClient() {
        var result = service.analyze("Сколько заказов у клиента ООО \"Бета Сервис\"?");

        assertThat(result).isNotNull();
        assertThat(result.answer()).isEqualTo("У клиента ООО \"Бета Сервис\" 2 заказ(а/ов).");
    }

    @Test
    void analyze_handlesModelRephrasedOrderQueryWithoutTotalKeyword() {
        var result = service.analyze("Заказы клиента ООО \"Альфа Трейд\"");

        assertThat(result).isNotNull();
        assertThat(result.answer()).contains("ООО \"Альфа Трейд\": 3 заказ(а/ов), 365000 RUB.");
    }

    @Test
    void analyze_returnsNoDataForSpecificUnknownClient() {
        var result = service.analyze("Какая сумма заказов у клиента ООО \"Ромашка\"?");

        assertThat(result).isNotNull();
        assertThat(result.answer()).isEqualTo("В загруженных бизнес-документах не найдено заказов для указанного клиента.");
        assertThat(result.supportingDocuments()).isEmpty();
    }

    @Test
    void analyze_returnsNullForNonMetricsQuery() {
        var result = service.analyze("Расскажи про Jmix");

        assertThat(result).isNull();
    }

    @Test
    void analyze_supportingDocumentsMatchedOrders() {
        var result = service.analyze("Какова общая сумма заказов клиента ООО \"Альфа Трейд\"?");

        assertThat(result).isNotNull();
        assertThat(result.supportingDocuments()).hasSize(3);
    }

    @Test
    void analyze_supportsLegacyMetadataWithDocPathWithoutDocumentKind() {
        when(vectorStoreRepository.loadList(any(Filter.Expression.class), eq(0), eq(0)))
                .thenReturn(List.of(legacyOrderEntity("orders/alfa-trade/order-AT-001.txt", "AT-001", "ООО \"Альфа Трейд\"", 120000)));

        var result = service.analyze("Сколько заказов у клиента ООО \"Альфа Трейд\"?");

        assertThat(result).isNotNull();
        assertThat(result.answer()).isEqualTo("У клиента ООО \"Альфа Трейд\" 1 заказ(а/ов).");
    }

    private List<VectorStoreEntity> buildOrderEntities() {
        return List.of(
                orderEntity("orders/alfa-trade/order-AT-001.txt", "AT-001", "ООО \"Альфа Трейд\"", 120000),
                orderEntity("orders/alfa-trade/order-AT-002.txt", "AT-002", "ООО \"Альфа Трейд\"", 85000),
                orderEntity("orders/alfa-trade/order-AT-003.txt", "AT-003", "ООО \"Альфа Трейд\"", 160000),
                orderEntity("orders/beta-service/order-BS-101.txt", "BS-101", "ООО \"Бета Сервис\"", 60000),
                orderEntity("orders/beta-service/order-BS-102.txt", "BS-102", "ООО \"Бета Сервис\"", 80000)
        );
    }

    private VectorStoreEntity orderEntity(String path, String orderNumber, String client, int amount) {
        String content = """
                Business document path: %s
                File type: .txt

                Документ: Заказ клиента
                Номер заказа: %s
                Клиент: %s
                Валюта: RUB
                Итоговая сумма заказа: %d
                Статус: Подтвержден
                """.formatted(path, orderNumber, client, amount);

        String metadata = """
                {"type":"business-documents","documentKind":"order","documentPath":"%s",
                 "sourceCode":"business-documents-local","kb":"business-documents-demo",
                 "documentName":"%s"}
                """.formatted(path, path.substring(path.lastIndexOf('/') + 1));

        VectorStoreEntity entity = new VectorStoreEntity();
        entity.setContent(content);
        entity.setMetadata(metadata);
        return entity;
    }

    private VectorStoreEntity legacyOrderEntity(String path, String orderNumber, String client, int amount) {
        String content = """
                Business document path: %s
                File type: .txt

                Документ: Заказ клиента
                Номер заказа: %s
                Клиент: %s
                Итоговая сумма заказа: %d
                """.formatted(path, orderNumber, client, amount);

        String metadata = """
                {"type":"business-documents","docPath":"%s","source":"%s"}
                """.formatted(path, path);

        VectorStoreEntity entity = new VectorStoreEntity();
        entity.setContent(content);
        entity.setMetadata(metadata);
        return entity;
    }
}
